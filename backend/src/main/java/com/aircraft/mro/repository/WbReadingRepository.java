package com.aircraft.mro.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.WbReading;

public interface WbReadingRepository extends JpaRepository<WbReading, Long> {

    List<WbReading> findByWeighingIdOrderByIdAsc(Long weighingId);

    void deleteByWeighingId(Long weighingId);
}
