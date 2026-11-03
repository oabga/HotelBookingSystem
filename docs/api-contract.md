# StayHub — API Contract chung (Google SSO + Rooms + Booking)

> **Phiên bản:** 2.0, ngày 03/11/2026. Thay thế bản 1.0 ngày 02/11/2026.
> **Trạng thái:** **BẢN NHÁP CHỜ KÝ LẠI G2.** Booking đã có code và test; các phần khác là mục tiêu để owner triển khai. Chữ ký của bản 1.0 không còn hiệu lực vì bản này đổi nghiệp vụ đặt phòng.
> **Nguồn thiết kế:** [`erd-v2.md`](erd-v2.md) và migration trên `main` quyết định dữ liệu và luồng đặt phòng. [`iam-google-sso-demo-plan-v2.md`](iam-google-sso-demo-plan-v2.md) quyết định scope, kiến trúc và owner. Những chỗ plan còn nói `available` và hold đã lỗi thời, xem §12.
> **Phạm vi:** Demo local trong 3 ngày, 4 vai A/B/C/D. Đây là hợp đồng giữa các thành viên và các ứng dụng, không phải đặc tả production.

## 0. Quyết định chung và ranh giới

- **Google SSO thay V-ID**, thông qua `iam-service` đóng vai trò **OAuth2/OIDC client của Google**; IAM phát **JWT nội bộ** RS256. Không dùng Keycloak; không xây OAuth2 authorization server riêng.
- Có **2 tenant**, 2 role `USER` và `ADMIN`. `USER` xem/đặt phòng; `ADMIN` xem/thêm phòng/sửa giá và xem booking trong tenant mình. `ADMIN` **không** gọi API tạo booking.
- Phòng được đặt theo **khoảng thời gian `[checkIn, checkOut)` có giờ thật**. `room` không có cờ `available`; phòng còn trống hay không phụ thuộc vào các booking đã có. Đặt được thì booking **`CONFIRMED` ngay**. Ràng buộc `EXCLUDE` của database bảo đảm không có hai booking đè giờ trên cùng một phòng.
- **Booking không gọi Catalog khi đặt phòng** và không còn API hold. Hai service dùng chung database `hotelbooking`, ràng buộc FK ghép chặn phòng hoặc user sai tenant.
- **Không triển khai** thanh toán, hủy booking, notification/push, idempotency key, refresh/revocation, UI đầy đủ hay message broker.
- **Owner:** **A (Bảo)** — IAM/JWT/PKCE/Gateway/CORS/root platform; **B (Hòa)** — Catalog/Room; **C (Phúc)** — Booking; **D (Đạt)** — hai frontend demo, `docs/demo.http`, kiểm thử E2E, tài liệu/bằng chứng. D chỉ đề xuất/review contract, không tự sửa service của A/B/C.
- **Quy tắc ưu tiên:** File này chốt **chi tiết interface** sau khi cả bốn người ký nhận. Điểm thay đổi so với bản 1.0 được liệt kê ở §12, **không mặc nhiên là quyết định mentor**.

## 1. Quy ước toàn hệ thống

| Mục | Quy ước thống nhất |
|---|---|
| Môi trường | Local demo, dùng duy nhất hostname `localhost` trong URL browser/callback; **không trộn** `localhost` với `127.0.0.1` khi thử SSO/cookie |
| Frontend | App 1 `http://localhost:3000`; App 2 `http://localhost:3001` (hai origin khác nhau) |
| API công khai cho frontend | Gateway `http://localhost:8080`; API được gọi dưới `/api/**` |
| IAM (điều hướng/login/JWKS) | `http://localhost:8084` **trực tiếp**, không cần qua Gateway |
| Service nội bộ | Catalog `http://localhost:8081`; Booking `http://localhost:8082` |
| Database | Một database chung `hotelbooking`, schema `public`. Mỗi service có bảng lịch sử Flyway riêng (`flyway_schema_history_iam`, `_catalog`, `_booking`). Thứ tự migrate bắt buộc trên DB sạch: IAM → Catalog → Booking |
| Dữ liệu | JSON UTF-8; request có body JSON gửi `Content-Type: application/json`; response JSON dùng `camelCase` |
| Ngày giờ | Response luôn là ISO 8601 UTC, ví dụ `2026-11-03T02:15:30Z`. Request `checkIn`/`checkOut` là ISO 8601 **bắt buộc có múi giờ**, ví dụ `2026-12-01T14:00:00+07:00`. JWT `iat`/`exp` là Unix seconds |
| Tiền | `price`: JSON number, **VND**, `> 0`, tối đa `99999999.99`, tối đa 2 chữ số thập phân, đúng giới hạn cột `NUMERIC(10,2)`; không dùng float/double cho phép tính |
| ID phòng/booking | Số nguyên dương (DB `BIGINT`); ID không hợp lệ → 400 |
| `tenant_id` | Số nguyên dương trong JWT và DB (`BIGINT`); response JSON dùng `tenantId` (number) |
| Danh sách | Mảng JSON, không phân trang trong demo, theo `id` tăng dần; rỗng → `[]` |
| Tạo mới | `201 Created` với JSON tài nguyên; không bắt buộc header `Location` trong demo |
| Không tin dữ liệu client | `tenantId`, role, người tạo booking **chỉ được suy ra từ JWT đã xác minh**, không nhận từ body/query/header tùy ý |
| Field lạ ở request | DTO chỉ ánh xạ các trường được phép; trường lạ (kể cả `tenantId`) bị **bỏ qua**, tuyệt đối không ảnh hưởng tenant/quyền. Có test chứng minh không thể đổi tenant qua body |
| Authentication | `Authorization: Bearer <stayhub-jwt>` cho **mọi** API `/api/rooms/**`, `/api/bookings/**`; **không** dùng Google access token trực tiếp |

