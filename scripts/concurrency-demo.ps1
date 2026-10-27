# Two customers try to book the SAME seat at the same moment: exactly one 201, the other 409.  Run: .\scripts\concurrency-demo.ps1
$ErrorActionPreference = "Stop"
$GW = if ($env:GW) { $env:GW } else { "http://localhost:8080" }
$KC = if ($env:KC) { $env:KC } else { "http://localhost:8180/realms/tickethub/protocol/openid-connect/token" }

function Get-Token($user, $pass) {
    (Invoke-RestMethod -Method Post -Uri $KC -Body @{ client_id = "tickethub-web"; grant_type = "password"; username = $user; password = $pass }).access_token
}
$show = (Invoke-RestMethod "$GW/shows").content[0].id
$seat = (Invoke-RestMethod "$GW/shows/$show/seats" | Where-Object { $_.status -eq "AVAILABLE" } | Select-Object -Last 1).seatId
$t1 = Get-Token "customer1" "customer123"; $t2 = Get-Token "customer2" "customer123"
Write-Host "Racing for seat $seat of show $show ..."

$work = {
    param($gw, $token, $show, $seat)
    try {
        Invoke-RestMethod -Method Post -Uri "$gw/bookings" -Headers @{ Authorization = "Bearer $token" } -ContentType "application/json" `
            -Body (@{ showId = $show; seatIds = @($seat) } | ConvertTo-Json) | Out-Null
        "201 CREATED"
    } catch {
        "{0} {1}" -f [int]$_.Exception.Response.StatusCode, $_.ErrorDetails.Message
    }
}
$j1 = Start-Job -ScriptBlock $work -ArgumentList $GW, $t1, $show, $seat
$j2 = Start-Job -ScriptBlock $work -ArgumentList $GW, $t2, $show, $seat
Wait-Job $j1, $j2 | Out-Null
Write-Host "customer1 -> $(Receive-Job $j1)"
Write-Host "customer2 -> $(Receive-Job $j2)"
Remove-Job $j1, $j2
