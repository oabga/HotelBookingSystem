# Plan: Custom IAM service (OAuth2 Client to Google) cho demo multi-tenant đơn giản

Cập nhật: 2026-11-02

> Đây là bản plan cho một **demo thu hẹp phạm vi** so với `team-plan.md` và `StayHub_Execution_Roadmap_ABCD_3Days.md`: bỏ hẳn booking/payment/notification, chỉ còn IAM tự dựng (OAuth2 với Google) + quản lý phòng (xem/thêm/sửa giá) theo tenant.

## Phạm vi

- 2 tenant dùng chung một platform (không clone project khi thêm tenant mới).
- 2 role: `USER` (chỉ xem phòng của tenant mình) và `ADMIN` (thêm phòng, sửa giá của tenant mình).
- Authentication/authorization: **tự viết `iam-service`** đóng vai trò OAuth2 Client với Google, tự mint JWT nội bộ có `tenant_id` + `role`.
- Tenant isolation bắt buộc: user/admin của tenant A không đọc/sửa được dữ liệu tenant B.

**Bỏ khỏi phạm vi so với plan cũ:** `booking-service`, `notification-service`, Keycloak, PKCE-client-pages, idempotency, luồng đặt phòng/thanh toán/push.

## Kiến trúc

```
Browser ──login──> iam-service ──OAuth2──> Google
Browser ──Bearer JWT──> api-gateway ──> catalog-service (rooms, tenant_id)
api-gateway/catalog-service ──verify JWT──> iam-service /.well-known/jwks.json
```

Giữ lại và sửa: `api-gateway`, `common-security`, `catalog-service` (dùng cho CRUD phòng). Thêm mới: `iam-service`.

## Mô hình dữ liệu (iam_db)

| Bảng | Cột chính |
|---|---|
| `tenant` | `id`, `name` |
| `app_user` | `id`, `email` (unique), `tenant_id`, `role` (`USER`/`ADMIN`), `google_sub` |

Không có flow tự đăng ký: mỗi email Google phải được **pre-seed** vào `app_user` kèm tenant + role trước. Email Google đăng nhập mà không có trong bảng → **403, không tự tạo user mặc định** — đây là chốt isolation quan trọng nhất; nếu auto-provision thì không kiểm soát được user rơi vào tenant nào.

## `iam-service` — việc cần làm

1. Module Gradle mới, add vào `settings.gradle`.
2. `spring-boot-starter-oauth2-client` cấu hình Google (client-id/secret từ Google Cloud Console, redirect URI cố định).
3. Flyway V1: `tenant`, `app_user` + seed 2 tenant × 2 user (1 `USER`, 1 `ADMIN` mỗi tenant, dùng email Gmail thật để test được).
4. `OAuth2LoginSuccessHandler` tùy biến:
   - Lấy email từ ID token Google.
   - Tra `app_user`; không có → trả 403.
   - Có → mint **JWT nội bộ** (RS256), claims: `sub`, `email`, `tenant_id`, `role`.
   - Redirect về frontend kèm token (`http://localhost:3000/callback#token=...`) — dùng URL fragment để token không vào access log server, nhưng ghi rõ đây là simplification cho demo (không có refresh token/revocation).
5. Endpoint `GET /.well-known/jwks.json` publish public key cho các service khác verify.
6. Key: sinh RSA keypair tĩnh lúc khởi động hoặc nạp từ file — đủ cho demo, không cần rotation.

## `common-security` — sửa lại

- Đổi `jwk-set-uri` trỏ từ Keycloak sang `iam-service` JWKS endpoint.
- Converter: map claim `role` → `ROLE_USER`/`ROLE_ADMIN`, claim `tenant_id` → `TenantContext`.
- Giữ nguyên nguyên tắc: JWT thiếu `tenant_id` → reject.

## `catalog-service` — rooms CRUD

- Flyway V1: bảng `room` (`id`, `tenant_id`, `name`, `price`), seed vài phòng/tenant.
- `GET /rooms` — `USER` hoặc `ADMIN`, luôn filter theo `tenant_id` lấy từ JWT (không nhận tenant từ query param).
- `POST /rooms` — chỉ `ADMIN`, `tenant_id` gán từ JWT, không nhận từ body.
- `PUT /rooms/{id}/price` — chỉ `ADMIN`, phải kiểm tra `room.tenant_id == JWT.tenant_id` trước khi sửa, sai thì 404 (không lộ là phòng tồn tại ở tenant khác).

