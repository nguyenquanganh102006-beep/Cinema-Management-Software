package com.cinema.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cinema.cms.dto.*;
import com.cinema.cms.entity.*;
import com.cinema.cms.repository.PaymentRepository;
import com.cinema.cms.service.*;
import com.cinema.cms.support.SeatHoldTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

@TestPropertySource(
        properties = {
            "app.payments.vietqr.bank-account=0123456789",
            "app.payments.vietqr.bank-name=Test Bank",
            "spring.datasource.hikari.maximum-pool-size=5",
            "spring.datasource.hikari.minimum-idle=1"
        })
class PosPaymentIntegrationTest extends SeatHoldTestSupport {
    @Autowired BookingService bookingService;
    @Autowired PosPaymentService posPayments;
    @Autowired PaymentRepository payments;

    @Test
    void cashPaymentUsesTotalAmountAndReturnsChange() throws Exception {
        BookingResponse booking = book(show, List.of(a1), staff);
        BigDecimal total = booking.totalAmount();

        JsonNode data =
                data(
                        cash(booking.bookingId(), staffJwt, "100000.00", null)
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data.paymentStatus").value("PAID"))
                                .andExpect(jsonPath("$.data.tickets.length()").value(1))
                                .andExpect(
                                        jsonPath("$.data.tickets[0].ticketCode")
                                                .value(matchesPattern("TK-.+"))));

        assertThat(data.path("totalAmount").decimalValue()).isEqualByComparingTo(total);
        assertThat(data.path("cashReceived").decimalValue()).isEqualByComparingTo("100000.00");
        assertThat(data.path("change").decimalValue())
                .isEqualByComparingTo(new BigDecimal("100000.00").subtract(total));
        assertThat(data.path("paymentId").asLong()).isPositive();

        Map<String, Object> row = paymentRow(booking.bookingId());
        assertThat(row.get("payment_method")).isEqualTo("CASH");
        assertThat(row.get("status")).isEqualTo("PAID");
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo(total);
        assertThat((BigDecimal) row.get("cash_received")).isEqualByComparingTo("100000.00");
        assertThat(((Number) row.get("confirmed_by_staff_id")).longValue())
                .isEqualTo(staff.userId());
        assertThat(row.get("paid_at")).isNotNull();

        assertThat(bookingStatus(booking.bookingId())).isEqualTo("PAID");
        assertThat(ticketCount(booking.bookingId())).isEqualTo(1);
        assertThat(seatStatus(show, a1)).isEqualTo("BOOKED");
    }

    @Test
    void cashPaymentRejectsShortAmountWithoutCreatingPayment() throws Exception {
        BookingResponse booking = book(show, List.of(a1), staff);

        cash(booking.bookingId(), staffJwt, "79999.99", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("PAYMENT_CASH_INSUFFICIENT"));

        assertThat(paymentCount(booking.bookingId())).isZero();
        assertThat(ticketCount(booking.bookingId())).isZero();
        assertThat(bookingStatus(booking.bookingId())).isEqualTo("PENDING");
        assertThat(seatStatus(show, a1)).isEqualTo("HOLDING");
    }

