
# StayHub — IAM Testing & SSO Acceptance

> **Version:** 3-App Architecture + Custom IAM  
> **Updated:** 2026-11-04  
> **Status:** TEST PLAN — NOT EXECUTED  
> **Scope:** IAM Service, Google OIDC, PKCE, JWT/JWKS, SSO, RBAC, Tenant Isolation  
> **Architecture:** Custom Spring Security IAM — **No Keycloak**

---

## 1. Testing Objectives

Kiểm chứng IAM hiện tại đáp ứng ba ứng dụng:

| Application | URL | IAM App ID | Allowed Role |
|---|---|---|---|
| Booking Web | `http://localhost:3000` | `app1` | `USER` |
| Admin Dashboard | `http://localhost:3001` | `app2` | `ADMIN` |
| Support Center | `http://localhost:3002` | `support` | `USER`, `ADMIN` |

### Acceptance Goals

1. Google OAuth2/OIDC Authorization Code + PKCE S256 hoạt động.
2. IAM chỉ chấp nhận tài khoản đã được provision.
3. Google identity được liên kết an toàn qua `google_sub`.
4. IAM phát JWT RS256 với claims chính xác.
5. Gateway và business services xác minh JWT độc lập.
6. SSO hoạt động giữa Booking → Support và Admin → Support.
7. Role-to-app authorization được thực thi.
8. Tenant isolation và resource ownership được bảo vệ.
9. Không làm lộ token, cookie hoặc credentials trong quá trình test.
10. Không đánh dấu PASS khi chưa có evidence thực tế.

---

## 2. Environment Preparation

### 2.1. Development Services

| Service | Port | Status |
|---|---|---|
| IAM Service | `8084` | Existing |
| API Gateway | `8080` | Existing |
| Catalog Service | `8081` | Existing |
| Booking Service | `8082` | Existing; migration cần xác minh |
| Support Service | TBD | Proposed |
| PostgreSQL | `5432` | Development example |

Database:

```text
hotelbooking
```

### 2.2. Start PostgreSQL

```bash
cd backend
docker compose up -d postgres
```

Kiểm tra:

```bash
docker compose ps
```

Chỉ sử dụng tên service `postgres` nếu khớp với `docker-compose.yml` thực tế.

### 2.3. Start IAM Service

Terminal 1:

```bash
cd backend

source .env.local

./gradlew :iam-service:bootRun
```

### 2.4. Start Catalog Service

Terminal 2:

```bash
cd backend

./gradlew :catalog-service:bootRun
```

### 2.5. Start API Gateway

Terminal 3:

```bash
cd backend

./gradlew :api-gateway:bootRun
```

### 2.6. Start Booking Service

Chỉ thực hiện sau khi xác minh và xử lý migration tham chiếu bảng `room` cũ.

```bash
cd backend

./gradlew :booking-service:bootRun
```

### 2.7. Environment Checklist

- [ ] PostgreSQL hoạt động.
- [ ] IAM Service khởi động thành công.
- [ ] Google OAuth2 configuration hợp lệ.
- [ ] IAM kết nối PostgreSQL thành công.
- [ ] `app_user` và `tenant` tồn tại.
- [ ] Test accounts được provision.
- [ ] Gateway hoạt động.
- [ ] Catalog Service hoạt động.
- [ ] Booking migration được xác minh.
- [ ] Support callback được cấu hình.
- [ ] Support Service hoạt động nếu đã triển khai.

---

## 3. Test Account Strategy

### 3.1. Required Accounts

| Account | Tenant | Role | Purpose |
|---|---|---|---|
| `user_t1` | Tenant 1 | USER | Booking + Support |
| `admin_t1` | Tenant 1 | ADMIN | Admin + Support |
| `user_t2` | Tenant 2 | USER | Tenant isolation |
| `admin_t2` | Tenant 2 | ADMIN | Tenant isolation |
| `unprovisioned` | None | None | Login rejection |

Tên trên là test identifiers, không phải Google accounts thực tế.

### 3.2. Provisioning Rules

IAM hiện tại sử dụng:

```text
app_user
    |
    +-- id
    +-- tenant_id
    +-- email
    +-- role
    +-- google_sub
```

Login phải kiểm tra:

