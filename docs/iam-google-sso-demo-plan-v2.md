# STAYHUB — KẾ HOẠCH DEMO TỐI GIẢN: GOOGLE SSO + IAM + MULTI-TENANT + BOOKING

> **Phiên bản:** 2.0 — cập nhật ngày 02/11/2026  
> **Thay thế:** `iam-google-sso-demo-plan.md` (bản ngày 02/11/2026)  
> **Nguồn đối chiếu:** `team-plan.md` và `StayHub_Execution_Roadmap_ABCD_3Days.md`  
> **Thời lượng mục tiêu:** 3 ngày làm việc, 4 người A/B/C/D.  
> **Trạng thái:** Đề xuất kế hoạch mới để cả nhóm chốt trước khi triển khai; chưa phải bằng chứng dự án đã hoàn thành.

## 0. Quyết định và mục tiêu của bản cập nhật

**Mục tiêu:** giảm thời gian viết code và tích hợp so với plan 4 microservices nghiệp vụ cũ, nhưng vẫn có **bằng chứng triển khai thực tế cho ít nhất 8/9 chủ đề mentor giao (≈ 88,9%)**, nếu các tiêu chí nghiệm thu bên dưới đều PASS.

**Thay đổi đã được xác nhận từ yêu cầu hiện tại:** sử dụng **đăng nhập Google (Google SSO) thay cho V-ID** để demo. Không còn nhu cầu sử dụng Keycloak. Phương án được giữ là `iam-service` đóng vai trò **OAuth2/OIDC client của Google**, sau khi xác thực sẽ cấp JWT nội bộ cho các API của StayHub. **Đây không phải tự xây dựng một OAuth2 Authorization Server đầy đủ** và cũng không giả định Google SSO tương đương tích hợp V-ID ở cấp giao thức/nền tảng.

**Bổ sung bắt buộc so với bản v1:**

1. **Unit Test** cho IAM, Catalog, Booking; có test bằng lệnh Gradle và bằng chứng kết quả, không chỉ test thủ công.
2. **PKCE S256 thật ở chặng IAM ↔ Google** (Authorization Code Flow), có kiểm tra URL authorize chứa `code_challenge`/`code_challenge_method=S256` và việc trao đổi code hoàn tất thành công. Với OAuth2 client dạng web server có client secret, phải **kiểm tra/cấu hình tường minh** PKCE theo phiên bản Spring Security thực tế; không mặc định thư viện tự bật.
3. **Minh chứng SSO tối thiểu trên 2 trang HTML ở 2 origin/port khác nhau**, cùng dùng `iam-service`/phiên Google trong **cùng browser profile**; đăng nhập trang 1 rồi vào trang 2 không phải nhập lại mật khẩu. Không xây frontend booking đầy đủ.
4. **Phân công lại thành viên D**: chủ trì 2 trang HTML demo, bộ request kiểm thử tích hợp, kịch bản trình diễn, tổng hợp diagram và evidence. D **không** còn phụ trách `notification-service`.

**Flow phòng và booking:** user chọn đủ tiêu chí (ngày/giờ nhận-trả, số khách, loại phòng nếu có) rồi bấm **Tìm kiếm** mới thấy phòng phù hợp. `room.operational_status` là `ACTIVE`/`MAINTENANCE`; response tìm kiếm có `status=AVAILABLE` nếu không có booking overlap, nếu có thì `BOOKED`. Booking `PENDING_PAYMENT` giữ khoảng giờ ngay; khi thanh toán thành công thành `CONFIRMED`; khi huỷ hoặc hết hạn thanh toán không còn giữ phòng và phòng hiện lại cho người khác tìm.

**Mức thanh toán:** MVP mô phỏng kết quả thanh toán, không tích hợp cổng thanh toán thật. Không notification, không idempotency-key, không PKCE public-client riêng ở frontend, không xây website hoàn chỉnh.

> **Phạm vi số liệu 8/9:** tính theo **9 chủ đề trong `team-plan.md`**, không phải 9 thư viện độc lập. **Chủ đề Mobile Push Notification bị bỏ**. IAM/SSO/PKCE chỉ tính đạt khi chứng minh được **cả ba nội dung** phù hợp với cách triển khai Google ở plan này. Nếu mentor bắt buộc phải thấy push thì phải xin xác nhận điều chỉnh phạm vi, không tuyên bố đã đạt đủ 9/9.

---

## 1. Phạm vi chức năng và những thứ KHÔNG làm

### 1.1 Phải làm (MVP)

- **2 tenant** cùng dùng 1 hệ thống: dữ liệu phòng/booking của tenant này không bị tenant khác đọc hoặc sửa.
- Role `USER` (tìm phòng theo tiêu chí/thời gian, đặt và huỷ booking của mình) và `ADMIN` (quản lý phòng, xem số lượng phòng, booking trong tenant và điều phối/gán phòng cho booking).
- Google login qua `iam-service`; tài khoản phải được **provision trước** trong bảng `users`, không có tự đăng ký/gán tenant mặc định.
- IAM ký JWT nội bộ RS256 có `sub`, `email`, `tenant_id`, `role` (kèm `iss`, `iat`, `exp` và thông tin kiểm tra chữ ký); services verify bằng JWKS.
- `room.operational_status` là tình trạng vận hành `ACTIVE`/`MAINTENANCE`; kết quả tìm kiếm trả `status` `AVAILABLE`/`BOOKED` theo `checkIn`/`checkOut`.
- `USER` chỉ được đặt phòng khả dụng trong đúng khoảng giờ; booking `PENDING_PAYMENT` giữ phòng để người khác không thấy/đặt trùng. Hai request cùng phòng và thời gian chỉ một request thành công.
- Thanh toán mô phỏng chuyển `PENDING_PAYMENT → CONFIRMED`. Huỷ chuyển sang `CANCELLED`, giải phóng khoảng giờ; hết hạn thanh toán chuyển `EXPIRED` và cũng giải phóng.
- `USER` chỉ xem/huỷ booking của mình. Chỉ `ADMIN` xem thống kê tổng phòng, danh sách điều phối và gán/đổi phòng cho booking trong tenant.
- Flyway cho các DB nghiệp vụ, REST API, chuẩn lỗi, unit tests, sequence diagrams và demo E2E.
- 2 HTML demo rất nhỏ (2 origin) để chứng minh Google SSO/PKCE và các thao tác chính.

### 1.2 Không làm / ngoài scope 3 ngày

- `notification-service`, FCM/APNs, push token/inbox.
- Keycloak/V-ID thực, phát triển OAuth2 Authorization Server riêng, tích hợp hệ thống tài khoản production.
- Cổng thanh toán thật, xử lý refund tự động, notification, idempotency-key.
- `room_inventory` theo ngày và định giá theo ngày; tuy vậy booking vẫn có `check_in`/`check_out` timestamp để kiểm tra phòng trống theo thời gian.
- Kubernetes, Kafka/RabbitMQ, service discovery, distributed transaction, hệ thống UI hoàn chỉnh, mua vé.
- Refresh token nội bộ, token revocation, JWT key rotation tự động; không tuyên bố phù hợp production.

