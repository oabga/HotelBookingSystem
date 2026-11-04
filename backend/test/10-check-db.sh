#!/usr/bin/env bash
set -Eeuo pipefail

POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-hotelbooking-postgres}"

docker exec "$POSTGRES_CONTAINER" pg_isready -U hotelbooking -d hotelbooking
FAILED_MIGRATIONS="$(docker exec "$POSTGRES_CONTAINER" psql -X -At -v ON_ERROR_STOP=1 -U hotelbooking -d hotelbooking -c \
  "SELECT count(*) FROM (SELECT success FROM flyway_schema_history_iam UNION ALL SELECT success FROM flyway_schema_history_catalog UNION ALL SELECT success FROM flyway_schema_history_booking UNION ALL SELECT success FROM flyway_schema_history_support UNION ALL SELECT success FROM flyway_schema_history_notification) h WHERE NOT success;")"
[[ "$FAILED_MIGRATIONS" == 0 ]] || { echo "Found $FAILED_MIGRATIONS failed Flyway migrations." >&2; exit 1; }
docker exec -i "$POSTGRES_CONTAINER" psql -X -v ON_ERROR_STOP=1 -U hotelbooking -d hotelbooking <<'SQL'
\echo '--- Applied Flyway migrations'
SELECT history, version, description, success
FROM (
    SELECT 'iam' AS history, version, description, success, installed_rank FROM flyway_schema_history_iam
    UNION ALL
    SELECT 'catalog', version, description, success, installed_rank FROM flyway_schema_history_catalog
    UNION ALL
    SELECT 'booking', version, description, success, installed_rank FROM flyway_schema_history_booking
    UNION ALL
    SELECT 'support', version, description, success, installed_rank FROM flyway_schema_history_support
    UNION ALL
    SELECT 'notification', version, description, success, installed_rank FROM flyway_schema_history_notification
) migrations
ORDER BY history, installed_rank;

\echo '--- Core table counts'
SELECT
    (SELECT count(*) FROM tenant) AS tenants,
    (SELECT count(*) FROM app_user) AS app_users,
    (SELECT count(*) FROM hotel) AS hotels,
    (SELECT count(*) FROM room_type) AS room_types,
    (SELECT count(*) FROM room_inventory) AS inventory_rows,
    (SELECT count(*) FROM booking) AS bookings,
    (SELECT count(*) FROM date_change_request) AS date_change_requests,
    (SELECT count(*) FROM notification_outbox) AS notification_outbox,
    (SELECT count(*) FROM notification) AS notifications;

\echo '--- Data consistency mismatches (expected: zero rows)'
SELECT i.tenant_id, i.room_type_id, i.stay_date, i.sold,
       COALESCE(SUM(b.room_count), 0)::integer AS expected_sold
FROM room_inventory i
LEFT JOIN booking b
  ON b.tenant_id = i.tenant_id
 AND b.room_type_id = i.room_type_id
 AND b.status IN ('PENDING_PAYMENT', 'CONFIRMED')
 AND i.stay_date >= b.check_in
 AND i.stay_date < b.check_out
GROUP BY i.tenant_id, i.room_type_id, i.stay_date, i.sold
HAVING i.sold <> COALESCE(SUM(b.room_count), 0)::integer
ORDER BY i.tenant_id, i.room_type_id, i.stay_date;

\echo '--- Inventory currently sold'
SELECT tenant_id, room_type_id, stay_date, total, sold, total - sold AS available
FROM room_inventory
WHERE sold > 0
ORDER BY tenant_id, room_type_id, stay_date;
SQL
MISMATCH_COUNT="$(docker exec "$POSTGRES_CONTAINER" psql -X -At -v ON_ERROR_STOP=1 -U hotelbooking -d hotelbooking -c \
  "SELECT count(*) FROM (SELECT i.tenant_id, i.room_type_id, i.stay_date FROM room_inventory i LEFT JOIN booking b ON b.tenant_id=i.tenant_id AND b.room_type_id=i.room_type_id AND b.status IN ('PENDING_PAYMENT','CONFIRMED') AND i.stay_date>=b.check_in AND i.stay_date<b.check_out GROUP BY i.tenant_id,i.room_type_id,i.stay_date,i.sold HAVING i.sold<>COALESCE(SUM(b.room_count),0)::integer) mismatches;")"
if [[ "$MISMATCH_COUNT" != 0 ]]; then
  echo "Inventory reconciliation failed: $MISMATCH_COUNT mismatch(es)." >&2
  docker exec "$POSTGRES_CONTAINER" psql -X -U hotelbooking -d hotelbooking -c \
    "SELECT i.tenant_id,i.room_type_id,i.stay_date,i.sold,COALESCE(SUM(b.room_count),0)::integer AS expected_sold FROM room_inventory i LEFT JOIN booking b ON b.tenant_id=i.tenant_id AND b.room_type_id=i.room_type_id AND b.status IN ('PENDING_PAYMENT','CONFIRMED') AND i.stay_date>=b.check_in AND i.stay_date<b.check_out GROUP BY i.tenant_id,i.room_type_id,i.stay_date,i.sold HAVING i.sold<>COALESCE(SUM(b.room_count),0)::integer ORDER BY i.tenant_id,i.room_type_id,i.stay_date;"
  exit 1
fi
echo 'PASS: Flyway histories are successful; inventory reconciliation has zero mismatches.'
