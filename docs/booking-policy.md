# Ghi chú nghiệp vụ đặt phòng

Owner: Phúc (C). Phần demo bám theo [erd-v2.md](erd-v2.md) và [api-contract.md](api-contract.md).

## Quy tắc trong demo

- Khách chọn khách sạn, ngày nhận và trả phòng, rồi bấm **Tìm kiếm**; chỉ khi đó hệ thống mới trả các loại phòng còn tồn đủ mọi đêm.
- Đơn vị bán là loại phòng (`room_type`), không phải phòng cụ thể. Một dòng `room_inventory` là số phòng bán được của một loại phòng trong một đêm.
- Kỳ ở là `[checkIn, checkOut)`: tính đêm nhận phòng, không tính đêm trả phòng. `room_type.base_price` là giá cho **một phòng, một đêm**; tổng tiền được chốt khi tạo booking: `base_price × số đêm × roomCount`.
- `room_type` không có trường `code`. `hotel` lưu `address` (địa chỉ), không dùng `city`.
- `PENDING_PAYMENT` giữ tồn ngay để người khác không thể đặt số phòng đó trong cùng kỳ. Thanh toán mô phỏng chuyển `PENDING_PAYMENT → CONFIRMED`; cả hai trạng thái đều tiếp tục giữ tồn.
- Khi chủ đơn huỷ booking đang `PENDING_PAYMENT`, trạng thái chuyển sang `CANCELLED` và tồn được trả lại cho tất cả đêm trong kỳ. Booking `CANCELLED` không còn giữ tồn, nên lần tìm kiếm sau có thể hiển thị phòng cho user khác.
- Đơn `PENDING_PAYMENT` quá hạn chuyển sang `EXPIRED` và cũng trả tồn. Cả đổi trạng thái và cập nhật tồn phải nằm trong cùng transaction; chỉ trả tồn nếu trạng thái thực sự được đổi.
- `USER` chỉ xem và huỷ booking của mình. `ADMIN` mới được xem tổng tồn (`total`, `sold`, `available`) và quản trị số lượng tồn trong tenant. API tìm kiếm công khai cho user chỉ trả số phòng còn có thể đặt, không tiết lộ tổng tồn.
- `tenantId`, email người đặt, giá và trạng thái booking do server xác định từ JWT/database; client không thể tự gửi để ghi đè.
- Không xoá booking đã huỷ/hết hạn; giữ lịch sử. Huỷ lặp lại không được trừ tồn lần thứ hai.

## Hai người cùng đặt

Booking tăng `room_inventory.sold` có điều kiện cho từng đêm, theo thứ tự ngày tăng dần, rồi thêm booking trong cùng transaction `READ COMMITTED`. Nếu bất kỳ đêm nào không đủ tồn hoặc chưa mở bán, rollback toàn bộ và trả `409`. Như vậy hai request đồng thời không thể bán vượt `total`.

Tìm kiếm chỉ là ảnh chụp tại thời điểm đọc; booking phải kiểm tra và trừ tồn lại. Không dùng `SELECT` availability làm cơ chế giữ phòng. Booking và tồn phải cùng commit hoặc cùng rollback.

## So sánh với thực tế

| Chủ đề | Thực tế | Demo đang làm |
| --- | --- | --- |
| Tồn kho | Có thể quản lý theo loại phòng, ngày, kế hoạch bảo trì và phân bổ kênh | `room_inventory` lưu `total`/`sold` theo từng đêm |
| Thanh toán | Có thể trả trước, một phần hoặc tại khách sạn; cần đối soát giao dịch | Mô phỏng thành công, không kết nối cổng thanh toán |
| Huỷ phòng | Tuỳ chính sách giá, thời điểm, phí huỷ và hoàn tiền | User huỷ được khi `PENDING_PAYMENT`; đổi sang `CANCELLED` và trả tồn |
| Giá | Có thể thay đổi theo ngày, mùa, kênh và điều kiện giá | Một `base_price` cho mỗi loại phòng, áp dụng mỗi đêm; snapshot vào `total_amount` |
| Phân phòng | Gán số phòng cụ thể gần ngày nhận phòng | Không gán số phòng; demo bán tồn theo loại phòng |

## Giới hạn cần nói rõ khi demo

1. Tồn kho được tạo sẵn trong khoảng ngày mở bán theo thiết kế tại `erd-v2.md`; ngày không có dòng inventory không được coi là còn phòng.
2. `sold` khớp với booking là trách nhiệm service: phải cập nhật cùng transaction ở bước tạo, huỷ và hết hạn. Truy vấn đối soát tại `erd-v2.md` §8 phải trả 0 dòng.
3. Không có idempotency key; gửi lại yêu cầu đặt có thể tạo booking khác nếu vẫn còn tồn.
4. Thanh toán chỉ mô phỏng; không có hoàn tiền, phí huỷ hoặc giao dịch tiền thật.
5. `base_price` không biến thiên theo ngày. Đổi giá chỉ ảnh hưởng booking mới, booking cũ giữ `total_amount` đã chốt.
