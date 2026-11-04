#!/usr/bin/env bash
set -Eeuo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
TOKEN_URL="${TOKEN_URL:-http://127.0.0.1:8090/dev/token}"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-hotelbooking-postgres}"
HTTP_BODY=""
HTTP_CODE=""
BOOKING_ID=""
REQUEST_ID=""
RACE_ID=""

fail() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "PASS: $*"; }

request() {
  local method="$1" path="$2" token="${3:-}" data="${4:-}" raw
  local -a args=(-sS -X "$method" -w $'\n%{http_code}')
  if [[ -n "$token" ]]; then args+=(-H "Authorization: Bearer $token"); fi
  if [[ -n "$data" ]]; then args+=(-H 'Content-Type: application/json' --data "$data"); fi
  raw="$(curl "${args[@]}" "$BASE_URL$path")" || fail "Request failed: $method $path"
  HTTP_CODE="${raw##*$'\n'}"
  HTTP_BODY="${raw%$'\n'*}"
}

expect_status() {
  local expected="$1" label="$2"
  [[ "$HTTP_CODE" == "$expected" ]] || fail "$label: expected HTTP $expected, got $HTTP_CODE; response: $HTTP_BODY"
  pass "$label (HTTP $HTTP_CODE)"
}

json_value() {
  python3 -c 'import json,sys; value=json.load(sys.stdin); print(value[sys.argv[1]])' "$1" <<<"$2"
}

db_scalar() {
  docker exec "$POSTGRES_CONTAINER" psql -X -At -v ON_ERROR_STOP=1 -U hotelbooking -d hotelbooking -c "$1"
}

cleanup_test_rows() {
  if [[ -n "$BOOKING_ID" || -n "$REQUEST_ID" || -n "$RACE_ID" ]]; then
    echo "Cleaning up workflow-created booking/request/notification rows..."
    docker exec -i "$POSTGRES_CONTAINER" psql -X -v ON_ERROR_STOP=1 -U hotelbooking -d hotelbooking \
      -v booking_id="${BOOKING_ID:-0}" -v race_id="${RACE_ID:-0}" -v request_id="${REQUEST_ID:-0}" <<'SQL' >/dev/null
BEGIN;
UPDATE room_inventory i
SET sold = i.sold - b.room_count
FROM booking b
WHERE b.id IN (:'booking_id'::bigint, :'race_id'::bigint)
  AND b.status IN ('PENDING_PAYMENT', 'CONFIRMED')
  AND i.tenant_id = b.tenant_id
  AND i.room_type_id = b.room_type_id
  AND i.stay_date >= b.check_in
  AND i.stay_date < b.check_out
  AND i.sold >= b.room_count;
DELETE FROM notification
WHERE source_event_id IN (SELECT id FROM notification_outbox WHERE reference_id = :'request_id'::bigint);
DELETE FROM notification_outbox WHERE reference_id = :'request_id'::bigint;
DELETE FROM date_change_request WHERE id = :'request_id'::bigint;
DELETE FROM booking_reschedule WHERE booking_id IN (:'booking_id'::bigint, :'race_id'::bigint);
DELETE FROM booking WHERE id IN (:'booking_id'::bigint, :'race_id'::bigint);
COMMIT;
SQL
  fi
}
trap cleanup_test_rows EXIT

echo 'Creating temporary local USER/ADMIN tokens...'
USER1="$(curl -fsS -H 'Content-Type: application/json' -d '{"role":"USER","tenantId":1}' "$TOKEN_URL" | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')"
ADMIN1="$(curl -fsS -H 'Content-Type: application/json' -d '{"role":"ADMIN","tenantId":1}' "$TOKEN_URL" | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')"
USER2="$(curl -fsS -H 'Content-Type: application/json' -d '{"role":"USER","tenantId":2}' "$TOKEN_URL" | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')"

CATALOG_DATE="$(db_scalar "SELECT stay_date FROM room_inventory WHERE tenant_id=1 AND room_type_id=1 AND stay_date>=CURRENT_DATE+7 AND total-sold>=1 ORDER BY stay_date LIMIT 1;")"
[[ -n "$CATALOG_DATE" ]] || fail 'No future room_type 1 inventory date is available.'
CATALOG_CHECKOUT="$(db_scalar "SELECT (DATE '$CATALOG_DATE' + 1)::text;")"

request GET /api/hotels
expect_status 401 'Gateway rejects missing JWT'

