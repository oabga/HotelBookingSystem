# StayHub: API contract

> **Phiên bản:** 0.1, ngày 02/11/2026  
> **Trạng thái:** nháp, chờ ký nhận ở mục 9. Chưa có endpoint nào được cài đặt, và config hiện tại vẫn trỏ tới Keycloak.  
> **Nguồn:** [plan v3](iam-google-sso-demo-plan-v3.md). Nếu hai tài liệu khác nhau về chi tiết API, contract này được ưu tiên sau khi đã ký nhận.

## 1. Quy ước chung

| Mục | Quy ước |
|---|---|
| Địa chỉ cho client | Gateway `http://localhost:8080`, IAM `http://localhost:8084` (gọi trực tiếp, không qua gateway) |
| Địa chỉ giữa các service | Catalog `http://localhost:8081`, Booking `http://localhost:8082` |
| Định dạng | JSON. Có body thì gửi `Content-Type: application/json` |
| Tên trường | JSON dùng `camelCase`. Riêng claim trong JWT dùng `tenant_id` |
| Thời gian | ISO-8601 UTC, ví dụ `2026-11-03T02:15:30Z` |
| Tiền | Số JSON, tối đa 2 chữ số thập phân. Không có trường tiền tệ |
| Xác thực | `Authorization: Bearer <jwt>` cho mọi API phòng và booking, kể cả khi gọi thẳng service |
| Danh sách | Mảng JSON, không phân trang, sắp theo `id` tăng dần. Không có dữ liệu thì trả `[]` |
| Trường lạ trong body | Bị bỏ qua, không gây lỗi. Ví dụ `tenantId` gửi lên không có tác dụng (I-07) |
| Tạo mới | Trả 201 kèm tài nguyên trong body. Không dùng header `Location` |

**Tenant, người dùng và role chỉ lấy từ JWT**, không bao giờ lấy từ query, header hay body.

## 2. Tổng hợp endpoint

Cột "Qua gateway" là đường dẫn cho trang HTML và script. Gateway bỏ tiền tố `/api` rồi chuyển tiếp, nên service nhận đường dẫn ở cột "Tại service".

| Qua gateway | Tại service | Quyền | Thành công | Lỗi nghiệp vụ | Kiểm tra |
|---|---|---|---|---|---|
| `GET /api/rooms` | `GET /rooms` | `USER`, `ADMIN` | 200 | | I-01 đến I-04, I-09 |
| `GET /api/rooms/{id}` | `GET /rooms/{id}` | `USER`, `ADMIN` | 200 | 400, 404 | I-10 |
| `POST /api/rooms` | `POST /rooms` | `ADMIN` | 201 | 400 | I-05, I-07 |
| `PUT /api/rooms/{id}/price` | `PUT /rooms/{id}/price` | `ADMIN` | 200 | 400, 404 | I-06, I-08 |
| `POST /api/bookings` | `POST /bookings` | `USER` | 201 | 400, 404, 409, 503 | I-11, I-12, I-20 |
| `GET /api/bookings` | `GET /bookings` | `USER`, `ADMIN` | 200 | | I-14, I-15 |
| `GET /api/bookings/{id}` | `GET /bookings/{id}` | `USER`, `ADMIN` | 200 | 400, 404 | I-13 |

Mọi endpoint trong bảng đều có thể trả 401, và 403 nếu endpoint giới hạn role.

Endpoint của IAM:

| Endpoint | Xác thực | Kết quả | Kiểm tra |
|---|---|---|---|
| `GET /auth/start?app=app1\|app2` | Session IAM, nếu có | 302, `app` sai trả 400 | I-16, I-17, I-18, I-21 |
| `GET /auth/logout?app=app1\|app2` | Session IAM, nếu có | 302, `app` sai trả 400 | |
| `GET /.well-known/jwks.json` | Không | 200 | |
| `POST /dev/token` | Không, chỉ có ở profile `dev` | 200, 400 | I-19 |

## 3. Lỗi

### Thứ tự kiểm tra

Nhiều lỗi cùng xảy ra thì trả lỗi đứng trước:

1. **401:** thiếu token, token hỏng hoặc hết hạn.
2. **403:** sai role.
3. **400:** ID hoặc body không hợp lệ.
4. **404:** không có bản ghi trong phạm vi được phép.
5. **409:** xung đột dữ liệu.

Ví dụ: `USER` gửi `POST /api/rooms` với body sai vẫn nhận 403.

### Định dạng

