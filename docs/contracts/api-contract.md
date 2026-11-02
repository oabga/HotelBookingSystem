# StayHub — API Contract chung (Google SSO + Rooms + Booking)

> **Phiên bản:** 1.0 — đề xuất chốt G2, ngày 02/11/2026  
> **Trạng thái:** **READY FOR TEAM SIGN-OFF** — đặc tả hoàn chỉnh để review; **chưa được coi là đã ký nhận hay đã triển khai**.  
> **Kế hoạch có hiệu lực:** [`iam-google-sso-demo-plan-v2.md`](iam-google-sso-demo-plan-v2.md). Không sử dụng tham chiếu `plan-v3` vì chưa có phiên bản đó trong nguồn đã đối chiếu.  
> **Phạm vi:** Demo local trong 3 ngày, 4 vai A/B/C/D. Đây là hợp đồng giữa các thành viên và các ứng dụng, không phải đặc tả production.

## 0. Quyết định chung và ranh giới

- **Google SSO thay V-ID**, thông qua `iam-service` đóng vai trò **OAuth2/OIDC client của Google**; IAM phát **JWT nội bộ** RS256. Không dùng Keycloak; không xây OAuth2 authorization server riêng.
- Có **2 tenant**, 2 role `USER` và `ADMIN`. `USER` xem/đặt phòng; `ADMIN` xem/thêm phòng/sửa giá và xem booking trong tenant mình. `ADMIN` **không** gọi API tạo booking.
- Một `room` có cờ `available`, không có ngày ở/check-in/check-out. Đặt được phòng thì booking **`CONFIRMED` ngay**; hold phòng phải nguyên tử.
- **Không triển khai** thanh toán, hủy/release, notification/push, idempotency key, refresh/revocation, inventory theo ngày, UI đầy đủ hay message broker.
- **Owner:** **A (Bảo)** — IAM/JWT/PKCE/Gateway/CORS/root platform; **B (Hòa)** — Catalog/Room/atomic hold; **C (Phúc)** — Booking; **D (Đạt)** — hai frontend demo, `docs/demo.http`, kiểm thử E2E, tài liệu/bằng chứng. D chỉ đề xuất/review contract, không tự sửa service của A/B/C.
- **Quy tắc ưu tiên:** Plan v2 quyết định scope, kiến trúc và quyền sở hữu. File này chốt **chi tiết interface** sau khi cả bốn người ký nhận. Điểm cụ thể hóa hoặc sửa so với contract 0.1 được liệt kê ở §12, **không mặc nhiên là quyết định mentor**.

## 1. Quy ước toàn hệ thống

| Mục | Quy ước thống nhất |
|---|---|
| Môi trường | Local demo, dùng duy nhất hostname `localhost` trong URL browser/callback; **không trộn** `localhost` với `127.0.0.1` khi thử SSO/cookie |
| Frontend | App 1 `http://localhost:3000`; App 2 `http://localhost:3001` (hai origin khác nhau) |
| API công khai cho frontend | Gateway `http://localhost:8080`; API được gọi dưới `/api/**` |
| IAM (điều hướng/login/JWKS) | `http://localhost:8084` **trực tiếp**, không cần qua Gateway |
| Service nội bộ | Catalog `http://localhost:8081`; Booking `http://localhost:8082` |
| Database | `iam_db`, `catalog_db`, `booking_db` độc lập trên cùng PostgreSQL local; mỗi DB tự chạy Flyway |
| Dữ liệu | JSON UTF-8; request có body JSON gửi `Content-Type: application/json`; response JSON dùng `camelCase` |
| Ngày giờ | ISO 8601 UTC, ví dụ `2026-11-03T02:15:30Z`; JWT `iat`/`exp` là Unix seconds |
| Tiền | `price`: JSON number, **VND**, `> 0`, tối đa `9999999999.99`, tối đa 2 chữ số thập phân; lưu DB bằng `DECIMAL/NUMERIC`, không dùng float/double cho phép tính |
| ID phòng/booking | Số nguyên dương (DB `BIGINT` hoặc kiểu nguyên phù hợp, nhất quán JSON); ID không hợp lệ → 400 |
| `tenant_id` | UUID trong JWT và DB; response JSON dùng `tenantId` (chuỗi UUID) |
| Danh sách | Mảng JSON, không phân trang trong demo, theo `id` tăng dần; rỗng → `[]` |
| Tạo mới | `201 Created` với JSON tài nguyên; không bắt buộc header `Location` trong demo |
| Không tin dữ liệu client | `tenantId`, role, người tạo booking **chỉ được suy ra từ JWT đã xác minh**, không nhận từ body/query/header tùy ý |
| Field lạ ở request | DTO chỉ ánh xạ các trường được phép; trường lạ (kể cả `tenantId`) bị **bỏ qua**, tuyệt đối không ảnh hưởng tenant/quyền. Có test chứng minh không thể đổi tenant qua body |
| Authentication | `Authorization: Bearer <stayhub-jwt>` cho **mọi** API `/api/rooms/**`, `/api/bookings/**` và lời gọi Booking → Catalog; **không** dùng Google access token trực tiếp |

