
# StayHub — Technical Documentation

> **Phiên bản:** 3-App Architecture + Custom IAM  
> **Cập nhật:** 04/11/2026  
> **Trạng thái:** Documentation Baseline — Chờ đối chiếu source code và kiểm thử tích hợp  
> **Phạm vi:** Booking Web, Admin Dashboard, Support Center, IAM và các Backend Services

## 1. Tổng quan hệ thống

StayHub là hệ thống đặt phòng khách sạn theo kiến trúc microservices, hỗ trợ đăng nhập Google, Single Sign-On (SSO), phân quyền người dùng và quản lý tồn kho phòng theo từng đêm.

Hệ thống gồm **3 ứng dụng frontend độc lập**, sử dụng chung một IAM Service và các backend services.

### 1.1. Kiến trúc xác thực đã thống nhất

```text
                   Google Identity
                   (OAuth2 / OIDC)
                          |
                          v
                 +------------------+
                 |   IAM Service    |
                 |      :8084       |
                 |------------------|
                 | Google OAuth2    |
                 | PKCE S256        |
                 | HttpSession SSO  |
                 | JWT RS256        |
                 | JWKS Endpoint    |
                 +------------------+
                          |
           +--------------+--------------+
           |              |              |
           v              v              v
      Booking Web    Admin Dashboard  Support Center
        :3000            :3001           :3002
        USER             ADMIN         USER / ADMIN
           |              |              |
           +--------------+--------------+
                          |
                          v
                 +------------------+
                 |   API Gateway    |
                 |      :8080       |
                 +------------------+
                          |
           +--------------+--------------+
           |              |              |
           v              v              v
      Catalog Service Booking Service Support Service
           :8081          :8082          TBD
           |              |              |
           +--------------+--------------+
                          |
                          v
                  PostgreSQL Database
                      hotelbooking
```

**Quyết định kiến trúc:**

- Giữ nguyên hướng triển khai IAM Service của developer.
- Sử dụng Google OAuth2/OIDC với Authorization Code Flow và PKCE S256.
- IAM Service quản lý phiên đăng nhập SSO bằng `HttpSession`.
- IAM Service tự phát hành JWT ký bằng RS256.
- Gateway và các backend services xác thực JWT thông qua JWKS.
- Không sử dụng Keycloak.
- Mở rộng IAM từ 2 ứng dụng lên 3 ứng dụng, hạn chế thay đổi implementation hiện có.

> IAM Service hiện tại đóng vai trò OAuth2 Client kết nối Google và JWT Issuer nội bộ. Không mặc định xem IAM là một OIDC Authorization Server đầy đủ.

---

## 2. Kiến trúc 3 ứng dụng

| Application | Development URL | Role | Chức năng |
|---|---|---|---|
| Booking Web | `http://localhost:3000` | `USER` | Tìm kiếm, đặt phòng, thanh toán mô phỏng, quản lý đơn |
| Admin Dashboard | `http://localhost:3001` | `ADMIN` | Quản lý khách sạn, loại phòng, tồn kho, đơn đặt phòng |
| Support Center | `http://localhost:3002` | `USER`, `ADMIN` | Tạo, theo dõi, phản hồi và xử lý yêu cầu hỗ trợ |

### 2.1. Booking Web

**Role:** `USER`

Chức năng chính:

- Đăng nhập bằng Google thông qua IAM Service.
- Xem danh sách khách sạn thuộc tenant.
- Tìm kiếm loại phòng còn trống theo ngày nhận/trả phòng.
- Tạo đơn đặt phòng.
- Thanh toán mô phỏng.
- Hủy đơn đang chờ thanh toán.
- Xem lịch sử và trạng thái đơn đặt phòng.
- Chuyển sang Support Center thông qua SSO.

**Ràng buộc:** `ADMIN` không được thực hiện nghiệp vụ đặt phòng.

### 2.2. Admin Dashboard

**Role:** `ADMIN`

Chức năng chính:

- Đăng nhập bằng Google thông qua IAM Service.
- Quản lý khách sạn.
- Quản lý loại phòng và giá bán.
- Quản lý tồn kho phòng theo từng đêm.
- Xem và quản lý thông tin đơn đặt phòng trong phạm vi tenant.
- Chuyển sang Support Center thông qua SSO.

**Ràng buộc:** `USER` không được truy cập các chức năng quản trị.

### 2.3. Support Center

**Role:** `USER`, `ADMIN`

Đây là ứng dụng thứ ba dùng chung cho hai role, đồng thời phục vụ kiểm thử SSO giữa các ứng dụng độc lập.

**USER:**

- Tạo ticket hỗ trợ.
- Liên kết ticket với booking thuộc quyền sở hữu.
- Xem danh sách ticket của mình.
- Gửi và xem phản hồi.

**ADMIN:**

- Xem ticket thuộc tenant được quản lý.
- Phản hồi ticket.
- Cập nhật trạng thái xử lý.
- Theo dõi các yêu cầu hỗ trợ.

**Lưu ý:** Support Center là phạm vi mở rộng cần triển khai. Không mặc định Support Service và các API ticket đã tồn tại trong source code.

---

## 3. Authentication, SSO và Authorization

### 3.1. Authentication Flow

```text
User / Admin
    |
    v
Frontend Application
    |
    | GET /auth/start?app=<app>
    v
IAM Service
    |
    | Kiểm tra HttpSession
    |
    +-- Chưa đăng nhập
    |       |
    |       v
    |   Google OAuth2 / OIDC
    |   Authorization Code + PKCE
    |       |
    |       v
    |   IAM xác thực Google Identity
    |
    +-- Đã có IAM Session
            |
            v
    Kiểm tra app_user
    - email_verified
    - pre-provisioned account
    - google_sub binding
    - tenant
    - role
            |
            v
    Kiểm tra quyền truy cập ứng dụng
            |
            v
    IAM phát hành JWT RS256
            |
            v
    Redirect về Frontend Callback
            |
            v
    Frontend gọi API Gateway
    Authorization: Bearer <JWT>
```

### 3.2. Single Sign-On

SSO được triển khai thông qua phiên xác thực chung tại IAM Service.

Ví dụ:

```text
USER
  |
  v
Booking Web (:3000)
  |
  | Đăng nhập Google lần đầu
  v
IAM Service
  |
  | Tạo HttpSession
  | Phát JWT cho Booking
  v
Booking Web
  |
  | Mở Support Center
  v
Support Center (:3002)
  |
  | /auth/start?app=support
  v
IAM Service
  |
  | Nhận diện phiên SSO hiện có
  | Không yêu cầu đăng nhập Google lại
  | Phát JWT mới
  v
Support Center
```

Tương tự, `ADMIN` có thể chuyển từ Admin Dashboard sang Support Center.

**Nguyên tắc:**

- Các ứng dụng sử dụng chung IAM Session.
- Không chia sẻ JWT trực tiếp giữa các frontend.
- Mỗi ứng dụng nhận JWT mới từ IAM khi thực hiện luồng đăng nhập.
- SSO không đồng nghĩa với việc bỏ qua kiểm tra role.
- Gateway và backend services phải thực hiện authorization độc lập.

### 3.3. Role-Based Access Control

| Nghiệp vụ | USER | ADMIN |
|---|---|---|
| Truy cập Booking Web | Cho phép | Từ chối |
| Truy cập Admin Dashboard | Từ chối | Cho phép |
| Truy cập Support Center | Cho phép | Cho phép |
| Tạo booking | Cho phép | Từ chối |
| Thanh toán mô phỏng | Booking của mình | Từ chối |
| Hủy booking | Booking của mình | Từ chối |
| Quản lý loại phòng | Từ chối | Cho phép |
| Quản lý inventory | Từ chối | Cho phép |
| Tạo ticket hỗ trợ | Cho phép | Không thuộc scope hiện tại |
| Xử lý ticket | Từ chối | Cho phép |

