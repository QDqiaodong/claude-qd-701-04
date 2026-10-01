package com.aircraft.mro.service;

import static com.aircraft.mro.service.WeightBalanceEngine.UNIT_KG;
import static com.aircraft.mro.service.WeightBalanceEngine.UNIT_LB;
import static com.aircraft.mro.service.WeightBalanceEngine.UNIT_MM;
import static com.aircraft.mro.service.WeightBalanceEngine.UNIT_IN;
import static com.aircraft.mro.service.WeightBalanceEngine.BOUNDARY_AFT;
import static com.aircraft.mro.service.WeightBalanceEngine.BOUNDARY_FORWARD;
import static com.aircraft.mro.service.WeightBalanceEngine.IMPERIAL;
import static com.aircraft.mro.service.WeightBalanceEngine.METRIC;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aircraft.mro.common.BizException;
import com.aircraft.mro.domain.MroDict;
import com.aircraft.mro.domain.WeightRule;
import com.aircraft.mro.domain.WeightRuleEnvelope;
import com.aircraft.mro.domain.WeightRulePivot;
import com.aircraft.mro.domain.WbConfigVersion;
import com.aircraft.mro.domain.WbWeighing;
import com.aircraft.mro.dto.WeightDtos;
import com.aircraft.mro.dto.WeightViews;
import com.aircraft.mro.repository.WbConfigVersionRepository;
import com.aircraft.mro.repository.WbWeighingRepository;
import com.aircraft.mro.repository.WeightRuleEnvelopeRepository;
import com.aircraft.mro.repository.WeightRulePivotRepository;
import com.aircraft.mro.repository.WeightRuleRepository;

/**
 * 称重规则版本服务：草稿登记、发布生效、按称重日解析、只读追溯。
 *
 * <p>规则一旦被任何称重/构型版本引用即永久冻结（不可改、不可删、不可重复发布）；
 * 新版发布只把同机型旧 EFFECTIVE 置为 SUPERSEDED，绝不改动历史引用。
 */
@Service
public class WeightRuleService {

    private final WeightRuleRepository ruleRepository;
    private final WeightRulePivotRepository pivotRepository;
    private final WeightRuleEnvelopeRepository envelopeRepository;
    private final WbWeighingRepository weighingRepository;
    private final WbConfigVersionRepository configVersionRepository;

    public WeightRuleService(WeightRuleRepository ruleRepository,
                             WeightRulePivotRepository pivotRepository,
                             WeightRuleEnvelopeRepository envelopeRepository,
                             WbWeighingRepository weighingRepository,
                             WbConfigVersionRepository configVersionRepository) {
        this.ruleRepository = ruleRepository;
        this.pivotRepository = pivotRepository;
        this.envelopeRepository = envelopeRepository;
        this.weighingRepository = weighingRepository;
        this.configVersionRepository = configVersionRepository;
    }

    @Transactional(readOnly = true)
    public List<WeightViews.RuleListItem> listRules(String model) {
        List<WeightRule> rules = model == null || model.isBlank()
                ? ruleRepository.findAllByOrderByModelAscVersionNoDesc()
                : ruleRepository.findByModelOrderByVersionNoDesc(model.trim().toUpperCase());
        Map<Long, Boolean> referenced = referencedRuleIds();
        List<WeightViews.RuleListItem> list = new ArrayList<>();
        for (WeightRule rule : rules) {
            list.add(new WeightViews.RuleListItem(
                    rule.getId(), rule.getRuleNo(), rule.getModel(), rule.getVersionNo(),
                    rule.getStatus(), MroDict.ruleStatusName(rule.getStatus()),
                    rule.getEffectiveDate(), rule.getUnitSystem(),
                    MroDict.wbUnitSystemName(rule.getUnitSystem()),
                    rule.getMinWeight(), rule.getMaxWeight(), rule.getForwardNodeCount(),
                    referenced.getOrDefault(rule.getId(), false), rule.getCreatedAt()));
        }
        return list;
    }

