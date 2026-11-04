-- Mock fixtures derived from backend/docs/documents/erd.md.
-- Run after IAM, Catalog and Booking migrations. Support fixtures are added only
-- when both proposed Support tables already exist.
-- All fixture email addresses use the reserved .test domain.

BEGIN;

-- Fixture accounts are stable identifiers for repeatable local mock runs.
INSERT INTO app_user (email, tenant_id, role) VALUES
    ('mock.user.tenant1@stayhub.example.test', 1, 'USER'),
    ('mock.user2.tenant1@stayhub.example.test', 1, 'USER'),
    ('mock.admin.tenant1@stayhub.example.test', 1, 'ADMIN'),
    ('mock.user.tenant2@stayhub.example.test', 2, 'USER'),
    ('mock.admin.tenant2@stayhub.example.test', 2, 'ADMIN')
ON CONFLICT (email) DO UPDATE
SET tenant_id = EXCLUDED.tenant_id,
    role = EXCLUDED.role;

-- Remove only prior Support fixtures and tickets referring to the dedicated
-- mock accounts. Real tickets are left intact.
DO $support_cleanup$
BEGIN
    IF to_regclass('public.support_ticket') IS NOT NULL THEN
        IF to_regclass('public.ticket_message') IS NOT NULL THEN
            EXECUTE $sql$
                DELETE FROM ticket_message m
                USING support_ticket t
                WHERE m.ticket_id = t.id
                  AND (t.subject LIKE '[MOCK] %'
                       OR t.booking_id IN (
                           SELECT b.id FROM booking b
                           WHERE b.user_email IN (
                               'mock.user.tenant1@stayhub.example.test',
                               'mock.user2.tenant1@stayhub.example.test',
                               'mock.user.tenant2@stayhub.example.test'
                           )
                       ))
            $sql$;
        END IF;

        EXECUTE $sql$
            DELETE FROM support_ticket
            WHERE subject LIKE '[MOCK] %'
               OR booking_id IN (
                   SELECT b.id FROM booking b
                   WHERE b.user_email IN (
                       'mock.user.tenant1@stayhub.example.test',
                       'mock.user2.tenant1@stayhub.example.test',
                       'mock.user.tenant2@stayhub.example.test'
                   )
               )
        $sql$;
    ELSE
        RAISE NOTICE 'Support tables are not migrated; Support mock rows will be skipped.';
    END IF;
END
$support_cleanup$;

-- These reserved mock accounts own the generated bookings. Re-running the
-- script replaces only their previous fixture bookings.
DELETE FROM booking
WHERE user_email IN (
    'mock.user.tenant1@stayhub.example.test',
    'mock.user2.tenant1@stayhub.example.test',
    'mock.user.tenant2@stayhub.example.test'
);

WITH seed (
    tenant_id, room_type_id, user_email, check_in, check_out,
    room_count, base_price, status
) AS (
    VALUES
        (1, 1, 'mock.user.tenant1@stayhub.example.test', DATE '2026-12-01', DATE '2026-12-03', 1, 500000::numeric, 'PENDING_PAYMENT'),
        (1, 2, 'mock.user2.tenant1@stayhub.example.test', DATE '2026-12-04', DATE '2026-12-06', 2, 900000::numeric, 'CONFIRMED'),
        (2, 3, 'mock.user.tenant2@stayhub.example.test', DATE '2026-12-02', DATE '2026-12-04', 2, 450000::numeric, 'PENDING_PAYMENT'),
        (1, 5, 'mock.user.tenant1@stayhub.example.test', DATE '2026-12-10', DATE '2026-12-12', 1, 500000::numeric, 'CANCELLED'),
        (2, 4, 'mock.user.tenant2@stayhub.example.test', DATE '2026-12-10', DATE '2026-12-11', 1, 850000::numeric, 'EXPIRED')
)
INSERT INTO booking (
    tenant_id, room_type_id, user_email, check_in, check_out, room_count,
    total_amount, status, payment_expires_at
)
SELECT s.tenant_id,
       s.room_type_id,
       s.user_email,
       s.check_in,
       s.check_out,
       s.room_count,
       s.base_price * (s.check_out - s.check_in) * s.room_count,
       s.status,
       CASE
           WHEN s.status IN ('PENDING_PAYMENT', 'CONFIRMED') THEN now() + INTERVAL '15 minutes'
           ELSE now() - INTERVAL '1 day'
       END
FROM seed s
JOIN app_user u
  ON u.tenant_id = s.tenant_id
 AND u.email = s.user_email;

-- Keep inventory sold consistent with all bookings that still hold rooms.
-- This is fixture setup for a local database; application ownership remains Booking.
UPDATE room_inventory i
SET sold = COALESCE((
    SELECT SUM(b.room_count)::integer
    FROM booking b
    WHERE b.tenant_id = i.tenant_id
      AND b.room_type_id = i.room_type_id
      AND i.stay_date >= b.check_in
      AND i.stay_date < b.check_out
      AND b.status IN ('PENDING_PAYMENT', 'CONFIRMED')
), 0);

