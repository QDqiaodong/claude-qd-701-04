package com.aircraft.mro.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.WbConfigVersion;

public interface WbConfigVersionRepository extends JpaRepository<WbConfigVersion, Long> {

    List<WbConfigVersion> findByAircraftIdOrderByVersionNoAsc(Long aircraftId);

    Optional<WbConfigVersion> findByAircraftIdAndCurrentFlagTrue(Long aircraftId);

    boolean existsByAircraftIdAndParentId(Long aircraftId, Long parentId);

    long countByAircraftId(Long aircraftId);
}
