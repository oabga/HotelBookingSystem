CREATE TABLE tenant (
    id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(100) NOT NULL
);

CREATE TABLE app_user (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email      VARCHAR(255) NOT NULL,
    tenant_id  BIGINT NOT NULL REFERENCES tenant (id),
    role       VARCHAR(20) NOT NULL,
    google_sub VARCHAR(255),
    CONSTRAINT uq_app_user_email UNIQUE (email),
    CONSTRAINT uq_app_user_google_sub UNIQUE (google_sub),
    CONSTRAINT chk_app_user_role CHECK (role IN ('USER', 'ADMIN'))
);

CREATE INDEX idx_app_user_tenant_id ON app_user (tenant_id);