CREATE TEMP TABLE stayhub_mock_booking_refs (
    fixture_key TEXT PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    user_email VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL
) ON COMMIT DROP;

INSERT INTO stayhub_mock_booking_refs (fixture_key, booking_id, tenant_id, user_email, status)
SELECT CASE
           WHEN b.tenant_id = 1 AND b.room_type_id = 1 THEN 'pending-tenant1'
           WHEN b.tenant_id = 1 AND b.room_type_id = 2 THEN 'confirmed-tenant1'
           WHEN b.tenant_id = 2 AND b.room_type_id = 3 THEN 'pending-tenant2'
           WHEN b.tenant_id = 1 AND b.room_type_id = 5 THEN 'cancelled-tenant1'
           WHEN b.tenant_id = 2 AND b.room_type_id = 4 THEN 'expired-tenant2'
       END,
       b.id, b.tenant_id, b.user_email, b.status
FROM booking b
WHERE b.user_email IN (
    'mock.user.tenant1@stayhub.example.test',
    'mock.user2.tenant1@stayhub.example.test',
    'mock.user.tenant2@stayhub.example.test'
)
  AND (b.check_in, b.room_type_id) IN (
      (DATE '2026-12-01', 1),
      (DATE '2026-12-04', 2),
      (DATE '2026-12-02', 3),
      (DATE '2026-12-10', 5),
      (DATE '2026-12-10', 4)
  );

-- Support is still target design in this repository. Seed it only if its schema
-- has been migrated; do not create proposed tables from a data script.
DO $support_seed$
BEGIN
    IF to_regclass('public.support_ticket') IS NULL
       OR to_regclass('public.ticket_message') IS NULL THEN
        RAISE NOTICE 'Support schema is absent; skipped support_ticket and ticket_message fixtures.';
        RETURN;
    END IF;

    EXECUTE $sql$
        INSERT INTO support_ticket (
            tenant_id, requester_user_id, booking_id, assigned_admin_user_id,
            subject, description, status, created_at, updated_at
        )
        SELECT refs.tenant_id,
               requester.id,
               refs.booking_id,
               admin.id,
               fixture.subject,
               fixture.description,
               fixture.ticket_status,
               now() - INTERVAL '2 hours',
               now() - INTERVAL '1 hour'
        FROM (VALUES
            ('pending-tenant1', '[MOCK] Payment question', 'User asks how mock payment works.', 'OPEN', NULL::text),
            ('confirmed-tenant1', '[MOCK] Check-in details', 'User asks about arrival time.', 'IN_PROGRESS', 'mock.admin.tenant1@stayhub.example.test'),
            ('expired-tenant2', '[MOCK] Expired booking help', 'User asks why the pending booking expired.', 'RESOLVED', 'mock.admin.tenant2@stayhub.example.test')
        ) AS fixture(fixture_key, subject, description, ticket_status, admin_email)
        JOIN pg_temp.stayhub_mock_booking_refs refs USING (fixture_key)
        JOIN app_user requester
          ON requester.tenant_id = refs.tenant_id
         AND requester.email = refs.user_email
        LEFT JOIN app_user admin
          ON admin.tenant_id = refs.tenant_id
         AND admin.email = fixture.admin_email
    $sql$;

    EXECUTE $sql$
        INSERT INTO ticket_message (tenant_id, ticket_id, author_user_id, body, created_at)
        SELECT t.tenant_id, t.id, author.id, fixture.body, now() - fixture.age
        FROM (VALUES
            ('[MOCK] Payment question', 'mock.user.tenant1@stayhub.example.test', 'I need help understanding the mock payment deadline.', INTERVAL '90 minutes'),
            ('[MOCK] Payment question', 'mock.admin.tenant1@stayhub.example.test', 'The demo payment can be confirmed before the expiry time.', INTERVAL '60 minutes'),
            ('[MOCK] Check-in details', 'mock.user2.tenant1@stayhub.example.test', 'Please confirm the check-in date for my stay.', INTERVAL '80 minutes'),
            ('[MOCK] Check-in details', 'mock.admin.tenant1@stayhub.example.test', 'Your booking is confirmed for the selected dates.', INTERVAL '50 minutes'),
            ('[MOCK] Expired booking help', 'mock.user.tenant2@stayhub.example.test', 'I missed the payment window.', INTERVAL '70 minutes'),
            ('[MOCK] Expired booking help', 'mock.admin.tenant2@stayhub.example.test', 'The booking expired and inventory was released.', INTERVAL '40 minutes')
        ) AS fixture(subject, author_email, body, age)
        JOIN support_ticket t ON t.subject = fixture.subject
        JOIN app_user author
          ON author.tenant_id = t.tenant_id
         AND author.email = fixture.author_email
    $sql$;
END
$support_seed$;

COMMIT;
