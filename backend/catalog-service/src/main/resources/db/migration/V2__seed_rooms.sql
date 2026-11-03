-- tenant_id phải khớp tenant.id (bảng do iam-service tạo, cùng database "hotelbooking"):
-- 1 = Tenant 1 - Demo Hotel A, 2 = Tenant 2 - Demo Hotel B
-- (xem docs/erd-v2.md mục 5 — chỉ đúng khi migrate trên DB mới tạo sạch).
INSERT INTO room (tenant_id, name, price) VALUES
    (1, 'Standard Room 101', 500000),
    (1, 'Deluxe Room 102', 900000),
    (2, 'Standard Room 201', 450000),
    (2, 'Deluxe Room 202', 850000);

-- Phòng riêng dành cho test tranh chỗ cùng khoảng thời gian (race condition) — không dùng ở
-- các test khác để tránh nhiễu kết quả.
INSERT INTO room (tenant_id, name, price) VALUES
    (1, 'Race-Test Room 199', 500000);
