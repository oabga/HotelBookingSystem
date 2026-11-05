# Build và rebuild Docker local

Tài liệu này dùng cho backend stack trong `backend/docker-compose.yml`. Lệnh build và chạy đầy đủ stack giống nhau ở lần đầu và những lần cập nhật sau.

## Một lệnh build/rebuild

Mở Terminal tại thư mục `backend`:

```bash
cd /Volumes/DATA/JIKAY/src/project-demo/HotelBookingSystem/backend
docker compose --env-file .env.local --profile workflow up -d --build --wait
```

Hoặc gọi từ thư mục bất kỳ:

```bash
docker compose \
  --env-file /Volumes/DATA/JIKAY/src/project-demo/HotelBookingSystem/backend/.env.local \
  -f /Volumes/DATA/JIKAY/src/project-demo/HotelBookingSystem/backend/docker-compose.yml \
  --profile workflow up -d --build --wait
```

`--profile workflow` bật toàn bộ backend workflow. `--build` dựng image cho các service có khai báo `build:`; Docker dùng lại các layer còn hợp lệ trong cache. Compose sẽ tạo lại container khi image hoặc cấu hình service thay đổi, rồi `--wait` đợi các service healthy/running.

Trong stack này, các service Spring Boot được build là IAM, Catalog, Booking, Support, Notification và API Gateway. PostgreSQL, Adminer và `dev-token` dùng image có sẵn nên Compose khởi động chúng, không build lại image ứng dụng. `.env.local` cung cấp Google OAuth credentials và cấu hình issuer/JWKS để API xác minh JWT do IAM phát hành; luôn dùng `--env-file .env.local` khi chạy Google SSO.

## Build image mà chưa khởi động

Nếu chỉ muốn build images:

```bash
cd /Volumes/DATA/JIKAY/src/project-demo/HotelBookingSystem/backend
docker compose --env-file .env.local --profile workflow build
```

Lệnh `build` không tạo lại container đang chạy. Để cập nhật app sau khi build, chạy tiếp lệnh `up -d --wait` ở trên.

## Database và seed local

Rebuild không xóa volume `hotelbooking-pgdata`, nên dữ liệu PostgreSQL được giữ lại. Không chạy `docker compose down -v` trừ khi chủ ý xóa database local.

Seed Google SSO ở `docker/seed/google-sso-users.local.sql` là file local và không tự chạy khi build. Áp dụng hoặc chạy lại seed bằng:

```bash
docker exec -i hotelbooking-postgres psql -v ON_ERROR_STOP=1 -U hotelbooking -d hotelbooking \
  < docker/seed/google-sso-users.local.sql
```

## Kiểm tra trạng thái

```bash
docker compose --env-file .env.local --profile workflow ps
docker compose --env-file .env.local --profile workflow logs --tail=100 iam-service api-gateway
```

`docker-compose.yml` chỉ chứa backend stack. Frontend demo hiện chạy riêng từ thư mục project:

```bash
cd /Volumes/DATA/JIKAY/src/project-demo/HotelBookingSystem
node frontend/server.js
```

Frontend server đọc các file hiện tại khi có request; tải lại trình duyệt sau khi sửa giao diện.