**Không có endpoint nào được coi là chạy thật chỉ vì đã có contract.** Hiện chỉ `booking-service` đã có code; Catalog, IAM và Gateway chưa có API. Khi kiểm thử, kết quả chạy thật mới là bằng chứng.

## 2. Bản đồ route và phân quyền (source of truth)

Gateway **bỏ đúng một tiền tố `/api`** bằng `StripPrefix=1` (hoặc rewrite tương đương) và **forward nguyên Bearer token**. Dấu `*`/`{id}` chỉ là ký hiệu, không phải phần URL literal.

| Frontend/HTTP script gọi | Service nhận | Role | Thành công | Lỗi chính | Owner |
|---|---|---|---|---|---|
| `GET /api/rooms` | `GET /rooms` | `USER`, `ADMIN` | 200 | 401 | B |
| `GET /api/rooms/{id}` | `GET /rooms/{id}` | `USER`, `ADMIN` | 200 | 400, 404 | B |
| `POST /api/rooms` | `POST /rooms` | `ADMIN` | 201 | 400, 403 | B |
| `PUT /api/rooms/{id}/price` | `PUT /rooms/{id}/price` | `ADMIN` | 200 | 400, 403, 404 | B |
| `POST /api/bookings` | `POST /bookings` | `USER` | 201 | 400, 403, 404, 409 | C |
| `GET /api/bookings` | `GET /bookings` | `USER`, `ADMIN` | 200 | 401 | C |
| `GET /api/bookings/{id}` | `GET /bookings/{id}` | `USER`, `ADMIN` | 200 | 400, 404 | C |

| Direct call (không qua Gateway) | Người gọi | Quyền/yêu cầu | Thành công | Lỗi chính | Owner |
|---|---|---|---|---|---|
| `GET http://localhost:8084/auth/start?app=app1\|app2` | Browser navigation | `app` trong allowlist | 302 | 400, 403 trong luồng đăng nhập | A |
| `GET http://localhost:8084/login/oauth2/code/google` | Google → IAM | OAuth2 `code`/`state` | Theo luồng | Theo luồng OAuth2 | A |
| `GET http://localhost:8084/.well-known/jwks.json` | Gateway/B/C | Public | 200 | 5xx nếu IAM lỗi | A |

Tất cả business API yêu cầu JWT hợp lệ; ngoài lỗi được liệt kê vẫn có thể trả 401 (thiếu/sai token) hoặc 500 khi lỗi server chưa xử lý. **Không có route** `/dev/**`, `/auth/**` trên Gateway, và không còn API `/internal/**`. OAuth/SSO/JWKS truy cập trực tiếp IAM theo bảng trên.

### Quy tắc thứ tự lỗi

Áp dụng cho business API khi có nhiều sai sót: **401 (JWT) → 403 (role) → 400 (tham số/body) → 404 (không thấy trong tenant/owner) → 409 (đè giờ)**. Riêng lỗi 403 do user chưa provision chỉ được phát hiện khi lưu booking, nên có thể đứng sau 404 hoặc 409. Lỗi hạ tầng như 500 là nhánh riêng; không ép thành 409. `OPTIONS` CORS hợp lệ được xử lý trước bộ lọc JWT.

| HTTP | Ý nghĩa | Response body |
|---|---|---|
| **400** | ID không nguyên dương, body thiếu/sai kiểu, ràng buộc `name`/`price`/`roomId`/`checkIn`/`checkOut` sai | `application/problem+json` |
| **401** | Thiếu, sai chữ ký, issuer sai, token hết hạn, thiếu/malformed JWT claim bắt buộc | Không ràng buộc JSON; chỉ kiểm tra status |
| **403** | JWT hợp lệ nhưng role không đủ; hoặc email/tenant trong JWT không khớp `app_user` khi tạo booking | Không ràng buộc JSON; chỉ kiểm tra status |
| **404** | Bản ghi không tồn tại, thuộc tenant khác hoặc booking của user khác | `application/problem+json` |
| **409** | Phòng trong tenant đã có booking đè lên khoảng thời gian yêu cầu | `application/problem+json` |
| **500** | Lỗi nội bộ, ví dụ database không truy cập được | `application/problem+json` khi có thể |

Định dạng lỗi Spring `ProblemDetail` ví dụ cho **404**:

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Room not found",
  "instance": "/rooms/99"
}
```

- Client/test chỉ phụ thuộc **HTTP status**, không parse `detail`/`title` để quyết định nhánh.
- **404 của ID không tồn tại và ID thuộc tenant khác phải có status/body tương đương**, không kể cho client record thuộc tenant khác. Không chứa SQL, stack trace, token hay dữ liệu người khác.
- `ProblemDetail` áp dụng cho các lỗi 400/404/409/500 do service quản lý; 401/403 có thể do Spring Security tạo body trống.

## 3. IAM — Google login, PKCE, SSO, JWT (A)

### 3.1 URL, luồng browser và callback

| Thao tác | URL chính xác | Kết quả |
|---|---|---|
| App 1 bắt đầu login | `GET http://localhost:8084/auth/start?app=app1` | 302 sang Google hoặc redirect theo session IAM |
| App 2 bắt đầu login | `GET http://localhost:8084/auth/start?app=app2` | Như trên; dùng chung session IAM trong browser profile |
| Google callback **về IAM** | `http://localhost:8084/login/oauth2/code/google` | Spring Security xử lý `code`/`state` + PKCE, không phải URL frontend |
| IAM redirect **về App 1** | `http://localhost:3000/callback/#token=<jwt>` | HTTP 302, token trong **URL fragment** |
| IAM redirect **về App 2** | `http://localhost:3001/callback/#token=<jwt>` | HTTP 302, token trong **URL fragment** |
| Khóa xác minh công khai | `GET http://localhost:8084/.well-known/jwks.json` | 200 JSON JWKS |

