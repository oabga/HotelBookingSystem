# StayHub — API Contract

> **Cập nhật:** 05/11/2026, đối chiếu với controller và `SecurityConfig` tại commit `1737b60`
> **Phạm vi:** Toàn bộ HTTP API đang có. Không liệt kê API chưa được xây dựng.

## 1. Quy ước

- Frontend gọi qua Gateway: `http://localhost:8080/api/...`. Gateway bỏ `/api` trước khi chuyển tiếp.
- IAM được gọi trực tiếp ở `http://localhost:8084`, xem [mục 7](#7-iam).
- Mọi API qua Gateway cần `Authorization: Bearer <JWT>`, trừ preflight `OPTIONS`.
- Ngày có dạng `YYYY-MM-DD`, không có giờ và múi giờ. "Hôm nay" tính theo giờ `Asia/Ho_Chi_Minh`.
- Thời điểm là UTC theo ISO-8601, ví dụ `2026-11-03T02:30:30Z`.
- Field lạ trong body như `tenantId`, `status`, `totalAmount` bị bỏ qua. Các giá trị này chỉ lấy từ JWT và database.
- API chưa có tiền tố phiên bản.

### 1.1. Mã trạng thái

| Mã | Ý nghĩa |
|---|---|
| `200`, `201` | Thành công, tạo mới |
| `400` | Dữ liệu sai |
| `401` | Thiếu JWT, JWT sai hoặc hết hạn |
| `403` | Sai role, hoặc tài khoản trong JWT không khớp `app_user` |
| `404` | Không tồn tại, khác tenant hoặc khác chủ. Không phân biệt để tránh lộ dữ liệu |
| `409` | Hết phòng hoặc trạng thái không cho phép |
| `503` | Support không gọi được Booking |

### 1.2. Định dạng lỗi

Lỗi từ service dùng `application/problem+json`, không kèm SQL hay stack trace. `401` và `403` do Spring Security trả có thể không có body. Client chỉ nên dựa vào HTTP status, không dựa vào `detail`.

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Not enough rooms for the requested stay",
  "instance": "/bookings"
}
```

### 1.3. Tổng hợp endpoint

| Endpoint | Service | USER | ADMIN |
|---|---|---|---|
| `GET /api/hotels` | Catalog | Có | Có |
| `GET /api/hotels/{id}/availability` | Catalog | Có | Có |
| `POST /api/hotels/{id}/room-types` | Catalog | Không | Có |
| `PUT /api/room-types/{id}/price` | Catalog | Không | Có |
| `GET /api/admin/hotels/{id}/inventory` | Catalog | Không | Có |
| `POST /api/bookings` | Booking | Có | Không |
| `GET /api/bookings` | Booking | Của mình | Trong tenant |
| `GET /api/bookings/{id}` | Booking | Của mình | Trong tenant |
| `POST /api/bookings/{id}/payment` | Booking | Của mình | Không |
| `POST /api/bookings/{id}/cancel` | Booking | Của mình | Không |
| `GET /api/bookings/reschedulable` | Booking | Của mình | Không |
| `GET /api/bookings/{id}/reschedule-quote` | Booking | Của mình | Trong tenant |
| `POST /api/bookings/{id}/reschedule` | Booking | Không | Trong tenant |
| `POST /api/support/date-change-requests` | Support | Có | Không |
| `GET /api/support/date-change-requests` | Support | Của mình | Trong tenant |
| `GET /api/support/date-change-requests/{id}` | Support | Của mình | Trong tenant |
| `POST /api/support/date-change-requests/{id}/approve` | Support | Không | Trong tenant |
| `POST /api/support/date-change-requests/{id}/reject` | Support | Không | Trong tenant |
| `GET /api/notifications` | Notification | Của mình | Của mình |
| `POST /api/notifications/{id}/read` | Notification | Của mình | Của mình |

Support và Notification trả `403` nếu email, tenant và role trong JWT không khớp `app_user`.

## 2. JWT

Claim và cách kiểm tra xem [iam-sso.md](iam-sso.md#8-jwt-và-jwks). JWKS: `http://localhost:8084/.well-known/jwks.json`.

## 3. Catalog

### 3.1. `GET /api/hotels`

Trả mảng khách sạn của tenant, sắp theo `id`, không phân trang.

```json
[{ "id": 1, "tenantId": 1, "name": "Demo Hotel A - Hà Nội", "address": "12 Hàng Trống, Hoàn Kiếm, Hà Nội" }]
```

