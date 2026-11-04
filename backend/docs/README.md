# StayHub Documentation — 3 Apps + IAM hiện tại (chuẩn team)

**Kiến trúc chốt:** Google OIDC → `iam-service :8084` (Spring Security OAuth2 Client + HttpSession SSO + RS256 JWT/JWKS) → Booking, Admin, Support. **Không Keycloak.**

## Quyền truy cập
| App | URL dev | Role |
|---|---|---|
| Booking | :3000 | USER |
| Admin Dashboard | :3001 | ADMIN |
| Support Center | :3002 (proposed) | USER + ADMIN |

## Đọc tài liệu theo thứ tự
1. `docs/architecture.md` — thành phần, flow, boundaries.
2. `docs/iam-sso.md` — kiến thức và sequence IAM của developer, mở rộng 3 app.
3. `docs/iam-integration-tasks.md` — delta tối thiểu IAM + Support + Frontend.
4. `docs/iam-testing.md` — terminal/browser acceptance test.
5. `docs/api-contract.md` — HTTP, role matrix và API Support đề xuất.
6. `docs/data-model.md` — tenant, booking inventory, support ticket.
7. `docs/workflows.md` — vòng đời USER/ADMIN và SSO.
8. `docs/operations-and-tests.md` — migration, acceptance, blocker.
9. `docs/decisions.md` — quyết định kiến trúc.

## Phân biệt trạng thái
- **Dev documented existing:** OAuth2 Google + PKCE, pre-provision, `google_sub`, custom RS256 JWT/JWKS, Gateway/Catalog validation, 2-app SSO (theo báo cáo dev).
- **To implement:** support callback thứ 3, IAM role-to-app policy, Support service/ticket, E2E 3-app.
- **Known blocker:** Booking migration tham chiếu bảng `room` cũ (theo báo cáo dev, cần xác minh).
- **Demo-only debt:** fragment JWT, keypair reset on IAM restart, thiếu refresh/revoke/logout, session app selector multi-tab race.

Đây là bản hợp nhất documentation, **không phải xác nhận code đã chạy**. Không commit secrets/tokens/cookies thật.
