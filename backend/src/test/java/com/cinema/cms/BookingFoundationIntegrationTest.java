package com.cinema.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.cinema.cms.entity.*;
import com.cinema.cms.repository.*;
import com.cinema.cms.service.*;
import com.cinema.cms.support.SeatHoldTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loading this Spring context already proves Flyway V6 applied and Hibernate ddl-auto=validate
 * accepted every new mapping. The tests below cover the persistence and constraints on top.
 */
class BookingFoundationIntegrationTest extends SeatHoldTestSupport {
    @Autowired BookingRepository bookingRepository;
    @Autowired BookingSeatRepository bookingSeatRepository;
    @Autowired TicketRepository ticketRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired AgeVerificationRepository ageVerificationRepository;
    @Autowired BookingCodeGenerator bookingCodes;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void migrationV6IsApplied() {
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from flyway_schema_history where version = '6' and success",
                                Integer.class))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from information_schema.tables"
                                        + " where table_schema = 'public'"
                                        + " and table_name in ('booking_seats', 'booking_code_counters')",
                                Integer.class))
                .isEqualTo(2);
    }

    @Test
    void generatesBookingCodesFromTheDailyCounterInBusinessZone() {
        String[] generated =
                new TransactionTemplate(transactionManager)
                        .execute(status -> new String[] {bookingCodes.next(), bookingCodes.next()});

        Pattern pattern = Pattern.compile("BK-(\\d{6})-(\\d+)");
        Matcher first = pattern.matcher(generated[0]);
        Matcher second = pattern.matcher(generated[1]);
        assertThat(first.matches()).isTrue();
        assertThat(second.matches()).isTrue();

        String expectedDate =
                LocalDate.ofInstant(clock.instant(), ShowtimeTimeService.BUSINESS_ZONE)
                        .format(DateTimeFormatter.ofPattern("yyMMdd"));
        assertThat(first.group(1)).isEqualTo(expectedDate);
        assertThat(second.group(1)).isEqualTo(expectedDate);
        assertThat(Long.parseLong(second.group(2))).isEqualTo(Long.parseLong(first.group(2)) + 1);
        assertThat(generated[0].length()).isLessThanOrEqualTo(30);
    }

    @Test
    void persistsBookingPlannedSeatsTicketPaymentAndAgeVerification() {
        String code = new TransactionTemplate(transactionManager).execute(status -> bookingCodes.next());
        Booking booking = bookingRepository.saveAndFlush(booking(code));
        bookingSeatRepository.saveAndFlush(new BookingSeat(booking.getId(), a1, new BigDecimal("70000.00")));
        bookingSeatRepository.saveAndFlush(new BookingSeat(booking.getId(), sweet1, new BigDecimal("85000.00")));

        Ticket ticket =
                ticketRepository.saveAndFlush(
                        new Ticket(
                                "TK-" + UUID.randomUUID(),
                                booking.getId(),
                                showtimeSeatId(a1),
                                new BigDecimal("70000.00"),
                                clock));
        Payment payment =
                paymentRepository.saveAndFlush(
                        new Payment(booking.getId(), PaymentMethod.CASH, new BigDecimal("70000.00")));
        AgeVerification verification =
                ageVerificationRepository.saveAndFlush(
                        AgeVerification.pos(booking.getId(), staff.userId(), clock));

        assertThat(bookingRepository.findById(booking.getId()))
                .get()
                .extracting(Booking::getBookingCode, Booking::getShowtimeId, Booking::getStatus)
                .containsExactly(code, show, BookingStatus.PENDING);
        assertThat(bookingSeatRepository.findByBookingId(booking.getId()))
                .extracting(seat -> seat.getId().getSeatId())
                .containsExactly(a1, sweet1);
        assertThat(ticketRepository.findByBookingIdOrderByIdAsc(booking.getId()))
                .extracting(Ticket::getTicketCode, Ticket::getStatus, Ticket::getPriceSnapshot)
                .containsExactly(
                        tuple(
                                ticket.getTicketCode(),
                                TicketStatus.VALID,
                                new BigDecimal("70000.00")));
        assertThat(paymentRepository.findByBookingId(booking.getId()))
                .get()
                .extracting(Payment::getPaymentMethod, Payment::getAmount, Payment::getStatus)
                .containsExactly(PaymentMethod.CASH, new BigDecimal("70000.00"), PaymentStatus.PENDING);
        assertThat(ageVerificationRepository.findByBookingIdOrderByIdAsc(booking.getId()))
                .extracting(AgeVerification::getVerificationStage, AgeVerification::getVerifiedByStaffId)
                .containsExactly(tuple(AgeVerification.POS, staff.userId()));
    }

    @Test
    void uniqueActiveTicketIndexBlocksSecondValidTicketForSameShowtimeSeat() {
        String code = new TransactionTemplate(transactionManager).execute(status -> bookingCodes.next());
        Booking booking = bookingRepository.saveAndFlush(booking(code));
        Long showtimeSeatId = showtimeSeatId(a1);
        ticketRepository.saveAndFlush(
                new Ticket(
                        "TK-" + UUID.randomUUID(),
                        booking.getId(),
                        showtimeSeatId,
                        new BigDecimal("70000.00"),
                        clock));

        assertThatThrownBy(
                        () ->
                                ticketRepository.saveAndFlush(
                                        new Ticket(
                                                "TK-" + UUID.randomUUID(),
                                                booking.getId(),
                                                showtimeSeatId,
                                                new BigDecimal("70000.00"),
                                                clock)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Booking booking(String code) {
        return new Booking(
                code,
                show,
                null,
                staff.userId(),
                BookingChannel.POS,
                new BigDecimal("70000.00"),
                new BigDecimal("70000.00"),
                new BigDecimal("0.00"),
                UUID.randomUUID().toString(),
                ownership.owner(staff),
                null,
                clock);
    }

    private Long showtimeSeatId(Long seatId) {
        return jdbc.queryForObject(
                "select showtime_seat_id from showtime_seats where showtime_id = ? and seat_id = ?",
                Long.class,
                show,
                seatId);
    }
}
