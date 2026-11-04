# Rebuild DB and seed synthetic data

Use `rebuild-demo-db.sh` to rebuild the local Docker database from scratch. The script removes the Compose PostgreSQL volume, starts PostgreSQL, runs IAM and Catalog Flyway migrations, inserts five synthetic `app_user` rows, and prints verification queries.

Catalog's Flyway seed supplies the hotels, six baseline room types and 552 inventory-date rows. `seed-virtual-data.sql` adds synthetic accounts using reserved `.test` email addresses; it contains no real identity data and is safe to apply repeatedly. These sample emails are database fixtures, not Google accounts for IAM login.

The Booking V1 migration in this checkout still references the old `room` table, so the script deliberately stops after IAM and Catalog. Run Booking only after its owner updates that migration to the `room_type`/`room_inventory` schema.

## Run

From any directory:

```bash
bash /Volumes/DATA/JIKAY/src/project-demo/HotelBookingSystem/backend/data/rebuild-demo-db.sh
```

The script asks you to type `REBUILD` because it deletes the current local database volume. To acknowledge that deletion in advance:

```bash
bash /Volumes/DATA/JIKAY/src/project-demo/HotelBookingSystem/backend/data/rebuild-demo-db.sh --yes
```

Expected final counts:

| tenants | app_users | hotels | room_types | inventory_rows | sold |
|---:|---:|---:|---:|---:|---:|
| 2 | 5 | 3 | 6 | 552 | 0 |

Use `seed-virtual-data.sql` by itself only after IAM and Catalog migrations have been applied:

```bash
cd /Volumes/DATA/JIKAY/src/project-demo/HotelBookingSystem/backend
docker exec -i hotelbooking-postgres psql -v ON_ERROR_STOP=1 -U hotelbooking -d hotelbooking \
  < data/seed-virtual-data.sql
```

This seed does not create `booking` rows or alter `room_inventory.sold`; Booking's current migration is not yet compatible with the Catalog schema, and only Booking may write `sold`.