- Google identity hợp lệ.
- `email_verified = true`.
- Email tồn tại trong `app_user`.
- `google_sub` được bind/check theo policy IAM hiện tại.
- Role hợp lệ.
- Tenant hợp lệ.

Không tự động tạo `app_user` khi Google login.

### 3.3. Test Data Safety

Không thực hiện:

```sql
DELETE FROM app_user;
```

trên shared database.

Để test unprovisioned account:

- Sử dụng Google test account chưa được provision.
- Hoặc sử dụng disposable test database.
- Không thay đổi identity production.
- Không ghi Google credentials vào source code.

---

## 4. IAM Endpoint Testing

### 4.1. Login Endpoints

| Endpoint | Expected |
|---|---|
| `/auth/start?app=app1` | Valid Booking login entry |
| `/auth/start?app=app2` | Valid Admin login entry |
| `/auth/start?app=support` | Valid Support login entry |
| `/auth/start?app=invalid` | `400` |
| `/auth/start` | `400` |

Support endpoint là phần mở rộng cần triển khai hoặc xác minh.

### 4.2. Invalid App Tests

```bash
IAM=http://localhost:8084

for url in \
  "$IAM/auth/start" \
  "$IAM/auth/start?app=invalid"
do
  curl -sS -o /dev/null \
    -w "%{http_code}\n" \
    "$url"
done
```

Expected:

```text
400
400
```

Không redirect sang Google khi app identifier không hợp lệ.

### 4.3. Valid App Tests

```bash
IAM=http://localhost:8084

for app in app1 app2 support
do
  echo "Testing app=$app"

  curl -sS -o /dev/null \
    -w "%{http_code} %{redirect_url}\n" \
    "$IAM/auth/start?app=$app"
done
```

Expected:

- App hợp lệ được chấp nhận.
- Có thể trả redirect `302`.
- Không redirect đến callback ngoài allowlist.
- Khi đã authenticated, IAM phải kiểm tra role trước khi phát JWT.

Không kết luận role authorization chỉ dựa vào HTTP redirect của request chưa đăng nhập.

---

## 5. PKCE S256 Testing

### 5.1. Objective

Xác minh IAM sử dụng:

```text
response_type=code
code_challenge=<challenge>
code_challenge_method=S256
```

### 5.2. Authorization Redirect Test

```bash
IAM=http://localhost:8084

COOKIE_JAR=$(mktemp)

curl -sS \
  -D - \
  -o /dev/null \
  -c "$COOKIE_JAR" \
  -b "$COOKIE_JAR" \
  "$IAM/auth/start?app=app1"
```

Kiểm tra redirect location.

Nếu `/auth/start` chuyển qua Spring Security OAuth2 authorization endpoint, kiểm tra tiếp:

```bash
IAM=http://localhost:8084

COOKIE_JAR=$(mktemp)

curl -sS \
  -D - \
  -o /dev/null \
  -c "$COOKIE_JAR" \
  -b "$COOKIE_JAR" \
  "$IAM/auth/start?app=app1"

LOCATION=$(
  curl -sS \
    -D - \
    -o /dev/null \
    -c "$COOKIE_JAR" \
    -b "$COOKIE_JAR" \
    "$IAM/oauth2/authorization/google" \
  | grep -i '^location:' \
  | tr -d '\r'
)

printf '%s\n' "$LOCATION" \
  | grep -q 'code_challenge=' \
  && echo "PKCE challenge: PRESENT" \
  || echo "PKCE challenge: MISSING"

printf '%s\n' "$LOCATION" \
  | grep -q 'code_challenge_method=S256' \
  && echo "PKCE method: S256" \
  || echo "PKCE method: INVALID"

rm -f "$COOKIE_JAR"
```

**Lưu ý:** Đoạn test trên giả định endpoint `/oauth2/authorization/google` hoạt động độc lập và cùng cookie jar. Nếu IAM sử dụng custom authorization request resolver, cần theo đúng redirect chain thực tế.

### 5.3. PKCE Acceptance

- [ ] Authorization request sử dụng `response_type=code`.
- [ ] Có `code_challenge`.
- [ ] Có `code_challenge_method=S256`.
- [ ] Có `state`.
- [ ] Callback xác minh đúng authorization response.
- [ ] Code exchange sử dụng `code_verifier`.
- [ ] Không sử dụng implicit flow.

Terminal chỉ kiểm tra authorization request. Browser E2E và integration tests mới xác minh đầy đủ code exchange.

