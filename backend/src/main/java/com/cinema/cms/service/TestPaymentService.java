package com.cinema.cms.service;

import com.cinema.cms.dto.TestPaymentResultResponse;
import com.cinema.cms.dto.CheckoutResult;
import com.cinema.cms.entity.Booking;
import com.cinema.cms.entity.BookingChannel;
import com.cinema.cms.entity.BookingStatus;
import com.cinema.cms.entity.PaymentMethod;
import com.cinema.cms.entity.UserRole;
import com.cinema.cms.repository.BookingRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Local-only payment simulation. Production VNPay IPNs never use this service. */
@Service
@Profile("local")
public class TestPaymentService {
    private final BookingRepository bookings;
    private final CheckoutService checkout;
    private final AuditEventService auditEvents;

    public TestPaymentService(
            BookingRepository bookings, CheckoutService checkout, AuditEventService auditEvents) {
        this.bookings = bookings;
        this.checkout = checkout;
        this.auditEvents = auditEvents;
    }

    public TestPaymentResultResponse complete(Long bookingId, SessionPrincipal principal) {
        if (principal == null || principal.role() != UserRole.CUSTOMER) {
            throw new ApiException(403, "ACCESS_DENIED", "Chỉ khách hàng mới được mô phỏng thanh toán");
        }
        Booking booking = bookings.findById(bookingId).orElseThrow(TestPaymentService::notFound);
        if (!principal.userId().equals(booking.getUserId())) throw notFound();
        if (booking.getChannel() != BookingChannel.ONLINE) {
            throw new ApiException(422, "BOOKING_CHANNEL_INVALID", "Chỉ áp dụng cho đơn đặt vé trực tuyến");
        }
        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new ApiException(409, "BOOKING_INVALID_STATE", "Đơn không còn chờ thanh toán");
        }

        CheckoutResult result = checkout.finalizePaidOnline(
                bookingId, PaymentMethod.GATEWAY_ONLINE, booking.getTotalAmount());
        auditEvents.record(
                principal.userId(),
                "LOCAL_TEST_PAYMENT_COMPLETED",
                "SUCCESS",
                "BOOKING",
                bookingId,
                "paymentMethod=GATEWAY_ONLINE; tickets=" + result.tickets().size());
        return new TestPaymentResultResponse(bookingId, "PAID", result.tickets().size());
    }

    private static ApiException notFound() {
        return new ApiException(404, "BOOKING_NOT_FOUND", "Không tìm thấy đơn đặt vé");
    }
}
