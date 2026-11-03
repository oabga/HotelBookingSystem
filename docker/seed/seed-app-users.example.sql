-- Mẫu seed app_user cho test Google SSO local. KHÔNG commit bản chứa email thật.
-- Copy file này thành seed-app-users.local.sql (đã có trong .gitignore), điền email Gmail
-- thật dùng để test, rồi chạy:
--   psql "postgresql://hotelbooking:hotelbooking@localhost:5432/hotelbooking" -f docker/seed/seed-app-users.local.sql
--
-- tenant_id = 1 -> Tenant 1 - Demo Hotel A, tenant_id = 2 -> Tenant 2 - Demo Hotel B (theo V2__seed_tenants.sql)
-- Email PHẢI viết thường toàn bộ (DB có CHECK email = lower(email)) — Gmail không phân biệt hoa/thường
-- nhưng app_user.email thì có, nên luôn insert/so khớp bằng chữ thường.

INSERT INTO app_user (email, tenant_id, role) VALUES
    ('admin-tenant1@example.com', 1, 'ADMIN'),
    ('user-tenant1@example.com', 1, 'USER'),
    ('user2-tenant1@example.com', 1, 'USER'), -- user thứ 2 cùng tenant 1, dùng để test tranh phòng bằng 2 người khác nhau
    ('admin-tenant2@example.com', 2, 'ADMIN'),
    ('user-tenant2@example.com', 2, 'USER');
