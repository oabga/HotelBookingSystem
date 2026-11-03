-- Yêu cầu bảng "tenant" đã tồn tại (migration V1 của iam-service phải chạy trước
-- trên database chung "hotelbooking" — xem docs/iam-google-sso-demo-plan-v2.md §4.1).
CREATE TABLE room (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenant (id),
    name      VARCHAR(100) NOT NULL,
    price     NUMERIC(10, 2) NOT NULL,
    available BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT chk_room_price_positive CHECK (price > 0),
    -- Composite unique để booking.(tenant_id, room_id) có thể FK ghép xuống đây,
    -- chặn booking trỏ vào room thuộc tenant khác (xem docs/erd-v2.md mục 3).
    CONSTRAINT uq_room_tenant_id UNIQUE (tenant_id, id)
);

CREATE INDEX idx_room_tenant_id ON room (tenant_id);
CREATE INDEX idx_room_tenant_available ON room (tenant_id, available);
