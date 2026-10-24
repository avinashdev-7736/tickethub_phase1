#!/usr/bin/env bash
# End-to-end happy path through the API gateway: browse -> seats -> book -> pay -> confirmed -> email.
# Needs: curl, python3. Stack running via `docker compose up -d --build`.
set -euo pipefail
GW=${GW:-http://localhost:8080}
KC=${KC:-http://localhost:8180/realms/tickethub/protocol/openid-connect/token}
MAIL=${MAIL:-http://localhost:8025}

token() {
  curl -s -d "client_id=tickethub-web" -d "grant_type=password" -d "username=$1" -d "password=$2" "$KC" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])"
}
json() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }

echo "== 1. Browse (public, no token)"
curl -s "$GW/events" | json "[ (e['id'], e['title']) for e in d['content'] ]"
SHOW=$(curl -s "$GW/shows" | json "d['content'][0]['id']")
echo "first show id: $SHOW"

echo "== 2. Seat map"
curl -s "$GW/shows/$SHOW/seats" | json "[ (s['seatId'], s['label'], s['status']) for s in d[:6] ]"
S1=$(curl -s "$GW/shows/$SHOW/seats" | json "d[0]['seatId']")
S2=$(curl -s "$GW/shows/$SHOW/seats" | json "d[1]['seatId']")

echo "== 3. Book seats $S1,$S2 as customer1"
TOKEN=$(token customer1 customer123)
BOOKING=$(curl -s -X POST "$GW/bookings" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-$(date +%s)" -d "{\"showId\":$SHOW,\"seatIds\":[$S1,$S2]}")
echo "$BOOKING"
BID=$(echo "$BOOKING" | json "d['id']")

echo "== 4. Pay (amount comes from the booking, not from us)"
PAY=$(curl -s -X POST "$GW/payments" -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d "{\"bookingId\":\"$BID\"}")
echo "$PAY"

echo "== 5. Booking status"
curl -s "$GW/bookings/$BID" -H "Authorization: Bearer $TOKEN"; echo

echo "== 6. Another customer cannot see it (expect 404)"
TOKEN2=$(token customer2 customer123)
curl -s -o /dev/null -w "customer2 -> HTTP %{http_code}\n" "$GW/bookings/$BID" -H "Authorization: Bearer $TOKEN2"

echo "== 7. Confirmation email (Mailpit)"
sleep 3
curl -s "$MAIL/api/v1/messages" | json "[ (m['Subject'], [t['Address'] for t in m['To']]) for m in d['messages'][:3] ]"
echo "Open $MAIL to read it."
