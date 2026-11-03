package com.cinema.cms.repository;

import com.cinema.cms.entity.ConcessionItem;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConcessionItemRepository extends JpaRepository<ConcessionItem, Integer>, JpaSpecificationExecutor<ConcessionItem> {
    Optional<ConcessionItem> findByIdAndCombo(Integer id, boolean combo);

    @Query("select count(i) from ConcessionItem i where lower(i.name) = lower(:name)")
    long countByNameLower(@Param("name") String name);

    @Query("select count(i) from ConcessionItem i where lower(i.name) = lower(:name) and i.id <> :id")
    long countByNameLowerAndIdNot(@Param("name") String name, @Param("id") Integer id);

    default boolean existsByNameLower(String name) { return countByNameLower(name) > 0; }
    default boolean existsByNameLowerAndIdNot(String name, Integer id) { return countByNameLowerAndIdNot(name, id) > 0; }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ConcessionItem i where i.id = :id")
    Optional<ConcessionItem> findByIdForUpdate(@Param("id") Integer id);

    @Query(value = "select count(*) from combo_items ci join concession_items c on c.item_id = ci.combo_id "
            + "where ci.component_item_id = :itemId and c.is_active", nativeQuery = true)
    long countActiveCombosContaining(@Param("itemId") Integer itemId);
}