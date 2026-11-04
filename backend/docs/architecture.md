# Kiến trúc tổng thể StayHub — Service kết nối & Authentication/Authorization chi tiết

> Tài liệu bổ sung cho `docs/sequence-iam.md` (vốn tập trung vào riêng OAuth2/PKCE/JWT). File này
> vẽ **toàn cảnh hệ thống**: service nào gọi service nào, qua cổng nào, và tách rõ hai khái niệm
> hay bị nhầm — **Authentication (AuthN — xác thực "bạn là ai")** và **Authorization (AuthZ — xác
> thực "bạn được làm gì")** — áp dụng cụ thể vào phần IAM đã code.

## 1. Bản đồ hệ thống (component diagram)

```mermaid
flowchart TB
    subgraph Browser["Browser (1 profile)"]
        App1["HTML App 1<br/>:3000"]
        App2["HTML App 2<br/>:3001"]
    end

    Google["Google OAuth2<br/>(Identity Provider)"]

    subgraph StayHub["StayHub backend"]
        IAM["iam-service :8084<br/>— OAuth2 Client của Google<br/>— JWT RS256 issuer nội bộ<br/>— JWKS publisher"]
        GW["api-gateway :8080<br/>— Spring Cloud Gateway (reactive)<br/>— Verify JWT (coarse)<br/>— CORS + routing"]
        CAT["catalog-service :8081<br/>— Hotels/RoomTypes/Inventory<br/>— Verify JWT lại (fine)<br/>— Role check per-endpoint"]
        BOOK["booking-service :8082<br/>— Booking lifecycle<br/>— Verify JWT lại (fine)<br/>— Ownership check (email)"]
    end

    DB[("PostgreSQL<br/>database hotelbooking<br/>schema public<br/>(dùng chung, 3 bộ Flyway history riêng)")]

    App1 -. "1. browser navigation<br/>/auth/start?app=app1" .-> IAM
    App2 -. "1'. browser navigation<br/>/auth/start?app=app2" .-> IAM
    IAM -- "2. Authorization Code<br/>+ PKCE S256" --> Google
    Google -- "3. callback code<br/>/login/oauth2/code/google" --> IAM
    IAM -. "4. redirect #token=jwt<br/>(URL fragment)" .-> App1
    IAM -. "4'. redirect #token=jwt" .-> App2

    App1 -- "5. fetch, Bearer JWT<br/>(CORS)" --> GW
    App2 -- "5'. fetch, Bearer JWT<br/>(CORS)" --> GW
    GW -- "/api/hotels/**, /api/room-types/**,<br/>/api/admin/**  (StripPrefix=1)" --> CAT
    GW -- "/api/bookings/**  (StripPrefix=1)" --> BOOK

    GW -. "6. GET /.well-known/jwks.json<br/>(verify signature)" .-> IAM
    CAT -. "6'. GET /.well-known/jwks.json" .-> IAM
    BOOK -. "6''. GET /.well-known/jwks.json" .-> IAM

    IAM --> DB
    CAT --> DB
    BOOK --> DB

    style IAM fill:#2b6cb0,color:#fff
    style GW fill:#276749,color:#fff
    style CAT fill:#975a16,color:#fff
    style BOOK fill:#975a16,color:#fff
    style Google fill:#718096,color:#fff
    style DB fill:#4a5568,color:#fff
```

**Những điều dễ nhầm, nói rõ ở đây:**

- **IAM không nằm sau Gateway.** App 1/App 2 gọi `iam-service` **trực tiếp** ở `:8084` (navigation
  cả trang, không phải `fetch`), vì login phải đi qua redirect của trình duyệt (cookie session,
  redirect sang Google...) — Gateway không route `/auth/**`.
  Catalog/Booking cũng **không gọi `iam-service`** để xin xác thực theo kiểu "hỏi hộ" — mỗi service
  tự tải JWKS về và **tự verify JWT cục bộ** (xem §3). IAM chỉ đóng vai trò *publisher* của public
  key, không tham gia vào từng request nghiệp vụ.
- **Gateway KHÔNG gọi IAM để xác thực từng request.** Việc tải `jwks.json` chỉ xảy ra khi cache
  public key hết hạn/lần đầu (mỗi service tự cache key theo `kid`), không phải mỗi request.
- **Booking không bao giờ gọi HTTP sang Catalog.** Hai service đọc/ghi chung 1 database — Catalog
  sở hữu `room_type`/`room_inventory.total`, Booking sở hữu `booking`/`room_inventory.sold`.
