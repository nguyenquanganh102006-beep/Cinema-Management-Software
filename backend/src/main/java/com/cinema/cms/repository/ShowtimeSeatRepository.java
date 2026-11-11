package com.cinema.cms.repository;

import com.cinema.cms.entity.ShowtimeSeat;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ShowtimeSeatRepository extends JpaRepository<ShowtimeSeat, Long> {
    @Query("select s.seatId from ShowtimeSeat s where s.id in :ids")
    List<Long> physicalSeatIds(@Param("ids") Collection<Long> ids);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ShowtimeSeat s where s.showtimeId = :id order by s.seatId")
    List<ShowtimeSeat> findByShowtimeIdForUpdate(@Param("id") Long id);


    List<ShowtimeSeat> findByShowtimeIdOrderBySeatId(Long id);

    List<ShowtimeSeat> findByHoldTokenOrderBySeatId(String token);

    // Scalar read only: VNPay URL creation must not load ShowtimeSeat entities before checkout.
    @Query("select s.holdUntil from ShowtimeSeat s where s.holdToken = :token")
    List<Instant> findHoldUntilByHoldToken(@Param("token") String token);

    interface HoldMember {
      Long getShowtimeId();

      Long getSeatId();
    }

    @Query(
        value =
            "select showtime_id as showtimeId,seat_id as seatId from showtime_seats where"
                + " hold_token=:token order by showtime_id,seat_id",
        nativeQuery = true)
    List<HoldMember> holdMembers(@Param("token") String token);

    @Modifying
    @Query(value = "set local lock_timeout = '2s'", nativeQuery = true)
    void configureLockTimeout();

    @Query(
        value =
            "select * from showtime_seats where showtime_id=:showtime and seat_id in (:seats) order"
                + " by showtime_id,seat_id for update",
        nativeQuery = true)
    List<ShowtimeSeat> lockSeats(
        @Param("showtime") Long showtime, @Param("seats") Collection<Long> seats);

    @Query(
        value =
            "select distinct hold_token from showtime_seats where status='HOLDING' and"
                + " hold_until<=:now and hold_token is not null order by hold_token limit :batch",
        nativeQuery = true)
    List<String> expiredTokens(@Param("now") Instant now, @Param("batch") int batch);
}
