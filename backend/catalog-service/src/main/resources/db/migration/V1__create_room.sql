-- Tên file được giữ nguyên vì BookingFlowIntegrationTest tham chiếu trực tiếp đường dẫn này.
-- Catalog schema v3: IAM phải chạy trước để tạo tenant.

CREATE TABLE hotel (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id BIGINT       NOT NULL REFERENCES tenant (id),
    name      VARCHAR(100) NOT NULL,
    address   VARCHAR(255) NOT NULL,
    CONSTRAINT uq_hotel_tenant_id UNIQUE (tenant_id, id)
);

CREATE TABLE room_type (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT         NOT NULL,
    hotel_id    BIGINT         NOT NULL,
    name        VARCHAR(100)   NOT NULL,
    capacity    INT            NOT NULL,
    base_price  NUMERIC(10, 2) NOT NULL,
    total_rooms INT            NOT NULL,
    CONSTRAINT uq_room_type_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_room_type_hotel_same_tenant
        FOREIGN KEY (tenant_id, hotel_id) REFERENCES hotel (tenant_id, id),
    CONSTRAINT chk_room_type_capacity CHECK (capacity > 0),
    CONSTRAINT chk_room_type_price CHECK (base_price > 0),
    CONSTRAINT chk_room_type_total_rooms CHECK (total_rooms >= 0)
);

CREATE TABLE room_inventory (
    tenant_id    BIGINT NOT NULL,
    room_type_id BIGINT NOT NULL,
    stay_date    DATE   NOT NULL,
    total        INT    NOT NULL,
    sold         INT    NOT NULL DEFAULT 0,
    PRIMARY KEY (tenant_id, room_type_id, stay_date),
    CONSTRAINT fk_inventory_room_type_same_tenant
        FOREIGN KEY (tenant_id, room_type_id) REFERENCES room_type (tenant_id, id),
    CONSTRAINT chk_inventory_not_oversold CHECK (sold >= 0 AND sold <= total)
);

CREATE INDEX idx_hotel_tenant_id ON hotel (tenant_id);
CREATE INDEX idx_room_type_tenant_hotel ON room_type (tenant_id, hotel_id, id);
CREATE INDEX idx_inventory_tenant_date ON room_inventory (tenant_id, stay_date);