    @Test
    void cashPaymentValidationRejectsMissingCashReceived() throws Exception {
        BookingResponse booking = book(show, List.of(a1), staff);

        mvc.perform(
                        post("/api/v1/bookings/{bookingId}/payments/cash", booking.bookingId())
                                .header("Authorization", "Bearer " + staffJwt)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(paymentCount(booking.bookingId())).isZero();
        assertThat(ticketCount(booking.bookingId())).isZero();
    }

    @Test
    void cashPaymentRequiresAgeDocumentForRestrictedMovieAndRecordsIt() throws Exception {
        Long restrictedShow = showtime(movie("T16"), room, 30000);
        BookingResponse booking = book(restrictedShow, List.of(a1), staff);

        cash(booking.bookingId(), staffJwt, "100000.00", false)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AGE_VERIFICATION_REQUIRED"));

        assertThat(paymentCount(booking.bookingId())).isZero();
        assertThat(ticketCount(booking.bookingId())).isZero();
        assertThat(ageVerificationCount(booking.bookingId())).isZero();
        assertThat(bookingStatus(booking.bookingId())).isEqualTo("PENDING");

        cash(booking.bookingId(), staffJwt, "100000.00", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.data.tickets.length()").value(1));

        assertThat(ageVerificationCount(booking.bookingId())).isEqualTo(1);
        Map<String, Object> age = ageVerification(booking.bookingId());
        assertThat(age.get("verification_stage")).isEqualTo("POS");
        assertThat(((Number) age.get("verified_by_staff_id")).longValue())
                .isEqualTo(staff.userId());
        assertThat(age.get("verified_at")).isNotNull();
        assertThat(seatStatus(restrictedShow, a1)).isEqualTo("BOOKED");
    }

    @Test
    void vietQrCreateIsPendingAndConfirmIssuesTicketsOnce() throws Exception {
        BookingResponse booking = book(show, List.of(a1), staff);
        BigDecimal total = booking.totalAmount();

        JsonNode created =
                data(
                        vietQr(booking.bookingId(), staffJwt, null)
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data.paymentStatus").value("PENDING"))
                                .andExpect(jsonPath("$.data.bankAccount").value("0123456789"))
                                .andExpect(jsonPath("$.data.bankName").value("Test Bank"))
                                .andExpect(
                                        jsonPath("$.data.transferContent")
                                                .value("CINEMA " + booking.bookingCode()))
                                .andExpect(
                                        jsonPath("$.data.qrPayload")
                                                .value(
                                                        matchesPattern(
                                                                "vietqr://transfer\\?.*account=0123456789.*"))));

        assertThat(created.path("amount").decimalValue()).isEqualByComparingTo(total);
        assertThat(created.path("paymentId").asLong()).isPositive();
        assertThat(paymentCount(booking.bookingId())).isEqualTo(1);
        assertThat(ticketCount(booking.bookingId())).isZero();
        assertThat(bookingStatus(booking.bookingId())).isEqualTo("PENDING");
        assertThat(seatStatus(show, a1)).isEqualTo("HOLDING");

        Map<String, Object> pending = paymentRow(booking.bookingId());
        assertThat(pending.get("payment_method")).isEqualTo("VIETQR");
        assertThat(pending.get("status")).isEqualTo("PENDING");
        assertThat((BigDecimal) pending.get("amount")).isEqualByComparingTo(total);
        assertThat(pending.get("cash_received")).isNull();

        JsonNode confirmed =
                data(
                        confirmVietQr(booking.bookingId(), staffJwt)
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data.paymentStatus").value("PAID"))
                                .andExpect(jsonPath("$.data.tickets.length()").value(1)));

        assertThat(confirmed.path("paymentId").asLong())
                .isEqualTo(created.path("paymentId").asLong());
        assertThat(confirmed.path("confirmedByStaffId").asLong()).isEqualTo(staff.userId());
        assertThat(confirmed.path("paidAt").isNull()).isFalse();
        assertThat(bookingStatus(booking.bookingId())).isEqualTo("PAID");
        assertThat(ticketCount(booking.bookingId())).isEqualTo(1);
        assertThat(seatStatus(show, a1)).isEqualTo("BOOKED");

        Map<String, Object> paid = paymentRow(booking.bookingId());
        assertThat(paid.get("status")).isEqualTo("PAID");
        assertThat(((Number) paid.get("confirmed_by_staff_id")).longValue())
                .isEqualTo(staff.userId());
        assertThat(paid.get("paid_at")).isNotNull();

        confirmVietQr(booking.bookingId(), staffJwt)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOKING_ALREADY_PAID"));
        assertThat(paymentCount(booking.bookingId())).isEqualTo(1);
        assertThat(ticketCount(booking.bookingId())).isEqualTo(1);
    }

    @Test
    void vietQrCreateIsIdempotentForTheSamePendingPayment() throws Exception {
        BookingResponse booking = book(show, List.of(a1), staff);

        JsonNode first =
                data(vietQr(booking.bookingId(), staffJwt, null).andExpect(status().isOk()));
        JsonNode second =
                data(vietQr(booking.bookingId(), staffJwt, null).andExpect(status().isOk()));

        assertThat(second.path("paymentId").asLong()).isEqualTo(first.path("paymentId").asLong());
        assertThat(paymentCount(booking.bookingId())).isEqualTo(1);
        assertThat(bookingStatus(booking.bookingId())).isEqualTo("PENDING");
        assertThat(seatStatus(show, a1)).isEqualTo("HOLDING");
    }

    @Test
    void cashCannotUseABookingThatAlreadyHasAVietQrPayment() throws Exception {
        BookingResponse booking = book(show, List.of(a1), staff);
        payments.saveAndFlush(
                new Payment(
                        booking.bookingId(), PaymentMethod.VIETQR, booking.totalAmount()));

        cash(booking.bookingId(), staffJwt, "100000.00", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_METHOD_CONFLICT"));

        assertThat(paymentCount(booking.bookingId())).isEqualTo(1);
        assertThat(ticketCount(booking.bookingId())).isZero();
        assertThat(bookingStatus(booking.bookingId())).isEqualTo("PENDING");
        assertThat(seatStatus(show, a1)).isEqualTo("HOLDING");
    }

    @Test
    void vietQrCreateRequiresAgeDocumentForRestrictedMovie() throws Exception {
        Long restrictedShow = showtime(movie("T16"), room, 30000);
        BookingResponse booking = book(restrictedShow, List.of(a1), staff);

        vietQr(booking.bookingId(), staffJwt, false)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AGE_VERIFICATION_REQUIRED"));

        assertThat(paymentCount(booking.bookingId())).isZero();
        assertThat(ageVerificationCount(booking.bookingId())).isZero();
        assertThat(ticketCount(booking.bookingId())).isZero();
    }

    @Test
    void vietQrConfirmRequiresAnExistingPosAgeAudit() throws Exception {
        Long restrictedShow = showtime(movie("T16"), room, 30000);
        BookingResponse booking = book(restrictedShow, List.of(a1), staff);

        vietQr(booking.bookingId(), staffJwt, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentStatus").value("PENDING"));
        assertThat(ageVerificationCount(booking.bookingId())).isEqualTo(1);

        jdbc.update("delete from age_verifications where booking_id=?", booking.bookingId());

        confirmVietQr(booking.bookingId(), staffJwt)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AGE_VERIFICATION_REQUIRED"));

        assertThat(ageVerificationCount(booking.bookingId())).isZero();
        assertThat(ticketCount(booking.bookingId())).isZero();
        assertThat(bookingStatus(booking.bookingId())).isEqualTo("PENDING");
        assertThat(paymentRow(booking.bookingId()).get("status")).isEqualTo("PENDING");
    }

    @Test
    void vietQrConfirmWithoutAPaymentReturnsNotFound() throws Exception {
        BookingResponse booking = book(show, List.of(a1), staff);

        confirmVietQr(booking.bookingId(), staffJwt)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));

        assertThat(ticketCount(booking.bookingId())).isZero();
    }

    @Test
    void customerCannotCallPosPaymentEndpoints() throws Exception {
        BookingResponse booking = book(show, List.of(a1), staff);

        cash(booking.bookingId(), customerJwt, "100000.00", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        vietQr(booking.bookingId(), customerJwt, null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        confirmVietQr(booking.bookingId(), customerJwt)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        assertThat(paymentCount(booking.bookingId())).isZero();
        assertThat(ticketCount(booking.bookingId())).isZero();
        assertThat(seatStatus(show, a1)).isEqualTo("HOLDING");
    }

    @Test
    void anotherStaffCanCompleteAPosPaymentAndIsRecorded() throws Exception {
        BookingResponse booking = book(show, List.of(a1), staff);
        User second =
                users.saveAndFlush(
                        new User(
                                "staff2_" + UUID.randomUUID().toString().substring(0, 8),
                                "Second Staff",
                                UUID.randomUUID().toString().substring(0, 8)
                                        + "s2@test.invalid",
                                "+8490" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 100000000L),
                                "test-only-not-used",
                                UserRole.STAFF));
        String secondJwt = jwt.issue(second);

        cash(booking.bookingId(), secondJwt, "100000.00", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.data.tickets.length()").value(1));

        Map<String, Object> row = paymentRow(booking.bookingId());
        assertThat(((Number) row.get("confirmed_by_staff_id")).longValue())
                .isEqualTo(second.getId());
        assertThat(bookingStatus(booking.bookingId())).isEqualTo("PAID");
        assertThat(seatStatus(show, a1)).isEqualTo("BOOKED");
    }

    @Test
    void onlineBookingCannotBePaidAtTheCounter() throws Exception {
        BookingResponse booking = book(show, List.of(a1), customer);
        assertThat(channel(booking.bookingId())).isEqualTo("ONLINE");

        cash(booking.bookingId(), staffJwt, "100000.00", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BOOKING_CHANNEL_INVALID"));

        assertThat(paymentCount(booking.bookingId())).isZero();
        assertThat(ticketCount(booking.bookingId())).isZero();
        assertThat(bookingStatus(booking.bookingId())).isEqualTo("PENDING");
        assertThat(seatStatus(show, a1)).isEqualTo("HOLDING");
    }

    @Test
    void expiredHoldRollsBackThePendingPayment() throws Exception {
        BookingResponse booking = book(show, List.of(a1), staff);
        clock.advance(300);

        cash(booking.bookingId(), staffJwt, "100000.00", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_HOLD_EXPIRED"));

        assertThat(paymentCount(booking.bookingId())).isZero();
        assertThat(ticketCount(booking.bookingId())).isZero();
        assertThat(bookingStatus(booking.bookingId())).isEqualTo("PENDING");
        assertThat(seatStatus(show, a1)).isEqualTo("HOLDING");
    }

    @Test
    void concurrentCashRequestsSerializeOnTheBookingAndIssueOneTicket() throws Exception {
        BookingResponse booking = book(show, List.of(a1), staff);

        List<Object> results = payCashInParallel(booking.bookingId(), 2);

        assertThat(results.stream().filter(CashPaymentResponse.class::isInstance)).hasSize(1);
        ApiException failure = onlyFailure(results);
        assertThat(failure.getCode()).isEqualTo("BOOKING_ALREADY_PAID");

        assertThat(paymentCount(booking.bookingId())).isEqualTo(1);
        assertThat(ticketCount(booking.bookingId())).isEqualTo(1);
        assertThat(bookingStatus(booking.bookingId())).isEqualTo("PAID");
        assertThat(seatStatus(show, a1)).isEqualTo("BOOKED");
    }

    private BookingResponse book(
            Long showtimeId, List<Long> seatIds, SessionPrincipal principal) {
        HoldSeatsResponse held =
                holds.hold(showtimeId, new HoldSeatsRequest(seatIds), principal);
        return bookingService.create(
                new CreateBookingRequest(showtimeId, held.holdToken(), null, null), principal);
    }

    private ResultActions cash(
            Long bookingId, String jwt, String cashReceived, Boolean ageChecked)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cashReceived", new BigDecimal(cashReceived));
        if (ageChecked != null) body.put("ageDocumentChecked", ageChecked);
        return mvc.perform(
                post("/api/v1/bookings/{bookingId}/payments/cash", bookingId)
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)));
    }

    private ResultActions vietQr(Long bookingId, String jwt, Boolean ageChecked)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (ageChecked != null) body.put("ageDocumentChecked", ageChecked);
        return mvc.perform(
                post("/api/v1/bookings/{bookingId}/payments/vietqr", bookingId)
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)));
    }

