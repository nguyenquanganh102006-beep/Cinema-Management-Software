package com.cinema.cms.repository;

import com.cinema.cms.entity.Showtime;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ShowtimeRepository extends JpaRepository<Showtime, Long>, JpaSpecificationExecutor<Showtime> {
    interface Listing {
        Showtime getShowtime();
        com.cinema.cms.entity.Movie getMovie();
        com.cinema.cms.entity.Auditorium getAuditorium();
    }
    @Query(value = "select s as showtime, m as movie, a as auditorium from Showtime s join Movie m on m.id=s.movieId join Auditorium a on a.id=s.auditoriumId where (:movie is null or s.movieId=:movie) and (:room is null or s.auditoriumId=:room) and (cast(:start as timestamp) is null or s.startTime>=:start) and (cast(:until as timestamp) is null or s.startTime<:until) and (:includeInactive = true or (m.active = true and a.active = true))",
        countQuery = "select count(s) from Showtime s join Movie m on m.id=s.movieId join Auditorium a on a.id=s.auditoriumId where (:movie is null or s.movieId=:movie) and (:room is null or s.auditoriumId=:room) and (cast(:start as timestamp) is null or s.startTime>=:start) and (cast(:until as timestamp) is null or s.startTime<:until) and (:includeInactive = true or (m.active = true and a.active = true))")
    org.springframework.data.domain.Page<Listing> listing(@Param("movie") Long movie, @Param("room") Integer room, @Param("start") Instant start, @Param("until") Instant until, @Param("includeInactive") boolean includeInactive, org.springframework.data.domain.Pageable pageable);
    boolean existsByAuditoriumId(Integer auditoriumId);

    // Scalar read after inventory locks: do not reuse an Entity loaded before waiting.
    @Query("select s.startTime from Showtime s where s.id = :id")
    Optional<Instant> findCurrentStartTime(@Param("id") Long id);

    @Query("select s.auditoriumId from Showtime s where s.id = :id")
    Optional<Integer> findAuditoriumId(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Showtime s where s.id = :id")
    Optional<Showtime> findByIdForUpdate(@Param("id") Long id);

    @Query("select count(s) from Showtime s where s.auditoriumId = :room and s.startTime < :end and s.endTime > :start and (:excluded is null or s.id <> :excluded)")
    long countOverlaps(@Param("room") Integer room, @Param("start") Instant start,
                       @Param("end") Instant end, @Param("excluded") Long excluded);

    // Ticket belongs to Sprint E. Query its existing table without introducing a duplicate Ticket entity.
    @Query(value = "select exists(select 1 from tickets t join showtime_seats ss on ss.showtime_seat_id=t.showtime_seat_id where ss.showtime_id=:id)", nativeQuery = true)
    boolean hasAnyTicket(@Param("id") Long id);
}
