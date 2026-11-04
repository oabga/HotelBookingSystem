#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"

if [[ "${1:-}" != "--yes" ]]; then
  cat <<'NOTICE'
This rebuild removes the Docker volume for hotelbooking and deletes its current data.
It then runs IAM and Catalog Flyway migrations and inserts five synthetic app_user rows.
NOTICE
  read -r -p 'Type REBUILD to continue: ' confirmation
  if [[ "$confirmation" != "REBUILD" ]]; then
    echo 'Cancelled. No database changes were made.'
    exit 1
  fi
fi

cd "$BACKEND_DIR"

echo 'Stopping Compose and removing the local PostgreSQL volume...'
docker compose down -v --remove-orphans

echo 'Starting PostgreSQL...'
docker compose up -d postgres

echo 'Waiting for PostgreSQL health check...'
ready=false
for _ in $(seq 1 60); do
  health="$(docker inspect --format '{{.State.Health.Status}}' hotelbooking-postgres 2>/dev/null || true)"
  if [[ "$health" == 'healthy' ]]; then
    ready=true
    break
  fi
  sleep 2
done
if [[ "$ready" != true ]]; then
  echo 'PostgreSQL did not become healthy within 120 seconds.' >&2
  docker compose ps >&2
  exit 1
fi

echo 'Running IAM Flyway migrations...'
./gradlew :iam-service:bootRun --args='--spring.main.web-application-type=none'

echo 'Running Catalog Flyway migrations...'
./gradlew :catalog-service:bootRun --args='--spring.main.web-application-type=none'

echo 'Inserting synthetic demo users...'
docker exec -i hotelbooking-postgres \
  psql -v ON_ERROR_STOP=1 -U hotelbooking -d hotelbooking \
  < "$SCRIPT_DIR/seed-virtual-data.sql"

echo 'Asserting expected seed counts and inventory state...'
docker exec -i hotelbooking-postgres \
  psql -v ON_ERROR_STOP=1 -U hotelbooking -d hotelbooking \
  < "$SCRIPT_DIR/verify-demo-data.sql"

echo 'Verifying Flyway history and seed counts...'
docker exec hotelbooking-postgres psql -U hotelbooking -d hotelbooking -c \
  "SELECT version, description, success FROM flyway_schema_history_iam ORDER BY installed_rank;"
docker exec hotelbooking-postgres psql -U hotelbooking -d hotelbooking -c \
  "SELECT version, description, success FROM flyway_schema_history_catalog ORDER BY installed_rank;"
docker exec hotelbooking-postgres psql -U hotelbooking -d hotelbooking -c \
  "SELECT (SELECT count(*) FROM tenant) AS tenants, (SELECT count(*) FROM app_user) AS app_users, (SELECT count(*) FROM hotel) AS hotels, (SELECT count(*) FROM room_type) AS room_types, (SELECT count(*) FROM room_inventory) AS inventory_rows;"
docker exec hotelbooking-postgres psql -U hotelbooking -d hotelbooking -c \
  "SELECT DISTINCT sold FROM room_inventory;"

echo 'Database rebuild and synthetic seed completed.'
