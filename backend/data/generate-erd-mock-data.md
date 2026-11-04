# Generate ERD mock data

`generate-erd-mock-data.sh` inserts deterministic mock users and bookings into a local database that already has IAM, Catalog and Booking migrations. It recalculates `room_inventory.sold` from all active `PENDING_PAYMENT` and `CONFIRMED` bookings so the fixture demonstrates the ERD inventory invariant.

The script replaces bookings owned by its dedicated `mock.*@stayhub.example.test` users and any Support tickets linked to those mock bookings. It leaves other users, bookings and tickets intact. It does not rebuild the Docker volume or create/alter tables.

The ERD marks Support as target design, not verified implementation. The SQL creates Support fixture rows only when both `support_ticket` and `ticket_message` already exist; otherwise it prints a notice and skips them. Hotel and room-type records come from the existing Catalog Flyway seed. The script uses the IDs in that migration rather than the ERD's illustrative room type ID 10, and it only inserts columns present in current migrations.

## Run

From any directory:

```bash
bash /Volumes/DATA/JIKAY/src/project-demo/HotelBookingSystem/backend/data/generate-erd-mock-data.sh
```

The runner checks the database container and required tables, runs the SQL in a single transaction, and prints booking status counts and inventory sold values. Re-running it replaces its own mock bookings and tickets.

To inspect the full generated data:

```bash
docker exec -it hotelbooking-postgres psql -U hotelbooking -d hotelbooking
```

Then query users, bookings and inventory:

```sql
SELECT tenant_id, email, role
FROM app_user
WHERE email LIKE 'mock.%@stayhub.example.test'
ORDER BY tenant_id, role, email;

SELECT id, tenant_id, room_type_id, user_email, check_in, check_out,
       room_count, total_amount, status, payment_expires_at
FROM booking
WHERE user_email LIKE 'mock.%@stayhub.example.test'
ORDER BY id;

SELECT tenant_id, room_type_id, stay_date, total, sold, total - sold AS available
FROM room_inventory
WHERE sold > 0
ORDER BY tenant_id, room_type_id, stay_date;
```

Exit `psql` with `\q`. The generated dates are December 2026 and assume Catalog inventory includes those dates.
