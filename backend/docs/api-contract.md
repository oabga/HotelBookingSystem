# StayHub — API Contract chung (Google SSO + Hotels + Booking)

> **Phiên bản:** 3.0, ngày 03/11/2026. Thay thế bản 2.0 cùng ngày.
> **Trạng thái:** **BẢN NHÁP CHỜ KÝ LẠI G2.** Chưa có service nào chạy theo bản này: `booking-service` hiện vẫn chạy theo bản 2.0, các phần khác là mục tiêu để owner triển khai. Chữ ký của các bản trước không còn hiệu lực vì bản này đổi mô hình dữ liệu và nghiệp vụ đặt phòng.
> **Nguồn thiết kế:** [`erd-v2.md`](erd-v2.md) quyết định dữ liệu và luồng đặt phòng. [`iam-google-sso-demo-plan-v2.md`](iam-google-sso-demo-plan-v2.md) quyết định kiến trúc và owner. Những chỗ plan và migration chưa khớp với bản này được liệt kê ở §12.
> **Phạm vi:** Demo local trong 3 ngày, 4 vai A/B/C/D. Đây là hợp đồng giữa các thành viên và các ứng dụng, không phải đặc tả production.

## 0. Quyết định chung và ranh giới

- **Google SSO thay V-ID**, thông qua `iam-service` đóng vai trò **OAuth2/OIDC client của Google**; IAM phát **JWT nội bộ** RS256. Không dùng Keycloak; không xây OAuth2 authorization server riêng.
- Có **2 tenant**, 2 role `USER` và `ADMIN`. Một tenant có thể có nhiều khách sạn. `USER` tìm phòng, đặt, thanh toán mô phỏng và huỷ đơn của mình. `ADMIN` thêm loại phòng, sửa giá, xem tồn kho, xem và huỷ booking trong tenant mình. `ADMIN` **không** tạo hoặc thanh toán booking.
- **Flow chính:** khách chọn khách sạn, ngày nhận và trả phòng, bấm Tìm kiếm; hệ thống đọc `room_type` và `room_inventory`, rồi chỉ trả về các loại phòng còn trống ở mọi đêm trong kỳ ở.
- Đơn vị bán là **loại phòng theo từng đêm**, không phải phòng cụ thể. Kỳ ở là khoảng ngày `[checkIn, checkOut)`: tính đêm `checkIn`, không tính đêm `checkOut`.
- Đặt phòng tạo đơn `PENDING_PAYMENT` và **trừ tồn ngay**. Thanh toán mô phỏng chuyển đơn sang `CONFIRMED`. User chủ đơn huỷ khi còn `PENDING_PAYMENT` sẽ chuyển sang `CANCELLED` và trả tồn; quá hạn chuyển `EXPIRED` và cũng trả tồn. Trừ/hoàn tồn và ghi/cập nhật booking nằm trong cùng transaction.
- **Booking không gọi HTTP sang Catalog.** Hai service dùng chung database `hotelbooking`: Catalog tạo `room_inventory` và sửa `total`, Booking chỉ sửa `sold`.
- **Không triển khai** cổng thanh toán thật, hoàn tiền, notification/push, idempotency key, refresh/revocation, xếp phòng cụ thể, giá theo ngày, UI đầy đủ hay message broker.
- **Owner:** **A (Bảo)** — IAM/JWT/PKCE/Gateway/CORS/root platform; **B (Hòa)** — Catalog (`hotel`, `room_type`, `room_inventory`); **C (Phúc)** — Booking; **D (Đạt)** — hai frontend demo, `docs/demo.http`, kiểm thử E2E, tài liệu/bằng chứng. D chỉ đề xuất/review contract, không tự sửa service của A/B/C.
- **Quy tắc ưu tiên:** File này chốt **chi tiết interface** sau khi cả bốn người ký nhận. Điểm thay đổi so với bản 2.0 được liệt kê ở §12, **không mặc nhiên là quyết định mentor**.

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
| Ngày ở | `checkIn`, `checkOut`, `stayDate` là chuỗi `YYYY-MM-DD` ở cả request và response, **không có giờ và múi giờ**. Có giờ hoặc múi giờ, hoặc ngày không tồn tại → 400 |
| Thời điểm | `createdAt`, `paymentExpiresAt` là ISO 8601 UTC, ví dụ `2026-11-03T02:15:30Z`. JWT `iat`/`exp` là Unix seconds |
| "Hôm nay" | Tính theo giờ Việt Nam (`Asia/Ho_Chi_Minh`) |
| Tiền | JSON number, **VND**, tối đa 2 chữ số thập phân; không dùng float/double cho phép tính. `basePrice`: `> 0`, tối đa `99999999.99` (`NUMERIC(10,2)`). `totalAmount`: `NUMERIC(14,2)`, do server tính |
| ID | Số nguyên dương (DB `BIGINT`); ID không hợp lệ → 400 |
| `tenant_id` | Số nguyên dương trong JWT và DB (`BIGINT`); response JSON dùng `tenantId` (number) |
| Danh sách | Mảng JSON, không phân trang trong demo, theo `id` tăng dần trừ khi mục đó ghi khác; rỗng → `[]` |
| Tạo mới | `201 Created` với JSON tài nguyên; không bắt buộc header `Location` trong demo |
| Không tin dữ liệu client | `tenantId`, role, người tạo booking, giá và số tiền **chỉ được suy ra từ JWT đã xác minh hoặc từ database**, không nhận từ body/query/header tùy ý |
| Field lạ ở request | DTO chỉ ánh xạ các trường được phép; trường lạ (kể cả `tenantId`, `status`, `totalAmount`) bị **bỏ qua**, tuyệt đối không ảnh hưởng tenant/quyền/số tiền. Có test chứng minh không thể đổi tenant qua body |
| Authentication | `Authorization: Bearer <stayhub-jwt>` cho **mọi** API `/api/hotels/**`, `/api/room-types/**`, `/api/admin/**`, `/api/bookings/**`; **không** dùng Google access token trực tiếp |

**Không có endpoint nào được coi là chạy thật chỉ vì đã có contract.** Hiện chưa có API nào chạy theo bản 3.0: `booking-service` có code theo bản 2.0, Catalog và IAM chưa có API, Gateway mới có route của bản 2.0. Khi kiểm thử, kết quả chạy thật mới là bằng chứng.

## 2. Bản đồ route và phân quyền (source of truth)

