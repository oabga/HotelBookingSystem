# Catalog API Quick Check

Trang này giúp thử trực tiếp 5 endpoint của `catalog-service` mà không cần đăng nhập Google. Docker Compose cũng dùng server này làm `dev-token`.

**JWT do dev harness ký tạm**, issuer/JWKS cũng do harness cung cấp. Nó kiểm tra xác thực JWT, role và tenant ở Catalog, nhưng không kiểm tra Google login, IAM, Gateway hoặc CORS production. Khóa RSA được tạo mới trong RAM mỗi lần chạy; token chỉ ở memory của trang và hết hạn sau một giờ. Chỉ bind vào loopback `127.0.0.1`.

## Chạy

Terminal 1 — từ thư mục này:

```bash
cd test-api-catalog
node server.mjs
```

Terminal 2 — từ `backend/`, khởi động Catalog với issuer/JWKS tạm của harness:

```bash
cd backend
SECURITY_JWT_ISSUER=http://127.0.0.1:8090 \
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI=http://127.0.0.1:8090/.well-known/jwks.json \
./gradlew :catalog-service:bootRun
```

Mở <http://127.0.0.1:8090>. Chọn role/tenant rồi bấm **Tạo token local**. Dùng USER để thử đọc; đổi sang ADMIN để tạo loại phòng, đổi giá và xem inventory. Request đi qua proxy cục bộ và được chuyển trực tiếp đến `catalog-service:8081`.

## Kịch bản thử nhanh

1. Tạo `USER`, tenant 1 → `GET /hotels` trả hotel ID 1 và 3.
2. `GET availability`, hotel 1, ngày `2026-11-24` đến `2026-11-26` → ban đầu có room type 1 và 2.
3. Giữ USER → inventory và POST room type phải trả `403`.
4. Tạo `ADMIN`, tenant 1 → inventory hotel 1 trả `200`; POST room type trả `201`.
5. Bật checkbox `tenantId: 999`; response vẫn phải có `tenantId` bằng tenant trong token.
6. Tìm availability cùng kỳ → loại phòng mới có thể xuất hiện; sửa giá một loại phòng → `200`.
7. Tạo `USER`, tenant 2 → hotels chỉ có ID 2; availability hotel 1 trả `404`.

Ngày mẫu bám theo seed đến hết `2027-01-31`. Sau khi POST tạo loại phòng hoặc PUT sửa giá, dữ liệu DB đã đổi. Reset toàn bộ database demo về seed ban đầu bằng lệnh dưới đây (lệnh xóa volume PostgreSQL):

```bash
cd backend
docker compose down -v
docker compose up -d postgres
./gradlew :iam-service:bootRun
# Đợi log "Successfully applied 2 migrations", rồi Ctrl+C.
./gradlew :catalog-service:bootRun
# Đợi log "Successfully applied 2 migrations", rồi Ctrl+C.
```

## Xem database

```bash
docker exec -it hotelbooking-postgres psql -U hotelbooking -d hotelbooking
```

Trong `psql`:

```sql
\dt
\d hotel
\d room_type
\d room_inventory

SELECT version, description, success
FROM flyway_schema_history_catalog
ORDER BY installed_rank;

SELECT count(*) AS hotels FROM hotel;                  -- 3 sau reset
SELECT count(*) AS room_types FROM room_type;           -- 6 sau reset
SELECT count(*) AS inventory_rows FROM room_inventory;  -- 552 sau reset
SELECT DISTINCT sold FROM room_inventory;               -- 0 sau reset

SELECT h.tenant_id, h.id AS hotel_id, h.name AS hotel,
       rt.id AS room_type_id, rt.name AS room_type,
       rt.base_price, rt.total_rooms
FROM hotel h
JOIN room_type rt ON rt.tenant_id = h.tenant_id AND rt.hotel_id = h.id
ORDER BY h.id, rt.id;

SELECT tenant_id, room_type_id, stay_date, total, sold, total - sold AS available
FROM room_inventory
WHERE stay_date BETWEEN DATE '2026-11-24' AND DATE '2026-11-25'
ORDER BY tenant_id, room_type_id, stay_date;
```

Thoát `psql` bằng `\q`. Không gửi JWT hoặc secret trong log; khi gửi kết quả API, bỏ token khỏi ảnh/chụp màn hình.
