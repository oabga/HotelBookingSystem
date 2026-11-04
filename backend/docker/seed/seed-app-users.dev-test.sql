-- Tài khoản cho profile dev-test của docker-compose.yml. Email là giả và khớp với token do
-- test-api-catalog/server.mjs ký: <role viết thường>-tenant<id>@catalog.local.test.
-- Chạy lại nhiều lần không sao. Không dùng file này cho Google SSO, xem seed-app-users.example.sql.

INSERT INTO app_user (email, tenant_id, role) VALUES
    ('user-tenant1@catalog.local.test', 1, 'USER'),
    ('admin-tenant1@catalog.local.test', 1, 'ADMIN'),
    ('user-tenant2@catalog.local.test', 2, 'USER'),
    ('admin-tenant2@catalog.local.test', 2, 'ADMIN')
ON CONFLICT (email) DO NOTHING;