Gateway **bỏ đúng một tiền tố `/api`** bằng `StripPrefix=1` (hoặc rewrite tương đương) và **forward nguyên Bearer token**. Dấu `*`/`{id}` chỉ là ký hiệu, không phải phần URL literal.

| Frontend/HTTP script gọi | Service nhận | Role | Thành công | Lỗi chính | Owner |
|---|---|---|---|---|---|
| `GET /api/hotels` | `GET /hotels` | `USER`, `ADMIN` | 200 | 401 | B |
| `GET /api/hotels/{hotelId}/availability?checkIn=&checkOut=` | `GET /hotels/{hotelId}/availability` | `USER`, `ADMIN` | 200 | 400, 404 | B |
| `POST /api/hotels/{hotelId}/room-types` | `POST /hotels/{hotelId}/room-types` | `ADMIN` | 201 | 400, 403, 404 | B |
| `PUT /api/room-types/{id}/price` | `PUT /room-types/{id}/price` | `ADMIN` | 200 | 400, 403, 404 | B |
| `GET /api/admin/hotels/{hotelId}/inventory?checkIn=&checkOut=` | `GET /admin/hotels/{hotelId}/inventory` | `ADMIN` | 200 | 400, 403, 404 | B |
| `POST /api/bookings` | `POST /bookings` | `USER` | 201 | 400, 403, 404, 409 | C |
| `GET /api/bookings` | `GET /bookings` | `USER`, `ADMIN` | 200 | 401 | C |
| `GET /api/bookings/{id}` | `GET /bookings/{id}` | `USER`, `ADMIN` | 200 | 400, 404 | C |
| `POST /api/bookings/{id}/payment` | `POST /bookings/{id}/payment` | `USER` là chủ đơn | 200 | 400, 403, 404, 409 | C |
| `POST /api/bookings/{id}/cancel` | `POST /bookings/{id}/cancel` | `USER` là chủ đơn | 200 | 400, 404, 409 | C |

| Direct call (không qua Gateway) | Người gọi | Quyền/yêu cầu | Thành công | Lỗi chính | Owner |
|---|---|---|---|---|---|
| `GET http://localhost:8084/auth/start?app=app1\|app2` | Browser navigation | `app` trong allowlist | 302 | 400, 403 trong luồng đăng nhập | A |
| `GET http://localhost:8084/login/oauth2/code/google` | Google → IAM | OAuth2 `code`/`state` | Theo luồng | Theo luồng OAuth2 | A |
| `GET http://localhost:8084/.well-known/jwks.json` | Gateway/B/C | Public | 200 | 5xx nếu IAM lỗi | A |

Tất cả business API yêu cầu JWT hợp lệ; ngoài lỗi được liệt kê vẫn có thể trả 401 (thiếu/sai token) hoặc 500 khi lỗi server chưa xử lý. **Không có route** `/dev/**`, `/auth/**` trên Gateway, và không có API `/internal/**`. OAuth/SSO/JWKS truy cập trực tiếp IAM theo bảng trên.

### Quy tắc thứ tự lỗi

Áp dụng cho business API khi có nhiều sai sót: **401 (JWT) → 403 (role) → 400 (tham số/body) → 404 (không thấy trong tenant/owner) → 409 (hết phòng, trùng mã, trạng thái đơn)**. Riêng lỗi 403 do user chưa provision chỉ được phát hiện khi lưu booking, nên có thể đứng sau 404 hoặc 409. Lỗi hạ tầng như 500 là nhánh riêng; không ép thành 409. `OPTIONS` CORS hợp lệ được xử lý trước bộ lọc JWT.

| HTTP | Ý nghĩa | Response body |
|---|---|---|
| **400** | ID không nguyên dương, body thiếu/sai kiểu, tham số ngày sai, `roomCount`/`basePrice`/`name`/`capacity`/`totalRooms` sai | `application/problem+json` |
| **401** | Thiếu, sai chữ ký, issuer sai, token hết hạn, thiếu/malformed JWT claim bắt buộc | Không ràng buộc JSON; chỉ kiểm tra status |
| **403** | JWT hợp lệ nhưng role không đủ; hoặc email/tenant trong JWT không khớp `app_user` khi tạo booking | Không ràng buộc JSON; chỉ kiểm tra status |
| **404** | Bản ghi không tồn tại, thuộc tenant khác hoặc booking của user khác | `application/problem+json` |
| **409** | Có đêm hết phòng hoặc chưa mở bán; trạng thái đơn không cho phép thao tác | `application/problem+json` |
| **500** | Lỗi nội bộ, ví dụ database không truy cập được | `application/problem+json` khi có thể |

Định dạng lỗi Spring `ProblemDetail` ví dụ cho **404**:

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Hotel not found",
  "instance": "/hotels/99/availability"
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

**Quy tắc serve static:** D tạo đường dẫn `frontend/app1/callback/index.html` và `frontend/app2/callback/index.html` **hoặc** cấu hình HTTP static server thực sự phục vụ `/callback/` bằng chính HTML/JS của mỗi app. **Không dùng `/callback` tùy tiện nếu server trả 404**. Chuẩn `/callback/` (có slash cuối) trong bảng trên là địa chỉ chính thức của bản contract này; không thay bằng `/` nếu chưa cập nhật contract và thông báo A/D.

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

## 4. Catalog — Hotels, loại phòng và tồn kho (B)

### 4.1 JSON dùng chung

**`Hotel`**

| Trường | Kiểu | Ghi chú |
|---|---|---|
| `id` | integer > 0 | ID khách sạn |
| `tenantId` | integer > 0 | Trùng tenant trong JWT, chỉ để hiển thị/đối chiếu |
| `name` | string | Tên khách sạn |
| `address` | string | Địa chỉ khách sạn |

**`RoomType`**

| Trường | Kiểu | Ghi chú |
|---|---|---|
| `id` | integer > 0 | ID loại phòng |
| `tenantId` | integer > 0 | Trùng tenant trong JWT |
| `hotelId` | integer > 0 | Khách sạn chứa loại phòng |
| `name` | string | Tên hiển thị |
| `capacity` | integer > 0 | Số khách mỗi phòng |
| `basePrice` | number | Giá của loại phòng cho một phòng mỗi đêm, VND |
| `totalRooms` | integer > 0 | Số phòng mặc định khi tạo dòng tồn |

Ví dụ `RoomType`:

```json
{
  "id": 1,
  "tenantId": 1,
  "hotelId": 1,
  "name": "Standard",
  "capacity": 2,
  "basePrice": 500000.00,
  "totalRooms": 3
}
```

### 4.2 `GET /api/hotels` → `GET /hotels`

- `USER`/`ADMIN`: **200** + mảng `Hotel`, chỉ khách sạn có `tenant_id` bằng tenant claim. Dùng để hiển thị ô chọn khách sạn.
- Có thể trả `[]`. Test dùng 2 JWT tenant khác nhau phải có danh sách không lẫn.

### 4.3 `GET /api/hotels/{hotelId}/availability` → `GET /hotels/{hotelId}/availability`

UI chỉ gọi API này sau khi người dùng chọn đủ khách sạn, ngày nhận và trả phòng, rồi bấm **Tìm kiếm**. API chỉ đọc dữ liệu, chưa giữ phòng.

```http
GET /api/hotels/1/availability?checkIn=2026-11-24&checkOut=2026-11-26
```

| Query | Bắt buộc | Quy tắc, sai thì **400** |
|---|---|---|
| `checkIn` | Có | `YYYY-MM-DD`, không ở quá khứ. Bằng hôm nay vẫn hợp lệ |
| `checkOut` | Có | `YYYY-MM-DD`, phải **sau** `checkIn`, kỳ ở tối đa 30 đêm |

Response **200**, theo `roomTypeId` tăng dần:

```json
[
  {
    "roomTypeId": 1,
    "name": "Standard",
    "capacity": 2,
    "pricePerNight": 500000.00,
    "nights": 2,
    "totalPricePerRoom": 1000000.00,
    "availableRooms": 1
  }
]
```

| Trường | Cách tính |
|---|---|
| `nights` | `checkOut - checkIn` |
| `totalPricePerRoom` | `pricePerNight × nights`, cho một phòng |
| `availableRooms` | `MIN(total - sold)` trên các đêm của kỳ ở |

| Tình huống | Kết quả |
|---|---|
| Loại phòng còn trống ở mọi đêm | Có trong mảng |
| Loại phòng hết ở ít nhất một đêm | Không có trong mảng |
| Có đêm chưa mở bán (không có dòng `room_inventory`) | Không có trong mảng |
| Không loại phòng nào phù hợp | **200** với `[]` |
| Khách sạn không tồn tại hoặc thuộc tenant khác | **404**, cùng body cho cả hai trường hợp |

Kết quả tìm kiếm có thể cũ ngay sau khi trả về, nên `POST /api/bookings` vẫn có thể nhận **409**. `USER` chỉ thấy `availableRooms`, không thấy `total` và `sold`. Truy vấn nằm ở `erd-v2.md` §4.

### 4.4 `POST /api/hotels/{hotelId}/room-types` → `POST /hotels/{hotelId}/room-types`

`ADMIN` mới được gọi. Request:

```json
{
  "name": "Suite",
  "capacity": 4,
  "basePrice": 2500000.00,
  "totalRooms": 2
}
```

| Trường | Quy tắc, sai thì **400** |
|---|---|
| `name` | Chuỗi 1–100 ký tự sau trim, không blank |
| `capacity` | Số nguyên JSON từ 1 đến 20 |
| `basePrice` | JSON number `> 0` và `≤ 99999999.99`, tối đa 2 decimal |
| `totalRooms` | Số nguyên JSON từ 1 đến 1000 |

- Không nhận tenant từ client; `tenant_id` lấy từ JWT. Trường `tenantId` lạ nếu bị gửi thêm cũng **không có tác dụng**.
- Trong **cùng một transaction**, Catalog thêm loại phòng và tạo một dòng `room_inventory` cho mỗi đêm từ hôm nay (giờ Việt Nam) đến hết `2027-01-31` (`total = totalRooms`, `sold = 0`); nếu hôm nay đã sau ngày đó thì không tạo dòng nào. Thiếu bước này thì loại phòng không bao giờ hiện trong kết quả tìm kiếm.
- **201** + `RoomType`.
- `USER` gọi dù body đúng hay sai → **403**. Khách sạn không có trong tenant → **404**. Không kiểm tra mã `code` vì `room_type` không có trường này.

### 4.5 `PUT /api/room-types/{id}/price` → `PUT /room-types/{id}/price`

`ADMIN` mới được gọi. Request:

```json
{"basePrice": 2000000.00}
```

- `basePrice` cùng ràng buộc như §4.4; không đổi các trường khác.
- **200** + `RoomType` đã cập nhật. Giá mới chỉ áp dụng cho đơn tạo sau đó; `totalAmount` của đơn cũ không đổi.
- `USER` → **403**; ID/body sai → **400**; loại phòng không có trong tenant → **404**. Không thay giá nếu lỗi.

### 4.6 `GET /api/admin/hotels/{hotelId}/inventory` → `GET /admin/hotels/{hotelId}/inventory`

`ADMIN` mới được gọi. Xem tồn kho theo từng đêm trong khoảng `[checkIn, checkOut)`:

```http
GET /api/admin/hotels/1/inventory?checkIn=2026-11-24&checkOut=2026-11-26
```

Response **200**, theo `roomTypeId` rồi `stayDate` tăng dần. Đêm chưa mở bán không có dòng:

```json
[
  {"roomTypeId": 1, "name": "Standard", "stayDate": "2026-11-24", "total": 3, "sold": 1, "available": 2},
  {"roomTypeId": 1, "name": "Standard", "stayDate": "2026-11-25", "total": 3, "sold": 2, "available": 1}
]
```

- `checkIn`, `checkOut`: `YYYY-MM-DD`, `checkOut` sau `checkIn`, tối đa 30 ngày. **Được phép ở quá khứ**, vì đây là màn hình xem. Sai → **400**.
- `USER` → **403**. Khách sạn không có trong tenant → **404**.

## 5. Booking — đặt, xem, thanh toán và huỷ (C)

### 5.1 Booking JSON và ownership