**Quy tắc serve static:** D tạo đường dẫn `frontend-demo/app1/callback/index.html` và `frontend-demo/app2/callback/index.html` **hoặc** cấu hình HTTP static server thực sự phục vụ `/callback/` bằng chính HTML/JS của mỗi app. **Không dùng `/callback` tùy tiện nếu server trả 404**. Chuẩn `/callback/` (có slash cuối) trong bảng trên là địa chỉ chính thức của bản contract này; không thay bằng `/` nếu chưa cập nhật contract và thông báo A/D.

- Chỉ cho phép `app=app1` hoặc `app=app2`; thiếu/sai `app` → **400**, không redirect đến URL tùy ý. IAM **không nhận `redirect_uri` từ người dùng** để chuyển hướng tuỳ ý. Lưu `app` đã kiểm tra cùng OAuth `state`/server session, khôi phục sau Google callback.
- Browser bắt đầu login bằng điều hướng trang (`window.location.assign`), **không** `fetch` sang `/auth/start`.
- IAM sử dụng Google OAuth2 **Authorization Code + PKCE S256** với Google OAuth client loại **Web application** và client secret chỉ ở server. Spring Security phải kiểm tra hợp lệ ID token do Google cấp (issuer, audience, chữ ký và thời hạn) **trước** khi sử dụng các claim `sub`, `email`, `email_verified` để tìm user. Đăng ký tại Google Cloud duy nhất redirect URI về **IAM** (`http://localhost:8084/login/oauth2/code/google`); frontend `/callback/` **không** phải Google redirect URI.
- Authorization request tới Google phải có `code_challenge` và `code_challenge_method=S256`. IAM giữ `code_verifier` và kiểm tra `state` server-side; không đưa verifier/secret/code vào frontend hoặc logs. Với confidential client, A phải **bật PKCE tường minh và kiểm tra thực tế**, không giả định mặc định Spring đã làm.
- **Không dùng `prompt=login` hoặc buộc nhập lại mật khẩu**. Google có thể hiện chọn tài khoản/consent; điều kiện SSO là App 2 **không cần nhập lại mật khẩu** trong cùng browser profile khi phiên Google/IAM còn hiệu lực.

### 3.2 Kết quả đăng nhập và hành vi lỗi

| Trường hợp | Hành vi đã chốt |
|---|---|
| `app` thiếu hoặc không nằm allowlist | HTTP **400** tại IAM, không có redirect |
| Google OAuth trả danh tính hợp lệ, email đã provision | IAM phát JWT nội bộ và HTTP **302** về đúng `/callback/#token=...` của app ban đầu |
| IAM session hợp lệ đã xác thực user provision | Có thể phát JWT mới và redirect về đúng app mà **không cần login Google lần nữa** |
| Email Google không được provision / `email_verified != true` / `google_sub` đã liên kết nhưng không khớp | **403 tại IAM**, xóa authentication/session đăng nhập chưa được phép, không mint JWT, không auto-provision, không gán tenant mặc định; thông báo 403 an toàn cho người dùng |
| User hủy Google login hoặc OAuth state/code không hợp lệ | **Không cấp JWT**; có thể trả trang báo lỗi IAM (4xx) an toàn; không coi là SSO PASS |

**Phiên IAM đã tồn tại cũng phải đối chiếu user vẫn được phép sử dụng trước khi IAM cấp JWT mới**, không dùng session cũ để bỏ qua allowlist hoặc thay đổi tenant/role.

**Chính sách provision tối giản:** `app_user` được seed sẵn với email Google test thực, `tenant_id`, role. IAM kiểm tra email đã xác minh, chuẩn hóa email (`trim` + lowercase), tìm đúng bản ghi pre-provision. Khi lưu `google_sub`, nếu chưa bind có thể bind vào lần login hợp lệ đầu tiên; nếu đã bind thì **bắt buộc so khớp**. Không tạo user/tenant chỉ vì Gmail mới xuất hiện. Email và secret test thật không commit vào repo.

**Frontend D:** tại `/callback/`, đọc `location.hash` dạng `#token=...`; lập tức dùng `history.replaceState` loại fragment khỏi thanh địa chỉ, chỉ giữ JWT trong **biến bộ nhớ** của tab hiện tại. **Không dùng** localStorage/sessionStorage/cookie tự tạo để chứa JWT và không log nguyên token. Nếu trang callback cần quay về màn hình chính, chuyển giao token **trong runtime cùng trang**, không làm mất token bằng full page navigation. Nếu tải lại trang, người dùng cần login lại (IAM có thể SSO không hỏi mật khẩu).

**Không có `/auth/logout` hay `/dev/token` trong MVP/contract bắt buộc.** Một nút “Xóa phiên ở trang này” (nếu D làm) chỉ xóa token trong memory, **không** gọi đó là Google logout hay JWT revocation. Không dùng API mint token theo `tenantId`/role do caller tự cung cấp để chứng minh Google SSO hoặc bảo mật tenant. Negative JWT tests sử dụng fixture/unit test của A hoặc token đã hết hạn thực tế.

### 3.3 JWT nội bộ và JWKS

IAM ký JWT với **RS256**; JOSE header có `alg=RS256`, `kid` để chọn public key. Phát hành token sống **15 phút**. Không có refresh, revoke hay rotation tự động.

Ví dụ **payload minh họa**, không phải token dùng thật:

```json
{
  "iss": "http://localhost:8084",
  "sub": "2",
  "email": "user-tenant1@example.com",
  "tenant_id": 1,
  "role": "USER",
  "iat": 1793671200,
  "exp": 1793672100
}
```

