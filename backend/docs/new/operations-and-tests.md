# Operations, migrations, tests and known limitations

**Status: proposed acceptance checklist; no tests have been run in this documentation-only revision.**

## Migration convention
- Flyway `V<number>__snake_case.sql`; do not edit merged/shared migrations. Check actual schema history before upgrading.
- Current historical order: IAM tables -> Catalog tables -> Booking tables. New target needs Keycloak identity mapping and Support tables. Run migrations in explicit dependency order, verify FK/indexes, and preserve existing data.
- Separate Flyway history per service in shared DB if retained. Coordinate cross-service schema changes through a reviewed PR. For deployed DBs use additive migration + backfill + validation + later cleanup, not `docker compose down -v`.
- Local disposable DB may be reset **only** with explicit team agreement. Never reset shared/production data.

## Acceptance matrix
| Scenario | Expected result |
|---|---|
| USER Booking login -> Support | SSO; separate OIDC code/token; no password if Keycloak session valid |
| ADMIN Dashboard -> Support | SSO, ADMIN support queue visible |
| USER -> Admin Dashboard | 403/denied access |
| ADMIN -> USER booking checkout | Denied by policy |
| USER ticket on another user's booking | 404 |
| ADMIN ticket from other tenant | 404 |
| Cross-tenant hotel/booking | 404, no information disclosure |
| Two concurrent bookings for last room | Exactly one 201, one 409; `sold <= total` |
| Cancel/expiry duplicate requests | Stock released once |
| Expired access token | 401 or successful authorized refresh according to chosen client architecture |
| Logout in one app | Keycloak session terminated; other app sessions handled per logout design |
| Unit tests and integration tests | Gradle test PASS with assertions and PostgreSQL integration tests |

## Evidence required
- Record sanitized OIDC redirect and PKCE S256 challenge; issuer, audience and role verification; do not record tokens or secrets.
- Record Flyway history, build/test commands, integration test responses and stock reconciliation (0 discrepancies).
- Use Testcontainers/PostgreSQL for race, rollback and FK tests. Unit tests alone cannot prove concurrency correctness.

## Known limitations
- Inventory opened for a finite date range unless a replenishment job is implemented.
- Fixed base price per room type, no daily pricing, physical-room assignment or real payment/refund.
- Booking idempotency key, rate limiting, pagination and notifications require explicit prioritization; do not call MVP production-ready without them.
- Ticket attachments, automated SLA escalation, realtime chat and full audit/compliance retention are out of MVP.
- Keycloak deployment, email verification, Google broker linking and cross-app logout must be integration-tested; documentation alone does not establish they work.

## Metrics
- Authorization: 100% negative role/tenant/ownership tests pass.
- Booking: 0 oversold nights and 0 stock reconciliation mismatches under concurrency test.
- SSO: 2 cross-app flows (USER and ADMIN) succeed with valid Keycloak session.
- Quality: all service builds/tests pass; no secrets in Git or logs.
