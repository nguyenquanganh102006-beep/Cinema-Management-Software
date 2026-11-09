package com.cinema.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@TestPropertySource(
        properties = {
            "app.payments.vnpay.tmn-code=TESTTMN",
            "app.payments.vnpay.hash-secret=test-vnpay-hash-secret-at-least-32-bytes",
            "app.payments.vnpay.pay-url=https://sandbox.vnpayment.vn/paymentv2/vpcpay.html",
            "app.payments.vnpay.return-url=http://localhost:3003/payments/vnpay/return",
            "app.payments.vnpay.ipn-url=http://localhost:8080/api/v1/payments/vnpay/ipn",
            "spring.datasource.hikari.maximum-pool-size=5",
            "spring.datasource.hikari.minimum-idle=1"
        })
class VnPayPaymentIntegrationTest extends SeatHoldTestSupport {
    private static final String TMN_CODE = "TESTTMN";
    private static final String HASH_SECRET = "test-vnpay-hash-secret-at-least-32-bytes";

    @Autowired BookingService bookingService;
    @Autowired VnPayService vnPayService;
    @Autowired PaymentRepository payments;

    @Test
    void createsPaymentUrlForTheCustomersOnlineBooking() throws Exception {
        BookingResponse booking = book(show, customer, null);

        JsonNode data = createVnPay(booking, customerJwt);

        String paymentUrl = data.path("paymentUrl").asText();
        assertThat(paymentUrl)
                .startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?");
        assertThat(data.path("expiresAt").asText()).isEqualTo(booking.holdUntil().toString());

        Map<String, String> query = query(paymentUrl);
        assertThat(query.get("vnp_Version")).isEqualTo("2.1.0");
        assertThat(query.get("vnp_Command")).isEqualTo("pay");
        assertThat(query.get("vnp_TmnCode")).isEqualTo(TMN_CODE);
        assertThat(query.get("vnp_CurrCode")).isEqualTo("VND");
        assertThat(query.get("vnp_Amount"))
                .isEqualTo(
                        booking.totalAmount()
                                .movePointRight(2)
                                .toBigIntegerExact()
                                .toString());
        assertThat(query.get("vnp_TxnRef")).isEqualTo(txnRef(booking.bookingId()));
        assertThat(query.get("vnp_OrderInfo"))
                .isEqualTo("Thanh toan ve phim " + booking.bookingCode());
        assertThat(query.get("vnp_ReturnUrl"))
                .isEqualTo("http://localhost:3003/payments/vnpay/return");
        assertThat(query.get("vnp_CreateDate")).isEqualTo("20300104170000");
        assertThat(query.get("vnp_ExpireDate")).isEqualTo("20300104170500");
        assertThat(query.get("vnp_SecureHash")).isEqualTo(sign(query));

        Map<String, Object> payment = paymentRow(booking.bookingId());
        assertThat(payment.get("payment_method")).isEqualTo("GATEWAY_ONLINE");
        assertThat(payment.get("status")).isEqualTo("PENDING");
        assertThat((BigDecimal) payment.get("amount")).isEqualByComparingTo(booking.totalAmount());
        assertThat(payment.get("vnp_txn_ref")).isEqualTo(query.get("vnp_TxnRef"));
        assertThat(((Timestamp) payment.get("expires_at")).toInstant())
                .isEqualTo(booking.holdUntil());
        assertThat(payment.get("gateway_transaction_code")).isNull();
        assertThat(paymentCount(booking.bookingId())).isEqualTo(1);
        assertThat(ticketCount(booking.bookingId())).isZero();
        assertThat(bookingStatus(booking.bookingId())).isEqualTo("PENDING");
        assertThat(seatStatus(show, a1)).isEqualTo("HOLDING");
    }

    @Test
    void retryWhilePendingReusesThePaymentRowAndRotatesTheTxnRef() throws Exception {
        BookingResponse booking = book(show, customer, null);

        JsonNode first = createVnPay(booking, customerJwt);
        String firstRef = query(first.path("paymentUrl").asText()).get("vnp_TxnRef");
        Long firstPaymentId = paymentId(booking.bookingId());

        JsonNode second = createVnPay(booking, customerJwt);
        String secondRef = query(second.path("paymentUrl").asText()).get("vnp_TxnRef");

        assertThat(secondRef).isNotEqualTo(firstRef);
        assertThat(paymentId(booking.bookingId())).isEqualTo(firstPaymentId);
        assertThat(paymentCount(booking.bookingId())).isEqualTo(1);
        assertThat(paymentRow(booking.bookingId()).get("vnp_txn_ref")).isEqualTo(secondRef);
        assertThat(bookingStatus(booking.bookingId())).isEqualTo("PENDING");
    }

