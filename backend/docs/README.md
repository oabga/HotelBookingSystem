# StayHub Documentation

**Kiến trúc:** Google OIDC → `iam-service :8084` (Spring Security OAuth2 Client, HttpSession SSO, JWT RS256, JWKS) → App 1, App Admin, Support Center → API Gateway `:8080` → Catalog, Booking, Support, Notification. **Không dùng Keycloak.**

Các tài liệu mô tả hệ thống đang có trong code, đối chiếu ngày 05/11/2026 tại commit `1737b60`. Điều gì đã chạy thật và điều gì mới đọc từ code được ghi ở [documents/README.md](documents/README.md#6-mức-độ-kiểm-chứng).

## Quyền truy cập

| App | Mã IAM | URL dev | Ai nhận được JWT |
|---|---|---|---|
| App 1 | `app1` | `:3000` | Tài khoản `USER`. Email mới được tự tạo tài khoản |
| App Admin | `app2` | `:3001` | Chỉ email `IAM_ADMIN_EMAIL`, role `ADMIN` |
| Support Center | `app3` | `:3002` | Mọi tài khoản đã tồn tại, `USER` hoặc `ADMIN` |

## Đọc tài liệu theo thứ tự

1. [documents/README.md](documents/README.md): tổng quan và bản đồ tài liệu.
2. [documents/architecture.md](documents/architecture.md): thành phần, ranh giới, giới hạn.
3. [documents/iam-sso.md](documents/iam-sso.md): đăng nhập, chính sách theo app, SSO, JWT.
4. [documents/api-contract.md](documents/api-contract.md): HTTP API.
5. [documents/data-model.md](documents/data-model.md): quy tắc dữ liệu.
6. [documents/erd.md](documents/erd.md): schema.
7. [stayhub-sequence-diagrams.md](stayhub-sequence-diagrams.md): sequence diagram mọi luồng.
8. [documents/operations-and-tests.md](documents/operations-and-tests.md): cấu hình, chạy, test.
9. [documents/iam-testing.md](documents/iam-testing.md): kiểm thử IAM và SSO.
10. [documents/iam-integration-tasks.md](documents/iam-integration-tasks.md): việc còn lại của IAM.
11. [documents/decisions.md](documents/decisions.md): quyết định kiến trúc.
12. [build/README.md](build/README.md): build và rebuild Docker.

## Trạng thái

- **Đã có trong code:** Google OIDC + PKCE, chính sách theo app, tự tạo tài khoản ở App 1, đăng xuất, JWT RS256, JWKS, Gateway, Catalog, Booking, Support đổi ngày, Notification, ba frontend.
- **Đã chạy thật:** workflow API trên Docker với JWT của `dev-token`, các endpoint không cần đăng nhập Google của IAM.
- **Chưa chạy thật:** đăng nhập bằng tài khoản Google, SSO khi đã có phiên, JWT do IAM ký đi qua Gateway.
- **Chưa có:** quản lý khách sạn, sửa tồn kho theo đêm, thanh toán thật, ticket hỗ trợ chung, thu hồi JWT, refresh token.