| Claim/thuộc tính | Yêu cầu | Không hợp lệ |
|---|---|---|
| Signature/`kid` | Verify public JWKS của IAM, tin cậy duy nhất thuật toán RS256 | 401 |
| `iss` | Bắt buộc, đúng chuỗi `http://localhost:8084` | 401 |
| `exp` | Bắt buộc, chưa hết hạn (có clock skew phù hợp Spring Security) | 401 |
| `iat` | Bắt buộc khi IAM mint, số Unix seconds | 401 nếu thiếu/sai kiểu theo custom validator |
| `sub` | Bắt buộc, không rỗng; là ID nội bộ ổn định, **chuỗi** (có thể là `app_user.id` đổi sang chuỗi) | 401 |
| `email` | Bắt buộc, không rỗng; IAM phát đúng email đã chuẩn hóa của `app_user` (chữ thường) | 401 |
| `tenant_id` | Bắt buộc, **số nguyên JSON dương**, bằng `app_user.tenant_id`. Chuỗi như `"1"` không hợp lệ | 401 |
| `role` | Bắt buộc, chỉ `USER` hoặc `ADMIN` | 401 |

- **Gateway, Catalog, Booking cùng kiểm tra chữ ký, issuer, expiry và các claim bắt buộc**, không cho token thiếu tenant/role lọt qua. Mapping `role` → `ROLE_USER`/`ROLE_ADMIN`; lấy `tenant_id` từ JWT đã verify, không có tenant mặc định.
- `jwks-uri`: `http://localhost:8084/.well-known/jwks.json`. IAM không có OIDC discovery endpoint nên Resource Server lấy khóa bằng `jwk-set-uri`. **Decoder chỉ cấu hình `jwk-set-uri` không kiểm tra `iss`**, nên Gateway và mỗi service phải thêm validator issuer và có test token sai issuer trả 401. Booking dùng property `security.jwt.issuer`; đây là mẫu tham khảo.
- **Ownership Booking** dựa trên cặp `tenant_id` + `email` trong JWT đã xác minh, khớp FK `booking (tenant_id, user_email) → app_user (tenant_id, email)`. Không bao giờ lấy email từ dữ liệu client gửi. Service đổi email về chữ thường trước khi dùng.
- Không trả private RSA key, client secret hay mật khẩu trong JWKS/log/README.

Ví dụ phản hồi JWKS (các giá trị khóa minh họa):

```json
{
  "keys": [
    {
      "kty": "RSA",
      "use": "sig",
      "alg": "RS256",
      "kid": "stayhub-key-1",
      "n": "<public-modulus-base64url>",
      "e": "AQAB"
    }
  ]
}
```

**Chú ý key cho demo:** keypair phải **ổn định đủ lâu cho quá trình chạy/demo** và JWKS đúng với private key đang dùng ký; khi khởi động lại IAM bằng key mới, JWT cũ có thể hết hiệu lực. Không commit private key. Dùng reset/login lại sau restart nếu không có key persistence.

## 4. Catalog — Rooms API (B)

### 4.1 Room JSON và dữ liệu mẫu

Response `Room` có đủ **4 trường**:

| Trường | Kiểu | Ghi chú |
|---|---|---|
| `id` | integer > 0 | ID room DB |
| `tenantId` | integer > 0 | Tenant của row, trùng tenant trong JWT, chỉ để hiển thị/đối chiếu |
| `name` | string | 1–100 ký tự sau trim; không blank |
| `price` | number | Đơn vị VND, >0, tối đa `99999999.99`, tối đa 2 decimal |

Ví dụ Room:

```json
{
  "id": 1,
  "tenantId": 1,
  "name": "Standard Room 101",
  "price": 500000.00
}
```

### 4.2 `GET /api/rooms` → `GET /rooms`

- `USER`/`ADMIN`: **200** + array Room, chỉ room có `tenant_id` bằng tenant claim.
- Có thể trả `[]`; không phân trang. Test dùng 2 JWT tenant khác nhau phải có danh sách không lẫn.
- **Không trả trạng thái còn trống.** Trong MVP, người dùng chọn phòng và khoảng giờ rồi gửi `POST /api/bookings`; phòng đã kín giờ đó sẽ nhận `409`. Nếu nhóm muốn hiển thị phòng trống theo giờ (`docs/erd-v2.md` §4), B và D phải cập nhật contract trước khi làm.

Ví dụ response 200:

```json
[
  {"id": 1, "tenantId": 1, "name": "Standard Room 101", "price": 500000.00},
  {"id": 2, "tenantId": 1, "name": "Deluxe Room 102", "price": 900000.00}
]
```

### 4.3 `GET /api/rooms/{id}` → `GET /rooms/{id}`

- `USER`/`ADMIN`: **200** + Room JSON như §4.1.
- ID không nguyên dương → **400**; room không tồn tại hoặc thuộc tenant khác → **404** giống nhau.

### 4.4 `POST /api/rooms` → `POST /rooms`

`ADMIN` mới được gọi. Request:

```json
{
  "name": "Suite Room 301",
  "price": 2500000.00
}
```

- `name`: string 1–100 ký tự sau trim, không blank; cho phép trùng tên.
- `price`: JSON number >0 và ≤`99999999.99`, tối đa 2 decimal.
- Không nhận tenant từ client; tạo với `tenant_id` lấy từ JWT. Trường `tenantId` lạ nếu bị gửi thêm cũng **không có tác dụng**.
- **201** + Room JSON gồm ID được DB cấp và tenant thật.
- `USER` gọi dù body đúng hay sai → **403**; `ADMIN` body thiếu/sai → **400**.

### 4.5 `PUT /api/rooms/{id}/price` → `PUT /rooms/{id}/price`