Authorization phải được kiểm tra tại IAM khi điều hướng vào ứng dụng và tại backend khi truy cập API.

### 3.4. JWT và Tenant Isolation

JWT do IAM Service phát hành chứa các claims chính:

- `iss`: IAM issuer.
- `sub`: ID của `app_user`.
- `email`: Email đã xác thực.
- `tenant_id`: Tenant của người dùng.
- `role`: `USER` hoặc `ADMIN`.
- `iat`: Thời điểm phát hành.
- `exp`: Thời điểm hết hạn.

Backend phải:

1. Xác minh chữ ký RS256 bằng JWKS.
2. Kiểm tra issuer và thời hạn token.
3. Kiểm tra các claims bắt buộc.
4. Kiểm tra role theo endpoint.
5. Lấy `tenant_id` và danh tính từ JWT đã xác thực.
6. Kiểm tra quyền sở hữu tài nguyên.

Không tin tưởng `tenant_id`, `role` hoặc thông tin owner do frontend tự gửi.

---

## 4. Backend Services

| Service | Port | Trách nhiệm |
|---|---|---|
| API Gateway | `8080` | Routing, xác thực JWT, kiểm soát truy cập ở gateway |
| IAM Service | `8084` | Google Login, PKCE, SSO Session, JWT, JWKS |
| Catalog Service | `8081` | Hotel, Room Type, Inventory |
| Booking Service | `8082` | Booking, Payment Mock, Cancel, Expiry |
| Support Service | TBD | Ticket, Message, Ticket Status |

### 4.1. Service Boundaries

**IAM Service**

- Sở hữu dữ liệu `tenant`, `app_user`.
- Quản lý đăng nhập và định danh.
- Phát hành JWT.

**Catalog Service**

- Sở hữu dữ liệu `hotel`, `room_type`.
- Tạo inventory và quản lý `room_inventory.total`.
- Cung cấp API tìm phòng trống.

**Booking Service**

- Sở hữu dữ liệu `booking`.
- Quản lý `room_inventory.sold`.
- Xử lý giữ phòng, thanh toán, hủy và hết hạn.
- Đảm bảo tính nhất quán dữ liệu trong transaction.

**Support Service**

- Sở hữu dữ liệu ticket và message.
- Kiểm tra tenant, role và ownership.
- Liên kết ticket với booking khi cần.

> Trong phạm vi demo, các services sử dụng chung PostgreSQL database. Việc phân chia trách nhiệm ghi dữ liệu vẫn phải được tuân thủ.

---

## 5. Database và Booking Domain

**Database:** PostgreSQL `hotelbooking`  
**Schema:** `public`  
**Migration:** Flyway

### 5.1. Các bảng nghiệp vụ

| Table | Service phụ trách | Mục đích |
|---|---|---|
| `tenant` | IAM | Phân tách dữ liệu tenant |
| `app_user` | IAM | Người dùng, role, Google identity |
| `hotel` | Catalog | Thông tin khách sạn |
| `room_type` | Catalog | Loại phòng, giá, sức chứa |
| `room_inventory` | Catalog / Booking | Tồn kho theo từng đêm |
| `booking` | Booking | Đơn đặt phòng |
| `support_ticket` | Support | Yêu cầu hỗ trợ — đề xuất |
| `ticket_message` | Support | Nội dung trao đổi — đề xuất |

### 5.2. Booking Inventory Model

Hệ thống sử dụng tồn kho theo từng đêm, không đặt trực tiếp từng phòng vật lý.

```text
Hotel
  |
  v
Room Type
  |
  v
Room Inventory
  |
  +-- stay_date
  +-- total
  +-- sold
```

Số phòng còn trống:

```text
available = total - sold
```

Các nguyên tắc bắt buộc:

- Mỗi dòng `room_inventory` đại diện cho một loại phòng trong một đêm.
- `check_in` được tính là đêm đầu tiên.
- `check_out` không được tính là đêm lưu trú.
- Tìm phòng phải kiểm tra tồn kho đủ cho tất cả các đêm.
- Booking phải cập nhật inventory và tạo đơn trong cùng transaction.
- Các đêm được cập nhật theo thứ tự ngày tăng dần.
- Không cho phép `sold > total`.
- Hủy hoặc hết hạn chỉ được hoàn tồn kho một lần.

### 5.3. Booking Lifecycle

```text
                 CREATE BOOKING
                       |
                       v
                PENDING_PAYMENT
                       |
          +------------+------------+
          |            |            |
          v            v            v
      CONFIRMED     CANCELLED     EXPIRED
```

| Status | Giữ tồn kho | Ý nghĩa |
|---|---|---|
| `PENDING_PAYMENT` | Có | Đã giữ phòng, chờ thanh toán |
| `CONFIRMED` | Có | Thanh toán mô phỏng thành công |
| `CANCELLED` | Không | Đã hủy và hoàn tồn kho |
| `EXPIRED` | Không | Hết hạn thanh toán và hoàn tồn kho |

Thời gian thanh toán mặc định theo thiết kế: **15 phút**, có thể cấu hình.

### 5.4. Booking Ownership

Giữ nguyên hướng triển khai hiện tại của developer:

- Bảng người dùng: `app_user`.
- Google identity binding: `google_sub`.
- Booking owner: `booking.user_email`.
- Tenant lấy từ JWT đã xác minh.

Không tự ý thay `booking.user_email` bằng `user_id` trong giai đoạn tích hợp nếu chưa có migration và quyết định thống nhất.

---

## 6. Cấu trúc Documentation

```text
StayHub_Documentation/
├── README.md
└── docs/
    ├── architecture.md
    ├── iam-sso.md
    ├── iam-integration-tasks.md
    ├── iam-testing.md
    ├── api-contract.md
    ├── data-model.md
    ├── workflows.md
    ├── operations-and-tests.md
    └── decisions.md
```

### 6.1. Thứ tự đọc tài liệu

| Thứ tự | File | Nội dung |
|---|---|---|
| 1 | `docs/architecture.md` | Kiến trúc tổng thể, service boundaries, 3 App |
| 2 | `docs/iam-sso.md` | Google OAuth2, PKCE, JWT, JWKS, SSO |
| 3 | `docs/iam-integration-tasks.md` | Công việc mở rộng IAM từ 2 App lên 3 App |
| 4 | `docs/api-contract.md` | REST API, DTO, status code, RBAC |
| 5 | `docs/data-model.md` | ERD, PostgreSQL DDL, inventory, SQL |
| 6 | `docs/workflows.md` | Booking lifecycle, SSO, payment, cancel, expiry |
| 7 | `docs/iam-testing.md` | Kiểm thử IAM và SSO |
| 8 | `docs/operations-and-tests.md` | Flyway, seed, concurrency, integration testing |
| 9 | `docs/decisions.md` | Architecture Decision Records |

### 6.2. Quy tắc Single Source of Truth

Để tránh tài liệu trùng lặp và sai lệch:

- `architecture.md` là nguồn chuẩn cho kiến trúc hệ thống.
- `iam-sso.md` là nguồn chuẩn cho authentication và SSO.
- `api-contract.md` là nguồn chuẩn cho HTTP API.
- `data-model.md` là nguồn chuẩn cho ERD, DDL và database constraints.
- `workflows.md` là nguồn chuẩn cho business workflows.
- `operations-and-tests.md` là nguồn chuẩn cho migration và kiểm thử tích hợp.
- `decisions.md` ghi nhận các quyết định kiến trúc.

Không duy trì nhiều tài liệu khác nhau cùng định nghĩa một API, database schema hoặc authentication flow.

---

## 7. ERD v2 — Nội dung đã hợp nhất

