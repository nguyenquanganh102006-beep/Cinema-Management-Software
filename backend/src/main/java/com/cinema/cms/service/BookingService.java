package com.cinema.cms.service;

import com.cinema.cms.dto.*;
import com.cinema.cms.entity.*;
import com.cinema.cms.repository.*;
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Creates the PENDING Booking planned from an existing seat hold. Payment is Sprint E. */
@Service
public class BookingService {
    private final BookingRepository bookings;
    private final BookingSeatRepository bookingSeats;
    private final BookingConcessionRepository bookingConcessions;
    private final ConcessionItemRepository concessionItems;
    private final ShowtimeRepository showtimes;
    private final MovieRepository movies;
    private final ShowtimeSeatRepository inventory;
    private final CheckoutSeatService checkout;
    private final HoldOwnershipService ownership;
    private final PricingService pricing;
    private final BookingCodeGenerator bookingCodes;
    private final Clock clock;

    public BookingService(
            BookingRepository bookings,
            BookingSeatRepository bookingSeats,
            BookingConcessionRepository bookingConcessions,
            ConcessionItemRepository concessionItems,
            ShowtimeRepository showtimes,
            MovieRepository movies,
            ShowtimeSeatRepository inventory,
            CheckoutSeatService checkout,
            HoldOwnershipService ownership,
            PricingService pricing,
            BookingCodeGenerator bookingCodes,
            Clock clock) {
        this.bookings = bookings;
        this.bookingSeats = bookingSeats;
        this.bookingConcessions = bookingConcessions;
        this.concessionItems = concessionItems;
        this.showtimes = showtimes;
        this.movies = movies;
        this.inventory = inventory;
        this.checkout = checkout;
        this.ownership = ownership;
        this.pricing = pricing;
        this.bookingCodes = bookingCodes;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BookingResponse create(CreateBookingRequest request, SessionPrincipal principal) {
        String owner = ownership.owner(principal);
        SeatHoldService.validateShowtimeId(request.showtimeId());
        SeatHoldService.validateToken(request.holdToken());

        if (bookings.existsByHoldToken(request.holdToken())) throw alreadyExists();

        // Scalar projection only: loading ShowtimeSeat Entities here would leave stale rows in
        // the persistence context before lockAndValidateHold takes the inventory locks.
        List<ShowtimeSeatRepository.HoldMember> held =
                inventory.holdMembers(request.holdToken());
        if (held.isEmpty()) throw SeatHoldService.expired();
        List<Long> expectedSeatIds =
                held.stream().map(ShowtimeSeatRepository.HoldMember::getSeatId).sorted().toList();

        List<ShowtimeSeat> group =
                checkout.lockAndValidateHold(
                        request.holdToken(), owner, request.showtimeId(), expectedSeatIds);

        // A concurrent retry for the same token waits on the seat locks above, then sees the
        // committed Booking in this fresh READ_COMMITTED statement.
        if (bookings.existsByHoldToken(request.holdToken())) throw alreadyExists();

        Showtime showtime =
                showtimes
                        .findById(request.showtimeId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                404, "SHOWTIME_NOT_FOUND", "Không tìm thấy suất chiếu"));
        Movie movie =
                movies
                        .findById(showtime.getMovieId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                404, "MOVIE_NOT_FOUND", "Không tìm thấy phim"));

        Instant ageConfirmedAt = null;
        if (principal.role() == UserRole.CUSTOMER && movie.getAgeRating() != AgeRating.P) {
            if (!Boolean.TRUE.equals(request.ageConfirmed()))
                throw new ApiException(
                        422,
                        "AGE_CONFIRMATION_REQUIRED",
                        "Khách hàng phải xác nhận đủ tuổi trước khi đặt vé");
            ageConfirmedAt = now();
        }

        PricingQuoteResponse quote = pricing.quote(request.showtimeId(), expectedSeatIds);
        BigDecimal ticketTotal = quote.ticketTotal();

        List<CreateBookingRequest.ConcessionItem> requested =
                request.concessions() == null ? List.of() : request.concessions();
        validateUniqueItems(requested);
        Map<Integer, ConcessionItem> items = loadItems(requested);
        BigDecimal concessionTotal = zero();
        List<BookingResponse.ConcessionLine> concessionLines = new ArrayList<>();
        for (CreateBookingRequest.ConcessionItem line : requested) {
            ConcessionItem item = items.get(line.itemId());
            if (item == null)
                throw new ApiException(
                        404, "CONCESSION_ITEM_NOT_FOUND", "Không tìm thấy sản phẩm F&B");
            if (!item.isActive())
                throw new ApiException(
                        422, "CONCESSION_ITEM_INACTIVE", "Sản phẩm F&B đã ngừng bán");
            BigDecimal unitPrice = money(item.getPrice());
            concessionTotal =
                    money(
                            concessionTotal.add(
                                    unitPrice.multiply(BigDecimal.valueOf(line.quantity()))));
            concessionLines.add(
                    new BookingResponse.ConcessionLine(
                            item.getId(), line.quantity(), unitPrice));
        }
        BigDecimal totalAmount = money(ticketTotal.add(concessionTotal));

