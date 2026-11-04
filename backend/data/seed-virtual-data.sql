-- Synthetic demo users only. These are reserved example.test addresses, not real people.
-- Hotel, room_type and room_inventory are created and seeded by Catalog Flyway V1/V2.
-- This file may be applied more than once without duplicating the accounts.

BEGIN;

INSERT INTO app_user (email, tenant_id, role) VALUES
    ('admin-tenant1@example.test', 1, 'ADMIN'),
    ('user-tenant1@example.test', 1, 'USER'),
    ('user2-tenant1@example.test', 1, 'USER'),
    ('admin-tenant2@example.test', 2, 'ADMIN'),
    ('user-tenant2@example.test', 2, 'USER')
ON CONFLICT (email) DO UPDATE
SET tenant_id = EXCLUDED.tenant_id,
    role = EXCLUDED.role;

COMMIT;
