# Xem database bằng Adminer

Compose có service Adminer để xem trực tiếp database PostgreSQL đang chạy. Dữ liệu trong Adminer là dữ liệu hiện tại của `hotelbooking`; refresh trang để tải lại kết quả.

## Chạy

```bash
cd backend
docker compose up -d postgres adminer
```

Mở <http://127.0.0.1:8083>.

Đăng nhập bằng:

| Trường | Giá trị |
|---|---|
| System | PostgreSQL |
| Server | `postgres` |
| Username | `hotelbooking` |
| Password | `hotelbooking` |
| Database | `hotelbooking` |

Trong Adminer, chọn bảng `app_user`, `hotel`, `room_type`, `room_inventory` hoặc `booking` rồi chọn **Select data**. Các bảng khác gồm `booking_reschedule`, `date_change_request`, `notification_outbox`, `notification`, xuất hiện khi các service tương ứng đã chạy migration.

Port 8083 chỉ bind vào loopback của máy này, nên UI không được mở trực tiếp ra mạng LAN/Internet. Để dừng Adminer mà giữ PostgreSQL và dữ liệu:

```bash
docker compose stop adminer
```

Không dùng `docker compose down -v` nếu muốn giữ dữ liệu PostgreSQL.
