package com.cinema.cms.repository;

import com.cinema.cms.entity.Showtime;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ShowtimeRepository extends JpaRepository<Showtime, Long>, JpaSpecificationExecutor<Showtime> {
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