`ADMIN` mới được gọi. Request:

```json
{"price": 2000000.00}
```

- `price` cùng ràng buộc như POST; không thay name/tenant.
- **200** + Room JSON đã cập nhật.
- `USER` → **403**; ID/body sai → **400**; room không có trong tenant → **404** (không lộ trạng thái room tenant khác). Không thay giá nếu lỗi.

## 5. Booking — API tạo và xem booking (C)

### 5.1 Booking JSON và ownership

| Trường response | Kiểu | Ghi chú |
|---|---|---|
| `id` | integer > 0 | ID DB cấp |
| `tenantId` | integer > 0 | Tenant từ verified JWT |
| `roomId` | integer > 0 | Phòng đã đặt |
| `userEmail` | string | Email chữ thường từ JWT; dùng kiểm tra sở hữu trong tenant |
| `checkIn` | ISO UTC string | Thời điểm nhận phòng, tính cả điểm này |
| `checkOut` | ISO UTC string | Thời điểm trả phòng, không tính điểm này |
| `status` | string | Chỉ `CONFIRMED` trong MVP |
| `createdAt` | ISO UTC string | Thời gian tạo theo server |

Bảng `booking` do C sở hữu, định nghĩa ở `booking-service` `V1__create_booking.sql`. Client không thể cung cấp `tenantId`, `userEmail` hoặc `status` để quyết định record được lưu; các field lạ bị bỏ qua.

### 5.2 `POST /api/bookings` → `POST /bookings`

Chỉ `USER`. Request:

```json
{
  "roomId": 1,
  "checkIn": "2026-12-01T14:00:00+07:00",
  "checkOut": "2026-12-02T11:00:00+07:00"
}
```

| Trường | Bắt buộc | Quy tắc, sai thì **400** |
|---|---|---|
| `roomId` | Có | Số nguyên JSON dương; chuỗi `"1"` hay số `1.5` không hợp lệ |
| `checkIn` | Có | Chuỗi ISO 8601 có múi giờ (`Z` hoặc `+07:00`), năm từ 2000 đến 9999 |
| `checkOut` | Có | Như `checkIn`, và phải **sau** `checkIn` |

Thời gian được làm tròn xuống micro giây trước khi kiểm tra và lưu. Service không áp giờ nhận/trả phòng cố định và chưa chặn thời điểm trong quá khứ (§9).

Booking lấy `tenant_id` và `email` từ JWT đã verify rồi `INSERT`, không `SELECT` kiểm tra trước. Database quyết định kết quả:

| Kết quả khi lưu | Booking trả client | DB Booking |
|---|---|---|
| Lưu thành công | **201 `CONFIRMED`** | 1 booking mới |
| Phòng không tồn tại hoặc thuộc tenant khác | **404**, cùng body cho cả hai trường hợp | Không tạo booking |
| Phòng trong tenant đã có booking đè giờ | **409** | Không tạo booking |
| `email` hoặc `tenant_id` trong JWT không khớp `app_user` | **403** | Không tạo booking |
| Lỗi khác của database | **500**, không lộ chi tiết SQL | Không tạo booking |

Response **201** minh họa. Thời gian đã đổi sang UTC:

```json
{
  "id": 1,
  "tenantId": 1,
  "roomId": 1,
  "userEmail": "user-tenant1@example.com",
  "checkIn": "2026-12-01T07:00:00Z",
  "checkOut": "2026-12-02T04:00:00Z",
  "status": "CONFIRMED",
  "createdAt": "2026-11-03T02:15:30Z"
}
```

- **Quy tắc đè giờ:** hai khoảng `[checkIn, checkOut)` trên cùng phòng không được giao nhau. Người trước trả phòng đúng lúc người sau nhận phòng thì **không** đè giờ. Ví dụ với giờ cụ thể ở `docs/erd-v2.md` §2.
- `ADMIN` gọi `POST /bookings` → **403**. Cùng một `USER` gửi lại đúng request đó → **409**; không có idempotency key.
- **Không được lộ lịch của tenant khác:** phòng tenant khác luôn là 404, kể cả khi phòng đó đã có booking đúng khoảng giờ yêu cầu.
- Race test **từ state sạch**: các POST đồng thời vào cùng phòng, cùng khoảng giờ → đúng một **201**, còn lại **409**; bảng chỉ có một row. Có thể dùng hai USER cùng tenant; nếu dùng cùng JWT, ghi là **hai request**, không tuyên bố là hai người khác nhau.

### 5.3 `GET /api/bookings` → `GET /bookings`

- **200** + mảng Booking theo `id` tăng dần.
- `USER`: chỉ những booking có `tenant_id` **và** `user_email` khớp JWT.
- `ADMIN`: toàn bộ booking **trong tenant** từ JWT. Không bao giờ trả booking tenant khác, kể cả khi client gửi ID/tenant giả.
- Không có dữ liệu trả `[]`.

### 5.4 `GET /api/bookings/{id}` → `GET /bookings/{id}`

- **200** + Booking JSON nếu đúng quyền.
- **404** nếu ID không tồn tại, tenant khác, hoặc `USER` không phải chủ (`user_email` khác); response không tiết lộ lý do.
- **400** nếu ID không nguyên dương. `ADMIN` được xem booking của mọi user trong tenant mình.

## 6. Gateway & CORS (A), tích hợp browser (D)

A chịu trách nhiệm **Gateway/CORS**, D chỉ kiểm thử và báo nhu cầu origin/headers. Route phải match cả đường dẫn danh sách đúng `/api/rooms`, `/api/bookings` **và** đường dẫn có thêm `/{id}`; không để rule wildcard bỏ sót đường dẫn gốc. B/C bảo vệ service bằng JWT **kể cả khi gọi trực tiếp qua `:8081/:8082`**; Gateway không phải lớp bảo mật duy nhất.

