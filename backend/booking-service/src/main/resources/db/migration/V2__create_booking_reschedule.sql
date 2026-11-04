-- Ghi lại mỗi lần đổi ngày đã áp dụng. UNIQUE theo yêu cầu để gọi lại với cùng request_id không đổi ngày hai lần.
CREATE TABLE booking_reschedule (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT         NOT NULL REFERENCES tenant (id),
    request_id         BIGINT         NOT NULL,
    booking_id         BIGINT         NOT NULL REFERENCES booking (id),
    previous_check_in  DATE           NOT NULL,
    previous_check_out DATE           NOT NULL,
    new_check_in       DATE           NOT NULL,
    new_check_out      DATE           NOT NULL,
    previous_total     NUMERIC(14, 2) NOT NULL,
    new_total          NUMERIC(14, 2) NOT NULL,
    created_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_booking_reschedule_request UNIQUE (tenant_id, request_id)
);

CREATE INDEX idx_booking_reschedule_booking ON booking_reschedule (booking_id);
