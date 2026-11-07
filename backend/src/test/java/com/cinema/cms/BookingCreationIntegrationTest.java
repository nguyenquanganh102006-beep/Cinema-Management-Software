package com.cinema.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cinema.cms.dto.HoldSeatsRequest;
import com.cinema.cms.dto.PricingQuoteResponse;
import com.cinema.cms.service.PricingService;
import com.cinema.cms.service.ShowtimeTimeService;
import com.cinema.cms.support.SeatHoldTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class BookingCreationIntegrationTest extends SeatHoldTestSupport {
    @Autowired PricingService pricing;

    @Test
    void pricesTimeSurchargeExactlyOnceForFridayWeekendOrAfter18h() {
        LocalDate monday =
                LocalDate.of(2030, 1, 1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        LocalDate friday = monday.plusDays(4);

        Long monday15 = showtime(room, movie, at(monday, 15));
        Long monday19 = showtime(room, movie, at(monday, 19));
        Long friday15 = showtime(room, movie, at(friday, 15));
        Long friday19 = showtime(room, movie, at(friday, 19));

        assertQuote(monday15, a1, "70000.00", "0.00", "0.00", "0.00", "70000.00");
        assertQuote(monday19, a1, "70000.00", "0.00", "0.00", "10000.00", "80000.00");
        assertQuote(friday15, a1, "70000.00", "0.00", "0.00", "10000.00", "80000.00");
        assertQuote(friday19, a1, "70000.00", "0.00", "0.00", "10000.00", "80000.00");
    }

    @Test
    void pricesRoomAndSeatSurchargesPerPhysicalSeat() {
        LocalDate monday =
                LocalDate.of(2030, 1, 1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        LocalDate friday = monday.plusDays(4);

        Integer room3d = auditorium("3D");
        Long vip3d = seat(room3d, "A", 1, "VIP");
        Long show3d = showtime(room3d, movie, at(monday, 15));

        Integer roomImax = auditorium("IMAX");
        Long sweetImax = seat(roomImax, "A", 1, "SWEETBOX");
        Long showImax = showtime(roomImax, movie, at(friday, 19));

        assertQuote(show3d, vip3d, "70000.00", "20000.00", "15000.00", "0.00", "105000.00");
        assertQuote(
                showImax, sweetImax, "70000.00", "40000.00", "15000.00", "10000.00", "135000.00");
    }

    @Test
    void quoteEndpointReturnsBreakdownAndRejectsSeatsOutsideTheRoom() throws Exception {
        JsonNode data =
                mapper.readTree(
                                mvc.perform(
                                                post("/api/v1/pricing/quote")
                                                        .header(
                                                                "Authorization",
                                                                "Bearer " + customerJwt)
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content(
                                                                mapper.writeValueAsString(
                                                                        Map.of(
                                                                                "showtimeId",
                                                                                show,
                                                                                "seatIds",
                                                                                List.of(a1, vip)))))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.data.seats.length()").value(2))
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .path("data");

        assertThat(data.path("ticketTotal").decimalValue()).isEqualByComparingTo("175000.00");
        assertThat(data.path("seats").get(0).path("timeSurcharge").decimalValue())
                .isEqualByComparingTo("10000.00");
        assertThat(data.path("seats").get(1).path("seatSurcharge").decimalValue())
                .isEqualByComparingTo("15000.00");

        Integer otherRoom = auditorium("STANDARD_2D");
        Long foreignSeat = seat(otherRoom, "Z", 1, "STANDARD");
        mvc.perform(
                        post("/api/v1/pricing/quote")
                                .header("Authorization", "Bearer " + customerJwt)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of(
                                                        "showtimeId",
                                                        show,
                                                        "seatIds",
                                                        List.of(foreignSeat)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SEAT"));
    }

    @Test
    void sweetboxBookingCreatesTwoIndependentTicketsAndQuotedPrices() throws Exception {
        var held = holds.hold(show, new HoldSeatsRequest(List.of(sweet1)), staff);
        assertThat(held.seatIds()).containsExactlyInAnyOrder(sweet1, sweet2);

        JsonNode data =
                mapper.readTree(
                                postBooking(
                                                staffJwt,
                                                bookingBody(
                                                        show, held.holdToken(), null, null))
                                        .andExpect(status().isCreated())
                                        .andExpect(jsonPath("$.data.status").value("PENDING"))
                                        .andExpect(
                                                jsonPath("$.data.bookingCode")
                                                        .value(matchesPattern("BK-\\d{6}-\\d{4,}")))
                                        .andExpect(jsonPath("$.data.holdUntil").exists())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .path("data");

        assertThat(data.path("tickets")).hasSize(2);
        assertThat(data.path("totalAmount").decimalValue()).isEqualByComparingTo("190000.00");
        assertThat(data.path("tickets").get(0).path("price").decimalValue())
                .isEqualByComparingTo("95000.00");
        assertThat(data.path("tickets").get(1).path("price").decimalValue())
                .isEqualByComparingTo("95000.00");

        Long bookingId = data.path("bookingId").asLong();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from booking_seats where booking_id=?",
                                Integer.class,
                                bookingId))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "select status::text from bookings where booking_id=?",
                                String.class,
                                bookingId))
                .isEqualTo("PENDING");
        assertThat(
                        jdbc.queryForObject(
                                "select channel::text from bookings where booking_id=?",
                                String.class,
                                bookingId))
                .isEqualTo("POS");
        assertThat(
                        jdbc.queryForObject(
                                "select hold_owner from bookings where booking_id=?",
                                String.class,
                                bookingId))
                .isEqualTo(ownership.owner(staff));
    }

    @Test
    void createsBookingWithConcessionSnapshotWithoutDeductingStock() throws Exception {
        var held = holds.hold(show, new HoldSeatsRequest(List.of(a1, vip)), staff);
        Integer snack = concession("Snack " + UUID.randomUUID(), "45000.00", true);

        JsonNode data =
                mapper.readTree(
                                postBooking(
                                                staffJwt,
                                                bookingBody(
                                                        show,
                                                        held.holdToken(),
                                                        List.of(
                                                                Map.of(
                                                                        "itemId",
                                                                        snack,
                                                                        "quantity",
                                                                        2)),
                                                        null))
                                        .andExpect(status().isCreated())
                                        .andExpect(jsonPath("$.data.tickets.length()").value(2))
                                        .andExpect(jsonPath("$.data.concessions.length()").value(1))
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .path("data");

        assertThat(data.path("totalAmount").decimalValue()).isEqualByComparingTo("265000.00");
        assertThat(data.path("tickets").get(0).path("price").decimalValue())
                .isEqualByComparingTo("80000.00");
        assertThat(data.path("tickets").get(1).path("price").decimalValue())
                .isEqualByComparingTo("95000.00");
        assertThat(data.path("concessions").get(0).path("unitPrice").decimalValue())
                .isEqualByComparingTo("45000.00");

        Long bookingId = data.path("bookingId").asLong();
        assertThat(
                        jdbc.queryForObject(
                                "select unit_price_snapshot from booking_concessions"
                                        + " where booking_id=? and item_id=?",
                                BigDecimal.class,
                                bookingId,
                                snack))
                .isEqualByComparingTo("45000.00");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from inventory_transactions where item_id=?",
                                Integer.class,
                                snack))
                .isZero();
    }

    @Test
    void rejectsHoldOwnedByAnotherSession() throws Exception {
        var held = holds.hold(show, new HoldSeatsRequest(List.of(a1)), staff);

        postBooking(customerJwt, bookingBody(show, held.holdToken(), null, null))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SEAT_HOLD_NOT_OWNED"));
    }

    @Test
    void rejectsExpiredHold() throws Exception {
        var held = holds.hold(show, new HoldSeatsRequest(List.of(a1)), staff);
        clock.advance(300);

        postBooking(staffJwt, bookingBody(show, held.holdToken(), null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_HOLD_EXPIRED"));
    }

    @Test
    void rejectsClientSuppliedPricesAsUnknownFields() throws Exception {
        Map<String, Object> withTotal =
                bookingBody(show, UUID.randomUUID().toString(), null, null);
        withTotal.put("totalAmount", 1);
        postBooking(staffJwt, withTotal)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        Map<String, Object> withPrice = bookingBody(show, UUID.randomUUID().toString(), null, null);
        withPrice.put("price", 1);
        postBooking(staffJwt, withPrice)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void validatesConcessionItemsBeforeAnyStockWork() throws Exception {
        var held = holds.hold(show, new HoldSeatsRequest(List.of(a1)), staff);
        Integer active = concession("Snack " + UUID.randomUUID(), "45000.00", true);
        Integer inactive = concession("Old " + UUID.randomUUID(), "10000.00", false);

        postBooking(
                        staffJwt,
                        bookingBody(
                                show,
                                held.holdToken(),
                                List.of(Map.of("itemId", 999999, "quantity", 1)),
                                null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONCESSION_ITEM_NOT_FOUND"));

        postBooking(
                        staffJwt,
                        bookingBody(
                                show,
                                held.holdToken(),
                                List.of(Map.of("itemId", inactive, "quantity", 1)),
                                null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONCESSION_ITEM_INACTIVE"));

        postBooking(
                        staffJwt,
                        bookingBody(
                                show,
                                held.holdToken(),
                                List.of(
                                        Map.of("itemId", active, "quantity", 1),
                                        Map.of("itemId", active, "quantity", 2)),
                                null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from inventory_transactions where item_id=?",
                                Integer.class,
                                active))
                .isZero();
    }

    @Test
    void requiresAgeConfirmationForCustomerOnT16ButNotStaff() throws Exception {
        LocalDate friday =
                LocalDate.of(2030, 1, 1).with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));
        Long t16Movie =
                jdbc.queryForObject(
                        "insert into movies(title,duration_minutes,age_rating,release_date) values"
                                + " (?,120,'T16','2030-01-01') returning movie_id",
                        Long.class,
                        "T16 " + UUID.randomUUID());
        Long t16Show = showtime(room, t16Movie, at(friday, 19));

        var customerHold = holds.hold(t16Show, new HoldSeatsRequest(List.of(a1)), customer);
        postBooking(
                        customerJwt,
                        bookingBody(t16Show, customerHold.holdToken(), null, null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AGE_CONFIRMATION_REQUIRED"));

        JsonNode customerData =
                mapper.readTree(
                                postBooking(
                                                customerJwt,
                                                bookingBody(
                                                        t16Show,
                                                        customerHold.holdToken(),
                                                        null,
                                                        true))
                                        .andExpect(status().isCreated())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .path("data");
        Long customerBookingId = customerData.path("bookingId").asLong();
        assertThat(
                        jdbc.queryForObject(
                                "select age_confirmed_at from bookings where booking_id=?",
                                Timestamp.class,
                                customerBookingId))
                .isNotNull();
        assertThat(
                        jdbc.queryForObject(
                                "select channel::text from bookings where booking_id=?",
                                String.class,
                                customerBookingId))
                .isEqualTo("ONLINE");

        var staffHold = holds.hold(t16Show, new HoldSeatsRequest(List.of(a2)), staff);
        JsonNode staffData =
                mapper.readTree(
                                postBooking(
                                                staffJwt,
                                                bookingBody(
                                                        t16Show, staffHold.holdToken(), null, null))
                                        .andExpect(status().isCreated())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .path("data");
        Long staffBookingId = staffData.path("bookingId").asLong();
        assertThat(
                        jdbc.queryForObject(
                                "select age_confirmed_at from bookings where booking_id=?",
                                Timestamp.class,
                                staffBookingId))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "select channel::text from bookings where booking_id=?",
                                String.class,
                                staffBookingId))
                .isEqualTo("POS");
    }

    @Test
    void secondBookingForTheSameHoldReturnsConflict() throws Exception {
        var held = holds.hold(show, new HoldSeatsRequest(List.of(a1)), staff);

        postBooking(staffJwt, bookingBody(show, held.holdToken(), null, null))
                .andExpect(status().isCreated());
        postBooking(staffJwt, bookingBody(show, held.holdToken(), null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOKING_ALREADY_EXISTS"));
    }

    @Test
    void concurrentBookingsForTheSameHoldCreateExactlyOneBooking() throws Exception {
        var held = holds.hold(show, new HoldSeatsRequest(List.of(a1)), staff);
        String requestBody =
                mapper.writeValueAsString(bookingBody(show, held.holdToken(), null, null));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<MvcResult> booking =
                () -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS))
                        throw new IllegalStateException("Parallel booking start timed out");
                    return mvc.perform(
                                    post("/api/v1/bookings")
                                            .header("Authorization", "Bearer " + staffJwt)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(requestBody))
                            .andReturn();
                };

        try {
            Future<MvcResult> first = pool.submit(booking);
            Future<MvcResult> second = pool.submit(booking);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            MvcResult one = first.get(30, TimeUnit.SECONDS);
            MvcResult two = second.get(30, TimeUnit.SECONDS);
            assertThat(
                            List.of(
                                    one.getResponse().getStatus(),
                                    two.getResponse().getStatus()))
                    .containsExactlyInAnyOrder(201, 409);

            MvcResult conflict =
                    one.getResponse().getStatus() == 409 ? one : two;
            assertThat(
                            mapper.readTree(conflict.getResponse().getContentAsString())
                                    .path("code")
                                    .asText())
                    .isIn("BOOKING_ALREADY_EXISTS", "SEAT_HOLD_EXPIRED");
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from bookings where hold_token=?",
                                    Integer.class,
                                    held.holdToken()))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void returnsBookingToOwnerAndHidesItFromOthers() throws Exception {
        var held = holds.hold(show, new HoldSeatsRequest(List.of(a1)), staff);
        Long bookingId =
                mapper.readTree(
                                postBooking(
                                                staffJwt,
                                                bookingBody(
                                                        show, held.holdToken(), null, null))
                                        .andExpect(status().isCreated())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .path("data")
                        .path("bookingId")
                        .asLong();

        mvc.perform(
                        get("/api/v1/bookings/" + bookingId)
                                .header("Authorization", "Bearer " + staffJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bookingId").value(bookingId.intValue()))
                .andExpect(
                        jsonPath("$.data.bookingCode")
                                .value(matchesPattern("BK-\\d{6}-\\d{4,}")));

        mvc.perform(
                        get("/api/v1/bookings/" + bookingId)
                                .header("Authorization", "Bearer " + customerJwt))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"));

        mvc.perform(
                        get("/api/v1/bookings/999999")
                                .header("Authorization", "Bearer " + staffJwt))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"));
    }

    private void assertQuote(
            Long showtimeId,
            Long seatId,
            String base,
            String roomSurcharge,
            String seatSurcharge,
            String timeSurcharge,
            String finalPrice) {
        PricingQuoteResponse quote = pricing.quote(showtimeId, List.of(seatId));
        assertThat(quote.seats()).hasSize(1);
        var line = quote.seats().getFirst();
        assertThat(line.seatId()).isEqualTo(seatId);
        assertThat(line.basePrice()).isEqualByComparingTo(base);
        assertThat(line.roomSurcharge()).isEqualByComparingTo(roomSurcharge);
        assertThat(line.seatSurcharge()).isEqualByComparingTo(seatSurcharge);
        assertThat(line.timeSurcharge()).isEqualByComparingTo(timeSurcharge);
        assertThat(line.finalPrice()).isEqualByComparingTo(finalPrice);
        assertThat(quote.ticketTotal()).isEqualByComparingTo(finalPrice);
    }

    private Long showtime(Integer roomId, Long movieId, Timestamp start) {
        Long id =
                jdbc.queryForObject(
                        "insert into showtimes(movie_id,auditorium_id,start_time,end_time,base_price)"
                                + " values (?,?,?,?,70000) returning showtime_id",
                        Long.class,
                        movieId,
                        roomId,
                        start,
                        Timestamp.from(start.toInstant().plusSeconds(8100)));
        jdbc.update(
                "insert into showtime_seats(showtime_id,seat_id) select ?,seat_id from seats where"
                        + " auditorium_id=?",
                id,
                roomId);
        return id;
    }

    private Integer auditorium(String type) {
        return jdbc.queryForObject(
                "insert into auditoriums(name,total_seats,auditorium_type) values"
                        + " (?,?,?::auditorium_type_enum) returning auditorium_id",
                Integer.class,
                "Room " + UUID.randomUUID(),
                2,
                type);
    }

    private Integer concession(String name, String price, boolean active) {
        return jdbc.queryForObject(
                "insert into concession_items(name,price,is_combo,is_active) values"
                        + " (?,?,false,?) returning item_id",
                Integer.class,
                name,
                new BigDecimal(price),
                active);
    }

    private static Timestamp at(LocalDate date, int hour) {
        return Timestamp.from(
                date.atTime(hour, 0).atZone(ShowtimeTimeService.BUSINESS_ZONE).toInstant());
    }

    private ResultActions postBooking(String jwt, Map<String, Object> body) throws Exception {
        return mvc.perform(
                post("/api/v1/bookings")
                        .header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)));
    }

    private static Map<String, Object> bookingBody(
            Long showtimeId, String holdToken, Object concessions, Boolean ageConfirmed) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("showtimeId", showtimeId);
        body.put("holdToken", holdToken);
        if (concessions != null) body.put("concessions", concessions);
        if (ageConfirmed != null) body.put("ageConfirmed", ageConfirmed);
        return body;
    }
}
