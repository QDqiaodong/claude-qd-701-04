package com.aircraft.mro.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.WbTempItem;

public interface WbTempItemRepository extends JpaRepository<WbTempItem, Long> {

    List<WbTempItem> findByWeighingIdOrderByIdAsc(Long weighingId);

    void deleteByWeighingId(Long weighingId);
}
