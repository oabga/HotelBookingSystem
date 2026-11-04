# booking-service

Service đặt, xem, thanh toán mô phỏng và huỷ booking, cổng `8082`, owner Phúc (C). Hợp đồng API nằm ở [api-contract.md §5](../docs/api-contract.md), thiết kế dữ liệu ở [erd-v2.md](../docs/erd-v2.md), sơ đồ ở [sequence-booking.md](../docs/sequence-booking.md), quy tắc nghiệp vụ ở [booking-policy.md](../docs/booking-policy.md).

## Chạy local

**Cách nhanh nhất là dùng Docker:** một lệnh dựng đủ service, không cần JDK. Xem [backend/README.md](../README.md#chạy-thử-bằng-docker).

Cách dưới đây chạy trực tiếp bằng Gradle, dùng khi cần debug. Cần JDK 21 và PostgreSQL với database chung `hotelbooking`. Phải chạy migration theo thứ tự IAM → Catalog → Booking, vì bảng `booking` có FK sang `tenant`, `app_user` và `room_type`. Nếu chạy Booking trước trên DB trống, migration sẽ báo lỗi thiếu bảng.

Chạy từ thư mục `backend/` bằng Git Bash, mỗi service một terminal. IAM chưa phát JWT nên token lấy từ `test-api-catalog`, chạy riêng bằng `node server.mjs` trong thư mục đó. Dán khối biến môi trường vào mọi terminal chạy service:

```bash
export JAVA_HOME=<đường dẫn JDK 21>
# Trên Windows ở Việt Nam, JVM báo múi giờ Asia/Saigon và postgres:16 từ chối khi kết nối.
export JAVA_TOOL_OPTIONS=-Duser.timezone=UTC
# Bắt buộc để IAM khởi động; giá trị giả là đủ nếu không đăng nhập Google.
export GOOGLE_CLIENT_ID=dummy GOOGLE_CLIENT_SECRET=dummy
export SECURITY_JWT_ISSUER=http://127.0.0.1:8090
export SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI=http://127.0.0.1:8090/.well-known/jwks.json
```

```bash
docker compose up -d postgres
./gradlew :iam-service:bootRun       # thấy log Started thì mới chạy service tiếp theo
./gradlew :catalog-service:bootRun
./gradlew :booking-service:bootRun
```

Token của `test-api-catalog` dùng email `<role viết thường>-tenant<id>@catalog.local.test`, nên phải có `app_user` tương ứng, nếu không `POST /bookings` trả `403`. Thêm bằng `docker/seed/seed-app-users.dev-test.sql`:

```bash
docker exec -i hotelbooking-postgres psql -U hotelbooking -d hotelbooking < docker/seed/seed-app-users.dev-test.sql
```

Khi test đăng nhập Google thật thì dùng `docker/seed/seed-app-users.example.sql` theo hướng dẫn ở đầu file đó. DB tạo trước khi chuyển sang schema `room_inventory` phải dựng lại (ERD §11) bằng `docker compose down -v`, lệnh này xoá toàn bộ dữ liệu local.

| Cấu hình trong `application.yml` | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `booking.payment.hold` | `PT15M` | Thời gian giữ phòng chờ thanh toán |
| `booking.expiry.interval` | `PT60S` | Chu kỳ job đổi đơn quá hạn sang `EXPIRED` và trả tồn |

Khi demo hết hạn, có thể rút ngắn mà không sửa file:

```powershell
.\gradlew.bat :booking-service:bootRun --args="--booking.payment.hold=PT1M --booking.expiry.interval=PT5S"
```

## Chạy test

```powershell
.\gradlew.bat :booking-service:test
```

| Test | Kiểm tra | Cần Docker |
| --- | --- | --- |
| `BookingServiceTest` | Thứ tự trừ tồn, tính tiền, ngày theo giờ Việt Nam, chuyển trạng thái, cách đổi lỗi database thành lỗi nghiệp vụ | Không |
| `BookingExpiryJobTest` | Job xử lý mọi đơn quá hạn, đơn lỗi không chặn đơn khác | Không |
| `BookingApiTest` | Status và JSON của API, validate ngày và `roomCount`, thứ tự lỗi, phân quyền, các loại JWT lỗi | Không |
| `BookingFlowIntegrationTest` | Flyway, PostgreSQL thật, trừ và trả `room_inventory`, rollback, FK ghép, cách ly tenant và owner, hết hạn, request đồng thời. Sau mỗi test đều chạy truy vấn đối soát ERD §8 | Có |

`BookingFlowIntegrationTest` bị **bỏ qua mà build vẫn xanh** nếu Docker không chạy, nên phải bật Docker khi lấy bằng chứng và kiểm tra report không có test bị skip. Test này tạo bảng từ đúng migration của `iam-service` và `catalog-service`.

## Bàn giao cho Đạt (D)

Gọi qua Gateway tại `http://localhost:8080/api/bookings`. Không commit JWT vào `docs/demo.http`.

| Request | Role | Thành công | Lỗi |
| --- | --- | --- | --- |
| `POST /api/bookings` | `USER` | `201` | `400`, `401`, `403`, `404`, `409` |
| `GET /api/bookings` | `USER`, `ADMIN` | `200`, mảng theo `id` tăng dần | `401` |
| `GET /api/bookings/{id}` | `USER`, `ADMIN` | `200` | `400`, `401`, `404` |
| `POST /api/bookings/{id}/payment` | `USER` là chủ đơn | `200` | `400`, `401`, `403`, `404`, `409` |
| `POST /api/bookings/{id}/cancel` | `USER` là chủ đơn | `200` | `400`, `401`, `403`, `404`, `409` |

`USER` chỉ thấy booking của mình, `ADMIN` thấy mọi booking trong tenant của mình. Booking của tenant khác hoặc của người khác trả `404`. `payment` và `cancel` không có body.

Body của `POST /api/bookings`. `checkIn` và `checkOut` là `YYYY-MM-DD`, không có giờ và múi giờ; `roomCount` từ 1 đến 10:

```json
{ "roomTypeId": 1, "checkIn": "2026-11-24", "checkOut": "2026-11-26", "roomCount": 1 }
```

Response thành công. `paymentExpiresAt` và `createdAt` theo UTC:

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

Ý nghĩa của lỗi khi `POST /api/bookings`:

| Status | Khi nào |
| --- | --- |
| `400` | Body sai, ngày sai định dạng hoặc có giờ, `checkOut` không sau `checkIn`, `checkIn` ở quá khứ, ở quá 30 đêm, `roomCount` ngoài 1–10 |
| `401` | Thiếu hoặc sai JWT |
| `403` | Không phải role `USER`, hoặc email trong JWT không có trong `app_user` của tenant đó |
| `404` | Loại phòng không tồn tại hoặc thuộc tenant khác |
| `409` | Có đêm hết phòng hoặc chưa mở bán |

Kết quả của `payment` và `cancel` theo trạng thái đơn:

| Trạng thái đơn | `payment` | `cancel` |
| --- | --- | --- |
| `PENDING_PAYMENT`, còn hạn | `200 CONFIRMED`, giữ tồn | `200 CANCELLED`, trả tồn |
| `PENDING_PAYMENT`, quá hạn, job chưa chạy | `409` | `200 CANCELLED`, trả tồn |
| `CONFIRMED` | `409` | `409` |
| `CANCELLED` | `409` | `200 CANCELLED`, không trả tồn lần nữa |
| `EXPIRED` | `409` | `409` |

Response lỗi là `application/problem+json`, riêng `401` và `403` về role có thể không có body. Chỉ dựa vào HTTP status, không dựa vào `detail`:

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Not enough rooms for the requested stay",
  "instance": "/bookings"
}
```

Frontend phải khoá nút Đặt sau lần bấm đầu: không có idempotency key nên gửi lại sẽ tạo đơn thứ hai nếu còn phòng.

### Race test

Chạy từ Git Bash với loại phòng 5 (`Race Test`, tenant 1, chỉ có 1 phòng) và hai token của `user-tenant1` và `user2-tenant1`. Kết quả đúng là một dòng `201` và một dòng `409`. Thử trên DB vừa tạo, vì ID loại phòng chỉ đúng theo thứ tự seed trên DB sạch.

```bash
URL=http://localhost:8080/api/bookings
BODY='{"roomTypeId":5,"checkIn":"2026-12-20","checkOut":"2026-12-21","roomCount":1}'
for token in "$JWT_USER_1" "$JWT_USER_2"; do
  curl -s -o /dev/null -w '%{http_code}\n' -X POST "$URL" \
    -H "Authorization: Bearer $token" \
    -H 'Content-Type: application/json' \
    -d "$BODY" &
