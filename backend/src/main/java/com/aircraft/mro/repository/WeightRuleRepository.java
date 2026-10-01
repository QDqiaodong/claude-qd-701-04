package com.aircraft.mro.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.WeightRule;

public interface WeightRuleRepository extends JpaRepository<WeightRule, Long> {

    List<WeightRule> findAllByOrderByModelAscVersionNoDesc();

    List<WeightRule> findByModelOrderByVersionNoDesc(String model);

    Optional<WeightRule> findByModelAndVersionNo(String model, Integer versionNo);

    boolean existsByRuleNo(String ruleNo);

    Optional<WeightRule> findByModelAndStatus(String model, String status);

    /**
     * 解析称重日生效规则：在所有【已发布】版本（EFFECTIVE 生效中 + SUPERSEDED 已被取代）中，
     * 取生效日期不晚于该日期的最新一条。旧版虽被新版取代，但在其生效区间内的历史称重仍必须追溯到它。
     */
    Optional<WeightRule> findFirstByModelAndStatusInAndEffectiveDateLessThanEqualOrderByEffectiveDateDescVersionNoDesc(
            String model, List<String> statuses, LocalDate effectiveDate);
}