**Không có endpoint nào được coi là chạy thật chỉ vì đã có contract.** URL, JSON và trạng thái ở đây là mục tiêu để các owner code và D kiểm thử sau khi ký G2.

## 2. Bản đồ route và phân quyền (source of truth)

Gateway **bỏ đúng một tiền tố `/api`** bằng `StripPrefix=1` (hoặc rewrite tương đương) và **forward nguyên Bearer token**. Dấu `*`/`{id}` chỉ là ký hiệu, không phải phần URL literal.

| Frontend/HTTP script gọi | Service nhận | Role | Thành công | Lỗi chính | Owner |
|---|---|---|---|---|---|
| `GET /api/rooms` | `GET /rooms` | `USER`, `ADMIN` | 200 | 401 | B |
| `GET /api/rooms/{id}` | `GET /rooms/{id}` | `USER`, `ADMIN` | 200 | 400, 404 | B |
| `POST /api/rooms` | `POST /rooms` | `ADMIN` | 201 | 400, 403 | B |
| `PUT /api/rooms/{id}/price` | `PUT /rooms/{id}/price` | `ADMIN` | 200 | 400, 403, 404 | B |
| `POST /api/bookings` | `POST /bookings` | `USER` | 201 | 400, 403, 404, 409, 503 | C |
| `GET /api/bookings` | `GET /bookings` | `USER`, `ADMIN` | 200 | 401 | C |
| `GET /api/bookings/{id}` | `GET /bookings/{id}` | `USER`, `ADMIN` | 200 | 400, 404 | C |

| Direct call (không qua Gateway) | Người gọi | Quyền/yêu cầu | Thành công | Lỗi chính | Owner |
|---|---|---|---|---|---|
| `POST http://localhost:8081/internal/rooms/{id}/hold` | **Chỉ Booking → Catalog** | JWT `USER` + khóa nội bộ | 200 | 400, 401, 403, 404, 409 | B/C |
| `GET http://localhost:8084/auth/start?app=app1\|app2` | Browser navigation | `app` trong allowlist | 302 | 400, 403 trong luồng đăng nhập | A |
| `GET http://localhost:8084/login/oauth2/code/google` | Google → IAM | OAuth2 `code`/`state` | Theo luồng | Theo luồng OAuth2 | A |
| `GET http://localhost:8084/.well-known/jwks.json` | Gateway/B/C | Public | 200 | 5xx nếu IAM lỗi | A |

Tất cả business API yêu cầu JWT hợp lệ; ngoài lỗi được liệt kê vẫn có thể trả 401 (thiếu/sai token) hoặc 500 khi lỗi server chưa xử lý. **Không có route** `/internal/**`, `/dev/**`, `/auth/**` trên Gateway. OAuth/SSO/JWKS truy cập trực tiếp IAM theo bảng trên.

### Quy tắc thứ tự lỗi

Áp dụng cho business API khi có nhiều sai sót: **401 (JWT) → 403 (role/quyền API) → 400 (tham số/body) → 404 (không thấy trong tenant/owner) → 409 (phòng đã giữ)**. Lỗi sự cố hạ tầng như 500/503 là nhánh riêng; không ép thành 409. `OPTIONS` CORS hợp lệ được xử lý trước bộ lọc JWT.

| HTTP | Ý nghĩa | Response body |
|---|---|---|
| **400** | ID không nguyên dương, body thiếu/sai kiểu, ràng buộc `name`/`price`/`roomId` sai | `application/problem+json` |
| **401** | Thiếu, sai chữ ký, issuer sai, token hết hạn, thiếu/malformed JWT claim bắt buộc | Không ràng buộc JSON; chỉ kiểm tra status |
| **403** | JWT hợp lệ nhưng role không đủ; hoặc **internal key** không hợp lệ ở API nội bộ | Không ràng buộc JSON; chỉ kiểm tra status |
| **404** | Bản ghi không tồn tại, thuộc tenant khác hoặc booking của user khác | `application/problem+json` |
| **409** | Room trong tenant đã `available=false` tại thời điểm hold | `application/problem+json` |
| **503** | Booking không gọi được Catalog hoặc nhận lỗi upstream ngoài 200/404/409 | `application/problem+json` |
| **500** | Lỗi nội bộ (ví dụ ghi Booking DB thất bại sau hold) | `application/problem+json` khi có thể |

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
- `ProblemDetail` áp dụng cho các lỗi 400/404/409/500/503 do service quản lý; 401/403 có thể do Spring Security tạo body trống.

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

**Chính sách provision tối giản:** `iam_db.app_user` được seed sẵn với email Google test thực, `tenant_id`, role. IAM kiểm tra email đã xác minh, chuẩn hóa email (`trim` + lowercase), tìm đúng bản ghi pre-provision. Khi lưu `google_sub`, nếu chưa bind có thể bind vào lần login hợp lệ đầu tiên; nếu đã bind thì **bắt buộc so khớp**. Không tạo user/tenant chỉ vì Gmail mới xuất hiện. Email và secret test thật không commit vào repo.