**Không tự lấy lại đầu việc từ roadmap D cũ** nếu đã bị bỏ trong mục này. Mọi thay đổi scope phải có sự đồng ý của cả nhóm.

---

## 2. Kiến trúc, thành phần, công nghệ

```text
HTML App 1 :3000 ─┐
                  ├─ login ─> iam-service :8084 ── OAuth2 Code + PKCE S256 ──> Google
HTML App 2 :3001 ─┘                    │
          │                            └─ JWT RS256 / JWKS
          └── Bearer JWT ──> api-gateway :8080
                                 ├── /api/rooms/search/** ──> catalog-service :8081 ─┐
                                 ├── /api/admin/rooms/** ──> catalog-service :8081 ──┤── 1 database Postgres chung "hotelbooking"
                                 └── /api/bookings/** ──> booking-service :8082 ─────┘

api-gateway, catalog-service, booking-service xác minh JWT từ JWKS của iam-service.
iam-service, catalog-service, booking-service dùng CHUNG 1 database "hotelbooking" (1 schema `public`),
có FK thật giữa bảng `tenant`/`users` (IAM) ↔ `room` (Catalog) ↔ `booking` (Booking). Mỗi service vẫn chỉ tự
quản lý migration cho bảng của mình (ranh giới ownership giữ nguyên), chỉ khác là không tách DB vật lý.
```

| Thành phần | Owner | Mục đích | Chú ý |
|---|---|---|---|
| Root Gradle + Compose | A | Build/khởi động dự án | Monorepo, Java 21; kiểm tra repo hiện tại trước khi sửa |
| `iam-service` | A | Google login, PKCE, allowlist user, JWT, JWKS | Không dùng Keycloak |
| `common-security` | A | Verify JWT, role, `TenantContext` | Module thư viện, không chạy như service |
| `api-gateway` :8080 | A | Forward API có Bearer token | Không route `/internal/**` |
| `catalog-service` :8081 | B | Tìm phòng theo lựa chọn/thời gian, quản lý phòng và inventory Admin | DB chung, dữ liệu theo tenant |
| `booking-service` :8082 | C | Tạo booking giữ giờ, thanh toán mô phỏng, xem/huỷ booking | DB chung, sở hữu booking và chống overlap |
| HTML App 1 :3000 và App 2 :3001 | D | Chứng minh SSO, gọi API/role | HTML/JS tối giản, không framework UI mới |
| `docs/demo.http`, test manual, diagrams và README demo | D chủ trì | Bằng chứng trình diễn, tích hợp | A/B/C cung cấp response, test report và sơ đồ của mình |

**Công nghệ:** Java 21, Spring Boot + Spring Security (OAuth2 Client / Resource Server), Gradle multi-project, PostgreSQL, Flyway, REST (`RestClient` giữa service), JUnit 5/Mockito (hoặc test support tương thích), Mermaid, Google OAuth2/OIDC. Sử dụng `ProblemDetail` hoặc một JSON lỗi thống nhất trong contract.

**Version gate trước khi code:** A xác minh phiên bản Spring Boot / Spring Security / Spring Cloud (nếu có dùng) / Gradle wrapper tương thích. **Không tự nâng cấp toàn repo chỉ vì thấy có phiên bản mới hơn**; ghi phiên bản khóa trong `docs/decisions.md`, chạy `./gradlew build` được rồi mới yêu cầu các service cùng tuân thủ.

**Cơ sở dữ liệu:** một PostgreSQL server local, **1 database chung** `hotelbooking`, 1 schema `public`. Có FK thật giữa tenant/users/room/booking và FK ghép để chặn room/user sai tenant. Booking giữ `check_in`/`check_out`; constraint DB chặn hai booking active cùng phòng bị overlap (xem `docs/erd-v2.md`).

Mỗi service **vẫn chỉ sở hữu và chạy migration cho bảng của mình** (A: `tenant`, `users`; B: `room`; C: `booking`). Để 3 bộ Flyway độc lập không đụng version, mỗi service dùng riêng `spring.flyway.table` (`flyway_schema_history_iam`, `flyway_schema_history_catalog`, `flyway_schema_history_booking`). Tên bảng vật lý `users` đại diện cho user; tránh dùng tên `user` vì đó là từ khóa SQL.

**Phụ thuộc thứ tự migration:** `iam-service` tạo `tenant`/`users` trước, `catalog-service` tạo `room` tiếp theo, rồi `booking-service` tạo `booking` vì có FK tới các bảng trên.

---

## 3. Google SSO, IAM và PKCE — phạm vi minh chứng CHÍNH XÁC

### 3.1 Luồng xác thực

1. Người dùng mở HTML App 1/2 và chọn **Login with Google**.
2. Browser chuyển đến `iam-service` ở `/auth/start?app=app1` hoặc `app2` (mã app phải nằm trong allowlist). IAM có thể dùng phiên đăng nhập hiện có hoặc bắt đầu OAuth2 login Google.
3. Nếu cần OAuth2, Spring Security chuyển tới Google theo **Authorization Code Flow + PKCE S256**. Google gọi callback **về IAM**, ví dụ `http://localhost:8084/login/oauth2/code/google` (đăng ký chính xác URI này trong Google Cloud).
4. IAM kiểm tra danh tính Google đã xác thực, `email_verified` và tra `users` **theo email đã chuẩn hóa**. Email không được provision => **403**; tuyệt đối không tự gán tenant.
5. Có user hợp lệ => IAM tạo JWT nội bộ RS256: `sub` ổn định cho user nội bộ, `email`, `tenant_id`, `role`, `iss`, `iat`, `exp`. `google_sub` có thể lưu/đối chiếu với user được provision theo chính sách nhóm đã chốt; không cho Google account khác chiếm mapping đã liên kết.
6. IAM chỉ redirect về **địa chỉ callback trong allowlist** (`http://localhost:3000/callback` hoặc `http://localhost:3001/callback`) kèm `#token=...`. Frontend đọc rồi **xóa fragment khỏi URL ngay**, giữ token **trong memory**; không ghi log, localStorage, query string hoặc Git.
7. App 1/2 dùng Bearer JWT nội bộ gọi Gateway. Gateway và service verify chữ ký, issuer/expiry, role, tenant.

**Giới hạn demo có chủ ý:** token trong URL fragment vẫn tiềm ẩn rủi ro nếu client bị XSS/extension độc hại. Cơ chế này **không dùng cho production**; không có refresh/revocation. Không dùng access token Google để gọi API nội bộ thay JWT StayHub.

### 3.2 Chứng minh SSO với 2 trang HTML

