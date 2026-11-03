# Ghi chú nghiệp vụ đặt phòng

Owner: Phúc (C). Phần "Demo đang làm" bám theo [erd-v2.md](erd-v2.md). Phần "Thực tế" là kiến thức nghiệp vụ để trình bày, demo không code.

## Quy tắc trong demo

- Mỗi `room` là một phòng cụ thể, đặt theo khoảng thời gian `[checkIn, checkOut)` có giờ thật. Một phòng có nhiều booking được, miễn không đè giờ nhau.
- Trả phòng đúng lúc người sau nhận phòng thì không tính là đè giờ.
- Chỉ role `USER` được đặt phòng. `ADMIN` xem được mọi booking trong tenant của mình nhưng không đặt.
- Đặt được là booking `CONFIRMED` ngay. Đây là trạng thái duy nhất trong demo.
- `tenantId` và `userEmail` chỉ lấy từ JWT đã xác minh, không lấy từ dữ liệu client gửi. Email được đổi về chữ thường trước khi lưu.
- Booking không lưu giá, vì bảng `booking` trong ERD không có cột giá.

## So sánh với thực tế

| Chủ đề | Thực tế | Demo đang làm |
| --- | --- | --- |
| Giữ chỗ | Giữ tồn kho theo từng đêm trong một khoảng thời gian ngắn, hết hạn thì tự trả | Không có bước giữ chỗ, `INSERT` được là đặt xong |
| Thanh toán | Khách trả trước, trả một phần hoặc trả tại khách sạn. Booking chỉ xác nhận sau khi thanh toán hoặc bảo đảm thành công | Không có thanh toán |
| Hủy phòng | Theo chính sách của từng loại giá: miễn phí đến một mốc, sau đó mất phí, hoặc không hoàn | Không có hủy |
| No-show | Khách không đến: khách sạn thường thu phí đêm đầu hoặc toàn bộ, rồi trả phần phòng còn lại | Không xử lý |
| Hoàn tiền | Tính theo chính sách hủy, hoàn về phương thức đã thanh toán | Không có hoàn tiền |
| Giá | Lưu lại giá tại thời điểm đặt, để đổi giá sau này không ảnh hưởng booking cũ | Giá thuộc Catalog, booking không lưu |
| Giờ nhận và trả phòng | Khách sạn quy định giờ cố định, thường trả phòng sớm hơn giờ nhận để có thời gian dọn | Client tự gửi giờ, service không áp giờ quy định |

## Hai người tranh một khoảng giờ

Ràng buộc `EXCLUDE` trên `(room_id, tstzrange(check_in, check_out))` do database kiểm tra nguyên tử, nên hai booking đè giờ không thể cùng tồn tại. Nếu `SELECT` kiểm tra còn trống rồi mới `INSERT`, cả hai request có thể cùng thấy phòng trống và đều đặt được. Vì vậy service chỉ `INSERT` rồi đổi lỗi của database thành `409`.

`EXCLUDE` chỉ xét `room_id` và chạy trước các FK, nên phòng của tenant khác cũng có thể báo đè giờ. Khi đó service kiểm tra lại phòng có thuộc tenant của người gọi không và trả `404` nếu không thuộc, để không lộ lịch đặt phòng của tenant khác.

## Mua vé khác đặt phòng ở đâu

| | Đặt phòng khách sạn | Mua vé (máy bay, sự kiện) |
| --- | --- | --- |
| Đơn vị tồn kho | Số phòng theo từng đêm, phòng cùng loại thường thay thế được cho nhau | Một ghế hoặc suất cụ thể theo chuyến hoặc suất diễn |
| Thời gian | Một khoảng nhiều đêm, phải còn chỗ ở mọi đêm | Một thời điểm cố định |
| Giữ chỗ | Có thể giữ lâu hơn, nhiều nơi cho trả tại khách sạn | Giữ rất ngắn trong lúc thanh toán |
| Kết quả | Mã xác nhận, nhận phòng tại quầy | Vé điện tử, thường cần phát hành và soát vé |
| Thay đổi, hủy | Thường linh hoạt hơn | Thường chặt hơn, có thể không hoàn hoặc tính phí đổi |

## Giới hạn cần nói rõ khi demo

1. **Chưa chặn đặt phòng trong quá khứ:** ERD chưa có quy tắc này nên service nhận mọi thời điểm từ năm 2000 đến 9999, chỉ yêu cầu `checkOut` sau `checkIn`.
2. **Phụ thuộc qua bảng dùng chung:** Booking phụ thuộc trực tiếp vào bảng `room` và `app_user` của service khác qua FK, thay vì gọi API. Đổi lại, database tự chặn dữ liệu sai tenant, và thứ tự migrate bắt buộc là IAM → Catalog → Booking.
3. **Không có idempotency key:** gửi lại cùng request sẽ nhận `409` vì đè giờ với chính booking trước đó, không tạo booking thứ hai, nhưng client không nhận lại được booking đó qua API tạo mới.
4. **Có thể chậm khi tranh chấp đồng thời:** các `INSERT` đè giờ cùng lúc có thể chờ lẫn nhau cho đến khi PostgreSQL phát hiện deadlock và hủy một bên (mặc định sau 1 giây). Bên bị hủy vẫn nhận `409`. Kết quả đúng nhưng phản hồi có thể mất vài giây khi nhiều request cùng tranh một khoảng giờ.
