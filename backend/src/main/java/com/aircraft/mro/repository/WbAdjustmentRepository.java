package com.aircraft.mro.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.WbAdjustment;

public interface WbAdjustmentRepository extends JpaRepository<WbAdjustment, Long> {

    List<WbAdjustment> findByVersionIdOrderByIdAsc(Long versionId);
}
