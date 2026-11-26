ALTER TABLE showtimes
    ADD COLUMN movie_title_snapshot VARCHAR(200),
    ADD COLUMN age_rating_snapshot age_rating_enum,
    ADD COLUMN duration_minutes_snapshot INTEGER,
    ADD COLUMN auditorium_name_snapshot VARCHAR(50),
    ADD COLUMN auditorium_type_snapshot VARCHAR(20);

UPDATE showtimes s
SET movie_title_snapshot = m.title,
    age_rating_snapshot = m.age_rating,
    duration_minutes_snapshot = m.duration_minutes
FROM movies m
WHERE m.movie_id = s.movie_id;

UPDATE showtimes s
SET auditorium_name_snapshot = a.name,
    auditorium_type_snapshot = a.auditorium_type::text
FROM auditoriums a
WHERE a.auditorium_id = s.auditorium_id;

ALTER TABLE showtimes
    ALTER COLUMN movie_title_snapshot SET NOT NULL,
    ALTER COLUMN age_rating_snapshot SET NOT NULL,
    ALTER COLUMN duration_minutes_snapshot SET NOT NULL,
    ALTER COLUMN auditorium_name_snapshot SET NOT NULL,
    ALTER COLUMN auditorium_type_snapshot SET NOT NULL,
    ADD CONSTRAINT chk_showtime_duration_snapshot CHECK (duration_minutes_snapshot > 0);
