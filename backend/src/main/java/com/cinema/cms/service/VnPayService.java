package com.cinema.cms.service;

import com.cinema.cms.dto.*;
import com.cinema.cms.entity.*;
import com.cinema.cms.repository.*;
import java.math.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * VNPay sandbox integration.
 *
 * <p>The return URL is display-only. Only a signed IPN may finalize a booking. Money is recorded
 * before checkout in a separate transaction; if the hold or stock cannot deliver after VNPay has
 * confirmed the money, the booking is parked in {@code EXCEPTION_PENDING_DECISION} and the payment
 * is marked PAID for manual handling.
 */
@Service
public class VnPayService {
    private static final Logger audit = LoggerFactory.getLogger("AUDIT_PAYMENT");
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter VNP_DATE =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String SUCCESS = "00";
    private static final int MAX_TXN_REF_ATTEMPTS = 10;

    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final ShowtimeRepository showtimes;
    private final MovieRepository movies;
    private final ShowtimeSeatRepository inventory;
    private final CheckoutService checkout;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final TransactionTemplate tx;

    private final String tmnCode;
    private final String hashSecret;
    private final String payUrl;
    private final String returnUrl;
    private final String ipnUrl;

    public VnPayService(
            BookingRepository bookings,
            PaymentRepository payments,
            ShowtimeRepository showtimes,
            MovieRepository movies,
            ShowtimeSeatRepository inventory,
            CheckoutService checkout,
            Clock clock,
            PlatformTransactionManager transactionManager,
            @Value("${app.payments.vnpay.tmn-code:}") String tmnCode,
            @Value("${app.payments.vnpay.hash-secret:}") String hashSecret,
            @Value("${app.payments.vnpay.pay-url:}") String payUrl,
            @Value("${app.payments.vnpay.return-url:}") String returnUrl,
            @Value("${app.payments.vnpay.ipn-url:}") String ipnUrl) {
        this.bookings = bookings;
        this.payments = payments;
        this.showtimes = showtimes;
        this.movies = movies;
        this.inventory = inventory;
        this.checkout = checkout;
        this.clock = clock;
        this.tmnCode = strip(tmnCode);
        this.hashSecret = strip(hashSecret);
        this.payUrl = strip(payUrl);
        this.returnUrl = strip(returnUrl);
        this.ipnUrl = strip(ipnUrl);
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * Creates or refreshes the one Payment row allowed for a Booking. A PENDING retry gets a new
     * merchant reference on the same row; PAID and FAILED are terminal.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public VnPayPaymentResponse createPayment(
            Long bookingId, SessionPrincipal principal, String ipAddress) {
        Long customerId = principal == null ? null : principal.userId();
        try {
            requireCustomer(principal);
            requirePaymentConfiguration();

            // Lock first: the UNIQUE(booking_id) payment row is created only after this lock.
            Booking booking = lockBooking(bookingId);
            requireOwned(booking, principal);
            requireOnline(booking);
            requirePendingBooking(booking);
            requireAgeConfirmation(booking);

            Instant holdUntil = requireLiveHold(booking);
            Instant expiresAt = holdUntil.truncatedTo(ChronoUnit.SECONDS);
            Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
            if (!now.isBefore(expiresAt)) throw holdExpired();

            BigDecimal totalAmount = money(booking.getTotalAmount());
            Payment payment = payments.findByBookingId(bookingId).orElse(null);
            if (payment == null) {
                payment = new Payment(bookingId, PaymentMethod.GATEWAY_ONLINE, totalAmount);
            } else {
                requireGatewayMethod(payment);
                requirePendingPayment(payment);
                requirePaymentAmount(payment, totalAmount);
            }

            String txnRef = nextTxnRef(bookingId);
            payment.attachGateway(txnRef, expiresAt);
            payments.saveAndFlush(payment);

            String paymentUrl =
                    buildPaymentUrl(payment, booking, ipAddress, now, expiresAt);
            audit.info(
                    "action=VNPAY_CREATED result=PENDING bookingId={} bookingCode={} customerId={}"
                            + " paymentId={} amount={} txnRef={} expiresAt={}",
                    bookingId,
                    booking.getBookingCode(),
                    customerId,
                    payment.getId(),
                    payment.getAmount(),
                    txnRef,
                    expiresAt);
            return new VnPayPaymentResponse(paymentUrl, expiresAt);
        } catch (ApiException e) {
            audit.warn(
                    "action=VNPAY_CREATED result=REJECTED bookingId={} customerId={} code={}",
                    bookingId,
                    customerId,
                    e.getCode());
            throw e;
        }
    }

    /** UI-only: verify the signature and read the current database state. Never finalizes. */
    @Transactional(readOnly = true)
    public VnPayReturnResponse readReturn(Map<String, String> params) {
        requireHashConfiguration();
        if (!verifySignature(params) || !tmnMatches(params)) {
            audit.warn("action=VNPAY_RETURN result=REJECTED code=VNPAY_INVALID_SIGNATURE");
            throw new ApiException(400, "VNPAY_INVALID_SIGNATURE", "Chữ ký VNPay không hợp lệ");
        }
        String txnRef = params == null ? null : params.get("vnp_TxnRef");
        if (txnRef == null || txnRef.isBlank()) {
            throw new ApiException(400, "VALIDATION_ERROR", "Thiếu mã giao dịch VNPay");
        }
        Payment payment =
                payments
                        .findByVnpTxnRef(txnRef)
                        .orElseThrow(VnPayService::paymentNotFound);
        Booking booking =
                bookings
                        .findById(payment.getBookingId())
                        .orElseThrow(VnPayService::bookingNotFound);

        audit.info(
                "action=VNPAY_RETURN result=STATUS bookingId={} paymentId={} paymentStatus={}"
                        + " responseCode={}",
                booking.getId(),
                payment.getId(),
                payment.getStatus(),
                params.get("vnp_ResponseCode"));
        return new VnPayReturnResponse(
                payment.getId(),
                booking.getId(),
                booking.getBookingCode(),
                payment.getVnpTxnRef(),
                params.get("vnp_ResponseCode"),
                params.get("vnp_TransactionNo"),
                money(payment.getAmount()),
                payment.getStatus().name(),
                booking.getStatus().name());
    }

    /**
     * VNPay IPN. Always returns the provider's JSON contract; no exception escapes to the global
     * handler. Signature verification happens before any database access.
     */
    public VnPayIpnResponse handleIpn(Map<String, String> params) {
        try {
            if (!hashConfigured()) {
                audit.error("action=VNPAY_IPN result=ERROR reason=CONFIGURATION_MISSING");
                return ipn("99");
            }
            if (!verifySignature(params) || !tmnMatches(params)) {
                audit.warn("action=VNPAY_IPN result=REJECTED code=VNPAY_INVALID_SIGNATURE");
                return ipn("97");
            }

            String txnRef = params.get("vnp_TxnRef");
            if (txnRef == null || txnRef.isBlank()) return ipn("01");
            String responseCode = params.get("vnp_ResponseCode");
            if (responseCode == null || responseCode.isBlank()) return ipn("99");

            IpnPreparation prepared =
                    tx.execute(
                            status ->
                                    prepareIpn(
                                            txnRef,
                                            params.get("vnp_Amount"),
                                            responseCode,
                                            params.get("vnp_TransactionNo"),
                                            params.get("vnp_SecureHash")));
            if (prepared == null) return ipn("99");

            return switch (prepared.outcome()) {
                case NOT_FOUND -> ipn("01");
                case INVALID -> ipn("99");
                case AMOUNT_MISMATCH -> ipn("04");
                case TERMINAL -> ipn("02");
                case FAILED -> {
                    audit.info(
                            "action=VNPAY_IPN result=FAILED bookingId={} paymentId={} txnRef={}"
                                    + " responseCode={}",
                            prepared.bookingId(),
                            prepared.paymentId(),
                            txnRef,
                            responseCode);
                    yield ipn("00");
                }
                case READY -> finalizeIpn(prepared, txnRef, params.get("vnp_TransactionNo"));
            };
        } catch (RuntimeException e) {
            // Exception messages and SQL can contain sensitive values; log only the type.
            audit.error(
                    "action=VNPAY_IPN result=ERROR type={}", e.getClass().getSimpleName());
            return ipn("99");
        }
    }

    private VnPayIpnResponse finalizeIpn(
            IpnPreparation prepared, String txnRef, String transactionNo) {
        try {
            CheckoutResult result =
                    checkout.finalizePaid(
                            prepared.bookingId(),
                            PaymentMethod.GATEWAY_ONLINE,
                            prepared.amount());
            audit.info(
                    "action=VNPAY_IPN result=PAID bookingId={} bookingCode={} paymentId={}"
                            + " txnRef={} transactionNo={} amount={} tickets={}",
                    prepared.bookingId(),
                    result.booking().getBookingCode(),
                    result.payment().getId(),
                    txnRef,
                    transactionNo,
                    result.payment().getAmount(),
                    result.tickets().size());
            return ipn("00");
        } catch (ApiException e) {
            if ("BOOKING_ALREADY_PAID".equals(e.getCode())) {
                Payment current = payments.findByVnpTxnRef(txnRef).orElse(null);
                return current != null && current.getStatus() == PaymentStatus.PAID
                        ? ipn("02")
                        : ipn("99");
            }
            if ("SEAT_UPDATE_CONFLICT".equals(e.getCode())) {
                // Transient optimistic-lock conflict: let VNPay retry the same IPN.
                audit.warn(
                        "action=VNPAY_IPN result=REJECTED bookingId={} paymentId={} txnRef={}"
                                + " code={}",
                        prepared.bookingId(),
                        prepared.paymentId(),
                        txnRef,
                        e.getCode());
                return ipn("99");
            }

            // VNPay has already confirmed the money. Any other checkout rejection means the
            // promised seats/F&B cannot be delivered, so park the booking for manual handling
            // instead of letting VNPay retry until it gives up.
            ExceptionOutcome outcome = markException(prepared.bookingId(), txnRef);
            if (outcome == ExceptionOutcome.ALREADY_PAID) return ipn("02");
            if (outcome == ExceptionOutcome.MARKED
                    || outcome == ExceptionOutcome.ALREADY_MARKED) {
                audit.error(
                        "action=VNPAY_IPN result=EXCEPTION_PENDING_DECISION"
                                + " bookingId={} paymentId={} txnRef={} amount={} reason={}",
                        prepared.bookingId(),
                        prepared.paymentId(),
                        txnRef,
                        prepared.amount(),
                        e.getCode());
                return ipn("00");
            }
            return ipn("99");
        }
    }

    /**
     * Runs in a fresh READ_COMMITTED transaction. It validates and records the gateway result
     * while the payment row is locked, then lets the caller run checkout in another transaction.
     * This avoids Spring's rollback-only marker when checkout rejects an expired hold or stock.
     */
    private IpnPreparation prepareIpn(
            String txnRef,
            String rawAmount,
            String responseCode,
            String transactionNo,
            String secureHash) {
        Payment payment = payments.findByVnpTxnRefForUpdate(txnRef).orElse(null);
        if (payment == null) return IpnPreparation.notFound();
        Booking booking = bookings.findById(payment.getBookingId()).orElse(null);
        if (booking == null) return IpnPreparation.notFound();

        if (payment.getPaymentMethod() != PaymentMethod.GATEWAY_ONLINE
                || booking.getChannel() != BookingChannel.ONLINE) {
            return IpnPreparation.invalid();
        }
        if (!amountMatches(payment, rawAmount)) return IpnPreparation.amountMismatch();

        if (payment.getStatus() != PaymentStatus.PENDING) return IpnPreparation.terminal();
        if (booking.getStatus() == BookingStatus.PAID
                || booking.getStatus() == BookingStatus.EXCEPTION_PENDING_DECISION) {
            return IpnPreparation.terminal();
        }
        if (booking.getStatus() != BookingStatus.PENDING) return IpnPreparation.invalid();

        if (!SUCCESS.equals(responseCode)) {
            payment.markFailed();
            payments.saveAndFlush(payment);
            return IpnPreparation.failed(payment, booking);
        }

        if (transactionNo == null || transactionNo.isBlank()
                || secureHash == null || secureHash.isBlank()) {
            return IpnPreparation.invalid();
        }
        Payment duplicate = payments.findByGatewayTransactionCode(transactionNo).orElse(null);
        if (duplicate != null && !duplicate.getId().equals(payment.getId())) {
            return IpnPreparation.invalid();
        }
        payment.recordGatewayResult(transactionNo, secureHash);
        payments.saveAndFlush(payment);
        return IpnPreparation.ready(payment, booking);
    }

    /** Locks Booking then Payment, matching checkout lock order. Idempotent. */
    private ExceptionOutcome markException(Long bookingId, String txnRef) {
        ExceptionOutcome outcome =
                tx.execute(
                        status -> {
                            Booking booking =
                                    bookings.findByIdForUpdate(bookingId).orElse(null);
                            if (booking == null) return ExceptionOutcome.NOT_APPLIED;
                            Payment payment =
                                    payments
                                            .findByVnpTxnRefForUpdate(txnRef)
                                            .orElse(null);
                            if (payment == null) return ExceptionOutcome.NOT_APPLIED;
                            if (booking.getStatus() == BookingStatus.PAID) {
                                return ExceptionOutcome.ALREADY_PAID;
                            }
                            if (booking.getStatus() != BookingStatus.PENDING
                                    && booking.getStatus()
                                            != BookingStatus.EXCEPTION_PENDING_DECISION) {
                                return ExceptionOutcome.NOT_APPLIED;
                            }
                            if (payment.getStatus() == PaymentStatus.FAILED) {
                                return ExceptionOutcome.NOT_APPLIED;
                            }

                            boolean changed = false;
                            if (payment.getStatus() == PaymentStatus.PENDING) {
                                payment.markPaid(now());
                                payments.save(payment);
                                changed = true;
                            }
                            if (booking.getStatus() == BookingStatus.PENDING) {
                                booking.markException(now());
                                bookings.save(booking);
                                changed = true;
                            }
                            return changed
                                    ? ExceptionOutcome.MARKED
                                    : ExceptionOutcome.ALREADY_MARKED;
                        });
        return outcome == null ? ExceptionOutcome.NOT_APPLIED : outcome;
    }

    private String buildPaymentUrl(
            Payment payment,
            Booking booking,
            String ipAddress,
            Instant createdAt,
            Instant expiresAt) {
        Map<String, String> params = new TreeMap<>();
        params.put("vnp_Version", "2.1.0");
        params.put("vnp_Command", "pay");
        params.put("vnp_TmnCode", tmnCode);
        params.put("vnp_Amount", vnpAmount(payment.getAmount()));
        params.put("vnp_CurrCode", "VND");
        params.put("vnp_TxnRef", payment.getVnpTxnRef());
        params.put("vnp_OrderInfo", "Thanh toan ve phim " + booking.getBookingCode());
        params.put("vnp_OrderType", "other");
        params.put("vnp_Locale", "vn");
        params.put("vnp_ReturnUrl", returnUrl);
        params.put("vnp_IpAddr", clientIp(ipAddress));
        params.put("vnp_CreateDate", formatVnpDate(createdAt));
        params.put("vnp_ExpireDate", formatVnpDate(expiresAt));

        String hashData = hashData(params);
        String secureHash = hmac(hashData);
        String separator = payUrl.contains("?") ? "&" : "?";
        return payUrl + separator + hashData + "&vnp_SecureHash=" + secureHash;
    }

    private String nextTxnRef(Long bookingId) {
        for (int attempt = 0; attempt < MAX_TXN_REF_ATTEMPTS; attempt++) {
            String ref =
                    "VNP"
                            + bookingId
                            + "T"
                            + clock.millis()
                            + "R"
                            + String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
            if (!payments.existsByVnpTxnRef(ref)) return ref;
        }
        throw new IllegalStateException("Could not generate a unique VNPay transaction reference");
    }

    private boolean verifySignature(Map<String, String> params) {
        if (params == null || hashSecret.isBlank()) return false;
        String provided = params.get("vnp_SecureHash");
        if (provided == null || provided.isBlank()) return false;

        Map<String, String> signed = new TreeMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            if (key == null
                    || !key.startsWith("vnp_")
                    || "vnp_SecureHash".equals(key)
                    || "vnp_SecureHashType".equals(key)) {
                continue;
            }
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                signed.put(key, entry.getValue());
            }
        }
        String expected = hmac(hashData(signed));
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                provided.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    private boolean tmnMatches(Map<String, String> params) {
        return params != null && tmnCode.equals(params.get("vnp_TmnCode"));
    }