---

## 6. Google OIDC Login Testing

### 6.1. USER Login

1. Mở browser profile mới.
2. Truy cập:

```text
http://localhost:8084/auth/start?app=app1
```

3. Đăng nhập Google bằng USER đã provision.
4. IAM xác minh Google identity.
5. IAM tìm `app_user`.
6. IAM phát StayHub JWT.
7. Browser redirect về Booking callback.

Expected callback pattern:

```text
http://localhost:3000/callback/#token=<JWT>
```

Frontend phải:

- Đọc JWT từ URL fragment.
- Xóa fragment khỏi browser URL.
- Giữ JWT trong memory.
- Kiểm tra trạng thái đăng nhập.
- Không ghi token vào console hoặc analytics.

### 6.2. ADMIN Login

Truy cập:

```text
http://localhost:8084/auth/start?app=app2
```

Expected callback:

```text
http://localhost:3001/callback/#token=<JWT>
```

JWT phải chứa:

```text
role = ADMIN
```

### 6.3. Negative Login Tests

| Test | Expected |
|---|---|
| Unprovisioned account | Denied |
| `email_verified = false` | Denied |
| `google_sub` mismatch | Denied |
| USER login Admin | `403` |
| ADMIN login Booking | `403` |
| Invalid app identifier | `400` |
| Invalid Google authorization response | Denied |

Các test phải xác minh không có StayHub JWT được phát khi login bị từ chối.

---

## 7. JWT & JWKS Testing

### 7.1. JWKS Endpoint

```http
GET http://localhost:8084/.well-known/jwks.json
```

### 7.2. Inspect JWKS

```bash
curl -fsS \
  http://localhost:8084/.well-known/jwks.json \
  | python3 -m json.tool
```

Expected public RSA fields:

```text
kty = RSA
kid = present
n = present
e = present
```

Private RSA fields không được xuất hiện:

```text
d
p
q
dp
dq
qi
oth
```

### 7.3. Automated JWKS Security Check

```bash
curl -fsS \
  http://localhost:8084/.well-known/jwks.json \
  | python3 -c '
import json
import sys

jwks = json.load(sys.stdin)
keys = jwks.get("keys", [])

private_fields = {
    "d", "p", "q", "dp", "dq", "qi", "oth"
}

if not keys:
    raise SystemExit("FAIL: JWKS contains no keys")

for key in keys:
    if key.get("kty") != "RSA":
        raise SystemExit("FAIL: Unexpected key type")

    for field in ("kid", "n", "e"):
        if not key.get(field):
            raise SystemExit(
                f"FAIL: Missing public field {field}"
            )

    exposed = private_fields.intersection(key.keys())

    if exposed:
        raise SystemExit(
            f"FAIL: Private RSA fields exposed: {exposed}"
        )

print("PASS: Public RSA JWKS validated")
'
```

### 7.4. JWT Claims

JWT do IAM phát phải có:

| Claim | Expected |
|---|---|
| `iss` | `http://localhost:8084` |
| `sub` | `app_user.id` |
| `email` | Provisioned user email |
| `tenant_id` | Numeric tenant ID |
| `role` | `USER` hoặc `ADMIN` |
| `iat` | Issued timestamp |
| `exp` | Expiration timestamp |

Theo tài liệu developer, token TTL là 15 phút.

### 7.5. JWT Validation Matrix

| Scenario | Expected |
|---|---|
| Valid RS256 signature | Accepted |
| Modified payload | `401` |
| Modified signature | `401` |
| Expired JWT | `401` |
| Invalid issuer | `401` |
| Missing required role | Rejected |
| Missing tenant ID | Rejected |
| Invalid tenant ID type | Rejected |
| Unsupported signing algorithm | Rejected |
| Unknown `kid` | Rejected |
| Invalid audience, if required | Rejected |

Không mặc định JWT có `aud` nếu IAM developer chưa triển khai claim này. Nếu service yêu cầu audience validation, IAM và resource services phải thống nhất contract trước khi test.

---

## 8. Gateway JWT Testing

### 8.1. Missing JWT

```bash
curl -sS \
  -o /dev/null \
  -w "%{http_code}\n" \
  http://localhost:8080/api/hotels
```

Expected:

```text
401
```

