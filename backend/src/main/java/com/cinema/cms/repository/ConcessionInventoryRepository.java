package com.cinema.cms.repository;

import com.cinema.cms.entity.ConcessionInventory;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConcessionInventoryRepository extends JpaRepository<ConcessionInventory, Integer>, JpaSpecificationExecutor<ConcessionInventory> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ConcessionInventory i join fetch i.item where i.itemId = :id")
    Optional<ConcessionInventory> findByIdForUpdate(@Param("id") Integer id);

    @Query("select i from ConcessionInventory i join fetch i.item c where c.active = true and i.stockQuantity <= i.lowStockThreshold order by i.stockQuantity asc, c.name asc")
    List<ConcessionInventory> findAlerts();
}