-- Yêu cầu bảng "tenant"/"app_user" (iam-service) và "room" (catalog-service) đã tồn tại
-- trên database chung "hotelbooking" — migrate theo thứ tự A (iam) -> B (catalog) -> C (booking).

-- Cần cho EXCLUDE constraint phía dưới: cho phép index GiST trên cột kiểu "=" (room_id)
-- kết hợp với range type (tstzrange). Không có extension này, EXCLUDE sẽ không tạo được.
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE booking (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id  BIGINT NOT NULL REFERENCES tenant (id),
    room_id    BIGINT NOT NULL,
    user_email VARCHAR(255) NOT NULL,
    -- Lưu đủ ngày GIỜ (không chỉ ngày): check-in/check-out có giờ cụ thể (vd 12:00/14:00),
    -- nên 2 booking "liền ngày" (A trả phòng ngày X, B nhận phòng cũng ngày X) vẫn có thể
    -- THỰC SỰ đè giờ nhau nếu giờ nhận phòng sớm hơn giờ trả phòng — phải tính bằng giờ
    -- thật mới phát hiện đúng, không thể chỉ so sánh DATE (xem docs/erd-v2.md mục 2).
    check_in   TIMESTAMPTZ NOT NULL,
    check_out  TIMESTAMPTZ NOT NULL,
    status     VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_booking_status CHECK (status = 'CONFIRMED'),
    CONSTRAINT chk_booking_email_lowercase CHECK (user_email = lower(user_email)),
    CONSTRAINT chk_booking_checkout_after_checkin CHECK (check_out > check_in),
    -- FK ghép (không phải FK đơn cột): bắt buộc room_id phải thuộc ĐÚNG tenant_id của
    -- booking này, không chỉ "room_id có tồn tại ở đâu đó". Cần uq_room_tenant_id ở room.
    CONSTRAINT fk_booking_room_same_tenant
        FOREIGN KEY (tenant_id, room_id) REFERENCES room (tenant_id, id),
    -- Tương tự: user_email phải thuộc đúng tenant_id của booking, không chỉ "email có tồn tại".
    -- Cần uq_app_user_tenant_email ở app_user.
    CONSTRAINT fk_booking_user_same_tenant
        FOREIGN KEY (tenant_id, user_email) REFERENCES app_user (tenant_id, email),
    -- Thay cho "UPDATE room SET available=false WHERE available=true" của bản cũ: DB tự
    -- chặn INSERT nếu cùng room_id mà khoảng [check_in, check_out) đè giờ với 1 booking
    -- CONFIRMED khác đã có. Đây là cơ chế atomic chống tranh phòng/tranh giờ thật sự,
    -- không cần tự code transaction/lock tay.
    CONSTRAINT excl_booking_room_overlap
        EXCLUDE USING gist (room_id WITH =, tstzrange(check_in, check_out) WITH &&)
        WHERE (status = 'CONFIRMED')
);

CREATE INDEX idx_booking_tenant_id ON booking (tenant_id);
CREATE INDEX idx_booking_user_email ON booking (user_email);
CREATE INDEX idx_booking_room_id ON booking (room_id);