### 3.2. `GET /api/hotels/{hotelId}/availability?checkIn=&checkOut=`

Trả các loại phòng còn trống ở mọi đêm trong kỳ ở. Kết quả chỉ để xem, không giữ chỗ. Mảng rỗng nghĩa là không còn phòng.

```json
[{
  "roomTypeId": 1,
  "name": "Standard",
  "capacity": 2,
  "pricePerNight": 500000.00,
  "nights": 2,
  "totalPricePerRoom": 1000000.00,
  "availableRooms": 3
}]
```

`availableRooms` là số phòng trống của đêm ít phòng nhất.

| Lỗi | Khi nào |
|---|---|
| `400` | Sai định dạng ngày, `checkOut` không sau `checkIn`, quá 30 đêm, `checkIn` ở quá khứ |
| `404` | Khách sạn không tồn tại hoặc thuộc tenant khác |

### 3.3. `POST /api/hotels/{hotelId}/room-types`

```json
{ "name": "Suite", "capacity": 2, "basePrice": 1200000, "totalRooms": 5 }
```

| Field | Quy tắc |
|---|---|
| `name` | 1 đến 100 ký tự sau khi cắt khoảng trắng |
| `capacity` | 1 đến 20 |
| `basePrice` | Lớn hơn 0, tối đa `99999999.99`, tối đa 2 chữ số thập phân |
| `totalRooms` | 1 đến 1000 |

Trả `201` kèm `{ id, tenantId, hotelId, name, capacity, basePrice, totalRooms }`. Trong cùng transaction, Catalog tạo tồn kho từ hôm nay đến `2027-01-31`. Lỗi: `400`, `403`, `404`.

### 3.4. `PUT /api/room-types/{roomTypeId}/price`

```json
{ "basePrice": 650000 }
```

Trả `200` với cùng dạng response như mục 3.3. Đơn đã tạo giữ nguyên số tiền. Lỗi: `400`, `403`, `404`.

### 3.5. `GET /api/admin/hotels/{hotelId}/inventory?checkIn=&checkOut=`

Trả tồn kho từng đêm trong khoảng ngày, tối đa 30 đêm. Khác với tìm phòng, khoảng này được phép ở quá khứ. Lỗi: `400`, `403`, `404`.

```json
[{ "roomTypeId": 1, "name": "Standard", "stayDate": "2026-11-24", "total": 3, "sold": 1, "available": 2 }]
```

Hiện không có API liệt kê loại phòng, xem chi tiết khách sạn, tạo hoặc sửa khách sạn, hay sửa `total` theo đêm.

## 4. Booking

### 4.1. `POST /api/bookings`

```json
{ "roomTypeId": 1, "checkIn": "2026-11-24", "checkOut": "2026-11-26", "roomCount": 1 }
```

Trả `201`:

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

`totalAmount = base_price × số đêm × roomCount`, lấy giá mới nhất tại thời điểm đặt.

| Lỗi | Khi nào |
|---|---|
| `400` | Body sai, ngày sai định dạng hoặc có giờ, `checkOut` không sau `checkIn`, `checkIn` ở quá khứ, quá 30 đêm, `roomCount` ngoài 1 đến 10 |
| `403` | Không phải `USER`, hoặc email không có trong `app_user` của tenant đó |
| `404` | Loại phòng không tồn tại hoặc thuộc tenant khác |
| `409` | Có đêm hết phòng hoặc chưa mở bán |

API chưa có idempotency key. Gửi lại request sẽ tạo đơn thứ hai nếu còn phòng, nên frontend khóa nút Đặt đến khi có response và không tự gửi lại.

### 4.2. `GET /api/bookings` và `GET /api/bookings/{id}`

USER thấy đơn của mình, ADMIN thấy mọi đơn trong tenant. Danh sách là mảng sắp theo `id` tăng dần, không phân trang. Chi tiết trả `404` nếu không xem được.

### 4.3. `POST /api/bookings/{id}/payment` và `/cancel`

Không có body. Chỉ `USER` là chủ đơn. Đơn của người khác trả `404`.

| Trạng thái đơn | `payment` | `cancel` |
|---|---|---|
| `PENDING_PAYMENT`, còn hạn | `200 CONFIRMED`, giữ tồn | `200 CANCELLED`, trả tồn |
| `PENDING_PAYMENT`, quá hạn, job chưa chạy | `409` | `200 CANCELLED`, trả tồn |
| `CONFIRMED` | `409` | `409` |
| `CANCELLED` | `409` | `200 CANCELLED`, không trả tồn lần nữa |
| `EXPIRED` | `409` | `409` |

