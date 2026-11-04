-- ID contract: hotel 1/2/3; room_type 1..6.
INSERT INTO hotel (tenant_id, name, address)
VALUES (1, 'Demo Hotel A - Hà Nội', '12 Hàng Trống, Hoàn Kiếm, Hà Nội');
INSERT INTO hotel (tenant_id, name, address)
VALUES (2, 'Demo Hotel B - TP.HCM', '25 Nguyễn Huệ, Quận 1, TP.HCM');
INSERT INTO hotel (tenant_id, name, address)
VALUES (1, 'Demo Hotel A - Đà Nẵng', '08 Bạch Đằng, Hải Châu, Đà Nẵng');

INSERT INTO room_type (tenant_id, hotel_id, name, capacity, base_price, total_rooms)
VALUES (1, 1, 'Standard', 2, 500000, 3);
INSERT INTO room_type (tenant_id, hotel_id, name, capacity, base_price, total_rooms)
VALUES (1, 1, 'Deluxe', 2, 900000, 4);
INSERT INTO room_type (tenant_id, hotel_id, name, capacity, base_price, total_rooms)
VALUES (2, 2, 'Standard', 2, 450000, 5);
INSERT INTO room_type (tenant_id, hotel_id, name, capacity, base_price, total_rooms)
VALUES (2, 2, 'Deluxe', 2, 850000, 6);
INSERT INTO room_type (tenant_id, hotel_id, name, capacity, base_price, total_rooms)
VALUES (1, 3, 'Race Test', 2, 500000, 1);
INSERT INTO room_type (tenant_id, hotel_id, name, capacity, base_price, total_rooms)
VALUES (1, 3, 'Standard', 2, 600000, 5);

INSERT INTO room_inventory (tenant_id, room_type_id, stay_date, total)
SELECT rt.tenant_id, rt.id, d::date, rt.total_rooms
FROM room_type rt
CROSS JOIN generate_series(DATE '2026-11-01', DATE '2027-01-31', INTERVAL '1 day') AS d;