request GET /api/hotels "$USER1"
expect_status 200 'Tenant 1 can list hotels'
python3 -c 'import json,sys; ids=sorted(row["id"] for row in json.load(sys.stdin)); assert ids == [1,3], ids' <<<"$HTTP_BODY" \
  || fail "Tenant 1 hotel list was not [1, 3]: $HTTP_BODY"
pass 'Tenant 1 sees only hotels 1 and 3'

request GET /api/hotels "$USER2"
expect_status 200 'Tenant 2 can list hotels'
python3 -c 'import json,sys; ids=sorted(row["id"] for row in json.load(sys.stdin)); assert ids == [2], ids' <<<"$HTTP_BODY" \
  || fail "Tenant 2 hotel list was not [2]: $HTTP_BODY"
pass 'Tenant 2 sees only hotel 2'

request GET "/api/hotels/1/availability?checkIn=$CATALOG_DATE&checkOut=$CATALOG_CHECKOUT" "$USER2"
expect_status 404 'Cross-tenant hotel availability is hidden'

request GET "/api/admin/hotels/1/inventory?checkIn=$CATALOG_DATE&checkOut=$CATALOG_CHECKOUT" "$USER1"
expect_status 403 'USER cannot read admin inventory'
request GET "/api/admin/hotels/1/inventory?checkIn=$CATALOG_DATE&checkOut=$CATALOG_CHECKOUT" "$ADMIN1"
expect_status 200 'ADMIN can read inventory'

RACE_DATE="$(db_scalar "SELECT stay_date FROM room_inventory WHERE tenant_id=1 AND room_type_id=5 AND stay_date >= CURRENT_DATE + 7 AND total - sold >= 1 ORDER BY stay_date LIMIT 1;")"
[[ -n "$RACE_DATE" ]] || fail 'No future available room_type 5 inventory row found.'
RACE_DIR="$(mktemp -d)"
trap 'rm -rf "$RACE_DIR"; cleanup_test_rows' EXIT
RACE_CHECKOUT="$(db_scalar "SELECT (DATE '$RACE_DATE' + 1)::text;")"
RACE_BODY="{\"roomTypeId\":5,\"checkIn\":\"$RACE_DATE\",\"checkOut\":\"$RACE_CHECKOUT\",\"roomCount\":1}"
for i in {1..6}; do
  (curl -sS -X POST "$BASE_URL/api/bookings" -H "Authorization: Bearer $USER1" -H 'Content-Type: application/json' \
      --data "$RACE_BODY" -w $'\n%{http_code}' >"$RACE_DIR/$i") &
