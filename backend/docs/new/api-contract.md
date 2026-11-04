# API Contract v4 — Proposed

**Status: PROPOSED / not verified against implementation.** Gateway prefix `/api`; services must validate JWT and authorization independently. JSON examples illustrative.

## HTTP and identity
- 400 validation; 401 unauthenticated/invalid token; 403 authenticated but role denied; 404 nonexistent or cross-tenant/foreign-owned resource; 409 inventory/state conflict; 429 rate-limited.
- Bearer access token issued by Keycloak. Claims: `sub`, `iss`, `aud`, `exp`, role mapping; tenant membership resolved server-side. Do not accept `tenantId`, `role`, `userEmail`, `price` from clients as authority.
- Pagination `page`/`size` on lists (MVP may defer, documented); consistent RFC 9457 Problem Details error response.

## Role matrix
| Endpoint | USER | ADMIN | Semantics |
|---|---|---|---|
| `GET /api/hotels` | Yes | Yes | Tenant hotels |
| `GET /api/hotels/{hotelId}/availability?checkIn&checkOut` | Yes | Yes | Available room types for every night |
| `POST /api/hotels/{hotelId}/room-types` | No | Yes | Create type and daily inventory |
| `PUT /api/room-types/{id}/price` | No | Yes | Future bookings only |
| `GET /api/admin/hotels/{hotelId}/inventory?checkIn&checkOut` | No | Yes | total/sold/available |
| `POST /api/bookings` | Yes | No | Reserve inventory, pending payment |
| `GET /api/bookings` | Own | Tenant | Filter enforced server-side |
| `GET /api/bookings/{id}` | Own | Tenant | Foreign resource -> 404 |
| `POST /api/bookings/{id}/payment` | Own | No | Mock success before expiry |
| `POST /api/bookings/{id}/cancel` | Own | No | Pending only; release once |
| `POST /api/support/tickets` | Yes | No | Create ticket, optional owned booking |
| `GET /api/support/tickets` | Own | Tenant | Paginated |
| `GET /api/support/tickets/{id}` | Own | Tenant | Includes message history |
| `POST /api/support/tickets/{id}/messages` | Own | Tenant | USER/ADMIN reply; audit author |
| `PATCH /api/support/tickets/{id}/status` | No | Yes | Allowed state transitions only |

## Booking request
`POST /api/bookings`
```json
{"roomTypeId":1,"checkIn":"2026-11-24","checkOut":"2026-11-26","roomCount":1}
```
Response `201`: `id`, `roomTypeId`, `checkIn`, `checkOut`, `roomCount`, `totalAmount`, `status=PENDING_PAYMENT`, `paymentExpiresAt`. Server derives price, tenant and owner. Search is not a hold; reservation must re-check inventory atomically.

## Support request
`POST /api/support/tickets`
```json
{"bookingId":123,"subject":"Cần hỗ trợ nhận phòng","description":"Tôi cần thay đổi giờ đến."}
```
Response `201`: `id`, `status=OPEN`, `bookingId`, `createdAt`. Verify booking belongs to requester and tenant. `bookingId` optional for general help. Ticket status: `OPEN -> IN_PROGRESS -> RESOLVED -> CLOSED`; optionally `RESOLVED -> IN_PROGRESS` for reopen, only after G2 approval. USER cannot set status or impersonate authors. Message body has length limits and HTML sanitization; attachments deferred.

## Identity endpoints
OIDC authorization, token, userinfo, logout and JWKS endpoints are **Keycloak-managed** (`/.well-known/openid-configuration` discovery). Remove legacy `/auth/start?app=...`, IAM JWT/JWKS, and fragment-token callbacks from target contract.

## Open contract questions
Agree on BFF vs SPA; issuer/audience per service; `tenant_id` mapping; ticket reopen rules; pagination and exact response DTOs; payment mock idempotency. Freeze at G2 before implementation.