| Rule | Giá trị |
|---|---|
| Route 1 | `/api/rooms/**` → `http://localhost:8081/rooms/**` (`StripPrefix=1`) |
| Route 2 | `/api/bookings/**` → `http://localhost:8082/bookings/**` (`StripPrefix=1`) |
| Bearer | Giữ nguyên `Authorization` khi chuyển tiếp |
| Từ chối | Không route `/dev/**` hay phần `/auth/**` tại Gateway |
| Preflight | `OPTIONS` hợp lệ xử lý trước JWT, không yêu cầu Bearer |

| CORS field | Giá trị |
|---|---|
| Allowed origins | `http://localhost:3000`, `http://localhost:3001` (exact match) |
| Methods | `GET, POST, PUT, OPTIONS` |
| Allowed headers | `Authorization, Content-Type` |
| Allow credentials | `false` (gửi JWT header, không fetch kèm cookie) |
| Wildcard origins | **Không** dùng `*` thay cho allowlist |

- Gateway trả preflight hợp lệ cho browser khi gửi JSON/Bearer; không chặn vì OPTIONS không có token. Các service không chủ động thêm CORS header trùng với gateway.
- Browser chỉ gọi business API qua **`http://localhost:8080/api/...`**, **không** gọi trực tiếp Catalog/Booking. Login là **browser navigation** sang IAM (khác với CORS `fetch`).
- Dùng `localhost` nhất quán với JWT issuer và allowlist. API lỗi 401/403 phải được UI hiển thị an toàn, không log JWT. Không coi 403 IAM do chưa provision là lỗi role 403 của API.

## 7. Seed, account alias, reset và ví dụ kiểm thử

### 7.1 Dữ liệu hợp đồng (alias; không chứa danh tính thật)

ID dưới đây theo thứ tự seed trong migration và **chỉ đúng trên DB vừa tạo sạch**.

| Tài nguyên | Giá trị dùng chung | Owner seed/xác nhận |
|---|---|---|
| Tenant 1 | ID `1`, Demo Hotel A | A |
| Tenant 2 | ID `2`, Demo Hotel B | A |
| Tenant 1 room seed | ID `1` (Standard 101), `2` (Deluxe 102) | B |
| Tenant 2 room seed | ID `3` (Standard 201), `4` (Deluxe 202) | B |
| Happy-path room | Tenant 1: `1` | B |
| Race-only room | Tenant 1: `5` (Race-Test Room 199) | B |
| Cross-tenant room | Tenant 2: `3` | B |
| Booking ID | ID phát sinh từ DB, **không hard-code** | C |
| Role | `USER`, `ADMIN` | A |
| Booking status | `CONFIRMED` | C |

| Alias | Mẫu trong `docker/seed/seed-app-users.example.sql` | Tenant, role |
|---|---|---|
| `ADMIN_1` | `admin-tenant1@example.com` | 1, `ADMIN` |
| `USER_1` | `user-tenant1@example.com` | 1, `USER` |
| `USER_1B` | `user2-tenant1@example.com` | 1, `USER`, dùng cho race test hai người |
| `ADMIN_2` | `admin-tenant2@example.com` | 2, `ADMIN` |
| `USER_2` | `user-tenant2@example.com` | 2, `USER` |

**Email Google thật**, client secret, Bearer JWT và RSA private key **không được lưu vào Git**. Bản seed thật đặt ở `docker/seed/*.local.sql`, đã có trong `.gitignore`. Đặt bảng alias + cách lấy token vào `docs/demo-data.md` (D tổng hợp với A/B/C), giá seed lấy từ B.

- **Reset demo:** chỉ cần `TRUNCATE booking RESTART IDENTITY`, vì phòng không còn trạng thái riêng. Khi demo lặp lần thứ hai, đảm bảo state hoàn toàn sạch hoặc dùng khoảng giờ khác.
- Mọi seed chỉ minh họa/local.

### 7.2 Ma trận kiểm thử hợp đồng tối thiểu