**Frontend D:** tại `/callback/`, đọc `location.hash` dạng `#token=...`; lập tức dùng `history.replaceState` loại fragment khỏi thanh địa chỉ, chỉ giữ JWT trong **biến bộ nhớ** của tab hiện tại. **Không dùng** localStorage/sessionStorage/cookie tự tạo để chứa JWT và không log nguyên token. Nếu trang callback cần quay về màn hình chính, chuyển giao token **trong runtime cùng trang**, không làm mất token bằng full page navigation. Nếu tải lại trang, người dùng cần login lại (IAM có thể SSO không hỏi mật khẩu).

**Không có `/auth/logout` hay `/dev/token` trong MVP/contract bắt buộc.** Một nút “Xóa phiên ở trang này” (nếu D làm) chỉ xóa token trong memory, **không** gọi đó là Google logout hay JWT revocation. Không dùng API mint token theo `tenantId`/role do caller tự cung cấp để chứng minh Google SSO hoặc bảo mật tenant. Negative JWT tests sử dụng fixture/unit test của A hoặc token đã hết hạn thực tế.

### 3.3 JWT nội bộ và JWKS

IAM ký JWT với **RS256**; JOSE header có `alg=RS256`, `kid` để chọn public key. Phát hành token sống **15 phút**. Không có refresh, revoke hay rotation tự động.

Ví dụ **payload minh họa**, không phải token dùng thật:

```json
{
  "iss": "http://localhost:8084",
  "sub": "8f14e45f-ceea-4e7a-9a1b-0d1f2c3b4a59",
  "email": "user-a1@example.test",
  "tenant_id": "11111111-1111-1111-1111-111111111111",
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
| `sub` | Bắt buộc, không rỗng; là ID nội bộ ổn định, **chuỗi** | 401 |
| `email` | Bắt buộc, không rỗng | 401 |
| `tenant_id` | Bắt buộc, UUID parse được | 401 |
| `role` | Bắt buộc, chỉ `USER` hoặc `ADMIN` | 401 |

- **Gateway, Catalog, Booking cùng kiểm tra chữ ký, issuer, expiry và các claim bắt buộc**, không cho token thiếu tenant/role lọt qua. Mapping `role` → `ROLE_USER`/`ROLE_ADMIN`; lấy `tenant_id` từ JWT đã verify, không có tenant mặc định.
- `jwks-uri`: `http://localhost:8084/.well-known/jwks.json`. Resource Server chỉ rõ **cả `issuer-uri` và `jwk-set-uri`** (hoặc decoder tương đương kiểm `iss`), **không** trông chờ IAM tự có OIDC discovery endpoint. Với `jwk-set-uri`, mặc định phải kiểm tra việc validate issuer thực tế.
- `sub` dùng để kiểm soát **ownership Booking**; `email` lưu để minh họa, không dùng địa chỉ email do client gửi làm khóa kiểm tra chủ sở hữu.
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

## 4. Catalog — Rooms API và atomic hold (B)

### 4.1 Room JSON và dữ liệu mẫu

Response `Room` có đủ **5 trường**:

| Trường | Kiểu | Ghi chú |
|---|---|---|
| `id` | integer > 0 | ID room DB |
| `tenantId` | UUID string | Tenant từ row thuộc JWT, chỉ để hiển thị/đối chiếu |
| `name` | string | 1–100 ký tự sau trim; không blank |
| `price` | number | Đơn vị VND, >0, tối đa 2 decimal |
| `available` | boolean | `true` có thể đặt, `false` đã giữ/đặt |

Ví dụ Room:

```json
{
  "id": 1,
  "tenantId": "11111111-1111-1111-1111-111111111111",
  "name": "Room A-101",
  "price": 1500000.00,
  "available": true
}
```

### 4.2 `GET /api/rooms` → `GET /rooms`

- `USER`/`ADMIN`: **200** + array Room, chỉ room có `tenant_id` bằng tenant claim. Không lọc riêng theo `available` (hiển thị cả phòng đã đặt).
- Có thể trả `[]`; không phân trang. Test dùng 2 JWT tenant khác nhau phải có danh sách không lẫn.

Ví dụ response 200:

```json
[
  {"id": 1, "tenantId": "11111111-1111-1111-1111-111111111111", "name": "Room A-101", "price": 1500000.00, "available": true},
  {"id": 2, "tenantId": "11111111-1111-1111-1111-111111111111", "name": "Room A-102", "price": 1800000.00, "available": true}
]
```

### 4.3 `GET /api/rooms/{id}` → `GET /rooms/{id}`

- `USER`/`ADMIN`: **200** + Room JSON như §4.1.
- ID không nguyên dương → **400**; room không tồn tại hoặc thuộc tenant khác → **404** giống nhau.
- Đây là **endpoint đọc hỗ trợ frontend/kiểm tra kết quả**, có thể được Booking gọi khi cần thông tin phòng; **không được thay thế atomic hold bằng `GET` rồi `UPDATE` riêng rẽ**.

### 4.4 `POST /api/rooms` → `POST /rooms`

`ADMIN` mới được gọi. Request:

```json
{
  "name": "Room A-201",
  "price": 2500000.00
}
```

