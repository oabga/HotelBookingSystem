# notification-service

Thông báo trong ứng dụng, cổng `8083`. Hiện dùng để báo kết quả duyệt hoặc từ chối yêu cầu đổi ngày. Chưa có email hay push.

## API

Gọi qua Gateway `http://localhost:8080/api/notifications`, cần `Authorization: Bearer <jwt>`. Mỗi người chỉ thấy thông báo của mình; thông báo của người khác trả 404.

| Route | Kết quả |
|---|---|
| `GET /notifications?page=0&size=20` | `{"items":[...],"page":0,"size":20,"totalElements":1,"totalPages":1,"unreadCount":1}`, mới nhất trước |
| `POST /notifications/{id}/read` | Đánh dấu đã đọc, gọi lại không đổi thời điểm đọc đầu tiên |

Không có API tạo thông báo.

## Cách nhận sự kiện

`support-service` ghi vào bảng `notification_outbox` **cùng transaction** với kết quả duyệt. Job ở service này đọc bảng đó mỗi 2 giây (`notification.delivery.interval`), tạo thông báo và đánh dấu đã xử lý trong một transaction.

Vì vậy:
- Phiếu đã duyệt thì chắc chắn sẽ có thông báo, kể cả khi service này đang tắt; chạy lại sẽ gửi tiếp.
- Mỗi sự kiện chỉ tạo một thông báo (`UNIQUE source_event_id`), và nhiều bản chạy song song không xử lý trùng (`FOR UPDATE SKIP LOCKED`).

Cách này dùng chung database như quy ước hiện có của dự án, nên không cần tài khoản dịch vụ hay API nội bộ.

## Chạy

Phải migrate sau Support: IAM → Catalog → Booking → Support → Notification.

```bash
./gradlew :notification-service:bootRun
./gradlew :notification-service:test   # Cần Docker; thiếu Docker thì test fail, không bị bỏ qua
```