Điều kiện: Endpoint này được cấu hình là protected. Nếu thực tế public, phải dùng một protected endpoint khác để test.

### 8.2. Invalid JWT

```bash
curl -sS \
  -o /dev/null \
  -w "%{http_code}\n" \
  http://localhost:8080/api/hotels \
  -H "Authorization: Bearer invalid.jwt.token"
```

Expected:

```text
401
```

### 8.3. Valid JWT

Nhập JWT test trực tiếp vào shell, không lưu trong file:

```bash
read -r -s -p "JWT: " JWT
echo
```

Gọi Gateway:

```bash
curl -sS \
  -o /dev/null \
  -w "%{http_code}\n" \
  http://localhost:8080/api/hotels \
  -H "Authorization: Bearer $JWT"
```

Expected:

```text
200
```

Nếu endpoint tồn tại, USER được phép truy cập và dữ liệu hợp lệ.

### 8.4. Cleanup

```bash
unset JWT
```

Không sử dụng `set -x` khi chạy các lệnh chứa JWT.

---

## 9. Independent Service JWT Validation

### 9.1. Objective

Gateway không phải lớp bảo mật duy nhất.

Các business services phải tự xác minh:

- JWT signature.
- Issuer.
- Expiration.
- Required claims.
- Role.
- Tenant.
- Ownership.

### 9.2. Catalog Direct Call

```bash
curl -sS \
  -o /dev/null \
  -w "%{http_code}\n" \
  http://localhost:8081/hotels
```

Expected:

```text
401
```

Endpoint direct path cần đối chiếu với Catalog controller mapping thực tế.

### 9.3. Booking Direct Call

```bash
curl -sS \
  -o /dev/null \
  -w "%{http_code}\n" \
  http://localhost:8082/bookings
```

Expected:

```text
401
```

Chỉ chạy khi Booking Service đã khởi động và endpoint mapping được xác nhận.

### 9.4. Direct Service Test Matrix

| Test | Gateway | Catalog | Booking | Support |
|---|---|---|---|---|
| Missing JWT | Reject | Reject | Reject | Reject |
| Invalid signature | Reject | Reject | Reject | Reject |
| Expired JWT | Reject | Reject | Reject | Reject |
| Wrong role | Reject where applicable | Reject | Reject | Reject |
| Cross-tenant access | Reject or delegate | Reject | Reject | Reject |
| Ownership violation | Reject or delegate | N/A | Reject | Reject |

Business services phải tự bảo vệ ngay cả khi request không đi qua Gateway.

---

## 10. Role-to-App Authorization Testing

### 10.1. Access Matrix

| Role | Booking | Admin | Support |
|---|---|---|---|
| USER | Allow | Deny | Allow |
| ADMIN | Deny | Allow | Allow |

### 10.2. USER → Admin

Steps:

1. Đăng nhập bằng USER đã provision.
2. Truy cập:

```text
http://localhost:8084/auth/start?app=app2
```

Expected:

- IAM xác định role USER.
- IAM từ chối app Admin.
- HTTP `403` hoặc controlled denial response theo contract.
- Không phát JWT cho Admin callback.
- Admin APIs tiếp tục từ chối USER.

### 10.3. ADMIN → Booking

Steps:

1. Đăng nhập bằng ADMIN.
2. Truy cập:

```text
http://localhost:8084/auth/start?app=app1
```

Expected:

- IAM xác định role ADMIN.
- IAM từ chối Booking.
- Không phát JWT cho Booking callback.
- Booking creation/payment APIs tiếp tục từ chối ADMIN.

### 10.4. Support Access

| Actor | Expected |
|---|---|
| USER | Support USER interface |
| ADMIN | Support ADMIN interface |
| Unauthenticated | Redirect to IAM |
| Invalid role | Denied |

Không thêm role `SUPPORT` trong MVP.

---

## 11. Cross-App SSO Testing

### 11.1. USER: Booking → Support

**Test ID:** `IAM-SSO-USER-001`

Steps:

1. Mở browser profile mới.
2. Login Booking bằng USER.
3. Xác minh Booking hoạt động.
4. Mở Support Center trong cùng browser profile.
5. Support gọi `/auth/start?app=support`.
6. IAM tái sử dụng HttpSession.
7. IAM phát JWT mới.
8. Support hiển thị giao diện USER.

Expected:

```text
Google re-authentication prompt: NONE
IAM session: REUSED
Support JWT: NEWLY ISSUED
Role: USER
Tenant: SAME
```

Không so sánh hoặc ghi lại toàn bộ JWT trong report. Có thể kiểm tra `iat`, `exp` và flow phát token bằng evidence đã che dữ liệu nhạy cảm.

### 11.2. ADMIN: Dashboard → Support

**Test ID:** `IAM-SSO-ADMIN-001`

Steps:

1. Mở browser profile mới.
2. Login Admin bằng ADMIN.
3. Xác minh Admin Dashboard hoạt động.
4. Mở Support Center.
5. IAM tái sử dụng HttpSession.
6. IAM phát JWT mới.
7. Support hiển thị tenant ticket queue.

Expected:

```text
Google re-authentication prompt: NONE
IAM session: REUSED
Support JWT: NEWLY ISSUED
Role: ADMIN
Tenant: SAME
```

### 11.3. SSO Negative Tests

| Scenario | Expected |
|---|---|
| IAM session expired | Google login may be required |
| JWT expired, IAM session valid | New IAM login flow can issue JWT |
| USER opens Admin | Denied |
| ADMIN opens Booking | Denied |
| Unprovisioned account | Denied |
| Invalid app callback | Denied |
| Different browser profile | Independent session |

### 11.4. Multi-Tab SSO Race

Known risk:

```text
HttpSession attribute: sso_app
```

Scenario:

1. Tab A starts Booking login.
2. Tab B starts Support login.
3. Both share IAM HttpSession.
4. Complete Google callback.
5. Verify redirect target.

Expected target behavior:

- Booking request redirects to Booking.
- Support request redirects to Support.
- App destination cannot be overwritten by another login request.

Nếu current implementation không đạt, ghi nhận `KNOWN ISSUE`.

Recommended future solution:

- Bind app destination to authorization request/state.
- Avoid one mutable session-wide app selector.
- Add automated multi-tab regression test.

---

## 12. Booking Authorization Testing

### 12.1. Booking Role Policy

| Action | USER | ADMIN |
|---|---|---|
| Create Booking | Allow | Deny |
| View Own Booking | Allow | Deny |
| Mock Payment | Own Booking | Deny |
| Cancel Booking | Own Booking | Deny |
| View Tenant Bookings via Admin API | Deny | Allow |

### 12.2. Booking Ownership Tests

| Test | Expected |
|---|---|
| USER reads own booking | `200` |
| USER reads another USER's booking | `404` |
| USER reads booking from another tenant | `404` |
| ADMIN uses USER checkout API | `403` |
| Invalid JWT | `401` |

Booking ownership hiện tại sử dụng:

```text
booking.user_email
```

Kết hợp:

```text
tenant_id from verified JWT
email from verified JWT
```

Không tự ý thay bằng `booking.user_id` khi chưa có migration riêng.

---

## 13. Support Authorization Testing

### 13.1. USER Ticket Tests

| Scenario | Expected |
|---|---|
| USER creates ticket | `201` |
| USER reads own ticket | `200` |
| USER reads another USER's ticket | `404` |
| USER links own booking | Allowed |
| USER links another USER's booking | `404` |
| USER links cross-tenant booking | `404` |
| USER attempts ADMIN status update | `403` |

### 13.2. ADMIN Ticket Tests

| Scenario | Expected |
|---|---|
| ADMIN lists tenant tickets | `200` |
| ADMIN reads tenant ticket | `200` |
| ADMIN replies to ticket | Success |
| ADMIN resolves ticket | Success |
| ADMIN reads another tenant's ticket | `404` |
| ADMIN assigns cross-tenant admin | Rejected |

### 13.3. Support Identity Mapping

Support sử dụng:

```text
requester_user_id = JWT sub
tenant_id = JWT tenant_id
```

Không lấy requester identity từ frontend request body.

---

## 14. Tenant Isolation Testing

### 14.1. Tenant Test Data

```text
Tenant 1
    USER_1
    ADMIN_1
    HOTEL_1
    BOOKING_1
    TICKET_1

Tenant 2
    USER_2
    ADMIN_2
    HOTEL_2
    BOOKING_2
    TICKET_2
```

### 14.2. Test Matrix