| Trường response | Kiểu | Ghi chú |
|---|---|---|
| `id` | integer > 0 | ID DB cấp |
| `tenantId` | integer > 0 | Tenant từ verified JWT |
| `roomTypeId` | integer > 0 | Loại phòng đã đặt |
| `userEmail` | string | Email chữ thường từ JWT; dùng kiểm tra sở hữu trong tenant |
| `checkIn` | `YYYY-MM-DD` | Đêm đầu tiên của kỳ ở |
| `checkOut` | `YYYY-MM-DD` | Ngày trả phòng, không tính đêm này |
| `roomCount` | integer 1–10 | Số phòng trong đơn |
| `totalAmount` | number | `basePrice × số đêm × roomCount`, chốt lúc đặt |
| `status` | string | `PENDING_PAYMENT`, `CONFIRMED`, `CANCELLED`, `EXPIRED` |
| `paymentExpiresAt` | ISO UTC string | Hạn thanh toán, luôn có giá trị |
| `createdAt` | ISO UTC string | Thời gian tạo theo server |

| `status` | Giữ phòng? | Ý nghĩa |
|---|---|---|
| `PENDING_PAYMENT` | Có | Đã trừ tồn, đang chờ thanh toán |
| `CONFIRMED` | Có | Thanh toán mô phỏng thành công |
| `CANCELLED` | Không | Đã huỷ và trả phòng |
| `EXPIRED` | Không | Quá hạn thanh toán và trả phòng |

Bảng `booking` do C sở hữu; DDL ở `erd-v2.md` §9. Client không thể cung cấp `tenantId`, `userEmail`, `status` hoặc `totalAmount` để quyết định record được lưu; các field lạ bị bỏ qua.

### 5.2 `POST /api/bookings` → `POST /bookings`

Chỉ `USER`. Request:

```json
{
  "roomTypeId": 1,
  "checkIn": "2026-11-24",
  "checkOut": "2026-11-26",
  "roomCount": 1
}
```

| Trường | Bắt buộc | Quy tắc, sai thì **400** |
|---|---|---|
| `roomTypeId` | Có | Số nguyên JSON dương; chuỗi `"1"` hay số `1.5` không hợp lệ |
| `checkIn` | Có | `YYYY-MM-DD`, không ở quá khứ |
| `checkOut` | Có | `YYYY-MM-DD`, phải **sau** `checkIn`, kỳ ở tối đa 30 đêm |
| `roomCount` | Có | Số nguyên JSON từ 1 đến 10 |

Booking lấy `tenant_id` và `email` từ JWT đã verify, rồi trong **một transaction READ COMMITTED**:

1. Đọc `room_type` theo `id` và `tenant_id`, không có thì trả **404**.
2. `UPDATE room_inventory` cho từng đêm **theo ngày tăng dần**, với điều kiện `sold + roomCount <= total`. Đêm nào cập nhật được 0 dòng thì rollback và trả **409**.
3. `INSERT booking` trạng thái `PENDING_PAYMENT`, `paymentExpiresAt = now + thời hạn cấu hình` (mặc định 15 phút).

Không tách bước kiểm tra còn phòng khỏi bước trừ tồn, vì hai request có thể cùng thấy còn phòng. SQL nằm ở `erd-v2.md` §5.1.

| Kết quả | Booking trả client | Dữ liệu |
|---|---|---|
| Đủ phòng mọi đêm | **201 `PENDING_PAYMENT`** | Trừ tồn và thêm 1 đơn |
| Loại phòng không tồn tại hoặc thuộc tenant khác | **404**, cùng body cho cả hai trường hợp | Không đổi |
| Có đêm hết phòng hoặc chưa mở bán | **409** | Rollback toàn bộ, kể cả các đêm đã trừ |
| `email` hoặc `tenant_id` trong JWT không khớp `app_user` | **403** | Rollback toàn bộ |
| Lỗi khác của database | **500**, không lộ chi tiết SQL | Rollback toàn bộ |

Response **201** minh họa:

```json
{
  "id": 5,
  "tenantId": 1,
  "roomTypeId": 1,
  "userEmail": "user-tenant1@example.com",
  "checkIn": "2026-11-24",
  "checkOut": "2026-11-26",
  "roomCount": 1,
  "totalAmount": 1000000.00,
  "status": "PENDING_PAYMENT",
  "paymentExpiresAt": "2026-11-03T02:30:30Z",
  "createdAt": "2026-11-03T02:15:30Z"
}
```

- **Quy tắc theo đêm:** chỉ trừ từ đêm `checkIn` đến đêm trước `checkOut`. Khách trả phòng ngày 29 và khách nhận phòng ngày 29 không dùng chung đêm nào. Ví dụ ở `erd-v2.md` §7.1.
- `ADMIN` gọi `POST /bookings` → **403**.
- **Gửi lại cùng một request sẽ tạo thêm đơn thứ hai nếu còn phòng**, vì demo không có idempotency key. Frontend phải khóa nút Đặt sau lần bấm đầu (§9).
- **Không được lộ dữ liệu của tenant khác:** loại phòng của tenant khác luôn là **404**, kể cả khi loại phòng đó đã hết phòng ở các đêm yêu cầu.
- Race test **từ state sạch**: các POST đồng thời vào loại phòng `5` (chỉ có 1 phòng), cùng kỳ ở → đúng một **201**, còn lại **409**; bảng chỉ có một row, `sold = 1` ở mọi đêm của kỳ ở. Có thể dùng hai USER cùng tenant; nếu dùng cùng JWT, ghi là **hai request**, không tuyên bố là hai người khác nhau.

### 5.3 `GET /api/bookings` → `GET /bookings`

- **200** + mảng Booking theo `id` tăng dần.
- `USER`: chỉ những booking có `tenant_id` **và** `user_email` khớp JWT.
- `ADMIN`: toàn bộ booking **trong tenant** từ JWT. Không bao giờ trả booking tenant khác, kể cả khi client gửi ID/tenant giả.
- Không có dữ liệu trả `[]`.

### 5.4 `GET /api/bookings/{id}` → `GET /bookings/{id}`

- **200** + Booking JSON nếu đúng quyền.
- **404** nếu ID không tồn tại, tenant khác, hoặc `USER` không phải chủ (`user_email` khác); response không tiết lộ lý do.
- **400** nếu ID không nguyên dương. `ADMIN` được xem booking của mọi user trong tenant mình.

### 5.5 `POST /api/bookings/{id}/payment` → `POST /bookings/{id}/payment`

Thanh toán mô phỏng, luôn được coi là thành công, không có body và không gọi cổng thanh toán. Chỉ `USER` là chủ đơn. Chỉ đổi `booking`, không đổi `room_inventory`.

