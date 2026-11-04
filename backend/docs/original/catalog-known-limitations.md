# Catalog — giới hạn hiện tại

- IAM, Catalog và Booking dùng chung database. Thay đổi `hotel`, `room_type` hoặc `room_inventory` có thể ảnh hưởng trực tiếp tới Booking.
- Seed inventory chỉ được tạo đến hết `2027-01-31`; hiện không có job mở bán các ngày tiếp theo.
- Giá là một `base_price` cố định cho mỗi loại phòng, áp dụng cho mọi đêm. Đổi giá chỉ ảnh hưởng booking tạo sau đó.
- Chưa có API quản lý `total_rooms` hoặc cập nhật `room_inventory.total` để mô phỏng bảo trì.
- Catalog tạo inventory với `sold = 0` nhưng không cập nhật `sold`; Booking chịu trách nhiệm giữ và trả tồn. Truy vấn đối soát ở ERD §8 cần trả 0 dòng sau kiểm thử tích hợp.
- Không có phân trang trong danh sách khách sạn, availability hoặc inventory.