| ID | Request/tình huống | Kỳ vọng bắt buộc | Owner bằng chứng |
|---|---|---|---|
| T01 | GET rooms bằng USER_1 | 200, chỉ tenant 1 | B/D |
| T02 | GET rooms bằng USER_2 | 200, chỉ tenant 2 | B/D |
| T03 | POST rooms bằng USER_1 | 403, không thêm room | B/D |
| T04 | POST rooms bằng ADMIN_1 | 201, `tenantId=1` | B/D |
| T05 | PUT room price bằng USER_1 | 403, giá không đổi | B/D |
| T06 | PUT room tenant 2 bằng ADMIN_1 | 404, giá không đổi | B/D |
| T07 | POST room kèm `tenantId=2` bằng ADMIN_1 | Không thể tạo vào tenant 2; field lạ bỏ qua, kết quả tenant 1 | B/D |
| T08 | GET room ID của tenant khác | 404, không lộ dữ liệu | B/D |
| T09 | POST booking room 1 với một khoảng giờ bằng USER_1 | 201 CONFIRMED, thời gian trả về UTC, DB một row | C/D |
| T10 | POST lại cùng room, khoảng giờ đè lên booking T09 | 409, không tạo booking mới | C/D |
| T11 | Hai POST đồng thời room 5, cùng khoảng giờ, từ state sạch | **Một 201, một 409**; DB chỉ có 1 booking | C/D |
| T12 | USER_1 POST booking room 3 của tenant 2 | 404, không tạo booking | C/D |
| T13 | ADMIN_1 POST booking | 403 | C/D |
| T14 | USER_1 GET bookings | 200, chỉ của email USER_1 trong tenant 1 | C/D |
| T15 | ADMIN_1 GET bookings | 200, tất cả booking trong tenant 1 | C/D |
| T16 | USER_2 / USER_1B truy cập booking của USER_1 bằng ID | 404, không lộ owner/tenant | C/D |
| T17 | GET business API thiếu, sai chữ ký, hết hạn JWT | 401 mỗi trường hợp | A/D |
| T18 | JWT hợp lệ nhưng thiếu `tenant_id`/`role` | 401, test bằng fixture/unit hoặc cấu hình test A | A/D |
| T19 | Login Google user chưa provision/không verified | 403 IAM, không thêm user, không phát JWT | A/D |
| T20 | Login App 1 → App 2 cùng browser profile | Không nhập lại mật khẩu, app2 nhận JWT của IAM | A/D |
| T21 | Google authorize có `code_challenge` và `S256` | Evidence PKCE thực, Google callback thành công | A/D |
| T22 | POST booking room 1 ở khoảng giờ không đè, hoặc liền ngay sau booking T09 | 201 | C/D |
| T23 | POST booking `checkOut` không sau `checkIn`, hoặc thời gian thiếu múi giờ | 400, không tạo booking | C/D |
| T24 | `gradlew.bat clean build`, unit tests IAM/B/C, 3 Flyway histories | Build/test xanh, migrations thực, không skip để qua | A/B/C/D |
| T25 | USER_1 POST room 3 đúng khoảng giờ USER_2 đã đặt | 404 như phòng không tồn tại, không phải 409 | C/D |
| T26 | JWT `tenant_id` dạng chuỗi hoặc token sai `iss` | 401 ở Gateway và mọi service | A/B/C |

**Lưu ý test JWT:** Token hết hạn thật có thể phải đợi >15 phút hoặc dùng test fixture riêng; đừng commit/sao chép JWT. Token giả thiếu claim chỉ kiểm tra qua fixture của A, **không cần tạo endpoint phát JWT public**. Kiểm thử tranh phòng **phải gửi đồng thời**; chạy 2 request tuần tự chỉ kiểm được việc đặt lại trả 409.

## 8. Dữ liệu bàn giao để D lập frontend và `docs/demo.http`

| Ai giao | Dữ liệu/artifact cần giao | Điều kiện D xác nhận nhận bàn giao |
|---|---|---|
| **A → D/B/C** | IAM login URL + Google callback + **frontend callback `/callback/`**; issuer/JWKS, role/claims, origins, config CORS; cách có tài khoản Google được provision và thu evidence PKCE | D mở được hai origin, nắm cách login thật; B/C verify cùng kiểu JWT |
| **B → D** | Room JSON, GET/POST/PUT, ID seed 1–5 | D test UI và dữ liệu đúng tenant |
| **C → D** | Booking POST/GET JSON có `checkIn`/`checkOut`, error 400/403/404/409, cách test race, xem và xóa booking (`booking-service/README.md`) | D viết `demo.http` chính xác và có bằng chứng 201/409 |
| **A/B/C → D** | Lệnh chạy Gradle/DB, các unit test reports, 3 Mermaid diagrams và limitations đã biết | D tổng hợp vào `demo-script.md`, `test-evidence.md`, README |

D **không đưa token/client secret/private key vào `docs/demo.http`**. Dùng biến placeholder `@BASE_URL`, `@JWT_USER_1`, `@JWT_ADMIN_1`, `@JWT_USER_2`, `@ROOM_ID`, `@BOOKING_ID`, v.v.; các bearer token thật chỉ đưa vào runtime/test environment không theo Git. Khi contract này được ký, D sửa tài liệu/frontend theo URL `/callback/` và request/response ở đây; những phần đã mock vẫn đánh dấu `MOCK ONLY` cho đến khi chạy thật.

## 9. Ranh giới lỗi và giới hạn cần công bố khi demo

1. **Dùng chung database giữa các service.** Booking có FK và một truy vấn đọc trực tiếp sang bảng `room`, nên đổi schema `room` hoặc `app_user` có thể ảnh hưởng Booking. Đây là đánh đổi có chủ ý của thiết kế một database, không phải database-per-service.
2. **Chưa chặn đặt phòng trong quá khứ và chưa áp giờ nhận/trả phòng cố định.** ERD chưa có quy tắc này; client tự gửi giờ.
3. **Có thể chậm khi nhiều request tranh cùng một khoảng giờ.** Các `INSERT` đồng thời có thể chờ nhau đến khi PostgreSQL phát hiện deadlock (mặc định sau 1 giây). Bên bị hủy vẫn nhận 409, kết quả đúng nhưng phản hồi có thể mất vài giây.

4. **Google SSO không đồng nghĩa phát JWT OIDC chuẩn cho bên thứ ba.** IAM là OAuth2 client và JWT issuer nội bộ, không phải full authorization server. Token ở URL fragment là simplification demo; XSS/browser extension vẫn có rủi ro.
5. **Không có Google logout toàn cục, JWT revoke, refresh token hoặc key rotation.** Clearing token trong tab không thu hồi JWT đã phát. `localhost` HTTP chỉ dùng local, không dùng mô hình này ở production.
6. `UNIT TEST` của A/B/C khác với `INTEGRATION/E2E` của D; không đánh dấu PASS khi chỉ có code/markdown hoặc mock.

## 10. Gate G2 — Điều kiện ký và đóng băng contract

G2 chỉ **PASS khi tất cả mục sau hoàn thành**:

