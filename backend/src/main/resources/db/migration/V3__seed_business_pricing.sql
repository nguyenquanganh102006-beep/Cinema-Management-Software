-- G1 BR-01..06. TIME_SURCHARGE is one OR condition, never two cumulative fees.
INSERT INTO pricing_rules(rule_code, rule_type, target_ref, amount, description) VALUES
('BASE_STANDARD', 'BASE_PRICE', 'STANDARD_2D', 70000, 'Base ticket price before surcharges'),
('SUR_2D', 'ROOM_SURCHARGE', 'STANDARD_2D', 0, 'Standard 2D room'),
('SUR_3D', 'ROOM_SURCHARGE', '3D', 20000, '3D room'),
('SUR_IMAX', 'ROOM_SURCHARGE', 'IMAX', 40000, 'IMAX room'),
('SUR_STANDARD', 'SEAT_SURCHARGE', 'STANDARD', 0, 'Normal seat'),
('SUR_VIP', 'SEAT_SURCHARGE', 'VIP', 15000, 'VIP seat'),
('SUR_SWEETBOX', 'SEAT_SURCHARGE', 'SWEETBOX', 15000, 'Each physical seat in a Sweetbox pair'),
('SUR_TIME', 'TIME_SURCHARGE', 'WEEKEND_OR_AFTER_18H', 10000, 'Fri-Sun OR start time after 18:00; apply once');