    @Transactional(readOnly = true)
    public WeightViews.RuleView getRule(Long id) {
        WeightRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new BizException("称重规则不存在：id=" + id));
        return toView(rule, referencedRuleIds().getOrDefault(id, false));
    }

    /**
     * 按机型 + 称重日期解析当时生效规则（EFFECTIVE、生效日期不晚于称重日中最新的一条）。
     * 这是每次称重/构型判定唯一允许引用规则的入口，保证旧单据可追溯到旧版本。
     */
    @Transactional(readOnly = true)
    public WeightRule resolveRule(String model, LocalDate date) {
        String m = model.trim().toUpperCase();
        if (!MroDict.isValidModel(m)) {
            throw new BizException("未知机型：" + model);
        }
        // 在所有已发布版本（含已被取代的旧版）里按称重日解析，保证历史日期追溯到旧规则
        return ruleRepository
                .findFirstByModelAndStatusInAndEffectiveDateLessThanEqualOrderByEffectiveDateDescVersionNoDesc(
                        m, List.of(MroDict.RULE_EFFECTIVE, MroDict.RULE_SUPERSEDED), date)
                .orElseThrow(() -> new BizException(
                        "机型 " + m + " 在 " + date + " 及以前没有已发布的称重规则，不能登记该日期的称重"));
    }

    @Transactional(readOnly = true)
    public WeightViews.RuleResolvedView resolveView(String model, LocalDate date) {
        WeightRule rule = resolveRule(model, date);
        return new WeightViews.RuleResolvedView(rule.getModel(), date, rule.getId(), rule.getRuleNo(),
                rule.getVersionNo(), MroDict.ruleStatusName(rule.getStatus()),
                "称重日 " + date + " 将引用 " + rule.getRuleNo() + "（"
                        + rule.getEffectiveDate() + " 生效）");
    }

    /** 登记一份草稿规则（同机型版本号自增）。 */
    @Transactional
    public WeightViews.RuleView createDraft(WeightDtos.RuleSaveReq req) {
        String model = req.getModel().trim().toUpperCase();
        if (!MroDict.isValidModel(model)) {
            throw new BizException("机型只能是 B737 / A320 / A330 / ARJ21，收到：" + req.getModel());
        }
        String unitSystem = normalizeUnitSystem(req.getUnitSystem());

        WeightRule rule = new WeightRule();
        rule.setModel(model);
        int nextNo = ruleRepository.findByModelOrderByVersionNoDesc(model).stream()
                .mapToInt(WeightRule::getVersionNo).max().orElse(0) + 1;
        rule.setVersionNo(nextNo);
        rule.setRuleNo("WR-" + model + "-V" + nextNo);
        rule.setStatus(MroDict.RULE_DRAFT);
        applyRuleFields(rule, req, unitSystem);
        LocalDateTime now = LocalDateTime.now();
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        rule = ruleRepository.save(rule);

        saveChildren(rule.getId(), req, unitSystem);
        return getRule(rule.getId());
    }

    /** 草稿可改（未被引用是天然保证：草稿不参与称重解析）；生效/已取代版本一律拒绝。 */
    @Transactional
    public WeightViews.RuleView updateDraft(Long id, WeightDtos.RuleSaveReq req) {
        WeightRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new BizException("称重规则不存在：id=" + id));
        if (!MroDict.RULE_DRAFT.equals(rule.getStatus())) {
            throw new BizException(rule.getRuleNo() + " 已是「" + MroDict.ruleStatusName(rule.getStatus())
                    + "」，历史/生效规则不得修改；请在该机型上新建版本");
        }
        String model = req.getModel().trim().toUpperCase();
        if (!model.equals(rule.getModel())) {
            throw new BizException("规则机型不能变更（应始终为 " + rule.getModel() + "）");
        }
        String unitSystem = normalizeUnitSystem(req.getUnitSystem());
        applyRuleFields(rule, req, unitSystem);
        rule.setUpdatedAt(LocalDateTime.now());
        ruleRepository.save(rule);

        pivotRepository.deleteByRuleId(id);
        pivotRepository.flush();
        envelopeRepository.deleteByRuleId(id);
        envelopeRepository.flush();
        saveChildren(id, req, unitSystem);
        return getRule(id);
    }

    /** 发布草稿：生效日期落定，同机型原 EFFECTIVE 立即冻结为 SUPERSEDED。 */
    @Transactional
    public WeightViews.RuleView publish(Long id, WeightDtos.RulePublishReq req) {
        WeightRule rule = ruleRepository.findById(id)
                .orElseThrow(() -> new BizException("称重规则不存在：id=" + id));
        if (!MroDict.RULE_DRAFT.equals(rule.getStatus())) {
            throw new BizException(rule.getRuleNo() + " 当前为「" + MroDict.ruleStatusName(rule.getStatus())
                    + "」，只有草稿可以发布生效");
        }
        LocalDate effective = req.getEffectiveDate() != null ? req.getEffectiveDate() : rule.getEffectiveDate();
        if (effective == null) {
            throw new BizException("生效日期不能为空");
        }
        rule.setEffectiveDate(effective);
        rule.setStatus(MroDict.RULE_EFFECTIVE);
        rule.setUpdatedAt(LocalDateTime.now());
        ruleRepository.save(rule);

        // 同机型旧生效版中、生效日不晚于新版的立即冻结；
        // 生效日更晚的规则保持现状（例如补录一个历史版本，不应把未来版置为已取代）。
        // 历史称重仍按其 rule_id 引用旧版，不受影响。
        for (WeightRule old : ruleRepository.findByModelOrderByVersionNoDesc(rule.getModel())) {
            if (!old.getId().equals(rule.getId())
                    && MroDict.RULE_EFFECTIVE.equals(old.getStatus())
                    && !old.getEffectiveDate().isAfter(effective)) {
                old.setStatus(MroDict.RULE_SUPERSEDED);
                old.setUpdatedAt(LocalDateTime.now());
                ruleRepository.save(old);
            }
        }
        return getRule(id);
    }

    /** 被任何称重单或构型版本引用过的规则 id 集合（引用即冻结）。 */
    @Transactional(readOnly = true)
    public Map<Long, Boolean> referencedRuleIds() {
        Map<Long, Boolean> map = new HashMap<>();
        for (WbWeighing w : weighingRepository.findAll()) {
            map.put(w.getRuleId(), true);
        }
        for (WbConfigVersion v : configVersionRepository.findAll()) {
            map.put(v.getRuleId(), true);
        }
        return map;
    }

    // ---------------------------------------------------------------- 内部

    private void applyRuleFields(WeightRule rule, WeightDtos.RuleSaveReq req, String unitSystem) {
        LocalDate date = req.getEffectiveDate();
        if (date == null) {
            throw new BizException("生效日期不能为空");
        }
        String wUnit = METRIC.equals(unitSystem) ? UNIT_KG : UNIT_LB;
        String aUnit = METRIC.equals(unitSystem) ? UNIT_MM : UNIT_IN;

        BigDecimal min = WeightBalanceEngine.toKg(req.getMinWeight(), wUnit);
        BigDecimal max = WeightBalanceEngine.toKg(req.getMaxWeight(), wUnit);
        if (min == null || max == null || min.signum() <= 0 || max.compareTo(min) <= 0) {
            throw new BizException("许用总重范围非法：最小、最大总重必须为正，且最大值大于最小值");
        }

        List<WeightDtos.PivotReq> pivots = req.getPivots() == null ? List.of() : req.getPivots();
        validatePivots(pivots, aUnit);

        List<NodeInput> fwd = validateNodes(req.getForwardNodes(), wUnit, aUnit);
        List<NodeInput> aft = validateNodes(req.getAftNodes(), wUnit, aUnit);
        // 节点重量范围必须完整覆盖许用总重区间（端点对齐），否则总重合法处可能无法插值
        requireCoversRange(fwd, min, max, "前边界");
        requireCoversRange(aft, min, max, "后边界");
        for (int i = 0; i < fwd.size(); i++) {
            BigDecimal fa = fwd.get(i).armMm();
            BigDecimal aa = aft.get(i).armMm();
            if (fa.compareTo(aa) > 0) {
                throw new BizException("规则自相矛盾：第 " + (i + 1) + " 个重量节点处前边界臂长 "
                        + fa + "mm 大于后边界 " + aa + "mm");
            }
        }

        rule.setEffectiveDate(date);
        rule.setUnitSystem(unitSystem);
        rule.setDatumName(trim(req.getDatumName()));
        rule.setDatumNote(trim(req.getDatumNote()));
        rule.setMinWeight(min);
        rule.setMaxWeight(max);
        rule.setMinWeightOriginal(req.getMinWeight());
        rule.setMaxWeightOriginal(req.getMaxWeight());
        rule.setForwardNodeCount(fwd.size());
    }

    private void validatePivots(List<WeightDtos.PivotReq> pivots, String armUnit) {
        if (pivots.size() < 2) {
            throw new BizException("至少需要 2 个起落架支点（如前起 + 主起）");
        }
        Set<String> codes = new HashSet<>();
        for (WeightDtos.PivotReq p : pivots) {
            String code = p.getPivotCode().trim().toUpperCase();
            if (!codes.add(code)) {
                throw new BizException("支点代码重复：" + code);
            }
            if (p.getArm() == null || WeightBalanceEngine.isIllegallyNegative(p.getArm())) {
                throw new BizException("支点 " + code + " 臂长缺失或为负数");
            }
            WeightBalanceEngine.toMm(p.getArm(), armUnit); // 换算非法/精度检查
        }
    }

    private record NodeInput(BigDecimal weightKg, BigDecimal armMm,
                             BigDecimal weightOriginal, BigDecimal armOriginal, int seq) {
    }

    private List<NodeInput> validateNodes(List<WeightDtos.NodeReq> nodes, String wUnit, String aUnit) {
        if (nodes == null || nodes.size() < 2) {
            throw new BizException("每条重心边界折线至少需要 2 个重量节点");
        }
        List<NodeInput> out = new ArrayList<>();
        BigDecimal prev = null;
        int seq = 1;
        for (WeightDtos.NodeReq n : nodes) {
            if (n.getWeight() == null || n.getArm() == null
                    || WeightBalanceEngine.isIllegallyNegative(n.getWeight())
                    || WeightBalanceEngine.isIllegallyNegative(n.getArm())) {
                throw new BizException("包线节点存在缺失或负数：第 " + seq + " 个节点");
            }
            BigDecimal w = WeightBalanceEngine.toKg(n.getWeight(), wUnit);
            BigDecimal a = WeightBalanceEngine.toMm(n.getArm(), aUnit);
            if (prev != null && w.compareTo(prev) <= 0) {
                throw new BizException("包线节点重量必须严格升序，第 " + seq + " 个节点未递增");
            }
            out.add(new NodeInput(w, a, n.getWeight(), n.getArm(), seq));
            prev = w;
            seq++;
        }
        return out;
    }

    private void requireCoversRange(List<NodeInput> nodes, BigDecimal min, BigDecimal max, String label) {
        BigDecimal first = nodes.get(0).weightKg();
        BigDecimal last = nodes.get(nodes.size() - 1).weightKg();
        if (first.compareTo(min) > 0) {
            throw new BizException(label + "折线首个节点重量 " + first + "kg 必须不晚于许用最小总重 "
                    + min + "kg 开始，否则低重量段无法插值判定");
        }
        if (last.compareTo(max) < 0) {
            throw new BizException(label + "折线末个节点重量 " + last + "kg 必须不早于许用最大总重 "
                    + max + "kg 结束，否则高重量段无法插值判定");
        }
    }

    private void saveChildren(Long ruleId, WeightDtos.RuleSaveReq req, String unitSystem) {
        String wUnit = METRIC.equals(unitSystem) ? UNIT_KG : UNIT_LB;
        String aUnit = METRIC.equals(unitSystem) ? UNIT_MM : UNIT_IN;
        int order = 1;
        for (WeightDtos.PivotReq p : req.getPivots()) {
            WeightRulePivot pivot = new WeightRulePivot();
            pivot.setRuleId(ruleId);
            String code = p.getPivotCode().trim().toUpperCase();
            pivot.setPivotCode(code);
            pivot.setPivotName(p.getPivotName() == null || p.getPivotName().isBlank()
                    ? MroDict.wbPivotName(code) : p.getPivotName().trim());
            pivot.setDisplayOrder(p.getDisplayOrder() == null ? order : p.getDisplayOrder());
            pivot.setArmMm(WeightBalanceEngine.toMm(p.getArm(), aUnit));
            pivot.setArmOriginalValue(p.getArm());
            pivot.setArmOriginalUnit(aUnit);
            pivotRepository.save(pivot);
            order++;
        }
        saveBoundary(ruleId, BOUNDARY_FORWARD, req.getForwardNodes(), wUnit, aUnit, unitSystem);
        saveBoundary(ruleId, BOUNDARY_AFT, req.getAftNodes(), wUnit, aUnit, unitSystem);
    }

    private void saveBoundary(Long ruleId, String boundary, List<WeightDtos.NodeReq> nodes,
                              String wUnit, String aUnit, String unitSystem) {
        int seq = 1;
        for (WeightDtos.NodeReq n : nodes) {
            WeightRuleEnvelope node = new WeightRuleEnvelope();
            node.setRuleId(ruleId);
            node.setBoundary(boundary);
            node.setSeqNo(seq);
            node.setWeightKg(WeightBalanceEngine.toKg(n.getWeight(), wUnit));
            node.setArmMm(WeightBalanceEngine.toMm(n.getArm(), aUnit));
            node.setWeightOriginalValue(n.getWeight());
            node.setArmOriginalValue(n.getArm());
            node.setOriginalUnitSystem(unitSystem);
            envelopeRepository.save(node);
            seq++;
        }
    }

    private String normalizeUnitSystem(String unitSystem) {
        if (unitSystem == null) {
            throw new BizException("单位制不能为空：METRIC(kg/mm) 或 IMPERIAL(lb/in)");
        }
        String u = unitSystem.trim().toUpperCase();
        if (!METRIC.equals(u) && !IMPERIAL.equals(u)) {
            throw new BizException("单位制只能是 METRIC(kg/mm) 或 IMPERIAL(lb/in)，收到：" + unitSystem);
        }
        return u;
    }

    private WeightViews.RuleView toView(WeightRule rule, boolean referenced) {
        List<WeightViews.PivotView> pivots = new ArrayList<>();
        for (WeightRulePivot p : pivotRepository.findByRuleIdOrderByDisplayOrderAsc(rule.getId())) {
            pivots.add(new WeightViews.PivotView(p.getId(), p.getPivotCode(), p.getPivotName(),
                    p.getDisplayOrder(), p.getArmMm(), p.getArmOriginalValue(), p.getArmOriginalUnit()));
        }
        List<WeightViews.NodeView> fwd = nodes(rule.getId(), BOUNDARY_FORWARD);
        List<WeightViews.NodeView> aft = nodes(rule.getId(), BOUNDARY_AFT);
        return new WeightViews.RuleView(
                rule.getId(), rule.getRuleNo(), rule.getModel(), rule.getVersionNo(),
                rule.getStatus(), MroDict.ruleStatusName(rule.getStatus()),
                rule.getEffectiveDate(), rule.getUnitSystem(),
                MroDict.wbUnitSystemName(rule.getUnitSystem()),
                rule.getDatumName(), rule.getDatumNote(),
                rule.getMinWeight(), rule.getMaxWeight(),
                rule.getMinWeightOriginal(), rule.getMaxWeightOriginal(),
                rule.getForwardNodeCount(), referenced,
                pivots, fwd, aft, rule.getCreatedAt(), rule.getUpdatedAt());
    }

    private List<WeightViews.NodeView> nodes(Long ruleId, String boundary) {
        List<WeightViews.NodeView> list = new ArrayList<>();
        for (WeightRuleEnvelope n : envelopeRepository
                .findByRuleIdAndBoundaryOrderBySeqNoAsc(ruleId, boundary)) {
            list.add(new WeightViews.NodeView(n.getId(), n.getBoundary(),
                    BOUNDARY_FORWARD.equals(n.getBoundary()) ? "前边界" : "后边界",
                    n.getSeqNo(), n.getWeightKg(), n.getArmMm(),
                    n.getWeightOriginalValue(), n.getArmOriginalValue(), n.getOriginalUnitSystem()));
        }
        return list;
    }

    private String trim(String s) {
        return s == null ? null : s.trim();
    }

    /** 仅供视图装配：按 id 取规则（不存在给清晰错误）。 */
    @Transactional(readOnly = true)
    public WeightRule mustGet(Long id) {
        return ruleRepository.findById(id)
                .orElseThrow(() -> new BizException("称重规则不存在：id=" + id));
    }

    public Optional<WeightRule> findById(Long id) {
        return ruleRepository.findById(id);
    }
}
