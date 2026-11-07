package com.cinema.cms.repository;

import com.cinema.cms.entity.BookingSeat;
import com.cinema.cms.entity.BookingSeatId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingSeatRepository extends JpaRepository<BookingSeat, BookingSeatId> {
    @Query("select bs from BookingSeat bs where bs.id.bookingId = :bookingId order by bs.id.seatId")
    List<BookingSeat> findByBookingId(@Param("bookingId") Long bookingId);
}
