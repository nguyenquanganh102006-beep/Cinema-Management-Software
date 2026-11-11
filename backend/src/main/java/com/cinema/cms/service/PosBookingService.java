package com.cinema.cms.service;

import com.cinema.cms.dto.PageResponse;
import com.cinema.cms.dto.PosBookingDetailResponse;
import com.cinema.cms.dto.PosBookingSummaryResponse;
import com.cinema.cms.entity.*;
import com.cinema.cms.repository.*;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only POS (counter) booking history, built from Booking/Payment rows. */
@Service
public class PosBookingService {
    static final int MAX_PAGE_SIZE = 100;

    private final BookingRepository bookings;
    private final BookingSeatRepository bookingSeats;
    private final BookingConcessionRepository bookingConcessions;
    private final ConcessionItemRepository concessionItems;
    private final TicketRepository tickets;
    private final PaymentRepository payments;
    private final ShowtimeRepository showtimes;
    private final ShowtimeSeatRepository showtimeSeats;
    private final MovieRepository movies;
    private final SeatRepository seats;
    private final AuditoriumRepository auditoriums;
    private final UserRepository users;
    private final RefundRepository refunds;

    public PosBookingService(
            BookingRepository bookings,
            BookingSeatRepository bookingSeats,
            BookingConcessionRepository bookingConcessions,
            ConcessionItemRepository concessionItems,
            TicketRepository tickets,
            PaymentRepository payments,
            ShowtimeRepository showtimes,
            ShowtimeSeatRepository showtimeSeats,
            MovieRepository movies,
            SeatRepository seats,
            AuditoriumRepository auditoriums,
            UserRepository users, RefundRepository refunds) {
        this.bookings = bookings;
        this.bookingSeats = bookingSeats;
        this.bookingConcessions = bookingConcessions;
        this.concessionItems = concessionItems;
        this.tickets = tickets;
        this.payments = payments;
        this.showtimes = showtimes;
        this.showtimeSeats = showtimeSeats;
        this.movies = movies;
        this.seats = seats;
        this.auditoriums = auditoriums;
        this.users = users;
        this.refunds = refunds;
    }

    @Transactional(readOnly = true)
    public PageResponse<PosBookingSummaryResponse> list(
            String bookingCode,
            String date,
            String paymentMethod,
            int page,
            int size,
            SessionPrincipal principal) {
        requireStaff(principal);
        QueryParams.checkPaging(page, size, MAX_PAGE_SIZE);
        String code = blankToNull(bookingCode);
        PaymentMethod method = parsePaymentMethod(paymentMethod);
        DateRange range = parseDate(date);

        Specification<Booking> specification =
                (root, query, cb) -> {
                    List<Predicate> predicates = new ArrayList<>();
                    predicates.add(cb.equal(root.get("channel"), BookingChannel.POS));
                    predicates.add(cb.equal(root.get("status"), BookingStatus.PAID));
                    predicates.add(cb.isNotNull(root.get("paidAt")));
                    if (code != null) {
                        predicates.add(
                                cb.like(
                                        cb.upper(root.get("bookingCode")),
                                        "%" + code.toUpperCase(Locale.ROOT) + "%"));
                    }
                    if (range != null) {
                        predicates.add(cb.greaterThanOrEqualTo(root.get("paidAt"), range.start()));
                        predicates.add(cb.lessThan(root.get("paidAt"), range.endExclusive()));
                    }
                    if (method != null) {
                        Subquery<Long> subquery = query.subquery(Long.class);
                        Root<Payment> payment = subquery.from(Payment.class);
                        subquery
                                .select(payment.get("id"))
                                .where(
                                        cb.equal(payment.get("bookingId"), root.get("id")),
                                        cb.equal(payment.get("paymentMethod"), method));
                        predicates.add(cb.exists(subquery));
                    }
                    return cb.and(predicates.toArray(Predicate[]::new));
                };

        Page<Booking> found =
                bookings.findAll(
                        specification,
                        PageRequest.of(
                                page,
                                size,
                                Sort.by(Sort.Order.desc("paidAt"), Sort.Order.desc("id"))));
        List<Long> bookingIds = found.getContent().stream().map(Booking::getId).toList();
        Map<Long,String> refundStatus = refunds.statuses(bookingIds);
        Map<Long, Payment> paymentByBooking =
                bookingIds.isEmpty()
                        ? Map.of()
                        : payments.findByBookingIdIn(bookingIds).stream()
                                .collect(Collectors.toMap(Payment::getBookingId, Function.identity()));
        Map<Long, Long> ticketCountByBooking =
                bookingIds.isEmpty()
                        ? Map.of()
                        : tickets.countByBookingIds(bookingIds).stream()
                                .collect(
                                        Collectors.toMap(
                                                TicketRepository.BookingTicketCount::getBookingId,
                                                TicketRepository.BookingTicketCount::getTicketCount));

        return PageResponse.from(
                found,
                booking -> {
                    Payment payment = paymentByBooking.get(booking.getId());
                    return new PosBookingSummaryResponse(
                            booking.getId(),
                            booking.getBookingCode(),
                            booking.getPaidAt(),
                            booking.getTotalAmount(),
                            payment == null ? null : payment.getPaymentMethod().name(),
                            payment == null ? null : payment.getStatus().name(),
                            ticketCountByBooking.getOrDefault(booking.getId(), 0L),
                            refundStatus.getOrDefault(booking.getId(), "NONE"));
                });
    }