- `name`: string 1–100 ký tự sau trim, không blank; cho phép trùng tên.
- `price`: JSON number >0 và ≤9999999999.99, tối đa 2 decimal.
- Không nhận tenant/available từ client; tạo với `tenant_id` lấy từ JWT và `available=true`. Trường `tenantId` lạ nếu bị gửi thêm cũng **không có tác dụng**.
- **201** + Room JSON gồm ID được DB cấp, tenant thật và `available=true`. Không giả định ID phòng mới luôn bắt đầu từ 100; database sequence phải tránh đụng seed IDs.
- `USER` gọi dù body đúng hay sai → **403**; `ADMIN` body thiếu/sai → **400**.

### 4.5 `PUT /api/rooms/{id}/price` → `PUT /rooms/{id}/price`

`ADMIN` mới được gọi. Request:

```json
{"price": 2000000.00}
```

- `price` cùng ràng buộc như POST; không thay name/tenant/available.
- **200** + Room JSON đã cập nhật (gồm `available` không đổi).
- `USER` → **403**; ID/body sai → **400**; room không có trong tenant → **404** (không lộ trạng thái room tenant khác). Không thay giá nếu lỗi.

### 4.6 `POST /internal/rooms/{id}/hold` — API nội bộ B ↔ C

**Đây là endpoint bắt buộc**, do **C/Booking gọi trực tiếp Catalog**, **không qua Gateway**. Chỉ dành cho luồng tạo Booking, không được gọi từ UI để “test chức năng đặt phòng”.

```http
POST http://localhost:8081/internal/rooms/1/hold
Authorization: Bearer <stayhub-jwt-user>
X-Internal-Service-Key: <local-secret-from-env>
```

- **Body:** không có. `id` là room ID nguyên dương. `USER` trong JWT là người đặt.
- **Bảo vệ tối thiểu cho demo local:** Catalog yêu cầu **cả JWT hợp lệ với role `USER`** lẫn header bí mật `X-Internal-Service-Key` khớp cấu hình môi trường của Booking/Catalog. Khóa này do A/B/C phối hợp cấu hình bằng biến môi trường/Compose, **không commit**, không gửi từ browser, không viết vào `docs/demo.http`. Nếu không cấu hình key, endpoint phải **fail closed**, không hoạt động ở chế độ public. Không triển khai IAM service-token/OAuth client-credentials chỉ cho 3 ngày demo.
- Mặc dù dùng header bảo vệ, vẫn **không route `/internal/**`** qua Gateway; ưu tiên không publish Catalog port ra ngoài máy local khi triển khai Compose. Đây là mức bảo vệ **demo-local**, không coi là mTLS/service identity production.
- Catalog **tự verify Bearer token**, lấy `tenant_id` từ JWT; không nhận `tenant_id` từ request body hoặc `X-Internal-Service-Key`.
- Catalog thực hiện atomic update trên DB trong transaction:

```sql
UPDATE room
SET available = FALSE
WHERE id = :roomId
  AND tenant_id = :tenantIdFromVerifiedJwt
  AND available = TRUE;
```

| Tình huống | HTTP | Body |
|---|---|---|
| Update đúng **1 row** | **200** | `{"roomId":1,"held":true}` |
| Room không tồn tại hoặc thuộc tenant khác | **404** | `ProblemDetail` (`Room not found`) |
| Room thuộc tenant hiện tại nhưng `available=false` | **409** | `ProblemDetail` (`Room is not available`) |
| ID không nguyên dương | **400** | `ProblemDetail` |
| JWT thiếu/hỏng/thiếu claim | **401** | Body không ràng buộc |
| Role sai hoặc internal key không khớp/thiếu | **403** | Body không ràng buộc |

Nếu SQL ảnh hưởng 0 row, kiểm tra room **trong cùng tenant** để phân biệt 404/409; không truy vấn/trả thông tin của tenant khác. Commit transaction trước khi báo 200; test hai request đồng thời vào một room `available=true` phải có **một 200 và một 409**, không âm tồn kho/không có hai hold thành công.

**Không có `/release` trong MVP.** Nếu Catalog giữ phòng thành công nhưng ghi Booking DB lỗi, có thể còn phòng `available=false` không gắn booking. Đây là giới hạn chủ ý **không có distributed transaction/compensation**; thực hiện reset seed/DB an toàn cho dữ liệu thử theo hướng dẫn B cung cấp. Không được ghi “đã rollback hold” nếu chưa triển khai thật.

## 5. Booking — API tạo và xem booking (C)

### 5.1 Booking JSON và ownership

| Trường response | Kiểu | Ghi chú |
|---|---|---|
| `id` | integer > 0 | ID DB cấp |
| `tenantId` | UUID string | Tenant từ verified JWT |
| `roomId` | integer > 0 | Phòng đã hold |
| `userSub` | string | `sub` nội bộ ổn định từ JWT, dùng kiểm tra sở hữu |
| `userEmail` | string | Email từ JWT; chỉ để hiển thị/demo |
| `status` | string | Chỉ `CONFIRMED` trong MVP |
| `createdAt` | ISO UTC string | Thời gian tạo theo server |

