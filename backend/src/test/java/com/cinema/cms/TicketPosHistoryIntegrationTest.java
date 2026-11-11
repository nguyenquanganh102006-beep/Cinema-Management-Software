package com.cinema.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cinema.cms.dto.*;
import com.cinema.cms.entity.*;
import com.cinema.cms.service.*;
import com.cinema.cms.support.SeatHoldTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
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
class TicketPosHistoryIntegrationTest extends SeatHoldTestSupport {
    @Autowired BookingService bookingService;
    @Autowired PosPaymentService posPayments;
    @Autowired CheckoutService checkoutService;
    @Autowired TicketService ticketService;

    @Test
    void customerCanReadOnlyTheirOwnTicketAndBookingTickets() throws Exception {
        BookingResponse booking = book(show, List.of(a1), customer, null);
        checkoutService.finalizePaid(
                booking.bookingId(), PaymentMethod.GATEWAY_ONLINE, booking.totalAmount());
        String ticketCode = ticketCode(booking.bookingId());
        User other = customer("Other");
        String otherJwt = jwt.issue(other);

        mvc.perform(
                        get("/api/v1/tickets/{code}", ticketCode)
                                .header("Authorization", "Bearer " + customerJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ticketCode").value(ticketCode))
                .andExpect(jsonPath("$.data.status").value("VALID"))
                .andExpect(jsonPath("$.data.bookingId").value(booking.bookingId()))
                .andExpect(jsonPath("$.data.movieId").value(movie))
                .andExpect(jsonPath("$.data.seatId").value(a1));

        mvc.perform(
                        get("/api/v1/tickets/{code}", ticketCode)
                                .header("Authorization", "Bearer " + staffJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ticketCode").value(ticketCode));

        mvc.perform(
                        get("/api/v1/tickets/{code}", ticketCode)
                                .header("Authorization", "Bearer " + otherJwt))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));

        mvc.perform(
                        get("/api/v1/bookings/{id}/tickets", booking.bookingId())
                                .header("Authorization", "Bearer " + customerJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].ticketCode").value(ticketCode));

        mvc.perform(
                        get("/api/v1/bookings/{id}/tickets", booking.bookingId())
                                .header("Authorization", "Bearer " + staffJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));

        mvc.perform(
                        get("/api/v1/bookings/{id}/tickets", booking.bookingId())
                                .header("Authorization", "Bearer " + otherJwt))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"));

        mvc.perform(
                        get("/api/v1/me/bookings/{id}", booking.bookingId())
                                .header("Authorization", "Bearer " + customerJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bookingId").value(booking.bookingId()))
                .andExpect(jsonPath("$.data.tickets.length()").value(1))
                .andExpect(jsonPath("$.data.payment.method").value("GATEWAY_ONLINE"));

        mvc.perform(
                        get("/api/v1/me/bookings/{id}", booking.bookingId())
                                .header("Authorization", "Bearer " + otherJwt))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"));
    }

    @Test
    void meBookingsAreScopedToJwtUserAndPaginated() throws Exception {
        BookingResponse first = book(show, List.of(a1), customer, null);
        BookingResponse second = book(show, List.of(a2), customer, null);
        BookingResponse third = book(show, List.of(vip), customer, null);
        User other = customer("Other");
        String otherJwt = jwt.issue(other);
        BookingResponse foreign = book(secondShow, List.of(a1), principal(other, otherJwt), null);

        JsonNode page0 =
                data(
                        mvc.perform(
                                        get("/api/v1/me/bookings")
                                                .param("page", "0")
                                                .param("size", "2")
                                                .header(
                                                        "Authorization",
                                                        "Bearer " + customerJwt))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data.page").value(0))
                                .andExpect(jsonPath("$.data.size").value(2))
                                .andExpect(jsonPath("$.data.totalElements").value(3))
                                .andExpect(jsonPath("$.data.totalPages").value(2))
                                .andExpect(jsonPath("$.data.items.length()").value(2)));
        assertThat(
                        Arrays.asList(
                                page0.path("items").get(0).path("bookingId").asLong(),
                                page0.path("items").get(1).path("bookingId").asLong()))
                .containsExactly(third.bookingId(), second.bookingId());

        JsonNode page1 =
                data(
                        mvc.perform(
                                        get("/api/v1/me/bookings")
                                                .param("page", "1")
                                                .param("size", "2")
                                                .header(
                                                        "Authorization",
                                                        "Bearer " + customerJwt))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.data.items.length()").value(1)));
        assertThat(page1.path("items").get(0).path("bookingId").asLong())
                .isEqualTo(first.bookingId());

        assertThat(
                        data(
                                        mvc.perform(
                                                        get("/api/v1/me/bookings")
                                                                .header(
                                                                        "Authorization",
                                                                        "Bearer " + otherJwt))
                                                .andExpect(status().isOk()))
                                .path("items"))
                .hasSize(1);
        assertThat(
                        data(
                                        mvc.perform(
                                                        get("/api/v1/me/bookings")
                                                                .header(
                                                                        "Authorization",
                                                                        "Bearer " + otherJwt))
                                                .andExpect(status().isOk()))
                                .path("items")
                                .get(0)
                                .path("bookingId")
                                .asLong())
                .isEqualTo(foreign.bookingId());

        mvc.perform(
                        get("/api/v1/me/bookings/{id}", foreign.bookingId())
                                .header("Authorization", "Bearer " + customerJwt))
                .andExpect(status().isNotFound());

        mvc.perform(
                        get("/api/v1/me/bookings")
                                .header("Authorization", "Bearer " + staffJwt))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void posHistoryExcludesOnlineBookingsAndSupportsFilters() throws Exception {
        // The Spring context and database are shared by the whole suite. Move this test to its own
        // day and room so assertions are not affected by POS bookings created by other tests.
        clock.set(Instant.parse("2040-06-06T10:00:00Z"));
        // Reissue JWTs after moving the test clock: the fixture tokens were signed at 2030 and
        // would otherwise be rejected as expired.
        staffJwt = jwt.issue(users.findById(staff.userId()).orElseThrow());
        customerJwt = jwt.issue(users.findById(customer.userId()).orElseThrow());
        Integer posRoom = room(5);
        Long posA1 = seat(posRoom, "A", 1, "STANDARD");
        Long posA2 = seat(posRoom, "A", 2, "STANDARD");
        Long posMovie = movie("P");
        Long posShow = showtime(posMovie, posRoom, 3600);
        Long posSecondShow = showtime(posMovie, posRoom, 14400);
        Long posOnlineShow = showtime(posMovie, posRoom, 30000);
        Integer snack = item("Snack", "45000.00");
        stock(snack, 10);
        BookingResponse cashBooking =
                book(posShow, List.of(posA1), staff, Map.of(snack, 2));
        payCash(cashBooking.bookingId(), null);

        clock.advance(60);
        BookingResponse qrBooking = book(posSecondShow, List.of(posA2), staff, null);
        payVietQr(qrBooking.bookingId());

        BookingResponse onlineBooking = book(posOnlineShow, List.of(posA1), customer, null);
        checkoutService.finalizePaid(
                onlineBooking.bookingId(), PaymentMethod.GATEWAY_ONLINE, onlineBooking.totalAmount());

        JsonNode items =
                mapper.readTree(
                                mvc.perform(
                                                get("/api/v1/pos/bookings")
                                                        .param("date", "2040-06-06")
                                                        .header(
                                                                "Authorization",
                                                                "Bearer " + staffJwt))
                                        .andExpect(status().isOk())
                                        .andExpect(
                                                jsonPath("$.data.items[0].bookingId")
                                                        .value(qrBooking.bookingId()))
                                        .andExpect(
                                                jsonPath("$.data.items[0].paymentMethod")
                                                        .value("VIETQR"))
                                        .andExpect(
                                                jsonPath("$.data.items[0].paymentStatus")
                                                        .value("PAID"))
                                        .andExpect(
                                                jsonPath("$.data.items[0].ticketCount").value(1))
                                        .andExpect(
                                                jsonPath("$.data.items[0].refundStatus")
                                                        .value("NONE"))
                                        .andExpect(
                                                jsonPath("$.data.items[1].bookingId")
                                                        .value(cashBooking.bookingId()))
                                        .andExpect(
                                                jsonPath("$.data.items[1].paymentMethod")
                                                        .value("CASH"))
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .path("data")
                        .path("items");
        assertThat(items.findValuesAsText("bookingId"))
                .doesNotContain(String.valueOf(onlineBooking.bookingId()));

        mvc.perform(
                        get("/api/v1/pos/bookings")
                                .param("paymentMethod", "CASH")
                                .param("bookingCode", cashBooking.bookingCode())
                                .header("Authorization", "Bearer " + staffJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].bookingId").value(cashBooking.bookingId()));

        mvc.perform(
                        get("/api/v1/pos/bookings")
                                .param("paymentMethod", "VIETQR")
                                .param("bookingCode", qrBooking.bookingCode())
                                .header("Authorization", "Bearer " + staffJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].bookingId").value(qrBooking.bookingId()));

        mvc.perform(
                        get("/api/v1/pos/bookings")
                                .param("bookingCode", cashBooking.bookingCode())
                                .header("Authorization", "Bearer " + staffJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].bookingId").value(cashBooking.bookingId()));

        mvc.perform(
                        get("/api/v1/pos/bookings")
                                .param("date", "2040-06-06")
                                .header("Authorization", "Bearer " + staffJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2));
        mvc.perform(
                        get("/api/v1/pos/bookings")
                                .param("date", "2040-06-07")
                                .header("Authorization", "Bearer " + staffJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));

        mvc.perform(
                        get("/api/v1/pos/bookings")
                                .param("size", "101")
                                .header("Authorization", "Bearer " + staffJwt))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(
                        get("/api/v1/pos/bookings")
                                .param("paymentMethod", "GATEWAY_ONLINE")
                                .header("Authorization", "Bearer " + staffJwt))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(
                        get("/api/v1/pos/bookings/{id}", cashBooking.bookingId())
                                .header("Authorization", "Bearer " + staffJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.channel").value("POS"))
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.showtime.movieId").value(posMovie))
                .andExpect(jsonPath("$.data.seats.length()").value(1))
                .andExpect(jsonPath("$.data.seats[0].seatId").value(posA1))
                .andExpect(jsonPath("$.data.tickets.length()").value(1))
                .andExpect(jsonPath("$.data.concessions.length()").value(1))
                .andExpect(jsonPath("$.data.concessions[0].itemId").value(snack))
                .andExpect(jsonPath("$.data.concessions[0].itemName").isNotEmpty())
                .andExpect(jsonPath("$.data.concessions[0].quantity").value(2))
                .andExpect(jsonPath("$.data.concessions[0].lineTotal").value(90000.00))
                .andExpect(jsonPath("$.data.payment.method").value("CASH"))
                .andExpect(jsonPath("$.data.payment.status").value("PAID"))
                .andExpect(jsonPath("$.data.performedBy.staffId").value(staff.userId()))
                .andExpect(jsonPath("$.data.performedBy.fullName").value("Staff"));

        mvc.perform(
                        get("/api/v1/pos/bookings/{id}", onlineBooking.bookingId())
                                .header("Authorization", "Bearer " + staffJwt))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"));

        mvc.perform(
                        get("/api/v1/pos/bookings")
                                .header("Authorization", "Bearer " + customerJwt))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void gateRequiresAgeDocumentForRestrictedMovieAndRecordsGateAudit() throws Exception {
        Integer restrictedRoom = room(3);
        Long restrictedSeat = seat(restrictedRoom, "A", 1, "STANDARD");
        Long restrictedMovie = movie("T18");
        Long restrictedShow = showtime(restrictedMovie, restrictedRoom, 3600);
        BookingResponse booking = book(restrictedShow, List.of(restrictedSeat), staff, null);
        payCash(booking.bookingId(), true);
        String code = ticketCode(booking.bookingId());

        validate(code, null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AGE_VERIFICATION_REQUIRED"));
        validate(code, false)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AGE_VERIFICATION_REQUIRED"));

        assertThat(ticketStatus(booking.bookingId())).isEqualTo("VALID");
        assertThat(gateVerificationCount(code)).isZero();

        validate(code, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("USED"))
                .andExpect(jsonPath("$.data.ageRating").value("T18"))
                .andExpect(jsonPath("$.data.usedAt").isNotEmpty());

        assertThat(ticketStatus(booking.bookingId())).isEqualTo("USED");
        Map<String, Object> gate =
                jdbc.queryForMap(
                        "select a.verification_stage, a.verified_by_staff_id, a.verified_at,"
                                + " a.ticket_id, a.booking_id"
                                + " from age_verifications a join tickets t on t.ticket_id=a.ticket_id"
                                + " where t.ticket_code=?",
                        code);
        assertThat(gate.get("verification_stage")).isEqualTo("GATE");
        assertThat(((Number) gate.get("verified_by_staff_id")).longValue())
                .isEqualTo(staff.userId());
        assertThat(gate.get("verified_at")).isNotNull();
        assertThat(gate.get("booking_id")).isNull();

        validate(code, true)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_ALREADY_USED"));
    }

    @Test
    void gateRejectsCancelledTicketsAndTicketsOutsideTheShowtimeWindow() throws Exception {
        BookingResponse cancelledBooking = book(show, List.of(a1), staff, null);
        payCash(cancelledBooking.bookingId(), null);
        String cancelledCode = ticketCode(cancelledBooking.bookingId());
        jdbc.update(
                "update tickets set status='CANCELLED' where booking_id=?",
                cancelledBooking.bookingId());

        validate(cancelledCode, null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_VALID"));

        Long lateShow = showtime(movie, room, 30000);
        BookingResponse lateBooking = book(lateShow, List.of(a2), staff, null);
        payCash(lateBooking.bookingId(), null);

        validate(ticketCode(lateBooking.bookingId()), null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TICKET_OUTSIDE_SHOWTIME"));
        assertThat(ticketStatus(lateBooking.bookingId())).isEqualTo("VALID");
    }

    @Test
    void concurrentScansAdmitOnlyOne() throws Exception {
        BookingResponse booking = book(show, List.of(a1), staff, null);
        payCash(booking.bookingId(), null);
        String code = ticketCode(booking.bookingId());

        List<Object> results = validateInParallel(code, 2);

        assertThat(results.stream().filter(TicketValidationResponse.class::isInstance)).hasSize(1);
        ApiException failure =
                results.stream()
                        .filter(ApiException.class::isInstance)
                        .map(ApiException.class::cast)
                        .findFirst()
                        .orElseThrow();
        assertThat(failure.getCode()).isEqualTo("TICKET_ALREADY_USED");
        assertThat(ticketStatus(booking.bookingId())).isEqualTo("USED");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from tickets where booking_id=? and used_at is not null",
                                Integer.class,
                                booking.bookingId()))
                .isEqualTo(1);
    }

    private BookingResponse book(
            Long showtimeId,
            List<Long> seatIds,
            SessionPrincipal principal,
            Map<Integer, Integer> concessions) {
        HoldSeatsResponse held =
                holds.hold(showtimeId, new HoldSeatsRequest(seatIds), principal);
        List<CreateBookingRequest.ConcessionItem> lines =
                concessions == null
                        ? null
                        : concessions.entrySet().stream()
                                .map(
                                        entry ->
                                                new CreateBookingRequest.ConcessionItem(
                                                        entry.getKey(), entry.getValue()))
                                .toList();
        return bookingService.create(
                new CreateBookingRequest(showtimeId, held.holdToken(), lines, null), principal);
    }

    private void payCash(Long bookingId, Boolean ageChecked) {
        posPayments.payCash(
                bookingId,
                new CashPaymentRequest(new BigDecimal("1000000.00"), ageChecked),
                staff);
    }

    private void payVietQr(Long bookingId) {
        posPayments.createVietQr(bookingId, new VietQrPaymentRequest(null), staff);
        posPayments.confirmVietQr(bookingId, staff);
    }

    private ResultActions validate(String ticketCode, Boolean ageChecked) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticketCode", ticketCode);
        if (ageChecked != null) body.put("ageDocumentChecked", ageChecked);
        return mvc.perform(
                post("/api/v1/tickets/validate")
                        .header("Authorization", "Bearer " + staffJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)));
    }

    private JsonNode data(ResultActions actions) throws Exception {
        return mapper.readTree(actions.andReturn().getResponse().getContentAsString())
                .path("data");
    }

    private String ticketCode(Long bookingId) {
        return jdbc.queryForObject(
                "select ticket_code from tickets where booking_id=? order by ticket_id limit 1",
                String.class,
                bookingId);
    }

    private String ticketStatus(Long bookingId) {
        return jdbc.queryForObject(
                "select status::text from tickets where booking_id=? order by ticket_id limit 1",
                String.class,
                bookingId);
    }

    private int gateVerificationCount(String ticketCode) {
        return jdbc.queryForObject(
                "select count(*) from age_verifications a join tickets t on t.ticket_id=a.ticket_id"
                        + " where t.ticket_code=? and a.verification_stage='GATE'",
                Integer.class,
                ticketCode);
    }

    private User customer(String label) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return users.saveAndFlush(
                new User(
                        "cus_" + suffix,
                        label,
                        suffix + "@test.invalid",
                        "+86" + Math.abs(UUID.randomUUID().getMostSignificantBits() % 10000000000L),
                        "test-only-not-used",
                        UserRole.CUSTOMER));
    }

    private Long movie(String ageRating) {
        return jdbc.queryForObject(
                "insert into movies(title,duration_minutes,age_rating,release_date) values"
                        + " (?,120,?::age_rating_enum,'2030-01-01') returning movie_id",
                Long.class,
                "Movie " + UUID.randomUUID(),
                ageRating);
    }

    private Integer room(int seats) {
        return jdbc.queryForObject(
                "insert into auditoriums(name,total_seats) values (?,?) returning auditorium_id",
                Integer.class,
                "Room " + UUID.randomUUID(),
                seats);
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

    private Integer item(String name, String price) {
        return jdbc.queryForObject(
                "insert into concession_items(name,price,is_combo,is_active) values"
                        + " (?,?,false,true) returning item_id",
                Integer.class,
                name + " " + UUID.randomUUID(),
                new BigDecimal(price));
    }

    private void stock(Integer itemId, int quantity) {
        jdbc.update(
                "insert into concession_inventory(item_id,stock_quantity,low_stock_threshold)"
                        + " values (?,?,?)",
                itemId,
                quantity,
                5);
    }

    private List<Object> validateInParallel(String ticketCode, int count) throws Exception {
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
                                        throw new IllegalStateException("Scan start timed out");
                                    try {
                                        return ticketService.validate(
                                                new TicketValidationRequest(ticketCode, null),
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
}
