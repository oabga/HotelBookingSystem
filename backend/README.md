# HotelBookingSystem

## Chạy thử bằng Docker

Chỉ cần Docker có Compose v2, không cần cài JDK hay Node. Chạy từ thư mục `backend/`:

```bash
docker compose --profile dev-test up --build -d --wait
```

Lần đầu phải tải image, Gradle và dependency nên có thể mất hơn 10 phút; các lần sau nhanh hơn nhiều nhờ cache. Lệnh kết thúc khi mọi service đã sẵn sàng. Migration chạy đúng thứ tự IAM → Catalog → Booking và tài khoản test được tạo tự động.

| Service | Địa chỉ | Vai trò |
| --- | --- | --- |
| `booking-service` | `http://localhost:8082` | API đặt, thanh toán, huỷ booking |
| `catalog-service` | `http://localhost:8081` | API khách sạn, loại phòng, tồn kho |
| `dev-token` | `http://127.0.0.1:8090` | Ký JWT tạm, phát JWKS, trang thử API Catalog |
| `iam-service` | Không mở cổng | Chỉ để tạo bảng `tenant`, `app_user` |
| `postgres` | `localhost:5432` | Database chung `hotelbooking` |

**Giới hạn:** profile này gọi thẳng service bằng JWT tạm, nên không kiểm tra Gateway, Google login và IAM thật. Chỉ dùng trên máy local, không dùng để demo Google SSO. Mỗi tenant chỉ có một tài khoản `USER`, nên race test là nhiều request từ cùng một người.

### Test API bằng curl

Dùng Git Bash trên Windows, hoặc terminal trên macOS và Linux. Token hết hạn sau 1 giờ và mất hiệu lực khi `dev-token` khởi động lại (bị `401`), khi đó chạy lại dòng tạo token.

```bash
sql() { docker exec hotelbooking-postgres psql -U hotelbooking -d hotelbooking -c "$1"; }
token() { curl -s -X POST http://127.0.0.1:8090/dev/token -H 'Content-Type: application/json' \
  -d "{\"role\":\"$1\",\"tenantId\":$2}" | grep -o '"accessToken": "[^"]*"' | cut -d'"' -f4; }
call() { curl -s -w '\nHTTP %{http_code}\n' -X "$2" "http://localhost:8082$3" \
  -H "Authorization: Bearer $1" -H 'Content-Type: application/json' ${4:+-d "$4"}; }
sold() { sql "SELECT stay_date, total, sold FROM room_inventory WHERE room_type_id=$1 AND stay_date BETWEEN '$2' AND '$3' ORDER BY 1"; }

USER1=$(token USER 1); ADMIN1=$(token ADMIN 1); USER2=$(token USER 2)
```

Các tình huống dưới đây dùng ID đơn 1, 2, 3, 4 nên phải chạy theo thứ tự, trên dữ liệu vừa reset. Kết quả đúng ghi trong comment.