    private static String hashData(Map<String, String> params) {
        List<String> parts = new ArrayList<>(params.size());
        for (Map.Entry<String, String> entry : params.entrySet()) {
            parts.add(entry.getKey() + "=" + encode(entry.getValue()));
        }
        return String.join("&", parts);
    }

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(hashSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign VNPay payload", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String vnpAmount(BigDecimal amount) {
        return amount.movePointRight(2).toBigIntegerExact().toString();
    }

    private static boolean amountMatches(Payment payment, String rawAmount) {
        if (payment.getAmount() == null || rawAmount == null || rawAmount.isBlank()) return false;
        try {
            return new BigDecimal(rawAmount)
                            .compareTo(payment.getAmount().movePointRight(2))
                    == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String formatVnpDate(Instant instant) {
        return instant.atZone(VN_ZONE).format(VNP_DATE);
    }

    private static String clientIp(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()
                || "0:0:0:0:0:0:0:1".equals(ipAddress)
                || "::1".equals(ipAddress)) {
            return "127.0.0.1";
        }
        return ipAddress;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private Booking lockBooking(Long bookingId) {
        if (bookingId == null || bookingId <= 0) throw bookingNotFound();
        return bookings.findByIdForUpdate(bookingId).orElseThrow(VnPayService::bookingNotFound);
    }

    private AgeRating ageRating(Booking booking) {
        Showtime showtime =
                showtimes
                        .findById(booking.getShowtimeId())
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                404,
                                                "SHOWTIME_NOT_FOUND",
                                                "Không tìm thấy suất chiếu"));
        return movies
                .findById(showtime.getMovieId())
                .orElseThrow(
                        () -> new ApiException(404, "MOVIE_NOT_FOUND", "Không tìm thấy phim"))
                .getAgeRating();
    }