| Status | Khi nào | Body |
|---|---|---|
| 400 | Sai định dạng hoặc vi phạm ràng buộc | `ProblemDetail` |
| 401 | Token không hợp lệ theo mục 5.3 | Không cam kết, chỉ dựa vào status |
| 403 | Token hợp lệ nhưng sai role | Không cam kết, chỉ dựa vào status |
| 404 | Không tồn tại, thuộc tenant khác, hoặc là booking của người khác | `ProblemDetail` |
| 409 | Phòng đã có người đặt | `ProblemDetail` |
| 503 | Booking không gọi được catalog | `ProblemDetail` |

`ProblemDetail` dùng `Content-Type: application/problem+json`:

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Room not found",
  "instance": "/rooms/5"
}
```

- Client chỉ dựa vào `status`. Nội dung `title` và `detail` có thể đổi.
- **404 phải giống hệt nhau** dù bản ghi không tồn tại hay thuộc tenant khác, kể cả `detail`, để không lộ là bản ghi có tồn tại.
- Không đưa stack trace, câu SQL hay dữ liệu của tenant khác vào lỗi.
- Bật `spring.mvc.problemdetails.enabled: true` ở 2 service để lỗi 400 của Spring cũng theo định dạng này.

## 4. API

### 4.1 Đối tượng trả về

**Room**

| Trường | Kiểu | Ghi chú |
|---|---|---|
| `id` | số nguyên | Phòng seed từ 1 đến 6, phòng mới từ 100 |
| `tenantId` | UUID | Script dùng để chứng minh isolation |
| `name` | chuỗi | |
| `price` | số | Lớn hơn 0 |

**Booking**

| Trường | Kiểu | Ghi chú |
|---|---|---|
| `id` | số nguyên | |
| `tenantId` | UUID | |
| `roomId` | số nguyên | |
| `userSub` | chuỗi | Claim `sub` của người đặt. Coi là chuỗi bất kỳ: token thật là UUID, token dev có thể là `user-a1`. Cột DB không được dùng kiểu UUID |
| `userEmail` | chuỗi | Claim `email` của người đặt |
| `status` | chuỗi | Hiện chỉ có `CONFIRMED` |
| `createdAt` | thời gian | |

### 4.2 Phòng (Hòa cung cấp)

#### `GET /api/rooms`

Trả 200 với các phòng thuộc tenant trong JWT.

```json
[
  {
    "id": 1,
    "tenantId": "11111111-1111-1111-1111-111111111111",
    "name": "Room A-101",
    "price": 1500000.00
  },
  {
    "id": 2,
    "tenantId": "11111111-1111-1111-1111-111111111111",
    "name": "Room A-102",
    "price": 1800000.00
  }
]
```

#### `GET /api/rooms/{id}`

Trả 200 với một Room. Phòng của tenant khác hoặc không tồn tại trả 404. `id` không phải số nguyên trả 400.

`booking-service` cũng gọi endpoint này, tại `GET http://localhost:8081/rooms/{id}`, kèm JWT của người dùng.

#### `POST /api/rooms`

```json
{
  "name": "Room A-201",
  "price": 2500000.00
}
```

| Trường | Bắt buộc | Ràng buộc |
|---|---|---|
| `name` | Có | Từ 1 đến 100 ký tự, không chỉ có khoảng trắng. Được phép trùng tên |
| `price` | Có | Lớn hơn 0, không quá 9999999999.99, tối đa 2 chữ số thập phân |

Trả 201 với Room mới, tenant lấy từ JWT:

```json
{
  "id": 100,
  "tenantId": "11111111-1111-1111-1111-111111111111",
  "name": "Room A-201",
  "price": 2500000.00
}
```

#### `PUT /api/rooms/{id}/price`

```json
{
  "price": 2000000.00
}
```

`price` có cùng ràng buộc như trên. Trả 200 với Room sau khi cập nhật. Phòng của tenant khác trả 404 và giá không đổi.

### 4.3 Đặt phòng (Phúc cung cấp)

#### `POST /api/bookings`

```json
{
  "roomId": 1
}
```

`roomId` là số nguyên, bắt buộc. Trả 201:

```json
{
  "id": 1,
  "tenantId": "11111111-1111-1111-1111-111111111111",
  "roomId": 1,
  "userSub": "user-a1",
  "userEmail": "user-a1@example.com",
  "status": "CONFIRMED",
  "createdAt": "2026-11-03T02:15:30Z"
}
```

| Status | Khi nào |
|---|---|
| 400 | Thiếu `roomId` hoặc sai kiểu. Không gọi catalog |
| 403 | Người gọi là `ADMIN` |
| 404 | Catalog trả 404: phòng không tồn tại hoặc thuộc tenant khác |
| 409 | Phòng đã có người đặt, kể cả khi chính người đó đặt lại |
| 503 | Catalog không gọi được, hoặc trả kết quả khác 200 và 404 |