- [ ] A/B/C/D cùng xác nhận **bỏ hold**, dùng thiết kế đặt phòng theo khoảng giờ của `erd-v2.md`; đồng ý owner như §0.
- [ ] A xác nhận **`tenant_id` là số nguyên JSON**, email trong JWT là chữ thường, **IAM redirect `.../callback/`**, OAuth redirect IAM/PKCE, issuer, JWT claim validator, CORS/Gateway và lỗi email chưa provision **403**; đồng thời sửa các mục lỗi thời trong plan (§12).
- [ ] B xác nhận Room JSON không có `available`, `tenantId` là số, giới hạn giá, tenant isolation và ID seed.
- [ ] C xác nhận JSON Booking, ownership theo `user_email`, bảng kết quả §5.2 và các giới hạn §9.
- [ ] D xác nhận hai static callback URLs **thực sự được server phục vụ**, frontend/demo.http có thể triển khai theo response/error mới, không dùng API ngoài scope.
- [ ] Tất cả nhất trí thay thế contract cũ và chỉ đổi contract sau đó bằng PR có bên cung cấp **và** bên sử dụng API review.

| Người | Vai trò | Phần cung cấp | Phần sử dụng/duyệt | Xác nhận G2 |
|---|---|---|---|---|
| **Bảo (A)** | IAM/Platform | §3, §6 | JWT/API requirements của B/C/D | [ ] |
| **Hòa (B)** | Catalog | §4, seed §7 | JWT A | [ ] |
| **Phúc (C)** | Booking | §5 | JWT A, bảng `room` của B, `app_user` của A, demo D | [ ] |
| **Đạt (D)** | Frontend/QA | HTML, `demo.http`, kiểm thử §7/§8 | IAM/Rooms/Booking, scripts/evidence | [ ] |

**Một PR tích hợp không được đổi field/path/status/role/issuer/port hoặc luật giữ phòng chỉ để “chạy được” mà không cập nhật contract và nhận xác nhận của bên liên quan.** Sau G2, check PR/commit + log/HTTP response/test mới là bằng chứng PASS khi triển khai.

## 11. Nguồn kỹ thuật để implement (không thay thế phê duyệt nhóm)

- Plan: [`iam-google-sso-demo-plan-v2.md`](iam-google-sso-demo-plan-v2.md); thiết kế dữ liệu: [`erd-v2.md`](erd-v2.md).
- Spring Security — [Authorization Code + PKCE (confidential client cần bật tường minh)](https://docs.spring.io/spring-security/reference/servlet/oauth2/client/authorization-grants.html).
- Spring Security — [JWT Resource Server: `jwk-set-uri` và kiểm tra issuer](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).
- Spring Security — [CORS & preflight trước authentication](https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html).
- Google — [OAuth 2.0 for Web Server Applications: callback khớp tuyệt đối](https://developers.google.com/identity/protocols/oauth2/web-server).

## 12. Thay đổi so với bản 1.0

| Nội dung | Bản 1.0 | Bản 2.0 | Lý do |
|---|---|---|---|
| Đặt phòng | Cờ `available`, không có ngày giờ | Khoảng `[checkIn, checkOut)` có giờ | `erd-v2.md`, migration `room` và `booking` |
| Chống tranh phòng | `UPDATE ... WHERE available = TRUE` qua API hold | `EXCLUDE` trên bảng `booking` | `erd-v2.md` §3 |
| API `/internal/rooms/{id}/hold`, `X-Internal-Service-Key` | Bắt buộc | **Bỏ** | Không còn cột `available` để giữ |
| Lỗi 503 và orphan hold | Có | **Bỏ** | Booking không còn gọi Catalog |
| Database | 3 database riêng | Một database `hotelbooking` | `erd-v2.md`, `docker-compose.yml` |
| `tenant_id`, `tenantId` | UUID, chuỗi | Số nguyên | `tenant.id` là `BIGINT`. Kiểu trong JWT là đề xuất của C, **A cần xác nhận** |
| Chủ booking | `user_sub`, trường `userSub` | `user_email`, bỏ `userSub` | Bảng `booking` không có `user_sub` |
| `Room.available` | Có | **Bỏ** | `room` không có cột này |
| Giá tối đa | `9999999999.99` | `99999999.99` | Bản cũ vượt giới hạn `NUMERIC(10,2)`, sẽ gây lỗi khi lưu |
| Seed | Tenant UUID, phòng 1–6, race phòng 3 | Tenant 1, 2; phòng 1–5; race phòng 5; cross-tenant phòng 3 | `V2__seed_rooms.sql` |
| Kiểm tra `iss` | Khai báo cả `issuer-uri` và `jwk-set-uri` | Dùng `jwk-set-uri` và validator issuer | Khớp ghi chú trong `application.yml` của `main`, giữ yêu cầu kiểm tra `iss` |
| Vị trí file | `docs/contracts/api-contract.md` | `docs/api-contract.md` | Đường dẫn plan dùng; bản cũ đã bị xóa khỏi `main` |

**Mục đã có quyết định từ bản 1.0 và giữ nguyên:** frontend callback `/callback/` (plan còn ghi `/callback`), bỏ `/dev/token`, không có `/auth/logout`, token sống 15 phút, field lạ bị bỏ qua, thứ tự lỗi.

**Mục plan đang lỗi thời, A cần sửa cho khớp:** `iam-google-sso-demo-plan-v2.md` còn nói phòng có cờ `available` (phần giữ nguyên tối giản và §4.1), `POST /api/bookings` chỉ có `roomId`, API hold và đoạn "Điểm phải code đúng ở hold" (§4.2), nhiệm vụ hold của B, mốc G4 Gateway → Booking → Catalog, và sơ đồ liên quan.

**Nguyên tắc cuối:** việc ký G2 và việc API chạy đúng vẫn cần xác nhận, triển khai và kiểm thử thực tế. Sau khi ký, chỉ đổi contract bằng PR có bên cung cấp **và** bên sử dụng API review.
