# Docker backend workflow checks

Scripts here build and run the backend locally, then check migrations, database consistency, API security, booking inventory, Support date changes, and notifications. They do not test Google OAuth with real credentials; JWTs come from the local `dev-token` harness.

## Requirements

- Docker Desktop running with Compose v2.
- `curl`, `python3`, and Bash on the host.
- This workflow preserves the `hotelbooking-pgdata` volume. It does not run `docker compose down -v`.

## Start and build

From any working directory:

```bash
bash /Volumes/DATA/JIKAY/src/project-demo/HotelBookingSystem/backend/test/00-up.sh
```

The first run builds each Spring Boot service and may take several minutes. The profile starts PostgreSQL, IAM, Catalog, Booking, Support, Notification, API Gateway, the temporary token server, and Adminer. Test identities and repeatable ERD fixtures are inserted after migrations complete.

## Check DB and run API workflows

```bash
bash /Volumes/DATA/JIKAY/src/project-demo/HotelBookingSystem/backend/test/10-check-db.sh
bash /Volumes/DATA/JIKAY/src/project-demo/HotelBookingSystem/backend/test/20-api-workflow.sh
```

The API workflow checks:

- Missing token is rejected; tenant-specific hotel lists and cross-tenant 404s.
- USER/ADMIN permissions for inventory.
- Six concurrent requests for one room do not oversell; cancellation returns `sold` to its prior value.
- Booking → mock payment → Support date-change request → ADMIN approval → Booking date update → Notification inbox.
- The script removes the booking, reschedule, request, and notification rows it created, and returns its held inventory. It leaves the seeded mock fixtures intact.

If an API workflow is interrupted, rerun it after checking the printed error. Its cleanup trap runs on normal exit and Ctrl+C; for a forced kill, inspect `booking`, `date_change_request`, `booking_reschedule`, `notification_outbox`, and `notification` in Adminer before cleanup.

## Inspect database and services

- Adminer: <http://127.0.0.1:8083> — PostgreSQL server `postgres`, user/password `hotelbooking`, database `hotelbooking`.
- API Gateway: <http://127.0.0.1:8080>
- Temporary token/catalog test UI: <http://127.0.0.1:8090>
- Direct service ports: Catalog `8081`, Booking `8082`, Support `8085`, Notification `8086`, IAM `8084`.

The temporary token issuer is only for local API testing. IAM starts for migrations and JWKS visibility; Google login requires valid `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` environment variables and the registered callback URLs. Do not expose this workflow or its mock token issuer to the Internet.

## Stop and retain data

```bash
cd /Volumes/DATA/JIKAY/src/project-demo/HotelBookingSystem/backend
docker compose --profile workflow down
```

To rebuild after source edits, run `00-up.sh` again. Avoid `docker compose down -v` unless you intentionally want to delete the local PostgreSQL data volume.
