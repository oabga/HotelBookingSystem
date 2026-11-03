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
    -- Composite unique để booking.(tenant_id, user_email) có thể FK ghép xuống đây,
    -- chặn booking trỏ vào user thuộc tenant khác (xem docs/erd-v2.md mục 3).
    CONSTRAINT uq_app_user_tenant_email UNIQUE (tenant_id, email),
    CONSTRAINT uq_app_user_google_sub UNIQUE (google_sub),
    CONSTRAINT chk_app_user_role CHECK (role IN ('USER', 'ADMIN')),
    -- Email phải chuẩn hoá chữ thường trước khi lưu — đúng yêu cầu "tra app_user theo
    -- email đã chuẩn hóa" ở docs/iam-google-sso-demo-plan-v2.md §3.1.
    CONSTRAINT chk_app_user_email_lowercase CHECK (email = lower(email))
);

CREATE INDEX idx_app_user_tenant_id ON app_user (tenant_id);
