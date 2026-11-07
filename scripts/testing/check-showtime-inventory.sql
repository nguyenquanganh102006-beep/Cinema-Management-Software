-- Read-only audit. Empty result sets mean no mismatch was found in this database.
-- Compare IDs, not just counts: equal counts can still hide a wrong mapping.
WITH inventory_audit AS (
    SELECT sh.showtime_id, sh.auditorium_id,
           ARRAY(SELECT s.seat_id FROM seats s WHERE s.auditorium_id = sh.auditorium_id
                 EXCEPT SELECT ss.seat_id FROM showtime_seats ss WHERE ss.showtime_id = sh.showtime_id
                 ORDER BY seat_id) AS missing_seat_ids,
           ARRAY(SELECT ss.seat_id FROM showtime_seats ss WHERE ss.showtime_id = sh.showtime_id
                 EXCEPT SELECT s.seat_id FROM seats s WHERE s.auditorium_id = sh.auditorium_id
                 ORDER BY seat_id) AS extra_seat_ids
    FROM showtimes sh
)
SELECT * FROM inventory_audit
WHERE cardinality(missing_seat_ids) > 0 OR cardinality(extra_seat_ids) > 0
ORDER BY showtime_id;

SELECT s.auditorium_id, s.seat_id, s.seat_row, s.seat_number, s.pair_seat_id,
       p.auditorium_id AS pair_auditorium_id, p.seat_row AS pair_row, p.seat_number AS pair_number
FROM seats s LEFT JOIN seats p ON p.seat_id = s.pair_seat_id
WHERE (s.seat_type = 'SWEETBOX' AND (
    p.seat_id IS NULL OR p.seat_id = s.seat_id OR p.seat_type <> 'SWEETBOX'
    OR p.auditorium_id <> s.auditorium_id OR p.seat_row <> s.seat_row
    OR abs(s.seat_number::bigint - p.seat_number::bigint) <> 1
    OR p.pair_seat_id IS DISTINCT FROM s.seat_id
)) OR (s.seat_type <> 'SWEETBOX' AND s.pair_seat_id IS NOT NULL)
ORDER BY s.auditorium_id, s.seat_id;
