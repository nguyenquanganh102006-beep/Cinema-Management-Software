package com.cinema.cms.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
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

    public void markPaid(Instant now) {
        this.status = PaymentStatus.PAID;
        this.paidAt = now;
    }

    public void markFailed() { this.status = PaymentStatus.FAILED; }

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
