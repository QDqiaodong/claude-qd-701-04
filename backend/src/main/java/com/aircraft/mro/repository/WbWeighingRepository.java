package com.aircraft.mro.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.WbWeighing;

public interface WbWeighingRepository extends JpaRepository<WbWeighing, Long> {

    List<WbWeighing> findByAircraftIdOrderByWeighingDateDescIdDesc(Long aircraftId);

    List<WbWeighing> findAllByOrderByIdDesc();

    Optional<WbWeighing> findByAircraftIdAndCurrentFlagTrue(Long aircraftId);

    boolean existsByRuleId(Long ruleId);

    boolean existsByWeighingNo(String weighingNo);

    java.util.Optional<WbWeighing> findByWeighingNo(String weighingNo);
}