    @Test
    void ipnSuccessIssuesTicketsExactlyOnce() throws Exception {
        BookingResponse booking = book(show, customer, null);
        createVnPay(booking, customerJwt);
        Map<String, String> params = successfulIpn(booking);

        ipn(params)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.RspCode").value("00"))
                .andExpect(jsonPath("$.Message").value("Confirm Success"));

        assertThat(bookingStatus(booking.bookingId())).isEqualTo("PAID");
        assertThat(paymentRow(booking.bookingId()).get("status")).isEqualTo("PAID");
        assertThat(paymentRow(booking.bookingId()).get("gateway_transaction_code"))
                .isEqualTo(params.get("vnp_TransactionNo"));
        assertThat(paymentRow(booking.bookingId()).get("webhook_signature"))
                .isEqualTo(params.get("vnp_SecureHash"));
        assertThat(paymentRow(booking.bookingId()).get("paid_at")).isNotNull();
        assertThat(ticketCount(booking.bookingId())).isEqualTo(1);
        assertThat(seatStatus(show, a1)).isEqualTo("BOOKED");

        ipn(params).andExpect(status().isOk()).andExpect(jsonPath("$.RspCode").value("02"));

        assertThat(paymentCount(booking.bookingId())).isEqualTo(1);
        assertThat(ticketCount(booking.bookingId())).isEqualTo(1);
    }

    @Test
    void parallelIpnDeliversOneTicketSet() throws Exception {
        BookingResponse booking = book(show, customer, null);
        createVnPay(booking, customerJwt);
        Map<String, String> params = successfulIpn(booking);

        List<VnPayIpnResponse> responses = ipnInParallel(params, 2);

        assertThat(responses).extracting(VnPayIpnResponse::rspCode)
                .containsExactlyInAnyOrder("00", "02");
        assertThat(bookingStatus(booking.bookingId())).isEqualTo("PAID");
        assertThat(ticketCount(booking.bookingId())).isEqualTo(1);
        assertThat(paymentCount(booking.bookingId())).isEqualTo(1);
    }

    @Test
    void returnUrlOnlyShowsStateAndNeverIssuesTickets() throws Exception {
        BookingResponse booking = book(show, customer, null);
        createVnPay(booking, customerJwt);
        Map<String, String> params = successfulIpn(booking);

        returnUrl(params)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.bookingStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.vnpResponseCode").value("00"));

        assertThat(bookingStatus(booking.bookingId())).isEqualTo("PENDING");
        assertThat(paymentRow(booking.bookingId()).get("status")).isEqualTo("PENDING");
        assertThat(paymentRow(booking.bookingId()).get("gateway_transaction_code")).isNull();
        assertThat(ticketCount(booking.bookingId())).isZero();
    }

    @Test
    void invalidSignatureIsRejectedWith97() throws Exception {
        BookingResponse booking = book(show, customer, null);
        createVnPay(booking, customerJwt);
        Map<String, String> params = successfulIpn(booking);
        params.put("vnp_SecureHash", "0".repeat(128));

        ipn(params)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.RspCode").value("97"))
                .andExpect(jsonPath("$.Message").value("Invalid signature"));

        assertThat(bookingStatus(booking.bookingId())).isEqualTo("PENDING");
        assertThat(paymentRow(booking.bookingId()).get("status")).isEqualTo("PENDING");
        assertThat(ticketCount(booking.bookingId())).isZero();
    }

    @Test
    void wrongAmountIsRejectedWith04() throws Exception {
        BookingResponse booking = book(show, customer, null);
        createVnPay(booking, customerJwt);
        Map<String, String> params = successfulIpn(booking);
        params.put(
                "vnp_Amount",
                new BigDecimal(params.get("vnp_Amount"))
                        .add(BigDecimal.valueOf(100))
                        .toBigInteger()
                        .toString());
        params.put("vnp_SecureHash", sign(params));

        ipn(params)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.RspCode").value("04"))
                .andExpect(jsonPath("$.Message").value("Invalid amount"));

        assertThat(bookingStatus(booking.bookingId())).isEqualTo("PENDING");
        assertThat(paymentRow(booking.bookingId()).get("status")).isEqualTo("PENDING");
        assertThat(ticketCount(booking.bookingId())).isZero();
    }