Tài liệu `erd-v2.md` được hợp nhất vào bộ documentation hiện tại.

| Nội dung ERD v2 | File chuẩn |
|---|---|
| ERD, bảng, quan hệ và constraints | `docs/data-model.md` |
| PostgreSQL DDL, composite FK, index | `docs/data-model.md` |
| Inventory theo từng đêm | `docs/data-model.md` |
| SQL tìm phòng trống | `docs/data-model.md` |
| SQL đối soát tồn kho | `docs/data-model.md` |
| Booking transaction và concurrency | `docs/workflows.md` |
| Payment, cancellation, expiry | `docs/workflows.md` |
| API tìm phòng và đặt phòng | `docs/api-contract.md` |
| Flyway migration và seed data | `docs/operations-and-tests.md` |
| Race test và acceptance criteria | `docs/operations-and-tests.md` |

**Quyết định:** Không giữ `erd-v2.md` như một tài liệu active độc lập để tránh trùng nguồn sự thật.

Nội dung quan trọng từ ERD v2 cần được bảo toàn, đặc biệt là SQL DDL, logic chống overselling, transaction và reconciliation.

---

## 8. Implementation Status

Trạng thái dưới đây dựa trên tài liệu và báo cáo của developer, chưa phải kết quả kiểm thử độc lập trên repository.

| Thành phần | Trạng thái |
|---|---|
| Google OAuth2/OIDC Login | Developer reported |
| PKCE S256 | Developer reported |
| IAM HttpSession SSO cho 2 App | Developer reported |
| Custom JWT RS256 | Developer reported |
| JWKS Endpoint | Developer reported |
| Gateway/Catalog JWT Validation | Developer reported |
| IAM SSO mở rộng 3 App | To implement |
| IAM Role-to-App Enforcement | To implement |
| Support Center Frontend | To implement |
| Support Service / Ticket APIs | To implement |
| Booking Inventory ERD v2 | Proposed design |
| Booking Payment / Cancel / Expiry | Cần đối chiếu implementation |
| Full 3-App E2E Testing | Not verified |

### 8.1. Known Blockers

**Booking migration**

Theo báo cáo developer, Booking migration hiện tại còn tham chiếu bảng `room` cũ, trong khi thiết kế ERD v2 chuyển sang `room_type` và `room_inventory`.

Cần đồng bộ migration trước khi kiểm thử toàn bộ Booking flow.

**IAM integration**

Cần mở rộng callback allowlist cho Support Center và kiểm tra quyền truy cập ứng dụng theo role.

**Support Service**

Cần thống nhất API, database schema, service port và quyền truy cập ticket.

---

## 9. Security & Technical Debt

Các giới hạn hiện tại cần được phân biệt với thiết kế production.

| Vấn đề | Hiện trạng / Hướng xử lý |
|---|---|
| JWT trong URL fragment | Demo-only; cần thay bằng cơ chế an toàn hơn cho production |
| JWT lưu trong frontend memory | Demo-only; mất token khi reload |
| RSA keypair sinh lại khi restart | Cần persistent keys và key rotation |
| JWT refresh/revocation | Chưa có đầy đủ |
| IAM Single Logout | Chưa hoàn thiện |
| Multi-tab SSO app selector | Có nguy cơ ghi đè destination trong HttpSession |
| Booking idempotency | Chưa có idempotency key |
| Booking inventory reconciliation | Cần kiểm thử và giám sát |
| Tenant isolation | Bắt buộc kiểm tra tại mọi backend service |

Không đưa Google Client Secret, JWT, private key, session cookie hoặc credentials thật vào Git repository và tài liệu.

---

## 10. Integration Roadmap

### Phase 1 — IAM 3-App Integration

- [ ] Giữ nguyên Google OAuth2, PKCE, JWT và JWKS.
- [ ] Bổ sung callback Support Center.
- [ ] Thiết lập role-to-app policy.
- [ ] Kiểm thử SSO Booking → Support.
- [ ] Kiểm thử SSO Admin → Support.
- [ ] Kiểm thử truy cập sai role.

