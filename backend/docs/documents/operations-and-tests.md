# StayHub — Operations & Tests

> **Cập nhật:** 05/11/2026, đối chiếu với `main` tại commit `1737b60`
> **Môi trường:** Chỉ dùng cho máy local. Hệ thống chưa đạt mức production.

Các lệnh dưới đây chạy từ thư mục `backend/` nếu không ghi khác. Trên Windows, dùng Git Bash.

## 1. Thành phần khi chạy

| Thành phần | Local (Gradle) | Docker Compose, cổng host |
|---|---|---|
| API Gateway | `8080` | `8080` |
| Catalog | `8081` | `8081` |
| Booking | `8082` | `8082` |
| Notification | `8083` | `8086` |
| IAM | `8084` | `8084` |
| Support | `8085` | `8085` |
| PostgreSQL | `5432` | `5432` |
| Adminer | Không có | `8083` |
| `dev-token` | `8090` | `8090` |
| Frontend | `3000`, `3001`, `3002` | Không nằm trong Compose |

Mọi cổng Compose chỉ bind vào `127.0.0.1`. Chạy frontend từ thư mục gốc repository:

```bash
node frontend/server.js
```

## 2. Cấu hình

| Biến | Dùng ở | Mặc định | Ý nghĩa |
|---|---|---|---|
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | IAM | Bắt buộc khi chạy Gradle. Compose dùng giá trị giả | OAuth client của Google |
| `IAM_ADMIN_EMAIL` | IAM | Rỗng. Compose đặt sẵn một email | Email duy nhất được vào App Admin |
| `IAM_REGISTRATION_DEFAULT_TENANT_ID` | IAM | `1` | Tenant cho tài khoản tự tạo |
| `SECURITY_JWT_ISSUER` | IAM, Gateway, service | `http://localhost:8084`. Compose: xem dưới | `iss` của JWT |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` | Gateway, service | JWKS của IAM. Compose: xem dưới | Nơi lấy public key |
| `SPRING_DATASOURCE_URL`, `_USERNAME`, `_PASSWORD` | Service có DB | `localhost:5432`, `hotelbooking` | Kết nối database |
| `CATALOG_SERVICE_URL`, `BOOKING_SERVICE_URL`, `SUPPORT_SERVICE_URL`, `NOTIFICATION_SERVICE_URL` | Gateway | `localhost` | Đích định tuyến |
| `BOOKING_BASE_URL` | Support | `http://localhost:8082` | Địa chỉ Booking |

Cấu hình trong `application.yml`:

| Thuộc tính | Mặc định | Ý nghĩa |
|---|---|---|
| `security.jwt.ttl-minutes` | `15` | Hạn JWT |
| `booking.payment.hold` | `PT15M` | Thời gian giữ phòng |
| `booking.expiry.interval` | `PT60S` | Chu kỳ job hết hạn |
| `notification.delivery.interval` | `PT2S` | Chu kỳ job thông báo |

Compose chỉ truyền các biến khai báo trong `docker-compose.yml`. Vì vậy `booking.payment.hold` và `booking.expiry.interval` không đổi được qua biến môi trường của Compose nếu không sửa file đó. Khi test hết hạn, chạy Booking bằng Gradle kèm `--args`:

```bash
./gradlew :booking-service:bootRun --args="--booking.payment.hold=PT1M --booking.expiry.interval=PT5S"
```

### 2.1. JWT trong Compose

Compose mặc định để Gateway và các service tin `dev-token`:

```text
SECURITY_JWT_ISSUER=http://127.0.0.1:8090
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI=http://dev-token:8090/.well-known/jwks.json
```

Với cấu hình này, JWT do IAM ký sẽ bị `401`. Khi đăng nhập Google thật, tạo `backend/.env.local`:

```bash
GOOGLE_CLIENT_ID=...
GOOGLE_CLIENT_SECRET=...
SECURITY_JWT_ISSUER=http://localhost:8084
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI=http://iam-service:8084/.well-known/jwks.json
IAM_ADMIN_EMAIL=...
```

