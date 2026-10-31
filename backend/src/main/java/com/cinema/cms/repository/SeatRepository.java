package com.cinema.cms.repository;

import com.cinema.cms.entity.Seat;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<Seat, Long> {
    interface AuditoriumSeatCount { Integer getAuditoriumId(); long getSeatCount(); }

    List<Seat> findByAuditoriumIdOrderBySeatRowAscSeatNumberAsc(Integer auditoriumId);
    long countByAuditoriumId(Integer auditoriumId);
    boolean existsByAuditoriumIdAndSeatRowAndSeatNumberAndIdNot(Integer auditoriumId, String seatRow, int seatNumber, Long id);
    boolean existsByPairSeatId(Long pairSeatId);

    Optional<Seat> findByPairSeatId(Long pairSeatId);

    @Query("select s.auditoriumId from Seat s where s.id = :seatId")
    Optional<Integer> findAuditoriumIdBySeatId(@Param("seatId") Long seatId);

    @Query("select s.auditoriumId as auditoriumId, count(s) as seatCount from Seat s where s.auditoriumId in :ids group by s.auditoriumId")
    List<AuditoriumSeatCount> countByAuditoriumIds(@Param("ids") Collection<Integer> ids);
}