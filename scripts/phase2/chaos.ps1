# Scenario 14: failure drills against the running stack (Windows PowerShell).
#   .\scripts\phase2\chaos.ps1 <drill>
# drills: cancel-after-pay | late-payment | kafka-down | mail-down | duplicate-webhook | silent-capture | restart-booking | poison
param([Parameter(Mandatory = $true)][string]$Drill)
$ErrorActionPreference = "Stop"
$GW   = "http://localhost:8080"
$KC   = "http://localhost:8180/realms/tickethub/protocol/openid-connect/token"
$MAIL = "http://localhost:8025"
$SECRET = if ($env:PAYMENT_WEBHOOK_SECRET) { $env:PAYMENT_WEBHOOK_SECRET } else { "dev-webhook-secret" }

function Token($u, $p) { (Invoke-RestMethod -Method Post -Uri $KC -Body @{ client_id = "tickethub-web"; grant_type = "password"; username = $u; password = $p }).access_token }
function H($t) { @{ Authorization = "Bearer $t" } }
function Json($o) { $o | ConvertTo-Json -Depth 5 }
function Mode($mode) { Invoke-RestMethod -Method Post -Uri "$GW/payments/simulator/mode?mode=$mode" -Headers (H $script:admin) | Out-Null; Write-Host "simulator mode: $mode" }
function Book($token) {
    $show = (Invoke-RestMethod "$GW/shows").content[0].id
    $seat = (Invoke-RestMethod "$GW/shows/$show/seats" | Where-Object { $_.status -eq "AVAILABLE" } | Select-Object -First 1).seatId
    $b = Invoke-RestMethod -Method Post -Uri "$GW/bookings" -Headers (H $token) -ContentType "application/json" -Body (Json @{ showId = $show; seatIds = @($seat) })
    Write-Host "booking $($b.id) created (seat $seat)"; $b
}
function Pay($token, $bookingId) {
    $p = Invoke-RestMethod -Method Post -Uri "$GW/payments" -Headers (H $token) -ContentType "application/json" -Body (Json @{ bookingId = $bookingId })
    Write-Host "payment $($p.id) -> $($p.status)"; $p
}
function Wait-Booking($token, $id, $status, $seconds = 60) {
    for ($i = 0; $i -lt $seconds; $i++) {
        $b = Invoke-RestMethod "$GW/bookings/$id" -Headers (H $token)
        if ($b.status -eq $status) { Write-Host "booking is $status (refund: $($b.refundState))"; return $b }
        Start-Sleep -Seconds 1
    }
    Write-Warning "booking did not reach $status within $seconds s"; Invoke-RestMethod "$GW/bookings/$id" -Headers (H $token)
}
function Timeline($token, $id) { (Invoke-RestMethod "$GW/bookings/$id/timeline" -Headers (H $token)).entries | Format-Table at, from, to, reason -AutoSize }
function Mails() { (Invoke-RestMethod "$MAIL/api/v1/messages").total }
function Sign($body) { $h = New-Object System.Security.Cryptography.HMACSHA256; $h.Key = [Text.Encoding]::UTF8.GetBytes($SECRET); -join ($h.ComputeHash([Text.Encoding]::UTF8.GetBytes($body)) | ForEach-Object { $_.ToString("x2") }) }
function Webhook($ref, $status, $eventId) {
    $body = '{"eventId":"' + $eventId + '","providerReference":"' + $ref + '","providerPaymentId":"pay_demo","status":"' + $status + '"}'
    Invoke-RestMethod -Method Post -Uri "$GW/payments/webhook" -ContentType "application/json" -Headers @{ "X-Simulator-Signature" = (Sign $body) } -Body $body | Out-Null
    Write-Host "webhook $eventId ($status) sent"
}

$script:admin = Token "admin" "admin123"
$cust = Token "customer1" "customer123"
$mailsBefore = Mails
Mode "SUCCESS"