| Trạng thái đơn | Kết quả |
|---|---|
| `PENDING_PAYMENT`, chưa quá `paymentExpiresAt` | **200** + Booking `CONFIRMED` |
| `PENDING_PAYMENT`, đã quá hạn nhưng job chưa chạy | **409**, không xác nhận |
| `CONFIRMED`, `CANCELLED`, `EXPIRED` | **409**, không đổi dữ liệu |

`ADMIN` → **403**. ID sai → **400**. Đơn không tồn tại, khác tenant hoặc của user khác → **404**.

### 5.6 `POST /api/bookings/{id}/cancel` → `POST /bookings/{id}/cancel`

Chỉ `USER` là chủ đơn được huỷ. Không có body. Trong flow demo, chỉ booking `PENDING_PAYMENT` được huỷ; đổi trạng thái có điều kiện trước, **chỉ khi đổi được đúng 1 dòng** mới trả phòng từng đêm theo ngày tăng dần, trong cùng một transaction (`erd-v2.md` §6).

| Trạng thái đơn | Kết quả | `room_inventory` |
|---|---|---|
| `PENDING_PAYMENT` | **200** + Booking `CANCELLED` | Trả `roomCount` cho mỗi đêm trong cùng transaction |
| `CONFIRMED` | **409** | Demo chỉ cho huỷ khi đang chờ thanh toán |
| `CANCELLED` (huỷ lại) | **200** + Booking `CANCELLED` | **Không đổi** |
| `EXPIRED` | **409** | Không đổi |

ID sai → **400**. Đơn không tồn tại, khác tenant, hoặc `USER` không phải chủ → **404**. Thanh toán và huỷ gửi cùng lúc thì chỉ một bên đổi được trạng thái, kết quả cuối vẫn nhất quán với tồn kho. Lần tìm kiếm kế tiếp có thể thấy lại phòng vừa được trả.

### 5.7 Hết hạn thanh toán

Không có API. Job trong `booking-service` chạy định kỳ (mặc định 60 giây), tìm đơn `PENDING_PAYMENT` đã quá `paymentExpiresAt`, xử lý mỗi đơn trong một transaction riêng: đổi sang `EXPIRED` có điều kiện rồi trả phòng. Vì vậy phòng có thể được trả chậm tối đa một chu kỳ job so với hạn thanh toán. Thời hạn và chu kỳ job cấu hình được, để test không phải chờ 15 phút.

## 6. Gateway & CORS (A), tích hợp browser (D)

A chịu trách nhiệm **Gateway/CORS**, D chỉ kiểm thử và báo nhu cầu origin/headers. Route phải match cả đường dẫn danh sách đúng `/api/hotels`, `/api/bookings` **và** đường dẫn có thêm `/{id}`; không để rule wildcard bỏ sót đường dẫn gốc. B/C bảo vệ service bằng JWT **kể cả khi gọi trực tiếp qua `:8081/:8082`**; Gateway không phải lớp bảo mật duy nhất.

| Rule | Giá trị |
|---|---|
| Route 1 | `/api/hotels/**` → `http://localhost:8081/hotels/**` (`StripPrefix=1`) |
| Route 2 | `/api/room-types/**` → `http://localhost:8081/room-types/**` (`StripPrefix=1`) |
| Route 3 | `/api/admin/**` → `http://localhost:8081/admin/**` (`StripPrefix=1`) |
| Route 4 | `/api/bookings/**` → `http://localhost:8082/bookings/**` (`StripPrefix=1`) |
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
| Khách sạn tenant 1 | ID `1` (Hà Nội), `3` (Đà Nẵng) | B |
| Khách sạn tenant 2 | ID `2` (TP.HCM) | B |
| Khoảng mở bán | Mỗi loại phòng có một dòng tồn cho mỗi đêm từ `2026-11-01` đến `2027-01-31` | B |
| Booking ID | ID phát sinh từ DB, **không hard-code** | C |
| Role | `USER`, `ADMIN` | A |
| Booking status | `PENDING_PAYMENT`, `CONFIRMED`, `CANCELLED`, `EXPIRED` | C |

| ID loại phòng | Tenant | Khách sạn | Tên | `basePrice` mỗi đêm | `totalRooms` | Dùng cho |
|---|---|---|---|---|---|---|
| `1` | 1 | 1 | Standard | 500,000 | 3 | Happy path |
| `2` | 1 | 1 | Deluxe | 900,000 | 4 | Happy path, kiểm thử hết phòng |
| `3` | 2 | 2 | Standard | 450,000 | 5 | Kiểm thử khác tenant |
| `4` | 2 | 2 | Deluxe | 850,000 | 6 | Kiểm thử khác tenant |
| `5` | 1 | 3 | Race Test | 500,000 | 1 | **Chỉ** dùng cho race test |
| `6` | 1 | 3 | Standard | 600,000 | 5 | Happy path khách sạn thứ hai |

| Alias | Mẫu trong `docker/seed/seed-app-users.example.sql` | Tenant, role |
|---|---|---|
| `ADMIN_1` | `admin-tenant1@example.com` | 1, `ADMIN` |
| `USER_1` | `user-tenant1@example.com` | 1, `USER` |
| `USER_1B` | `user2-tenant1@example.com` | 1, `USER`, dùng cho race test hai người |
| `ADMIN_2` | `admin-tenant2@example.com` | 2, `ADMIN` |
| `USER_2` | `user-tenant2@example.com` | 2, `USER` |

**Email Google thật**, client secret, Bearer JWT và RSA private key **không được lưu vào Git**. Bản seed thật đặt ở `docker/seed/*.local.sql`, đã có trong `.gitignore`. Đặt bảng alias + cách lấy token vào `docs/demo-data.md` (D tổng hợp với A/B/C), giá seed lấy từ B.

**Reset booking giữa các lần demo:** xoá booking thôi là chưa đủ, vì `sold` vẫn còn. Phải chạy cả hai lệnh trong một transaction:

```sql
BEGIN;
TRUNCATE booking RESTART IDENTITY;
UPDATE room_inventory SET sold = 0;
COMMIT;
```

Cách này không hoàn tác loại phòng mới thêm hoặc giá đã sửa. Muốn về đúng ID và giá seed thì tạo lại DB bằng `docker compose down -v`. Mọi seed chỉ minh họa/local.

### 7.2 Ma trận kiểm thử hợp đồng tối thiểu

