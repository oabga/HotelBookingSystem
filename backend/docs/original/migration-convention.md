# Quy ước migration

## Phạm vi và thứ tự

Ba service cùng dùng PostgreSQL database `hotelbooking`, schema `public`, nhưng mỗi service chỉ tạo và sở hữu migration cho bảng của mình:

1. `iam-service`: `tenant`, `app_user`.
2. `catalog-service`: `hotel`, `room_type`, `room_inventory`.
3. `booking-service`: `booking`.

Trên database trống, chạy migration theo thứ tự IAM → Catalog → Booking vì các bảng sau có foreign key tới bảng của service trước. Mỗi service có bảng Flyway history riêng: `flyway_schema_history_iam`, `flyway_schema_history_catalog`, `flyway_schema_history_booking`.

## Đặt tên và phạm vi migration

- Dùng tên `V<số>__<mô_tả_snake_case>.sql`, ví dụ `V3__add_inventory_index.sql`.
- Mỗi migration mới có một mục đích rõ ràng; tách thay đổi schema và seed khi phù hợp.
- Migration thuộc service nào thì đặt trong `src/main/resources/db/migration/` của service đó.
- Không sửa migration đã merge hoặc đã chạy trên môi trường dùng chung. Tạo migration version mới để thay đổi schema.
- Chỉ được sửa trực tiếp migration chưa phát hành trong ngoại lệ chuyển đổi được ERD §11 nêu: Catalog `V1__create_room.sql`, `V2__seed_rooms.sql` và Booking `V1__create_booking.sql`. Ngoại lệ này cần nhóm thống nhất và dựng lại database local bằng `docker compose down -v`.
- Giữ nguyên tên hai migration Catalog trong lần chuyển đổi này vì test Booking hiện tham chiếu trực tiếp đường dẫn `V1__create_room.sql`.

## Cách chạy và kiểm chứng

1. Dùng database sạch cho lần chuyển đổi schema; không sửa database thủ công để thay cho migration.
2. Chạy service theo thứ tự IAM → Catalog → Booking.
3. Xác nhận migration thành công trong bảng history tương ứng và kiểm tra dữ liệu/constraint bằng SQL.
4. Không dùng `clean` hoặc xoá volume trên database có dữ liệu cần giữ. `docker compose down -v` xoá volume PostgreSQL local và chỉ dùng khi nhóm chấp thuận reset dữ liệu demo.
5. Sau khi contract G2 được ký và migration chuyển đổi được merge, mọi thay đổi tiếp theo phải dùng migration version mới.

## Quyền sở hữu bảng dùng chung

- Catalog tạo dòng `room_inventory` và quản lý `total`; Booking cập nhật `sold` trong transaction của Booking.
- Mọi foreign key liên tenant dùng khóa ghép theo `tenant_id` như ERD để database chặn tham chiếu chéo tenant.
- Thay đổi bảng `hotel`, `room_type` hoặc `room_inventory` cần Catalog đề xuất và Booking review trước khi merge vì Booking phụ thuộc schema này.
