# End-to-end happy path through the API gateway (PowerShell). Run from the repo root:  .\scripts\demo.ps1
$ErrorActionPreference = "Stop"
$GW   = if ($env:GW)   { $env:GW }   else { "http://localhost:8080" }
$KC   = if ($env:KC)   { $env:KC }   else { "http://localhost:8180/realms/tickethub/protocol/openid-connect/token" }
$MAIL = if ($env:MAIL) { $env:MAIL } else { "http://localhost:8025" }

function Get-Token($user, $pass) {
    (Invoke-RestMethod -Method Post -Uri $KC -Body @{ client_id = "tickethub-web"; grant_type = "password"; username = $user; password = $pass }).access_token
}
function Get-Status($scriptBlock) {
    try { & $scriptBlock | Out-Null; return 200 } catch { return [int]$_.Exception.Response.StatusCode }
}

Write-Host "== 1. Browse (public)"
$events = Invoke-RestMethod "$GW/events"
$events.content | ForEach-Object { "{0}  {1}" -f $_.id, $_.title }
$show = (Invoke-RestMethod "$GW/shows").content[0].id
Write-Host "first show id: $show"

Write-Host "== 2. Seat map"
$seats = Invoke-RestMethod "$GW/shows/$show/seats"
$seats | Select-Object -First 6 | ForEach-Object { "{0}  {1}  {2}" -f $_.seatId, $_.label, $_.status }
$s1 = $seats[0].seatId; $s2 = $seats[1].seatId

Write-Host "== 3. Book seats $s1,$s2 as customer1"
$token = Get-Token "customer1" "customer123"
$h = @{ Authorization = "Bearer $token"; "Idempotency-Key" = "demo-" + [DateTimeOffset]::UtcNow.ToUnixTimeSeconds() }
$body = @{ showId = $show; seatIds = @($s1, $s2) } | ConvertTo-Json
$booking = Invoke-RestMethod -Method Post -Uri "$GW/bookings" -Headers $h -ContentType "application/json" -Body $body
$booking | ConvertTo-Json -Depth 5

Write-Host "== 4. Pay (amount comes from the booking, not from us)"
$pay = Invoke-RestMethod -Method Post -Uri "$GW/payments" -Headers @{ Authorization = "Bearer $token" } -ContentType "application/json" -Body (@{ bookingId = $booking.id } | ConvertTo-Json)
$pay | ConvertTo-Json -Depth 5

Write-Host "== 5. Booking status"
Invoke-RestMethod "$GW/bookings/$($booking.id)" -Headers @{ Authorization = "Bearer $token" } | ConvertTo-Json -Depth 5

Write-Host "== 6. Another customer cannot see it (expect 404)"
$token2 = Get-Token "customer2" "customer123"
$code = Get-Status { Invoke-RestMethod "$GW/bookings/$($booking.id)" -Headers @{ Authorization = "Bearer $token2" } }
Write-Host "customer2 -> HTTP $code"

Write-Host "== 7. Confirmation email (Mailpit)"
Start-Sleep -Seconds 3
(Invoke-RestMethod "$MAIL/api/v1/messages").messages | Select-Object -First 3 | ForEach-Object { "{0}  ->  {1}" -f $_.Subject, ($_.To.Address -join ",") }
Write-Host "Open $MAIL to read it."