    private ResultActions confirmVietQr(Long bookingId, String jwt) throws Exception {
        return mvc.perform(
                post("/api/v1/bookings/{bookingId}/payments/vietqr/confirm", bookingId)
                        .header("Authorization", "Bearer " + jwt));
    }

    private JsonNode data(ResultActions actions) throws Exception {
        return mapper.readTree(actions.andReturn().getResponse().getContentAsString())
                .path("data");
    }

    private Long movie(String ageRating) {
        return jdbc.queryForObject(
                "insert into movies(title,duration_minutes,age_rating,release_date) values"
                        + " (?,120,?::age_rating_enum,'2030-01-01') returning movie_id",
                Long.class,
                "Movie " + UUID.randomUUID(),
                ageRating);
    }

    private Long showtime(Long movieId, Integer roomId, int seconds) {
        Long id =
                jdbc.queryForObject(
                        "insert into showtimes(movie_id,auditorium_id,start_time,end_time,base_price)"
                                + " values (?,?,?,?,70000) returning showtime_id",
                        Long.class,
                        movieId,
                        roomId,
                        Timestamp.from(clock.instant().plusSeconds(seconds)),
                        Timestamp.from(clock.instant().plusSeconds(seconds + 8100)));
        jdbc.update(
                "insert into showtime_seats(showtime_id,seat_id) select ?,seat_id from seats"
                        + " where auditorium_id=?",
                id,
                roomId);
        return id;
    }