- **3 service (IAM/Catalog/Booking) dùng chung 1 database nhưng 3 bảng Flyway history riêng**
  (`flyway_schema_history_iam/_catalog/_booking`) để tránh đụng số version khi cùng ghi vào 1 DB.

---

## 2. AuthN vs AuthZ — hai câu hỏi khác nhau, hai cơ chế khác nhau

| | **Authentication (AuthN)** | **Authorization (AuthZ)** |
|---|---|---|
| Câu hỏi | "Bạn là ai?" | "Bạn — đã biết là ai — được phép làm gì?" |
| Ai chịu trách nhiệm | **Chỉ IAM** (và Google, đứng sau IAM) | **Gateway + từng service** (Catalog/Booking), mỗi nơi tự quyết |
| Xảy ra khi nào | Một lần ở `/auth/start` → `/login/oauth2/code/google`, kết quả là 1 JWT sống 15 phút | **Mỗi request** nghiệp vụ, tại Gateway (coarse) rồi lại tại service (fine) |
| Input | Mật khẩu/consent Google — IAM **không bao giờ thấy** mật khẩu | JWT đã có sẵn trong header `Authorization: Bearer ...` |
| Output | JWT RS256 chứa `sub`, `email`, `tenant_id`, `role` | Quyết định: cho qua (200...) hay chặn (401/403) |
| Code tương ứng | `AuthController`, `SsoLoginService`, `JwtIssuer`, Google OAuth2 Client của Spring Security | `SecurityConfig` + `StayHubJwtValidator` ở **cả 3 nơi** (Gateway, Catalog, Booking) + check role/ownership trong controller |

Nói ngắn: **AuthN xảy ra 1 lần lúc login, sinh ra "chứng minh nhân dân" (JWT). AuthZ xảy ra lại từ
đầu ở mỗi request, dựa vào thông tin đã ký sẵn trong chứng minh nhân dân đó** — không có "nhớ" gì
giữa các request (stateless), mọi request tự mang đủ bằng chứng.

---

## 3. AuthN chi tiết — IAM xác thực "bạn là ai" như thế nào

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant App as HTML App (1 hoặc 2)
    participant IAM as iam-service
    participant Google

    User->>App: Bấm "Login with Google"
    App->>IAM: Browser navigation: GET /auth/start?app=app1
    Note over IAM: AuthController kiểm "app" nằm trong allowlist<br/>(SsoProperties) — sai/thiếu thì 400, KHÔNG redirect

    alt Chưa có session IAM hợp lệ
        IAM->>IAM: Lưu "app1" vào HttpSession (key sso_app)
        IAM-->>User: 302 /oauth2/authorization/google
        User->>IAM: GET /oauth2/authorization/google
        Note over IAM: authorizationRequestResolver sinh<br/>code_verifier (giữ server-side) +<br/>code_challenge = SHA256(code_verifier)
        IAM-->>User: 302 kèm code_challenge, code_challenge_method=S256, state
        User->>Google: GET authorize?...code_challenge...
        Google-->>User: Trang login/consent
        User->>Google: Nhập mật khẩu + đồng ý
        Google-->>User: 302 /login/oauth2/code/google?code=...&state=...
        User->>IAM: GET /login/oauth2/code/google?code=...
        IAM->>Google: POST /token (code + code_verifier + client_secret)
        Note over IAM,Google: Google tự hash lại code_verifier,<br/>so khớp code_challenge đã nhận trước đó
        Google-->>IAM: ID token (JWT do GOOGLE ký): sub, email, email_verified
    else Đã có session IAM (SSO app thứ 2)
        Note over IAM: Lấy OidcUser trực tiếp từ SecurityContext<br/>của session hiện tại — KHÔNG hỏi lại Google
    end

    IAM->>IAM: SsoLoginService.completeLogin(oidcUser, app)
    Note over IAM: 1. email_verified phải = true<br/>2. tìm app_user theo email (chuẩn hoá lowercase)<br/>3. bind/so khớp google_sub<br/>Bất kỳ bước nào fail => 403, KHÔNG mint JWT, KHÔNG tự tạo user
    IAM->>IAM: JwtIssuer.issue(appUser): ký RS256 bằng PRIVATE key<br/>claims: iss, sub=appUser.id, email, tenant_id, role, iat, exp(+15p)
    IAM-->>User: 302 http://App/callback/#token=<jwt>
    User->>App: Đọc location.hash, xoá fragment, giữ token trong memory
