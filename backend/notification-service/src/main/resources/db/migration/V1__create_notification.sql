-- Yêu cầu app_user (iam-service) và notification_outbox (support-service) đã tồn tại trên database chung:
-- migrate theo thứ tự IAM -> Catalog -> Booking -> Support -> Notification.

CREATE TABLE notification (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT        NOT NULL,
    recipient_user_id BIGINT        NOT NULL,
    type              VARCHAR(40)   NOT NULL,
    title             VARCHAR(200)  NOT NULL,
    body              VARCHAR(2000) NOT NULL,
    reference_id      BIGINT        NOT NULL,
    -- Mỗi sự kiện chỉ tạo một thông báo, kể cả khi job xử lý lại.
    source_event_id   BIGINT        NOT NULL,
    read_at           TIMESTAMPTZ,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_notification_source_event UNIQUE (source_event_id),
    CONSTRAINT fk_notification_recipient_same_tenant
        FOREIGN KEY (tenant_id, recipient_user_id) REFERENCES app_user (tenant_id, id)
);

CREATE INDEX idx_notification_recipient ON notification (tenant_id, recipient_user_id, id);
CREATE INDEX idx_notification_unread ON notification (tenant_id, recipient_user_id) WHERE read_at IS NULL;