- **App 1** và **App 2** là hai origin khác nhau (`localhost:3000`, `localhost:3001`), cùng chuyển login về `iam-service`.
- Demo trong **một browser profile thường, không dùng incognito giữa hai app**: login Google tại App 1 → lấy JWT → mở App 2 → login lại qua IAM/Google nhưng **không phải nhập mật khẩu lần thứ hai** nhờ phiên đăng nhập có sẵn. IAM phải có hành vi session/login phù hợp; không được hard-code token để giả lập SSO.
- Minh chứng được yêu cầu là **SSO ở trải nghiệm đăng nhập Google/IAM cho hai ứng dụng demo**. Không mô tả IAM là Google, V-ID hay một Identity Provider chuẩn OIDC đầy đủ.
- Nếu Google hiện màn hình chọn tài khoản/consent tùy trạng thái phiên, ghi rõ; không bắt buộc mọi màn hình đều biến mất, nhưng không được nhập lại mật khẩu trong điều kiện session còn hợp lệ. `prompt=login` hoặc ép Google reauthenticate sẽ làm hỏng demo SSO.

### 3.3 Chứng minh PKCE S256, không chỉ tuyên bố

- **PKCE nằm trên chặng IAM OAuth2 Client ↔ Google**; không phải frontend public client tự trao code. Để mentor hiểu đúng, sơ đồ phải thể hiện vị trí `code_verifier` nằm ở IAM (server-side).
- Với OAuth2 Client server-side dùng Google `client-secret`, A phải kiểm tra cấu hình PKCE ở phiên bản Spring Security hiện tại; khi cần, dùng client setting `requireProofKey` hoặc `DefaultOAuth2AuthorizationRequestResolver` + `withPkce()` theo tài liệu tương ứng.
- Mở DevTools > Network, quan sát **authorization request tới Google** chứa `code_challenge` và `code_challenge_method=S256`. Đăng nhập hoàn tất chứng minh server đã trao authorization code theo flow được cấu hình. **Không log/in `code_verifier`, authorization code, secret hoặc JWT nguyên văn** vào chứng cứ.
- **Không được đánh dấu PASS** nếu chỉ thấy Google login hoạt động mà request không chứa PKCE, hoặc nếu PKCE chỉ được nhắc trong README mà không chạy thực tế.

### 3.4 Tài khoản và dữ liệu mẫu

- Tối thiểu **2 tenant**; mỗi tenant có 1 `ADMIN` và 1 `USER` dùng tài khoản Google thử nghiệm thật.
- Để chứng minh **hai người khác nhau** cùng tenant tranh một phòng, ưu tiên chuẩn bị thêm **USER thứ hai của tenant 1** (tổng tối thiểu 5 Google accounts). Nếu không chuẩn bị được tài khoản thứ 5, có thể chạy **2 request đồng thời bằng cùng một JWT** để chứng minh atomic update, nhưng phải nói rõ đó là hai request, **không được gọi là hai người dùng độc lập**.
- Khai báo seed `tenant` qua migration. Dữ liệu `users` demo có thể seed bằng script/SQL local ngoài Git với email thật (hoặc qua biến môi trường được quản lý đúng); **không commit email cá nhân, access token, client secret hoặc private key**. `docs/demo-data.md` chỉ lưu alias và ID test không nhạy cảm.
- Tài khoản Google chưa provision => 403; tenant/role lấy từ `users`, không lấy từ tham số trình duyệt.

---

## 4. Mô hình dữ liệu và API contract tối thiểu

### 4.1 Database schema

1 database chung `hotelbooking`, 1 schema `public`. Cột "Owner" chỉ nói ai giữ quyền sửa migration của bảng đó (ranh giới ownership §6), không phải database riêng.

| Bảng | Owner | Các trường bắt buộc | FK thật |
|---|---|---|---|
| `tenant` | A | `id`, `name` | — |
| `users` | A | `id`, `email` unique/lowercase, `tenant_id`, `role (USER/ADMIN)`, `google_sub`; unique `(tenant_id, email)` | `tenant_id → tenant.id` |
| `room` | B | `id`, `tenant_id`, `name`, `room_type`, `capacity`, `price`, `operational_status VARCHAR(20) CHECK IN ('ACTIVE','MAINTENANCE')` | `tenant_id → tenant.id`; unique `(tenant_id, id)` |
| `booking` | C | `id`, `tenant_id`, `room_id`, `email`, `check_in`, `check_out`, `status (PENDING_PAYMENT/CONFIRMED/CANCELLED/EXPIRED)`, `assigned_by`, `created_at` | `tenant_id → tenant.id`; FK ghép `(tenant_id, room_id) → room(tenant_id, id)`; FK ghép `(tenant_id, email) → users(tenant_id, email)`; thời gian hợp lệ và không overlap khi active |

- Flyway tạo schema, constraints/index, FK thật như trên; có seed data có thể tái hiện.
- Mỗi service dùng riêng `spring.flyway.table` (`flyway_schema_history_iam`/`_catalog`/`_booking`) vì 3 bộ migration độc lập cùng ghi vào 1 database — tránh đụng số version giữa các service.
- **Thứ tự migrate bắt buộc vì có FK thật:** A (`tenant`, `users`) → B (`room`) → C (`booking`). Chạy B/C trước A trên DB sạch sẽ fail ngay do thiếu bảng tham chiếu — đây là hành vi đúng, không phải bug.
- Giá phải lớn hơn 0, `room_id`/`tenant_id` không null. Nên dùng giá dạng decimal thay cho float.
- Không sửa migration đã chạy/merge; thay đổi thêm migration version mới.

### 4.2 REST API — hợp đồng dùng chung trước khi code

| Endpoint demo (qua Gateway) | Service thật | Quyền | Kết quả chính |
|---|---|---|---|
| `GET /api/rooms/search?checkIn=...&checkOut=...&guestCount=...&roomType=...` | B | `USER`, `ADMIN` | 200, chỉ phòng trống đúng tiêu chí/khung giờ; chỉ gọi sau khi bấm Tìm kiếm |
| `POST /api/rooms` | `POST /rooms` — B | `ADMIN` | 201; tenant được gán từ JWT, không nhận từ body |
| `PUT /api/rooms/{id}/price` | `PUT /rooms/{id}/price` — B | `ADMIN` | 200; tenant khác => 404; role sai => 403 |
| `POST /api/bookings` `{ "roomId", "checkIn", "checkOut" }` | `POST /bookings` — C | `USER` | 201 `PENDING_PAYMENT`; trùng giờ => 409; khác tenant/không có => 404 |
| `POST /api/bookings/{id}/payment` | C | `USER` là chủ booking | 200; mô phỏng thanh toán thành công, `PENDING_PAYMENT→CONFIRMED` |
| `POST /api/bookings/{id}/cancel` | C | `USER` là chủ booking hoặc `ADMIN` cùng tenant | 200 `CANCELLED`; giải phóng khoảng giờ |
| `GET /api/bookings` | `GET /bookings` — C | `USER`, `ADMIN` | USER chỉ xem của mình, ADMIN xem trong tenant |
| `GET /api/bookings/{id}` | `GET /bookings/{id}` — C | `USER`, `ADMIN` | 200 đúng quyền; khác tenant/owner => 404 |
| `GET /api/admin/rooms/summary?checkIn=...&checkOut=...` | B | **ADMIN** | Tổng phòng và số AVAILABLE/BOOKED trong khoảng giờ; USER => 403 |
| `GET /api/admin/rooms?checkIn=...&checkOut=...` | B | **ADMIN** | Danh sách quản trị gồm cả phòng bận/trống và tình trạng vận hành; USER => 403 |
| `PUT /api/admin/bookings/{id}/room` | C → B | **ADMIN** | Gán/đổi phòng khi tenant/capacity/time còn hợp lệ; overlap => 409; USER => 403 |
| `GET /.well-known/jwks.json` | A/IAM | Public | Public RSA JWK, không chứa private key |
| `GET /auth/start?app=app1\|app2` | A/IAM | Public | Bắt đầu/tiếp tục login Google; app trong allowlist |

