#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
POSTGRES_CONTAINER="${POSTGRES_CONTAINER:-hotelbooking-postgres}"

cd "$BACKEND_DIR"
docker compose --profile workflow up --build -d --wait

echo "Seeding local API test identities into $POSTGRES_CONTAINER..."
docker exec -i "$POSTGRES_CONTAINER" psql -X -v ON_ERROR_STOP=1 -U hotelbooking -d hotelbooking \
  < "$BACKEND_DIR/docker/seed/seed-app-users.dev-test.sql"

echo "Generating repeatable ERD mock fixtures..."
bash "$BACKEND_DIR/data/generate-erd-mock-data.sh"

echo
echo "Workflow is ready:"
echo "  API Gateway: http://127.0.0.1:8080"
echo "  Catalog test UI: http://127.0.0.1:8090"
echo "  Adminer: http://127.0.0.1:8083"
echo "  PostgreSQL: 127.0.0.1:5432"
