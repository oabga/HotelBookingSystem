# StayHub frontend demo

App 1, App 2 và Support Center là các trang HTML/JavaScript thuần ở ba origin. App 1 và App 2 gọi backend theo API Contract bản 3.0. Bản này đã bị xoá khỏi `main`; xem lại bằng `git show 45d964e^:backend/docs/original/api-contract.md`. Giao diện theo role trong JWT, không theo số ứng dụng. Frontend không xác minh chữ ký JWT và không tự chứng minh Google SSO.

## Chạy hai trang

Cần Node.js. Từ thư mục gốc repository, chạy:

```bash
node frontend/server.js
```

Mở `http://localhost:3000/` (App 1), `http://localhost:3001/` (App 2) và `http://localhost:3002/` (Support Center). Server cũng phục vụ cùng giao diện tại `/callback/` của mỗi origin. Dừng bằng `Ctrl+C`.

## Hành vi hiện có

- **Đăng nhập:** nút login chuyển toàn trang tới `http://localhost:8084/auth/start?app=app1` hoặc `app2`. Trang callback đọc `#token=<jwt>`, xoá fragment ngay, giữ token trong bộ nhớ của trang. Tải lại trang sẽ mất token; không dùng localStorage, sessionStorage hay JWT hard-code.
- **Tìm phòng (USER, ADMIN):** chọn khách sạn từ `GET /api/hotels`, chọn ngày, bấm **Tìm kiếm** mới gọi `GET /api/hotels/{id}/availability`. Đổi điều kiện hoặc đặt phòng xong sẽ xoá kết quả cũ; frontend không tự tìm lại.
- **Đặt phòng (USER):** gửi `POST /api/bookings` với `roomTypeId`, `checkIn`, `checkOut`, `roomCount`. Mọi nút Đặt bị khoá tới khi có response và không tự gửi lại, vì gửi lại sẽ tạo đơn thứ hai.
- **Booking:** USER xem đơn của mình, thanh toán mô phỏng và huỷ đơn `PENDING_PAYMENT`. ADMIN xem mọi đơn trong tenant nhưng không có nút thao tác.
- **ADMIN:** xem tồn kho theo đêm, thêm loại phòng và sửa giá cho khách sạn đang chọn. Danh sách loại phòng để sửa giá lấy từ tồn kho đã tải, vì contract không có API liệt kê loại phòng.
- **Lỗi:** giao diện chỉ dựa vào HTTP status, không đọc `detail`. 401 ở bất kỳ API nào sẽ báo đăng nhập lại. Khi mất kết nối lúc ghi, giao diện báo chưa rõ kết quả thay vì coi là thất bại.

- **Support Center (`:3002`):** ứng dụng riêng, đăng nhập qua cùng IAM nên có phiên rồi thì không phải nhập lại mật khẩu. USER tạo ticket, có thể gắn booking của mình, và trả lời ticket của mình. ADMIN xem ticket trong tenant, trả lời và đổi trạng thái theo đúng bước kế tiếp. Xem API ở `backend/support-service/README.md`.

## Kiểm tra local

```bash
node --test frontend/*.test.js
```

Test dùng DOM và `fetch` giả lập trong `test-support.js`: **MOCK ONLY**, không phải bằng chứng tích hợp.

Muốn thử với backend thật mà chưa có Google login, xem profile `dev-test` trong `backend/README.md`. Profile này không có Gateway, nên phải chạy thêm `api-gateway` với `SECURITY_JWT_ISSUER=http://127.0.0.1:8090` và `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI=http://127.0.0.1:8090/.well-known/jwks.json`, rồi mở `http://localhost:3000/callback/#token=<token từ dev-token>`. Cách này không chứng minh Google SSO.

## Giới hạn

- Booking chỉ trả `roomTypeId`, nên danh sách booking hiển thị ID loại phòng thay vì tên.
- Số phòng trống và hạn thanh toán không tự cập nhật; bấm **Tìm kiếm** hoặc **Tải lại**.
- Frontend tin claim trong JWT chỉ để hiển thị; Gateway và các service mới là nơi xác minh quyền.