## `api-gateway`

- Route `/auth/**` → `iam-service` (không cần JWT, đây là login flow).
- Route `/api/rooms/**` → `catalog-service` (cần JWT hợp lệ).

## Frontend demo tối giản

Một trang HTML: nút "Login with Google" → redirect iam-service → callback đọc token từ URL fragment → lưu trong memory → gọi `GET /api/rooms` hiển thị danh sách; nếu `role=ADMIN` hiện thêm form thêm phòng/sửa giá.

## Sequence diagram 1 — Login & phát JWT

```mermaid
sequenceDiagram
    participant Browser
    participant IAM as iam-service
    participant Google
    participant DB as iam_db

    Browser->>IAM: GET /oauth2/authorization/google
    IAM->>Google: Redirect tới Google consent
    Browser->>Google: Đăng nhập + đồng ý
    Google-->>Browser: Redirect về IAM callback kèm code
    Browser->>IAM: GET /login/oauth2/code/google?code=...
    IAM->>Google: Đổi code lấy id_token
    Google-->>IAM: id_token (email, sub)
    IAM->>DB: SELECT app_user WHERE email = ?
    alt Chưa được provision
        DB-->>IAM: not found
        IAM-->>Browser: 403 Forbidden
    else Đã có trong hệ thống
        DB-->>IAM: tenant_id, role
        IAM->>IAM: Mint JWT (sub, email, tenant_id, role), ký RS256
        IAM-->>Browser: Redirect frontend#token=<jwt>
    end
```

## Sequence diagram 2 — Xem phòng (USER) & thêm/sửa phòng (ADMIN) có tenant isolation

```mermaid
sequenceDiagram
    participant Browser
    participant Gateway as api-gateway
    participant Catalog as catalog-service
    participant JWKS as iam-service (jwks)
    participant DB as catalog_db

    Browser->>Gateway: GET /api/rooms  (Bearer JWT)
    Gateway->>Catalog: forward + JWT
    Catalog->>JWKS: verify signature (cache public key)
    Catalog->>Catalog: extract tenant_id, role
    Catalog->>DB: SELECT * FROM room WHERE tenant_id = ?
    DB-->>Catalog: rooms của đúng tenant
    Catalog-->>Browser: 200 OK

    Browser->>Gateway: POST /api/rooms {...}  (Bearer JWT)
    Gateway->>Catalog: forward + JWT
    Catalog->>Catalog: role == ADMIN ?
    alt role = USER
        Catalog-->>Browser: 403 Forbidden
    else role = ADMIN
        Catalog->>DB: INSERT room (tenant_id từ JWT)
        DB-->>Catalog: ok
        Catalog-->>Browser: 201 Created
    end

    Browser->>Gateway: PUT /api/rooms/{id}/price (Bearer JWT, tenant khác)
    Gateway->>Catalog: forward + JWT
    Catalog->>DB: SELECT room WHERE id = ? AND tenant_id = JWT.tenant_id
    DB-->>Catalog: not found (room thuộc tenant khác)
    Catalog-->>Browser: 404 Not Found
```

## Thứ tự implement đề xuất

1. `iam-service` skeleton + Google OAuth2 + seed data + mint JWT + JWKS.
2. `common-security` đổi sang verify qua JWKS của `iam-service`.
3. `catalog-service` rooms CRUD + tenant/role enforcement.
4. `api-gateway` route 2 service trên.
5. Frontend demo 1 trang.
6. Test tay: 2 tenant × (user, admin) login thật bằng Gmail, xác nhận isolation.

## Điểm cần chốt / có thể đổi

- **Chính sách provisioning:** hiện chốt là pre-seed thủ công, từ chối email lạ. Nếu muốn tự động tạo user mới khi lần đầu login thì cần quy tắc gán tenant (ví dụ theo domain email) — chưa chốt trong bản này.
- **Cơ chế giao token về frontend:** hiện dùng URL fragment redirect, không có refresh token/revocation — là simplification cho demo, không dùng cho production.