Form tìm phòng yêu cầu `checkIn`, `checkOut`, `guestCount` và các lựa chọn đang hiển thị (ví dụ `roomType`); danh sách không tải trước. Chỉ sau khi người dùng chọn đủ trường bắt buộc và bấm **Tìm kiếm**, UI gọi `/api/rooms/search`. Response trả trường `status=AVAILABLE` theo khoảng thời gian đó. Phòng có booking `PENDING_PAYMENT` hoặc `CONFIRMED` overlap bị loại khỏi kết quả. Backend vẫn phải áp dụng constraint lúc tạo booking vì kết quả tìm kiếm có thể cũ.

Chỉ Admin được gọi summary để xem số lượng phòng (tổng, bảo trì, khả dụng/đã giữ theo khoảng giờ) và các API điều phối/gán phòng. User thường chỉ nhận danh sách phòng phù hợp `AVAILABLE`; Admin view có thể thấy cả phòng `BOOKED` trong cùng khung giờ. Việc gán lại phải kiểm tra cùng tenant, tình trạng vận hành, sức chứa và khoảng thời gian; không cho hai booking active dùng cùng phòng trong thời gian overlap.

**Gateway rewrite:** `/api/rooms/**` chuyển thành `/rooms/**`; `/api/bookings/**` thành `/bookings/**`. Chốt chính xác cách rewrite trước G2. Login/callback/JWKS có thể gọi **trực tiếp IAM :8084** để tránh thêm rủi ro route OAuth state/cookie; **không bắt buộc** đưa OAuth callback qua gateway.

**Mã lỗi thống nhất:** `400` request sai (bao gồm `checkOut <= checkIn`), `401` thiếu/hỏng/hết hạn JWT, `403` sai role hoặc Google email không được provision, `404` không tồn tại hoặc thuộc tenant/owner khác, `409` booking overlap/phòng đã được giữ hoặc trạng thái không cho phép chuyển.

**Điểm phải code đúng khi tạo booking/giữ khoảng giờ:**

```sql
-- Booking PENDING_PAYMENT hoặc CONFIRMED giữ khoảng thời gian.
-- PostgreSQL EXCLUDE constraint kiểm tra overlap một cách nguyên tử.
INSERT INTO booking (tenant_id, room_id, email, check_in, check_out, status)
VALUES (:tenantId, :roomId, :email, :checkIn, :checkOut, 'PENDING_PAYMENT');
```

- Nếu constraint báo overlap, trả `409`; không tạo booking trùng giờ. Catalog search loại room không `ACTIVE`; Booking kiểm tra room, tenant và capacity rồi insert booking active.
- Booking/Catalog lấy `tenant_id` và email từ JWT đã verify, không tin body.
- Exclusion constraint kiểm tra overlap đồng thời; không dựa vào search vừa trả phòng trống vì kết quả có thể cũ.
- Chỉ Admin xem room summary và gán/đổi phòng. Endpoint phải kiểm tra role và tenant trong JWT.
- Catalog search đọc read-only các khoảng booking active từ cùng database; Booking là service duy nhất được ghi bảng booking.
- Trạng thái `PENDING_PAYMENT` phải có thời hạn cấu hình; job đổi booking quá hạn thành `EXPIRED` để giải phóng thời gian. Cancel chuyển `PENDING_PAYMENT`/`CONFIRMED` sang `CANCELLED`; không xoá lịch sử booking.
- **Giới hạn biết trước:** nếu thanh toán thành công bên ngoài nhưng cập nhật booking thất bại, trạng thái cần reconciliation; demo chỉ mô phỏng thanh toán, không tuyên bố xử lý giao dịch tiền thật.

---

## 5. Định nghĩa Unit Test bắt buộc (không cần tối ưu coverage)

| Owner | Unit tests tối thiểu có ý nghĩa | Bằng chứng PASS |
|---|---|---|
| **A — IAM/security** | User chưa provision bị 403; token thiếu `tenant_id` bị reject; map role đúng; token hợp lệ/hết hạn | Test chạy local **không cần login Google thật**; verify JWT/security bằng fixture/mocks |
| **B — Catalog** | Search chỉ sau nút Tìm kiếm; lọc check-in/out, guestCount, roomType, tenant; loại phòng bảo trì; chỉ Admin xem tổng và điều phối | USER không xem inventory tổng/không gọi Admin API; status theo khung giờ đúng |
| **C — Booking** | Overlap trả 409; tạo `PENDING_PAYMENT` giữ giờ; payment mô phỏng thành công → `CONFIRMED`; cancel/expiry giải phóng giờ; USER chỉ xem/huỷ booking mình | Test cùng giờ/khác giờ, thanh toán, huỷ, expiry và quyền; booking huỷ không chặn search |
| **D — Demo/QA** | D **không thay A/B/C viết test cho service của họ**; D viết bộ kiểm thử HTTP/integration xuyên service và hướng dẫn chạy test | `docs/demo.http`, `docs/test-evidence.md`, script và response PASS/FAIL tái hiện |

**Phân biệt:** Unit Test chạy với JUnit/Mockito và không phụ thuộc Google/DB thật; test **concurrency** và **cross-tenant** trong luồng demo nên dùng integration/manual check với PostgreSQL thật. Không chỉ gọi `curl` rồi ghi là Unit Test.

Lệnh bằng chứng tối thiểu: `./gradlew clean build` (Windows `gradlew.bat clean build`) và test của từng service. Giữ test có assertion; không skip/xóa test để làm xanh build.

---

## 6. Phân công 4 thành viên và ranh giới sửa file

### A — IAM / Platform / Security (phần chủ trì kỹ thuật)

- Sở hữu `iam-service` (bảng `tenant`, `users`), `common-security`, `api-gateway`, cấu hình root Gradle và Compose (bao gồm database chung `hotelbooking`).
- Cấu hình Google Cloud OAuth Client, redirect URI IAM, PKCE S256, Google account allowlist, JWT RS256 và JWKS.
- Cung cấp cho B/C/D: issuer/JWKS, JWT claims/roles/tenant IDs, API login/callback, các port và ví dụ lỗi auth.
- Test IAM/JWT/401/403, diagram IAM login + PKCE + SSO.
- **Không sửa HTML và demo HTTP của D** nếu chưa báo trước.