Kỳ ở mẫu `2026-11-24` → `2026-11-26` chỉ áp dụng khi còn ở tương lai. Ngày demo thực tế phải nằm trong khoảng mở bán.

| ID | Request/tình huống | Kỳ vọng bắt buộc | Owner bằng chứng |
|---|---|---|---|
| T01 | GET hotels bằng USER_1 | 200, chỉ khách sạn `1`, `3` | B/D |
| T02 | GET hotels bằng USER_2 | 200, chỉ khách sạn `2` | B/D |
| T03 | POST room-types bằng USER_1 | 403, không thêm loại phòng | B/D |
| T04 | POST room-types bằng ADMIN_1 vào khách sạn `1` | 201, `tenantId=1`; có dòng tồn và hiện trong tìm kiếm | B/D |
| T05 | PUT price bằng USER_1 | 403, giá không đổi | B/D |
| T06 | PUT price loại phòng `3` bằng ADMIN_1 | 404, giá không đổi | B/D |
| T07 | POST room-types kèm `tenantId=2` bằng ADMIN_1 | Không thể tạo vào tenant 2; field lạ bỏ qua, kết quả tenant 1 | B/D |
| T08 | USER_1 tìm kiếm trên khách sạn `2`, và trên ID không tồn tại | 404 ở cả hai, cùng body, không lộ dữ liệu | B/D |
| T09 | USER_1 POST booking loại `1`, 2 đêm, 1 phòng | 201 `PENDING_PAYMENT`, `totalAmount=1000000`; `sold` tăng 1 ở 2 đêm, không đổi ở ngày trả phòng | C/D |
| T10 | Gửi lại đúng request T09 khi còn phòng | 201 đơn thứ hai, `sold` tăng thêm 1. Ghi nhận là giới hạn đã công bố, không phải idempotency | C/D |
| T11 | Hai POST đồng thời loại `5`, cùng kỳ ở, từ state sạch | **Một 201, một 409**; DB chỉ có 1 booking; `sold = 1`, không vượt `total` | C/D |
| T12 | USER_1 POST booking loại `3` của tenant 2 | 404, không tạo booking, `sold` không đổi | C/D |
| T13 | ADMIN_1 POST booking | 403 | C/D |
| T14 | USER_1 GET bookings | 200, chỉ của email USER_1 trong tenant 1 | C/D |
| T15 | ADMIN_1 GET bookings | 200, tất cả booking trong tenant 1 | C/D |
| T16 | USER_2 / USER_1B truy cập booking của USER_1 bằng ID | 404, không lộ owner/tenant | C/D |
| T17 | GET business API thiếu, sai chữ ký, hết hạn JWT | 401 mỗi trường hợp | A/D |
| T18 | JWT hợp lệ nhưng thiếu `tenant_id`/`role` | 401, test bằng fixture/unit hoặc cấu hình test A | A/D |
| T19 | Login Google user chưa provision/không verified | 403 IAM, không thêm user, không phát JWT | A/D |
| T20 | Login App 1 → App 2 cùng browser profile | Không nhập lại mật khẩu, app2 nhận JWT của IAM | A/D |
| T21 | Google authorize có `code_challenge` và `S256` | Evidence PKCE thực, Google callback thành công | A/D |
| T22 | POST loại `2` đủ 4 phòng kỳ `2026-12-27`→`2026-12-29`, rồi 4 phòng kỳ `2026-12-29`→`2026-12-31` | Cả hai 201, vì không chung đêm; `sold = 4` ở các đêm 27, 28, 29, 30 | C/D |
| T23 | POST booking và tìm kiếm với `checkOut` không sau `checkIn`, ngày có giờ/múi giờ, `checkIn` ở quá khứ, quá 30 đêm; POST với `roomCount` là 0 hoặc 11 | 400, không tạo booking | B/C/D |
| T24 | `gradlew.bat clean build`, unit tests IAM/B/C, 3 Flyway histories | Build/test xanh, migrations thực, không skip để qua | A/B/C/D |
| T25 | USER_2 đặt hết phòng loại `3` ở một đêm, rồi USER_1 POST loại `3` đúng đêm đó | 404 như không tồn tại, không phải 409 | C/D |
| T26 | JWT `tenant_id` dạng chuỗi hoặc token sai `iss` | 401 ở Gateway và mọi service | A/B/C |
| T27 | Một đêm của loại `2` đã hết, tìm kỳ ở có đêm đó | Loại `2` không có trong kết quả; loại `1` có `availableRooms` bằng số phòng của đêm ít nhất | B/D |
| T28 | Tìm và POST kỳ `2027-01-31`→`2027-02-02` | Tìm kiếm không có loại phòng nào; POST 409; `sold` đêm `2027-01-31` không đổi | B/C/D |
| T29 | USER_1 thanh toán đơn `PENDING_PAYMENT` | 200 `CONFIRMED`; `sold` không đổi | C/D |
| T30 | Thanh toán lại đơn đã `CONFIRMED`, đơn đã huỷ, đơn đã quá hạn | 409 mỗi trường hợp, không đổi dữ liệu | C/D |
| T31 | USER_1B hoặc USER_2 thanh toán đơn của USER_1; ADMIN_1 thanh toán | 404 với USER, 403 với ADMIN | C/D |
| T32 | USER_1 huỷ đơn của mình, rồi huỷ lại | Cả hai 200 `CANCELLED`; `sold` chỉ giảm một lần; tìm kiếm thấy lại phòng | C/D |
| T33 | ADMIN_1 huỷ đơn trong tenant; ADMIN_2 huỷ đơn đó | 200 với ADMIN_1, 404 với ADMIN_2 | C/D |
| T34 | Đơn quá hạn thanh toán sau khi job chạy (test đặt thời hạn ngắn) | `EXPIRED`, `sold` được trả; huỷ đơn đó → 409 | C/D |
| T35 | ADMIN_1 xem inventory khách sạn `1`; USER_1 gọi; ADMIN_1 gọi khách sạn `2` | 200 đúng `total`/`sold`/`available`; 403; 404 | B/D |
| T36 | Sửa giá loại `1` sau khi đã có đơn, rồi đặt đơn mới | `totalAmount` đơn cũ không đổi, đơn mới theo giá mới | B/C/D |
| T37 | Chạy truy vấn đối soát `erd-v2.md` §8 sau T09–T36 và sau race test | **0 dòng** | C/D |

