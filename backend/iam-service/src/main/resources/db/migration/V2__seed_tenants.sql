-- Chỉ seed tenant (không chứa PII) qua Flyway. KHÔNG seed app_user ở đây vì cần email Gmail
-- thật để test login — xem docker/seed/seed-app-users.example.sql, chạy local, không commit bản thật.
INSERT INTO tenant (name) VALUES ('Tenant 1 - Demo Hotel A');
INSERT INTO tenant (name) VALUES ('Tenant 2 - Demo Hotel B');