| Actor | Resource | Expected |
|---|---|---|
| USER_1 | HOTEL_1 | Allowed |
| USER_1 | HOTEL_2 | `404` |
| USER_1 | BOOKING_1 owned by USER_1 | Allowed |
| USER_1 | BOOKING_2 | `404` |
| ADMIN_1 | Tenant 1 bookings | Allowed |
| ADMIN_1 | Tenant 2 bookings | `404` |
| USER_1 | TICKET_1 owned by USER_1 | Allowed |
| USER_1 | TICKET_2 | `404` |
| ADMIN_1 | Tenant 1 tickets | Allowed |
| ADMIN_1 | Tenant 2 tickets | `404` |

### 14.3. Required Security Assertions

- Tenant lấy từ verified JWT.
- Không tin `tenantId` do client gửi.
- Không cho phép đổi tenant bằng query parameter.
- Không cho phép đổi owner bằng request body.
- Không để lộ cross-tenant resource existence.
- Direct service calls phải tuân thủ cùng policy.

---

## 15. JWT Expiration & Session Lifecycle

### 15.1. JWT Expiration

Theo tài liệu IAM developer:

```text
JWT TTL = 15 minutes
```

Test:

1. Login thành công.
2. Đợi JWT hết hạn hoặc sử dụng JWT test đã hết hạn.
3. Gọi protected API.
4. Kiểm tra response.

Expected:

```text
401 Unauthorized
```

### 15.2. IAM Session Still Valid

Scenario:

```text
JWT expired
IAM HttpSession valid
```

Expected:

1. Frontend phát hiện authentication failure.
2. Frontend có thể khởi tạo lại IAM login flow.
3. IAM tái sử dụng authenticated session.
4. IAM phát JWT mới nếu user vẫn hợp lệ và được phép vào app.

Không coi đây là refresh token flow.

### 15.3. Local Logout

Current demo behavior:

```text
Frontend clears JWT
        |
        v
IAM HttpSession may remain active
        |
        v
Next login may reuse SSO
```

Expected:

- Frontend không tiếp tục sử dụng JWT đã xóa.
- Không tuyên bố IAM Single Logout đã được triển khai.
- Cross-app logout được ghi nhận là TODO.

### 15.4. RSA Key Restart Behavior

Theo tài liệu developer, RSA keypair có thể được tạo lại khi IAM restart.

Test:

1. Login và nhận JWT test.
2. Restart IAM Service.
3. Kiểm tra JWKS.
4. Gọi protected API với JWT cũ.

Expected current demo behavior:

- JWT cũ có thể không còn xác minh được nếu signing key thay đổi.
- Client phải thực hiện lại login flow.
- Ghi nhận limitation.

Persistent signing keys và key rotation là yêu cầu trước production.

---

## 16. Automated Security Testing

### 16.1. Recommended Test Stack

- JUnit 5.
- Spring Security Test.
- MockMvc hoặc WebTestClient.
- Spring Boot Test.
- Testcontainers PostgreSQL.
- Flyway.

### 16.2. IAM Unit Tests

Required cases:

```text
IAM-001: Invalid app identifier rejected
IAM-002: USER allowed Booking
IAM-003: USER denied Admin
IAM-004: ADMIN allowed Admin
IAM-005: ADMIN denied Booking
IAM-006: USER allowed Support
IAM-007: ADMIN allowed Support
IAM-008: Unprovisioned user rejected
IAM-009: google_sub mismatch rejected
IAM-010: Unverified Google email rejected
IAM-011: JWT contains required claims
IAM-012: JWT expiration configured correctly
```

### 16.3. Resource Server Tests

Required cases:

```text
SEC-001: Missing JWT rejected
SEC-002: Invalid signature rejected
SEC-003: Expired JWT rejected
SEC-004: Invalid issuer rejected
SEC-005: Missing role rejected
SEC-006: Missing tenant rejected
SEC-007: Wrong role rejected
SEC-008: Cross-tenant access rejected
SEC-009: Cross-user ownership rejected
SEC-010: Direct service access protected
```

### 16.4. Example Spring Security Test

Ví dụ minh họa cho controller đã cấu hình Spring Security Resource Server:

```java
@Test
void userCannotAccessAdminEndpoint() throws Exception {
    mockMvc.perform(
            get("/api/admin/bookings")
                .with(jwt().jwt(jwt -> jwt
                    .claim("sub", "101")
                    .claim("tenant_id", 1)
                    .claim("role", "USER")
                ))
        )
        .andExpect(status().isForbidden());
}
```