    @Test
    void failedGatewayResponseMarksPaymentFailedAndBlocksRetry() throws Exception {
        BookingResponse booking = book(show, customer, null);
        createVnPay(booking, customerJwt);
        Map<String, String> params = failedIpn(booking);

        ipn(params).andExpect(status().isOk()).andExpect(jsonPath("$.RspCode").value("00"));

        assertThat(paymentRow(booking.bookingId()).get("status")).isEqualTo("FAILED");
        assertThat(bookingStatus(booking.bookingId())).isEqualTo("PENDING");
        assertThat(ticketCount(booking.bookingId())).isZero();

        vnPay(booking.bookingId(), customerJwt)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_INVALID_STATE"));
    }

    @Test
    void successfulIpnAfterHoldExpiryParksBookingForManualDecision() throws Exception {
        BookingResponse booking = book(show, customer, null);
        createVnPay(booking, customerJwt);
        Map<String, String> params = successfulIpn(booking);

        clock.advance(301);
        ipn(params).andExpect(status().isOk()).andExpect(jsonPath("$.RspCode").value("00"));

        assertThat(bookingStatus(booking.bookingId()))
                .isEqualTo("EXCEPTION_PENDING_DECISION");
        assertThat(paymentRow(booking.bookingId()).get("status")).isEqualTo("PAID");
        assertThat(paymentRow(booking.bookingId()).get("gateway_transaction_code"))
                .isEqualTo(params.get("vnp_TransactionNo"));
        assertThat(ticketCount(booking.bookingId())).isZero();
        assertThat(seatStatus(show, a1)).isEqualTo("HOLDING");
    }

    @Test
    void priceChangedAfterBookingParksBookingForManualDecision() throws Exception {
        BookingResponse booking = book(show, customer, null);
        createVnPay(booking, customerJwt);
        Map<String, String> params = successfulIpn(booking);

        BigDecimal originalRuleAmount =
                jdbc.queryForObject(
                        "select amount from pricing_rules where rule_code='SUR_TIME'",
                        BigDecimal.class);
        try {
            jdbc.update(
                    "update pricing_rules set amount = amount + 10000"
                            + " where rule_code='SUR_TIME'");
            ipn(params)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.RspCode").value("00"));
        } finally {
            jdbc.update(
                    "update pricing_rules set amount=? where rule_code='SUR_TIME'",
                    originalRuleAmount);
        }

        assertThat(bookingStatus(booking.bookingId()))
                .isEqualTo("EXCEPTION_PENDING_DECISION");
        assertThat(paymentRow(booking.bookingId()).get("status")).isEqualTo("PAID");
        assertThat(paymentRow(booking.bookingId()).get("gateway_transaction_code"))
                .isEqualTo(params.get("vnp_TransactionNo"));
        assertThat(ticketCount(booking.bookingId())).isZero();
        assertThat(seatStatus(show, a1)).isEqualTo("HOLDING");

        ipn(params).andExpect(status().isOk()).andExpect(jsonPath("$.RspCode").value("02"));
    }

    @Test
    void anotherCustomerCannotCreatePaymentForTheBooking() throws Exception {
        BookingResponse booking = book(show, customer, null);
        User other =
                users.saveAndFlush(
                        new User(
                                "cus2_" + UUID.randomUUID().toString().substring(0, 8),
                                "Other Customer",
                                UUID.randomUUID().toString().substring(0, 8) + "c2@test.invalid",
                                "+8491"
                                        + Math.abs(
                                                UUID.randomUUID().getMostSignificantBits()
                                                        % 100000000L),
                                "test-only-not-used",
                                UserRole.CUSTOMER));
        String otherJwt = jwt.issue(other);

        vnPay(booking.bookingId(), otherJwt)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"));

        assertThat(paymentCount(booking.bookingId())).isZero();
        assertThat(ticketCount(booking.bookingId())).isZero();
    }

    @Test
    void staffCannotCallTheCustomerPaymentEndpoint() throws Exception {
        BookingResponse booking = book(show, customer, null);

        vnPay(booking.bookingId(), staffJwt)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        assertThat(paymentCount(booking.bookingId())).isZero();
    }

    @Test
    void restrictedMovieWithoutAgeConfirmationIsRejected() throws Exception {
        Long restrictedShow = showtime(movie("T16"), room, 4000);
        BookingResponse booking = book(restrictedShow, customer, true);
        jdbc.update(
                "update bookings set age_confirmed_at=null where booking_id=?",
                booking.bookingId());

        vnPay(booking.bookingId(), customerJwt)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AGE_CONFIRMATION_REQUIRED"));

        assertThat(paymentCount(booking.bookingId())).isZero();
        assertThat(ticketCount(booking.bookingId())).isZero();
    }