`booking_db.booking` phải lưu tối thiểu `tenant_id`, `room_id`, `user_sub` (TEXT/VARCHAR, **không ép UUID** để dễ test fixture), `user_email`, `status`, `created_at`. Đây là bổ sung **nhỏ và cần thiết** so với bảng tối thiểu Plan v2 (`user_email`) để thực thi kiểm tra chủ booking bằng ID ổn định. Không cho client cung cấp `userSub`, `userEmail`, `tenantId` hoặc `status` để quyết định record được lưu.

### 5.2 `POST /api/bookings` → `POST /bookings`

Chỉ `USER`. Request:

```json
{"roomId":1}
```

- `roomId` bắt buộc là số nguyên dương; thiếu/sai → **400**, **không gọi Catalog**.
- C lấy `tenant_id`/`sub`/`email` từ JWT đã verify và **forward nguyên Bearer JWT** khi gọi `POST http://localhost:8081/internal/rooms/{roomId}/hold` (đính kèm khóa nội bộ từ cấu hình server, không từ client).
- Chỉ khi **Catalog trả 200 hold thành công** thì ghi `booking_db` với trạng thái `CONFIRMED`, sau đó trả **201**. Không trả 201 nếu chưa lưu booking thành công.

Response **201** minh họa:

```json
{
  "id": 1,
  "tenantId": "11111111-1111-1111-1111-111111111111",
  "roomId": 1,
  "userSub": "8f14e45f-ceea-4e7a-9a1b-0d1f2c3b4a59",
  "userEmail": "user-a1@example.test",
  "status": "CONFIRMED",
  "createdAt": "2026-11-03T02:15:30Z"
}
```

**Bảng ánh xạ Catalog → Booking — TUYỆT ĐỐI KHÔNG NHẦM 409 VỚI 503:**

| Kết quả gọi Catalog hold | Booking trả client | DB Booking |
|---|---|---|
| **200**; lưu booking thành công | **201 `CONFIRMED`** | 1 booking mới |
| **404** (không có room trong tenant) | **404** | Không tạo booking |
| **409** (room đã được giữ) | **409** | Không tạo booking |
| Timeout, connection refused, lỗi upstream 5xx hoặc status bất ngờ (kể cả 401/403 do cấu hình giữa service sai) | **503** | Không báo tạo thành công; trạng thái hold có thể **chưa xác định** nếu đã có network request |
| Hold 200 nhưng **lưu booking DB thất bại** | **500** | Không có booking xác nhận; **có thể tồn tại orphan hold** (giới hạn §4.6) |

- `ADMIN` gọi `POST /bookings` → **403**. Cùng một `USER` gửi lại room đã giữ → **409**; không có idempotency key/retry tự sinh booking thứ hai.
- Race test **từ state sạch**: hai POST cạnh tranh cùng một room → một **201**, một **409**; bảng Booking chỉ có một row `CONFIRMED` của room đó. Có thể dùng hai USER cùng tenant; nếu dùng cùng JWT, ghi là **hai request**, không tuyên bố là hai người khác nhau.
- Không tự động retry lời gọi hold sau timeout (có thể hold đã thành công), không tự động biến lỗi 503 thành 409. Nêu rõ giới hạn reliability cho mentor.

### 5.3 `GET /api/bookings` → `GET /bookings`

- **200** + mảng Booking theo `id` tăng dần.
- `USER`: chỉ những booking có `tenant_id` **và** `user_sub` khớp JWT.
- `ADMIN`: toàn bộ booking **trong tenant** từ JWT. Không bao giờ trả booking tenant khác, kể cả khi client gửi ID/tenant giả.
- Không có dữ liệu trả `[]`.

### 5.4 `GET /api/bookings/{id}` → `GET /bookings/{id}`

- **200** + Booking JSON nếu đúng quyền.
- **404** nếu ID không tồn tại, tenant khác, hoặc `USER` không phải chủ (`user_sub` khác); response không tiết lộ lý do.
- **400** nếu ID không nguyên dương. `ADMIN` được xem booking của mọi user trong tenant mình.

## 6. Gateway & CORS (A), tích hợp browser (D)

A chịu trách nhiệm **Gateway/CORS**, D chỉ kiểm thử và báo nhu cầu origin/headers. Route phải match cả đường dẫn danh sách đúng `/api/rooms`, `/api/bookings` **và** đường dẫn có thêm `/{id}`; không để rule wildcard bỏ sót đường dẫn gốc. B/C bảo vệ service bằng JWT **kể cả khi gọi trực tiếp qua `:8081/:8082`**; Gateway không phải lớp bảo mật duy nhất.

| Rule | Giá trị |
|---|---|
| Route 1 | `/api/rooms/**` → `http://localhost:8081/rooms/**` (`StripPrefix=1`) |
| Route 2 | `/api/bookings/**` → `http://localhost:8082/bookings/**` (`StripPrefix=1`) |
| Bearer | Giữ nguyên `Authorization` khi chuyển tiếp |
| Từ chối | Không route `/internal/**`, `/dev/**` hay phần `/auth/**` tại Gateway |
| Preflight | `OPTIONS` hợp lệ xử lý trước JWT, không yêu cầu Bearer |

