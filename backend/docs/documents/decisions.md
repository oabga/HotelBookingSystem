
# StayHub — Architecture Decision Records (ADR)

> **Updated:** 2026-11-05, đối chiếu với `main` tại commit `1737b60`  
> **Status:** Ghi nhận các quyết định đang có hiệu lực trong code. Chưa có biên bản G2 trong repository.  
> **Scope:** Architecture, Authentication, Authorization, Multi-Tenancy, Booking, Inventory, Support, Database & Operations  
> **Architecture Principle:** Preserve existing Custom IAM — **No Keycloak**

---

## 1. Purpose

Tài liệu này quản lý các quyết định kiến trúc (Architecture Decision Records — ADR) của hệ thống StayHub.

Mục tiêu:

- Ghi nhận các quyết định kỹ thuật và lý do lựa chọn.
- Xác định trade-offs giữa tính đơn giản, hiệu năng, bảo mật và khả năng mở rộng.
- Phân biệt quyết định đã thống nhất về mặt thiết kế với implementation đã được xác minh.
- Hạn chế thay đổi kiến trúc không cần thiết trong MVP.
- Cung cấp cơ sở cho code review, integration testing và production hardening.

**Lưu ý:** `IMPLEMENTED` nghĩa là quyết định đã có trong code. Mức đã chạy thật ghi ở từng ADR và ở [README.md](README.md#6-mức-độ-kiểm-chứng). ADR có nguồn từ code ghi rõ điều đó, vì lý do chọn cần team xác nhận.

---

## 2. Decision Status Convention

| Status | Meaning |
|---|---|
| PROPOSED | Đã đề xuất, chưa được phê duyệt |
| ACCEPTED | Đã được phê duyệt về kiến trúc |
| IMPLEMENTED | Đã triển khai và có evidence |
| DEPRECATED | Không còn khuyến nghị sử dụng |
| SUPERSEDED | Được thay thế bởi ADR khác |
| REJECTED | Không được lựa chọn |

### 2.1. Approval Gates

| Gate | Purpose |
|---|---|
| G1 — Architecture Review | Kiểm tra tính nhất quán của thiết kế |
| G2 — Architecture Sign-off | Phê duyệt ADR và integration contracts |
| G3 — Implementation Verification | Xác minh source code, migrations và tests |
| G4 — Release Acceptance | Nghiệm thu E2E, security và operational readiness |

**Current status:** Repository chưa có biên bản G2, G3, G4.

Không chuyển ADR sang `IMPLEMENTED` chỉ dựa trên tài liệu thiết kế.

---

## 3. ADR Register

| ID | Decision | Status | Đã chạy thật |
|---|---|---|---|
| ADR-001 | Three independent frontend applications | IMPLEMENTED | Test frontend, chưa chạy với Google thật |
| ADR-002 | Preserve Custom IAM + Google OIDC + RS256 JWT | IMPLEMENTED | Endpoint và chuyển hướng. Chưa đăng nhập Google thật |
| ADR-003 | HttpSession SSO across three applications | IMPLEMENTED | Chỉ đọc từ code |
| ADR-004 | RBAC + Tenant Isolation + Resource Ownership | IMPLEMENTED | Workflow Docker |
| ADR-005 | Shared PostgreSQL for atomic Booking and Inventory | IMPLEMENTED | Workflow Docker |
| ADR-006 | Room-Type Booking with Nightly Inventory | IMPLEMENTED | Workflow Docker |
| ADR-007 | Mock Payment for MVP | IMPLEMENTED | Workflow Docker |
| ADR-008 | Support Ticket Service with USER/ADMIN roles | SUPERSEDED bởi ADR-017 | Không áp dụng |
| ADR-009 | Immutable Flyway Migrations | IMPLEMENTED | Migration chạy trên DB mới |
| ADR-010 | Independent JWT Verification at Each Service | IMPLEMENTED | Workflow Docker với JWT của `dev-token` |
| ADR-011 | Preserve Existing Booking Email Ownership Contract | IMPLEMENTED | Workflow Docker |
| ADR-012 | JWT Fragment Callback for Demo Only | IMPLEMENTED | Test frontend. Chưa chạy với Google thật |
| ADR-013 | Transactional Inventory Reservation | IMPLEMENTED | Workflow Docker, 6 request tranh 1 phòng |
| ADR-014 | Minimal IAM Integration Delta | IMPLEMENTED, đã mở rộng bởi ADR-016, ADR-019 | Một phần |
| ADR-015 | Explicit Service and Data Ownership | IMPLEMENTED | Đọc từ code |
| ADR-016 | Chính sách đăng nhập theo từng app, tự tạo tài khoản ở App 1 | IMPLEMENTED | Chỉ đọc từ code |
| ADR-017 | Support chỉ xử lý phiếu đổi ngày, gọi Booking qua HTTP | IMPLEMENTED | Workflow Docker |
| ADR-018 | Notification qua transactional outbox | IMPLEMENTED | Workflow Docker |
| ADR-019 | Đăng xuất chỉ hủy phiên IAM | IMPLEMENTED | Endpoint đã gọi thật |

---

# 4. Architecture Decisions

## ADR-001 — Three Independent Frontend Applications

**Status:** IMPLEMENTED  
**Priority:** P0  
**Decision Owner:** Architecture Team

### Context

StayHub phục vụ ba nhóm chức năng:

1. Khách hàng đặt phòng.
2. Quản trị khách sạn.
3. Hỗ trợ khách hàng sau đặt phòng.

Các nhóm có UI, navigation và authorization requirements khác nhau.

### Decision

Triển khai ba frontend applications:

| Application | URL | Purpose |
|---|---|---|
| App 1 | `http://localhost:3000` | Hotel discovery and booking |
| App Admin | `http://localhost:3001` | Inventory view, room types, prices, tenant bookings |
| Support Center | `http://localhost:3002` | Date-change requests and notifications |

Chỉ sử dụng hai business roles:

```text
USER
ADMIN
```

### Rationale

- Phân tách rõ user experience.
- Frontend có thể phát triển và deploy độc lập.
- Authorization policy dễ xác định.
- Support phục vụ cả USER và ADMIN mà không cần role mới.
- Giữ tương thích với kiến trúc hai frontend hiện tại.

### Trade-offs

**Advantages:**

- Separation of concerns.
- Independent frontend lifecycle.
- Clear route ownership.
- Reduced UI coupling.

**Disadvantages:**

- Phải quản lý ba frontend deployments.
- Phải cấu hình callback riêng cho từng ứng dụng.
- Cần kiểm thử cross-app SSO.
- Có thêm integration complexity.

### Consequences

- IAM phải hỗ trợ ba app identifiers.
- Support Center phải có callback riêng.
- Không chia sẻ frontend JWT trực tiếp giữa các ứng dụng.

---

## ADR-002 — Preserve Custom IAM with Google OIDC

**Status:** IMPLEMENTED  
**Priority:** P0  
**Decision Owner:** IAM Developer + Architecture Team

### Context

IAM developer đã xây dựng hệ thống xác thực riêng bằng Spring Security, Google OIDC, HttpSession và JWT RS256.

Việc thay thế IAM trong giai đoạn tích hợp ba ứng dụng làm tăng chi phí migration và regression risk.

### Decision

Tiếp tục sử dụng:

```text
iam-service :8084
```

Với các thành phần:

- Spring Security OAuth2 Client.
- Google OAuth2/OIDC.
- Authorization Code flow.
- PKCE S256.
- HttpSession SSO.
- `app_user` theo chính sách ở ADR-016. Quyết định ban đầu là pre-provision toàn bộ.
- Google `sub` binding.
- Custom JWT RS256 issuer.
- JWKS public key endpoint.

**Không triển khai Keycloak.**

### Rationale

- Tương thích với source code IAM hiện tại.
- Tránh viết lại authentication infrastructure.
- Giảm integration latency và migration risk.
- Giữ nguyên JWT contract cho Gateway và services.
- Tập trung nguồn lực vào Support Service và business workflows.

### Trade-offs

**Advantages:**

- Minimum code changes.
- Preserve existing developer work.
- No new identity infrastructure.
- Full control over IAM business policy.

**Disadvantages:**

- Team tự chịu trách nhiệm security hardening.
- Chưa có full OIDC Authorization Server.
- Chưa có token revocation.
- Đăng xuất chỉ hủy phiên IAM, xem ADR-019.
- Cần quản lý signing keys và session lifecycle.

### Consequences

IAM hiện tại được giữ nguyên trừ khi phát hiện bug hoặc security issue.

Production readiness phải được đánh giá độc lập.

---

## ADR-003 — HttpSession SSO Across Three Applications

**Status:** IMPLEMENTED  
**Priority:** P0  
**Decision Owner:** IAM Developer

### Context

Booking và Admin đã có luồng SSO dựa trên IAM HttpSession theo tài liệu developer.

Support Center cần tham gia cùng cơ chế.

### Decision

Giữ các app identifiers:

```text
app1    -> App 1
app2    -> App Admin
app3    -> Support Center
```

Role-to-app policy:

| Application | Allowed Roles |
|---|---|
| App 1 | USER |
| App Admin | ADMIN, chỉ email `IAM_ADMIN_EMAIL` (ADR-016) |
| Support | USER, ADMIN |

Mỗi lần chuyển ứng dụng:

1. Browser truy cập IAM `/auth/start?app=...`.
2. IAM tái sử dụng authenticated HttpSession nếu còn hợp lệ.
3. IAM kiểm tra lại identity, provisioning và role.
4. IAM phát JWT mới.
5. Browser redirect tới callback tương ứng.

### Rationale

- SSO không cần chia sẻ JWT giữa frontend applications.
- Tận dụng IAM session hiện tại.
- Cho phép kiểm tra lại authorization khi vào ứng dụng mới.
- Không cần triển khai identity provider mới.

### Trade-offs

**Advantages:**

- Reduced repeated authentication.
- Clear app authorization.
- Independent frontend tokens.
- Minimal changes to IAM architecture.

**Disadvantages:**

- Phụ thuộc browser cookie và IAM session.
- Multi-tab login có thể gặp race với session-wide app selector.
- JWT expiry và IAM session expiry độc lập.
- Chưa có đăng xuất giữa các app.
- Mở một app không được phép sẽ hủy phiên IAM.

### Consequences

- Callback Support và chính sách theo app đã có trong code.
- Chưa chạy thật SSO App 1 → Support và App Admin → Support, xem [iam-testing.md](iam-testing.md).
- Rủi ro hai tab song song với `sso_app` vẫn còn và chưa có test.

---

## ADR-004 — RBAC + Tenant Isolation + Resource Ownership

**Status:** IMPLEMENTED  
**Priority:** P0  
**Decision Owner:** Backend Architecture Team

### Context

StayHub là hệ thống multi-tenant.

Chỉ kiểm tra role không đủ để bảo vệ dữ liệu của các tenant hoặc người dùng khác nhau.

### Decision

Authorization sử dụng ba lớp:

```text
Role-Based Access Control
        +
Tenant Isolation
        +
Resource Ownership
```

Trusted identity lấy từ JWT đã được verify:

```text
sub
email
tenant_id
role
```

### Authorization Model

| Layer | Example |
|---|---|
| RBAC | USER không được gọi ADMIN API |
| Tenant Isolation | Tenant A không đọc dữ liệu Tenant B |
| Ownership | USER chỉ đọc booking/ticket của mình |

### Response Policy

| Condition | Response |
|---|---|
| Missing/invalid JWT | `401` |
| Wrong role | `403` |
| Cross-tenant resource | `404` |
| Another user's resource | `404` |

### Rationale

- Tránh horizontal privilege escalation.
- Tránh cross-tenant data leakage.
- Frontend route guards không phải security boundary.
- Có thể kiểm thử authorization ở service level.

### Trade-offs

- Mọi repository query cần tenant scoping.
- Business services cần ownership checks.
- Authorization tests phức tạp hơn.
- Cần thống nhất identity contract.

### Consequences

Không tin các giá trị sau từ request payload:

```text
tenantId
userId
role
ownerEmail
```

để quyết định quyền truy cập.

---

## ADR-005 — Shared PostgreSQL for Booking & Inventory

**Status:** IMPLEMENTED  
**Priority:** P0  
**Decision Owner:** Backend Architecture Team

### Context

Booking cần giữ tồn kho phòng theo từng đêm.

Nếu Booking và Inventory sử dụng database độc lập, việc giữ tồn kho nguyên tử sẽ cần distributed coordination.

### Decision

Trong MVP, sử dụng PostgreSQL chung:

```text
Database: hotelbooking
Schema: public
```

Các service sử dụng cùng database nhưng phải xác định rõ data ownership.

### Data Ownership

| Data | Owner |
|---|---|
| `tenant` | IAM |
| `app_user` | IAM |
| `hotel` | Catalog |
| `room_type` | Catalog |
| `room_inventory.total` | Catalog |
| `room_inventory.sold` | Booking |
| `booking`, `booking_reschedule` | Booking |
| `date_change_request` | Support |
| `notification_outbox` | Support ghi sự kiện, Notification ghi `processed_at` |
| `notification` | Notification |

### Rationale

Cho phép Booking cập nhật:

```text
room_inventory.sold
+
booking record
```

trong cùng PostgreSQL transaction.

### Trade-offs

**Advantages:**

- ACID transaction.
- Lower implementation complexity.
- No distributed transaction coordinator.
- Easier inventory consistency.

**Disadvantages:**

- Database-level coupling.
- Cross-service schema dependencies.
- Harder independent database scaling.
- Requires coordinated migrations.
- Ownership boundaries are logical rather than physical.

### Consequences

Shared database không có nghĩa mọi service được tự do sửa mọi bảng.

Cross-service writes phải được giới hạn theo ownership contract.

---

## ADR-006 — Room-Type Booking with Nightly Inventory

**Status:** IMPLEMENTED  
**Priority:** P0  
**Decision Owner:** Booking + Catalog Developers

### Context

MVP cần hỗ trợ đặt phòng theo loại phòng và khoảng ngày.

Không yêu cầu gán phòng vật lý cụ thể.

### Decision

Booking bán theo:

```text
Hotel
    |
    +-- Room Type
            |
            +-- Nightly Inventory
```

Inventory key:

```text
(tenant_id, room_type_id, stay_date)
```

### Inventory Model

```text
total = Total available rooms
sold  = Reserved/occupied inventory
```

Invariant:

```text
0 <= sold <= total
```

Khoảng ngày:

```text
[checkIn, checkOut)
```

Ví dụ:

```text
Check-in: 2026-12-01
Check-out: 2026-12-04

Inventory nights:
2026-12-01
2026-12-02
2026-12-03
```

### Rationale

- Đơn giản hơn physical-room assignment.
- Phù hợp hotel booking MVP.
- Hỗ trợ kiểm tra availability theo từng đêm.
- Có thể mở rộng sang pricing và inventory management sau này.

### Trade-offs

- Chưa quản lý số phòng vật lý.
- Chưa có room assignment.
- Chưa có daily pricing.
- Inventory phải được tạo cho các ngày cần bán.

### Consequences

Availability search chỉ là read-only snapshot.

Booking transaction mới là nguồn quyết định cuối cùng về tồn kho.

---

## ADR-007 — Mock Payment Only in MVP

**Status:** IMPLEMENTED  
**Priority:** P1  
**Decision Owner:** Product + Booking Team

### Context

MVP tập trung kiểm chứng authentication, booking workflow, inventory consistency và support operations.

Tích hợp payment gateway thật sẽ tăng scope và operational complexity.

### Decision

Sử dụng mock payment.

Booking lifecycle:

```text
PENDING_PAYMENT
        |
        +-- Mock Payment Success --> CONFIRMED
        |
        +-- Cancel --> CANCELLED
        |
        +-- Timeout --> EXPIRED
```

### Rationale

- Giảm integration dependencies.
- Không cần xử lý real money.
- Tập trung test booking state machine.
- Dễ mô phỏng payment success/failure.

### Trade-offs

- Không có payment gateway thật.
- Không có refund.
- Không có payment reconciliation.
- Không xử lý webhook hoặc settlement.

### Consequences

Không gọi MVP là production-ready payment system.

Mock payment endpoints phải được bảo vệ và không được bật ngoài môi trường cho phép.

---

## ADR-008 — Support Ticket Service with Existing Roles

> **Đã bị thay thế bởi ADR-017.** Phần dưới được giữ làm lịch sử. Các bảng `support_ticket`, `ticket_message` đã bị xóa ở `support-service` `V2`.

**Status:** SUPERSEDED bởi ADR-017  
**Priority:** P0  
**Decision Owner:** Support Developer + Architecture Team

### Context

Khách hàng cần được hỗ trợ sau khi đặt phòng.

ADMIN cần quản lý ticket theo tenant.

### Decision

Triển khai Support Service mới với:

```text
support_ticket
ticket_message
```

Chỉ sử dụng:

```text
USER
ADMIN
```

### USER Capabilities

- Create ticket.
- Link ticket to own booking.
- List own tickets.
- Read own ticket.
- Reply to own ticket.
- View ticket status.

### ADMIN Capabilities

- View tenant ticket queue.
- Read tenant tickets.
- Reply to tickets.
- Assign tickets.
- Update ticket status.
- Resolve and close tickets.

### Ticket Lifecycle

```mermaid
stateDiagram-v2
    [*] --> OPEN
    OPEN --> IN_PROGRESS
    IN_PROGRESS --> RESOLVED
    RESOLVED --> CLOSED
    CLOSED --> [*]
```

### Rationale

- Hỗ trợ post-booking operations.
- Không làm phức tạp IAM role model.
- Có thể phát triển Support độc lập.
- Có audit author và timestamp cho messages.

### Trade-offs

- Thêm service và frontend mới.
- Cần authorization integration.
- Cần xác định booking ownership validation.
- Cần quản lý ticket lifecycle và retention.

### Consequences

Support Service phải verify JWT độc lập.

USER chỉ được thao tác trên ticket của mình.

ADMIN chỉ được thao tác trên ticket thuộc tenant.

---

## ADR-009 — Immutable Flyway Migrations

**Status:** IMPLEMENTED  
**Priority:** P0  
**Decision Owner:** Backend + Database Team

### Context

IAM, Catalog, Booking, Support và Notification cùng sử dụng PostgreSQL.

Thay đổi migration đã chạy có thể gây checksum mismatch và deployment failure.

### Decision

Flyway migrations đã áp dụng trên shared/deployed database phải được giữ nguyên.

Naming convention:

```text
V<number>__snake_case.sql
```

### Migration Principles

1. Không chỉnh sửa applied migrations.
2. Tạo migration mới cho schema changes.
3. Kiểm tra migration dependencies.
4. Không xóa production/shared data để sửa migration.
5. Dùng additive migration khi có thể.
6. Backfill dữ liệu trước khi áp dụng constraint mới.
7. Validate trước khi cleanup legacy columns.
8. Review cross-service schema changes.

### Migration Dependency

```text
IAM Tables
    |
    v
Catalog Tables
    |
    v
Booking Tables
    |
    v
Support Tables
    |
    v
Notification Tables
```

Tên migration và bảng lịch sử xem [erd.md](erd.md#5-migration).

### Trade-offs

- Cần quản lý versioning chặt chẽ.
- Cross-service changes cần coordination.
- Shared schema tạo coupling.
- Migration rollback không luôn đơn giản.

### Consequences

Không sử dụng:

```bash
docker compose down -v
```

để xử lý lỗi migration trên shared hoặc production database.

Disposable local database chỉ được reset khi có sự đồng ý và không chứa dữ liệu cần giữ.

---

## ADR-010 — Independent JWT Verification at Each Service

**Status:** IMPLEMENTED  
**Priority:** P0  
**Decision Owner:** Security + Backend Team

### Context

Gateway là entry point chính, nhưng business services có thể bị gọi trực tiếp trong development hoặc internal network.

### Decision

Mỗi service tự verify JWT bằng IAM JWKS.

JWKS endpoint:

```text
http://localhost:8084/.well-known/jwks.json
```

Issuer:

```text
http://localhost:8084
```

### Required Checks

- RS256 signature.
- Issuer.
- Expiration.
- Required claims.
- Role.
- Tenant.
- Ownership where applicable.

### Rationale

- Defense in depth.
- Không phụ thuộc hoàn toàn vào Gateway.
- Ngăn bypass authorization qua direct service access.
- Giữ service security boundaries rõ ràng.

### Trade-offs

- Security configuration lặp lại giữa services.
- Cần shared JWT contract.
- Cần quản lý JWKS cache và key rotation.
- Cần integration tests cho từng service.

### Consequences

Gateway validation không thay thế service-level validation.

---

## ADR-011 — Preserve Booking Email Ownership Contract

**Status:** IMPLEMENTED  
**Priority:** P0  
**Decision Owner:** Booking Developer

### Context

Booking implementation hiện tại sử dụng:

```text
booking.user_email
```

Thay đổi sang `user_id` sẽ ảnh hưởng schema, queries, APIs và migration.

### Decision

Giữ Booking ownership contract hiện tại trong MVP.

Ownership:

```text
booking.tenant_id = JWT.tenant_id

AND

booking.user_email = JWT.email
```

Support và Notification dùng user ID:

```text
date_change_request.requester_user_id = app_user.id
notification.recipient_user_id = app_user.id
```

### Rationale

- Tránh breaking changes.
- Giữ tương thích Booking code.
- Giảm migration risk.
- Cho phép các bảng mới dùng định danh ổn định ngay từ đầu.

### Trade-offs

- Email có thể thay đổi.
- Ownership phụ thuộc email consistency.
- Booking và Support sử dụng hai identity references khác nhau.

### Consequences

Không tự ý đổi `booking.user_email` thành `booking.user_id`.

Nếu cần migration trong tương lai, phải tạo ADR và migration plan riêng.

---

## ADR-012 — JWT Fragment Callback for Demo Only

**Status:** IMPLEMENTED  
**Priority:** P1  
**Decision Owner:** IAM + Frontend Team

### Context

IAM hiện tại redirect JWT qua URL fragment:

```text
/callback/#token=<JWT>
```

Frontend giữ JWT trong memory.

### Decision

Giữ flow hiện tại trong demo để hạn chế thay đổi IAM.

Frontend phải:

1. Đọc JWT từ fragment.
2. Xóa fragment khỏi URL.
3. Giữ JWT trong memory.
4. Không lưu JWT vào localStorage.
5. Không ghi JWT vào logs.

### Rationale

- Tương thích implementation hiện tại.
- Đơn giản cho demo.
- Không yêu cầu xây dựng BFF ngay.

### Trade-offs

- XSS có thể đọc JWT.
- Browser extensions có thể truy cập token tùy quyền.
- Reload làm mất in-memory token.
- Chưa có refresh token.
- Đăng xuất không thu hồi JWT đã cấp.

### Consequences

**Không sử dụng flow này làm mặc định production architecture.**

Production replacement cần được quyết định riêng.

Các phương án có thể xem xét:

- BFF.
- HttpOnly Secure cookies.
- SameSite policy.
- CSRF protection.
- Server-side token handling.

---

## ADR-013 — Transactional Inventory Reservation

**Status:** IMPLEMENTED  
**Priority:** P0  
**Decision Owner:** Booking Developer

### Context

Hai người dùng có thể đặt cùng phòng cuối cùng tại cùng thời điểm.

Read-only availability check không bảo đảm inventory consistency.

### Decision

Booking phải giữ tồn kho bằng atomic conditional update trong PostgreSQL transaction.

### Reservation Invariant

```text
sold <= total
```

### Booking Transaction

```text
BEGIN TRANSACTION
    |
    v
Validate tenant and room type
    |
    v
Validate nightly inventory
    |
    v
Atomic conditional UPDATE sold = sold + quantity
    |
    +-- Any night failed --> ROLLBACK
    |
    v
INSERT booking (PENDING_PAYMENT)
    |
    v
COMMIT
```

### Cancellation / Expiry

- `CANCELLED` release inventory.
- `EXPIRED` release inventory.
- Duplicate cancellation không release lần hai.
- Duplicate expiry không release lần hai.
- `CONFIRMED` không tăng inventory lần nữa.

### Rationale

- Prevent overselling.
- ACID consistency.
- Avoid distributed transaction complexity.
- Deterministic concurrency behavior.

### Trade-offs

- Row-level contention khi nhiều booking cùng room type/date.
- Booking phụ thuộc shared PostgreSQL.
- Cần race tests và reconciliation.

### Consequences

Acceptance:

```text
Concurrent booking for last room:
    1 success
    1 conflict

Oversold nights:
    0

Inventory reconciliation mismatches:
    0
```

---

## ADR-014 — Minimum IAM Integration Delta

**Status:** IMPLEMENTED  
**Priority:** P0  
**Decision Owner:** Architecture + IAM Team

### Context

IAM hiện tại đã hỗ trợ hai ứng dụng.

Mục tiêu là tích hợp Support mà không viết lại authentication stack.

### Decision

Chỉ triển khai những thay đổi IAM cần thiết:

1. Add Support callback.
2. Add role-to-app policy.
3. Preserve HttpSession SSO.
4. Issue new JWT for Support.
5. Test three-app flows.
6. Fix security/regression bugs nếu phát hiện.

**Thực tế:** Commit `cf7b01b` đã đi xa hơn phạm vi này: thêm tự tạo tài khoản (ADR-016) và đăng xuất (ADR-019).

### Rationale

- Lower implementation cost.
- Reduced regression risk.
- Faster integration.
- Preserve developer ownership.
- Avoid unnecessary architecture changes.

### Trade-offs

- Giữ lại một số technical debt.
- Chưa có full production-grade IAM.
- Cần phân biệt MVP limitations với security blockers.

### Consequences

Không triển khai:

- Keycloak migration.
- Full OAuth2 Authorization Server.
- Refresh token infrastructure.
- Token revocation service.
- Đăng xuất giữa các app.

trong minimum integration scope nếu chưa có quyết định mới.

---

## ADR-015 — Explicit Service and Data Ownership

**Status:** IMPLEMENTED  
**Priority:** P1  
**Decision Owner:** Backend Architecture Team

### Context

Shared PostgreSQL tạo nguy cơ business services truy cập và thay đổi dữ liệu không thuộc trách nhiệm của mình.

### Decision

Mỗi service phải có ownership rõ ràng.

| Service | Owned Data | Responsibility |
|---|---|---|
| IAM | `tenant`, `app_user` | Identity, roles, Google binding |
| Catalog | `hotel`, `room_type`, inventory capacity | Hotel and room catalog |
| Booking | `booking`, inventory sold quantity | Booking lifecycle and reservations |
| Support | `date_change_request`, `notification_outbox` | Phiếu đổi ngày |
| Notification | `notification` | Thông báo trong ứng dụng |

### Rationale

- Giảm accidental coupling.
- Dễ review migrations.
- Dễ phân quyền database.
- Chuẩn bị cho khả năng tách database trong tương lai.

### Trade-offs

- Cross-service operations cần contract.
- Shared inventory có ownership chia theo column.
- Database permissions có thể phức tạp hơn.
- Schema evolution cần coordination.

### Consequences

Không thay đổi data ownership tùy tiện.

Cross-service data access cần được review và document.

---


## ADR-016 — Chính sách đăng nhập theo từng app

**Status:** IMPLEMENTED  
**Nguồn:** Code tại commit `cf7b01b`. Lý do chọn và các đánh đổi dưới đây được suy ra từ code, cần team xác nhận.

### Context

ADR-002 ban đầu yêu cầu mọi `app_user` phải được tạo trước, và tài liệu cũ cấm tự tạo tài khoản.

### Decision

| App | Chưa có tài khoản | Điều kiện nhận JWT |
|---|---|---|
| App 1 | Tạo `USER` trong `IAM_REGISTRATION_DEFAULT_TENANT_ID` | Email khác `IAM_ADMIN_EMAIL`, role `USER` |
| App Admin | Tạo `ADMIN`, chỉ cho `IAM_ADMIN_EMAIL` | Email đúng `IAM_ADMIN_EMAIL`. Tài khoản `USER` được nâng thành `ADMIN` |
| Support | Từ chối | Tài khoản đã tồn tại |

Mọi app đều yêu cầu `email_verified` và `google_sub` khớp.

### Trade-offs

**Advantages:**

- Người dùng mới vào được App 1 mà không cần seed.
- Không có bước quản trị tài khoản khi demo.

**Disadvantages:**

- Mọi Gmail đã xác minh đều có tài khoản `USER` trong tenant mặc định.
- Chỉ một email vào được App Admin, nên chỉ một tenant có người quản trị qua app này.
- ADMIN khác chỉ dùng được Support.
- Email admin là cấu hình triển khai, Compose đang đặt sẵn một email cá nhân.

### Consequences

Thay thế quy tắc "Không auto-provision users" của tài liệu cũ. Xem `OPEN-016`.

---

## ADR-017 — Support chỉ xử lý phiếu đổi ngày, gọi Booking qua HTTP

**Status:** IMPLEMENTED  
**Nguồn:** Code, `support-service/README.md`.

### Context

Support Center hiện chỉ cần chức năng xin đổi ngày. Booking là nơi duy nhất được đổi `booking` và `room_inventory.sold`.

### Decision

- Gỡ ticket và tin nhắn chung, thay bằng `date_change_request` (`support-service` `V2`).
- Support gọi Booking qua HTTP bằng chính JWT của người đang thao tác, không dùng tài khoản dịch vụ.
- Booking đổi ngày trước, phiếu ghi nhận sau. `requestId` giúp duyệt lại an toàn.
- Không giữ phòng trong lúc phiếu chờ duyệt.

### Trade-offs

- Booking tự kiểm tra role và tenant như mọi request khác, không cần cơ chế xác thực nội bộ.
- Support giữ khóa dòng phiếu và một kết nối DB trong lúc gọi Booking, tối đa khoảng 13 giây.
- Lúc duyệt vẫn có thể hết phòng.
- Support vẫn đọc trực tiếp `app_user` và `booking_reschedule`.

### Consequences

Giải quyết `OPEN-006` theo phương án B. Các câu hỏi về vòng đời ticket không còn áp dụng.

---

## ADR-018 — Notification qua transactional outbox

**Status:** IMPLEMENTED  
**Nguồn:** Code, `notification-service/README.md`.

### Decision

Support ghi `notification_outbox` cùng transaction với kết quả phiếu. Job của Notification đọc bảng này mỗi 2 giây bằng `FOR UPDATE SKIP LOCKED` và tạo `notification`. Không có API tạo thông báo, và Support không gọi Notification.

### Trade-offs

- Phiếu đã có kết quả thì chắc chắn có sự kiện, kể cả khi Notification đang tắt.
- Không cần message broker hay tài khoản dịch vụ.
- Hai service phụ thuộc cùng một bảng.
- Người dùng thấy thông báo chậm theo chu kỳ job cộng chu kỳ gọi lại 15 giây của frontend.

---

## ADR-019 — Đăng xuất chỉ hủy phiên IAM

**Status:** IMPLEMENTED  
**Nguồn:** Code tại commit `cf7b01b`. Cần team xác nhận đây là phạm vi mong muốn.

### Decision

`GET /auth/logout?app=...` hủy `HttpSession` của IAM rồi chuyển về callback kèm `#logged_out`. Chỉ App 1 có nút. Lệnh này không thu hồi JWT và không hủy phiên Google. `prompt=select_account` làm Google hiện màn hình chọn tài khoản ở lần sau.

### Trade-offs

- Đơn giản, không cần danh sách thu hồi.
- JWT ở các tab khác vẫn dùng được đến khi hết hạn.
- Là `GET` và CSRF đã tắt, nên trang khác có thể kích hoạt đăng xuất.

---

# 5. Decisions Still Open

| ID | Open Decision | Priority | Hiện trạng |
|---|---|---|---|
| OPEN-001 | Cơ chế trao JWT ở production | High | Demo dùng URL fragment. Khuyến nghị đánh giá BFF trước production |
| OPEN-002 | Đăng xuất giữa các app | High | Xem ADR-019. Cần quyết định: nút ở mọi app, báo các tab khác, thu hồi JWT, đổi `GET` thành `POST` |
| OPEN-003 | Race `sso_app` khi hai tab song song | Medium | Còn nguyên. Hướng xử lý: gắn app đích với từng authorization request |
| OPEN-004 | RSA key bền vững và xoay vòng | High | Key sinh lại mỗi lần khởi động, `kid` cố định. Đã đo hệ quả, xem [iam-sso.md](iam-sso.md#81-quản-lý-key). Nên sửa `kid` trước, ngay cả ở demo |
| OPEN-005 | Một người thuộc nhiều tenant | Medium | `app_user` chỉ có một `tenant_id` |
| OPEN-010 | Chốt API contract | Medium | [api-contract.md](api-contract.md) mô tả API hiện có. Chưa có sign-off và chưa có phiên bản API |
| OPEN-014 | Có cần `aud` trong JWT | Medium | JWT chưa có `aud`, service chưa kiểm tra |
| OPEN-015 | Thu hồi JWT, role cũ | High for production | Support và Notification đã đối chiếu role với DB. Catalog và Booking tin JWT đến khi hết hạn |
| OPEN-016 | Ai được vào App Admin | High | Xem ADR-016. Cần quyết định có giữ một email duy nhất không, và cách hỗ trợ tenant khác |
| OPEN-017 | Ai được tự đăng ký | Medium | App 1 nhận mọi Gmail đã xác minh. Cần chốt trước khi mở rộng |

## 6. Decisions Resolved

| ID | Câu hỏi | Kết quả |
|---|---|---|
| OPEN-006 | Support xác minh quyền với đơn thế nào | Gọi Booking qua HTTP bằng JWT của người thao tác (ADR-017) |
| OPEN-007 | Vòng đời ticket | Không còn áp dụng, ticket đã bị gỡ |
| OPEN-008 | Lưu trữ và SLA của ticket | Không còn áp dụng ở dạng cũ. Chính sách lưu trữ dữ liệu vẫn chưa có |
| OPEN-009 | Cổng và route của Support | Cổng `8085`, route `/api/support/**` |
| OPEN-011 | Lịch sử Flyway | Mỗi service một bảng `flyway_schema_history_*` |
| OPEN-012 | Phiên bản công cụ | Java 21, Spring Boot 3.3.5, Spring Cloud 2023.0.3, Gradle 9.8.2, PostgreSQL 16 |
| OPEN-013 | Booking còn tham chiếu `room` cũ | Đã hết. Booking `V1` dùng `room_type`, migration chạy thành công trên DB mới |

---

# 7. Rejected or Deferred Alternatives

| Alternative | Decision | Reason |
|---|---|---|
| Replace IAM with Keycloak | Rejected for current scope | Unnecessary migration and regression risk |
| Add SUPPORT role | Deferred | USER/ADMIN sufficient for MVP |
| Separate databases for Booking and Inventory | Deferred | Requires distributed consistency strategy |
| Physical-room assignment | Deferred | Beyond MVP |
| Real payment gateway | Deferred | Scope control |
| Realtime support chat | Deferred | Ngoài phạm vi |
| Ticket hỗ trợ chung | Removed | Support Center hiện chỉ cần đổi ngày (ADR-017) |
| Full OIDC Authorization Server | Deferred | Existing IAM integration is priority |
| Immediate migration to `booking.user_id` | Deferred | Preserve current Booking contract |
| JWT sharing between frontend apps | Rejected | Apps should obtain tokens through IAM |
| Frontend-only authorization | Rejected | Backend must enforce RBAC, tenant and ownership |

---

# 8. Architecture Invariants

Các invariants sau phải được bảo toàn trong implementation.

## 8.1. Authentication

```text
Google OIDC
    |
    v
Custom IAM
    |
    v
StayHub JWT RS256
```

- Không dùng Google ID Token trực tiếp cho business APIs.
- Chỉ tạo tài khoản theo chính sách ở ADR-016.
- Không bỏ qua `google_sub` binding.
- Không phát JWT cho app trái role.

## 8.2. Authorization

```text
Valid JWT
    +
Correct Role
    +
Correct Tenant
    +
Correct Ownership
```

- Gateway và service đều phải bảo vệ APIs.
- Không tin tenant hoặc owner từ request body.
- Không để lộ cross-tenant resources.

## 8.3. Booking Inventory

```text
0 <= sold <= total
```

- Booking và stock hold phải atomic.
- Không oversell.
- Cancel/expiry release đúng một lần.
- Payment confirmation không tăng stock lần hai.

## 8.4. Data Integrity

- Applied Flyway migrations immutable.
- Tenant-scoped data access.
- Foreign keys và indexes được xác minh.
- Shared database không đồng nghĩa shared ownership.

## 8.5. Support

- USER chỉ thao tác trên phiếu và đơn của mình.
- ADMIN chỉ thao tác trong tenant.
- Mỗi đơn tối đa một phiếu `PENDING`.
- Chỉ Booking được đổi `booking` và `sold`.
- Phiếu đã có kết quả thì chắc chắn có sự kiện trong `notification_outbox`.

---

# 9. Architecture Decision Dependencies

```mermaid
flowchart TD
    A["ADR-002<br/>Custom IAM"]
    B["ADR-003<br/>Three-App SSO"]
    C["ADR-004<br/>Authorization"]
    D["ADR-010<br/>Independent JWT Validation"]

    E["ADR-005<br/>Shared PostgreSQL"]
    F["ADR-006<br/>Nightly Inventory"]
    G["ADR-013<br/>Atomic Reservation"]

    H["ADR-017<br/>Support đổi ngày"]
    K["ADR-018<br/>Outbox"]
    L["ADR-016<br/>Chính sách theo app"]
    I["ADR-009<br/>Flyway"]
    J["ADR-015<br/>Data Ownership"]

    A --> B
    A --> D
    B --> C
    D --> C

    E --> F
    F --> G

    C --> H
    E --> I
    I --> H
    J --> H
    J --> G
    H --> K
    A --> L
    L --> B
```

---

# 10. G2 Architecture Sign-off Checklist

## Authentication & IAM

- [ ] Custom IAM được giữ nguyên.
- [ ] Không sử dụng Keycloak.
- [ ] Google OIDC + PKCE S256 được giữ nguyên.
- [ ] JWT RS256 contract được thống nhất.
- [ ] `app1`, `app2`, `app3` được thống nhất.
- [ ] Chính sách theo app ở ADR-016 được phê duyệt.
- [ ] Support callback được phê duyệt.

## Backend Architecture

- [ ] Gateway routing được thống nhất.
- [ ] Independent JWT verification được thống nhất.
- [ ] Tenant isolation được thống nhất.
- [ ] Booking ownership contract được giữ nguyên.
- [ ] Cách Support gọi Booking ở ADR-017 được phê duyệt.

## Database & Inventory

- [ ] Shared PostgreSQL được chấp thuận cho MVP.
- [ ] Nightly inventory model được chấp thuận.
- [ ] Atomic booking transaction được chấp thuận.
- [ ] Flyway ownership được thống nhất.
- [ ] Thứ tự migration của 5 service được phê duyệt.

## Support

- [ ] Luồng phiếu đổi ngày được phê duyệt.
- [ ] API Support và Notification được phê duyệt.
- [ ] Quyền USER và ADMIN được phê duyệt.
- [ ] Chính sách lưu trữ dữ liệu được xác định.

## Operations & Security

- [ ] Demo-only JWT fragment limitation được ghi nhận.
- [ ] Giới hạn đăng xuất ở ADR-019 được ghi nhận.
- [ ] RSA key persistence requirement được ghi nhận.
- [ ] Multi-tab session race được đánh giá.
- [ ] Production hardening backlog được xác định.

---

# 11. Decision Change Control

Mỗi thay đổi kiến trúc quan trọng phải được ghi nhận theo mẫu:

```markdown
## ADR-XXX — Decision Title

**Status:** PROPOSED
**Date:** YYYY-MM-DD
**Owner:** Team / Developer

### Context

Vấn đề cần giải quyết.

### Decision

Quyết định được lựa chọn.

### Alternatives Considered

Các phương án đã đánh giá.

### Rationale

Lý do lựa chọn.

### Trade-offs

Ưu điểm và hạn chế.

### Consequences

Tác động đến hệ thống.

### Acceptance Criteria

Điều kiện nghiệm thu.

### References

Tài liệu liên quan.
```

### Change Rules

- Không thay đổi ADR đã ACCEPTED mà không review tác động.
- Nếu thay thế quyết định, tạo ADR mới và đánh dấu ADR cũ là SUPERSEDED.
- Không đánh dấu IMPLEMENTED nếu chưa có evidence.
- Breaking changes cần migration và rollback strategy.
- Security-sensitive changes cần security review.

---

# 12. Documentation References

| Document | Responsibility |
|---|---|
| `../README.md` | Project documentation overview |
| `architecture.md` | System architecture |
| `iam-sso.md` | Custom IAM and SSO |
| `iam-integration-tasks.md` | Việc đã xong và việc còn lại của IAM |
| `iam-testing.md` | Kiểm thử IAM và SSO |
| `api-contract.md` | API contracts and authorization |
| `data-model.md`, `erd.md` | Quy tắc dữ liệu và schema |
| `../stayhub-sequence-diagrams.md` | End-to-end workflows |
| `operations-and-tests.md` | Operations, migrations and testing |

---

# 13. Final Architecture Status

| Mảng | Hiện trạng |
|---|---|
| Kiến trúc | Ba frontend, Custom IAM, Spring Boot microservices, PostgreSQL dùng chung |
| Xác thực | Google OIDC, Authorization Code + PKCE S256, HttpSession SSO, chính sách theo app |
| Phân quyền | JWT RS256, JWKS, RBAC, tách tenant, quyền sở hữu |
| Đặt phòng | Theo loại phòng, tồn kho từng đêm, giữ phòng trong transaction, thanh toán mô phỏng |
| Hỗ trợ | Phiếu đổi ngày, thông báo qua outbox |
| Migration | Flyway, không sửa migration đã áp dụng |
| Keycloak | Không dùng |
| Sign-off G2, G3, G4 | Chưa có biên bản trong repository |
| Production readiness | Chưa đạt |

---

## 14. Final Decision Summary

StayHub ưu tiên **tái sử dụng kiến trúc hiện tại, giảm integration complexity và bảo đảm data consistency**.

Các quyết định trọng tâm:

1. Giữ Custom IAM với Google OIDC, PKCE S256, HttpSession SSO và JWT RS256.
2. Mở rộng từ hai frontend lên ba frontend mà không thay đổi authentication stack, và áp dụng chính sách đăng nhập theo từng app.
3. Enforce RBAC, tenant isolation và resource ownership tại backend.
4. Sử dụng shared PostgreSQL cho Booking và Inventory trong MVP để bảo đảm atomic transactions.
5. Bán phòng theo room type và nightly inventory thay vì physical-room assignment.
6. Giữ mock payment và phiếu đổi ngày để kiểm soát phạm vi MVP.
7. Bảo toàn existing IAM/Booking contracts và Flyway migration history.
8. Chỉ mở rộng kiến trúc khi có requirement, evidence và quyết định được phê duyệt.

**Architecture principle:** Minimum necessary complexity, explicit security boundaries, transactional correctness, measurable acceptance criteria.
