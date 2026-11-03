-- Yêu cầu bảng "tenant"/"app_user" (iam-service) và "room" (catalog-service) đã tồn tại
-- trên database chung "hotelbooking" — migrate theo thứ tự A (iam) -> B (catalog) -> C (booking).
CREATE TABLE booking (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id  BIGINT NOT NULL REFERENCES tenant (id),
    room_id    BIGINT NOT NULL REFERENCES room (id),
    user_email VARCHAR(255) NOT NULL REFERENCES app_user (email),
    status     VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_booking_status CHECK (status = 'CONFIRMED')
);

CREATE INDEX idx_booking_tenant_id ON booking (tenant_id);
CREATE INDEX idx_booking_user_email ON booking (user_email);
CREATE INDEX idx_booking_room_id ON booking (room_id);