| CORS field | Giá trị |
|---|---|
| Allowed origins | `http://localhost:3000`, `http://localhost:3001` (exact match) |
| Methods | `GET, POST, PUT, OPTIONS` |
| Allowed headers | `Authorization, Content-Type` |
| Allow credentials | `false` (gửi JWT header, không fetch kèm cookie) |
| Wildcard origins | **Không** dùng `*` thay cho allowlist |

- Gateway trả preflight hợp lệ cho browser khi gửi JSON/Bearer; không chặn vì OPTIONS không có token. Các service không chủ động thêm CORS header trùng với gateway.
- Browser chỉ gọi business API qua **`http://localhost:8080/api/...`**, **không** gọi trực tiếp Catalog/Booking hoặc internal hold. Login là **browser navigation** sang IAM (khác với CORS `fetch`).
- Dùng `localhost` nhất quán với JWT issuer và allowlist. API lỗi 401/403 phải được UI hiển thị an toàn, không log JWT. Không coi 403 IAM do chưa provision là lỗi role 403 của API.

## 7. Seed, account alias, reset và ví dụ kiểm thử

### 7.1 Dữ liệu hợp đồng (alias; không chứa danh tính thật)

| Tài nguyên | Giá trị dùng chung | Owner seed/xác nhận |
|---|---|---|
| Tenant A | `11111111-1111-1111-1111-111111111111` | A/B |
| Tenant B | `22222222-2222-2222-2222-222222222222` | A/B |
| Tenant A room seed | ID `1`, `2`, `3` | B |
| Tenant B room seed | ID `4`, `5`, `6` | B |
| Happy-path room | A: `1`, `available=true` trước demo | B |
| Race-only room | A: `3`, `available=true` trước test cạnh tranh | B |
| Cross-tenant room | B: `4` | B |
| Booking ID | ID phát sinh từ DB, **không hard-code** | C |
| Role | `USER`, `ADMIN` | A |
| Booking status | `CONFIRMED` | C |

Các alias test `USER_A1`, `ADMIN_A`, `USER_B1`, `ADMIN_B`; nếu demo hai người tranh phòng thực sự thêm `USER_A2`. **Email Google thật**, client secret, Bearer JWT, khóa nội bộ và RSA private key **không được lưu vào Git**. Đặt bảng alias + cách lấy token vào `docs/demo-data.md` (D tổng hợp với A/B/C), giá seed lấy từ B.

- B cung cấp **cách seed/reset có thể tái hiện**, nêu rõ sẽ **xóa booking trước khi reset room**, không tự ý chỉnh `available=true` khi booking vẫn còn tồn tại.
- Demo bước đặt phòng dùng ID 1; bước race dùng ID 3 hoặc reset lại cả state theo tài liệu B/C. Khi demo lặp lần thứ hai, đảm bảo state hoàn toàn sạch.
- Mọi seed chỉ minh họa/local. Không giả định ID mới luôn ≥100, trừ khi B chủ động chốt sequence và cập nhật tài liệu.

### 7.2 Ma trận kiểm thử hợp đồng tối thiểu

| ID | Request/tình huống | Kỳ vọng bắt buộc | Owner bằng chứng |
|---|---|---|---|
| T01 | GET rooms bằng USER_A1 | 200, chỉ A, có `available` | B/D |
| T02 | GET rooms bằng USER_B1 | 200, chỉ B | B/D |
| T03 | POST rooms bằng USER_A1 | 403, không thêm room | B/D |
| T04 | POST rooms bằng ADMIN_A | 201, `tenantId=A`, `available=true` | B/D |
| T05 | PUT room price bằng USER_A1 | 403, giá không đổi | B/D |
| T06 | PUT room tenant B bằng ADMIN_A | 404, giá không đổi | B/D |
| T07 | POST room kèm `tenantId=B` bằng ADMIN_A | Không thể tạo vào tenant B; field lạ bỏ qua, kết quả tenant A | B/D |
| T08 | GET room ID của tenant khác | 404, không lộ dữ liệu | B/D |
| T09 | POST booking `{roomId:1}` bằng USER_A1 | 201 CONFIRMED, A:room1 `available=false`, Booking DB một row | B/C/D |
| T10 | POST lại cùng room đã đặt | 409, không tạo booking mới | B/C/D |
| T11 | Hai POST đồng thời room3 từ state sạch | **Một 201, một 409**; DB chỉ có 1 booking | B/C/D |
| T12 | USER_A1 POST booking room thuộc B | 404, không tạo booking | B/C/D |
| T13 | ADMIN_A POST booking | 403 | C/D |
| T14 | USER_A1 GET bookings | 200, chỉ của sub A1 trong tenant A | C/D |
| T15 | ADMIN_A GET bookings | 200, tất cả booking trong tenant A | C/D |
| T16 | USER_B1 / USER_A khác truy cập booking A1 bằng ID | 404, không lộ owner/tenant | C/D |
| T17 | GET business API thiếu, sai chữ ký, hết hạn JWT | 401 mỗi trường hợp | A/D |
| T18 | JWT hợp lệ nhưng thiếu `tenant_id`/`role` | 401, test bằng fixture/unit hoặc cấu hình test A | A/D |
| T19 | Login Google user chưa provision/không verified | 403 IAM, không thêm user, không phát JWT | A/D |
| T20 | Login App 1 → App 2 cùng browser profile | Không nhập lại mật khẩu, app2 nhận JWT của IAM | A/D |
| T21 | Google authorize có `code_challenge` và `S256` | Evidence PKCE thực, Google callback thành công | A/D |
| T22 | Direct internal hold thiếu khóa, dù JWT hợp lệ | 403; qua Gateway `/internal/**` không được route | A/B/C |
| T23 | Catalog không truy cập được khi POST booking | 503; không có booking CONFIRMED | C/D |
| T24 | `gradlew.bat clean build`, unit tests IAM/B/C, 3 Flyway histories | Build/test xanh, migrations thực, không skip để qua | A/B/C/D |

