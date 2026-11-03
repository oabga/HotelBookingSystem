# Sequence diagram: Tìm kiếm, đặt, thanh toán và huỷ booking

Owner: Phúc (C). Bám theo [api-contract.md §4–5](api-contract.md) và [erd-v2.md](erd-v2.md). `hotelbooking` là database chung.

## Tìm kiếm sau khi người dùng gửi đủ lựa chọn

```mermaid
sequenceDiagram
    actor User as USER
    participant UI as Frontend
    participant Gateway as api-gateway
    participant Catalog as catalog-service
    participant DB as hotelbooking

    User->>UI: Chọn hotel + checkIn/checkOut, bấm Tìm kiếm
    UI->>Gateway: GET /api/hotels/{hotelId}/availability?checkIn&checkOut + JWT
    Gateway->>Catalog: GET /hotels/{hotelId}/availability + JWT
    Catalog->>DB: Đọc room_type + room_inventory theo tenant và khoảng ngày
    Note over DB: Chỉ trả loại phòng có tồn ở mọi đêm — không trả total hoặc sold cho USER
    DB-->>Catalog: Loại phòng, base_price mỗi đêm, available_rooms
    Catalog-->>UI: 200 danh sách availability (có thể [])
    UI-->>User: Hiển thị loại phòng và giá sau khi tìm kiếm
```

## Tạo booking giữ tồn

```mermaid
sequenceDiagram
    actor A as USER_A
    participant Gateway as api-gateway
    participant Booking as booking-service
    participant DB as hotelbooking

    A->>Gateway: POST /api/bookings {roomTypeId, checkIn, checkOut, roomCount} + JWT
    Gateway->>Booking: POST /bookings + JWT
    Booking->>Booking: Xác thực JWT (401), role (403), body/ngày (400)
    Booking->>DB: BEGIN READ COMMITTED
    Booking->>DB: Đọc room_type theo id + tenant_id để lấy base_price
    Booking->>DB: UPDATE room_inventory từng đêm tăng dần, sold + roomCount ≤ total
    alt Đủ tồn cho mọi đêm
        Booking->>DB: INSERT booking PENDING_PAYMENT, chốt total_amount
        Booking->>DB: COMMIT
        Booking-->>A: 201 PENDING_PAYMENT + paymentExpiresAt
    else Thiếu tồn hoặc thiếu dòng inventory ở một đêm
        Booking->>DB: ROLLBACK toàn bộ thay đổi
        Booking-->>A: 409 Conflict
    else Loại phòng không tồn tại hoặc thuộc tenant khác
        Booking->>DB: ROLLBACK
        Booking-->>A: 404, không lộ dữ liệu tenant khác
    end
```

## Thanh toán mô phỏng

```mermaid
sequenceDiagram
    actor Owner as BOOKING_OWNER
    participant Gateway as api-gateway
    participant Booking as booking-service
    participant DB as hotelbooking

    Owner->>Gateway: POST /api/bookings/{id}/payment + JWT
    Gateway->>Booking: POST /bookings/{id}/payment + JWT
    Booking->>DB: Đổi PENDING_PAYMENT thành CONFIRMED nếu chưa hết hạn
    alt Đơn còn hạn
        DB-->>Booking: Cập nhật đúng 1 dòng
        Booking-->>Owner: 200 CONFIRMED
        Note over DB: Tồn không đổi — CONFIRMED vẫn giữ phòng
    else Trạng thái không hợp lệ hoặc đã hết hạn
        Booking-->>Owner: 409 — không đổi tồn
    end
```

## User huỷ khi đang chờ thanh toán

```mermaid
sequenceDiagram
    actor A
    actor B
    participant Gateway as api-gateway
    participant Booking as booking-service
    participant Catalog as catalog-service
    participant DB as hotelbooking

    A->>Gateway: POST /api/bookings/{id}/cancel + JWT
    Gateway->>Booking: POST /bookings/{id}/cancel + JWT
    Booking->>DB: BEGIN — đổi PENDING_PAYMENT thành CANCELLED có điều kiện
    alt Đổi trạng thái đúng 1 dòng
        Booking->>DB: Giảm sold cho từng đêm, trong cùng transaction
        Booking->>DB: COMMIT
        Booking-->>A: 200 CANCELLED
    else Đơn không tồn tại/sai owner
        Booking-->>A: 404
    else Trạng thái không cho phép huỷ
        Booking->>DB: ROLLBACK — không trả tồn
        Booking-->>A: 409
    end
    B->>Gateway: GET availability cùng hotel và khoảng ngày + JWT
    Gateway->>Catalog: GET availability
    Catalog->>DB: Đọc tồn đã được trả
    DB-->>Catalog: Loại phòng có available_rooms tăng lại
    Catalog-->>B: 200, phòng xuất hiện nếu còn tồn ở mọi đêm
```

## Hai request đặt cùng tồn đồng thời

```mermaid
sequenceDiagram
    actor A
    actor B
    participant Booking as booking-service
    participant DB as hotelbooking

    A->>Booking: POST booking cùng roomType, ngày
    B->>Booking: POST booking cùng roomType, ngày
    Booking->>DB: A UPDATE inventory theo ngày tăng dần
    Booking->>DB: B UPDATE cùng dòng, chờ khoá
    DB-->>Booking: A tăng sold hợp lệ — ghi PENDING_PAYMENT — COMMIT
    DB-->>Booking: B kiểm tra lại sold + roomCount > total
    Booking-->>A: 201 PENDING_PAYMENT
    Booking-->>B: 409 — rollback mọi đêm đã cập nhật tạm
```

## Các quy tắc trạng thái

| Từ trạng thái | Thao tác | Sang trạng thái | Tồn kho |
|---|---|---|---|
| `PENDING_PAYMENT` | Thanh toán mô phỏng thành công trước hạn | `CONFIRMED` | Giữ nguyên |
| `PENDING_PAYMENT` | User chủ đơn huỷ | `CANCELLED` | Trả đúng một lần |
| `PENDING_PAYMENT` | Quá hạn thanh toán | `EXPIRED` | Trả đúng một lần |
| `CONFIRMED` | Thử huỷ | `409` | Không đổi trạng thái hoặc tồn kho |
| `CANCELLED` / `EXPIRED` | Thanh toán hoặc huỷ lại | Không đổi; thao tác không trả tồn lần nữa | Không đổi |

User chỉ thấy booking của mình; Admin xem trong tenant. Chỉ Admin được gọi API kiểm kê tổng `total`/`sold`/`available`.