done
wait
```

### Xem, đối soát và reset dữ liệu

```bash
docker exec hotelbooking-postgres psql -U hotelbooking -d hotelbooking -c "SELECT * FROM booking ORDER BY id;"
docker exec hotelbooking-postgres psql -U hotelbooking -d hotelbooking -c "SELECT version, description, success FROM flyway_schema_history_booking;"
```

Truy vấn đối soát phải trả **0 dòng** sau mọi test và race test (T37):

```sql
SELECT i.tenant_id, i.room_type_id, i.stay_date, i.sold,
       COALESCE(SUM(b.room_count), 0) AS expected_sold
FROM room_inventory i
LEFT JOIN booking b
  ON b.tenant_id = i.tenant_id
 AND b.room_type_id = i.room_type_id
 AND b.status IN ('PENDING_PAYMENT', 'CONFIRMED')
 AND i.stay_date >= b.check_in
 AND i.stay_date <  b.check_out
GROUP BY i.tenant_id, i.room_type_id, i.stay_date, i.sold
HAVING i.sold <> COALESCE(SUM(b.room_count), 0);
```

Khi reset demo phải đưa `sold` về 0 cùng lúc với xoá booking, nếu không tồn sẽ lệch:

```sql
BEGIN;
TRUNCATE booking RESTART IDENTITY CASCADE;
UPDATE room_inventory SET sold = 0;
COMMIT;
```

## Giả định đang chờ IAM

Service đang yêu cầu claim `tenant_id` trong JWT là số nguyên JSON dương, ví dụ `"tenant_id": 1`, theo [api-contract.md §3.3](../docs/api-contract.md). `iam-service` cần phát đúng như vậy. Token có `tenant_id` dạng chuỗi sẽ bị trả `401`.

## Giới hạn hiện tại

- JWT đang được kiểm tra bằng `SecurityConfig` trong service này, vì `common-security` chưa có code. Có thể thay khi module đó hoàn thành.
- Xem thêm các giới hạn nghiệp vụ ở [booking-policy.md](../docs/booking-policy.md).
