# StayHub — IAM Testing & SSO Acceptance

> **Cập nhật:** 05/11/2026, đối chiếu với `main` tại commit `1737b60`
> **Trạng thái:** Mục 2 và 3 đã chạy. Mục 4 và 5 chưa chạy vì cần tài khoản Google thật.

## 1. Chuẩn bị

Để chạy mục 4 và 5, cần:

1. OAuth client trên Google Cloud Console, redirect URI `http://localhost:8084/login/oauth2/code/google`.
2. `backend/.env.local` theo [operations-and-tests.md](operations-and-tests.md#21-jwt-trong-compose).
3. Stack đang chạy và `node frontend/server.js`.
4. Các tài khoản test:

| Tên | Cách có | Dùng để |
|---|---|---|
| `new-user` | Gmail chưa có trong `app_user` | Tự tạo tài khoản ở App 1 |
| `admin` | Email bằng `IAM_ADMIN_EMAIL` | App Admin |
| `other-admin` | Seed role `ADMIN`, email khác `IAM_ADMIN_EMAIL` | Bị từ chối ở App Admin |
| `unknown` | Gmail chưa có trong `app_user`, chưa từng vào App 1 | Bị từ chối ở Support |

Không commit email thật. Không dán JWT, cookie phiên, `state`, `code` hay `code_verifier` vào tài liệu và bằng chứng.

## 2. Test tự động

```bash
./gradlew :iam-service:test :api-gateway:test
```

| Test | Kiểm tra | Kết quả 05/11/2026 |
|---|---|---|
| `JwtIssuerTest` | Claim của JWT | Đạt |
| `JwksControllerTest` | JWKS không lộ private key | Đạt |
| `SsoLoginServiceTest` | Email chưa xác minh, email chưa có khi không qua app, `google_sub` khác, gắn `google_sub` lần đầu, app ngoài allowlist | Đạt, 5 ca |
| `GatewayCorsTest` | Preflight, origin, 401 có CORS | Đạt, 5 ca |
| `StayHubJwtValidatorTest` (Gateway) | Claim bắt buộc | Đạt, 3 ca |

**Chưa có test cho:** chính sách của `app1`, `app2`, `app3`, tạo tài khoản, nâng role, `IAM_ADMIN_EMAIL`, `AuthController`, các handler. `SsoLoginServiceTest` vẫn dùng `app3` làm ví dụ app không hợp lệ vì allowlist trong test chỉ có hai app.

## 3. Kiểm tra endpoint không cần đăng nhập Google

Dùng `curl -s -o /dev/null -D -`, không theo chuyển hướng. Các dòng dưới đây đã chạy ngày 05/11/2026 và cho đúng kết quả.

| Request | Kết quả |
|---|---|
| `GET /auth/start` | `400` |
| `GET /auth/start?app=support` | `400` |
| `GET /auth/start?app=app2&mode=register` | `400` |
| `GET /auth/start?app=app1&mode=bad` | `400` |
| `GET /auth/start?app=app1&mode=login` | `302 /oauth2/authorization/google`, có `Set-Cookie: JSESSIONID; HttpOnly` |
| `GET /auth/start?app=app1&mode=register` | `302 /oauth2/authorization/google` |
| `GET /oauth2/authorization/google` kèm cookie | `302 accounts.google.com`, có `state`, `nonce`, `code_challenge`, `code_challenge_method=S256`, `prompt=select_account`, scope `openid email profile` |
| `GET /login/oauth2/code/google?code=x&state=y`, không có phiên | `400` |
| `GET /login/oauth2/code/google?code=x&state=wrong`, phiên có `app3` | `302 http://localhost:3002/callback/#error=login_failed` |
| `GET /login/oauth2/code/google?error=access_denied&state=<đúng>`, phiên có `app1` | `302 http://localhost:3000/callback/#error=login_failed` |
| `GET /auth/logout?app=bad` | `400` |
| `GET /auth/logout?app=app1` | `302 http://localhost:3000/callback/#logged_out` |
| `GET /auth/logout?app=app3` | `302 http://localhost:3002/callback/#logged_out` |
| `GET /.well-known/jwks.json` | `200`, một key RSA, `kid = stayhub-key-1` |

Qua Gateway:

| Request | Kết quả |
|---|---|
| `GET /api/hotels`, không có JWT | `401` |
| `GET /auth/start?app=app1` qua cổng `8080`, không có JWT | `401`, vì Gateway không có route này |
| Preflight `OPTIONS /api/bookings` từ `http://localhost:3000` | `200`, có `Access-Control-Allow-Origin` |

Khi restart IAM: `kid` không đổi, modulus đổi. Hệ quả ở [iam-sso.md](iam-sso.md#81-quản-lý-key).

## 4. Kiểm tra đăng nhập Google theo từng app

Mỗi dòng bắt đầu từ cửa sổ ẩn danh, trừ khi ghi khác. Trạng thái hiện tại của tất cả: **chưa chạy**.

| ID | Tài khoản | App | Kết quả mong đợi theo code |
|---|---|---|---|
| IAM-01 | `new-user` | App 1 | Tạo `app_user` role `USER`, tenant 1, gắn `google_sub`, nhận JWT `USER` |
| IAM-02 | `new-user`, lần hai | App 1 | Không tạo thêm, nhận JWT `USER` |
| IAM-03 | `admin` | App 1 | `#error=not_provisioned`, không tạo tài khoản |
| IAM-04 | `admin` | App Admin | Tạo hoặc nâng thành `ADMIN`, nhận JWT `ADMIN` |
| IAM-05 | `new-user` | App Admin | `#error=not_provisioned` |
| IAM-06 | `other-admin` | App Admin | `#error=not_provisioned` |
| IAM-07 | `other-admin` | Support | JWT `ADMIN`, giao diện duyệt phiếu |
| IAM-08 | `new-user` | Support | JWT `USER`, giao diện gửi phiếu |
| IAM-09 | `unknown` | Support | `#error=not_provisioned`, không tạo tài khoản |
| IAM-10 | Tài khoản có `google_sub` đã bị sửa trong DB | App 1 | `#error=not_provisioned`, không ghi đè `google_sub` |
| IAM-11 | Bất kỳ, bấm Hủy ở Google | App 1 | `#error=login_failed` |

Sau mỗi ca thành công, kiểm tra JWT bằng cách giải mã phần payload: có `iss`, `sub`, `email`, `tenant_id` dạng số, `role`, `iat`, `exp`, và `exp - iat` là 900 giây. URL phải không còn fragment sau khi trang tải xong.

Kiểm tra tài khoản:

```bash
docker exec -it hotelbooking-postgres psql -U hotelbooking -d hotelbooking -c \
  "SELECT email, role, tenant_id, google_sub IS NOT NULL AS google_bound FROM app_user ORDER BY email;"
```

## 5. Kiểm tra SSO, đăng xuất và tích hợp

Trạng thái hiện tại của tất cả: **chưa chạy**.

| ID | Các bước | Kết quả mong đợi theo code |
|---|---|---|
| SSO-01 | `new-user` vào App 1, rồi bấm đăng nhập ở Support | Không gặp Google, nhận JWT mới |
| SSO-02 | `admin` vào App Admin, rồi bấm đăng nhập ở Support | Không gặp Google, JWT `ADMIN` |
| SSO-03 | `new-user` vào App 1, rồi bấm đăng nhập ở App Admin | `#error=not_provisioned`, phiên IAM bị hủy |
| SSO-04 | Sau SSO-03, bấm đăng nhập ở Support | Phải qua Google lại |
| SSO-05 | `admin` vào App Admin, rồi bấm đăng nhập ở App 1 | `#error=not_provisioned`, phiên IAM bị hủy |
| SSO-06 | Tải lại trang sau khi đăng nhập | Mất JWT, phải bấm đăng nhập lại, không cần qua Google nếu phiên còn |
| OUT-01 | Đăng xuất ở App 1 | Về `#logged_out`, lần sau phải qua Google |
| OUT-02 | Mở Support, đăng xuất ở App 1, gọi API tiếp ở Support | API vẫn chạy đến khi JWT hết hạn |
| JWT-01 | JWT của IAM gọi `GET /api/hotels` qua Gateway | `200`, nếu đã đặt issuer và JWKS |
| JWT-02 | Giữ nguyên `.env` mặc định, đăng nhập rồi gọi API | `401` |
| JWT-03 | Đợi JWT hết hạn, gọi API | `401`, frontend xóa JWT và yêu cầu đăng nhập lại |
| KEY-01 | Restart riêng IAM, đăng nhập lại, gọi API qua Gateway | Dự kiến `401` cho đến khi restart Gateway |
| RACE-01 | Hai tab đăng nhập hai app song song khi chưa có phiên | Có thể về nhầm app, chưa có quy tắc xử lý |

## 6. Kiểm tra phân quyền API

Phần này không phụ thuộc Google và đã có trong `backend/test/20-api-workflow.sh` cùng test của từng service: thiếu JWT, sai role, khác tenant, khác chủ. Lần chạy ngày 05/11/2026 đạt toàn bộ với JWT của `dev-token`.

## 7. Điều kiện nghiệm thu IAM

IAM chỉ được coi là đã nghiệm thu khi:

- [x] Mục 2 và 3 đạt.
- [ ] Mục 4 đạt toàn bộ với Google thật.
- [ ] Mục 5 đạt toàn bộ, trừ `RACE-01` và `KEY-01` là giới hạn đã biết nếu chưa sửa.
- [ ] Có test tự động cho chính sách theo app.
- [ ] Không có bí mật nào trong bằng chứng.