switch ($Drill) {
    "cancel-after-pay" {
        $b = Book $cust; Pay $cust $b.id | Out-Null; Wait-Booking $cust $b.id "CONFIRMED" | Out-Null
        Invoke-RestMethod -Method Post -Uri "$GW/bookings/$($b.id)/cancel" -Headers (H $cust) | Out-Null
        Wait-Booking $cust $b.id "CANCELLED" | Out-Null; Timeline $cust $b.id
        Write-Host "EXPECT: CANCELLED with refundState REFUNDED; seat available again."
    }
    "late-payment" {
        Mode "PENDING"
        $b = Book $cust; $p = Pay $cust $b.id
        Invoke-RestMethod -Method Post -Uri "$GW/bookings/$($b.id)/cancel" -Headers (H $cust) | Out-Null
        Write-Host "booking cancelled while the payment was still pending; now the provider reports success (too late)"
        Invoke-RestMethod -Method Post -Uri "$GW/payments/simulator/complete?paymentId=$($p.id)&outcome=SUCCESS" -Headers (H $script:admin) | Out-Null
        Wait-Booking $cust $b.id "REJECTED" | Out-Null; Start-Sleep -Seconds 3
        (Invoke-RestMethod "$GW/bookings/$($b.id)" -Headers (H $cust)) | Select-Object status, refundState | Format-List; Timeline $cust $b.id
        Write-Host "EXPECT: REJECTED with refundState REFUNDED (money returned), timeline explains why."
    }
    "kafka-down" {
        docker compose stop kafka | Out-Null; Write-Host "Kafka STOPPED"
        $b = Book $cust; Pay $cust $b.id | Out-Null; Start-Sleep -Seconds 5
        Write-Host "payment succeeded but the booking cannot hear about it yet. Payment outbox:"; Write-Host "  GET http://localhost:8083/admin/outbox (ADMIN token) -> pending > 0"
        docker compose start kafka | Out-Null; Write-Host "Kafka STARTED"
        Wait-Booking $cust $b.id "CONFIRMED" 120 | Out-Null; Start-Sleep -Seconds 5
        Write-Host "emails before: $mailsBefore, after: $(Mails)"; Write-Host "EXPECT: booking CONFIRMED after Kafka returns, exactly one new email."
    }
    "mail-down" {
        docker compose stop mailpit | Out-Null; Write-Host "Mail server STOPPED"
        $b = Book $cust; Pay $cust $b.id | Out-Null; Wait-Booking $cust $b.id "CONFIRMED" | Out-Null
        Write-Host "booking is CONFIRMED even though email cannot be sent (retries run on the retry topics)."; Start-Sleep -Seconds 20
        docker compose start mailpit | Out-Null; Write-Host "Mail server STARTED; waiting for the retry..."; Start-Sleep -Seconds 60
        Write-Host "emails now: $(Mails) (start of drill: $mailsBefore)"; Write-Host "EXPECT: exactly one email for this booking after the mail server is back."
    }
    "duplicate-webhook" {
        Mode "PENDING"; $b = Book $cust; $p = Pay $cust $b.id
        Webhook $p.providerReference "SUCCESS" "dup-1"; Webhook $p.providerReference "SUCCESS" "dup-1"
        Webhook $p.providerReference "AUTHORIZED" "late-auth"; Webhook $p.providerReference "FAILED" "late-fail"
        Wait-Booking $cust $b.id "CONFIRMED" | Out-Null; Start-Sleep -Seconds 4
        Write-Host "emails before: $mailsBefore, after: $(Mails)"; Timeline $cust $b.id
        Write-Host "EXPECT: CONFIRMED once, one email, the late AUTHORIZED/FAILED callbacks ignored."
    }
    "silent-capture" {
        Mode "PENDING"; $b = Book $cust; $p = Pay $cust $b.id
        Invoke-RestMethod -Method Post -Uri "$GW/payments/simulator/silent-capture?paymentId=$($p.id)" -Headers (H $script:admin) | Out-Null
        Write-Host "provider captured the money, but no callback was sent. Booking stays PENDING_PAYMENT."
        Write-Host "Run reconciliation on payment-service (needs min-age: set payment.reconcile.min-age-seconds=0 or wait 2 minutes):"
        $r = Invoke-RestMethod -Method Post -Uri "http://localhost:8083/admin/reconciliation/run" -Headers (H $script:admin); Json $r
        Wait-Booking $cust $b.id "CONFIRMED" | Out-Null
        Write-Host "EXPECT: finding UNRESOLVED_RESOLVED (FIXED) and the booking CONFIRMED."
    }
    "restart-booking" {
        $b = Book $cust; Pay $cust $b.id | Out-Null
        docker compose restart booking-service | Out-Null; Write-Host "booking-service restarted right after payment"
        Wait-Booking $cust $b.id "CONFIRMED" 180 | Out-Null; Timeline $cust $b.id
        Write-Host "EXPECT: CONFIRMED once. The payment event waited in Kafka; the inbox prevents a double apply."
    }
    "poison" { & "$PSScriptRoot\poison.ps1" }
    default { Write-Host "Unknown drill '$Drill'" }
}
