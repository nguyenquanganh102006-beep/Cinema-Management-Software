package com.cinema.cms.repository;

import com.cinema.cms.entity.BookingConcession;
import com.cinema.cms.entity.BookingConcessionId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingConcessionRepository extends JpaRepository<BookingConcession, BookingConcessionId> {
    @Query("select bc from BookingConcession bc where bc.id.bookingId = :bookingId order by bc.id.itemId")
    List<BookingConcession> findByBookingId(@Param("bookingId") Long bookingId);
}
