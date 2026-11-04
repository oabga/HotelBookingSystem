-- Support Center hiện chỉ xử lý yêu cầu đổi ngày, nên thay ticket chung bằng phiếu đổi ngày.
-- Dùng migration mới thay vì sửa V1 vì V1 có thể đã chạy trên database local.

DROP TABLE ticket_message;
DROP TABLE support_ticket;
DROP FUNCTION reject_ticket_message_change();

CREATE TABLE date_change_request (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id           BIGINT         NOT NULL REFERENCES tenant (id),
    booking_id          BIGINT         NOT NULL,
    requester_user_id   BIGINT         NOT NULL,
    room_type_name      VARCHAR(100)   NOT NULL,
    room_count          INT            NOT NULL,
    current_check_in    DATE           NOT NULL,
    current_check_out   DATE           NOT NULL,
    requested_check_in  DATE           NOT NULL,
    requested_check_out DATE           NOT NULL,
    current_total       NUMERIC(14, 2) NOT NULL,
    new_total           NUMERIC(14, 2) NOT NULL,
    description         VARCHAR(4000)  NOT NULL,
    status              VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    decided_by_user_id  BIGINT,
    decided_at          TIMESTAMPTZ,
    decision_note       VARCHAR(1000),
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT chk_date_change_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT chk_date_change_requested_order CHECK (requested_check_out > requested_check_in),
    CONSTRAINT chk_date_change_decided
        CHECK ((status = 'PENDING') = (decided_at IS NULL) AND (status = 'PENDING') = (decided_by_user_id IS NULL)),
    CONSTRAINT fk_date_change_booking_same_tenant
        FOREIGN KEY (tenant_id, booking_id) REFERENCES booking (tenant_id, id),
    CONSTRAINT fk_date_change_requester_same_tenant
        FOREIGN KEY (tenant_id, requester_user_id) REFERENCES app_user (tenant_id, id),
    CONSTRAINT fk_date_change_decider_same_tenant
        FOREIGN KEY (tenant_id, decided_by_user_id) REFERENCES app_user (tenant_id, id)
);

-- Mỗi đơn chỉ có một phiếu đang chờ, nếu không hai phiếu có thể đổi cùng một đơn với ngày gốc đã cũ.
CREATE UNIQUE INDEX uq_date_change_request_pending ON date_change_request (booking_id) WHERE status = 'PENDING';
CREATE INDEX idx_date_change_tenant ON date_change_request (tenant_id, id);
CREATE INDEX idx_date_change_requester ON date_change_request (tenant_id, requester_user_id, id);

-- Sự kiện chờ notification-service đọc. Ghi cùng transaction với kết quả duyệt nên không thể có
-- phiếu đã duyệt mà mất thông báo, kể cả khi notification-service đang tắt.
CREATE TABLE notification_outbox (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT        NOT NULL,
    recipient_user_id BIGINT        NOT NULL,
    type              VARCHAR(40)   NOT NULL,
    title             VARCHAR(200)  NOT NULL,
    body              VARCHAR(2000) NOT NULL,
    reference_id      BIGINT        NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    processed_at      TIMESTAMPTZ,
    CONSTRAINT fk_notification_outbox_recipient_same_tenant
        FOREIGN KEY (tenant_id, recipient_user_id) REFERENCES app_user (tenant_id, id)
);

CREATE INDEX idx_notification_outbox_unprocessed ON notification_outbox (id) WHERE processed_at IS NULL;
