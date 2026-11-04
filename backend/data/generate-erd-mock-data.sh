#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
CONTAINER="${POSTGRES_CONTAINER:-hotelbooking-postgres}"

cd "$BACKEND_DIR"

if ! docker exec "$CONTAINER" pg_isready -U hotelbooking -d hotelbooking >/dev/null; then
  echo "PostgreSQL container '$CONTAINER' is not ready. Start it with: docker compose up -d postgres" >&2
  exit 1
fi

for table in tenant app_user hotel room_type room_inventory booking; do
  exists="$(docker exec "$CONTAINER" psql -At -U hotelbooking -d hotelbooking \
    -c "SELECT to_regclass('public.${table}') IS NOT NULL;")"
  if [[ "$exists" != 't' ]]; then
    echo "Required table '$table' is missing. Run its service migration first (IAM -> Catalog -> Booking)." >&2
    exit 1
  fi
done

echo 'Replacing only the dedicated StayHub mock fixtures...'
docker exec -i "$CONTAINER" psql -X -v ON_ERROR_STOP=1 -U hotelbooking -d hotelbooking \
  < "$SCRIPT_DIR/generate-erd-mock-data.sql"

echo 'Mock data counts:'
docker exec "$CONTAINER" psql -U hotelbooking -d hotelbooking -c \
  "SELECT (SELECT count(*) FROM tenant) AS tenants, (SELECT count(*) FROM app_user) AS users, (SELECT count(*) FROM hotel) AS hotels, (SELECT count(*) FROM room_type) AS room_types, (SELECT count(*) FROM room_inventory) AS inventory_rows, (SELECT count(*) FROM booking) AS bookings;"
docker exec "$CONTAINER" psql -U hotelbooking -d hotelbooking -c \
  "SELECT status, count(*) FROM booking WHERE user_email LIKE 'mock.%@stayhub.example.test' GROUP BY status ORDER BY status;"
docker exec "$CONTAINER" psql -U hotelbooking -d hotelbooking -c \
  "SELECT i.tenant_id, i.room_type_id, i.stay_date, i.total, i.sold, i.total - i.sold AS available FROM room_inventory i WHERE i.stay_date BETWEEN DATE '2026-12-01' AND DATE '2026-12-11' AND i.sold > 0 ORDER BY i.tenant_id, i.room_type_id, i.stay_date;"

support_state="$(docker exec "$CONTAINER" psql -At -U hotelbooking -d hotelbooking \
  -c "SELECT to_regclass('public.support_ticket') IS NOT NULL AND to_regclass('public.ticket_message') IS NOT NULL;")"
if [[ "$support_state" == 't' ]]; then
  docker exec "$CONTAINER" psql -U hotelbooking -d hotelbooking -c \
    "SELECT (SELECT count(*) FROM support_ticket WHERE subject LIKE '[MOCK] %') AS mock_tickets, (SELECT count(*) FROM ticket_message m JOIN support_ticket t ON t.id = m.ticket_id WHERE t.subject LIKE '[MOCK] %') AS mock_messages;"
else
  echo 'Support tables are not migrated; the SQL reported that Support fixtures were skipped.'
fi

echo 'ERD mock fixture generation completed.'
