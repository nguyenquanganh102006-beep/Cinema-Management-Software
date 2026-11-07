package com.cinema.cms.repository;

import com.cinema.cms.entity.AgeVerification;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgeVerificationRepository extends JpaRepository<AgeVerification, Long> {
    List<AgeVerification> findByBookingIdOrderByIdAsc(Long bookingId);

    List<AgeVerification> findByTicketIdOrderByIdAsc(Long ticketId);

    boolean existsByBookingIdAndVerificationStage(Long bookingId, String verificationStage);
}