Đơn quá hạn được job chuyển sang `EXPIRED` và trả tồn. Không có API công khai cho việc này.

### 4.4. `GET /api/bookings/reschedulable`

Trả đơn `CONFIRMED` của người gọi, chưa tới ngày nhận phòng.

```json
[{
  "id": 5,
  "roomTypeId": 1,
  "roomTypeName": "Standard",
  "hotelName": "Demo Hotel A - Hà Nội",
  "checkIn": "2026-12-10",
  "checkOut": "2026-12-12",
  "roomCount": 1,
  "totalAmount": 1000000.00
}]
```

### 4.5. `GET /api/bookings/{id}/reschedule-quote?checkIn=&checkOut=`

Chỉ đọc, không giữ phòng. Đêm trùng với kỳ cũ không cần thêm phòng.

```json
{
  "bookingId": 5,
  "roomTypeId": 1,
  "roomTypeName": "Standard",
  "currentCheckIn": "2026-12-10",
  "currentCheckOut": "2026-12-12",
  "checkIn": "2026-12-11",
  "checkOut": "2026-12-14",
  "nights": 3,
  "roomCount": 1,
  "currentTotalAmount": 1000000.00,
  "newTotalAmount": 1500000.00,
  "available": true
}
```

`newTotalAmount` tính theo giá mỗi đêm của đơn gốc.

| Lỗi | Khi nào |
|---|---|
| `400` | `checkOut` không sau `checkIn`, quá 30 đêm, `checkIn` ở quá khứ, ngày mới trùng ngày hiện tại |
| `404` | Đơn không tồn tại, khác tenant, hoặc USER không phải chủ đơn |
| `409` | Đơn không phải `CONFIRMED` hoặc đã tới ngày nhận phòng |

### 4.6. `POST /api/bookings/{id}/reschedule`

Support gọi thẳng API này khi ADMIN duyệt phiếu.

```json
{ "requestId": 12, "checkIn": "2026-12-11", "checkOut": "2026-12-14" }
```

Trả `200` với thông tin đơn sau khi đổi. Gọi lại với cùng `requestId` và cùng ngày sẽ trả `200` mà không đổi gì thêm. Cùng `requestId` nhưng khác đơn hoặc khác ngày trả `409`. Hết phòng hoặc đơn không còn đổi được trả `409` và Booking rollback.

ADMIN gọi trực tiếp API này qua Gateway được, tức là đổi ngày mà không qua phiếu.

## 5. Support

### 5.1. `POST /api/support/date-change-requests`

```json
{ "bookingId": 5, "checkIn": "2026-12-11", "checkOut": "2026-12-14", "description": "Đổi vì lịch công tác" }
```

`description` có 1 đến 4000 ký tự, không chứa thẻ HTML hoặc ký tự điều khiển. Phòng chưa được giữ khi tạo phiếu. Trả `201`:

```json
{
  "id": 12,
  "tenantId": 1,
  "bookingId": 5,
  "requesterEmail": "user-tenant1@example.com",
  "roomTypeName": "Standard",
  "roomCount": 1,
  "currentCheckIn": "2026-12-10",
  "currentCheckOut": "2026-12-12",
  "requestedCheckIn": "2026-12-11",
  "requestedCheckOut": "2026-12-14",
  "currentTotalAmount": 1000000.00,
  "newTotalAmount": 1500000.00,
  "description": "Đổi vì lịch công tác",
  "status": "PENDING",
  "decisionNote": null,
  "decidedAt": null,
  "createdAt": "2026-11-05T02:15:30Z"
}
```

| Lỗi | Khi nào |
|---|---|
| `400` | Body sai hoặc ngày không hợp lệ |
| `403` | Sai role, hoặc tài khoản không khớp `app_user` |
| `404` | Đơn không tồn tại, khác tenant hoặc khác chủ |
| `409` | Không đủ phòng, đơn không đổi được, hoặc đơn đã có phiếu đang chờ |
| `503` | Booking không phản hồi |

### 5.2. `GET /api/support/date-change-requests?status=&page=0&size=20`

USER thấy phiếu của mình, ADMIN thấy phiếu trong tenant, mới nhất trước. `status` là tùy chọn và nhận `PENDING`, `APPROVED`, `REJECTED`. `page` từ 0, `size` từ 1 đến 100.

