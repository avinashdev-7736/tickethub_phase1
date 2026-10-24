#!/usr/bin/env bash
# Two customers try to book the SAME seat at the same moment. Exactly one must win (201), the other gets 409.
set -euo pipefail
GW=${GW:-http://localhost:8080}
KC=${KC:-http://localhost:8180/realms/tickethub/protocol/openid-connect/token}
token() { curl -s -d "client_id=tickethub-web" -d "grant_type=password" -d "username=$1" -d "password=$2" "$KC" | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])"; }

SHOW=$(curl -s "$GW/shows" | python3 -c "import sys,json; print(json.load(sys.stdin)['content'][0]['id'])")
SEAT=$(curl -s "$GW/shows/$SHOW/seats" | python3 -c "import sys,json; d=json.load(sys.stdin); print([s['seatId'] for s in d if s['status']=='AVAILABLE'][-1])")
T1=$(token customer1 customer123); T2=$(token customer2 customer123)
echo "Racing for seat $SEAT of show $SHOW ..."
( curl -s -o /tmp/r1.json -w "customer1 -> HTTP %{http_code}\n" -X POST "$GW/bookings" -H "Authorization: Bearer $T1" -H "Content-Type: application/json" -d "{\"showId\":$SHOW,\"seatIds\":[$SEAT]}" ) &
( curl -s -o /tmp/r2.json -w "customer2 -> HTTP %{http_code}\n" -X POST "$GW/bookings" -H "Authorization: Bearer $T2" -H "Content-Type: application/json" -d "{\"showId\":$SHOW,\"seatIds\":[$SEAT]}" ) &
wait
echo "--- customer1 body:"; cat /tmp/r1.json; echo; echo "--- customer2 body:"; cat /tmp/r2.json; echo