### Phase 2 — Catalog & Booking

- [ ] Đồng bộ Flyway migration với ERD v2.
- [ ] Hoàn thiện hotel, room type và inventory.
- [ ] Triển khai tìm phòng theo khoảng ngày.
- [ ] Triển khai booking transaction.
- [ ] Triển khai payment mock.
- [ ] Triển khai cancellation và expiry.
- [ ] Kiểm thử concurrent booking và chống overselling.

### Phase 3 — Support Center

- [ ] Triển khai Support Center frontend.
- [ ] Triển khai Support Service.
- [ ] Tạo schema ticket/message.
- [ ] Tích hợp JWT validation.
- [ ] Triển khai ticket ownership và tenant isolation.
- [ ] Kiểm thử USER/ADMIN support workflows.

### Phase 4 — End-to-End Validation

- [ ] Kiểm thử Authentication.
- [ ] Kiểm thử SSO giữa 3 ứng dụng.
- [ ] Kiểm thử RBAC.
- [ ] Kiểm thử Tenant Isolation.
- [ ] Kiểm thử Booking Lifecycle.
- [ ] Kiểm thử Inventory Consistency.
- [ ] Kiểm thử Support Ticket Lifecycle.
- [ ] Đối chiếu documentation với source code thực tế.

---

## 11. Acceptance Criteria

| Metric | Target |
|---|---|
| Google Login | Thành công với tài khoản hợp lệ được provision |
| SSO Booking → Support | Không yêu cầu nhập lại thông tin Google khi IAM session còn hiệu lực |
| SSO Admin → Support | Không yêu cầu nhập lại thông tin Google khi IAM session còn hiệu lực |
| USER truy cập Admin | Bị từ chối |
| ADMIN tạo Booking | Bị từ chối |
| JWT không hợp lệ hoặc hết hạn | HTTP 401 |
| API sai role | HTTP 403 |
| Tài nguyên khác tenant / khác owner | HTTP 404 theo resource policy |
| Concurrent Booking | Không overselling |
| Inventory Reconciliation | 0 dòng sai lệch |
| Cancel / Expiry | Hoàn tồn kho đúng một lần |
| Support Ticket Isolation | USER chỉ xem ticket của mình; ADMIN trong tenant |
| Flyway Migration | Chạy thành công trên database mục tiêu |

Các chỉ tiêu trên là **điều kiện nghiệm thu**, không phải kết quả đã đạt được.

---

## 12. Nguyên tắc triển khai cho Team

1. **IAM-first compatibility:** Ưu tiên tương thích với IAM Service developer đang xây dựng; không thay đổi kiến trúc xác thực nếu không có quyết định mới.
2. **Three-App separation:** Booking, Admin và Support là ba ứng dụng frontend độc lập.
3. **Backend authorization:** Không dựa vào việc ẩn nút hoặc kiểm tra role trên frontend để bảo vệ API.
4. **Tenant isolation:** Mọi thao tác dữ liệu phải được giới hạn theo tenant đã xác thực.
5. **Transactional consistency:** Booking và inventory phải nhất quán trong cùng transaction.
6. **Documentation as contract:** Mọi thay đổi API, schema, workflow hoặc IAM phải cập nhật đúng file nguồn chuẩn.
7. **Evidence-based completion:** Chỉ đánh dấu hoàn thành khi có source code, migration và test evidence tương ứng.

---

## 13. Documentation Status

Bộ tài liệu này là **technical baseline thống nhất cho StayHub 3-App Architecture**, được xây dựng trên hướng IAM custom của developer và thiết kế Booking Inventory ERD v2.

**Documentation đã được hợp nhất không đồng nghĩa implementation đã hoàn tất.**

Trước khi sử dụng làm release baseline, team cần đối chiếu với repository, xác nhận các quyết định còn mở và chạy đầy đủ integration/E2E tests.
