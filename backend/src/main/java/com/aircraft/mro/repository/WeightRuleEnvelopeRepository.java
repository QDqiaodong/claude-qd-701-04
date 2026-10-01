package com.aircraft.mro.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.WeightRuleEnvelope;

public interface WeightRuleEnvelopeRepository extends JpaRepository<WeightRuleEnvelope, Long> {

    List<WeightRuleEnvelope> findByRuleIdAndBoundaryOrderBySeqNoAsc(Long ruleId, String boundary);

    List<WeightRuleEnvelope> findByRuleIdOrderByBoundaryAscSeqNoAsc(Long ruleId);

    void deleteByRuleId(Long ruleId);
}