File này không được commit. Trên Google Cloud Console, đăng ký redirect URI `http://localhost:8084/login/oauth2/code/google`.

### 2.2. Múi giờ khi chạy bằng Gradle

Trên Windows ở Việt Nam, JVM báo múi giờ `Asia/Saigon` và `postgres:16` từ chối kết nối. Đặt:

```bash
export JAVA_TOOL_OPTIONS=-Duser.timezone=UTC
```

## 3. Chạy hệ thống

Stack đầy đủ với Google thật:

```bash
docker compose --env-file .env.local --profile workflow up -d --build --wait
```

Stack đầy đủ với `dev-token`, không cần Google:

```bash
docker compose --profile workflow up -d --build --wait
```

Xem [../build/README.md](../build/README.md) để biết chi tiết về build. Lần đầu có thể mất hơn 10 phút.

Sau khi rebuild hoặc restart riêng IAM, restart cả Gateway và các service, vì lý do ở [iam-sso.md](iam-sso.md#81-quản-lý-key):

```bash
docker compose --env-file .env.local --profile workflow restart api-gateway catalog-service booking-service support-service notification-service
```

Dừng và giữ dữ liệu:

```bash
docker compose --profile workflow down
```

`docker compose --profile workflow down -v` sẽ xóa database local. Chỉ dùng khi chủ ý xóa dữ liệu.

## 4. Migration

- Mỗi service tự chạy Flyway khi khởi động, theo thứ tự IAM, Catalog, Booking, Support, Notification.
- Migration đã áp dụng không được sửa. Mọi thay đổi schema dùng version mới.
- `hibernate.ddl-auto` là `validate`, nên service không tự tạo bảng.
- Migration của service này có thể phụ thuộc bảng của service khác. Ví dụ Support `V1` thêm ràng buộc vào `app_user` và `booking`.

Khi đổi schema trên database có dữ liệu cần giữ: thêm mới, chuyển dữ liệu, kiểm tra, rồi mới dọn phần cũ ở migration sau.

Kiểm tra trạng thái:

```bash
docker exec hotelbooking-postgres psql -U hotelbooking -d hotelbooking \
  -c "SELECT version, description, success FROM flyway_schema_history_booking;"
```

## 5. Xem và reset dữ liệu

- Adminer: `http://127.0.0.1:8083`, server `postgres`, user, password và database đều là `hotelbooking`.
- Reset đơn phải đưa `sold` về 0 cùng lúc, nếu không tồn kho sẽ lệch:

```sql
BEGIN;
TRUNCATE booking RESTART IDENTITY CASCADE;
UPDATE room_inventory SET sold = 0;
COMMIT;
```

`CASCADE` xóa luôn `booking_reschedule` và toàn bộ `date_change_request`. `notification_outbox` và `notification` không có khóa ngoại sang `booking` nên không bị xóa.

Truy vấn đối soát nằm ở [data-model.md](data-model.md#11-đối-soát-tồn-kho).

## 6. Seed và dữ liệu mẫu

| File | Dùng khi | Chạy thế nào |
|---|---|---|
| Migration Flyway | Luôn có | Tự chạy. Xem [erd.md](erd.md#6-seed-trong-migration) |
| `docker/seed/seed-app-users.dev-test.sql` | JWT của `dev-token`, email `<role>-tenant<id>@catalog.local.test` | `test/00-up.sh` tự chạy |
| `docker/seed/seed-app-users.example.sql` | Mẫu tài khoản Google. Sao chép thành `seed-app-users.local.sql`, file này không được commit | Chạy tay |
| `data/generate-erd-mock-data.sh` | Đơn mẫu tháng 12/2026 của user `mock.*@stayhub.example.test` | `test/00-up.sh` tự chạy |

Với Google thật, App 1 tự tạo tài khoản `USER` nên không bắt buộc seed. Tài khoản ADMIN khác `IAM_ADMIN_EMAIL` chỉ dùng được Support.

## 7. Test

### 7.1. Backend

Cần JDK 21. Test tích hợp cần Docker.

| Module | Test | Cần Docker | Khi thiếu Docker |
|---|---|---|---|
| `iam-service` | `JwtIssuerTest`, `SsoLoginServiceTest`, `JwksControllerTest` | Không | |
| `api-gateway` | `GatewayCorsTest`, `StayHubJwtValidatorTest` | Không | |
| `catalog-service` | `CatalogSecurityApiTest`, `StayHubJwtValidatorTest`, `CatalogServiceTest`, `DateRangeValidatorTest` | Không | |
| `catalog-service` | `CatalogMigrationIntegrationTest` | Có | Bị bỏ qua, build vẫn xanh |
| `booking-service` | `BookingApiTest`, `BookingExpiryJobTest`, `BookingServiceTest` | Không | |
| `booking-service` | `BookingFlowIntegrationTest` | Có | Bị bỏ qua, build vẫn xanh |
| `booking-service` | `BookingRescheduleIntegrationTest` | Có | Fail |
| `support-service` | `DateChangeFlowIntegrationTest` | Có | Fail |
| `notification-service` | `NotificationFlowIntegrationTest` | Có | Fail |

```bash
./gradlew test
./gradlew :booking-service:test
```

Khi lấy bằng chứng, phải bật Docker và kiểm tra report không có test bị bỏ qua.

`SsoLoginServiceTest` chưa có ca nào cho chính sách theo app, tạo tài khoản và `IAM_ADMIN_EMAIL`.

### 7.2. Frontend

```bash
node --test frontend/*.test.js
```

Test dùng DOM và `fetch` giả lập, không phải bằng chứng tích hợp.

### 7.3. Workflow trên Docker

```bash
bash test/run-all.sh
```

Script build và khởi động stack, seed, kiểm tra migration và đối soát, rồi gọi API qua Gateway bằng JWT của `dev-token`. Script không xóa volume và tự dọn dữ liệu mình tạo. Script thêm tài khoản test và đơn mẫu vào database đang dùng. Muốn giữ nguyên dữ liệu local, chạy bằng project Compose và volume riêng.

Trên Windows, cần `bash` và lệnh `python3`. Chi tiết ở [test/README.md](../../test/README.md).

Script không kiểm tra Google login.

## 8. Kết quả đã có

Chạy ngày 05/11/2026 trên commit `1737b60`, stack Docker riêng, database mới:

| Kiểm tra | Kết quả |
|---|---|
| Migration của 5 service | Thành công |
| Đối soát tồn kho | 0 dòng lệch |
| Workflow API (22 kiểm tra) | Đạt |
| Tranh phòng: 6 request cho 1 phòng | 1 thành công, 5 bị từ chối |
| Luồng đặt, thanh toán, đổi ngày, thông báo | Đạt |
| `node --test frontend/*.test.js` | 103/103 |
| `:iam-service:test`, `:api-gateway:test` | 7/7, 8/8 |

Chưa chạy: đăng nhập Google thật, test của Catalog, Booking, Support và Notification trong lần này, kiểm thử tải.

## 9. Trước khi coi một tính năng là xong

- Có code, migration và test tương ứng.
- Test tích hợp liên quan đã chạy với Docker, không bị bỏ qua.
- Đối soát tồn kho trả 0 dòng nếu thay đổi chạm vào tồn kho.
- Đăng nhập Google được chạy thật nếu thay đổi chạm vào IAM, theo [iam-testing.md](iam-testing.md).
- Tài liệu nguồn chuẩn đã được cập nhật.
- Không commit client secret, JWT, private key, cookie phiên hay email thật.

## 10. Giới hạn vận hành

- Chưa có metric, tracing hay cảnh báo. Chỉ có log.
- Chưa có kiểm thử tải.
- Repository chưa có CI.
- Danh sách giới hạn về kiến trúc nằm ở [architecture.md](architecture.md#12-giới-hạn-đã-biết).