**Lưu ý test JWT:** Token hết hạn thật có thể phải đợi >15 phút hoặc dùng test fixture riêng; đừng commit/sao chép JWT. Token giả thiếu claim chỉ kiểm tra qua fixture của A, **không cần tạo endpoint phát JWT public**. Kiểm thử tranh phòng **phải gửi đồng thời**; chạy 2 request tuần tự không chứng minh được việc chống bán quá.

## 8. Dữ liệu bàn giao để D lập frontend và `docs/demo.http`

| Ai giao | Dữ liệu/artifact cần giao | Điều kiện D xác nhận nhận bàn giao |
|---|---|---|
| **A → D/B/C** | IAM login URL + Google callback + **frontend callback `/callback/`**; issuer/JWKS, role/claims, origins, config CORS và các route Gateway mới; cách có tài khoản Google được provision và thu evidence PKCE | D mở được hai origin, nắm cách login thật; B/C verify cùng kiểu JWT |
| **B → C/D** | Migration `hotel`, `room_type`, `room_inventory`; JSON Hotel, RoomType, Availability, Inventory; ID seed và khoảng mở bán | C viết được trừ tồn; D test form tìm kiếm và dữ liệu đúng tenant |
| **C → D** | Booking JSON với ngày `YYYY-MM-DD`, payment, cancel, error 400/403/404/409, cách test race, truy vấn đối soát, lệnh reset (`booking-service/README.md`) | D viết `demo.http` chính xác và có bằng chứng 201/409 |
| **A/B/C → D** | Lệnh chạy Gradle/DB, các unit test reports, 3 Mermaid diagrams và limitations đã biết | D tổng hợp vào `demo-script.md`, `test-evidence.md`, README |

D **không đưa token/client secret/private key vào `docs/demo.http`**. Dùng biến placeholder `@BASE_URL`, `@JWT_USER_1`, `@JWT_ADMIN_1`, `@JWT_USER_2`, `@HOTEL_ID`, `@ROOM_TYPE_ID`, `@BOOKING_ID`, v.v.; các bearer token thật chỉ đưa vào runtime/test environment không theo Git. Khi contract này được ký, D sửa tài liệu/frontend theo URL `/callback/` và request/response ở đây; những phần đã mock vẫn đánh dấu `MOCK ONLY` cho đến khi chạy thật.

**Yêu cầu riêng cho frontend:** chỉ gọi tìm kiếm sau khi bấm Tìm kiếm, và khóa nút Đặt sau lần bấm đầu cho đến khi có response (§5.2).

## 9. Ranh giới lỗi và giới hạn cần công bố khi demo

1. **Dùng chung database giữa các service.** Booking đọc `room_type`, ghi `room_inventory.sold` và có FK sang `room_type`, `app_user`, nên đổi schema các bảng này có thể ảnh hưởng Booking. Đây là đánh đổi có chủ ý của thiết kế một database, không phải database-per-service.
2. **`sold` khớp với booking là do service bảo đảm, không phải database.** `booking` và `room_inventory` không nối FK với nhau. Database chỉ chặn bán quá `total`; code ghi đơn mà quên trừ tồn sẽ không bị chặn. Vì vậy chỉ Booking được ghi `sold`, và T37 phải được chạy làm bằng chứng.
3. **Gửi lại request đặt phòng sẽ tạo đơn thứ hai nếu còn phòng,** vì không có idempotency key. Đơn thừa chưa thanh toán sẽ tự hết hạn; đơn đã thanh toán phải huỷ thủ công.
4. **Bán theo loại phòng, không xếp phòng cụ thể.** Không có API gán phòng cho booking. Giá một mức cho mọi đêm, không có giá theo ngày.
5. **Tồn kho được tạo sẵn đến `2027-01-31`, không có job mở bán thêm.** Không có API đổi `totalRooms` hoặc giảm `total` để bảo trì; muốn thử phải sửa dữ liệu trực tiếp.
6. **Thanh toán chỉ là mô phỏng,** không hoàn tiền, không có chính sách phí huỷ và chưa chặn huỷ sau ngày nhận phòng. Giờ nhận và trả phòng là quy định của khách sạn, API không lưu và không kiểm tra.
7. **Google SSO không đồng nghĩa phát JWT OIDC chuẩn cho bên thứ ba.** IAM là OAuth2 client và JWT issuer nội bộ, không phải full authorization server. Token ở URL fragment là simplification demo; XSS/browser extension vẫn có rủi ro.
8. **Không có Google logout toàn cục, JWT revoke, refresh token hoặc key rotation.** Clearing token trong tab không thu hồi JWT đã phát. `localhost` HTTP chỉ dùng local, không dùng mô hình này ở production.
9. `UNIT TEST` của A/B/C khác với `INTEGRATION/E2E` của D; không đánh dấu PASS khi chỉ có code/markdown hoặc mock.

## 10. Gate G2 — Điều kiện ký và đóng băng contract

G2 chỉ **PASS khi tất cả mục sau hoàn thành**:

- [ ] A/B/C/D cùng đồng ý **đổi scope sang `room_inventory`** theo `erd-v2.md`, đồng ý **có thanh toán mô phỏng, huỷ, hết hạn** và **bỏ gán phòng cho booking**; đồng ý owner như §0.
- [ ] A xác nhận **`tenant_id` là số nguyên JSON**, email trong JWT là chữ thường, **IAM redirect `.../callback/`**, OAuth redirect IAM/PKCE, issuer, JWT claim validator, CORS, **4 route Gateway ở §6** và lỗi email chưa provision **403**; đồng thời sửa các mục lỗi thời trong plan (§12).
- [ ] B xác nhận JSON Hotel/RoomType/Availability/Inventory, `tenantId` là số, giới hạn giá, tenant isolation, ID seed, khoảng mở bán và việc tạo dòng tồn khi thêm loại phòng.
- [ ] C xác nhận JSON Booking, ownership theo `user_email`, trình tự trừ tồn §5.2, bảng trạng thái §5.5/§5.6, job §5.7 và các giới hạn §9.
- [ ] D xác nhận hai static callback URLs **thực sự được server phục vụ**, frontend/demo.http có thể triển khai theo response/error mới, không dùng API ngoài scope.
- [ ] Tất cả nhất trí thay thế contract cũ và chỉ đổi contract sau đó bằng PR có bên cung cấp **và** bên sử dụng API review.