### B — Catalog / Flyway / Multi-tenancy

- Sở hữu `catalog-service`, migration/seed bảng `room`, tìm kiếm theo tiêu chí/thời gian, quản lý phòng và Admin inventory summary.
- Trả trường `status` khả dụng theo khung giờ; filter tenant, tình trạng vận hành, sức chứa và loại phòng.
- Chỉ Admin xem số lượng tổng và điều phối/gán phòng; mọi lần gán phải kiểm tra overlap.
- Unit/integration checks cho search, tenant filter, Admin permission và điều phối phòng.

### C — Booking / REST / Unit Test

- Sở hữu `booking-service`, migration bảng `booking`, POST/GET/cancel booking, thanh toán mô phỏng và expiry.
- Tạo `PENDING_PAYMENT` để giữ khoảng giờ; payment thành công thành `CONFIRMED`; cancel/expiry giải phóng giờ. Không tích hợp cổng thanh toán thật.
- Gán/đổi room theo thao tác Admin, ghi `assigned_by` và kiểm tra exclusion constraint.
- Cung cấp cho D request/response/error booking, quyền USER/ADMIN, script test cạnh tranh.
- Unit tests/validation và diagram booking với nhánh 404/409.

### D — Frontend demo / Integration QA / Documentation (phân công MỚI)

- Sở hữu **2 trang HTML/JS tối giản** (khác port) để gọi login Google qua IAM, nhận JWT nội bộ vào memory và demo SSO.
- App 1 hiển thị form tìm phòng; chỉ hiện kết quả sau khi chọn đủ tiêu chí và bấm Tìm kiếm. USER đặt/huỷ booking của mình. Với `ADMIN`, hiện số lượng phòng, danh sách booking và công cụ gán/đổi phòng. **Chỉ làm UI đủ để trình diễn endpoint, không làm website đẹp/full-feature.**
- Sở hữu `docs/demo.http`, `docs/demo-data.md` phần alias (A/B/C xác nhận), `docs/demo-script.md`, `docs/test-evidence.md`, phần README chạy demo, tổng hợp sequence diagrams do A/B/C gửi.
- Test thực tế: SSO giữa App 1–2; role USER/ADMIN; cross-tenant; tranh phòng; API lỗi; JWT thiếu/invalid; kiểm tra Flyway và kết quả test của từng service.
- **Không chỉnh** service IAM/Catalog/Booking, migration, root Gradle hoặc gateway bằng Codex trên branch D nếu chưa được owner đồng ý. Bug thuộc owner nào thì tạo issue kèm HTTP request/log đã lược bí mật.

### Quy tắc Git & AI agent

- Mỗi người làm branch riêng: `feat/a-*`, `feat/b-*`, `feat/c-*`, `feat/d-*`; tài liệu chéo thay bằng PR có owner review. `main` không push trực tiếp.
- Trước khi Codex code, xác nhận: branch hiện tại, owner file, API contract version, ranh giới edit. **Prompt một task / một đầu ra**, bắt Codex liệt kê file thay đổi, lệnh test, kết quả, rủi ro và các phần chưa làm.
- Không thay đổi API field/path, role, tenant IDs, ports, cấu hình auth hoặc migrations đã khóa nếu bên cung cấp và bên dùng chưa chấp thuận.
- Code do AI viết phải được người giao PR giải thích và kiểm tra, không merge chỉ vì Codex báo thành công.

---

## 7. Timeline 3 ngày + Gate nghiệm thu

### Ngày 1 — Chốt hợp đồng, dựng các service độc lập

| Giờ (tương đối) | A | B | C | D | Điều kiện chốt |
|---|---|---|---|---|---|
| **09:00–10:00 — G0** | Kiểm tra repo/versions/Google OAuth | Chốt room/search/admin schema | Chốt booking/payment/cancel/time contract | Ghi decisions, kịch bản demo và ports App 1/2 | Mọi người chấp thuận scope + 8/9, owner, seed, tài khoản Google test và Git rule |
| **10:00–12:00 — G1/G2** | Gradle skeleton, Compose, IAM claim/API auth | API search/room/admin JSON | API booking/payment/cancel JSON | Soạn `docs/demo-script.md`, `docs/demo.http` placeholder, HTML skeleton | `./gradlew build` trên skeleton; **contract đã chốt** và mỗi bên nhận API ký nhận |
| **13:00–15:00** | Migrate database chung (bảng `tenant`/`users`), Google client, provision, JWT/JWKS | Migrate bảng `room`, search API và seed rooms | Migrate bảng `booking` có check-in/out và exclusion constraint | HTML callback nhận token memory, form tìm kiếm tối giản | 3 bộ migration chạy đúng thứ tự A→B→C; search lọc được phòng theo khoảng giờ |
| **15:00–17:00** | Google login thật, PKCE config, common-security | Search/CRUD rooms, tenant filter, Admin summary | POST booking `PENDING_PAYMENT`, mock payment/cancel | Kết nối form tìm kiếm và kết quả vào API | Dùng được từng phần độc lập, chưa lấy stub làm bằng chứng security |
| **17:00–18:00 — G3** | JWT hợp lệ và auth tests | API B + ID seed cho C/D | POST booking mock, unit tests đầu tiên | Script login/rooms demo bản nháp, tổng hợp issues | Build xanh; **có đường lấy JWT Google** hoặc báo rõ Google Cloud blocker; từng service khởi động được |

### Ngày 2 — Tích hợp và chứng minh stack

| Giờ | A | B | C | D | Điều kiện chốt |
|---|---|---|---|---|---|
| **09:00–11:00** | JWKS/JWT ở gateway và service; PKCE S256 | Search theo thời gian + Admin inventory | Booking insert với exclusion constraint, forward JWT | 2 HTML ở 2 origin, login Google; search theo form | JWT thật đi được các service, 2 app không lộ secret |
| **11:00–12:00 — G4** | Hỗ trợ issuer/CORS/redirect | Search availability + Admin room summary/assign | Tạo booking pending, chống overlap | HTTP script test search/booking | Search → booking → pending-payment hoạt động; tenant đúng, no double-booking |
| **13:00–15:00** | Minh chứng SSO + PKCE, JWT negative | Search filters, Admin summary/assignment, cross-tenant | Payment mock, cancel, expiry; GET bookings role/owner | Hoàn thiện App 1/2, role USER/ADMIN và demo.http | Có bằng chứng SSO/PKCE/isolation và room release đúng time window |
| **15:00–17:00** | Unit tests IAM | Unit tests + race test với C | Unit tests + race test với B | Test hai requests, cross-tenant, 401/403/404/409; ghi evidence | Test chạy không phụ thuộc thao tác mơ hồ; lỗi cross-service có issue/owner |
| **17:00–18:00 — G5** | Build/security evidence | Seed/reset + test evidence | Booking evidence | Run toàn bộ luồng, nháp diagrams & README | Happy path/negative path qua gateway PASS; `./gradlew build` xanh |