**Lưu ý test JWT:** Token hết hạn thật có thể phải đợi >15 phút hoặc dùng test fixture riêng; đừng commit/sao chép JWT. Token giả thiếu claim chỉ kiểm tra qua fixture của A, **không cần tạo endpoint phát JWT public**. Kiểm thử tranh phòng **phải gửi đồng thời**; chạy 2 request tuần tự chỉ kiểm được việc đặt lại trả 409.

## 8. Dữ liệu bàn giao để D lập frontend và `docs/demo.http`

| Ai giao | Dữ liệu/artifact cần giao | Điều kiện D xác nhận nhận bàn giao |
|---|---|---|
| **A → D/B/C** | IAM login URL + Google callback + **frontend callback `/callback/`**; issuer/JWKS, role/claims, origins, config CORS; cách có tài khoản Google được provision và thu evidence PKCE | D mở được hai origin, nắm cách login thật; B/C verify cùng kiểu JWT |
| **B → C/D** | Room JSON đủ `available`, GET/POST/PUT, ID seed 1–6, script reset; contract atomic hold 200/404/409, cấu hình khóa nội bộ | C gọi hold thật; D test UI và dữ liệu đúng tenant |
| **C → D** | Booking POST/GET JSON, error 400/403/404/409/503, cách test race; cách xem booking DB | D viết `demo.http` chính xác và có bằng chứng 201/409 |
| **A/B/C → D** | Lệnh chạy Gradle/DB, các unit test reports, 3 Mermaid diagrams và limitations đã biết | D tổng hợp vào `demo-script.md`, `test-evidence.md`, README |

D **không đưa token/client secret/private key vào `docs/demo.http`**. Dùng biến placeholder `@BASE_URL`, `@JWT_USER_A1`, `@JWT_ADMIN_A`, `@JWT_USER_B1`, `@ROOM_ID`, `@BOOKING_ID`, v.v.; các bearer token thật chỉ đưa vào runtime/test environment không theo Git. Khi contract này được ký, D sửa tài liệu/frontend theo URL `/callback/` và request/response ở đây; những phần đã mock vẫn đánh dấu `MOCK ONLY` cho đến khi chạy thật.

## 9. Ranh giới lỗi và giới hạn cần công bố khi demo

1. **Không có transaction xuyên DB.** Hold tại Catalog commit rồi mới ghi Booking; nếu Booking insert lỗi, có thể sinh orphan hold. Không có `release`/retry/idempotency trong MVP. Phải reset demo bằng script được B/C xác nhận và ghi known limitation; không được claim nhất quán tuyệt đối.
2. **Không tự động retry khi timeout hold.** Không chắc request trước đã áp dụng hay chưa. Booking trả 503 và log correlation an toàn; chỉ kiểm tra/khôi phục state theo hướng dẫn test.
3. **Khóa nội bộ tĩnh là rào chắn demo local**, không thay được mTLS hay service identity production. Phải không expose `/internal` qua Gateway, không commit key, fail closed khi thiếu key.
4. **Google SSO không đồng nghĩa phát JWT OIDC chuẩn cho bên thứ ba.** IAM là OAuth2 client và JWT issuer nội bộ, không phải full authorization server. Token ở URL fragment là simplification demo; XSS/browser extension vẫn có rủi ro.
5. **Không có Google logout toàn cục, JWT revoke, refresh token hoặc key rotation.** Clearing token trong tab không thu hồi JWT đã phát. `localhost` HTTP chỉ dùng local, không dùng mô hình này ở production.
6. `UNIT TEST` của A/B/C khác với `INTEGRATION/E2E` của D; không đánh dấu PASS khi chỉ có code/markdown hoặc mock.

## 10. Gate G2 — Điều kiện ký và đóng băng contract

G2 chỉ **PASS khi tất cả mục sau hoàn thành**:

- [ ] A/B/C/D cùng xác nhận **Plan v2 là nguồn scope**, bỏ link v3 không có trong repo; đồng ý owner như §0.
- [ ] A xác nhận **IAM redirect `.../callback/`**, OAuth redirect IAM/PKCE, issuer, JWT claim validator, CORS/Gateway và lỗi email chưa provision **403**.
- [ ] B xác nhận `available`/Room JSON, `hold` 200/404/409, atomic update, tenant isolation, seed/reset và khóa nội bộ.
- [ ] C xác nhận 409 giữ nguyên từ Catalog, 503 chỉ cho sự cố gọi upstream, `userSub` ownership, JSON Booking và giới hạn orphan hold.
- [ ] D xác nhận hai static callback URLs **thực sự được server phục vụ**, frontend/demo.http có thể triển khai theo response/error mới, không dùng API ngoài scope.
- [ ] Tất cả nhất trí thay thế contract cũ và chỉ đổi contract sau đó bằng PR có bên cung cấp **và** bên sử dụng API review.