done
wait
RACE_201=0
RACE_409=0
for file in "$RACE_DIR"/*; do
  code="$(tail -n 1 "$file")"
  body="$(sed '$d' "$file")"
  case "$code" in
    201)
      RACE_201=$((RACE_201 + 1))
      RACE_ID="$(json_value id "$body")"
      ;;
    409) RACE_409=$((RACE_409 + 1));;
    *) fail "Concurrent booking returned HTTP $code: $body";;
  esac
done
[[ "$RACE_201" == 1 && "$RACE_409" == 5 ]] || fail "Expected 1x201 + 5x409, got ${RACE_201}x201 + ${RACE_409}x409"
pass 'Concurrent booking does not oversell (one created, five conflicts)'
RACE_SOLD="$(db_scalar "SELECT sold FROM room_inventory WHERE tenant_id=1 AND room_type_id=5 AND stay_date=DATE '$RACE_DATE';")"
[[ "$RACE_SOLD" == 1 ]] || fail "Expected room_type 5 sold=1 during the race, got $RACE_SOLD"
request POST "/api/bookings/$RACE_ID/cancel" "$USER1"
expect_status 200 'Cancel releases the race-test inventory'
RACE_SOLD="$(db_scalar "SELECT sold FROM room_inventory WHERE tenant_id=1 AND room_type_id=5 AND stay_date=DATE '$RACE_DATE';")"
[[ "$RACE_SOLD" == 0 ]] || fail "Expected room_type 5 sold=0 after cancellation, got $RACE_SOLD"
pass 'Database sold count returns to zero after cancellation'

FLOW_START="$(db_scalar "SELECT i.stay_date FROM room_inventory i JOIN room_inventory i1 ON i1.tenant_id=i.tenant_id AND i1.room_type_id=i.room_type_id AND i1.stay_date=i.stay_date+1 JOIN room_inventory i4 ON i4.tenant_id=i.tenant_id AND i4.room_type_id=i.room_type_id AND i4.stay_date=i.stay_date+4 JOIN room_inventory i5 ON i5.tenant_id=i.tenant_id AND i5.room_type_id=i.room_type_id AND i5.stay_date=i.stay_date+5 WHERE i.tenant_id=1 AND i.room_type_id=1 AND i.stay_date>=CURRENT_DATE+7 AND i.total-i.sold>=1 AND i1.total-i1.sold>=1 AND i4.total-i4.sold>=1 AND i5.total-i5.sold>=1 ORDER BY i.stay_date LIMIT 1;")"
[[ -n "$FLOW_START" ]] || fail 'No two-night room_type 1 availability with a free two-night reschedule window found.'
FLOW_END="$(db_scalar "SELECT (DATE '$FLOW_START' + 2)::text;")"
NEW_START="$(db_scalar "SELECT (DATE '$FLOW_START' + 4)::text;")"
NEW_END="$(db_scalar "SELECT (DATE '$FLOW_START' + 6)::text;")"

request POST /api/bookings "$USER1" "{\"roomTypeId\":1,\"checkIn\":\"$FLOW_START\",\"checkOut\":\"$FLOW_END\",\"roomCount\":1}"
expect_status 201 'Create booking'
BOOKING_ID="$(json_value id "$HTTP_BODY")"
request POST "/api/bookings/$BOOKING_ID/payment" "$USER1"
expect_status 200 'Mock payment confirms booking'
[[ "$(json_value status "$HTTP_BODY")" == CONFIRMED ]] || fail "Booking did not become CONFIRMED: $HTTP_BODY"
pass 'Booking state is CONFIRMED'

request POST /api/support/date-change-requests "$USER1" \
  "{\"bookingId\":$BOOKING_ID,\"checkIn\":\"$NEW_START\",\"checkOut\":\"$NEW_END\",\"description\":\"Automated Docker workflow check\"}"
expect_status 201 'USER creates date-change request for own booking'
REQUEST_ID="$(json_value id "$HTTP_BODY")"

request POST "/api/support/date-change-requests/$REQUEST_ID/approve" "$ADMIN1"
expect_status 200 'ADMIN approves date-change request'
[[ "$(json_value status "$HTTP_BODY")" == APPROVED ]] || fail "Date-change request was not approved: $HTTP_BODY"
pass 'Date-change request state is APPROVED'

request GET "/api/bookings/$BOOKING_ID" "$USER1"
expect_status 200 'Booking reflects the approved new dates'
[[ "$(json_value checkIn "$HTTP_BODY")" == "$NEW_START" && "$(json_value checkOut "$HTTP_BODY")" == "$NEW_END" ]] \
  || fail "Booking dates do not match approved dates: $HTTP_BODY"
pass 'Booking service applied the approved dates'

for attempt in {1..10}; do
  request GET '/api/notifications?page=0&size=20' "$USER1"
  expect_status 200 'USER can read notification inbox'
  if python3 -c 'import json,sys; d=json.load(sys.stdin); sys.exit(0 if any(x.get("referenceId")==int(sys.argv[1]) for x in d.get("items",[])) else 1)' "$REQUEST_ID" <<<"$HTTP_BODY"; then
    break
  fi
  [[ "$attempt" == 10 ]] && fail "No notification received for request $REQUEST_ID"
  sleep 2
done
pass 'Notification outbox produced an inbox notification'

DB_BOOKING_STATUS="$(db_scalar "SELECT status FROM booking WHERE id=$BOOKING_ID;")"
DB_REQUEST_STATUS="$(db_scalar "SELECT status FROM date_change_request WHERE id=$REQUEST_ID;")"
DB_OUTBOX="$(db_scalar "SELECT count(*) FROM notification_outbox WHERE reference_id=$REQUEST_ID AND processed_at IS NOT NULL;")"
[[ "$DB_BOOKING_STATUS" == CONFIRMED && "$DB_REQUEST_STATUS" == APPROVED && "$DB_OUTBOX" -ge 1 ]] \
  || fail "Unexpected DB workflow state: booking=$DB_BOOKING_STATUS request=$DB_REQUEST_STATUS processed_outbox=$DB_OUTBOX"
pass 'Database rows confirm booking, request, and processed notification outbox'

echo 'All Docker API workflow checks passed.'