### Ngày 3 — Test, đối chiếu mentor, freeze

| Giờ | A | B | C | D | Điều kiện chốt |
|---|---|---|---|---|---|
| **09:00–11:00** | Test login/PKCE/SSO, token lỗi | Test search tenant/time + Admin assignment | Test pending/payment/cancel/expiry/overlap | Chạy `docs/demo.http` trên state sạch; ghi PASS/FAIL | Không có lỗi P0 chưa phân owner |
| **11:00–12:00** | Gửi IAM sequence & test log | Gửi search/admin sequence & DB history | Gửi payment/cancel/booking sequence & test log | Gom diagrams + sơ đồ demo; hoàn thiện docs | Diagrams, endpoint, roles, JSON thực khớp code |
| **13:00–15:00 — G6** | Dẫn Google IAM/PKCE/SSO | Dẫn tìm kiếm phòng và Admin điều phối | Dẫn booking, payment mock, cancel/expiry | Chạy 2 lần E2E, xác nhận evidence | User B không thấy phòng khi A pending; sau cancel phòng hiện lại; không double-booking |
| **15:00–18:00** | Sửa P0 duy nhất | Sửa P0 duy nhất | Sửa P0 duy nhất | Freeze demo và hỏi chéo 9 chủ đề (push nói rõ ngoài scope) | Không thêm feature mới; mỗi người giải thích được code của mình và luồng chung |

**Quy tắc Gate:** PASS khi có PR/commit, lệnh chạy, response/log/test có thể tái hiện và bên nhận xác nhận. Không đánh dấu PASS chỉ vì agent báo đã code xong.

---

## 8. Handoff quan trọng — đặc biệt để D không phải chờ

| Khi nào | Bên giao → bên nhận | Cần cung cấp | Dùng để làm gì |
|---|---|---|---|
| D1 trước 12:00 | A → B/C/D | Google login start/callback, issuer, claims, ports, 2 frontend callback origins | D code login/callback, B/C security |
| D1 trước 12:00 | B → C/D | Search API, time filter, status response, Admin inventory/assign | C/D viết client và demo request |
| D1 trước 12:00 | C → D | Booking API + USER/ADMIN semantics, response/error | D làm UI nút book & tests |
| D1 cuối ngày | A → D | Hướng dẫn tạo test accounts/tokens và PKCE evidence, không kèm secret | D thử login và SSO |
| D1 cuối ngày | B → C/D | Room IDs, seed/reset DB, room có `status=AVAILABLE` | C tạo booking thật, D cố định demo data |
| D2 trước 12:00 | B → C | Room search và tenant/capacity validation | C gắn room/time vào booking |
| D2 trước 15:00 | C → D | Booking end-to-end thực, response/ID status | D cập nhật demo script |
| D2 cuối ngày | A/B/C → D | PR, test commands/reports, diagrams, actual paths | D tổng hợp minh chứng |
| D3 trước 12:00 | Tất cả → D | Test logs cuối và known limitations | D freeze bản demo |

**Khi dependency chưa đến:** D dùng mock JSON theo contract để dựng UI/script; gắn nhãn `MOCK ONLY`, không coi là E2E PASS. D báo blocker quá 30 phút kèm expected/actual + owner + thời hạn, không tự sửa service khác.

---

## 9. Kịch bản demo cuối cho mentor (D chủ trì)

| # | Trình diễn | Bằng chứng **phải nhìn thấy** | Chủ trì |
|---|---|---|---|
| 1 | Start repo/database chung, Gradle build | Gateway + IAM + B/C chạy, mỗi service có `flyway_schema_history_*` riêng dù chung 1 database | A/B/D |
| 2 | App 1 login Google | Email provision đúng role/tenant, JWT được issuer nội bộ ký | A/D |
| 3 | PKCE S256 | Authorization request Google có `code_challenge` và `S256`; code flow kết thúc login thành công | A/D |
| 4 | App 2 ở origin khác | Cùng browser profile, không nhập lại mật khẩu; dùng JWT mới/hợp lệ để gọi API | A/D |
| 5 | USER điền thời gian/số khách/loại phòng rồi bấm Tìm kiếm | Danh sách chỉ xuất hiện sau search; status khả dụng và tenant filter đúng | B/D |
| 6 | USER A tạo booking và tới bước thanh toán | Booking `PENDING_PAYMENT`; user B tìm cùng khung giờ không thấy phòng đó | C/D |
| 7 | A huỷ booking trước khi hoàn tất | Booking `CANCELLED`; user B tìm lại cùng khung giờ thấy phòng trống | C/D |
| 8 | Thanh toán mô phỏng thành công và booking được xác nhận | `PENDING_PAYMENT→CONFIRMED`; phòng tiếp tục bị ẩn trong giờ đã đặt | C/D |
| 9 | ADMIN xem tổng số phòng và điều phối phòng cho booking | ADMIN thành công; USER gọi các API này nhận 403; không gán phòng bị overlap | B/C/D |
| 10 | Hai request cùng lúc tranh một phòng trong cùng thời gian | Một booking thành công, request kia 409; không có hai booking active overlap | B/C/D |
| 11 | Token tenant 2 đọc/sửa/đặt tài nguyên tenant 1 | 404/403 theo contract, không lộ dữ liệu tenant 1 | A/B/C/D |
| 12 | Token thiếu/hỏng/hết hạn; email Google chưa provision | API 401; email chưa provision 403, không tự tạo account | A/D |
| 13 | `./gradlew clean build` và unit tests A/B/C | Test report xanh có assertions; có test mock phù hợp | A/B/C/D |
| 14 | Sequence diagrams + giải thích code | Diagrams khớp code, cả nhóm giải thích quyết định và giới hạn | A/B/C/D |

**Phải chuẩn bị state sạch:** dùng cùng một khoảng giờ test rõ ràng; reset/cancel booking giữa các lần chạy. Sau khi test A pending, chạy search bằng B; sau cancel, chạy lại cùng search và xác nhận phòng xuất hiện. Tài khoản tenant 2 nên test trong browser profile tách biệt; không gửi token thật vào Git/evidence.

**Quy ước tài liệu cho D:**

```text
docs/
  decisions.md                  # A điều phối, cả nhóm xác nhận
  api-contract.md               # A/B/C cung cấp hợp đồng, D đối chiếu script
  demo-data.md                  # alias tenant/user/room, không có PII/token thật
  demo.http                     # các request có biến, không hard-code JWT
  demo-script.md                # checklist từng bước cho mentor
  test-evidence.md              # PASS/FAIL + lệnh/HTTP/log đã ẩn thông tin nhạy cảm
  sequence-iam.md               # A
  sequence-tenant.md            # B
  sequence-booking.md           # C
  known-limitations.md          # cả nhóm cung cấp, D tổng hợp
frontend-demo/
  app1/                         # D, host local :3000
  app2/                         # D, host local :3001
```

Cấu trúc trên là **gợi ý**, phải kiểm tra thư mục thật trong repo trước khi Codex tạo file. Nếu repo đã có quy ước khác thì dùng đường dẫn thực, không tự di chuyển module của A/B/C.