Mọi trường hợp lỗi đều không tạo booking. Khi nhiều người cùng đặt một phòng, đúng một request nhận 201, các request còn lại nhận 409.

#### `GET /api/bookings`

Trả 200 với mảng Booking:

- `USER`: booking của chính mình trong tenant.
- `ADMIN`: mọi booking của tenant.

#### `GET /api/bookings/{id}`

Trả 200 với một Booking. Trả 404 nếu booking không tồn tại, thuộc tenant khác, hoặc `USER` đọc booking của người khác. `ADMIN` đọc được mọi booking trong tenant. `id` không phải số nguyên trả 400.

## 5. IAM (Bảo cung cấp)

### 5.1 Đăng nhập

Hai endpoint này là điều hướng của trình duyệt, không gọi bằng `fetch`.

| `app` | Trang đích |
|---|---|
| `app1` | `http://localhost:3000/` |
| `app2` | `http://localhost:3001/` |

**`GET /auth/start?app=app1`**

| Tình huống | Kết quả |
|---|---|
| `app` thiếu hoặc ngoài allowlist | 400, không redirect |
| Session IAM còn hiệu lực | 302 về trang đích, kèm `#token=<jwt>` |
| Chưa có session | 302 sang Google với `code_challenge`, `code_challenge_method=S256`, `prompt=select_account` |
| Login Google thành công | 302 về trang đích, kèm `#token=<jwt>` |
| Email không được cấp quyền, chưa xác minh, hoặc `sub` không khớp | Xóa session, 302 về trang đích, kèm `#error=not_provisioned` |
| Login Google thất bại vì lý do khác, ví dụ người dùng hủy | Xóa session, 302 về trang đích, kèm `#error=login_failed` |

Trang HTML đọc fragment, xóa ngay bằng `history.replaceState`, và chỉ giữ token trong bộ nhớ.

**`GET /auth/logout?app=app1`**

Xóa session IAM rồi trả 302 về trang đích. `app` thiếu hoặc sai trả 400 và không xóa session. Trang HTML tự xóa token của mình. Token đã phát vẫn dùng được đến khi hết hạn, vì không có revocation.

### 5.2 JWKS

`GET /.well-known/jwks.json` là public và trả 200:

```json
{
  "keys": [
    {
      "kty": "RSA",
      "use": "sig",
      "alg": "RS256",
      "kid": "stayhub-key-1",
      "n": "<modulus base64url>",
      "e": "AQAB"
    }
  ]
}
```

### 5.3 JWT nội bộ

Ký RS256, header có `kid`, sống 15 phút, không có refresh token.

```json
{
  "iss": "http://localhost:8084",
  "sub": "8f14e45f-ceea-4e7a-9a1b-0d1f2c3b4a59",
  "email": "user-a1@example.com",
  "tenant_id": "11111111-1111-1111-1111-111111111111",
  "role": "USER",
  "iat": 1793000000,
  "exp": 1793000900
}
```

| Claim | Yêu cầu | Token không đạt |
|---|---|---|
| Chữ ký | Đúng theo JWKS của IAM | 401 |
| `iss` | Đúng `http://localhost:8084` | 401 |
| `exp` | Bắt buộc, chưa hết hạn | 401 |
| `sub` | Bắt buộc, không rỗng | 401 |
| `tenant_id` | Bắt buộc, là UUID | 401 |
| `role` | Bắt buộc, là `USER` hoặc `ADMIN` | 401 |
| `email` | IAM luôn phát cho cả token thật và token dev | |

Gateway kiểm tra chữ ký, `iss` và `exp`. `common-security` ở 2 service kiểm tra đủ bảng trên, nên I-04 nhận 401 từ service, không phải từ gateway.

### 5.4 Token phát triển

`POST /dev/token` chỉ có ở profile `dev`:

```json
{
  "sub": "user-a1",
  "email": "user-a1@example.com",
  "tenantId": "11111111-1111-1111-1111-111111111111",
  "role": "USER",
  "expiresInSeconds": 900
}
```

| Trường | Bắt buộc | Nếu bỏ trống |
|---|---|---|
| `sub` | Có, thiếu trả 400 | |
| `email` | Không | Dùng `<sub>@example.com` |
| `tenantId` | Không | Token không có `tenant_id`, dùng cho I-04 |
| `role` | Không | Token không có `role`, dùng cho ca lỗi |
| `expiresInSeconds` | Không | 900 |

Trả 200:

```json
{
  "accessToken": "<jwt>",
  "tokenType": "Bearer",
  "expiresIn": 900
}
```

