# support-service

Service ticket hỗ trợ, cổng `8085`. Làm theo phần Support trong `docs/new/api-contract.md` và `docs/new/data-model.md`, nhưng **chạy trên IAM và JWT hiện tại**, chưa dùng Keycloak. Tài liệu v4 vẫn ở trạng thái đề xuất nên các quyết định dưới đây có thể đổi sau G2.

## API

Frontend gọi qua Gateway `http://localhost:8080/api/support/...`. Mọi route cần `Authorization: Bearer <jwt>`.

| Route | USER | ADMIN | Thành công | Lỗi chính |
|---|---|---|---|---|
| `POST /support/tickets` | Có | Không | 201 | 400, 403, 404 |
| `GET /support/tickets?page=0&size=20` | Của mình | Trong tenant | 200 | 400 |
| `GET /support/tickets/{id}` | Của mình | Trong tenant | 200, có `messages` | 400, 404 |
| `POST /support/tickets/{id}/messages` | Của mình | Trong tenant | 201 | 400, 404, 409 |
| `PATCH /support/tickets/{id}/status` | Không | Trong tenant | 200 | 400, 403, 404, 409 |

```bash
# Tạo ticket; bookingId không bắt buộc và phải là booking của chính người gọi
curl -X POST http://localhost:8080/api/support/tickets -H "Authorization: Bearer $JWT" \
  -H 'Content-Type: application/json' \
  -d '{"bookingId":1,"subject":"Cần hỗ trợ nhận phòng","description":"Tôi cần đổi giờ đến."}'

# Trả lời
curl -X POST http://localhost:8080/api/support/tickets/1/messages -H "Authorization: Bearer $JWT" \
  -H 'Content-Type: application/json' -d '{"body":"Tôi đến lúc 20 giờ."}'

# ADMIN đổi trạng thái
curl -X PATCH http://localhost:8080/api/support/tickets/1/status -H "Authorization: Bearer $JWT" \
  -H 'Content-Type: application/json' -d '{"status":"IN_PROGRESS"}'
```

Danh sách trả về `{"items":[...],"page":0,"size":20,"totalElements":1,"totalPages":1}`, theo `id` tăng dần, `size` tối đa 100.

## Quy tắc

- **Trạng thái:** chỉ đi một chiều `OPEN → IN_PROGRESS → RESOLVED → CLOSED`. Bỏ bước, lặp lại hoặc quay lại đều là 409. Chưa có mở lại ticket vì tài liệu ghi cần duyệt ở G2.
- **Tin nhắn:** gửi được tới khi `CLOSED`, sau đó là 409. Tin nhắn không sửa, không xoá được, kể cả ghi trực tiếp vào database. Người viết và role lấy từ JWT.
- **Không lộ dữ liệu:** ticket hoặc booking của người khác, tenant khác đều trả 404 giống hệt bản ghi không tồn tại.
- **Tài khoản:** email, tenant và role trong JWT phải khớp `app_user`, nếu không là 403.
- **Nội dung:** `subject` 1–200 ký tự trên một dòng, `description` và `body` 1–4000 ký tự. Có thẻ HTML hoặc ký tự điều khiển thì trả 400. Frontend vẫn phải hiển thị bằng `textContent`.
- **Đồng thời:** đổi trạng thái và thêm tin nhắn cùng khóa dòng ticket, nên chỉ một request đổi trạng thái thắng và không có tin nhắn mới sau khi đóng.

## Dữ liệu

Bảng `support_ticket` và `ticket_message` nằm trong database chung `hotelbooking`, lịch sử Flyway ở `flyway_schema_history_support`. **Phải migrate sau Booking:** IAM → Catalog → Booking → Support.

Migration thêm `UNIQUE (tenant_id, id)` vào `app_user` và `booking` để có FK ghép theo tenant. Vì có FK sang `booking`, lệnh reset booking phải dùng `TRUNCATE booking RESTART IDENTITY CASCADE`, và lệnh này **xoá luôn toàn bộ ticket**.

## Chạy

```bash
# Local, cần Postgres và đã chạy IAM, Catalog, Booking
./gradlew :support-service:bootRun

# Docker
docker compose --profile dev-test up --build -d --wait

# Test, cần JDK 21 và Docker. Thiếu Docker thì test tích hợp fail, không bị bỏ qua
./gradlew :support-service:test
```

## Giới hạn và điểm chờ chốt

- **Lệch so với tài liệu v4:**
  - Chưa có cột `assigned_admin_user_id`, vì tài liệu ghi việc phân công là tuỳ chọn và chưa chốt.
  - Thêm cột `author_role` để lưu role lúc viết.
  - Dùng email để tìm `app_user` vì đó là định danh JWT hiện có; ticket vẫn lưu `user_id` nên đổi sang Keycloak không phải migrate ticket.
- **Câu hỏi còn mở ở G2:** thời gian lưu tin nhắn, SLA, đăng xuất, và Support đọc DB hay gọi API Booking (hiện đọc DB).
- **Chưa có:** tệp đính kèm, thông báo, chat thời gian thực, và giao diện frontend cho ticket.
