-- Run only against a dedicated reference database after Flyway. No source schema reset.
DO $$
DECLARE movie BIGINT; room INTEGER; show BIGINT; left_seat BIGINT; right_seat BIGINT;
BEGIN
 SELECT movie_id INTO movie FROM movies WHERE title='Reference seat holding demo' LIMIT 1;
 IF movie IS NULL THEN INSERT INTO movies(title,duration_minutes,age_rating,release_date) VALUES ('Reference seat holding demo',110,'T18',CURRENT_DATE) RETURNING movie_id INTO movie; END IF;
 SELECT auditorium_id INTO room FROM auditoriums WHERE name='Reference room' LIMIT 1;
 IF room IS NULL THEN
  INSERT INTO auditoriums(name,total_seats) VALUES ('Reference room',6) RETURNING auditorium_id INTO room;
  INSERT INTO seats(auditorium_id,seat_row,seat_number,seat_type) VALUES (room,'A',1,'STANDARD'),(room,'A',2,'STANDARD'),(room,'B',1,'VIP'),(room,'B',2,'VIP');
  INSERT INTO seats(auditorium_id,seat_row,seat_number,seat_type) VALUES (room,'C',1,'SWEETBOX') RETURNING seat_id INTO left_seat;
  INSERT INTO seats(auditorium_id,seat_row,seat_number,seat_type) VALUES (room,'C',2,'SWEETBOX') RETURNING seat_id INTO right_seat;
  UPDATE seats SET pair_seat_id=right_seat WHERE seat_id=left_seat;
  UPDATE seats SET pair_seat_id=left_seat WHERE seat_id=right_seat;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM showtimes WHERE movie_id=movie AND start_time>CURRENT_TIMESTAMP) THEN
  INSERT INTO showtimes(movie_id,auditorium_id,start_time,end_time,base_price) VALUES (movie,room,CURRENT_TIMESTAMP+interval '1 hour',CURRENT_TIMESTAMP+interval '185 minutes',70000) RETURNING showtime_id INTO show;
  INSERT INTO showtime_seats(showtime_id,seat_id) SELECT show,seat_id FROM seats WHERE auditorium_id=room;
 END IF;
END $$;
