package com.cinema.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.cinema.cms.entity.Payment;
import com.cinema.cms.entity.PaymentMethod;
import com.cinema.cms.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PaymentDomainTest {
    private static final Instant NOW = Instant.parse("2030-01-04T10:00:00Z");

    @Test
    void markPaidAndMarkFailedOnlyWorkFromPending() {
        Payment paid = cash("100000.00");
        paid.markPaid(NOW);
        assertThat(paid.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(paid.getPaidAt()).isEqualTo(NOW);

        assertThrows(IllegalStateException.class, () -> paid.markPaid(NOW));
        assertThrows(IllegalStateException.class, paid::markFailed);

        Payment failed = cash("100000.00");
        failed.markFailed();
        assertThat(failed.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThrows(IllegalStateException.class, () -> failed.markPaid(NOW));
    }

    @Test
    void recordCashRequiresCashMethodAndEnoughMoney() {
        Payment vietQr = new Payment(1L, PaymentMethod.VIETQR, new BigDecimal("100000.00"));
        assertThrows(
                IllegalStateException.class,
                () -> vietQr.recordCash(new BigDecimal("100000.00")));

        Payment cash = cash("100000.00");
        assertThrows(
                IllegalArgumentException.class,
                () -> cash.recordCash(new BigDecimal("99999.99")));
        assertThrows(IllegalArgumentException.class, () -> cash.recordCash(null));

        cash.recordCash(new BigDecimal("100000.5"));
        assertThat(cash.getCashReceived()).isEqualByComparingTo("100000.50");
        assertThat(cash.getCashReceived().scale()).isEqualTo(2);
    }

    @Test
    void confirmByStaffRequiresAPositiveStaffId() {
        Payment payment = cash("100000.00");
        assertThrows(IllegalArgumentException.class, () -> payment.confirmByStaff(null));
        assertThrows(IllegalArgumentException.class, () -> payment.confirmByStaff(0L));

        payment.confirmByStaff(9L);
        assertThat(payment.getConfirmedByStaffId()).isEqualTo(9L);
    }

    @Test
    void gatewayDetailsOnlyWorkForGatewayOnline() {
        Payment cash = cash("100000.00");
        assertThrows(
                IllegalStateException.class,
                () -> cash.attachGateway("TXN", NOW.plusSeconds(60)));
        assertThrows(
                IllegalStateException.class,
                () -> cash.recordGatewayResult("CODE", "SIGNATURE"));

        Payment gateway = new Payment(1L, PaymentMethod.GATEWAY_ONLINE, new BigDecimal("100000.00"));
        gateway.attachGateway("TXN", NOW.plusSeconds(60));
        gateway.recordGatewayResult("CODE", "SIGNATURE");
        gateway.useIdempotencyKey("IDEMPOTENCY");
        gateway.confirmByStaff(7L);
        gateway.markPaid(NOW);

        assertThat(gateway.getVnpTxnRef()).isEqualTo("TXN");
        assertThat(gateway.getExpiresAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(gateway.getGatewayTransactionCode()).isEqualTo("CODE");
        assertThat(gateway.getWebhookSignature()).isEqualTo("SIGNATURE");
        assertThat(gateway.getIdempotencyKey()).isEqualTo("IDEMPOTENCY");
        assertThat(gateway.getStatus()).isEqualTo(PaymentStatus.PAID);

        assertThrows(
                IllegalStateException.class,
                () -> gateway.useIdempotencyKey("ANOTHER"));
    }

    private static Payment cash(String amount) {
        return new Payment(1L, PaymentMethod.CASH, new BigDecimal(amount));
    }
}