```bash
# Đặt 2 đêm: 201 PENDING_PAYMENT, totalAmount 1000000; sold = 1 ở đêm 24 và 25, đêm 23 và 26 vẫn 0
call "$USER1" POST /bookings '{"roomTypeId":1,"checkIn":"2026-11-24","checkOut":"2026-11-26","roomCount":1}'
sold 1 2026-11-23 2026-11-26

# Thanh toán: lần đầu 200 CONFIRMED, lần hai 409
call "$USER1" POST /bookings/1/payment
call "$USER1" POST /bookings/1/payment

# Huỷ: sold lên 2 rồi về 0; huỷ lại vẫn 200 và không trả tồn lần nữa
call "$USER1" POST /bookings '{"roomTypeId":1,"checkIn":"2026-12-01","checkOut":"2026-12-03","roomCount":2}'
sold 1 2026-12-01 2026-12-02
call "$USER1" POST /bookings/2/cancel
call "$USER1" POST /bookings/2/cancel
sold 1 2026-12-01 2026-12-02

# Không đủ phòng: 409 (loại 5 chỉ có 1 phòng)
call "$USER1" POST /bookings '{"roomTypeId":5,"checkIn":"2026-12-10","checkOut":"2026-12-12","roomCount":2}'

# Khác tenant: cả ba đều 404
call "$USER2" POST /bookings '{"roomTypeId":1,"checkIn":"2026-12-10","checkOut":"2026-12-11","roomCount":1}'
call "$USER2" GET /bookings/1
call "$USER2" POST /bookings/1/cancel

# ADMIN: huỷ 403, xem danh sách 200
call "$ADMIN1" POST /bookings/1/cancel
call "$ADMIN1" GET /bookings

# Dữ liệu sai: cả hai đều 400
call "$USER1" POST /bookings '{"roomTypeId":1,"checkIn":"2026-01-01","checkOut":"2026-01-02","roomCount":1}'
call "$USER1" POST /bookings '{"roomTypeId":1,"checkIn":"2026-12-01","checkOut":"2026-12-02","roomCount":11}'

# Race: đúng một 201, còn lại 409; sold = 1
for i in 1 2 3 4 5 6; do curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8082/bookings \
  -H "Authorization: Bearer $USER1" -H 'Content-Type: application/json' \
  -d '{"roomTypeId":5,"checkIn":"2026-12-20","checkOut":"2026-12-21","roomCount":1}' & done; wait
sold 5 2026-12-20 2026-12-20

# Hết hạn (đơn số 4): đẩy hạn về quá khứ thì thanh toán nhận 409
call "$USER1" POST /bookings '{"roomTypeId":2,"checkIn":"2026-12-15","checkOut":"2026-12-16","roomCount":1}'
sql "UPDATE booking SET payment_expires_at = now() - interval '1 minute' WHERE id = 4"
call "$USER1" POST /bookings/4/payment

# Job chạy mỗi 60 giây: trước đó vẫn là PENDING_PAYMENT, sold = 1; sau đó là EXPIRED, sold = 0
sql "SELECT id, status FROM booking WHERE id = 4"
sold 2 2026-12-15 2026-12-15
```

Đối soát cuối cùng phải trả **0 dòng**:

```bash
sql "SELECT i.tenant_id, i.room_type_id, i.stay_date, i.sold, COALESCE(SUM(b.room_count), 0) AS expected_sold
FROM room_inventory i
LEFT JOIN booking b
  ON b.tenant_id = i.tenant_id AND b.room_type_id = i.room_type_id
 AND b.status IN ('PENDING_PAYMENT', 'CONFIRMED')
 AND i.stay_date >= b.check_in AND i.stay_date < b.check_out
GROUP BY i.tenant_id, i.room_type_id, i.stay_date, i.sold
HAVING i.sold <> COALESCE(SUM(b.room_count), 0);"
```

Các ngày mẫu nằm trong khoảng mở bán đến `2027-01-31`. Khi một ngày đã qua, request với ngày đó trả `400`, hãy đổi sang ngày sau hôm nay.

API Catalog thử được trên giao diện tại <http://127.0.0.1:8090>. Trang này không gọi Booking.

### Các thao tác khác

```bash
# Reset booking để test lại từ đầu
sql "TRUNCATE booking RESTART IDENTITY; UPDATE room_inventory SET sold = 0;"

# Rút ngắn thời gian giữ phòng khi test hết hạn
BOOKING_PAYMENT_HOLD=PT1M BOOKING_EXPIRY_INTERVAL=PT5S docker compose --profile dev-test up -d --wait booking-service

# Xem log
docker compose --profile dev-test logs -f booking-service

# Dựng lại sau khi pull hoặc sửa code
docker compose --profile dev-test up --build -d --wait

# Dừng, giữ dữ liệu
docker compose --profile dev-test down

# Dừng và XOÁ toàn bộ dữ liệu local, cần khi migration đã merge bị đổi
docker compose --profile dev-test down -v
```

## Test tự động

Cần JDK 21 và Docker đang chạy. Xem chi tiết ở [booking-service/README.md](booking-service/README.md).

```bash
./gradlew :booking-service:test
```
