package com.cinema.cms.repository;

import com.cinema.cms.entity.ShowtimeSeat;
import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ShowtimeSeatRepository extends JpaRepository<ShowtimeSeat, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ShowtimeSeat s where s.showtimeId = :id order by s.seatId")
    List<ShowtimeSeat> findByShowtimeIdForUpdate(@Param("id") Long id);
}