---

## 10. Ma trận 9 chủ đề mentor: mục tiêu 8/9

| Chủ đề gốc mentor | Có trong plan v2? | Ai trình bày / minh chứng | Khi nào tính đạt |
|---|---|---|---|
| 1. Java 21 / Spring Boot / Gradle | **Có** | A; build multi-project + DI/Bean | Các service compile/chạy và người làm giải thích được |
| 2. Flyway | **Có** | B dẫn, A/C chứng minh migration của mình | 3 bộ migration (1 database chung) có history riêng, reset/recreate được, FK thật giữa bảng |
| 3. RESTful API | **Có** | C/B; method, 200/201/400/401/403/404/409 | API thực chạy đúng contract |
| 4. Unit Test | **Có (bổ sung bắt buộc)** | A/B/C; D tổng hợp report | Test assertions chạy, không skip vô lý |
| 5. IAM / SSO / PKCE | **Có (phải chứng minh)** | A + D; Google login, app1/app2 SSO, PKCE S256 | Cả 3 đều có evidence; không nhầm với V-ID thật |
| 6. Multi-tenancy | **Có** | B; token tenant1/2, room và booking isolation | Không lộ dữ liệu dù sửa ID trong URL/body |
| 7. Mobile Push Notification | **Không** | D giải thích là ngoài scope | **0 điểm chủ đề này**, không giả là đã triển khai |
| 8. Nghiệp vụ khách sạn | **Có (đơn giản hóa)** | C/B; đặt phòng, status, tranh phòng | Hai request không thể cùng chiếm một phòng |
| 9. Sequence Diagram | **Có** | A/B/C xây, D gom | Mermaid mô tả đúng luồng thực và nhánh lỗi |

**Kết quả dự kiến:** **8/9 = 88,9%** nếu toàn bộ 8 dòng “Có” đều PASS. Đây là **mức bao phủ chủ đề**, không có nghĩa đã đạt 88,9% yêu cầu chi tiết của mentor; đánh giá cuối cùng vẫn phụ thuộc tiêu chí mentor. Nếu mentor yêu cầu riêng mobile push hoặc PKCE **ở browser public client**, mức bao phủ và scope phải được xác nhận lại trước kickoff.

---

## 11. Thứ tự ưu tiên và nguyên tắc cắt giảm

**Không cắt** trong 3 ngày: Google login thật, PKCE S256 minh chứng, SSO 2 trang, tenant isolation, Flyway, unit test, sequence diagram, time-based search, giữ chỗ khi `PENDING_PAYMENT`, cancel/expiry giải phóng, Admin-only inventory/assignment.

**Có thể đơn giản hóa khi trễ:**

1. UI đẹp, responsive, animation, nhiều màn hình; giữ hai HTML rất nhỏ và đầy đủ thao tác demo.
2. Các tính năng ngoài MVP; giữ `POST/GET rooms`, `PUT price`, `POST/GET bookings`, Google login, test.
3. Các báo cáo/trang dashboard, test automation UI nâng cao; giữ `docs/demo.http` và test Gradle bắt buộc.
4. Nếu lỗi Gateway không thể giải quyết kịp, chỉ dùng service trực tiếp **khi cả nhóm chấp thuận**, vẫn verify JWT ở B/C, và ghi rõ Gateway đã bị cắt khỏi demo; khi đó chủ đề REST/auth vẫn có nhưng phải báo thay đổi kiến trúc.

Không mở rộng thanh toán mô phỏng thành cổng thanh toán thật; không thêm refund tự động, push, idempotency, day-based inventory, external auth service, UI framework hoặc tính năng AI. **Không nâng cấp dependency giữa dự án khi không cần**.

**Blocker Google Cloud:** cần Client ID/Secret và cấu hình OAuth consent/test users, các email Google thật được provision, đúng redirect URI. Nếu chưa có, A mock IAM cho unit tests và D dựng UI theo contract; nhưng **G6 không được ghi PASS ở Google SSO/PKCE khi chưa login Google thật**.

---

## 12. Definition of Done (DoD) cho từng người

- **A PASS:** Build/Compose chạy; IAM Google login + pre-provision 403; PKCE S256 thực; SSO session cho 2 frontend; JWT RS256 + JWKS/claims; A/B/C verify JWT; Gateway không route internal; IAM/security unit tests; diagram.
- **B PASS:** Flyway Catalog; search chỉ sau submit với thời gian/tiêu chí; response status đúng theo interval; Admin-only room summary/assign; tenant isolation; diagram.
- **C PASS:** Flyway Booking; POST tạo `PENDING_PAYMENT`; payment mock xác nhận; cancel/expiry giải phóng giờ; overlap trả 409; GET đúng role/owner/tenant; Admin reassignment hợp lệ; unit tests; diagram.
- **D PASS:** 2 HTML khác origin dùng Google login thật và demo các thao tác; `docs/demo.http` đầy đủ các case 401/403/404/409/201; `demo-script`, `test-evidence`, diagrams và README nhất quán; demo từ state sạch chạy được 2 lần; không sửa file owner khác để né bug.
- **Cả nhóm PASS:** Từng người giải thích được code/PR mình, chủ đề mình và ít nhất hai luồng kỹ thuật khác; commit/PR có người review, test/build xanh, không secret/PII trong repo.

## 13. Việc cả nhóm cần CHỐT trước khi thay thế hoàn toàn kế hoạch cũ

- [ ] Công nhận **kế hoạch này thay** `iam-google-sso-demo-plan.md` v1 về phạm vi, **và thay các đầu việc Notification/Keycloak/Payment trong roadmap 3 ngày cũ**; roadmap cũ chỉ tham khảo cách phối hợp/gate, không áp nguyên các task đã bỏ.
- [ ] Đồng ý mục tiêu **8/9** và xác nhận mentor chấp nhận **bỏ mobile push**. Google SSO thay V-ID là yêu cầu đã có, không cần quay về Keycloak.
- [ ] Chốt **A = IAM**, **B = Catalog**, **C = Booking**, **D = Frontend Demo + QA/Docs** cùng quyền sở hữu file/branch.
- [ ] Chốt version Gradle/Spring và port/origin/JWT contract; README có lệnh chạy từ máy mới.
- [ ] Tạo Google OAuth client **Web application**, redirect IAM chính xác, test users và email allowlist; không commit secret.
- [ ] Chốt 2 tenant, 4–5 tài khoản demo, room IDs/seed/reset, data alias; chuẩn bị ít nhất một phòng riêng cho race test.
- [ ] A/B/C/D ký nhận `docs/api-contract.md` trước khi cho Codex code tích hợp.

**Chốt cuối:** Đây là **plan 3 ngày có thể dùng để phân công ngay sau G0**, tập trung trình diễn kỹ thuật chứ không phát triển sản phẩm đặt phòng thương mại. Mục tiêu 8/9 chỉ thành hiện thực khi có bằng chứng thực tế; tuyệt đối không đánh đổi tính chính xác bằng việc “giả vờ” SSO, PKCE, multi-tenancy hoặc unit tests đã có.

