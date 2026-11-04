-- Theo docs/erd-v2.md §9. Yêu cầu tenant/app_user (iam-service) và room_type (catalog-service) đã tồn tại
-- trên database chung "hotelbooking": migrate theo thứ tự IAM -> Catalog -> Booking.
-- Sửa trực tiếp V1 là ngoại lệ chuyển đổi của ERD §11, cần dựng lại DB local bằng docker compose down -v.

CREATE TABLE booking (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT         NOT NULL REFERENCES tenant (id),
    room_type_id       BIGINT         NOT NULL,
    user_email         VARCHAR(255)   NOT NULL,
    check_in           DATE           NOT NULL,
    check_out          DATE           NOT NULL,
    room_count         INT            NOT NULL,
    total_amount       NUMERIC(14, 2) NOT NULL,
    status             VARCHAR(20)    NOT NULL DEFAULT 'PENDING_PAYMENT',
    payment_expires_at TIMESTAMPTZ    NOT NULL,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT chk_booking_status
        CHECK (status IN ('PENDING_PAYMENT', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT chk_booking_email_lowercase CHECK (user_email = lower(user_email)),
    CONSTRAINT chk_booking_checkout_after_checkin CHECK (check_out > check_in),
    CONSTRAINT chk_booking_room_count CHECK (room_count > 0),
    CONSTRAINT chk_booking_total_amount CHECK (total_amount > 0),
    CONSTRAINT fk_booking_room_type_same_tenant
        FOREIGN KEY (tenant_id, room_type_id) REFERENCES room_type (tenant_id, id),
    CONSTRAINT fk_booking_user_same_tenant
        FOREIGN KEY (tenant_id, user_email) REFERENCES app_user (tenant_id, email)
);

CREATE INDEX idx_booking_tenant_user ON booking (tenant_id, user_email);
CREATE INDEX idx_booking_room_type ON booking (tenant_id, room_type_id);
CREATE INDEX idx_booking_pending_expiry ON booking (payment_expires_at)
    WHERE status = 'PENDING_PAYMENT';
