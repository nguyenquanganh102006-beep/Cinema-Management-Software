package com.cinema.cms.repository;

import com.cinema.cms.entity.Payment;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByBookingId(Long bookingId);

    Optional<Payment> findByVnpTxnRef(String vnpTxnRef);

    Optional<Payment> findByGatewayTransactionCode(String gatewayTransactionCode);
}
