package com.cinema.cms.repository;

import com.cinema.cms.entity.Ticket;
import com.cinema.cms.entity.TicketStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketRepository extends JpaRepository<Ticket, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Ticket t where t.id in :ids order by t.id")
    List<Ticket> lockByIds(@Param("ids") Collection<Long> ids);
    List<Ticket> findByBookingIdOrderByIdAsc(Long bookingId);

    Optional<Ticket> findByTicketCode(String ticketCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Ticket t where t.ticketCode = :ticketCode")
    Optional<Ticket> findByTicketCodeForUpdate(@Param("ticketCode") String ticketCode);

    boolean existsByTicketCode(String ticketCode);

    boolean existsByShowtimeSeatIdAndStatusIn(Long showtimeSeatId, Collection<TicketStatus> statuses);

    interface BookingTicketCount {
        Long getBookingId();

        long getTicketCount();
    }

    @Query(
            "select t.bookingId as bookingId, count(t) as ticketCount from Ticket t"
                    + " where t.bookingId in :bookingIds group by t.bookingId")
    List<BookingTicketCount> countByBookingIds(@Param("bookingIds") Collection<Long> bookingIds);
}