- **Tạo token hết hạn phải dùng số âm lớn, ví dụ `-3600`.** `JwtTimestampValidator` của Spring mặc định cho lệch đồng hồ 60 giây, nên `-1` vẫn được chấp nhận và I-02 sẽ FAIL giả.
- Token sai chữ ký cho I-03: script tự sửa phần chữ ký của một token hợp lệ.
- **Khi tắt profile `dev`** (I-19), đạt yêu cầu là không có token được phát. Tùy cấu hình bảo mật, IAM có thể trả 302, 403 hoặc 404, nên script kiểm tra status không phải 2xx và không có `accessToken`.

## 6. Gateway (Đạt cung cấp)

| Đường dẫn ngoài | Service | Bộ lọc |
|---|---|---|
| `/api/rooms/**` | `http://localhost:8081` | `StripPrefix=1` |
| `/api/bookings/**` | `http://localhost:8082` | `StripPrefix=1` |

- Chuyển tiếp nguyên header `Authorization`.
- Request `OPTIONS` không cần JWT. Mọi request khác cần JWT hợp lệ, không thì trả 401.
- Chỉ gateway xử lý CORS, 2 service không thêm header CORS để tránh trùng lặp.

| CORS | Giá trị |
|---|---|
| Origin | `http://localhost:3000`, `http://localhost:3001` |
| Method | `GET`, `POST`, `PUT`, `OPTIONS` |
| Header | `Authorization`, `Content-Type` |
| Credentials | Không, vì token gửi bằng header |

`Content-Type: application/json` cũng kích hoạt preflight. Nếu không cho phép header này, trình duyệt sẽ chặn `POST` và `PUT`.

## 7. Dữ liệu dùng chung

| Tên | Giá trị |
|---|---|
| Tenant A | `11111111-1111-1111-1111-111111111111` |
| Tenant B | `22222222-2222-2222-2222-222222222222` |
| Phòng seed của tenant A | ID 1, 2, 3 |
| Phòng seed của tenant B | ID 4, 5, 6 |
| Phòng tạo mới | ID từ 100 |
| Role | `USER`, `ADMIN` |
| Trạng thái booking | `CONFIRMED` |

## 8. Bổ sung so với plan v3

Plan v3 chưa ghi các điểm dưới đây. Đây là đề xuất, cần được xác nhận khi ký nhận:

| # | Bổ sung | Người xác nhận chính |
|---|---|---|
| 1 | Tên trường JSON của Room, Booking, và trả `tenantId` trong response | Hòa, Phúc, Đạt |
| 2 | Danh sách sắp theo `id`, không phân trang, không dùng `Location` | Hòa, Phúc, Đạt |
| 3 | Ràng buộc `name` và giới hạn `price` | Hòa |
| 4 | Thứ tự kiểm tra lỗi, và 401, 403 không cam kết body | Cả nhóm |
| 5 | Validator kiểm tra thêm `sub`, kiểu UUID của `tenant_id` và giá trị `role`. v3 chỉ yêu cầu có `exp`, `tenant_id`, `role` | Bảo |
| 6 | Phân chia phòng seed: 1 đến 3 cho A, 4 đến 6 cho B | Hòa, Đạt |
| 7 | Catalog trả kết quả khác 200 và 404 thì booking trả 503 | Phúc |
| 8 | Lỗi `#error=login_failed`, và `/auth/logout` từ chối `app` sai | Bảo, Đạt |
| 9 | Trường `email`, quy tắc bỏ trống và response của `/dev/token` | Bảo, Đạt |
| 10 | Cách kiểm tra I-19 không phụ thuộc status cụ thể | Bảo, Đạt |
| 11 | CORS cho phép thêm header `Content-Type`. v3 chỉ nêu `Authorization` | Đạt |

## 9. Ký nhận và thay đổi

Mỗi người xác nhận phần mình cung cấp và phần mình sử dụng. Chưa đủ 4 người thì contract vẫn là nháp.

| Người | Cung cấp | Sử dụng | Đã đồng ý |
|---|---|---|---|
| Bảo | IAM, JWT, `/dev/token` | | [ ] |
| Hòa | API phòng, phòng seed | JWT | [ ] |
| Phúc | API booking | JWT, `GET /rooms/{id}` | [ ] |
| Đạt | Gateway, CORS | Toàn bộ API, cho trang HTML và `scripts/isolation-check.sh` | [ ] |

Sau khi ký nhận, muốn đổi endpoint, tên trường, claim, role hay status thì bên cung cấp và bên sử dụng phải cùng đồng ý, và cập nhật file này trong cùng PR với thay đổi code.
