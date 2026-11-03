-- Yêu cầu bảng "tenant"/"app_user" (iam-service) và "room" (catalog-service) đã tồn tại
-- trên database chung "hotelbooking" — migrate theo thứ tự A (iam) -> B (catalog) -> C (booking).
CREATE TABLE booking (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id  BIGINT NOT NULL REFERENCES tenant (id),
    room_id    BIGINT NOT NULL,
    user_email VARCHAR(255) NOT NULL,
    status     VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_booking_status CHECK (status = 'CONFIRMED'),
    CONSTRAINT chk_booking_email_lowercase CHECK (user_email = lower(user_email)),
    -- FK ghép (không phải FK đơn cột): bắt buộc room_id phải thuộc ĐÚNG tenant_id của
    -- booking này, không chỉ "room_id có tồn tại ở đâu đó". Cần uq_room_tenant_id ở room.
    CONSTRAINT fk_booking_room_same_tenant
        FOREIGN KEY (tenant_id, room_id) REFERENCES room (tenant_id, id),
    -- Tương tự: user_email phải thuộc đúng tenant_id của booking, không chỉ "email có tồn tại".
    -- Cần uq_app_user_tenant_email ở app_user.
    CONSTRAINT fk_booking_user_same_tenant
        FOREIGN KEY (tenant_id, user_email) REFERENCES app_user (tenant_id, email)
);

CREATE INDEX idx_booking_tenant_id ON booking (tenant_id);
CREATE INDEX idx_booking_user_email ON booking (user_email);
CREATE INDEX idx_booking_room_id ON booking (room_id);