| Người | Vai trò | Phần cung cấp | Phần sử dụng/duyệt | Xác nhận G2 |
|---|---|---|---|---|
| **Bảo (A)** | IAM/Platform | §3, §6, key env phối hợp | JWT/API requirements của B/C/D | [ ] |
| **Hòa (B)** | Catalog | §4, seed §7 | JWT A, lời gọi Booking C | [ ] |
| **Phúc (C)** | Booking | §5 | JWT A, hold B, demo D | [ ] |
| **Đạt (D)** | Frontend/QA | HTML, `demo.http`, kiểm thử §7/§8 | IAM/Rooms/Booking, scripts/evidence | [ ] |

**Một PR tích hợp không được đổi field/path/status/role/issuer/port hoặc luật giữ phòng chỉ để “chạy được” mà không cập nhật contract và nhận xác nhận của bên liên quan.** Sau G2, check PR/commit + log/HTTP response/test mới là bằng chứng PASS khi triển khai.

## 11. Nguồn kỹ thuật để implement (không thay thế phê duyệt nhóm)

- Plan đã chốt: [`iam-google-sso-demo-plan-v2.md`](iam-google-sso-demo-plan-v2.md); danh sách chủ đề mentor: [`team-plan.md`](team-plan.md).
- Spring Security — [Authorization Code + PKCE (confidential client cần bật tường minh)](https://docs.spring.io/spring-security/reference/servlet/oauth2/client/authorization-grants.html).
- Spring Security — [JWT Resource Server: `issuer-uri` với `jwk-set-uri` chỉ định rõ](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).
- Spring Security — [CORS & preflight trước authentication](https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html).
- Google — [OAuth 2.0 for Web Server Applications: callback khớp tuyệt đối](https://developers.google.com/identity/protocols/oauth2/web-server).

## 12. Nhật ký sửa lỗi contract 0.1 và các quyết định được cụ thể hóa

| Vấn đề cũ | Bản 1.0 xử lý |
|---|---|
| Booking 503 nuốt cả Catalog 409 | Bảng 200 → 201; 404 → 404; **409 → 409**; lỗi mạng/upstream → 503 (§5.2) |
| Thiếu contract atomic hold | Thêm endpoint nội bộ không body, 200/404/409, JWT forwarded, khóa nội bộ, SQL atomic và test (§4.6) |
| Room thiếu `available` | Thêm vào **tất cả response Room**, tạo phòng mặc định `available=true` (§4) |
| IAM 302 `#error` trái Plan v2 403 | Chốt **403 trực tiếp tại IAM** khi Google user không được provision/verified (§3.2) |
| Gateway/CORS gán sai cho D | Chuyển đúng **A sở hữu**, D kiểm thử (§0, §6) |
| Plan v3 không có trong nguồn đã kiểm tra | Dùng **Plan v2 làm source of truth** (§0); không viện dẫn kịch bản I-xx/script chưa tồn tại |
| `/callback` (v2) vs `/` (contract cũ) | Chốt **`/callback/`**, serve bằng static directory `callback/index.html` hoặc route tương đương (§3.1). Đây là lựa chọn tương thích static server, cần A/D ký |
| `userSub` không có trong schema tối thiểu v2 | Bổ sung `user_sub` để kiểm tra chủ booking bằng ID ổn định (§5.1), cần C ký |
| `/dev/token` không nằm scope v2 và nguy cơ bảo mật | **Loại khỏi contract bắt buộc**; negative token tests dùng fixture/unit tests (§3.2, §7.2) |
| `/auth/logout` không nằm MVP, token không revoke | **Không cam kết logout**; chỉ xóa token khỏi bộ nhớ UI (§3.2) |
| Internal URL gọi trực tiếp có thể bị client JWT tự gọi | Thêm khóa nội bộ từ env và fail-closed, không đi Gateway; ghi hạn chế demo local (§4.6). Đây là **ràng buộc tối thiểu bổ sung** cần A/B/C chấp thuận |
| Hold thành công nhưng Booking lưu lỗi | Ghi **500 + orphan hold**/reset rõ ràng, không nói có compensation/distributed transaction (§5.2, §9) |
| Các trường chi tiết chưa có trong v2 | Chốt làm **đề xuất nội bộ cần ký G2**: giá VND/giới hạn 2 số thập phân, seed ID 1–6, field lạ bỏ qua, thứ tự lỗi, không pagination và 15 phút token; không tự xem đây là chỉ đạo mentor (§1, §3, §7) |

**Nguyên tắc cuối:** Tài liệu này giải quyết các mâu thuẫn về interface và giữ phạm vi MVP; **việc ký G2 và việc API chạy đúng vẫn cần xác nhận, triển khai và kiểm thử thực tế**. Không thể bảo đảm “không bao giờ phải sửa” trước khi có code và kiểm thử tích hợp.