    private void requireAgeConfirmation(Booking booking) {
        if (ageRating(booking) != AgeRating.P && booking.getAgeConfirmedAt() == null) {
            throw new ApiException(
                    422,
                    "AGE_CONFIRMATION_REQUIRED",
                    "Khách hàng phải xác nhận đủ tuổi trước khi thanh toán");
        }
    }

    private Instant requireLiveHold(Booking booking) {
        String token = booking.getHoldToken();
        if (token == null || token.isBlank()) throw holdExpired();
        Instant holdUntil =
                inventory.findHoldUntilByHoldToken(token).stream()
                        .filter(Objects::nonNull)
                        .min(Comparator.naturalOrder())
                        .orElse(null);
        if (holdUntil == null || !clock.instant().isBefore(holdUntil)) throw holdExpired();
        return holdUntil;
    }

    private void requirePaymentConfiguration() {
        requireHashConfiguration();
        if (payUrl.isBlank() || returnUrl.isBlank() || ipnUrl.isBlank()) {
            throw configurationMissing();
        }
    }

    private void requireHashConfiguration() {
        if (!hashConfigured()) throw configurationMissing();
    }

    private boolean hashConfigured() {
        return !tmnCode.isBlank() && !hashSecret.isBlank();
    }

    private static void requireCustomer(SessionPrincipal principal) {
        if (principal == null
                || principal.role() != UserRole.CUSTOMER
                || principal.userId() == null) {
            throw new ApiException(
                    403, "ACCESS_DENIED", "Chỉ CUSTOMER được thanh toán online");
        }
    }