        BookingChannel channel =
                principal.role() == UserRole.STAFF ? BookingChannel.POS : BookingChannel.ONLINE;
        Long userId = principal.role() == UserRole.CUSTOMER ? principal.userId() : null;
        Long staffId = principal.role() == UserRole.STAFF ? principal.userId() : null;

        // The counter row stays locked until commit, so generate the code only after every
        // validation and pricing step has succeeded.
        String bookingCode = bookingCodes.next();
        Booking booking =
                new Booking(
                        bookingCode,
                        request.showtimeId(),
                        userId,
                        staffId,
                        channel,
                        totalAmount,
                        ticketTotal,
                        concessionTotal,
                        request.holdToken(),
                        owner,
                        ageConfirmedAt,
                        clock);
        bookings.saveAndFlush(booking);

        List<BookingResponse.TicketLine> ticketLines = new ArrayList<>(quote.seats().size());
        for (TicketPrice price : quote.seats()) {
            bookingSeats.save(
                    new BookingSeat(booking.getId(), price.seatId(), price.finalPrice()));
            ticketLines.add(
                    new BookingResponse.TicketLine(price.seatId(), price.finalPrice()));
        }
        for (CreateBookingRequest.ConcessionItem line : requested) {
            ConcessionItem item = items.get(line.itemId());
            bookingConcessions.save(
                    new BookingConcession(
                            booking.getId(),
                            item.getId(),
                            line.quantity(),
                            money(item.getPrice())));
        }

        return new BookingResponse(
                booking.getId(),
                booking.getBookingCode(),
                booking.getStatus().name(),
                ticketLines,
                concessionLines,
                booking.getTotalAmount(),
                group.getFirst().getHoldUntil());
    }

    @Transactional(readOnly = true)
    public BookingResponse get(Long bookingId, SessionPrincipal principal) {
        if (bookingId == null || bookingId <= 0) throw notFound();
        Booking booking = bookings.findById(bookingId).orElseThrow(BookingService::notFound);
        if (!ownedBy(booking, principal)) throw notFound();

        Instant holdUntil = null;
        if (booking.getHoldToken() != null) {
            List<ShowtimeSeat> live = inventory.findByHoldTokenOrderBySeatId(booking.getHoldToken());
            if (!live.isEmpty()) holdUntil = live.getFirst().getHoldUntil();
        }

        List<BookingResponse.TicketLine> tickets =
                bookingSeats.findByBookingId(bookingId).stream()
                        .map(
                                seat ->
                                        new BookingResponse.TicketLine(
                                                seat.getId().getSeatId(), seat.getQuotedPrice()))
                        .toList();
        List<BookingResponse.ConcessionLine> concessions =
                bookingConcessions.findByBookingId(bookingId).stream()
                        .map(
                                line ->
                                        new BookingResponse.ConcessionLine(
                                                line.getId().getItemId(),
                                                line.getQuantity(),
                                                line.getUnitPriceSnapshot()))
                        .toList();
        return new BookingResponse(
                booking.getId(),
                booking.getBookingCode(),
                booking.getStatus().name(),
                tickets,
                concessions,
                booking.getTotalAmount(),
                holdUntil);
    }

    private static void validateUniqueItems(List<CreateBookingRequest.ConcessionItem> lines) {
        Set<Integer> seen = new HashSet<>();
        for (CreateBookingRequest.ConcessionItem line : lines) {
            if (line == null)
                throw new ApiException(
                        400, "VALIDATION_ERROR", "Sản phẩm F&B không hợp lệ");
            if (!seen.add(line.itemId()))
                throw new ApiException(
                        400, "VALIDATION_ERROR", "Không được gửi trùng sản phẩm F&B");
        }
    }

    private Map<Integer, ConcessionItem> loadItems(
            List<CreateBookingRequest.ConcessionItem> lines) {
        if (lines.isEmpty()) return Map.of();
        List<Integer> ids = lines.stream().map(CreateBookingRequest.ConcessionItem::itemId).toList();
        Map<Integer, ConcessionItem> items = new HashMap<>();
        concessionItems.findAllById(ids).forEach(item -> items.put(item.getId(), item));
        return items;
    }

    private static boolean ownedBy(Booking booking, SessionPrincipal principal) {
        if (principal == null) return false;
        return switch (principal.role()) {
            case CUSTOMER -> Objects.equals(booking.getUserId(), principal.userId());
            case STAFF -> Objects.equals(booking.getStaffId(), principal.userId());
            default -> false;
        };
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    private static ApiException alreadyExists() {
        return new ApiException(
                409, "BOOKING_ALREADY_EXISTS", "Phiên giữ ghế này đã có Booking");
    }

    private static ApiException notFound() {
        return new ApiException(404, "BOOKING_NOT_FOUND", "Không tìm thấy Booking");
    }
}
