# StayHub frontend demo

App 1 và App 2 là hai trang HTML/JavaScript thuần ở hai origin. Nút **Login with Google** giữ luồng IAM đã chuẩn bị; sau khi nhận JWT, trang gọi GET Rooms, USER có thể gửi POST Booking và ADMIN có thể tạo phòng/sửa giá theo API Contract v2.0. Frontend không xác minh chữ ký JWT và không tự chứng minh Google SSO.

## Chạy hai trang

Cần Node.js. Từ thư mục gốc repository, chạy:

```powershell
node frontend-demo/server.js
```

Mở `http://localhost:3000/` (App 1) và `http://localhost:3001/` (App 2). Server cũng phục vụ cùng giao diện tại `http://localhost:3000/callback/` và `http://localhost:3001/callback/`, cùng các file JS/CSS trên mỗi origin. Dừng bằng `Ctrl+C`.

## Hành vi hiện có

- Nút login chuyển **toàn trang** tới `http://localhost:8084/auth/start?app=app1` hoặc `app2`. IAM phải chạy để đăng nhập Google thật.
- Trang callback đọc `#token=<jwt>`, xóa fragment ngay bằng `history.replaceState`, giữ token trong bộ nhớ của trang và hiển thị `email`, `role`, `tenant_id` bằng `textContent`. Các claim này **chỉ phục vụ giao diện, chưa được frontend xác minh**; Gateway và service phải xác minh JWT cho các request API mà frontend đã triển khai theo Contract v2.0.
- `#error=not_provisioned` và `#error=login_failed` hiển thị lỗi, không giữ token. IAM trả **HTTP 403 tại IAM** cho user chưa provision theo contract v2.0; hai fragment lỗi chỉ là nhánh xử lý giao diện nếu xuất hiện, không phải bằng chứng IAM đã làm đúng.
- Khi có JWT trong memory, `/rooms.js` gọi `GET http://localhost:8080/api/rooms` với Bearer header, hiển thị ID, tên và giá VND. Không có JWT thì không gọi API. Giao diện phân biệt đang tải, 200 (kể cả danh sách rỗng), 401, 403, JSON không hợp lệ và lỗi kết nối Gateway/CORS. Frontend **không** dùng `room.available` để suy ra phòng trống.
- USER chọn phòng từ danh sách đã tải, nhập check-in/check-out, rồi frontend gửi `POST http://localhost:8080/api/bookings` với `roomId`, `checkIn`, `checkOut` (giờ UTC có `Z`). Giao diện báo 201 CONFIRMED từ response hợp lệ hoặc lỗi 400/401/403/404/409/500/network; không tự retry.
- ADMIN thấy form tạo phòng (`POST http://localhost:8080/api/rooms`) và chọn phòng đã tải để sửa giá (`PUT http://localhost:8080/api/rooms/{id}/price`). Giao diện kiểm tra tên/giá, hiển thị Room response hợp lệ vào danh sách, báo lỗi theo HTTP status và không tự retry. USER không thấy phần ADMIN. Role chỉ điều khiển giao diện; backend phải xác minh JWT và quyền. Vùng xem booking vẫn chưa kết nối.
- `window.stayHubAuth.bearerHeaders()` cung cấp header Bearer trong cùng trang. Tải lại trang sẽ mất token. Không dùng localStorage, sessionStorage hoặc JWT hard-code.

## Kiểm tra local

```powershell
node --test frontend-demo/*.test.js
```

Test dùng JWT **tổng hợp, không ký** và `fetch` giả lập để kiểm tra giao diện: **MOCK ONLY**. Chưa có bằng chứng E2E với backend thật; đăng nhập và các thao tác API cần JWT, IAM, Gateway và service của A/B/C. Khi server chạy, bốn URL `/` và `/callback/` nêu trên phải trả HTTP 200.

## Phụ thuộc còn lại

- **A:** IAM :8084, Google OAuth/PKCE S256, callback allowlist `/callback/`, JWT/JWKS, issuer và CORS/Gateway.
- **B:** Catalog/Rooms service đang chạy theo contract v2.0; response thực của POST/PUT/GET, phòng trong tenant và cách reset dữ liệu để D nghiệm thu tích hợp ADMIN.
- **C:** Booking service, cách reset booking và các response/lỗi thực để D nghiệm thu tích hợp Booking.
