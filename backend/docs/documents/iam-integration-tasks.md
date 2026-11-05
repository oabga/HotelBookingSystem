# StayHub — IAM Integration Tasks

> **Cập nhật:** 05/11/2026, đối chiếu với `main` tại commit `1737b60`
> **Mục đích:** Theo dõi việc đã xong và việc còn lại của phần tích hợp IAM với ba ứng dụng.

"Xong trong code" nghĩa là đã có trong `main`. Cột bằng chứng ghi rõ đã được chạy tới đâu.

## 1. Việc đã xong

### 1.1. IAM

| Việc | Vị trí | Bằng chứng |
|---|---|---|
| Google OIDC, Authorization Code + PKCE S256 | `SecurityConfig` | Đã thấy tham số khi chuyển sang Google. Chưa đăng nhập thật |
| `prompt=select_account` | `SecurityConfig` | Đã thấy khi chuyển sang Google |
| Callback cho `app1`, `app2`, `app3` | `application.yml`, `SsoProperties` | Đã gọi thật |
| Từ chối `app`, `mode` không hợp lệ bằng `400` | `AuthController` | Đã gọi thật |
| Chính sách theo app | `SsoLoginService.issueToken` | Chỉ đọc từ code |
| Tự tạo `USER` ở App 1 | `SsoLoginService` | Chỉ đọc từ code |
| `IAM_ADMIN_EMAIL` cho App Admin | `SsoLoginService`, `SsoProperties` | Chỉ đọc từ code |
| Trả `#error=not_provisioned` | Success handler, `AuthController` | Chỉ đọc từ code |
| Trả `#error=login_failed` | Failure handler | Đã gọi thật |
| `/auth/logout` | `AuthController` | Đã gọi thật |
| JWT RS256 và JWKS | `JwtIssuer`, `JwtKeyProvider`, `JwksController` | Unit test, đã gọi JWKS |

### 1.2. Gateway và service

| Việc | Bằng chứng |
|---|---|
| Gateway định tuyến và kiểm tra JWT | Unit test, workflow Docker |
| CORS cho ba origin, kể cả phản hồi `401` | `GatewayCorsTest` |
| Mỗi service tự kiểm tra JWT, role, tenant | Workflow Docker, test của service |
| Support Service với phiếu đổi ngày | Workflow Docker |
| Notification Service qua outbox | Workflow Docker |

### 1.3. Frontend

| Việc | Bằng chứng |
|---|---|
| Ba app với callback riêng | `frontend/*.test.js` |
| Đọc `#token`, xóa fragment, giữ JWT trong bộ nhớ | `auth.test.js` |
| Xử lý `#error=...` | `auth.test.js` |
| Xử lý `#logged_out` | Chỉ đọc từ code, chưa có test |
| App 1 chỉ mở nội dung cho role `USER` | `app.test.js` |
| Xóa JWT và yêu cầu đăng nhập lại khi API trả `401` | `auth.test.js` |
| Nút đăng xuất ở App 1 | `frontend/app1/index.html` |

## 2. Việc còn lại

Thứ tự theo mức ảnh hưởng tới việc chạy hệ thống.

| # | Việc | Vì sao | Nơi sửa |
|---|---|---|---|
| 1 | Chạy `IAM-01` đến `IAM-11`, `SSO-*`, `OUT-*`, `JWT-*` với Google thật | Luồng đăng nhập chưa được chạy thật | [iam-testing.md](iam-testing.md) |
| 2 | Tạo `kid` theo từng key | Restart IAM làm Gateway từ chối mọi JWT mới cho đến khi restart Gateway | `JwtKeyProvider` |
| 3 | Viết test cho chính sách theo app, tạo tài khoản, nâng role, `IAM_ADMIN_EMAIL`, `AuthController`, các handler | Logic mới chưa có test | `iam-service/src/test` |
| 4 | Quyết định lại việc chỉ một email vào App Admin | ADMIN khác và tenant khác không vào được App Admin | `SsoLoginService`, [decisions.md](decisions.md) |
| 5 | Bỏ email cá nhân khỏi giá trị mặc định của Compose | Mọi người dùng cấu hình mặc định đều phụ thuộc email đó | `docker-compose.yml` |
| 6 | Trả mã lỗi riêng cho từng lý do từ chối | Frontend có thể báo sai nguyên nhân | `SsoLoginService`, `frontend/auth.js` |
| 7 | Xử lý trùng `google_sub` khi tạo tài khoản mới và đăng nhập đồng thời lần đầu | Vi phạm `UNIQUE` có thể thành lỗi `500` thay vì `#error=...`. Suy ra từ code, cần chạy thử | `SsoLoginService`, các handler |
| 8 | Gắn app đích với từng authorization request | Hai tab song song có thể về nhầm app | `AuthController`, handler |
| 9 | Quyết định về đăng xuất giữa các app | Chỉ App 1 có nút, tab khác không được báo. `/auth/logout` đang là `GET` | `OPEN-002` trong [decisions.md](decisions.md) |
| 10 | Dùng `common-security` hoặc xóa module này | Module rỗng, mỗi service đang lặp lại `SecurityConfig` | `common-security` |
| 11 | Làm rõ vai trò của `mode` | `mode=register` không khác `mode=login`, và frontend không còn gửi `register` | `AuthController`, `SsoLoginService` |

## 3. Trước production

Chưa thuộc phạm vi demo, cần quyết định riêng:

- Cơ chế trao JWT thay cho URL fragment (BFF, cookie `HttpOnly`, `Secure`, `SameSite`).
- Lưu RSA key bền vững, xoay vòng key và giữ key cũ trong JWKS một thời gian.
- Refresh token và thu hồi JWT.
- Chính sách đăng ký: App 1 hiện nhận mọi Gmail đã xác minh.
- Chính sách cho tài khoản nhiều tenant.
- Đổi email của tài khoản.

## 4. Không làm

- Không dùng Keycloak.
- Không thêm role `SUPPORT`.
- Không dùng Google ID Token để gọi API nghiệp vụ.
