package com.cinema.cms.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "payments")
public class Payment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_id") private Long id;
    @Column(name = "booking_id", nullable = false) private Long bookingId;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "payment_method", nullable = false, columnDefinition = "payment_method_enum") private PaymentMethod paymentMethod;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal amount;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "payment_status_enum") private PaymentStatus status;
    @Column(name = "gateway_transaction_code", length = 100) private String gatewayTransactionCode;
    @Column(name = "idempotency_key", length = 100) private String idempotencyKey;
    @Column(name = "webhook_signature", length = 255) private String webhookSignature;
    @Column(name = "paid_at") private Instant paidAt;
    @Column(name = "vnp_txn_ref", length = 100) private String vnpTxnRef;
    @Column(name = "expires_at") private Instant expiresAt;
    @Column(name = "confirmed_by_staff_id") private Long confirmedByStaffId;
    @Column(name = "cash_received", precision = 12, scale = 2) private BigDecimal cashReceived;

    protected Payment() {}

    public Payment(Long bookingId, PaymentMethod paymentMethod, BigDecimal amount) {
        this.bookingId = bookingId;
        this.paymentMethod = paymentMethod;
        this.amount = amount;
        this.status = PaymentStatus.PENDING;
    }

    /** Records the cash the customer handed over. Only valid for a PENDING CASH payment. */
    public void recordCash(BigDecimal received) {
        requirePending();
        if (paymentMethod != PaymentMethod.CASH)
            throw new IllegalStateException("Cash can only be recorded on a CASH payment");
        if (received == null || received.signum() < 0)
            throw new IllegalArgumentException("cashReceived must be non-negative");
        if (received.compareTo(amount) < 0)
            throw new IllegalArgumentException("cashReceived must cover the payment amount");
        this.cashReceived = received.setScale(2, RoundingMode.HALF_UP);
    }

    /** Records which STAFF member confirmed the payment at the counter. */
    public void confirmByStaff(Long staffId) {
        requirePending();
        if (staffId == null || staffId <= 0)
            throw new IllegalArgumentException("staffId must be positive");
        this.confirmedByStaffId = staffId;
    }

    /** Attaches the gateway reference before a VNPay redirect. GATEWAY_ONLINE only. */
    public void attachGateway(String txnRef, Instant expiresAt) {
        requirePending();
        if (paymentMethod != PaymentMethod.GATEWAY_ONLINE)
            throw new IllegalStateException("Gateway details are only valid for GATEWAY_ONLINE");
        if (txnRef == null || txnRef.isBlank())
            throw new IllegalArgumentException("txnRef is required");
        if (expiresAt == null) throw new IllegalArgumentException("expiresAt is required");
        this.vnpTxnRef = txnRef;
        this.expiresAt = expiresAt;
    }

    /** Records the verified gateway result. GATEWAY_ONLINE only; the caller verifies the signature. */
    public void recordGatewayResult(String code, String signature) {
        requirePending();
        if (paymentMethod != PaymentMethod.GATEWAY_ONLINE)
            throw new IllegalStateException("Gateway result is only valid for GATEWAY_ONLINE");
        if (code == null || code.isBlank())
            throw new IllegalArgumentException("gateway transaction code is required");
        if (signature == null || signature.isBlank())
            throw new IllegalArgumentException("webhook signature is required");
        this.gatewayTransactionCode = code;
        this.webhookSignature = signature;
    }

    /** Stores the provider idempotency key while the payment is still open. */
    public void useIdempotencyKey(String key) {
        requirePending();
        if (key == null || key.isBlank())
            throw new IllegalArgumentException("idempotencyKey is required");
        this.idempotencyKey = key;
    }

    /** PENDING -> PAID. Any other source state is a programming error, not a normal retry. */
    public void markPaid(Instant now) {
        requirePending();
        if (now == null) throw new IllegalArgumentException("paidAt is required");
        this.status = PaymentStatus.PAID;
        this.paidAt = now;
    }

    /** PENDING -> FAILED. The service must check the current state before calling. */
    public void markFailed() {
        requirePending();
        this.status = PaymentStatus.FAILED;
    }

    private void requirePending() {
        if (status != PaymentStatus.PENDING)
            throw new IllegalStateException("Payment is not PENDING");
    }

    public Long getId() { return id; }
    public Long getBookingId() { return bookingId; }
    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public BigDecimal getAmount() { return amount; }
    public PaymentStatus getStatus() { return status; }
    public String getGatewayTransactionCode() { return gatewayTransactionCode; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getWebhookSignature() { return webhookSignature; }
    public Instant getPaidAt() { return paidAt; }
    public String getVnpTxnRef() { return vnpTxnRef; }
    public Instant getExpiresAt() { return expiresAt; }
    public Long getConfirmedByStaffId() { return confirmedByStaffId; }
    public BigDecimal getCashReceived() { return cashReceived; }
}
