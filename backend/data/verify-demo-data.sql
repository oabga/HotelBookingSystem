DO $$
DECLARE
    tenant_count       INT;
    app_user_count     INT;
    hotel_count        INT;
    room_type_count    INT;
    inventory_count    INT;
    catalog_migrations  INT;
BEGIN
    SELECT count(*) INTO tenant_count FROM tenant;
    SELECT count(*) INTO app_user_count FROM app_user;
    SELECT count(*) INTO hotel_count FROM hotel;
    SELECT count(*) INTO room_type_count FROM room_type;
    SELECT count(*) INTO inventory_count FROM room_inventory;
    SELECT count(*) INTO catalog_migrations
    FROM flyway_schema_history_catalog
    WHERE success AND version IN ('1', '2');

    IF tenant_count <> 2 OR app_user_count <> 5 OR hotel_count <> 3
       OR room_type_count <> 6 OR inventory_count <> 552 OR catalog_migrations <> 2 THEN
        RAISE EXCEPTION 'Unexpected demo seed counts: tenants %, app_users %, hotels %, room_types %, inventory %, catalog migrations %',
            tenant_count, app_user_count, hotel_count, room_type_count, inventory_count, catalog_migrations;
    END IF;

    IF EXISTS (SELECT 1 FROM room_inventory WHERE sold <> 0) THEN
        RAISE EXCEPTION 'Catalog seed must leave room_inventory.sold at 0';
    END IF;
END $$;
