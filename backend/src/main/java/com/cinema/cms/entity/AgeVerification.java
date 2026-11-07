package com.cinema.cms.entity;

import jakarta.persistence.*;
import java.time.Clock;
import java.time.Instant;

@Entity
@Table(name = "age_verifications")
public class AgeVerification {
    public static final String POS = "POS";
    public static final String GATE = "GATE";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "verification_id") private Long id;
    @Column(name = "booking_id") private Long bookingId;
    @Column(name = "ticket_id") private Long ticketId;
    @Column(name = "verification_stage", nullable = false, length = 10) private String verificationStage;
    @Column(name = "verified_by_staff_id", nullable = false) private Long verifiedByStaffId;
    @Column(name = "verified_at", nullable = false) private Instant verifiedAt;

    protected AgeVerification() {}

    public static AgeVerification pos(Long bookingId, Long staffId, Clock clock) {
        return create(bookingId, null, POS, staffId, clock);
    }

    public static AgeVerification gate(Long ticketId, Long staffId, Clock clock) {
        return create(null, ticketId, GATE, staffId, clock);
    }

    private static AgeVerification create(Long bookingId, Long ticketId, String stage, Long staffId, Clock clock) {
        AgeVerification verification = new AgeVerification();
        verification.bookingId = bookingId;
        verification.ticketId = ticketId;
        verification.verificationStage = stage;
        verification.verifiedByStaffId = staffId;
        verification.verifiedAt = clock.instant();
        return verification;
    }

    public Long getId() { return id; }
    public Long getBookingId() { return bookingId; }
    public Long getTicketId() { return ticketId; }
    public String getVerificationStage() { return verificationStage; }
    public Long getVerifiedByStaffId() { return verifiedByStaffId; }
    public Instant getVerifiedAt() { return verifiedAt; }
}
