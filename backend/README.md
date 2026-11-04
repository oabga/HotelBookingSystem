# StayHub — Documentation index

**Status:** Proposed target architecture (2026-11-04). Not a statement of deployed functionality. Requires code/migration verification and team approval (G2).

## Source of truth
1. `docs/architecture.md` — system boundary, 3 apps, Keycloak SSO, authorization.
2. `docs/api-contract.md` — proposed routes, payloads, HTTP semantics.
3. `docs/data-model.md` — logical schema, booking inventory invariants, support tickets.
4. `docs/workflows.md` — USER/ADMIN lifecycles, SSO, booking, support.
5. `docs/decisions.md` — architecture decisions, open questions.
6. `docs/operations-and-tests.md` — migration, local setup, acceptance, known limitations.

## Superseded documents
`plan-v2.md`, `booking-policy.md`, `catalog-known-limitations.md`, `erd-v2.md`, `migration-convention.md`, `sequence-booking.md`, `sequence-catalog.md`, and the original `api-contract.md` / `decisions.md` are archived under `archive/original/` for traceability. Do not keep duplicate copies in active `docs/`.

**Important:** No repository implementation was supplied. This is a proposed documentation baseline, not verified runtime behavior.
