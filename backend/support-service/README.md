# support-service

Service xử lý phiếu **xin đổi ngày thuê phòng**, cổng `8085`. Chạy trên IAM và JWT hiện tại. Phần ticket và tin nhắn chung đã được gỡ vì Support Center hiện chỉ cần chức năng này; code cũ còn trong lịch sử git.

## Luồng

1. USER chọn đơn đã đặt và ngày mới, Booking báo còn phòng hay không và tổng tiền mới (`reschedule-quote`).
2. USER nhập mô tả rồi gửi phiếu. Phòng **chưa được giữ**, đơn chưa đổi.
3. ADMIN trong tenant duyệt hoặc từ chối.
4. Khi duyệt, Booking kiểm tra lại phòng và đổi ngày trong một transaction. Hết phòng thì trả 409, phiếu vẫn chờ, đơn giữ ngày cũ.
5. Kết quả được ghi vào `notification_outbox` cùng transaction với phiếu, và `notification-service` tạo thông báo cho người gửi.

## API

Frontend gọi qua Gateway `http://localhost:8080/api/support/...`, cần `Authorization: Bearer <jwt>`.

| Route | USER | ADMIN | Thành công | Lỗi chính |
|---|---|---|---|---|
| `POST /support/date-change-requests` | Có | Không | 201 | 400, 403, 404, 409, 503 |
| `GET /support/date-change-requests?status=&page=0&size=20` | Của mình | Trong tenant | 200 | 400 |
| `GET /support/date-change-requests/{id}` | Của mình | Trong tenant | 200 | 400, 404 |
| `POST /support/date-change-requests/{id}/approve` | Không | Trong tenant | 200 | 403, 404, 409, 503 |
| `POST /support/date-change-requests/{id}/reject` | Không | Trong tenant | 200 | 400, 403, 404, 409 |

```bash
curl -X POST http://localhost:8080/api/support/date-change-requests -H "Authorization: Bearer $JWT" \
  -H 'Content-Type: application/json' \
  -d '{"bookingId":1,"checkIn":"2026-12-10","checkOut":"2026-12-13","description":"Đổi vì lịch công tác"}'

curl -X POST http://localhost:8080/api/support/date-change-requests/1/approve -H "Authorization: Bearer $JWT"

curl -X POST http://localhost:8080/api/support/date-change-requests/1/reject -H "Authorization: Bearer $JWT" \
  -H 'Content-Type: application/json' -d '{"note":"Ngày đó khách sạn bảo trì"}'
```

Danh sách trả về `{"items":[...],"page":0,"size":20,"totalElements":1,"totalPages":1}`, mới nhất trước, `size` tối đa 100.

## Quy tắc

- **Đơn đổi được:** `CONFIRMED`, chưa tới ngày nhận phòng, của chính người gửi. Mỗi đơn chỉ có một phiếu đang chờ.
- **Ngày mới:** khác ngày hiện tại, không ở quá khứ, tối đa 30 đêm, phải còn đủ phòng lúc gửi và lúc duyệt. Đêm trùng với kỳ cũ không cần thêm phòng vì đơn đang giữ sẵn.
- **Số đêm và tiền:** được đổi số đêm. Tổng tiền tính lại theo **giá mỗi đêm của đơn gốc**, đúng với quy tắc đổi giá phòng không ảnh hưởng đơn đã tạo. Chênh lệch chỉ được ghi nhận, không có thu thêm hay hoàn tiền thật.
- **Không lộ dữ liệu:** phiếu hoặc đơn của người khác, tenant khác đều trả 404 giống hệt bản ghi không tồn tại.
- **Tài khoản:** email, tenant và role trong JWT phải khớp `app_user`, nếu không là 403.
- **Nội dung:** `description` 1–4000 ký tự, lý do từ chối tối đa 1000 ký tự và không bắt buộc. Có thẻ HTML hoặc ký tự điều khiển thì trả 400.

## Gọi sang Booking

Chỉ Booking được sửa `booking` và `sold`, nên Support gọi Booking bằng **chính JWT của người đang thao tác**, không dùng tài khoản dịch vụ. Booking đổi ngày trước, phiếu ghi nhận sau, trong lúc giữ khóa dòng phiếu:

- Booking lỗi hoặc không phản hồi (503): phiếu vẫn chờ, không có thông báo.
- Booking đã đổi nhưng Support lỗi trước khi ghi nhận: phiếu vẫn chờ, **duyệt lại là an toàn** vì Booking bỏ qua `requestId` đã áp dụng, và phiếu đó không thể bị từ chối nhầm.

## Dữ liệu

Bảng `date_change_request` và `notification_outbox` nằm trong database chung `hotelbooking`, lịch sử Flyway ở `flyway_schema_history_support`. **Thứ tự migrate:** IAM → Catalog → Booking → Support → Notification.

Migration thêm `UNIQUE (tenant_id, id)` vào `app_user` và `booking` để có FK ghép theo tenant. Vì có FK sang `booking`, lệnh reset booking phải dùng `TRUNCATE booking RESTART IDENTITY CASCADE`, và lệnh này **xoá luôn toàn bộ phiếu**.

## Chạy

```bash
# Local, cần Postgres và đã chạy IAM, Catalog, Booking
./gradlew :support-service:bootRun

# Docker
docker compose --profile workflow up --build -d --wait

# Test, cần JDK 21 và Docker. Thiếu Docker thì test tích hợp fail, không bị bỏ qua
./gradlew :support-service:test
```

## Giới hạn

- ADMIN gọi trực tiếp `POST /api/bookings/{id}/reschedule` thì đổi được ngày mà không qua phiếu; quyền này ADMIN đã có khi duyệt nên chưa chặn.
- Phiếu chờ quá lâu không tự hết hạn. Nếu đơn đã tới ngày nhận phòng, duyệt sẽ trả 409 và admin từ chối phiếu.