### Tài liệu kỹ thuật tham khảo để implement (không thay API contract nội bộ)

- Spring Security — [Authorization Grant Support / PKCE](https://docs.spring.io/spring-security/reference/servlet/oauth2/client/authorization-grants.html)
- Spring Security — [OAuth2AuthorizationRequestCustomizers.withPkce()](https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/oauth2/client/web/OAuth2AuthorizationRequestCustomizers.html)
- Google Developers — [OAuth 2.0 for Web Server Applications](https://developers.google.com/identity/protocols/oauth2/web-server)
- Spring Boot — [System Requirements](https://docs.spring.io/spring-boot/system-requirements.html)

---

## Phụ lục A — Sequence diagrams nháp để nhóm tham chiếu

> Các sơ đồ dưới đây là **bản nháp hợp đồng**; A/B/C sẽ sửa theo code thực tế rồi gửi D tổng hợp. Không dùng sơ đồ nháp để nhận PASS nếu runtime khác.

### A1. Google login + PKCE S256 + JWT nội bộ + SSO hai app

```mermaid
sequenceDiagram
    participant Browser as Browser / App 1
    participant IAM as iam-service
    participant Google
    participant DB as hotelbooking (tenant/users)
    participant App2 as HTML App 2

    Browser->>IAM: GET /auth/start?app=app1
    IAM->>Google: authorize (code_challenge, method=S256, state)
    Google-->>Browser: Login / consent nếu cần
    Browser->>IAM: callback code + state
    IAM->>Google: exchange code + code_verifier
    Google-->>IAM: ID token đã xác thực
    IAM->>DB: Lookup email đã verify / tenant / role
    alt Email không được provision
        DB-->>IAM: Not found
        IAM-->>Browser: 403 Forbidden
    else Email hợp lệ
        DB-->>IAM: users, tenant_id, role
        IAM->>IAM: Mint JWT nội bộ RS256
        IAM-->>Browser: Redirect app1/callback, JWT trong URL fragment
    end
    Browser->>App2: Mở origin/port thứ hai, cùng browser profile
    App2->>IAM: GET /auth/start?app=app2
    Note over App2,Google: Tái sử dụng session IAM/Google - không nhập lại mật khẩu nếu session hợp lệ
    IAM-->>App2: Redirect app2/callback, JWT hợp lệ trong URL fragment
```

### A2. Tenant isolation và phân quyền phòng

```mermaid
sequenceDiagram
    participant UI as HTML App
    participant Gateway as api-gateway
    participant Catalog as catalog-service
    participant DB as hotelbooking (room)

    UI->>Gateway: GET /api/rooms/search?checkIn&checkOut&guestCount (Bearer JWT)
    Gateway->>Gateway: Verify JWT
    Gateway->>Catalog: GET /rooms/search + Bearer JWT
    Catalog->>Catalog: Verify JWT, extract tenant_id
    Catalog->>DB: SELECT ACTIVE rooms tenant + criteria, exclude overlapping active bookings
    DB-->>Catalog: Rooms trống trong khoảng giờ đó
    Catalog-->>UI: 200 results with status=AVAILABLE

    UI->>Gateway: PUT /api/rooms/{id}/price (Bearer JWT)
    Gateway->>Catalog: PUT /rooms/{id}/price + Bearer JWT
    alt USER không có quyền
        Catalog-->>UI: 403
    else ADMIN
        Catalog->>DB: UPDATE room WHERE id=? AND tenant_id=?
        alt Không có room trong tenant
            Catalog-->>UI: 404
        else Update thành công
            Catalog-->>UI: 200
        end
    end

    UI->>Gateway: GET /api/admin/rooms/summary (Bearer JWT)
    Gateway->>Catalog: Forward request + JWT
    alt USER
        Catalog-->>UI: 403 Forbidden
    else ADMIN
        Catalog->>DB: Count total/maintenance/available/booked for requested time window
        Catalog-->>UI: 200 tenant room inventory summary
    end
```

### A3. Hai yêu cầu cùng tranh một phòng trong cùng khoảng giờ

```mermaid
sequenceDiagram
    participant U1 as User / Request A
    participant U2 as User / Request B
    participant Gateway as api-gateway
    participant Booking as booking-service
    participant BookingDB as hotelbooking (booking)

    U1->>Gateway: POST /api/bookings {roomId, checkIn, checkOut}
    U2->>Gateway: POST /api/bookings {roomId, checkIn, checkOut}
    Gateway->>Booking: Forward A + Bearer JWT
    Gateway->>Booking: Forward B + Bearer JWT
    Booking->>BookingDB: INSERT A status PENDING_PAYMENT
    Booking->>BookingDB: INSERT B status PENDING_PAYMENT
    Note over Booking,BookingDB: Exclusion constraint: chỉ một booking active được giữ cùng room/time
    BookingDB-->>Booking: A: inserted / B: overlap violation
    Booking-->>Gateway: A: 201 PENDING_PAYMENT / B: 409
    Gateway-->>U1: 201 PENDING_PAYMENT
    Gateway-->>U2: 409 Conflict
```

**Lưu ý sơ đồ A3:** request nào thắng cũng được; tổng phải là 1 booking `PENDING_PAYMENT` và 1 lỗi `409`. Booking đang chờ thanh toán đã giữ phòng.

### A4. A pending/cancel và kết quả tìm kiếm của B

```mermaid
sequenceDiagram
    participant A as User A
    participant B as User B
    participant Gateway as api-gateway
    participant Catalog as catalog-service
    participant Booking as booking-service
    participant DB as hotelbooking database

    A->>Gateway: Search criteria + time window
    Gateway->>Catalog: Search rooms
    Catalog->>DB: Find room with no overlapping active booking
    DB-->>A: Room R status AVAILABLE
    A->>Gateway: POST booking R + checkIn/checkOut
    Gateway->>Booking: Create PENDING_PAYMENT
    Booking->>DB: INSERT active booking (holds time window)
    DB-->>Booking: Created
    Booking-->>A: 201 PENDING_PAYMENT / proceed to payment

    B->>Gateway: Search same criteria + time window
    Gateway->>Catalog: Search rooms
    Catalog->>DB: Exclude R because A has PENDING_PAYMENT overlap
    DB-->>B: R not in results

    A->>Gateway: POST /api/bookings/{id}/cancel
    Gateway->>Booking: Verify owner and cancel
    Booking->>DB: UPDATE status = CANCELLED
    DB-->>Booking: Updated; active time hold released
    Booking-->>A: 200 CANCELLED

    B->>Gateway: Search same criteria + time window again
    Gateway->>Catalog: Search rooms
    Catalog->>DB: R has no overlapping active booking
    DB-->>B: Room R status AVAILABLE
```

Kết quả của lần tìm kiếm đầu tiên không được cache thành quyền đặt. Khi A huỷ, search mới của B sẽ tính lại availability cho đúng khoảng giờ.
