# booking-service

Service tạo và xem booking, cổng `8082`, owner Phúc (C). Hợp đồng API nằm ở [api-contract.md §5](../docs/api-contract.md), thiết kế dữ liệu ở [erd-v2.md](../docs/erd-v2.md), sơ đồ ở [sequence-booking.md](../docs/sequence-booking.md), quy tắc nghiệp vụ ở [booking-policy.md](../docs/booking-policy.md).

## Chạy local

Cần JDK 21 và PostgreSQL với database chung `hotelbooking`. Phải chạy migration theo thứ tự IAM → Catalog → Booking, vì bảng `booking` có FK sang `tenant`, `room` và `app_user`. Nếu chạy Booking trước trên DB trống, migration sẽ báo lỗi thiếu bảng.

```powershell
docker compose up -d postgres
.\gradlew.bat :iam-service:bootRun
.\gradlew.bat :catalog-service:bootRun
.\gradlew.bat :booking-service:bootRun
```

Tài khoản demo được thêm bằng `docker/seed/seed-app-users.example.sql`. Xem hướng dẫn ở đầu file đó. Để gọi được API, IAM phải đang chạy ở `:8084` để phát JWKS.

## Chạy test

```powershell
.\gradlew.bat :booking-service:test
```

| Test | Kiểm tra | Cần Docker |
| --- | --- | --- |
| `BookingServiceTest` | Nghiệp vụ tạo và đọc booking, cách đổi lỗi database thành lỗi nghiệp vụ | Không |
| `BookingApiTest` | Status và JSON của API, kiểm tra `checkIn`/`checkOut`, thứ tự lỗi, phân quyền, các loại JWT lỗi | Không |
| `BookingFlowIntegrationTest` | Flyway, PostgreSQL thật, chống trùng giờ, FK ghép, cách ly tenant và owner, request đồng thời | Có |

`BookingFlowIntegrationTest` bị bỏ qua nếu Docker không chạy, nên cần bật Docker khi lấy bằng chứng. Test này tạo bảng từ đúng migration của `iam-service` và `catalog-service`.

## Bàn giao cho Đạt (D)

Gọi qua Gateway tại `http://localhost:8080/api/bookings`. Không commit JWT vào `docs/demo.http`.

| Request | Role | Thành công | Lỗi |
| --- | --- | --- | --- |
| `POST /api/bookings` | `USER` | `201` | `400`, `401`, `403`, `404`, `409` |
| `GET /api/bookings` | `USER`, `ADMIN` | `200`, mảng theo `id` tăng dần | `401` |
| `GET /api/bookings/{id}` | `USER`, `ADMIN` | `200` | `400`, `401`, `404` |

`USER` chỉ thấy booking của mình, `ADMIN` thấy mọi booking trong tenant của mình. Booking của tenant khác hoặc của người khác trả `404`.

Body của `POST`. `checkIn` và `checkOut` là chuỗi ISO-8601 bắt buộc có múi giờ, `checkOut` phải sau `checkIn`:

```json
{ "roomId": 1, "checkIn": "2026-12-01T14:00:00+07:00", "checkOut": "2026-12-02T11:00:00+07:00" }
```

Response thành công. Thời gian luôn trả về theo UTC:

```json
{
  "id": 1,
  "tenantId": 1,
  "roomId": 1,
  "userEmail": "user-tenant1@example.com",
  "checkIn": "2026-12-01T07:00:00Z",
  "checkOut": "2026-12-02T04:00:00Z",
  "status": "CONFIRMED",
  "createdAt": "2026-11-03T02:15:30Z"
}
```

Ý nghĩa của lỗi khi `POST`:

| Status | Khi nào |
| --- | --- |
| `400` | Body sai, thời gian sai định dạng hoặc thiếu múi giờ, `checkOut` không sau `checkIn` |
| `401` | Thiếu hoặc sai JWT |
| `403` | Không phải role `USER`, hoặc email trong JWT không có trong `app_user` của tenant đó |
| `404` | Phòng không tồn tại hoặc thuộc tenant khác |
| `409` | Phòng đã có booking đè lên khoảng thời gian này |

Response lỗi là `application/problem+json`, riêng `401` và `403` về role có thể không có body. Chỉ dựa vào HTTP status, không dựa vào `detail`:

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Room is not available for the requested time",
  "instance": "/bookings"
}
```

### Race test

Chạy từ Git Bash với phòng 5 (`Race-Test Room 199`, tenant 1) và hai token của `user-tenant1` và `user2-tenant1`. Kết quả đúng là một dòng `201` và một dòng `409`. Thử trên DB vừa tạo, vì ID phòng chỉ đúng theo thứ tự seed trên DB sạch.

```bash
URL=http://localhost:8080/api/bookings
BODY='{"roomId":5,"checkIn":"2026-12-20T14:00:00+07:00","checkOut":"2026-12-21T11:00:00+07:00"}'
for token in "$JWT_USER_1" "$JWT_USER_2"; do
  curl -s -o /dev/null -w '%{http_code}\n' -X POST "$URL" \
    -H "Authorization: Bearer $token" \
    -H 'Content-Type: application/json' \
    -d "$BODY" &
done
wait
```

### Xem và xóa dữ liệu booking

```bash
docker exec hotelbooking-postgres psql -U hotelbooking -d hotelbooking -c "SELECT * FROM booking ORDER BY id;"
docker exec hotelbooking-postgres psql -U hotelbooking -d hotelbooking -c "SELECT version, description, success FROM flyway_schema_history_booking;"
docker exec hotelbooking-postgres psql -U hotelbooking -d hotelbooking -c "TRUNCATE booking RESTART IDENTITY;"
```

Khi reset demo chỉ cần xóa booking, vì trạng thái còn trống của phòng được tính từ chính các booking.

## Giả định đang chờ IAM

Service đang yêu cầu claim `tenant_id` trong JWT là số nguyên JSON dương, ví dụ `"tenant_id": 1`, theo [api-contract.md §3.3](../docs/api-contract.md). A chưa ký xác nhận, nên `iam-service` cần phát đúng như vậy. Token có `tenant_id` dạng chuỗi sẽ bị trả `401`.

## Giới hạn hiện tại

- JWT đang được kiểm tra bằng `SecurityConfig` trong service này, vì `common-security` chưa có code. Có thể thay khi module đó hoàn thành.
- Xem thêm các giới hạn nghiệp vụ ở [booking-policy.md](../docs/booking-policy.md).