```json
{ "items": [], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0 }
```

### 5.3. `GET /api/support/date-change-requests/{id}`

Trả một phiếu. Phiếu của người khác hoặc tenant khác trả `404`.

### 5.4. `POST /api/support/date-change-requests/{id}/approve`

Không có body. Booking đổi ngày trước, sau đó phiếu thành `APPROVED` và có sự kiện thông báo.

| Lỗi | Khi nào |
|---|---|
| `403` | Không phải `ADMIN`, hoặc tài khoản không khớp `app_user` |
| `404` | Phiếu không tồn tại hoặc thuộc tenant khác |
| `409` | Phiếu đã có kết quả, hết phòng, hoặc đơn không còn đổi được. Phiếu vẫn `PENDING` nếu lỗi đến từ Booking |
| `503` | Booking không phản hồi. Phiếu vẫn `PENDING` |

Sau lỗi giữa chừng, duyệt lại là an toàn nhờ `requestId`.

### 5.5. `POST /api/support/date-change-requests/{id}/reject`

Body là tùy chọn. `note` tối đa 1000 ký tự, không chứa thẻ HTML hoặc ký tự điều khiển.

```json
{ "note": "Ngày đó khách sạn bảo trì" }
```

Lỗi: `400`, `403`, `404`, và `409` nếu phiếu đã có kết quả hoặc Booking đã áp dụng đổi ngày của phiếu này. Trường hợp sau chỉ có thể duyệt lại.

## 6. Notification

### 6.1. `GET /api/notifications?page=0&size=20`

Mỗi người chỉ thấy thông báo của mình, mới nhất trước.

```json
{
  "items": [{
    "id": 1,
    "type": "DATE_CHANGE_APPROVED",
    "title": "Yêu cầu đổi ngày đã được duyệt",
    "body": "Booking #5 (Standard) đã đổi sang 2026-12-11 → 2026-12-14. Tổng tiền mới: 1.500.000 VND.",
    "referenceId": 12,
    "readAt": null,
    "createdAt": "2026-11-05T02:20:00Z"
  }],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "unreadCount": 1
}
```

`type` là `DATE_CHANGE_APPROVED` hoặc `DATE_CHANGE_REJECTED`. `referenceId` là ID phiếu.

### 6.2. `POST /api/notifications/{id}/read`

Đánh dấu đã đọc và trả thông báo đó. Gọi lại không đổi thời điểm đọc đầu tiên. Thông báo của người khác trả `404`.

## 7. IAM

Các endpoint này do trình duyệt điều hướng tới, không phải API JSON. Chi tiết ở [iam-sso.md](iam-sso.md).

| Endpoint | Thành công | Lỗi |
|---|---|---|
| `GET /auth/start?app=&mode=` | `302` sang Google, hoặc về callback nếu đã có phiên | `400` |
| `GET /auth/logout?app=` | `302 {callback}#logged_out` | `400` |
| `GET /.well-known/jwks.json` | `200` kèm public key | |

## 8. Concurrency

| Trường hợp | Cách xử lý |
|---|---|
| Hai request tranh phòng cuối | `UPDATE` có điều kiện, một bên `201`, bên kia `409` |
| Thanh toán trùng | `UPDATE ... WHERE status = 'PENDING_PAYMENT'`, lần sau `409` |
| Hủy trùng, hoặc hủy trùng lúc job hết hạn chạy | Chỉ một bên đổi được dòng, nên chỉ trả tồn một lần |
| Duyệt trùng | `SELECT ... FOR UPDATE` trên phiếu, lần sau `409` |
| Duyệt lại sau lỗi giữa chừng | `requestId` là idempotency key của `reschedule` |
| Hai phiếu cho một đơn | Unique index `uq_date_change_request_pending`, phiếu sau `409` |
| Tạo đơn trùng | Chưa xử lý, xem mục 4.1 |

## 9. Câu hỏi còn mở

| Câu hỏi | Hiện trạng |
|---|---|
| Idempotency key cho `POST /bookings` | Chưa có |
| Phân trang danh sách khách sạn và đơn | Chưa có |
| Có cần `aud` trong JWT | Chưa có, chưa kiểm tra |
| Cơ chế trao JWT ở production | Đang dùng URL fragment cho demo |
| Thu hồi JWT | Chưa có |
| Tiền tố phiên bản API | Chưa có |
| Đối chiếu giá đã xem khi đặt phòng | Chưa có |
