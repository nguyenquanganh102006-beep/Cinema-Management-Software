package com.cinema.cms.repository;

import com.cinema.cms.entity.Payment;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByBookingId(Long bookingId);

    Optional<Payment> findByVnpTxnRef(String vnpTxnRef);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.vnpTxnRef = :vnpTxnRef")
    Optional<Payment> findByVnpTxnRefForUpdate(@Param("vnpTxnRef") String vnpTxnRef);

    Optional<Payment> findByGatewayTransactionCode(String gatewayTransactionCode);

    boolean existsByVnpTxnRef(String vnpTxnRef);
}