    private static void requireOwned(Booking booking, SessionPrincipal principal) {
        if (!Objects.equals(booking.getUserId(), principal.userId())) {
            throw bookingNotFound();
        }
    }

    private static void requireOnline(Booking booking) {
        if (booking.getChannel() != BookingChannel.ONLINE) throw channelInvalid();
    }

    private static void requirePendingBooking(Booking booking) {
        if (booking.getStatus() == BookingStatus.PAID) throw bookingAlreadyPaid();
        if (booking.getStatus() != BookingStatus.PENDING) throw bookingInvalidState();
    }

    private static void requireGatewayMethod(Payment payment) {
        if (payment.getPaymentMethod() != PaymentMethod.GATEWAY_ONLINE) {
            throw new ApiException(
                    409,
                    "PAYMENT_METHOD_CONFLICT",
                    "Booking đã có Payment bằng phương thức khác");
        }
    }

    private static void requirePendingPayment(Payment payment) {
        if (payment.getStatus() != PaymentStatus.PENDING) throw paymentInvalidState();
    }

    private static void requirePaymentAmount(Payment payment, BigDecimal expected) {
        if (payment.getAmount() == null || payment.getAmount().compareTo(expected) != 0) {
            throw paymentAmountMismatch();
        }
    }

    private static String strip(String value) {
        return value == null ? "" : value.strip();
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static VnPayIpnResponse ipn(String code) {
        return switch (code) {
            case "00" -> new VnPayIpnResponse("00", "Confirm Success");
            case "01" -> new VnPayIpnResponse("01", "Order not found");
            case "02" -> new VnPayIpnResponse("02", "Order already confirmed");
            case "04" -> new VnPayIpnResponse("04", "Invalid amount");
            case "97" -> new VnPayIpnResponse("97", "Invalid signature");
            default -> new VnPayIpnResponse("99", "Unknown error");
        };
    }

    private static ApiException configurationMissing() {
        return new ApiException(
                503,
                "PAYMENT_CONFIGURATION_MISSING",
                "Chưa cấu hình VNPay");
    }

    private static ApiException bookingNotFound() {
        return new ApiException(404, "BOOKING_NOT_FOUND", "Không tìm thấy Booking");
    }

    private static ApiException paymentNotFound() {
        return new ApiException(404, "PAYMENT_NOT_FOUND", "Không tìm thấy Payment");
    }

    private static ApiException bookingAlreadyPaid() {
        return new ApiException(409, "BOOKING_ALREADY_PAID", "Booking đã được thanh toán");
    }

    private static ApiException bookingInvalidState() {
        return new ApiException(
                409, "BOOKING_INVALID_STATE", "Booking không ở trạng thái chờ thanh toán");
    }

    private static ApiException channelInvalid() {
        return new ApiException(
                422,
                "BOOKING_CHANNEL_INVALID",
                "Endpoint này chỉ áp dụng cho Booking kênh ONLINE");
    }

    private static ApiException holdExpired() {
        return new ApiException(409, "SEAT_HOLD_EXPIRED", "Phiên giữ ghế đã hết hạn");
    }

    private static ApiException paymentInvalidState() {
        return new ApiException(
                409, "PAYMENT_INVALID_STATE", "Payment không ở trạng thái chờ thanh toán");
    }

    private static ApiException paymentAmountMismatch() {
        return new ApiException(409, "PAYMENT_AMOUNT_MISMATCH", "Số tiền thanh toán không khớp");
    }

    private enum IpnOutcome {
        NOT_FOUND,
        INVALID,
        AMOUNT_MISMATCH,
        TERMINAL,
        FAILED,
        READY
    }

    private enum ExceptionOutcome {
        MARKED,
        ALREADY_MARKED,
        ALREADY_PAID,
        NOT_APPLIED
    }

    private record IpnPreparation(
            IpnOutcome outcome, Long paymentId, Long bookingId, BigDecimal amount) {
        static IpnPreparation notFound() {
            return new IpnPreparation(IpnOutcome.NOT_FOUND, null, null, null);
        }

        static IpnPreparation invalid() {
            return new IpnPreparation(IpnOutcome.INVALID, null, null, null);
        }

        static IpnPreparation amountMismatch() {
            return new IpnPreparation(IpnOutcome.AMOUNT_MISMATCH, null, null, null);
        }

        static IpnPreparation terminal() {
            return new IpnPreparation(IpnOutcome.TERMINAL, null, null, null);
        }

        static IpnPreparation failed(Payment payment, Booking booking) {
            return new IpnPreparation(
                    IpnOutcome.FAILED,
                    payment.getId(),
                    booking.getId(),
                    payment.getAmount());
        }

        static IpnPreparation ready(Payment payment, Booking booking) {
            return new IpnPreparation(
                    IpnOutcome.READY,
                    payment.getId(),
                    booking.getId(),
                    payment.getAmount());
        }
    }
}