| Người | Vai trò | Phần cung cấp | Phần sử dụng/duyệt | Xác nhận G2 |
|---|---|---|---|---|
| **Bảo (A)** | IAM/Platform | §3, §6 | JWT/API requirements của B/C/D | [ ] |
| **Hòa (B)** | Catalog | §4, seed §7 | JWT A | [ ] |
| **Phúc (C)** | Booking | §5 | JWT A, bảng `room_type`/`room_inventory` của B, `app_user` của A, demo D | [ ] |
| **Đạt (D)** | Frontend/QA | HTML, `demo.http`, kiểm thử §7/§8 | IAM/Hotels/Booking, scripts/evidence | [ ] |

**Một PR tích hợp không được đổi field/path/status/role/issuer/port hoặc luật trừ tồn chỉ để “chạy được” mà không cập nhật contract và nhận xác nhận của bên liên quan.** Sau G2, check PR/commit + log/HTTP response/test mới là bằng chứng PASS khi triển khai.

## 11. Nguồn kỹ thuật để implement (không thay thế phê duyệt nhóm)

- Plan: [`iam-google-sso-demo-plan-v2.md`](iam-google-sso-demo-plan-v2.md); thiết kế dữ liệu: [`erd-v2.md`](erd-v2.md).
- Spring Security — [Authorization Code + PKCE (confidential client cần bật tường minh)](https://docs.spring.io/spring-security/reference/servlet/oauth2/client/authorization-grants.html).
- Spring Security — [JWT Resource Server: `jwk-set-uri` và kiểm tra issuer](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).
- Spring Security — [CORS & preflight trước authentication](https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html).
- Google — [OAuth 2.0 for Web Server Applications: callback khớp tuyệt đối](https://developers.google.com/identity/protocols/oauth2/web-server).

## 12. Thay đổi so với bản 2.0

| Nội dung | Bản 2.0 | Bản 3.0 | Lý do |
|---|---|---|---|
| Đơn vị bán | Phòng cụ thể (`room`, `roomId`) | Loại phòng theo đêm (`room_type`, `room_inventory`, `roomTypeId`, `roomCount`) | `erd-v2.md` §1 |
| Khách sạn | Không có, tenant được coi như khách sạn | Bảng `hotel`, `GET /api/hotels` | Flow bắt đầu bằng chọn khách sạn |
| Tìm phòng trống | `GET /api/rooms` không trả trạng thái trống | `GET /api/hotels/{hotelId}/availability` chỉ trả loại phòng còn trống | Flow tìm kiếm |
| API Rooms | `GET`/`POST /api/rooms`, `GET /api/rooms/{id}`, `PUT /api/rooms/{id}/price` | **Bỏ**, thay bằng các API ở §4 | Không còn bảng `room` |
| API xem tồn | Không có | `GET /api/admin/hotels/{hotelId}/inventory` | Thay `summary` trong plan |
| Thời gian | ISO 8601 có giờ và múi giờ | `YYYY-MM-DD` | Tồn kho tính theo đêm |
| Chống bán trùng | `EXCLUDE` trên `booking` | `UPDATE` có điều kiện từng đêm, `CHECK (sold <= total)` | `erd-v2.md` §5, §8 |
| Trạng thái đơn | Chỉ `CONFIRMED` | `PENDING_PAYMENT`, `CONFIRMED`, `CANCELLED`, `EXPIRED` | `erd-v2.md` §6, khớp với plan |
| API thanh toán, huỷ | Không có | `POST /api/bookings/{id}/payment`, `/cancel`, job hết hạn | Như trên |
| Giá trên đơn | Không lưu | `totalAmount`, chốt lúc đặt | Đổi giá không được ảnh hưởng đơn cũ |
| Gửi lại request đặt | 409 | 201 đơn thứ hai nếu còn phòng | Không còn `EXCLUDE`; công bố ở §9 |
| Đặt trong quá khứ | Chưa chặn | 400 | Cần cho việc mở bán theo ngày |
| Deadlock khi tranh giờ | Có thể chậm vài giây | Bỏ giới hạn này | Mọi request khóa các đêm theo ngày tăng dần |
| Gateway | 2 route | 4 route (§6) | Có thêm hotels, room-types, admin |
| Reset demo | `TRUNCATE booking` | Thêm `UPDATE room_inventory SET sold = 0` | `sold` là dữ liệu riêng |
| Seed | Phòng `1`–`5` | Khách sạn `1`–`3`, loại phòng `1`–`6`; race dùng loại `5`, khác tenant dùng loại `3` | `erd-v2.md` §3 |

**Mục giữ nguyên từ bản 2.0:** toàn bộ §3 (IAM, JWT, JWKS, callback `/callback/`), bỏ `/dev/token`, không có `/auth/logout`, token sống 15 phút, `tenant_id` số nguyên, chủ booking theo `user_email`, field lạ bị bỏ qua, thứ tự lỗi, Booking không gọi HTTP sang Catalog.

**Giá trị do bản này đề xuất, cần nhóm xác nhận:** giữ phòng 15 phút, job hết hạn 60 giây, kỳ ở tối đa 30 đêm, 1–10 phòng mỗi đơn, mở bán đến `2027-01-31`, giới hạn trường ở §4.4.

**Các phần cũ đã được thay bằng hợp đồng hiện hành ở trên:**

| Tài liệu hoặc code | Chưa khớp | Owner |
|---|---|---|
| `iam-google-sso-demo-plan-v2.md` | Đã sửa ngày 03/11/2026: `users` → `app_user`, mục B/C, timeline, handoff, kịch bản demo, DoD theo `room_inventory` | A/C |
| `booking-policy.md`, `sequence-booking.md` | Đã đồng bộ với giữ tồn theo đêm, huỷ `PENDING_PAYMENT` và trả tồn | C |
| `erd-v2.md` | Đã đồng bộ `hotel.address`, bỏ `room_type.code`, ghi rõ giá mỗi phòng/đêm và huỷ `PENDING_PAYMENT` | B/C |
| `booking-service/README.md`, code và migration | Cần được đối chiếu khi triển khai contract hiện hành theo đêm | B/C |
| Frontend, `docs/demo.http` | Theo API mới | D |

**Nguyên tắc cuối:** việc ký G2 và việc API chạy đúng vẫn cần xác nhận, triển khai và kiểm thử thực tế. Sau khi ký, chỉ đổi contract bằng PR có bên cung cấp **và** bên sử dụng API review.
