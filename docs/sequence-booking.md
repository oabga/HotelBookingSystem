# Sequence diagram: Đặt phòng

Owner: Phúc (C). Bám theo [api-contract.md §5](api-contract.md), [erd-v2.md §3 và §4](erd-v2.md) và code trong `booking-service`.

Nét liền là yêu cầu, nét đứt là phản hồi. `hotelbooking` là database chung của cả hệ thống.

## Đặt phòng và các nhánh lỗi

```mermaid
sequenceDiagram
    actor User as USER
    participant Gateway as api-gateway
    participant Booking as booking-service
    participant DB as hotelbooking

    User->>Gateway: POST /api/bookings {roomId, checkIn, checkOut} + Bearer JWT
    Gateway->>Booking: POST /bookings + Bearer JWT
    Booking->>Booking: Kiểm tra JWT (401), role USER (403), body và thời gian (400)
    Booking->>DB: INSERT booking (tenant_id và email lấy từ JWT, CONFIRMED)

    alt Không vi phạm ràng buộc
        DB-->>Booking: Đã lưu
        Booking-->>User: 201 CONFIRMED
    else excl_booking_room_overlap: đè giờ với booking khác
        DB-->>Booking: Lỗi EXCLUDE (23P01)
        Booking->>DB: Phòng có thuộc tenant của JWT không?
        alt Có
            DB-->>Booking: Có
            Booking-->>User: 409, không tạo booking
        else Không, không được lộ lịch của tenant khác
            DB-->>Booking: Không
            Booking-->>User: 404, không tạo booking
        end
    else fk_booking_room_same_tenant: phòng không có trong tenant
        DB-->>Booking: Lỗi FK
        Booking-->>User: 404, không tạo booking
    else fk_booking_user_same_tenant hoặc booking_tenant_id_fkey: email hoặc tenant không hợp lệ
        DB-->>Booking: Lỗi FK
        Booking-->>User: 403, không tạo booking
    else Lỗi khác
        DB-->>Booking: Lỗi
        Booking-->>User: 500, không lộ chi tiết SQL
    end
```

## Hai request tranh một khoảng giờ

Trong sơ đồ, request A thắng. Khi chạy thật, bên nào cũng có thể thắng, nhưng kết quả luôn là một 201 và một 409.

```mermaid
sequenceDiagram
    actor A as Request A
    actor B as Request B
    participant Booking as booking-service
    participant DB as hotelbooking

    A->>Booking: POST /bookings {roomId:5, cùng khoảng giờ}
    B->>Booking: POST /bookings {roomId:5, cùng khoảng giờ}
    Booking->>DB: INSERT booking của A
    Booking->>DB: INSERT booking của B
    Note over DB: EXCLUDE xử lý nguyên tử:<br/>chỉ một INSERT được giữ lại
    DB-->>Booking: A: đã lưu
    DB-->>Booking: B: bị từ chối
    Booking-->>A: 201 CONFIRMED
    Booking-->>B: 409 Conflict
```

## Xem booking

```mermaid
sequenceDiagram
    actor Client as USER hoặc ADMIN
    participant Booking as booking-service
    participant DB as hotelbooking

    Client->>Booking: GET /bookings/{id} + Bearer JWT
    alt role USER
        Booking->>DB: Tìm theo id, tenant_id và user_email từ JWT
    else role ADMIN
        Booking->>DB: Tìm theo id và tenant_id từ JWT
    end
    alt Có bản ghi
        DB-->>Booking: Booking
        Booking-->>Client: 200
    else Không có, khác tenant hoặc khác chủ
        DB-->>Booking: Không có
        Booking-->>Client: 404, không nói rõ lý do
    end
```
