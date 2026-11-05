# StayHub: sequence diagram toàn hệ thống

Vẽ theo source code của `main`, commit `1737b60`, đối chiếu ngày 05/11/2026. Các luồng đặt phòng, thanh toán, đổi ngày và thông báo đã chạy qua workflow Docker với JWT của `dev-token`. Đăng nhập bằng tài khoản Google thật chưa chạy, nên phần có Google là điều code quy định, chưa phải kết quả E2E. Chi tiết ở [documents/README.md](documents/README.md#6-mức-độ-kiểm-chứng).

Quy ước: nét liền là request, nét đứt là response. Số thứ tự đánh riêng trong từng sơ đồ. Nhãn "gọi thẳng" là lời gọi từ Support sang Booking không qua Gateway.

## Toàn hệ thống: một đơn đi từ lúc đăng nhập đến khi đổi ngày xong

Sơ đồ đi theo luồng thành công dài nhất, chạm đủ 10 thành phần. Cột USER và ADMIN là trình duyệt của từng người. Hai điểm dễ bỏ sót: IAM không đi qua Gateway, và Support với Notification không gọi nhau mà trao đổi qua bảng outbox.

```mermaid
sequenceDiagram
    autonumber
    participant U as USER<br/>:3000, :3002
    participant A as ADMIN<br/>:3001, :3002
    participant I as IAM<br/>:8084
    participant G as Google<br/>OIDC
    participant W as Gateway<br/>:8080
    participant C as Catalog<br/>:8081
    participant B as Booking<br/>:8082
    participant S as Support<br/>:8085
    participant N as Notification<br/>:8083
    participant D as PostgreSQL<br/>hotelbooking
    Note over U,D: A · Đăng nhập lần đầu
    U->>I: GET /auth/start?app=app1&mode=login
    I->>G: Chuyển hướng qua trình duyệt:<br/>Authorization Code + PKCE S256
    G-->>I: code + state, đổi lấy ID token
    I->>D: SELECT app_user theo email, App 1 tạo USER nếu chưa có, gắn hoặc đối chiếu google_sub
    I->>I: Ký JWT RS256, hạn 15 phút
    I-->>U: 302 /callback/#token=JWT
    Note over U,D: B · Tìm và đặt phòng
    U->>W: GET /api/hotels/{id}/availability
    W->>C: Chuyển tiếp kèm JWT
    C->>D: SELECT room_type + room_inventory
    C-->>U: 200 danh sách phòng trống
    U->>W: POST /api/bookings
    W->>B: Chuyển tiếp kèm JWT
    B->>D: Một transaction: tăng sold từng đêm,<br/>INSERT booking
    B-->>U: 201 PENDING_PAYMENT
    Note over U,D: C · Thanh toán mô phỏng trong 15 phút
    U->>W: POST /api/bookings/{id}/payment
    W->>B: Chuyển tiếp kèm JWT
    B->>D: UPDATE booking thành CONFIRMED
    B-->>U: 200 CONFIRMED
    B->>D: Job mỗi 60 giây: đơn quá hạn<br/>thành EXPIRED, trả sold
    Note over U,D: D · USER sang Support Center và gửi phiếu đổi ngày
    U->>I: GET /auth/start?app=app3&mode=login + cookie phiên
    I->>D: SELECT app_user, đối chiếu lại google_sub
    I-->>U: 302 /callback/#token=JWT mới, không qua Google
    U->>W: POST /api/support/date-change-requests
    W->>S: Chuyển tiếp kèm JWT
    S->>B: GET /bookings/{id}/reschedule-quote (gọi thẳng)
    B-->>S: available, tổng tiền mới
    S->>D: INSERT date_change_request PENDING
    S-->>U: 201 phiếu đang chờ, chưa giữ phòng
    Note over U,D: E · ADMIN duyệt phiếu
    A->>W: POST /api/support/<br/>date-change-requests/{id}/approve
    W->>S: Chuyển tiếp kèm JWT
    S->>D: SELECT phiếu FOR UPDATE
    S->>B: POST /bookings/{id}/reschedule<br/>kèm requestId (gọi thẳng)
    B->>D: Một transaction: chuyển sold, UPDATE booking,<br/>INSERT booking_reschedule
    B-->>S: 200
    S->>D: Cùng transaction khóa phiếu: UPDATE APPROVED,<br/>INSERT notification_outbox
    S-->>A: 200 APPROVED
    Note over U,D: F · USER nhận thông báo
    N->>D: Job mỗi 2 giây: đọc outbox,<br/>INSERT notification
    U->>W: GET /api/notifications, mỗi 15 giây
    W->>N: Chuyển tiếp kèm JWT
    N->>D: SELECT thông báo của người này
    N-->>U: 200 items + unreadCount
```

Bước A rút gọn chặng chuyển hướng qua trình duyệt. Response thực tế đi ngược qua Gateway, sơ đồ vẽ gộp về trình duyệt. Chi tiết và nhánh lỗi nằm ở các sơ đồ bên dưới.

## Đăng nhập Google và SSO

Mỗi app nhận JWT riêng, còn phiên đăng nhập nằm ở cookie của IAM. Khi đã có phiên, app được phép có thể lấy JWT mà không qua Google. Mọi lần từ chối đều trả về trang callback của app kèm mã lỗi. Tải lại trang sẽ mất JWT và phải bấm đăng nhập lại.

```mermaid
sequenceDiagram
    autonumber
    participant U as Người dùng<br/>USER hoặc ADMIN
    participant F as Trình duyệt<br/>App 1, App Admin, Support
    participant I as IAM<br/>:8084
    participant G as Google<br/>OIDC
    participant D as PostgreSQL<br/>hotelbooking
    U->>F: Bấm Tiếp tục với Google
    F->>I: GET /auth/start?app=app1 | app2 | app3 &mode=login
    alt app ngoài allowlist, hoặc mode=register ở app khác app1
        I-->>F: 400, không chuyển sang Google
    else hợp lệ: lưu sso_app và sso_register vào HttpSession
        alt chưa có phiên IAM
            I-->>F: 302 /oauth2/authorization/google
            F->>G: Authorization request: state, code_challenge, S256, prompt=select_account
            U->>G: Chọn tài khoản, đăng nhập Google
            G-->>F: 302 /login/oauth2/code/google?code&state
            F->>I: Gửi code + state, IAM kiểm tra state
            alt người dùng hủy, state hoặc code sai
                I-->>F: Hủy phiên, 302 {callback}/#error=login_failed
            else hợp lệ
                I->>G: Đổi code bằng code_verifier + client secret
                G-->>I: ID token
            end
        else đã có phiên IAM: dùng lại OidcUser, không gặp Google
        end
        I->>D: SELECT app_user theo email
        D-->>I: id, tenant_id, role, google_sub, hoặc không có dòng
        I->>I: Xét email_verified, chính sách của app (bảng dưới),<br/>rồi google_sub (chưa có thì gắn, khác thì từ chối)
        alt không đạt một trong các điều kiện trên
            I->>I: Rollback, xóa SecurityContext, hủy phiên
            I-->>F: 302 {callback}/#error=not_provisioned
        else đạt
            I->>D: Nếu cần: INSERT hoặc UPDATE app_user, COMMIT
            I->>I: Ký JWT RS256: iss, sub, email, tenant_id, role, iat, exp (15 phút)
            I-->>F: 302 {callback}/#token=JWT
            F->>F: Đọc token, xóa fragment khỏi URL, giữ trong bộ nhớ trang
            F-->>U: Hiện giao diện theo role trong JWT
        end
    end
```

Mở một app không được phép cũng hủy luôn phiên IAM, nên các app khác phải qua Google lại khi cần JWT mới.

### IAM xét gì ở từng app

Cả ba app đều yêu cầu `email_verified` và `google_sub` khớp. Tham số `mode` không đổi kết quả: App 1 tạo tài khoản mới kể cả với `mode=login`, còn `mode=register` ở app khác nhận 400.

| App | Khi email chưa có tài khoản | Điều kiện nhận JWT | Role trong JWT |
|---|---|---|---|
| App 1 (`app1`, `:3000`) | Tạo tài khoản USER trong tenant mặc định (Tenant 1) | Email khác `IAM_ADMIN_EMAIL` và role đang là USER | USER |
| App Admin (`app2`, `:3001`) | Tạo tài khoản ADMIN trong tenant mặc định, chỉ với `IAM_ADMIN_EMAIL` | Email đúng bằng `IAM_ADMIN_EMAIL`. Tài khoản đang là USER được nâng thành ADMIN và giữ nguyên tenant | ADMIN |
| Support (`app3`, `:3002`) | Từ chối | Tài khoản đã tồn tại | USER hoặc ADMIN đang lưu |

### Đăng xuất

Chỉ App 1 có nút Đăng xuất. Lệnh này hủy phiên IAM, không hủy phiên Google và không thu hồi JWT đã cấp. Tab App Admin hoặc Support đang mở vẫn dùng được JWT của nó đến khi hết hạn.

```mermaid
sequenceDiagram
    autonumber
    participant U as Người dùng<br/>USER
    participant F as Trình duyệt<br/>App 1
    participant I as IAM<br/>:8084
    U->>F: Bấm Đăng xuất
    F->>I: GET /auth/logout?app=app1
    alt app ngoài allowlist
        I-->>F: 400
    else hợp lệ: hủy phiên IAM
        I-->>F: 302 {callback}/#logged_out
        F->>F: Trang tải lại, JWT cũ mất khỏi bộ nhớ
    end
```

## Kiểm tra JWT trên mỗi request

Gateway chỉ xác nhận token hợp lệ, còn role và tenant do service tự xét. Service gọi thẳng vào cổng riêng vẫn từ chối token sai.

```mermaid
sequenceDiagram
    autonumber
    participant F as Trình duyệt<br/>App 1, App Admin, Support
    participant W as Gateway<br/>:8080
    participant T as Service nghiệp vụ<br/>Catalog, Booking, Support, Notification
    participant I as IAM<br/>:8084
    participant D as PostgreSQL<br/>hotelbooking
    F->>W: Request + Authorization: Bearer JWT
    opt Gateway chưa có public key trong cache
        W->>I: GET /.well-known/jwks.json
        I-->>W: Public key RSA
    end
    W->>W: Kiểm tra chữ ký RS256, iss, exp và claim bắt buộc.<br/>Không xét role.
    alt JWT sai hoặc hết hạn
        W-->>F: 401, frontend xóa JWT, báo đăng nhập lại
    else JWT hợp lệ
        W->>T: Bỏ tiền tố /api, chuyển tiếp nguyên header Authorization
        opt service chưa có public key trong cache
            T->>I: GET /.well-known/jwks.json
            I-->>T: Public key RSA
        end
        T->>T: Tự kiểm tra JWT lần nữa, rồi xét role theo endpoint
        alt sai role
            T-->>F: 403
        else đúng role
            T->>D: Truy vấn luôn kèm tenant_id từ JWT, USER kèm thêm chủ sở hữu
            alt có dòng phù hợp
                T-->>F: 2xx kết quả nghiệp vụ
            else không có dòng: khác tenant, khác chủ hoặc không tồn tại
                T-->>F: 404 giống nhau, không lộ lý do
            end
        end
    end
```

IAM chỉ được gọi để lấy public key khi gặp `kid` chưa có trong cache, không tham gia từng request nghiệp vụ.

## Đặt phòng

Kết quả tìm phòng không giữ chỗ. Khi đặt, Booking kiểm tra lại bằng chính câu `UPDATE` có điều kiện, nên hai request tranh phòng cuối chỉ có một bên thắng.

```mermaid
sequenceDiagram
    autonumber
    participant F as Trình duyệt<br/>App 1, App Admin, Support
    participant B as Booking<br/>:8082
    participant D as PostgreSQL<br/>hotelbooking
    F->>B: POST /api/bookings qua Gateway<br/>roomTypeId, checkIn, checkOut, roomCount
    B->>B: checkIn ở quá khứ thì trả 400
    B->>D: BEGIN, SELECT base_price theo id + tenant
    D-->>B: base_price, không có dòng thì 404
    loop mỗi đêm, ngày tăng dần để tránh deadlock
        B->>D: UPDATE room_inventory SET sold = sold + n<br/>WHERE ... AND sold + n #lt;= total
        D-->>B: Số dòng đổi được
    end
    alt có đêm đổi được khác 1 dòng
        B->>D: ROLLBACK, các đêm trước đó cũng hoàn lại
        B-->>F: 409 hết phòng
    else đủ mọi đêm
        B->>D: INSERT booking PENDING_PAYMENT<br/>payment_expires_at = bây giờ + 15 phút, COMMIT
        B-->>F: 201 kèm totalAmount
    end
```

Booking trực tiếp ghi room_inventory.sold và không gọi Catalog. Hai service chia sẻ dữ liệu qua database chung.

## Thanh toán, hủy đơn và hết hạn

Cả ba luồng đều đổi trạng thái bằng `UPDATE ... WHERE status = 'PENDING_PAYMENT'`. Khi hủy đơn trùng lúc job hết hạn chạy, chỉ một bên đổi được dòng đó nên phòng chỉ được trả một lần.

```mermaid
sequenceDiagram
    autonumber
    participant F as Trình duyệt<br/>App 1, App Admin, Support
    participant B as Booking<br/>:8082
    participant J as BookingExpiryJob<br/>trong Booking :8082
    participant D as PostgreSQL<br/>hotelbooking
    Note over F,D: Thanh toán mô phỏng: phòng đã giữ từ lúc đặt nên không đổi sold
    F->>B: POST /api/bookings/{id}/payment qua Gateway
    B->>D: UPDATE booking SET status = CONFIRMED<br/>WHERE chủ đơn AND PENDING_PAYMENT AND chưa quá hạn
    alt đổi được 1 dòng
        B-->>F: 200 CONFIRMED
    else 0 dòng: đã trả, đã hủy hoặc quá hạn. Đơn của người khác là 404
        B-->>F: 409
    end
    Note over F,D: Hủy đơn: đổi trạng thái trước, chỉ trả phòng khi đổi được đúng 1 dòng
    F->>B: POST /api/bookings/{id}/cancel qua Gateway
    B->>D: UPDATE booking SET status = CANCELLED<br/>WHERE chủ đơn AND PENDING_PAYMENT
    alt đổi được 1 dòng
        B->>D: Mỗi đêm: UPDATE sold = sold - n, COMMIT
        B-->>F: 200 CANCELLED
    else 0 dòng và đơn đã CANCELLED
        B-->>F: 200, không trả phòng lần hai
    else 0 dòng và trạng thái khác
        B-->>F: 409
    end
    Note over F,D: Hết hạn: BookingExpiryJob chạy mỗi 60 giây, cho mọi tenant
    J->>D: SELECT id đơn PENDING_PAYMENT đã quá payment_expires_at
    loop mỗi đơn, một transaction riêng
        J->>D: UPDATE booking SET status = EXPIRED<br/>WHERE PENDING_PAYMENT AND đã quá hạn
        J->>D: Đổi được 1 dòng thì trả sold từng đêm, COMMIT
    end
```

Số thứ tự chạy liên tục qua ba luồng để tiện tham chiếu, ba luồng này không nối tiếp nhau.

## ADMIN quản lý catalog

```mermaid
sequenceDiagram
    autonumber
    participant F as Trình duyệt<br/>App 1, App Admin, Support
    participant W as Gateway<br/>:8080
    participant C as Catalog<br/>:8081
    participant D as PostgreSQL<br/>hotelbooking
    F->>W: GET /api/admin/hotels/{id}/inventory?checkIn&checkOut
    W->>C: Chuyển tiếp, Catalog yêu cầu ROLE_ADMIN
    C->>D: SELECT tồn kho theo tenant, hotel, khoảng ngày
    C-->>F: 200 total, sold, available từng đêm
    F->>W: POST /api/hotels/{id}/room-types
    W->>C: Chuyển tiếp
    C->>D: Một transaction: INSERT room_type, INSERT room_inventory<br/>từ hôm nay đến 2027-01-31 (ngày cuối viết cứng trong SQL)
    C-->>F: 201
    F->>W: PUT /api/room-types/{id}/price
    W->>C: Chuyển tiếp
    C->>D: UPDATE base_price theo id + tenant
    C-->>F: 200, đơn đã tạo giữ nguyên số tiền
```

Loại phòng mới chỉ có tồn kho đến 2027-01-31, nên đặt phòng sau ngày đó sẽ nhận 409.

## Đổi ngày: USER gửi phiếu

Đây là đường gọi service-to-service duy nhất của hệ thống. Support dùng lại JWT của người đang thao tác để Booking tự kiểm tra quyền.

```mermaid
sequenceDiagram
    autonumber
    participant F as Trình duyệt<br/>App 1, App Admin, Support
    participant S as Support<br/>:8085
    participant B as Booking<br/>:8082
    participant D as PostgreSQL<br/>hotelbooking
    F->>B: GET /api/bookings/reschedulable qua Gateway
    B-->>F: Đơn CONFIRMED của mình, chưa tới ngày nhận phòng
    F->>B: GET /api/bookings/{id}/reschedule-quote qua Gateway
    B-->>F: available, newTotalAmount
    F->>S: POST /api/support/date-change-requests qua Gateway
    S->>D: SELECT app_user theo tenant, email, role. Không khớp thì 403
    S->>B: GET /bookings/{id}/reschedule-quote, gọi thẳng bằng JWT của USER
    alt Booking báo lỗi hoặc available = false
        B-->>S: 400, 403, 404, 409 hoặc không phản hồi
        S-->>F: Trả cùng mã. Booking không phản hồi thì 503
    else available = true
        B-->>S: Thông tin đơn và tổng tiền mới
        S->>D: INSERT date_change_request PENDING, không giữ phòng
        alt vướng uq_date_change_request_pending
            S-->>F: 409, đơn này đã có phiếu đang chờ
        else thành công
            S-->>F: 201
        end
    end
```

Phòng chưa được giữ trong lúc phiếu chờ duyệt, nên lúc duyệt vẫn có thể hết phòng.

## Đổi ngày: ADMIN duyệt hoặc từ chối

Booking commit trước, phiếu ghi nhận sau. Nếu Support lỗi giữa hai bước, phiếu vẫn PENDING. ADMIN duyệt lại là an toàn nhờ `requestId`, còn từ chối sẽ bị chặn.

```mermaid
sequenceDiagram
    autonumber
    participant F as Trình duyệt<br/>App 1, App Admin, Support
    participant S as Support<br/>:8085
    participant B as Booking<br/>:8082
    participant D as PostgreSQL<br/>hotelbooking
    F->>S: POST /api/support/date-change-requests/{id}/approve qua Gateway
    S->>D: BEGIN, SELECT phiếu FOR UPDATE theo id + tenant
    alt không thấy phiếu, hoặc phiếu không còn PENDING
        S-->>F: 404 hoặc 409
    else PENDING: giữ khóa dòng phiếu trong lúc gọi Booking
        S->>B: POST /bookings/{id}/reschedule, gọi thẳng bằng JWT của ADMIN<br/>requestId = id phiếu, timeout 3 giây kết nối + 10 giây đọc
        B->>D: BEGIN, SELECT booking FOR UPDATE, tìm requestId đã áp dụng
        alt requestId đã áp dụng cùng ngày
            B-->>S: 200, không đổi gì thêm
        else chưa áp dụng và đơn vẫn đổi được
            B->>D: Theo ngày tăng dần: tăng sold đêm mới, giảm sold đêm bỏ.<br/>UPDATE booking, INSERT booking_reschedule, COMMIT
            B-->>S: 200
        else hết phòng, hoặc đơn không còn đổi được
            B-->>S: 409, Booking đã rollback
        end
        alt Booking trả 200
            S->>D: UPDATE phiếu APPROVED, INSERT notification_outbox, COMMIT
            S-->>F: 200 APPROVED
        else 409 hoặc Booking không phản hồi
            S->>D: ROLLBACK, phiếu vẫn PENDING, không có thông báo
            S-->>F: 409 hoặc 503
        end
    end
    Note over F,D: Từ chối: không gọi Booking
    F->>S: POST /api/support/date-change-requests/{id}/reject qua Gateway
    S->>D: SELECT phiếu FOR UPDATE, kiểm tra booking_reschedule theo requestId
    alt Booking đã đổi ngày cho phiếu này
        S-->>F: 409, chỉ có thể duyệt lại
    else chưa đổi
        S->>D: UPDATE phiếu REJECTED + lý do, INSERT notification_outbox, COMMIT
        S-->>F: 200 REJECTED
    end
```

Transaction của Support mở suốt lời gọi HTTP sang Booking, nên Booking chậm sẽ giữ khóa dòng phiếu và một kết nối DB tối đa khoảng 13 giây.

## Thông báo qua outbox

Phiếu đã duyệt thì sự kiện đã nằm trong database, nên Notification tắt cũng không mất thông báo. Khi chạy lại, job xử lý tiếp phần còn tồn.

```mermaid
sequenceDiagram
    autonumber
    participant F as Trình duyệt<br/>App 1, App Admin, Support
    participant S as Support<br/>:8085
    participant N as Notification<br/>:8083
    participant D as PostgreSQL<br/>hotelbooking
    S->>D: Cùng transaction với kết quả duyệt: INSERT notification_outbox
    loop NotificationDelivery chạy mỗi 2 giây, một transaction
        N->>D: SELECT tối đa 100 dòng chưa xử lý<br/>FOR UPDATE SKIP LOCKED
        N->>D: INSERT notification ON CONFLICT (source_event_id) DO NOTHING
        N->>D: UPDATE notification_outbox SET processed_at = now()
    end
    loop Support Center gọi lại mỗi 15 giây
        F->>N: GET /api/notifications?page=0&size=... qua Gateway
        N->>D: SELECT theo tenant + người nhận, đếm chưa đọc
        N-->>F: 200 items + unreadCount
    end
    F->>N: POST /api/notifications/{id}/read qua Gateway
    N->>D: Ghi read_at. Gọi lại không đổi thời điểm đọc đầu tiên
    N-->>F: 200. Thông báo của người khác là 404
```

Thời gian từ lúc duyệt đến lúc USER thấy thông báo tối đa khoảng 17 giây: 2 giây của job cộng 15 giây của frontend. Con số này tính từ cấu hình, chưa đo.

## Giới hạn và việc còn lại

Danh sách đầy đủ ở [documents/architecture.md](documents/architecture.md#12-giới-hạn-đã-biết) và [documents/iam-integration-tasks.md](documents/iam-integration-tasks.md). Ba điểm ảnh hưởng trực tiếp tới các sơ đồ trên:

- **Khởi động lại IAM:** IAM sinh RSA key mới nhưng giữ nguyên `kid`. Đo bằng bản sao `dev-token` cố định `kid`: JWT mới bị từ chối ở mọi nơi, Catalog, Booking, Support hồi phục sau khoảng 5 phút, Gateway chỉ hết `401` khi được restart.
- **JWT trong Docker Compose:** Gateway và các service mặc định tin `dev-token`. Đăng nhập Google thật cần đặt `SECURITY_JWT_ISSUER` và `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` trong `.env.local`.
- **Cổng Notification:** chạy local dùng `8083`, Compose dùng cổng host `8086`. Đi qua Gateway thì không bị ảnh hưởng.