```

**Chốt lại AuthN:** kết quả cuối cùng của toàn bộ chặng này chỉ là **một chuỗi JWT**. Từ giây phút
JWT được phát ra, IAM không còn tham gia gì nữa — Gateway/Catalog/Booking tự verify chữ ký bằng
public key (JWKS) của IAM mà **không cần hỏi lại IAM "token này còn hợp lệ không?"** (không có
session server-side cho JWT, đúng kiểu stateless của JWT).

---

## 4. AuthZ chi tiết — mỗi request được "chặn" mấy lớp

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant App
    participant GW as api-gateway
    participant SVC as catalog-service / booking-service
    participant DB

    App->>GW: POST /api/bookings + Authorization: Bearer <jwt>
    Note over GW: LỚP 1 — Gateway (coarse-grained AuthN check)
    GW->>GW: NimbusReactiveJwtDecoder: verify signature RS256 bằng JWKS cache
    GW->>GW: DelegatingOAuth2TokenValidator:<br/>- issuer đúng "http://localhost:8084"?<br/>- iat/exp có mặt, chưa hết hạn?<br/>- StayHubJwtValidator: sub/email không rỗng?<br/>  tenant_id là SỐ dương? role ∈ {USER,ADMIN}?
    alt Bất kỳ check nào fail
        GW-->>App: 401 (dừng ở đây, KHÔNG forward)
    else JWT hợp lệ về hình thức
        GW->>SVC: Forward request + NGUYÊN Authorization header<br/>(StripPrefix=1: /api/bookings -> /bookings)
        Note over SVC: LỚP 2 — Service tự verify LẠI từ đầu<br/>(không tin Gateway đã verify, phòng trường hợp<br/>ai đó gọi thẳng :8082 bỏ qua Gateway)
        SVC->>SVC: Lặp lại đúng bộ check ở LỚP 1 (JwtDecoder riêng của service)
        alt JWT sai/hết hạn khi verify lại
            SVC-->>App: 401
        else Hợp lệ
            Note over SVC: LỚP 3 — Role-based Authorization (coarse)
            SVC->>SVC: SecurityConfig: endpoint này yêu cầu ROLE nào?<br/>(ví dụ POST /bookings chỉ USER, PUT /room-types/price chỉ ADMIN)
            alt Role trong JWT không khớp yêu cầu endpoint
                SVC-->>App: 403
            else Role đúng
                Note over SVC: LỚP 4 — Ownership / Tenant Authorization (fine, trong code nghiệp vụ)
                SVC->>SVC: CurrentUser.from(jwt): lấy tenant_id, email (ĐÃ VERIFY) — KHÔNG tin body/query
                SVC->>DB: Query luôn kèm WHERE tenant_id = :tenantIdFromJwt<br/>(Booking: thêm AND user_email = :emailFromJwt nếu USER)
                alt Record không tồn tại / thuộc tenant khác / thuộc user khác
                    DB-->>SVC: 0 dòng
                    SVC-->>App: 404 (KHÔNG tiết lộ "có nhưng không phải của bạn")
                else Record đúng tenant/owner
                    DB-->>SVC: dữ liệu
                    SVC-->>App: 200/201 kết quả thật
                end
            end
        end
    end
```

**4 lớp AuthZ, từ thô tới chi tiết:**

| Lớp | Tên | Trả lời câu hỏi | Nơi code |
|---|---|---|---|
| 1 | Gateway JWT check | "Token này có hợp lệ về hình thức không?" | `api-gateway/security/SecurityConfig` + `StayHubJwtValidator` |
| 2 | Service JWT check (lại) | Giống lớp 1, nhưng **độc lập**, phòng bypass Gateway | `catalog-service` / `booking-service` `security/SecurityConfig` |
| 3 | Role-based (RBAC) | "Role này được gọi endpoint này không?" (USER/ADMIN) | `.hasRole(...)` trong từng `SecurityConfig`, khai báo tĩnh theo route |
| 4 | Ownership/Tenant (ABAC) | "Record này có thuộc đúng tenant/đúng chủ sở hữu không?" | Trong service/repository, lọc bằng `tenant_id`/`email` **lấy từ JWT đã verify**, không lấy từ client |