    @Transactional(readOnly = true)
    public PosBookingDetailResponse detail(Long bookingId, SessionPrincipal principal) {
        requireStaff(principal);
        if (bookingId == null || bookingId <= 0) throw notFound();
        Booking booking = bookings.findById(bookingId).orElseThrow(PosBookingService::notFound);
        if (booking.getChannel() != BookingChannel.POS) throw notFound();

        Showtime showtime = showtimes.findById(booking.getShowtimeId()).orElseThrow(PosBookingService::notFound);
        Movie movie = movies.findById(showtime.getMovieId()).orElseThrow(PosBookingService::notFound);
        Auditorium room = auditoriums.findById(showtime.getAuditoriumId()).orElse(null);
        Payment payment = payments.findByBookingId(bookingId).orElse(null);

        List<BookingSeat> bookedSeats = bookingSeats.findByBookingId(bookingId);
        Map<Long, Seat> seatById =
                seats.findAllById(
                                bookedSeats.stream().map(line -> line.getId().getSeatId()).toList())
                        .stream()
                        .collect(Collectors.toMap(Seat::getId, Function.identity()));
        List<PosBookingDetailResponse.SeatLine> seatLines =
                bookedSeats.stream()
                        .map(
                                line -> {
                                    Seat seat = seatById.get(line.getId().getSeatId());
                                    return new PosBookingDetailResponse.SeatLine(
                                            line.getId().getSeatId(),
                                            seat == null ? null : seat.getSeatRow(),
                                            seat == null ? null : seat.getSeatNumber(),
                                            seat == null ? null : seat.getSeatType().name(),
                                            line.getQuotedPrice());
                                })
                        .toList();

        List<Ticket> bookingTickets = tickets.findByBookingIdOrderByIdAsc(bookingId);
        Map<Long, ShowtimeSeat> showtimeSeatById =
                showtimeSeats
                        .findAllById(
                                bookingTickets.stream().map(Ticket::getShowtimeSeatId).toList())
                        .stream()
                        .collect(Collectors.toMap(ShowtimeSeat::getId, Function.identity()));
        List<PosBookingDetailResponse.TicketLine> ticketLines =
                bookingTickets.stream()
                        .map(
                                ticket -> {
                                    ShowtimeSeat showtimeSeat = showtimeSeatById.get(ticket.getShowtimeSeatId());
                                    return new PosBookingDetailResponse.TicketLine(
                                            ticket.getId(),
                                            ticket.getTicketCode(),
                                            ticket.getShowtimeSeatId(),
                                            showtimeSeat == null ? null : showtimeSeat.getSeatId(),
                                            ticket.getPriceSnapshot(),
                                            ticket.getStatus().name(),
                                            ticket.getIssuedAt(),
                                            ticket.getUsedAt());
                                })
                        .toList();

        List<BookingConcession> concessions = bookingConcessions.findByBookingId(bookingId);
        Map<Integer, String> itemNames =
                concessionItems
                        .findAllById(
                                concessions.stream().map(line -> line.getId().getItemId()).toList())
                        .stream()
                        .collect(Collectors.toMap(ConcessionItem::getId, ConcessionItem::getName));
        List<PosBookingDetailResponse.ConcessionLine> concessionLines =
                concessions.stream()
                        .map(
                                line -> {
                                    Integer itemId = line.getId().getItemId();
                                    BigDecimal unit = line.getUnitPriceSnapshot();
                                    return new PosBookingDetailResponse.ConcessionLine(
                                            itemId,
                                            itemNames.get(itemId),
                                            line.getQuantity(),
                                            unit,
                                            unit.multiply(BigDecimal.valueOf(line.getQuantity())));
                                })
                        .toList();

        Long performedById =
                payment != null && payment.getConfirmedByStaffId() != null
                        ? payment.getConfirmedByStaffId()
                        : booking.getStaffId();
        PosBookingDetailResponse.StaffInfo performedBy =
                performedById == null
                        ? null
                        : users.findById(performedById)
                                .map(user -> new PosBookingDetailResponse.StaffInfo(user.getId(), user.getFullName()))
                                .orElse(new PosBookingDetailResponse.StaffInfo(performedById, null));

        PosBookingDetailResponse.PaymentInfo paymentInfo =
                payment == null
                        ? null
                        : new PosBookingDetailResponse.PaymentInfo(
                                payment.getId(),
                                payment.getPaymentMethod().name(),
                                payment.getStatus().name(),
                                payment.getAmount(),
                                payment.getCashReceived(),
                                payment.getPaidAt(),
                                payment.getConfirmedByStaffId());

        return new PosBookingDetailResponse(
                booking.getId(),
                booking.getBookingCode(),
                booking.getChannel().name(),
                booking.getStatus().name(),
                booking.getTicketTotal(),
                booking.getConcessionTotal(),
                booking.getTotalAmount(),
                booking.getCreatedAt(),
                booking.getPaidAt(),
                new PosBookingDetailResponse.ShowtimeInfo(
                        showtime.getId(),
                        movie.getId(),
                        movie.getTitle(),
                        movie.getAgeRating(),
                        showtime.getAuditoriumId(),
                        room == null ? null : room.getName(),
                        offset(showtime.getStartTime()),
                        offset(showtime.getEndTime())),
                seatLines,
                ticketLines,
                concessionLines,
                paymentInfo,
                performedBy);
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.atZone(ShowtimeTimeService.BUSINESS_ZONE).toOffsetDateTime();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static PaymentMethod parsePaymentMethod(String raw) {
        String value = blankToNull(raw);
        if (value == null) return null;
        try {
            PaymentMethod method = PaymentMethod.valueOf(value.toUpperCase(Locale.ROOT));
            if (method == PaymentMethod.CASH || method == PaymentMethod.VIETQR) return method;
        } catch (IllegalArgumentException ignored) {
            // Fall through to the validation error below.
        }
        throw new ApiException(
                400, "VALIDATION_ERROR", "paymentMethod phải là CASH hoặc VIETQR");
    }

    private static DateRange parseDate(String raw) {
        String value = blankToNull(raw);
        if (value == null) return null;
        try {
            LocalDate day = LocalDate.parse(value);
            Instant start = day.atStartOfDay(ShowtimeTimeService.BUSINESS_ZONE).toInstant();
            Instant end = day.plusDays(1).atStartOfDay(ShowtimeTimeService.BUSINESS_ZONE).toInstant();
            return new DateRange(start, end);
        } catch (DateTimeParseException e) {
            throw new ApiException(400, "VALIDATION_ERROR", "date phải theo định dạng yyyy-MM-dd");
        }
    }

    private record DateRange(Instant start, Instant endExclusive) {}

    private static void requireStaff(SessionPrincipal principal) {
        if (principal == null
                || principal.role() != UserRole.STAFF
                || principal.userId() == null) {
            throw new ApiException(403, "ACCESS_DENIED", "Chỉ STAFF được xem lịch sử bán tại quầy");
        }
    }

    private static ApiException notFound() {
        return new ApiException(404, "BOOKING_NOT_FOUND", "Không tìm thấy Booking tại quầy");
    }
}