Lưu ý:

- `jwt()` trong Spring Security Test tạo mock authentication.
- Test này không xác minh chữ ký RS256 hoặc JWKS thực tế.
- Nếu application dùng custom role converter, phải cấu hình mock authorities phù hợp.
- Cần integration tests riêng cho JWT signature, issuer và JWKS validation.

---

## 17. IAM Acceptance Matrix

| Test ID | Scenario | Expected | Status |
|---|---|---|---|
| IAM-001 | JWKS public RSA only | PASS | TODO |
| IAM-002 | Google OAuth2 Authorization Code | PASS | TODO |
| IAM-003 | PKCE S256 | PASS | TODO |
| IAM-004 | USER provisioned login Booking | Valid callback | TODO |
| IAM-005 | ADMIN provisioned login Admin | Valid callback | TODO |
| IAM-006 | Unprovisioned account | Denied | TODO |
| IAM-007 | `google_sub` mismatch | Denied | TODO |
| IAM-008 | USER → Admin | `403` | TODO |
| IAM-009 | ADMIN → Booking | `403` | TODO |
| IAM-010 | USER Booking → Support SSO | No Google re-login | TODO |
| IAM-011 | ADMIN Admin → Support SSO | No Google re-login | TODO |
| IAM-012 | JWT invalid signature | `401` | TODO |
| IAM-013 | JWT expired | `401` | TODO |
| IAM-014 | Invalid issuer | `401` | TODO |
| IAM-015 | Gateway JWT verification | PASS | TODO |
| IAM-016 | Catalog independent verification | PASS | TODO |
| IAM-017 | Booking independent verification | PASS | TODO |
| IAM-018 | Support independent verification | PASS | TODO |
| IAM-019 | Cross-tenant hotel access | `404` | TODO |
| IAM-020 | Cross-tenant booking access | `404` | TODO |
| IAM-021 | Cross-user booking access | `404` | TODO |
| IAM-022 | Cross-user ticket access | `404` | TODO |
| IAM-023 | Cross-tenant ticket access | `404` | TODO |
| IAM-024 | Multi-tab app selector | Correct redirect | TODO |
| IAM-025 | Local logout behavior | Documented | TODO |
| IAM-026 | IAM Single Logout | Not implemented | N/A |

### Related Integration Gates

| Scenario | Expected | Status |
|---|---|---|
| Booking concurrent last-room reservation | No overselling | BLOCKED until Booking migration verified |
| Support ticket E2E | USER/ADMIN workflows pass | BLOCKED until Support implemented |
| Inventory reconciliation | 0 mismatches | TODO |

---

## 18. Test Evidence & Security

### 18.1. Evidence Required

Mỗi acceptance test cần ghi:

```text
Test ID:
Environment:
Commit SHA:
IAM Version:
Test Account Type:
Test Steps:
Expected Result:
Actual Result:
HTTP Status:
Timestamp:
Evidence:
Status:
```

### 18.2. Example

```text
Test ID: IAM-SSO-USER-001

Environment:
Local Development

Scenario:
USER Booking → Support

Expected:
- IAM HttpSession reused
- No Google password prompt
- New Support JWT issued
- USER role preserved
- Tenant preserved

Actual:
NOT EXECUTED

Status:
TODO
```

### 18.3. Sensitive Data Rules

Không lưu:

- Google password.
- Google Client Secret.
- JWT access token.
- Session cookie hoặc `JSESSIONID`.
- RSA private key.
- Database credentials.

Có thể lưu:

- Sanitized HTTP status.
- Redirect host/path đã che token.
- JWT claim names.
- Test results.
- Public JWKS metadata.
- Sanitized screenshots.
- Build/test reports.

### 18.4. Browser Network Inspection

Khi kiểm tra login:

1. Mở DevTools → Network.
2. Bật Preserve Log nếu cần.
3. Thực hiện login.
4. Kiểm tra authorization redirect.
5. Kiểm tra callback destination.
6. Kiểm tra frontend xóa fragment.
7. Kiểm tra API sử dụng Bearer JWT.
8. Che token và cookie trước khi chia sẻ evidence.

Không export HAR chứa credentials hoặc tokens thật.

---

## 19. Known IAM Limitations

