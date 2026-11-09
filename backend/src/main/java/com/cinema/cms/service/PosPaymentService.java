package com.cinema.cms.service;

import com.cinema.cms.dto.*;
import com.cinema.cms.entity.*;
import com.cinema.cms.repository.*;
import java.math.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * POS payment flows for STAFF. Every flow locks the Booking before touching Payment, so two
 * concurrent counter requests are serialized by the Booking row instead of racing on the
 * {@code UNIQUE(payments.booking_id)} constraint and surfacing a generic RESOURCE_CONFLICT.
 */
@Service
public class PosPaymentService {
    private static final Logger audit = LoggerFactory.getLogger("AUDIT_PAYMENT");
    private static final String TRANSFER_PREFIX = "CINEMA ";

    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final AgeVerificationRepository ageVerifications;
    private final ShowtimeRepository showtimes;
    private final MovieRepository movies;
    private final CheckoutService checkout;
    private final Clock clock;
    private final String bankAccount;
    private final String bankName;

    public PosPaymentService(
            BookingRepository bookings,
            PaymentRepository payments,
            AgeVerificationRepository ageVerifications,
            ShowtimeRepository showtimes,
            MovieRepository movies,
            CheckoutService checkout,
            Clock clock,
            @Value("${app.payments.vietqr.bank-account:}") String bankAccount,
            @Value("${app.payments.vietqr.bank-name:}") String bankName) {
        this.bookings = bookings;
        this.payments = payments;
        this.ageVerifications = ageVerifications;
        this.showtimes = showtimes;
        this.movies = movies;
        this.checkout = checkout;
        this.clock = clock;
        this.bankAccount = bankAccount == null ? "" : bankAccount.strip();
        this.bankName = bankName == null ? "" : bankName.strip();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CashPaymentResponse payCash(
            Long bookingId, CashPaymentRequest request, SessionPrincipal principal) {
        Long staffId = principal == null ? null : principal.userId();
        try {
            requireStaff(principal);
            if (request == null || request.cashReceived() == null)
                throw new ApiException(400, "VALIDATION_ERROR", "Số tiền khách đưa là bắt buộc");

            // Lock first. The PENDING Payment is created only after the Booking row is ours.
            Booking booking = lockBooking(bookingId);
            requirePos(booking);
            requirePendingBooking(booking);

            AgeRating ageRating = ageRating(booking);
            requireAgeDocument(request.ageDocumentChecked(), ageRating);

            BigDecimal totalAmount = money(booking.getTotalAmount());
            BigDecimal cashReceived = money(request.cashReceived());
            if (cashReceived.compareTo(totalAmount) < 0) throw cashInsufficient();

            Payment payment = payments.findByBookingId(bookingId).orElse(null);
            if (payment == null) {
                payment = new Payment(bookingId, PaymentMethod.CASH, totalAmount);
            } else {
                requirePaymentMethod(payment, PaymentMethod.CASH);
                requirePendingPayment(payment);
                requirePaymentAmount(payment, totalAmount);
            }
            payment.recordCash(cashReceived);
            payment.confirmByStaff(staffId);
            payments.saveAndFlush(payment);

            if (ageRating != AgeRating.P) {
                ageVerifications.save(AgeVerification.pos(bookingId, staffId, clock));
            }

            CheckoutResult result =
                    checkout.finalizePaid(bookingId, PaymentMethod.CASH, totalAmount);
            BigDecimal change =
                    money(result.payment().getCashReceived().subtract(result.payment().getAmount()));
            audit.info(
                    "action=CASH_PAYMENT result=PAID bookingId={} bookingCode={} staffId={}"
                            + " paymentId={} amount={} cashReceived={} change={} tickets={}",
                    bookingId,
                    result.booking().getBookingCode(),
                    staffId,
                    result.payment().getId(),
                    result.payment().getAmount(),
                    result.payment().getCashReceived(),
                    change,
                    result.tickets().size());
            return cashResponse(result);
        } catch (ApiException e) {
            audit.warn(
                    "action=CASH_PAYMENT result=REJECTED bookingId={} staffId={} code={}",
                    bookingId,
                    staffId,
                    e.getCode());
            throw e;
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public VietQrPaymentResponse createVietQr(
            Long bookingId, VietQrPaymentRequest request, SessionPrincipal principal) {
        Long staffId = principal == null ? null : principal.userId();
        try {
            requireStaff(principal);

            Booking booking = lockBooking(bookingId);
            requirePos(booking);
            requirePendingBooking(booking);

            AgeRating ageRating = ageRating(booking);
            requireAgeDocument(
                    request == null ? null : request.ageDocumentChecked(), ageRating);
            requireBankConfiguration();

            BigDecimal totalAmount = money(booking.getTotalAmount());
            Payment payment = payments.findByBookingId(bookingId).orElse(null);
            if (payment == null) {
                payment = new Payment(bookingId, PaymentMethod.VIETQR, totalAmount);
                payments.saveAndFlush(payment);
            } else {
                requirePaymentMethod(payment, PaymentMethod.VIETQR);
                requirePendingPayment(payment);
                requirePaymentAmount(payment, totalAmount);
            }

            if (ageRating != AgeRating.P
                    && !ageVerifications.existsByBookingIdAndVerificationStage(
                            bookingId, AgeVerification.POS)) {
                ageVerifications.save(AgeVerification.pos(bookingId, staffId, clock));
            }

            String transferContent = TRANSFER_PREFIX + booking.getBookingCode();
            String qrPayload =
                    qrPayload(bankName, bankAccount, money(payment.getAmount()), transferContent);
            audit.info(
                    "action=VIETQR_CREATED result=PENDING bookingId={} bookingCode={} staffId={}"
                            + " paymentId={} amount={}",
                    bookingId,
                    booking.getBookingCode(),
                    staffId,
                    payment.getId(),
                    payment.getAmount());
            return new VietQrPaymentResponse(
                    payment.getId(),
                    bookingId,
                    booking.getBookingCode(),
                    money(payment.getAmount()),
                    bankAccount,
                    bankName,
                    transferContent,
                    qrPayload,
                    payment.getStatus().name());
        } catch (ApiException e) {
            audit.warn(
                    "action=VIETQR_CREATED result=REJECTED bookingId={} staffId={} code={}",
                    bookingId,
                    staffId,
                    e.getCode());
            throw e;
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public VietQrConfirmResponse confirmVietQr(Long bookingId, SessionPrincipal principal) {
        Long staffId = principal == null ? null : principal.userId();
        try {
            requireStaff(principal);

            Booking booking = lockBooking(bookingId);
            requirePos(booking);
            requirePendingBooking(booking);

            AgeRating ageRating = ageRating(booking);
            if (ageRating != AgeRating.P
                    && !ageVerifications.existsByBookingIdAndVerificationStage(
                            bookingId, AgeVerification.POS)) {
                throw ageVerificationRequired();
            }

            BigDecimal totalAmount = money(booking.getTotalAmount());
            Payment payment =
                    payments
                            .findByBookingId(bookingId)
                            .orElseThrow(PosPaymentService::paymentNotFound);
            requirePaymentMethod(payment, PaymentMethod.VIETQR);
            requirePendingPayment(payment);
            requirePaymentAmount(payment, totalAmount);

            payment.confirmByStaff(staffId);
            payments.saveAndFlush(payment);

            CheckoutResult result =
                    checkout.finalizePaid(bookingId, PaymentMethod.VIETQR, totalAmount);
            audit.info(
                    "action=VIETQR_CONFIRMED result=PAID bookingId={} bookingCode={} staffId={}"
                            + " paymentId={} amount={} tickets={}",
                    bookingId,
                    result.booking().getBookingCode(),
                    staffId,
                    result.payment().getId(),
                    result.payment().getAmount(),
                    result.tickets().size());
            return new VietQrConfirmResponse(
                    result.payment().getId(),
                    result.booking().getId(),
                    result.booking().getBookingCode(),
                    money(result.payment().getAmount()),
                    result.payment().getStatus().name(),
                    result.payment().getConfirmedByStaffId(),
                    result.payment().getPaidAt(),
                    ticketResponses(result.tickets()));
        } catch (ApiException e) {
            audit.warn(
                    "action=VIETQR_CONFIRMED result=REJECTED bookingId={} staffId={} code={}",
                    bookingId,
                    staffId,
                    e.getCode());
            throw e;
        }
    }

    private Booking lockBooking(Long bookingId) {
        if (bookingId == null || bookingId <= 0) throw bookingNotFound();
        return bookings
                .findByIdForUpdate(bookingId)
                .orElseThrow(PosPaymentService::bookingNotFound);
    }

    private static void requireStaff(SessionPrincipal principal) {
        if (principal == null
                || principal.role() != UserRole.STAFF
                || principal.userId() == null) {
            throw new ApiException(403, "ACCESS_DENIED", "Chỉ STAFF được thanh toán tại quầy");
        }
    }

    private static void requirePos(Booking booking) {
        if (booking.getChannel() != BookingChannel.POS) throw bookingChannelInvalid();
    }

    private static void requirePendingBooking(Booking booking) {
        if (booking.getStatus() == BookingStatus.PAID) throw bookingAlreadyPaid();
        if (booking.getStatus() != BookingStatus.PENDING) throw bookingInvalidState();
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

    private static void requireAgeDocument(Boolean ageDocumentChecked, AgeRating ageRating) {
        if (ageRating != AgeRating.P && !Boolean.TRUE.equals(ageDocumentChecked)) {
            throw ageVerificationRequired();
        }
    }

    private void requireBankConfiguration() {
        if (bankAccount.isBlank() || bankName.isBlank()) {
            throw new ApiException(
                    503,
                    "PAYMENT_CONFIGURATION_MISSING",
                    "Chưa cấu hình tài khoản nhận chuyển khoản");
        }
    }

    private static void requirePaymentMethod(Payment payment, PaymentMethod expected) {
        if (payment.getPaymentMethod() != expected) throw paymentMethodConflict();
    }

    private static void requirePendingPayment(Payment payment) {
        if (payment.getStatus() != PaymentStatus.PENDING) throw paymentInvalidState();
    }

    private static void requirePaymentAmount(Payment payment, BigDecimal expected) {
        if (payment.getAmount() == null || payment.getAmount().compareTo(expected) != 0) {
            throw paymentAmountMismatch();
        }
    }

    private static CashPaymentResponse cashResponse(CheckoutResult result) {
        Payment payment = result.payment();
        BigDecimal cashReceived = money(payment.getCashReceived());
        return new CashPaymentResponse(
                payment.getId(),
                result.booking().getId(),
                result.booking().getBookingCode(),
                money(result.booking().getTotalAmount()),
                cashReceived,
                money(cashReceived.subtract(payment.getAmount())),
                payment.getStatus().name(),
                payment.getPaidAt(),
                ticketResponses(result.tickets()));
    }

    private static List<PaymentTicketResponse> ticketResponses(List<Ticket> tickets) {
        return tickets.stream()
                .map(
                        ticket ->
                                new PaymentTicketResponse(
                                        ticket.getId(),
                                        ticket.getTicketCode(),
                                        ticket.getShowtimeSeatId(),
                                        money(ticket.getPriceSnapshot()),
                                        ticket.getStatus().name(),
                                        ticket.getIssuedAt()))
                .toList();
    }

    private static String qrPayload(
            String bankName, String bankAccount, BigDecimal amount, String transferContent) {
        return "vietqr://transfer?bankName="
                + encode(bankName)
                + "&account="
                + encode(bankAccount)
                + "&amount="
                + amount.toPlainString()
                + "&content="
                + encode(transferContent);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static ApiException bookingNotFound() {
        return new ApiException(404, "BOOKING_NOT_FOUND", "Không tìm thấy Booking");
    }

    private static ApiException bookingAlreadyPaid() {
        return new ApiException(409, "BOOKING_ALREADY_PAID", "Booking đã được thanh toán");
    }

    private static ApiException bookingInvalidState() {
        return new ApiException(
                409, "BOOKING_INVALID_STATE", "Booking không ở trạng thái chờ thanh toán");
    }

    private static ApiException bookingChannelInvalid() {
        return new ApiException(
                422, "BOOKING_CHANNEL_INVALID", "Endpoint này chỉ áp dụng cho Booking kênh POS");
    }

    private static ApiException ageVerificationRequired() {
        return new ApiException(
                422,
                "AGE_VERIFICATION_REQUIRED",
                "Nhân viên phải xác nhận đã kiểm tra giấy tờ đủ tuổi");
    }

    private static ApiException cashInsufficient() {
        return new ApiException(
                422, "PAYMENT_CASH_INSUFFICIENT", "Số tiền khách đưa không đủ thanh toán");
    }

    private static ApiException paymentMethodConflict() {
        return new ApiException(
                409,
                "PAYMENT_METHOD_CONFLICT",
                "Booking đã có Payment bằng phương thức khác");
    }

    private static ApiException paymentInvalidState() {
        return new ApiException(
                409, "PAYMENT_INVALID_STATE", "Payment không ở trạng thái chờ thanh toán");
    }

    private static ApiException paymentAmountMismatch() {
        return new ApiException(409, "PAYMENT_AMOUNT_MISMATCH", "Số tiền thanh toán không khớp");
    }

    private static ApiException paymentNotFound() {
        return new ApiException(404, "PAYMENT_NOT_FOUND", "Không tìm thấy Payment");
    }
}
