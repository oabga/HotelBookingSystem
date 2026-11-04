# Quyết định kỹ thuật

Tài liệu này ghi các quyết định áp dụng cho triển khai hiện tại. Thay đổi contract hoặc quyết định liên service cần được các owner liên quan xác nhận trước khi triển khai.

## Catalog và multi-tenancy

- Mô hình Catalog là `hotel` → `room_type` → `room_inventory` theo đêm; không còn bảng `room` trong mô hình mục tiêu.
- `tenant_id` lấy từ JWT đã xác minh, không lấy từ request body, query hoặc header do client gửi.
- Mọi truy vấn Catalog lọc tenant tường minh bằng `tenant_id`; không dựa riêng vào bộ lọc ORM vì truy vấn availability dùng phép tổng hợp SQL.
- Hotel/room type không tồn tại và bản ghi thuộc tenant khác cùng trả 404 với cùng dạng response để không tiết lộ dữ liệu tenant.
- FK ghép `(tenant_id, id)` và `(tenant_id, parent_id)` bảo vệ tính nhất quán tenant ở tầng database.
- Catalog không cập nhật `room_inventory.sold`; Booking là owner duy nhất của cột này.

## Flyway và chuyển đổi schema

- Database chung là `hotelbooking`, schema `public`; mỗi service dùng bảng Flyway history riêng.
- Thứ tự migrate trên database trống: IAM → Catalog → Booking.
- Theo ERD §11, lần chuyển đổi chưa phát hành được phép thay nội dung Catalog V1/V2 và Booking V1, sau khi nhóm thống nhất và reset database local. Sau chuyển đổi, migration đã merge không được sửa.
- Catalog giữ nguyên tên `V1__create_room.sql` trong lần chuyển đổi để tương thích test Booking đang đọc trực tiếp file đó.

## Trạng thái xác nhận

- `api-contract.md` v3.0 hiện ghi trạng thái chờ ký lại G2. Các quyết định ở đây phản ánh thiết kế đích trong tài liệu, chưa thay cho xác nhận của A/B/C/D.
- Trước khi đổi migration dùng chung, cần xác nhận G2 và phối hợp với Booking owner về việc bỏ FK `room`/`room_id` cũ.
