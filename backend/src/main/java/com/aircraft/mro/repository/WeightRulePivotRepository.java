package com.aircraft.mro.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.WeightRulePivot;

public interface WeightRulePivotRepository extends JpaRepository<WeightRulePivot, Long> {

    List<WeightRulePivot> findByRuleIdOrderByDisplayOrderAsc(Long ruleId);

    void deleteByRuleId(Long ruleId);
}
