# StayHub — Data Model

> **Cập nhật:** 05/11/2026, đối chiếu với Flyway migrations tại commit `1737b60`
> **Phạm vi:** Quy tắc nghiệp vụ của dữ liệu. Danh sách bảng, cột và ràng buộc nằm ở [erd.md](erd.md).

## 1. Tổng quan

Mọi service dùng chung PostgreSQL `hotelbooking`, schema `public`.

| Miền | Bảng |
|---|---|
| Danh tính | `tenant`, `app_user` |
| Danh mục | `hotel`, `room_type`, `room_inventory` |
| Đặt phòng | `booking`, `booking_reschedule` |
| Đổi ngày | `date_change_request` |
| Thông báo | `notification_outbox`, `notification` |

Các bảng `support_ticket` và `ticket_message` đã bị xóa ở `support-service` `V2`. Support hiện chỉ xử lý phiếu đổi ngày.

## 2. Danh tính

- Một `app_user` thuộc đúng một tenant và có đúng một role `USER` hoặc `ADMIN`.
- Email luôn viết thường, được ép bằng CHECK.
- `google_sub` được gắn ở lần đăng nhập Google đầu tiên, sau đó phải khớp ở mọi lần sau.
- Tài khoản được tạo theo một trong ba cách:
  - App 1 tự tạo `USER` trong tenant mặc định khi email Google đã xác minh đăng nhập lần đầu.
  - App Admin tạo `ADMIN` cho đúng email `IAM_ADMIN_EMAIL`.
  - Seed bằng SQL khi test, xem [operations-and-tests.md](operations-and-tests.md#6-seed-và-dữ-liệu-mẫu).
- Support không tạo tài khoản.
- Tenant chỉ seed trong migration, chưa có API tạo tenant.

## 3. Tồn kho theo từng đêm

Hệ thống bán theo loại phòng, không gán phòng vật lý. Mỗi dòng `room_inventory` là một loại phòng trong một đêm.

```text
available = total - sold
0 <= sold <= total
```

Kỳ ở là khoảng `[check_in, check_out)`. Ví dụ `2026-12-01` đến `2026-12-04` là ba đêm 1, 2, 3 tháng 12.

| Cột | Ai đổi | Khi nào |
|---|---|---|
| `total` | Catalog | Khi tạo loại phòng. Chưa có API sửa |
| `sold` | Booking | Khi đặt, hủy, hết hạn, đổi ngày |

Đêm không có dòng tồn kho nghĩa là chưa mở bán: không hiện khi tìm phòng và trả `409` khi đặt. `room_type.total_rooms` chỉ dùng làm giá trị `total` ban đầu.

### 3.1. Khoảng mở bán

- Loại phòng seed có tồn kho từ `2026-11-01` đến `2027-01-31`.
- Loại phòng tạo qua API có tồn kho từ hôm nay đến `2027-01-31`.

Chưa có job mở bán cuốn chiếu. Ngày cuối đang viết cứng trong migration và trong `CatalogRepository`.

## 4. Tìm phòng

```sql
SELECT rt.id, rt.name, rt.capacity, rt.base_price,
       MIN(i.total - i.sold) AS available_rooms
FROM room_type rt
JOIN room_inventory i
  ON i.tenant_id = rt.tenant_id AND i.room_type_id = rt.id
WHERE rt.tenant_id = :tenantId AND rt.hotel_id = :hotelId
  AND i.stay_date >= :checkIn AND i.stay_date < :checkOut
GROUP BY rt.id, rt.name, rt.capacity, rt.base_price
HAVING COUNT(*) = :nights AND MIN(i.total - i.sold) > 0
ORDER BY rt.id;
```

| Điều kiện | Tác dụng |
|---|---|
| `stay_date < :checkOut` | Không tính đêm của ngày trả phòng |
| `MIN(total - sold)` | Số phòng trống bị giới hạn bởi đêm ít phòng nhất |
| `COUNT(*) = :nights` | Mọi đêm đều phải đã mở bán |
| `MIN(total - sold) > 0` | Loại phòng hết chỗ ở bất kỳ đêm nào |

Truy vấn không khóa dòng nào, nên kết quả có thể cũ ngay sau khi trả về.

## 5. Đặt phòng

Trong một transaction `READ COMMITTED`:

1. Từ chối nếu `checkIn` ở quá khứ theo giờ Việt Nam.
2. Đọc `base_price` theo `room_type_id` và `tenant_id`. Không có dòng thì `404`.
3. Với từng đêm, theo ngày tăng dần, chạy `UPDATE` có điều kiện:

   ```sql
   UPDATE room_inventory
   SET sold = sold + :roomCount
   WHERE tenant_id = :tenantId AND room_type_id = :roomTypeId
     AND stay_date = :stayDate AND sold + :roomCount <= total;
   ```

4. Đêm nào đổi được khác 1 dòng thì trả `409` và rollback toàn bộ.
5. `INSERT booking` với `PENDING_PAYMENT`, `payment_expires_at = now + booking.payment.hold`.
6. Vi phạm `fk_booking_user_same_tenant` được chuyển thành `403`.

### 5.1. Chống bán vượt

`UPDATE` khóa dòng tồn kho. Request đến sau phải chờ, rồi đánh giá lại điều kiện trên dữ liệu mới. Vì vậy với 1 phòng còn lại, đúng một request thành công.

Các lớp bảo vệ: `UPDATE` có điều kiện, `chk_inventory_not_oversold`, và thứ tự ngày tăng dần để giảm nguy cơ deadlock. Lần kiểm tra ngày 05/11/2026 với 6 request tranh 1 phòng cho kết quả 1 thành công, 5 bị từ chối.

## 6. Vòng đời đơn

| Trạng thái | Giữ tồn | Sang trạng thái |
|---|---|---|
| `PENDING_PAYMENT` | Có | `CONFIRMED`, `CANCELLED`, `EXPIRED` |
| `CONFIRMED` | Có | Không đổi trạng thái, chỉ đổi được ngày |
| `CANCELLED` | Không | Không có |
| `EXPIRED` | Không | Không có |

Mọi chuyển trạng thái dùng `UPDATE ... WHERE status = 'PENDING_PAYMENT'`, chỉ trả tồn khi đổi được đúng 1 dòng.

| Thao tác | Điều kiện thêm | Tồn kho |
|---|---|---|
| Thanh toán mô phỏng | Chủ đơn, `payment_expires_at > now` | Không đổi, vì đã giữ khi đặt |
| Hủy | Chủ đơn. Đơn quá hạn nhưng job chưa chạy vẫn hủy được | Trả từng đêm |
| Hết hạn | `payment_expires_at <= now` | Trả từng đêm |

`BookingExpiryJob` chạy theo `booking.expiry.interval`. Mỗi đơn dùng một transaction riêng, nên đơn lỗi không chặn đơn khác. Nếu trả tồn thất bại, transaction rollback để không làm lệch thêm.

## 7. Đổi ngày

`date_change_request` là phiếu của USER. `booking_reschedule` là bản ghi Booking đã áp dụng.

| Trạng thái phiếu | Ý nghĩa |
|---|---|
| `PENDING` | Đang chờ, chưa giữ phòng, đơn chưa đổi. Mỗi đơn tối đa một phiếu |
| `APPROVED` | Booking đã đổi ngày |
| `REJECTED` | Đơn giữ nguyên |

Đơn đổi được khi có trạng thái `CONFIRMED` và `check_in` sau hôm nay. Ngày mới phải khác ngày hiện tại, không ở quá khứ, tối đa 30 đêm. Số đêm được phép thay đổi.

Khi duyệt, Booking khóa dòng đơn bằng `SELECT ... FOR UPDATE`, rồi xét lần lượt mọi đêm của kỳ cũ và kỳ mới theo ngày tăng dần:

| Đêm | Xử lý |
|---|---|
| Chỉ có ở kỳ mới | `sold + roomCount` có điều kiện, không đủ thì `409` |
| Chỉ có ở kỳ cũ | `sold - roomCount` |
| Có ở cả hai | Giữ nguyên, không đòi thêm phòng |

```text
newTotal = totalAmount × số đêm mới ÷ số đêm cũ   (làm tròn 2 chữ số, HALF_UP)
```

`UNIQUE (tenant_id, request_id)` của `booking_reschedule` bảo đảm mỗi phiếu chỉ áp dụng một lần. Thứ tự giữa hai service xem [architecture.md](architecture.md#9-đổi-ngày).

## 8. Thông báo

`notification_outbox` là hàng đợi nằm trong database. Support ghi vào cùng transaction với kết quả phiếu. `processed_at IS NULL` nghĩa là chưa xử lý.

Notification đọc bằng `FOR UPDATE SKIP LOCKED`, nên nhiều bản chạy song song không xử lý trùng và không chờ nhau. `notification.source_event_id` là ID dòng outbox, có `UNIQUE` để mỗi sự kiện chỉ tạo một thông báo. Dòng outbox đã xử lý không bị xóa.

## 9. Tách tenant

- Mọi truy vấn theo người dùng đều có `tenant_id` lấy từ JWT. Riêng các truy vấn của job hết hạn và job thông báo chạy cho mọi tenant.
- Khóa ngoại ghép `(tenant_id, ...)` chặn tham chiếu sang tenant khác ngay trong database. Danh sách ở [erd.md](erd.md#3-khóa-ngoại-theo-tenant).
- Ràng buộc database không thay thế kiểm tra quyền ở service.

## 10. Quyền sở hữu

| Dữ liệu | Chủ sở hữu xác định bằng |
|---|---|
| `booking` | `tenant_id` + `user_email`, so với claim `email` |
| `date_change_request` | `tenant_id` + `requester_user_id`, tra từ `app_user` theo JWT |
| `notification` | `tenant_id` + `recipient_user_id`, tra từ `app_user` theo JWT |

Booking dùng email để giữ tương thích với hợp đồng ban đầu, các bảng mới dùng user ID. Đổi email của tài khoản sẽ làm chủ đơn cũ mất quyền với đơn.

## 11. Đối soát tồn kho

Truy vấn sau phải trả 0 dòng:

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

Nếu có dòng lệch, tìm nguyên nhân trước khi sửa `sold`. `backend/test/10-check-db.sh` đã chạy sẵn truy vấn này.

## 12. Giới hạn của mô hình

- Chưa có phòng vật lý, giá theo ngày, thanh toán thật hay hoàn tiền.
- Một người chỉ thuộc một tenant.
- `booking` không có idempotency key.
- Chưa có bảng audit riêng và chính sách lưu trữ dữ liệu.
- `reschedule` chỉ lưu lịch sử trong `booking_reschedule`, không có API đọc.
