package com.cinema.cms.repository;

import com.cinema.cms.entity.Auditorium;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditoriumRepository extends JpaRepository<Auditorium, Integer> {
    List<Auditorium> findByActive(boolean active, Sort sort);

    @Query("select count(a) from Auditorium a where lower(a.name) = lower(:name)")
    long countByNameLower(@Param("name") String name);

    @Query("select count(a) from Auditorium a where lower(a.name) = lower(:name) and a.id <> :id")
    long countByNameLowerAndIdNot(@Param("name") String name, @Param("id") Integer id);

    default boolean existsByNameLower(String name) { return countByNameLower(name) > 0; }
    default boolean existsByNameLowerAndIdNot(String name, Integer id) { return countByNameLowerAndIdNot(name, id) > 0; }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Auditorium a where a.id = :id")
    Optional<Auditorium> findByIdForUpdate(@Param("id") Integer id);
}