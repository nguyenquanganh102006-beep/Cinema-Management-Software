package com.cinema.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.cinema.cms.dto.*;
import com.cinema.cms.entity.*;
import com.cinema.cms.repository.PaymentRepository;
import com.cinema.cms.service.*;
import com.cinema.cms.support.SeatHoldTestSupport;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CheckoutServiceIntegrationTest extends SeatHoldTestSupport {
    @Autowired BookingService bookingService;
    @Autowired CheckoutService checkoutService;
    @Autowired PaymentRepository payments;

    @Test
    void finalizesPaidBookingWithTicketsStockSalesAndSnapshots() {
        Integer snack = item("Snack", "45000.00");
        Integer popcorn = item("Popcorn", "30000.00");
        Integer drink = item("Drink", "20000.00");
        Integer combo = combo("Combo", "60000.00", Map.of(popcorn, 1, drink, 1));
        stock(snack, 10);
        stock(popcorn, 10);
        stock(drink, 10);

        Map<Integer, Integer> order = new LinkedHashMap<>();
        order.put(snack, 2);
        order.put(combo, 1);

        BookingResponse booking = book(show, List.of(a1, vip), order);
        assertThat(booking.totalAmount()).isEqualByComparingTo("325000.00");

        CheckoutResult result =
                checkoutService.finalizePaid(
                        booking.bookingId(), PaymentMethod.CASH, null);

        assertThat(result.booking().getStatus()).isEqualTo(BookingStatus.PAID);
        assertThat(result.booking().getPaidAt()).isNotNull();
        assertThat(result.payment().getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(result.payment().getAmount()).isEqualByComparingTo("325000.00");
        assertThat(result.payment().getPaidAt()).isNotNull();
        assertThat(result.tickets()).hasSize(2);

        assertThat(seatStatus(a1)).isEqualTo("BOOKED");
        assertThat(seatStatus(vip)).isEqualTo("BOOKED");
        assertThat(holdTokenPresent(a1)).isFalse();
        assertThat(holdTokenPresent(vip)).isFalse();

        assertThat(stockOf(snack)).isEqualTo(8);
        assertThat(stockOf(popcorn)).isEqualTo(9);
        assertThat(stockOf(drink)).isEqualTo(9);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from concession_inventory where item_id=?",
                                Integer.class,
                                combo))
                .isZero();

        assertSale(snack, -2, 10, 8);
        assertSale(popcorn, -1, 10, 9);
        assertSale(drink, -1, 10, 9);

        assertThat(bookingStatus(booking.bookingId())).isEqualTo("PAID");
        assertThat(paymentCount(booking.bookingId())).isEqualTo(1);

        List<BigDecimal> prices =
                jdbc.queryForList(
                        "select price_snapshot from tickets where booking_id=? order by ticket_id",
                        BigDecimal.class,
                        booking.bookingId());
        assertThat(prices)
                .containsExactly(new BigDecimal("80000.00"), new BigDecimal("95000.00"));

        List<String> codes =
                jdbc.queryForList(
                        "select ticket_code from tickets where booking_id=?",
                        String.class,
                        booking.bookingId());
        assertThat(codes)
                .hasSize(2)
                .doesNotHaveDuplicates()
                .allMatch(code -> code.startsWith("TK-") && code.length() <= 50);

        List<Long> ticketSeats =
                jdbc.queryForList(
                        "select showtime_seat_id from tickets where booking_id=?",
                        Long.class,
                        booking.bookingId());
        List<Long> expectedSeats =
                jdbc.queryForList(
                        "select showtime_seat_id from showtime_seats"
                                + " where showtime_id=? and seat_id in (?,?)",
                        Long.class,
                        show,
                        a1,
                        vip);
        assertThat(ticketSeats)
                .containsExactlyInAnyOrderElementsOf(expectedSeats);

        assertThat(
                        jdbc.queryForObject(
                                "select unit_price_snapshot from booking_concessions"
                                        + " where booking_id=? and item_id=?",
                                BigDecimal.class,
                                booking.bookingId(),
                                snack))
                .isEqualByComparingTo("45000.00");
        assertThat(
                        jdbc.queryForObject(
                                "select unit_price_snapshot from booking_concessions"
                                        + " where booking_id=? and item_id=?",
                                BigDecimal.class,
                                booking.bookingId(),
                                combo))
                .isEqualByComparingTo("60000.00");
    }

    @Test
    void insufficientStockMidwayRollsBackSeatsStockTicketsAndPayment() {
        Integer first = item("First", "10000.00");
        Integer second = item("Second", "20000.00");
        stock(first, 10);
        stock(second, 1);

        Map<Integer, Integer> order = new LinkedHashMap<>();
        order.put(first, 1);
        order.put(second, 5);
        BookingResponse booking = book(show, List.of(a1), order);

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                checkoutService.finalizePaid(
                                        booking.bookingId(), PaymentMethod.CASH, null));
        assertThat(error.getCode()).isEqualTo("INSUFFICIENT_STOCK");

        assertThat(seatStatus(a1)).isEqualTo("HOLDING");
        assertThat(holdTokenPresent(a1)).isTrue();
        assertThat(stockOf(first)).isEqualTo(10);
        assertThat(stockOf(second)).isEqualTo(1);
        assertThat(ticketCount(booking.bookingId())).isZero();
        assertThat(paymentCount(booking.bookingId())).isZero();
        assertThat(bookingStatus(booking.bookingId())).isEqualTo("PENDING");
    }

    @Test
    void concurrentFinalizeOfTheSameBookingSucceedsExactlyOnce() throws Exception {
        BookingResponse booking = book(show, List.of(a1), null);

        List<Object> results =
                finalizeInParallel(
                        List.of(booking.bookingId(), booking.bookingId()),
                        PaymentMethod.CASH);

        assertThat(results.stream().filter(CheckoutResult.class::isInstance)).hasSize(1);
        ApiException failure = onlyFailure(results);
        assertThat(failure.getCode()).isEqualTo("BOOKING_ALREADY_PAID");

        assertThat(ticketCount(booking.bookingId())).isEqualTo(1);
        assertThat(paymentCount(booking.bookingId())).isEqualTo(1);
        assertThat(bookingStatus(booking.bookingId())).isEqualTo("PAID");
        assertThat(seatStatus(a1)).isEqualTo("BOOKED");
    }

    @Test
    void expiredHoldIsRejectedWithoutTicketsOrPayment() {
        BookingResponse booking = book(show, List.of(a1), null);
        clock.advance(300);

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                checkoutService.finalizePaid(
                                        booking.bookingId(), PaymentMethod.CASH, null));
        assertThat(error.getCode()).isEqualTo("SEAT_HOLD_EXPIRED");

        assertThat(ticketCount(booking.bookingId())).isZero();
        assertThat(paymentCount(booking.bookingId())).isZero();
        assertThat(bookingStatus(booking.bookingId())).isEqualTo("PENDING");
        assertThat(seatStatus(a1)).isEqualTo("HOLDING");
    }

    @Test
    void twoBookingsCompetingForTheLastUnitLeaveStockNonNegative() throws Exception {
        Integer last = item("Last", "10000.00");
        stock(last, 1);

        BookingResponse first = book(show, List.of(a1), Map.of(last, 1));
        BookingResponse second = book(show, List.of(a2), Map.of(last, 1));

        List<Object> results =
                finalizeInParallel(
                        List.of(first.bookingId(), second.bookingId()), PaymentMethod.CASH);

        CheckoutResult success =
                (CheckoutResult)
                        results.stream()
                                .filter(CheckoutResult.class::isInstance)
                                .findFirst()
                                .orElseThrow();
        ApiException failure = onlyFailure(results);
        assertThat(failure.getCode()).isIn("OUT_OF_STOCK", "INSUFFICIENT_STOCK");

        Long paidBookingId = success.booking().getId();
        Long pendingBookingId =
                paidBookingId.equals(first.bookingId())
                        ? second.bookingId()
                        : first.bookingId();

        assertThat(stockOf(last)).isZero();
        assertThat(ticketCount(paidBookingId)).isEqualTo(1);
        assertThat(ticketCount(pendingBookingId)).isZero();
        assertThat(bookingStatus(paidBookingId)).isEqualTo("PAID");
        assertThat(bookingStatus(pendingBookingId)).isEqualTo("PENDING");

        Long paidSeat = paidBookingId.equals(first.bookingId()) ? a1 : a2;
        Long pendingSeat = pendingBookingId.equals(first.bookingId()) ? a1 : a2;
        assertThat(seatStatus(paidSeat)).isEqualTo("BOOKED");
        assertThat(seatStatus(pendingSeat)).isEqualTo("HOLDING");
        assertThat(holdTokenPresent(pendingSeat)).isTrue();
    }

    @Test
    void changedPriceRollsBackAndDoesNotIssueTickets() {
        Integer snack = item("Snack", "45000.00");
        stock(snack, 10);
        BookingResponse booking = book(show, List.of(a1), Map.of(snack, 1));

        jdbc.update(
                "update concession_items set price=? where item_id=?",
                new BigDecimal("50000.00"),
                snack);

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                checkoutService.finalizePaid(
                                        booking.bookingId(), PaymentMethod.CASH, null));
        assertThat(error.getCode()).isEqualTo("PRICE_CHANGED");

        assertThat(seatStatus(a1)).isEqualTo("HOLDING");
        assertThat(holdTokenPresent(a1)).isTrue();
        assertThat(stockOf(snack)).isEqualTo(10);
        assertThat(ticketCount(booking.bookingId())).isZero();
        assertThat(bookingStatus(booking.bookingId())).isEqualTo("PENDING");
    }

    @Test
    void expectedAmountMustMatchThePendingPayment() {
        BookingResponse booking = book(show, List.of(a1), null);
        payments.saveAndFlush(
                new Payment(
                        booking.bookingId(),
                        PaymentMethod.GATEWAY_ONLINE,
                        booking.totalAmount()));

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                checkoutService.finalizePaid(
                                        booking.bookingId(),
                                        PaymentMethod.GATEWAY_ONLINE,
                                        new BigDecimal("80001.00")));
        assertThat(error.getCode()).isEqualTo("PAYMENT_AMOUNT_MISMATCH");

        assertThat(seatStatus(a1)).isEqualTo("HOLDING");
        assertThat(ticketCount(booking.bookingId())).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select status::text from payments where booking_id=?",
                                String.class,
                                booking.bookingId()))
                .isEqualTo("PENDING");
    }

    @Test
    void failedPaymentCannotBeReused() {
        BookingResponse booking = book(show, List.of(a1), null);
        Payment payment =
                payments.saveAndFlush(
                        new Payment(
                                booking.bookingId(), PaymentMethod.CASH, booking.totalAmount()));
        payment.markFailed();
        payments.saveAndFlush(payment);

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                checkoutService.finalizePaid(
                                        booking.bookingId(), PaymentMethod.CASH, null));
        assertThat(error.getCode()).isEqualTo("PAYMENT_INVALID_STATE");

        assertThat(seatStatus(a1)).isEqualTo("HOLDING");
        assertThat(ticketCount(booking.bookingId())).isZero();
    }

    @Test
    void cancelledBookingCannotBeFinalized() {
        BookingResponse booking = book(show, List.of(a1), null);
        jdbc.update(
                "update bookings set status='CANCELLED' where booking_id=?",
                booking.bookingId());

        ApiException error =
                assertThrows(
                        ApiException.class,
                        () ->
                                checkoutService.finalizePaid(
                                        booking.bookingId(), PaymentMethod.CASH, null));
        assertThat(error.getCode()).isEqualTo("BOOKING_INVALID_STATE");

        assertThat(seatStatus(a1)).isEqualTo("HOLDING");
        assertThat(ticketCount(booking.bookingId())).isZero();
    }

    private BookingResponse book(
            Long showtimeId, List<Long> seatIds, Map<Integer, Integer> concessions) {
        HoldSeatsResponse held =
                holds.hold(showtimeId, new HoldSeatsRequest(seatIds), staff);
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
                new CreateBookingRequest(showtimeId, held.holdToken(), lines, null), staff);
    }

    private Integer item(String name, String price) {
        return jdbc.queryForObject(
                "insert into concession_items(name,price,is_combo,is_active) values"
                        + " (?,?,false,true) returning item_id",
                Integer.class,
                name + " " + UUID.randomUUID(),
                new BigDecimal(price));
    }

    private Integer combo(String name, String price, Map<Integer, Integer> components) {
        Integer comboId =
                jdbc.queryForObject(
                        "insert into concession_items(name,price,is_combo,is_active) values"
                                + " (?,?,true,true) returning item_id",
                        Integer.class,
                        name + " " + UUID.randomUUID(),
                        new BigDecimal(price));
        for (Map.Entry<Integer, Integer> component : components.entrySet()) {
            jdbc.update(
                    "insert into combo_items(combo_id,component_item_id,quantity) values (?,?,?)",
                    comboId,
                    component.getKey(),
                    component.getValue());
        }
        return comboId;
    }

    private void stock(Integer itemId, int quantity) {
        jdbc.update(
                "insert into concession_inventory(item_id,stock_quantity,low_stock_threshold)"
                        + " values (?,?,?)",
                itemId,
                quantity,
                5);
    }

    private int stockOf(Integer itemId) {
        return jdbc.queryForObject(
                "select stock_quantity from concession_inventory where item_id=?",
                Integer.class,
                itemId);
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

    private String bookingStatus(Long bookingId) {
        return jdbc.queryForObject(
                "select status::text from bookings where booking_id=?",
                String.class,
                bookingId);
    }

    private boolean holdTokenPresent(Long seatId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "select hold_token is not null from showtime_seats"
                                + " where showtime_id=? and seat_id=?",
                        Boolean.class,
                        show,
                        seatId));
    }

    private void assertSale(Integer itemId, int quantity, int before, int after) {
        Map<String, Object> row =
                jdbc.queryForMap(
                        "select movement_type, quantity, before_quantity, after_quantity,"
                                + " manager_id from inventory_transactions where item_id=?"
                                + " order by movement_id desc limit 1",
                        itemId);
        assertThat(row.get("movement_type")).isEqualTo("SALE");
        assertThat(((Number) row.get("quantity")).intValue()).isEqualTo(quantity);
        assertThat(((Number) row.get("before_quantity")).intValue()).isEqualTo(before);
        assertThat(((Number) row.get("after_quantity")).intValue()).isEqualTo(after);
        assertThat(row.get("manager_id")).isNull();
    }

    private List<Object> finalizeInParallel(List<Long> bookingIds, PaymentMethod method)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(bookingIds.size());
        CountDownLatch ready = new CountDownLatch(bookingIds.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Long bookingId : bookingIds) {
                futures.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    if (!start.await(10, TimeUnit.SECONDS))
                                        throw new IllegalStateException(
                                                "Parallel checkout start timed out");
                                    try {
                                        return checkoutService.finalizePaid(
                                                bookingId, method, null);
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
