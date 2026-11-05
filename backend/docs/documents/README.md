# StayHub — Technical Documentation

> **Cập nhật:** 05/11/2026, đối chiếu với `main` tại commit `1737b60`
> **Trạng thái:** Mô tả hệ thống đang có trong code. Mức kiểm chứng ghi ở [mục 6](#6-mức-độ-kiểm-chứng).
> **Phạm vi:** App 1, App Admin, Support Center, IAM, API Gateway, Catalog, Booking, Support, Notification

## 1. Tổng quan

StayHub là hệ thống đặt phòng khách sạn theo microservices. Người dùng đăng nhập bằng Google qua IAM, nhận JWT do IAM ký, rồi gọi API qua Gateway. Hệ thống bán phòng theo loại phòng và tồn kho từng đêm, thanh toán chỉ là mô phỏng, và cho phép xin đổi ngày sau khi đã thanh toán.

```text
                    Google OIDC
                         |
                         v
                  IAM Service :8084
       Google login, phiên SSO, JWT RS256, JWKS
                         |
                         | 302 callback/#token=JWT
          +--------------+--------------+
          v              v              v
       App 1         App Admin    Support Center
       :3000           :3001          :3002
          |              |              |
          +--------------+--------------+
                         | Authorization: Bearer JWT
                         v
                 API Gateway :8080
                         |
     +-----------+-------+------+--------------+
     v           v              v              v
  Catalog     Booking  <---  Support     Notification
   :8081       :8082   HTTP   :8085         :8083
     |           |              |              |
     +-----------+-------+------+--------------+
                         v
              PostgreSQL "hotelbooking"
```

IAM không đi qua Gateway: trình duyệt gọi thẳng cổng `8084`. Đường gọi trực tiếp giữa các service duy nhất là Support gọi Booking. Support và Notification không gọi nhau mà trao đổi qua bảng `notification_outbox`.

## 2. Ba ứng dụng

| App | Mã trong IAM | URL | Ai nhận được JWT | Chức năng |
|---|---|---|---|---|
| App 1 | `app1` | `http://localhost:3000` | Tài khoản role `USER`. Email mới được tự tạo tài khoản | Tìm phòng, đặt phòng, thanh toán mô phỏng, hủy đơn |
| App Admin | `app2` | `http://localhost:3001` | Chỉ email `IAM_ADMIN_EMAIL`, role `ADMIN` | Xem tồn kho, thêm loại phòng, sửa giá, xem đơn trong tenant |
| Support Center | `app3` | `http://localhost:3002` | Mọi tài khoản đã tồn tại | USER xin đổi ngày và xem thông báo. ADMIN duyệt hoặc từ chối phiếu |

Chi tiết chính sách đăng nhập ở [iam-sso.md](iam-sso.md).

## 3. Backend

| Service | Cổng | Trách nhiệm | Dữ liệu chính |
|---|---|---|---|
| `iam-service` | `8084` | Google login, SSO, JWT, JWKS, tạo tài khoản theo chính sách | `tenant`, `app_user` |
| `api-gateway` | `8080` | Định tuyến, CORS, kiểm tra JWT. Không xét role | Không có |
| `catalog-service` | `8081` | Khách sạn, loại phòng, giá, tìm phòng, xem tồn kho | `hotel`, `room_type`, `room_inventory` |
| `booking-service` | `8082` | Đặt, thanh toán mô phỏng, hủy, hết hạn, đổi ngày | `booking`, `booking_reschedule`, cột `room_inventory.sold` |
| `support-service` | `8085` | Phiếu xin đổi ngày | `date_change_request`, `notification_outbox` |
| `notification-service` | `8083` | Thông báo trong ứng dụng | `notification` |

Khi chạy bằng Docker Compose, cổng host của Notification là `8086` vì Adminer dùng `8083`.

## 4. Bản đồ tài liệu

| Tài liệu | Nội dung | Nguồn chuẩn cho |
|---|---|---|
| [architecture.md](architecture.md) | Thành phần, ranh giới dữ liệu, phân quyền, giới hạn | Kiến trúc |
| [iam-sso.md](iam-sso.md) | Google login, chính sách theo app, SSO, JWT, đăng xuất | Xác thực |
| [api-contract.md](api-contract.md) | Toàn bộ HTTP API đang có | API |
| [data-model.md](data-model.md) | Quy tắc tồn kho, vòng đời đơn, phiếu, outbox | Quy tắc dữ liệu |
| [erd.md](erd.md) | Sơ đồ quan hệ, bảng, ràng buộc, index | Schema |
| [operations-and-tests.md](operations-and-tests.md) | Cấu hình, chạy, migration, seed, test, đối soát | Vận hành |
| [iam-testing.md](iam-testing.md) | Cách kiểm thử IAM và SSO, kết quả đã có | Kiểm thử IAM |
| [iam-integration-tasks.md](iam-integration-tasks.md) | Việc đã xong và việc còn lại của IAM | Backlog IAM |
| [decisions.md](decisions.md) | Các quyết định kiến trúc và câu hỏi còn mở | Quyết định |
| [../stayhub-sequence-diagrams.md](../stayhub-sequence-diagrams.md) | Sequence diagram mọi luồng | Luồng nghiệp vụ |
| [../build/README.md](../build/README.md) | Build và rebuild Docker | Build |

Khi đổi API, schema hoặc luồng đăng nhập, cập nhật đúng tài liệu nguồn chuẩn trong cùng thay đổi code.

## 5. Quy tắc cốt lõi

- Role chỉ có `USER` và `ADMIN`.
- `tenant_id`, email và role luôn lấy từ JWT đã xác minh, không lấy từ request.
- Sai role trả `403`. Tài nguyên khác tenant, khác chủ hoặc không tồn tại đều trả `404`.
- Gateway và từng service đều tự kiểm tra JWT.
- Tồn kho được giữ trong cùng transaction với việc tạo đơn. Không bán vượt `total`.
- Hủy và hết hạn chỉ trả tồn đúng một lần.
- Migration đã áp dụng không được sửa.

## 6. Mức độ kiểm chứng

Kết quả ngày 05/11/2026 trên commit `1737b60`:

| Mức | Phạm vi | Kết quả |
|---|---|---|
| Chạy cả hệ thống bằng Docker | `backend/test/10-check-db.sh`, `20-api-workflow.sh`: migration, đối soát, tách tenant, phân quyền, tranh phòng, thanh toán, đổi ngày, thông báo | Đạt. JWT do `dev-token` ký, không phải IAM |
| Gọi thật vào IAM | Lỗi 400, chuyển hướng sang Google, hủy đăng nhập, đăng xuất, JWKS | Đạt |
| Test tự động đã chạy | Frontend, `iam-service`, `api-gateway` | 103/103, 7/7, 8/8 |
| Chưa chạy | Đăng nhập Google bằng tài khoản thật, nên chưa chạy thử chính sách theo app, tạo tài khoản, `#token=JWT`, SSO khi đã có phiên, JWT do IAM ký đi qua Gateway | Chỉ đọc từ code |
| Chưa chạy lại trong lần đối chiếu này | Test của Catalog, Booking, Support, Notification | Xem cách chạy ở [operations-and-tests.md](operations-and-tests.md) |

Hệ thống là bản demo, chưa đạt mức production. Các giới hạn đã biết nằm ở [architecture.md](architecture.md#12-giới-hạn-đã-biết).
