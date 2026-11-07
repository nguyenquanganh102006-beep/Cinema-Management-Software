package com.cinema.cms.repository;

import com.cinema.cms.entity.Ticket;
import com.cinema.cms.entity.TicketStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketRepository extends JpaRepository<Ticket, Long> {
    List<Ticket> findByBookingIdOrderByIdAsc(Long bookingId);

    Optional<Ticket> findByTicketCode(String ticketCode);

    boolean existsByShowtimeSeatIdAndStatusIn(Long showtimeSeatId, Collection<TicketStatus> statuses);
}