**Vì sao cần cả lớp 1 và lớp 2 (trùng nhau)?** Vì Gateway không phải là "tường lửa duy nhất" theo
chủ đích thiết kế của contract — nếu ai đó gọi thẳng `http://localhost:8081/hotels` (bỏ qua
`:8080`), Catalog vẫn phải tự chặn được, không được dựa vào giả định "request này chắc đã qua
Gateway rồi".

**Vì sao lớp 3 và lớp 4 phải tách riêng?** Role (`USER`/`ADMIN`) là thuộc tính **tĩnh** của người
dùng, kiểm được ngay từ JWT, không cần hỏi DB — nên có thể chặn ở `SecurityConfig` (declarative,
trước khi vào tới logic nghiệp vụ). Nhưng "booking này có phải của tôi không" là thuộc tính
**động** phụ thuộc dữ liệu cụ thể trong DB — phải kiểm trong code nghiệp vụ, sau khi đã load được
record. Hai lớp không thể gộp vì thời điểm kiểm tra khác nhau.

---

## 5. Vì sao JWT là "đủ dùng" cho AuthZ mà không cần hỏi lại IAM

JWT đã tự mang theo **mọi thông tin cần để quyết định quyền truy cập**: `tenant_id`, `role`,
`email`, đã được **ký** nên Gateway/Catalog/Booking tin được nội dung này là thật (không bị client
sửa) miễn là verify đúng chữ ký bằng public key của IAM. Đây là lý do toàn bộ hệ thống **stateless
về phiên đăng nhập business** — không có bảng "session" nào ở Catalog/Booking, không service nào
phải gọi `GET /introspect` sang IAM để hỏi "token này còn sống không" (so với mô hình opaque token
truyền thống). Cái giá phải đánh đổi: **JWT không revoke được giữa đường** — nếu cần vô hiệu hoá
một token đã phát, chỉ có cách đợi hết `exp` (15 phút) hoặc restart IAM để đổi keypair (làm hỏng
toàn bộ JWT cũ, kể cả JWT hợp lệ khác) — đây là giới hạn đã khai báo ở `docs/sequence-iam.md` §8.

---

## 6. Tổng hợp 1 request đi từ đầu đến cuối (ví dụ thật: USER đặt phòng)

```mermaid
flowchart LR
    A["USER_1 đã có JWT<br/>(role=USER, tenant_id=1,<br/>email=user-tenant1@...)"] --> B["POST localhost:8080<br/>/api/bookings"]
    B --> C{"Gateway:<br/>JWT hợp lệ về<br/>chữ ký/issuer/claims?"}
    C -- "Không" --> C1["401"]
    C -- "Có" --> D["Forward -> localhost:8082<br/>/bookings (StripPrefix)"]
    D --> E{"Booking:<br/>verify JWT lại"}
    E -- "Không" --> E1["401"]
    E -- "Có" --> F{"Role = USER?<br/>(ADMIN bị chặn ở đây)"}
    F -- "Không" --> F1["403"]
    F -- "Có" --> G["Đọc tenant_id=1, email<br/>TỪ JWT (không tin body)"]
    G --> H{"room_type thuộc<br/>tenant_id=1?"}
    H -- "Không" --> H1["404"]
    H -- "Có" --> I{"UPDATE room_inventory<br/>sold+1 <= total<br/>mọi đêm trong kỳ ở?"}
    I -- "Không (hết phòng)" --> I1["409, ROLLBACK"]
    I -- "Có" --> J["INSERT booking<br/>PENDING_PAYMENT<br/>tenant_id=1, user_email=user-tenant1@..."]
    J --> K["201 Booking JSON"]

    style C1 fill:#c53030,color:#fff
    style E1 fill:#c53030,color:#fff
    style F1 fill:#c53030,color:#fff
    style H1 fill:#c53030,color:#fff
    style I1 fill:#dd6b20,color:#fff
    style K fill:#2f855a,color:#fff
```

Request này đi qua **AuthN đúng 0 lần** (vì JWT đã được mint từ trước, request này không login
lại) và **AuthZ đúng 4 lần** (Gateway JWT, Booking JWT, Role, Tenant-ownership) trước khi chạm vào
logic nghiệp vụ thật (trừ tồn, tạo booking).

---

## 7. Liên kết tài liệu

- Chi tiết OAuth2/PKCE/JWKS/SSO 2 app, giải thích "vì sao" từng quyết định kỹ thuật: `docs/sequence-iam.md`.
- Hợp đồng API đầy đủ (route, status code, JSON schema): `docs/api-contract.md`.
- Mô hình dữ liệu: `docs/erd-v2.md`.