    private BookingResponse book(
            Long showtimeId, SessionPrincipal principal, Boolean ageConfirmed) {
        HoldSeatsResponse held =
                holds.hold(showtimeId, new HoldSeatsRequest(List.of(a1)), principal);
        return bookingService.create(
                new CreateBookingRequest(showtimeId, held.holdToken(), null, ageConfirmed),
                principal);
    }

    private ResultActions vnPay(Long bookingId, String jwt) throws Exception {
        return mvc.perform(
                post("/api/v1/bookings/{bookingId}/payments/vnpay", bookingId)
                        .header("Authorization", "Bearer " + jwt));
    }

    private JsonNode createVnPay(BookingResponse booking, String jwt) throws Exception {
        return data(
                vnPay(booking.bookingId(), jwt)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.paymentUrl").isNotEmpty())
                        .andExpect(jsonPath("$.data.expiresAt").isNotEmpty()));
    }

    private JsonNode data(ResultActions actions) throws Exception {
        return mapper.readTree(actions.andReturn().getResponse().getContentAsString())
                .path("data");
    }

    private ResultActions ipn(Map<String, String> params) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/payments/vnpay/ipn");
        params.forEach(request::param);
        return mvc.perform(request);
    }

    private ResultActions returnUrl(Map<String, String> params) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/payments/vnpay/return");
        params.forEach(request::param);
        return mvc.perform(request);
    }

    private Map<String, String> successfulIpn(BookingResponse booking) {
        return ipnParams(booking, "00", "00", transactionNo());
    }

    private Map<String, String> failedIpn(BookingResponse booking) {
        return ipnParams(booking, "24", "02", transactionNo());
    }

    private Map<String, String> ipnParams(
            BookingResponse booking,
            String responseCode,
            String transactionStatus,
            String transactionNo) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vnp_TmnCode", TMN_CODE);
        params.put("vnp_TxnRef", txnRef(booking.bookingId()));
        params.put(
                "vnp_Amount",
                booking.totalAmount().movePointRight(2).toBigIntegerExact().toString());
        params.put("vnp_ResponseCode", responseCode);
        params.put("vnp_TransactionStatus", transactionStatus);
        params.put("vnp_TransactionNo", transactionNo);
        params.put("vnp_OrderInfo", "Thanh toan ve phim " + booking.bookingCode());
        params.put("vnp_PayDate", "20300104170000");
        params.put("vnp_BankCode", "NCB");
        params.put("vnp_BankTranNo", "BANK" + transactionNo);
        params.put("vnp_CardType", "ATM");
        params.put("vnp_SecureHash", sign(params));
        return params;
    }

    private String sign(Map<String, String> params) {
        Map<String, String> sorted = new TreeMap<>();
        params.forEach(
                (key, value) -> {
                    if (key.startsWith("vnp_")
                            && !"vnp_SecureHash".equals(key)
                            && !"vnp_SecureHashType".equals(key)
                            && value != null
                            && !value.isEmpty()) {
                        sorted.put(key, value);
                    }
                });
        String data =
                sorted.entrySet().stream()
                        .map(
                                entry ->
                                        entry.getKey()
                                                + "="
                                                + URLEncoder.encode(
                                                        entry.getValue(), StandardCharsets.UTF_8))
                        .reduce((left, right) -> left + "&" + right)
                        .orElse("");
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(
                    new SecretKeySpec(
                            HASH_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of()
                    .formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<VnPayIpnResponse> ipnInParallel(Map<String, String> params, int count)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<VnPayIpnResponse>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    if (!start.await(10, TimeUnit.SECONDS))
                                        throw new IllegalStateException("IPN start timed out");
                                    return vnPayService.handleIpn(params);
                                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<VnPayIpnResponse> responses = new ArrayList<>();
            for (Future<VnPayIpnResponse> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    private Map<String, String> query(String url) {
        Map<String, String> params = new LinkedHashMap<>();
        String rawQuery = URI.create(url).getRawQuery();
        if (rawQuery == null) return params;
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            params.put(
                    URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    private String txnRef(Long bookingId) {
        return jdbc.queryForObject(
                "select vnp_txn_ref from payments where booking_id=?",
                String.class,
                bookingId);
    }

    private Long paymentId(Long bookingId) {
        return jdbc.queryForObject(
                "select payment_id from payments where booking_id=?",
                Long.class,
                bookingId);
    }

    private static String transactionNo() {
        return "VNP" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
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
                        + " amount, vnp_txn_ref, expires_at, gateway_transaction_code,"
                        + " webhook_signature, paid_at"
                        + " from payments where booking_id=?",
                bookingId);
    }
}