| Limitation | Current Status | Impact |
|---|---|---|
| JWT in URL fragment | Demo-only | Token exposure risk |
| JWT in frontend memory | Demo-only | Reload requires reauthentication flow |
| No refresh token | Not implemented | JWT expiry handling required |
| No token revocation | Not implemented | Existing JWT may remain valid until expiry |
| No IAM Single Logout | Not implemented | Local logout does not terminate SSO |
| RSA keypair reset on restart | Reported | Existing JWT may become invalid |
| Session-wide `sso_app` | Known risk | Multi-tab redirect race |
| Role changes after issuance | Requires testing | Stale authorization until token expiry |
| Support callback | Proposed | Must be implemented |
| Role-to-app gate | Requires verification | Must be enforced before redirect/JWT issuance |

---

## 20. Final IAM Integration Gate

### Gate A — Authentication

- [ ] Google OIDC login PASS.
- [ ] Authorization Code flow PASS.
- [ ] PKCE S256 PASS.
- [ ] Pre-provisioning enforcement PASS.
- [ ] `google_sub` binding PASS.
- [ ] `email_verified` enforcement PASS.

### Gate B — JWT Security

- [ ] RS256 signing PASS.
- [ ] JWKS public-only validation PASS.
- [ ] Issuer validation PASS.
- [ ] Expiration validation PASS.
- [ ] Required claims validation PASS.
- [ ] Gateway JWT validation PASS.
- [ ] Direct business service JWT validation PASS.

### Gate C — Three-App SSO

- [ ] Booking callback PASS.
- [ ] Admin callback PASS.
- [ ] Support callback PASS.
- [ ] USER Booking → Support SSO PASS.
- [ ] ADMIN Admin → Support SSO PASS.
- [ ] USER → Admin denied.
- [ ] ADMIN → Booking denied.

### Gate D — Authorization

- [ ] Role matrix PASS.
- [ ] Tenant isolation PASS.
- [ ] Booking ownership PASS.
- [ ] Ticket ownership PASS.
- [ ] Cross-tenant resource access denied.
- [ ] Direct service authorization PASS.

### Gate E — Operational Safety

- [ ] No secrets committed.
- [ ] No tokens/cookies in evidence.
- [ ] Multi-tab race documented/tested.
- [ ] JWT expiry behavior tested.
- [ ] Logout limitation documented.
- [ ] RSA key restart behavior tested.

---

## 21. Documentation References

| Document | Purpose |
|---|---|
| `../README.md` | Documentation overview |
| `architecture.md` | Three-App architecture |
| `iam-sso.md` | IAM implementation and SSO design |
| `iam-integration-tasks.md` | Required IAM integration changes |
| `api-contract.md` | API authorization contracts |
| `data-model.md` | IAM identity, tenant and ownership schema |
| `workflows.md` | End-to-end SSO and business sequences |
| `operations-and-tests.md` | Migrations, integration tests and release gates |
| `decisions.md` | Architecture decisions |

---

## 22. Final Testing Status

```text
DOCUMENTATION: UPDATED

IAM CUSTOM ARCHITECTURE: PRESERVED
KEYCLOAK MIGRATION: NOT REQUIRED

GOOGLE OIDC: NOT RE-VERIFIED
PKCE S256: NOT RE-VERIFIED
JWT / JWKS: NOT RE-VERIFIED

BOOKING LOGIN: TODO
ADMIN LOGIN: TODO
SUPPORT LOGIN: TODO

USER BOOKING → SUPPORT SSO: TODO
ADMIN ADMIN → SUPPORT SSO: TODO

ROLE-TO-APP POLICY: TODO
TENANT ISOLATION: TODO
OWNERSHIP VALIDATION: TODO

BOOKING INTEGRATION: BLOCKED UNTIL MIGRATION VERIFIED
SUPPORT INTEGRATION: PROPOSED

IAM SINGLE LOGOUT: NOT IMPLEMENTED

FINAL ACCEPTANCE: PENDING
```

**Kết luận:** Đây là IAM testing specification dành cho kiến trúc StayHub ba ứng dụng sử dụng Custom IAM hiện tại. Những kết quả PASS được developer báo cáo trước đây chỉ được xem là historical evidence; toàn bộ acceptance tests liên quan đến ba ứng dụng phải được thực thi lại trước khi xác nhận integration hoàn thành.
