package com.cinema.cms.service;

import com.cinema.cms.dto.MeBookingDetailResponse;
import com.cinema.cms.dto.MeBookingSummaryResponse;
import com.cinema.cms.dto.PageResponse;
import com.cinema.cms.entity.*;
import com.cinema.cms.repository.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only booking history for the authenticated CUSTOMER. */
@Service
public class CustomerBookingQueryService {
    static final int MAX_PAGE_SIZE = 100;

    private final BookingRepository bookings;
    private final BookingConcessionRepository bookingConcessions;
    private final ConcessionItemRepository concessionItems;
    private final TicketRepository tickets;
    private final PaymentRepository payments;
    private final ShowtimeRepository showtimes;
    private final ShowtimeSeatRepository showtimeSeats;
    private final MovieRepository movies;
    private final SeatRepository seats;
    private final AuditoriumRepository auditoriums;

    public CustomerBookingQueryService(
            BookingRepository bookings,
            BookingConcessionRepository bookingConcessions,
            ConcessionItemRepository concessionItems,
            TicketRepository tickets,
            PaymentRepository payments,
            ShowtimeRepository showtimes,
            ShowtimeSeatRepository showtimeSeats,
            MovieRepository movies,
            SeatRepository seats,
            AuditoriumRepository auditoriums) {
        this.bookings = bookings;
        this.bookingConcessions = bookingConcessions;
        this.concessionItems = concessionItems;
        this.tickets = tickets;
        this.payments = payments;
        this.showtimes = showtimes;
        this.showtimeSeats = showtimeSeats;
        this.movies = movies;
        this.seats = seats;
        this.auditoriums = auditoriums;
    }

    @Transactional(readOnly = true)
    public PageResponse<MeBookingSummaryResponse> list(
            int page, int size, SessionPrincipal principal) {
        Long userId = requireCustomer(principal);
        QueryParams.checkPaging(page, size, MAX_PAGE_SIZE);
        Specification<Booking> mine = (root, query, cb) -> cb.equal(root.get("userId"), userId);
        Page<Booking> found =
                bookings.findAll(
                        mine,
                        PageRequest.of(
                                page,
                                size,
                                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        return PageResponse.from(
                found,
                booking ->
                        new MeBookingSummaryResponse(
                                booking.getId(),
                                booking.getBookingCode(),
                                booking.getStatus().name(),
                                booking.getChannel().name(),
                                booking.getTotalAmount(),
                                booking.getCreatedAt(),
                                booking.getPaidAt(),
                                booking.getShowtimeId()));
    }

    @Transactional(readOnly = true)
    public MeBookingDetailResponse detail(Long bookingId, SessionPrincipal principal) {
        Long userId = requireCustomer(principal);
        if (bookingId == null || bookingId <= 0) throw notFound();
        Booking booking = bookings.findById(bookingId).orElseThrow(CustomerBookingQueryService::notFound);
        if (!Objects.equals(booking.getUserId(), userId)) throw notFound();

        Showtime showtime = showtimes.findById(booking.getShowtimeId()).orElseThrow(CustomerBookingQueryService::notFound);
        Movie movie = movies.findById(showtime.getMovieId()).orElseThrow(CustomerBookingQueryService::notFound);
        Auditorium room = auditoriums.findById(showtime.getAuditoriumId()).orElse(null);

        List<Ticket> bookingTickets = tickets.findByBookingIdOrderByIdAsc(bookingId);
        Map<Long, ShowtimeSeat> showtimeSeatById =
                showtimeSeats.findAllById(
                                bookingTickets.stream().map(Ticket::getShowtimeSeatId).toList())
                        .stream()
                        .collect(Collectors.toMap(ShowtimeSeat::getId, Function.identity()));
        Map<Long, Seat> seatById =
                seats.findAllById(
                                showtimeSeatById.values().stream().map(ShowtimeSeat::getSeatId).toList())
                        .stream()
                        .collect(Collectors.toMap(Seat::getId, Function.identity()));

        List<MeBookingDetailResponse.TicketLine> ticketLines =
                bookingTickets.stream()
                        .map(
                                ticket -> {
                                    ShowtimeSeat showtimeSeat = showtimeSeatById.get(ticket.getShowtimeSeatId());
                                    Seat seat = showtimeSeat == null ? null : seatById.get(showtimeSeat.getSeatId());
                                    return new MeBookingDetailResponse.TicketLine(
                                            ticket.getId(),
                                            ticket.getTicketCode(),
                                            seat == null ? null : seat.getId(),
                                            seat == null ? null : seat.getSeatRow(),
                                            seat == null ? null : seat.getSeatNumber(),
                                            seat == null ? null : seat.getSeatType().name(),
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
                                concessions.stream()
                                        .map(line -> line.getId().getItemId())
                                        .toList())
                        .stream()
                        .collect(Collectors.toMap(ConcessionItem::getId, ConcessionItem::getName));
        List<MeBookingDetailResponse.ConcessionLine> concessionLines =
                concessions.stream()
                        .map(
                                line -> {
                                    Integer itemId = line.getId().getItemId();
                                    BigDecimal unit = line.getUnitPriceSnapshot();
                                    return new MeBookingDetailResponse.ConcessionLine(
                                            itemId,
                                            itemNames.get(itemId),
                                            line.getQuantity(),
                                            unit,
                                            unit.multiply(BigDecimal.valueOf(line.getQuantity())));
                                })
                        .toList();

        MeBookingDetailResponse.PaymentInfo paymentInfo =
                payments
                        .findByBookingId(bookingId)
                        .map(
                                payment ->
                                        new MeBookingDetailResponse.PaymentInfo(
                                                payment.getPaymentMethod().name(),
                                                payment.getStatus().name(),
                                                payment.getAmount(),
                                                payment.getPaidAt()))
                        .orElse(null);

        return new MeBookingDetailResponse(
                booking.getId(),
                booking.getBookingCode(),
                booking.getStatus().name(),
                booking.getChannel().name(),
                booking.getTotalAmount(),
                booking.getTicketTotal(),
                booking.getConcessionTotal(),
                booking.getCreatedAt(),
                booking.getPaidAt(),
                new MeBookingDetailResponse.ShowtimeInfo(
                        showtime.getId(),
                        movie.getId(),
                        movie.getTitle(),
                        movie.getAgeRating(),
                        showtime.getAuditoriumId(),
                        room == null ? null : room.getName(),
                        offset(showtime.getStartTime()),
                        offset(showtime.getEndTime())),
                ticketLines,
                concessionLines,
                paymentInfo);
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.atZone(ShowtimeTimeService.BUSINESS_ZONE).toOffsetDateTime();
    }

    private static Long requireCustomer(SessionPrincipal principal) {
        if (principal == null
                || principal.role() != UserRole.CUSTOMER
                || principal.userId() == null) {
            throw new ApiException(403, "ACCESS_DENIED", "Chỉ CUSTOMER được xem lịch sử của mình");
        }
        return principal.userId();
    }

    private static ApiException notFound() {
        return new ApiException(404, "BOOKING_NOT_FOUND", "Không tìm thấy Booking");
    }
}
