# Architecture decisions / ADR register

**Status: proposed pending G2 sign-off.** This is the decision register, not a claim of completed deployment.

| ID | Decision | Rationale / trade-off |
|---|---|---|
| ADR-001 | Three browser apps: Booking, Admin Dashboard, Support Center | Clear UX boundaries; 2 roles only |
| ADR-002 | Google upstream IdP + Keycloak as OIDC SSO authority | Standardized login, sessions and per-client tokens; replaces custom IAM JWT |
| ADR-003 | 3 Keycloak clients, Authorization Code + PKCE S256 | No token sharing across apps |
| ADR-004 | Tenant scoped API authorization, role plus resource ownership | Frontend guards are not security boundary |
| ADR-005 | Shared PostgreSQL for MVP Catalog/Booking inventory | Atomic stock + booking transaction; tighter service coupling |
| ADR-006 | Sell room types using nightly inventory | Simpler than physical-room assignment |
| ADR-007 | Mock payment only; no payment gateway, refund or push in MVP | Scope control |
| ADR-008 | Support ticket + immutable messages; USER and ADMIN only | Supports post-booking operations without adding roles |
| ADR-009 | Flyway migrations immutable after shared deployment | Auditability and safe upgrades |

## Decisions still open
1. Choose BFF cookie sessions (recommended) versus pure SPA token flow; document CSRF and token storage.
2. Map Keycloak `sub` and tenant membership; confirm whether user can belong to >1 tenant.
3. Confirm ticket status transitions, retention, SLA and optional assignment.
4. Confirm Keycloak logout propagation strategy and session TTLs.
5. Decide whether support service queries shared DB or calls Booking API for ownership validation.
6. Freeze exact endpoints/DTOs, service ownership and actual build versions after repository audit.

**Do not** reuse old plan statements claiming “Keycloak out of scope” or “IAM mints RS256 JWT”.
