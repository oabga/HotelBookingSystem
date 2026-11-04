-- Theo docs/new/data-model.md. Yêu cầu app_user (iam-service) và booking (booking-service) đã tồn tại
-- trên database chung "hotelbooking": migrate theo thứ tự IAM -> Catalog -> Booking -> Support.

-- Bổ sung để FK ghép theo tenant trỏ được xuống hai bảng này. Chỉ thêm ràng buộc, không đổi dữ liệu.
ALTER TABLE app_user ADD CONSTRAINT uq_app_user_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE booking ADD CONSTRAINT uq_booking_tenant_id UNIQUE (tenant_id, id);

CREATE TABLE support_ticket (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT        NOT NULL REFERENCES tenant (id),
    requester_user_id BIGINT        NOT NULL,
    booking_id        BIGINT,
    subject           VARCHAR(200)  NOT NULL,
    description       VARCHAR(4000) NOT NULL,
    status            VARCHAR(20)   NOT NULL DEFAULT 'OPEN',
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    closed_at         TIMESTAMPTZ,
    CONSTRAINT uq_support_ticket_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT chk_support_ticket_status
        CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED')),
    CONSTRAINT chk_support_ticket_closed_at CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)),
    CONSTRAINT fk_support_ticket_requester_same_tenant
        FOREIGN KEY (tenant_id, requester_user_id) REFERENCES app_user (tenant_id, id),
    CONSTRAINT fk_support_ticket_booking_same_tenant
        FOREIGN KEY (tenant_id, booking_id) REFERENCES booking (tenant_id, id)
);

CREATE INDEX idx_support_ticket_requester ON support_ticket (tenant_id, requester_user_id, id);
CREATE INDEX idx_support_ticket_booking ON support_ticket (tenant_id, booking_id);

CREATE TABLE ticket_message (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id      BIGINT        NOT NULL,
    ticket_id      BIGINT        NOT NULL,
    author_user_id BIGINT        NOT NULL,
    -- Lưu role lúc viết để lịch sử không đổi khi role của user đổi về sau.
    author_role    VARCHAR(20)   NOT NULL,
    body           VARCHAR(4000) NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_ticket_message_author_role CHECK (author_role IN ('USER', 'ADMIN')),
    CONSTRAINT fk_ticket_message_ticket_same_tenant
        FOREIGN KEY (tenant_id, ticket_id) REFERENCES support_ticket (tenant_id, id),
    CONSTRAINT fk_ticket_message_author_same_tenant
        FOREIGN KEY (tenant_id, author_user_id) REFERENCES app_user (tenant_id, id)
);

CREATE INDEX idx_ticket_message_ticket ON ticket_message (tenant_id, ticket_id, id);

-- Tin nhắn là bản ghi kiểm toán: không cho sửa hoặc xoá, kể cả khi ghi trực tiếp vào database.
CREATE FUNCTION reject_ticket_message_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ticket_message is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ticket_message_immutable
    BEFORE UPDATE OR DELETE ON ticket_message
    FOR EACH ROW EXECUTE FUNCTION reject_ticket_message_change();