    private String channel(Long bookingId) {
        return jdbc.queryForObject(
                "select channel::text from bookings where booking_id=?",
                String.class,
                bookingId);
    }

    private String bookingStatus(Long bookingId) {
        return jdbc.queryForObject(
                "select status::text from bookings where booking_id=?",
                String.class,
                bookingId);
    }

    private String seatStatus(Long showtimeId, Long seatId) {
        return jdbc.queryForObject(
                "select status::text from showtime_seats where showtime_id=? and seat_id=?",
                String.class,
                showtimeId,
                seatId);
    }

    private int ticketCount(Long bookingId) {
        return jdbc.queryForObject(
                "select count(*) from tickets where booking_id=?",
                Integer.class,
                bookingId);
    }

    private int paymentCount(Long bookingId) {
        return jdbc.queryForObject(
                "select count(*) from payments where booking_id=?",
                Integer.class,
                bookingId);
    }

    private Map<String, Object> paymentRow(Long bookingId) {
        return jdbc.queryForMap(
                "select payment_method::text as payment_method, status::text as status,"
                        + " amount, cash_received, confirmed_by_staff_id, paid_at"
                        + " from payments where booking_id=?",
                bookingId);
    }

    private int ageVerificationCount(Long bookingId) {
        return jdbc.queryForObject(
                "select count(*) from age_verifications where booking_id=?",
                Integer.class,
                bookingId);
    }

    private Map<String, Object> ageVerification(Long bookingId) {
        return jdbc.queryForMap(
                "select verification_stage, verified_by_staff_id, verified_at"
                        + " from age_verifications where booking_id=?"
                        + " order by verification_id desc limit 1",
                bookingId);
    }

    private List<Object> payCashInParallel(Long bookingId, int count) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    if (!start.await(10, TimeUnit.SECONDS))
                                        throw new IllegalStateException(
                                                "Parallel payment start timed out");
                                    try {
                                        return posPayments.payCash(
                                                bookingId,
                                                new CashPaymentRequest(
                                                        new BigDecimal("100000.00"), false),
                                                staff);
                                    } catch (ApiException e) {
                                        return e;
                                    }
                                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static ApiException onlyFailure(List<Object> results) {
        List<ApiException> failures =
                results.stream()
                        .filter(ApiException.class::isInstance)
                        .map(ApiException.class::cast)
                        .toList();
        assertThat(failures).hasSize(1);
        return failures.getFirst();
    }
}
