package com.cinema.cms.service;

import com.cinema.cms.dto.TicketResponse;
import com.cinema.cms.dto.TicketValidationRequest;
import com.cinema.cms.dto.TicketValidationResponse;
import com.cinema.cms.entity.*;
import com.cinema.cms.repository.*;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads tickets and performs the gate scan. The gate opens 60 minutes before showtime and closes
 * 30 minutes after {@code showtimes.end_time} (which already includes the 15-minute cleaning
 * buffer). The ticket row lock makes two concurrent scans admit at most one person.
 */
@Service
public class TicketService {
    static final int GATE_OPENS_BEFORE_MINUTES = 60;
    static final int GATE_CLOSES_AFTER_MINUTES = 30;

    private static final Logger audit = LoggerFactory.getLogger("AUDIT_TICKET");

    private final TicketRepository tickets;
    private final BookingRepository bookings;
    private final ShowtimeSeatRepository showtimeSeats;
    private final ShowtimeRepository showtimes;
    private final MovieRepository movies;
    private final SeatRepository seats;
    private final AuditoriumRepository auditoriums;
    private final AgeVerificationRepository ageVerifications;
    private final Clock clock;

    public TicketService(
            TicketRepository tickets,
            BookingRepository bookings,
            ShowtimeSeatRepository showtimeSeats,
            ShowtimeRepository showtimes,
            MovieRepository movies,
            SeatRepository seats,
            AuditoriumRepository auditoriums,
            AgeVerificationRepository ageVerifications,
            Clock clock) {
        this.tickets = tickets;
        this.bookings = bookings;
        this.showtimeSeats = showtimeSeats;
        this.showtimes = showtimes;
        this.movies = movies;
        this.seats = seats;
        this.auditoriums = auditoriums;
        this.ageVerifications = ageVerifications;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TicketResponse getByCode(String ticketCode, SessionPrincipal principal) {
        if (ticketCode == null || ticketCode.isBlank()) throw ticketNotFound();
        Ticket ticket = tickets.findByTicketCode(ticketCode.strip()).orElseThrow(TicketService::ticketNotFound);
        Booking booking =
                bookings.findById(ticket.getBookingId()).orElseThrow(TicketService::ticketNotFound);
        if (!readable(booking, principal)) throw ticketNotFound();
        ShowtimeSeat showtimeSeat = showtimeSeat(ticket);
        return toResponse(ticket, booking, showtimeForBookingId(showtimeSeat.getShowtimeId()), showtimeSeat);
    }

    @Transactional(readOnly = true)
    public List<TicketResponse> listForBooking(Long bookingId, SessionPrincipal principal) {
        if (bookingId == null || bookingId <= 0) throw bookingNotFound();
        Booking booking = bookings.findById(bookingId).orElseThrow(TicketService::bookingNotFound);
        if (!readable(booking, principal)) throw bookingNotFound();
        Showtime showtime = showtimeForBooking(booking);
        return tickets.findByBookingIdOrderByIdAsc(bookingId).stream()
                .map(ticket -> toResponse(ticket, booking, showtime, showtimeSeat(ticket)))
                .toList();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TicketValidationResponse validate(
            TicketValidationRequest request, SessionPrincipal principal) {
        requireStaff(principal);
        if (request == null || request.ticketCode() == null || request.ticketCode().isBlank()) {
            throw new ApiException(400, "VALIDATION_ERROR", "Mã vé là bắt buộc");
        }

        // Lock first: a second scan waits here and then observes USED.
        Ticket ticket =
                tickets
                        .findByTicketCodeForUpdate(request.ticketCode().strip())
                        .orElseThrow(TicketService::ticketNotFound);
        if (ticket.getStatus() == TicketStatus.USED) throw alreadyUsed();
        if (ticket.getStatus() != TicketStatus.VALID) throw notValid();

        ShowtimeSeat showtimeSeat = showtimeSeat(ticket);
        Showtime showtime =
                showtimes.findById(showtimeSeat.getShowtimeId()).orElseThrow(TicketService::ticketNotFound);
        Movie movie =
                movies.findById(showtime.getMovieId()).orElseThrow(TicketService::ticketNotFound);
        Seat seat = seats.findById(showtimeSeat.getSeatId()).orElseThrow(TicketService::ticketNotFound);

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant opensAt = showtime.getStartTime().minusSeconds(GATE_OPENS_BEFORE_MINUTES * 60L);
        Instant closesAt = showtime.getEndTime().plusSeconds(GATE_CLOSES_AFTER_MINUTES * 60L);
        if (now.isBefore(opensAt) || now.isAfter(closesAt)) throw outsideShowtime();

        if (movie.getAgeRating() != AgeRating.P
                && !Boolean.TRUE.equals(request.ageDocumentChecked())) {
            throw ageVerificationRequired();
        }

        ticket.markUsed(now);
        tickets.saveAndFlush(ticket);
        if (movie.getAgeRating() != AgeRating.P) {
            ageVerifications.save(
                    AgeVerification.gate(ticket.getId(), principal.userId(), clock));
        }

        audit.info(
                "action=TICKET_VALIDATED result=USED ticketId={} ticketCode={} bookingId={}"
                        + " showtimeId={} staffId={} ageRating={}",
                ticket.getId(),
                ticket.getTicketCode(),
                ticket.getBookingId(),
                showtime.getId(),
                principal.userId(),
                movie.getAgeRating());
        return new TicketValidationResponse(
                ticket.getId(),
                ticket.getTicketCode(),
                ticket.getStatus().name(),
                ticket.getBookingId(),
                bookings.findById(ticket.getBookingId())
                        .map(Booking::getBookingCode)
                        .orElse(null),
                showtime.getId(),
                movie.getId(),
                movie.getTitle(),
                movie.getAgeRating(),
                seat.getId(),
                seat.getSeatRow(),
                seat.getSeatNumber(),
                ticket.getUsedAt());
    }

    private TicketResponse toResponse(
            Ticket ticket,
            Booking booking,
            Showtime showtime,
            ShowtimeSeat showtimeSeat) {
        Movie movie =
                movies.findById(showtime.getMovieId()).orElseThrow(TicketService::ticketNotFound);
        Seat seat = seats.findById(showtimeSeat.getSeatId()).orElseThrow(TicketService::ticketNotFound);
        Auditorium room =
                auditoriums.findById(showtime.getAuditoriumId()).orElse(null);
        return new TicketResponse(
                ticket.getId(),
                ticket.getTicketCode(),
                ticket.getPriceSnapshot(),
                ticket.getStatus().name(),
                ticket.getIssuedAt(),
                ticket.getUsedAt(),
                booking.getId(),
                booking.getBookingCode(),
                booking.getStatus().name(),
                booking.getChannel().name(),
                showtime.getId(),
                offset(showtime.getStartTime()),
                offset(showtime.getEndTime()),
                movie.getId(),
                movie.getTitle(),
                movie.getAgeRating(),
                showtime.getAuditoriumId(),
                room == null ? null : room.getName(),
                seat.getId(),
                seat.getSeatRow(),
                seat.getSeatNumber(),
                seat.getSeatType().name());
    }

    private Showtime showtimeForBooking(Booking booking) {
        return showtimeForBookingId(booking.getShowtimeId());
    }

    private Showtime showtimeForBookingId(Long showtimeId) {
        return showtimes.findById(showtimeId).orElseThrow(TicketService::ticketNotFound);
    }

    private ShowtimeSeat showtimeSeat(Ticket ticket) {
        return showtimeSeats
                .findById(ticket.getShowtimeSeatId())
                .orElseThrow(TicketService::ticketNotFound);
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.atZone(ShowtimeTimeService.BUSINESS_ZONE).toOffsetDateTime();
    }

    private static boolean readable(Booking booking, SessionPrincipal principal) {
        if (principal == null) return false;
        if (principal.role() == UserRole.STAFF) return true;
        return principal.role() == UserRole.CUSTOMER
                && Objects.equals(booking.getUserId(), principal.userId());
    }

    private static void requireStaff(SessionPrincipal principal) {
        if (principal == null
                || principal.role() != UserRole.STAFF
                || principal.userId() == null) {
            throw new ApiException(403, "ACCESS_DENIED", "Chỉ STAFF được soát vé");
        }
    }

    private static ApiException ticketNotFound() {
        return new ApiException(404, "TICKET_NOT_FOUND", "Không tìm thấy vé");
    }

    private static ApiException bookingNotFound() {
        return new ApiException(404, "BOOKING_NOT_FOUND", "Không tìm thấy Booking");
    }

    private static ApiException alreadyUsed() {
        return new ApiException(409, "TICKET_ALREADY_USED", "Vé đã được soát");
    }

    private static ApiException notValid() {
        return new ApiException(422, "TICKET_NOT_VALID", "Vé không còn hiệu lực");
    }

    private static ApiException outsideShowtime() {
        return new ApiException(
                422,
                "TICKET_OUTSIDE_SHOWTIME",
                "Ngoài khung giờ soát vé của suất chiếu");
    }

    private static ApiException ageVerificationRequired() {
        return new ApiException(
                422,
                "AGE_VERIFICATION_REQUIRED",
                "Nhân viên phải xác nhận đã kiểm tra giấy tờ đủ tuổi");
    }
}
