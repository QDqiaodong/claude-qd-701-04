package com.aircraft.mro.service;

import static com.aircraft.mro.service.WeightBalanceEngine.DIR_INSTALL;
import static com.aircraft.mro.service.WeightBalanceEngine.DIR_REMOVE;
import static com.aircraft.mro.service.WeightBalanceEngine.ENV_INDETERMINATE;
import static com.aircraft.mro.service.WeightBalanceEngine.IMPERIAL;
import static com.aircraft.mro.service.WeightBalanceEngine.METRIC;
import static com.aircraft.mro.service.WeightBalanceEngine.UNIT_IN;
import static com.aircraft.mro.service.WeightBalanceEngine.UNIT_KG;
import static com.aircraft.mro.service.WeightBalanceEngine.UNIT_LB;
import static com.aircraft.mro.service.WeightBalanceEngine.UNIT_MM;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aircraft.mro.common.BizException;
import com.aircraft.mro.domain.Aircraft;
import com.aircraft.mro.domain.MroDict;
import com.aircraft.mro.domain.WbAdjustment;
import com.aircraft.mro.domain.WbConfigVersion;
import com.aircraft.mro.domain.WbWeighing;
import com.aircraft.mro.domain.WeightRule;
import com.aircraft.mro.dto.WeightDtos;
import com.aircraft.mro.dto.WeightViews;
import com.aircraft.mro.repository.AircraftRepository;
import com.aircraft.mro.repository.WbAdjustmentRepository;
import com.aircraft.mro.repository.WbConfigVersionRepository;
import com.aircraft.mro.repository.WbWeighingRepository;
import com.aircraft.mro.repository.WeightRuleEnvelopeRepository;

/**
 * 构型版本服务：称重基线 → 构型调整链。
 *
 * <p>硬规则：
 * <ul>
 *   <li>只能从每架飞机的【当前版本】继续派生；在旧版本上分叉直接拒绝；</li>
 *   <li>调整项必须属于本架飞机（版本自带 aircraft_id），跨机并入拒绝；</li>
 *   <li>每个版本生成时按当时规则冻结总重/总力矩/重心臂与包线判定，之后永不重算——
 *       规则改版、后续调整都不改写历史版本“当时结果”；</li>
 *   <li>越界（超总重/越前/后边界）仍照常生成版本（现场需要记录既成事实），但精确标出方向与差值；</li>
 *   <li>重新称重确认会把当前构型链整体冻结，以新称重为根开新链。</li>
 * </ul>
 */
@Service
public class WbConfigService {

    private final WbConfigVersionRepository versionRepository;
    private final WbAdjustmentRepository adjustmentRepository;
    private final WbWeighingRepository weighingRepository;
    private final AircraftRepository aircraftRepository;
    private final WeightRuleService ruleService;
    private final WeightRuleEnvelopeRepository envelopeRepository;

    public WbConfigService(WbConfigVersionRepository versionRepository,
                           WbAdjustmentRepository adjustmentRepository,
                           WbWeighingRepository weighingRepository,
                           AircraftRepository aircraftRepository,
                           WeightRuleService ruleService,
                           WeightRuleEnvelopeRepository envelopeRepository) {
        this.versionRepository = versionRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.weighingRepository = weighingRepository;
        this.aircraftRepository = aircraftRepository;
        this.ruleService = ruleService;
        this.envelopeRepository = envelopeRepository;
    }

    // ---------------------------------------------------------------- 基线（由称重确认调用）

    /**
     * 以已确认称重为根生成 BASELINE 版本；同机旧链全部冻结（current_flag=0）。
     */
    @Transactional
    public WbConfigVersion baselineFromWeighing(WbWeighing w, String reviewedBy, String confirmedBy) {
        freezeChain(w.getAircraftId());
        WeightRule rule = ruleService.mustGet(w.getRuleId());

        WbConfigVersion v = new WbConfigVersion();
        v.setAircraftId(w.getAircraftId());
        v.setVersionNo(nextVersionNo(w.getAircraftId()));
        v.setParentId(null);
        v.setSource(MroDict.CFG_BASELINE);
        v.setWeighingId(w.getId());
        v.setTailNo(w.getTailNo());
        v.setModel(w.getModel());
        v.setChangeDate(w.getWeighingDate());
        v.setRuleId(rule.getId());
        v.setRuleNo(rule.getRuleNo());
        v.setChangeSummary("称重基线 " + w.getWeighingNo() + "（" + w.getWeighingDate()
                + "，" + w.getScaleNo() + "；登记 " + w.getWeighedBy()
                + "，复核 " + reviewedBy + "/" + confirmedBy + "）");
        v.setTotalWeight(w.getResultTotalWeight());
        v.setTotalMoment(w.getResultTotalMoment());
        v.setCgArm(w.getResultCgArm());
        v.setEnvelopeStatus(w.getEnvelopeStatus());
        v.setEnvelopeMessage(w.getEnvelopeMessage());
        v.setForwardLimit(w.getResultForwardLimit());
        v.setAftLimit(w.getResultAftLimit());
        v.setForwardMargin(w.getResultForwardMargin());
        v.setAftMargin(w.getResultAftMargin());
        v.setCurrentFlag(true);
        v.setOperator(confirmedBy);
        v.setCreatedAt(LocalDateTime.now());
        return versionRepository.save(v);
    }

    // ---------------------------------------------------------------- 调整链查询

    @Transactional(readOnly = true)
    public WeightViews.ConfigChainView chain(Long aircraftId) {
        Aircraft aircraft = aircraftRepository.findById(aircraftId)
                .orElseThrow(() -> new BizException("飞机不存在：id=" + aircraftId));
        List<WbConfigVersion> versions = versionRepository.findByAircraftIdOrderByVersionNoAsc(aircraftId);
        WbConfigVersion current = versions.stream().filter(v -> Boolean.TRUE.equals(v.getCurrentFlag()))
                .findFirst().orElse(null);
        List<WeightViews.ConfigVersionView> views = versions.stream().map(this::toVersionView).toList();
        return new WeightViews.ConfigChainView(
                aircraftId, aircraft.getTailNo(), aircraft.getModel(),
                hasBaseline(versions),
                current == null ? null : current.getId(),
                current == null ? null : current.getVersionNo(),
                views);
    }

    private boolean hasBaseline(List<WbConfigVersion> versions) {
        return versions.stream().anyMatch(v -> MroDict.CFG_BASELINE.equals(v.getSource()));
    }

    @Transactional(readOnly = true)
    public WeightViews.ConfigVersionView versionDetail(Long versionId) {
        WbConfigVersion v = versionRepository.findById(versionId)
                .orElseThrow(() -> new BizException("构型版本不存在：id=" + versionId));
        return toVersionView(v);
    }

    // ---------------------------------------------------------------- 派生新版本

    /**
     * 从当前构型版本派生一个调整版本。
     */
    @Transactional
    public WeightViews.ConfigVersionView derive(WeightDtos.DeriveReq req) {
        Aircraft aircraft = aircraftRepository.findById(req.getAircraftId())
                .orElseThrow(() -> new BizException("飞机不存在：id=" + req.getAircraftId()));

        WbConfigVersion current = versionRepository.findByAircraftIdAndCurrentFlagTrue(req.getAircraftId())
                .orElseThrow(() -> new BizException("飞机 " + aircraft.getTailNo()
                        + " 尚未建立称重基线，不能登记构型调整；请先完成实机称重并经两人复核确认"));

        if (req.getParentVersionId() != null && !req.getParentVersionId().equals(current.getId())) {
            throw new BizException("只能从当前版本 V" + current.getVersionNo()
                    + " 继续派生，不能在历史版本上分叉（传入的是 id=" + req.getParentVersionId() + "）");
        }
        if (req.getChangeDate() == null) {
            throw new BizException("调整日期不能为空");
        }
        if (req.getChangeDate().isBefore(current.getChangeDate())) {
            throw new BizException("调整日期 " + req.getChangeDate() + " 早于当前版本日期 "
                    + current.getChangeDate() + "，构型链不允许倒序追加");
        }
        if (req.getItems() == null || req.getItems().isEmpty()) {
            throw new BizException("至少需要一条构型调整项");
        }
        String unitSystem = normalizeUnitSystem(req.getUnitSystem());
        String wUnit = METRIC.equals(unitSystem) ? UNIT_KG : UNIT_LB;
        String aUnit = METRIC.equals(unitSystem) ? UNIT_MM : UNIT_IN;

        // 派生当日生效规则（历史规则仍可解析到，但此处是“当前”构型的新事实，按当日规则判定并冻结）
        WeightRule rule = ruleService.resolveRule(aircraft.getModel(), req.getChangeDate());

        BigDecimal deltaWeight = BigDecimal.ZERO;
        BigDecimal deltaMoment = BigDecimal.ZERO;
        List<WbAdjustment> adjustments = new ArrayList<>();
        List<String> summaryParts = new ArrayList<>();

        int idx = 1;
        for (WeightDtos.AdjustmentReq item : req.getItems()) {
            String direction = item.getDirection().trim().toUpperCase();
            if (!Set.of(DIR_INSTALL, DIR_REMOVE).contains(direction)) {
                throw new BizException("调整项第 " + idx + " 行方向只能是 INSTALL(安装) / REMOVE(拆除)");
            }
            if (item.getQuantity() == null || item.getQuantity() <= 0) {
                throw new BizException("调整项「" + item.getName() + "」数量必须为正整数");
            }
            String twUnit = item.getWeightUnit() == null || item.getWeightUnit().isBlank() ? wUnit
                    : item.getWeightUnit().trim().toUpperCase();
            String taUnit = item.getArmUnit() == null || item.getArmUnit().isBlank() ? aUnit
                    : item.getArmUnit().trim().toUpperCase();
            if (!Set.of(UNIT_KG, UNIT_LB).contains(twUnit) || !Set.of(UNIT_MM, UNIT_IN).contains(taUnit)) {
                throw new BizException("调整项「" + item.getName() + "」单位非法（重量 KG/LB，臂长 MM/IN）");
            }
            if (item.getWeight() == null || item.getArm() == null
                    || WeightBalanceEngine.isIllegallyNegative(item.getWeight())
                    || WeightBalanceEngine.isIllegallyNegative(item.getArm())) {
                throw new BizException("调整项「" + item.getName() + "」重量/臂长缺失或为负数，属非法数值");
            }

            BigDecimal perKg = WeightBalanceEngine.toKg(item.getWeight(), twUnit);
            BigDecimal armMm = WeightBalanceEngine.toMm(item.getArm(), taUnit);
            BigDecimal qty = BigDecimal.valueOf(item.getQuantity());
            int sign = DIR_INSTALL.equals(direction) ? 1 : -1;
            BigDecimal signedWeight = WeightBalanceEngine.roundWeight(perKg.multiply(qty)
                    .multiply(BigDecimal.valueOf(sign)));
            BigDecimal signedMoment = WeightBalanceEngine.roundMoment(
                    perKg.multiply(qty).multiply(armMm).multiply(BigDecimal.valueOf(sign)));

            WbAdjustment a = new WbAdjustment();
            a.setAircraftId(aircraft.getId()); // 冗余落库：跨机并入在数据层即可识别
            a.setDirection(direction);
            a.setName(item.getName().trim());
            a.setWeightOriginal(item.getWeight());
            a.setArmOriginal(item.getArm());
            a.setQuantity(item.getQuantity());
            a.setWeightKg(perKg);
            a.setArmMm(armMm);
            a.setSignedWeight(signedWeight);
            a.setSignedMoment(signedMoment);
            a.setReason(item.getReason() == null ? null : item.getReason().trim());
            a.setWorkCardNo(item.getWorkCardNo() == null || item.getWorkCardNo().isBlank()
                    ? null : item.getWorkCardNo().trim());
            a.setWeightUnit(twUnit);
            a.setArmUnit(taUnit);
            adjustments.add(a);

            deltaWeight = deltaWeight.add(signedWeight);
            deltaMoment = deltaMoment.add(signedMoment);
            summaryParts.add((DIR_INSTALL.equals(direction) ? "安装" : "拆除")
                    + "「" + a.getName() + "」×" + a.getQuantity());
            idx++;
        }

        BigDecimal newWeight = WeightBalanceEngine.roundWeight(current.getTotalWeight().add(deltaWeight));
        BigDecimal newMoment = WeightBalanceEngine.roundMoment(current.getTotalMoment().add(deltaMoment));
        BigDecimal newCg = WeightBalanceEngine.cgArm(newMoment, newWeight);

        List<WeightBalanceEngine.EnvelopeNode> fwd = loadNodes(rule.getId(), WeightBalanceEngine.BOUNDARY_FORWARD);
        List<WeightBalanceEngine.EnvelopeNode> aft = loadNodes(rule.getId(), WeightBalanceEngine.BOUNDARY_AFT);
        WeightBalanceEngine.EnvelopeResult er =
                WeightBalanceEngine.evaluate(newWeight, newCg, rule.getMinWeight(), rule.getMaxWeight(), fwd, aft);
        if (ENV_INDETERMINATE.equals(er.status()) && newWeight.signum() > 0) {
            // 构型调整是在合法基线上叠加，理论上只可能超重/越界；若出现其他无法判定，保留原因即可
        }

        // 冻结旧当前版本
        current.setCurrentFlag(false);
        versionRepository.save(current);

        WbConfigVersion v = new WbConfigVersion();
        v.setAircraftId(aircraft.getId());
        v.setVersionNo(nextVersionNo(aircraft.getId()));
        v.setParentId(current.getId());
        v.setSource(MroDict.CFG_ADJUSTMENT);
        v.setWeighingId(null);
        v.setTailNo(aircraft.getTailNo());
        v.setModel(aircraft.getModel());
        v.setChangeDate(req.getChangeDate());
        v.setRuleId(rule.getId());
        v.setRuleNo(rule.getRuleNo());
        v.setChangeSummary(String.join("；", summaryParts));
        v.setTotalWeight(newWeight);
        v.setTotalMoment(newMoment);
        v.setCgArm(newCg);
        v.setEnvelopeStatus(er.status());
        v.setEnvelopeMessage(er.message());
        v.setForwardLimit(er.forwardLimit());
        v.setAftLimit(er.aftLimit());
        v.setForwardMargin(er.forwardMargin());
        v.setAftMargin(er.aftMargin());
        v.setCurrentFlag(true);
        v.setOperator(req.getOperator().trim());
        v.setCreatedAt(LocalDateTime.now());
        v = versionRepository.save(v);

        for (WbAdjustment a : adjustments) {
            a.setVersionId(v.getId());
            adjustmentRepository.save(a);
        }
        return toVersionView(v);
    }

    // ---------------------------------------------------------------- 版本对比

    @Transactional(readOnly = true)
    public WeightViews.VersionCompareView compare(Long aircraftId, Long fromId, Long toId) {
        Aircraft aircraft = aircraftRepository.findById(aircraftId)
                .orElseThrow(() -> new BizException("飞机不存在：id=" + aircraftId));
        List<WbConfigVersion> chain = versionRepository.findByAircraftIdOrderByVersionNoAsc(aircraftId);
        if (chain.isEmpty()) {
            throw new BizException("飞机 " + aircraft.getTailNo() + " 未建立称重基线，没有可对比的构型版本");
        }
        WbConfigVersion from = fromId == null
                ? chain.stream().filter(v -> MroDict.CFG_BASELINE.equals(v.getSource())).findFirst()
                        .orElse(chain.get(0))
                : versionRepository.findById(fromId)
                        .orElseThrow(() -> new BizException("版本不存在：id=" + fromId));
        WbConfigVersion to = toId == null
                ? chain.stream().filter(WbConfigVersion::getCurrentFlag).findFirst()
                        .orElse(chain.get(chain.size() - 1))
                : versionRepository.findById(toId)
                        .orElseThrow(() -> new BizException("版本不存在：id=" + toId));
        if (!from.getAircraftId().equals(aircraftId) || !to.getAircraftId().equals(aircraftId)) {
            throw new BizException("不能跨飞机对比构型版本");
        }
        return new WeightViews.VersionCompareView(
                aircraftId, aircraft.getTailNo(), aircraft.getModel(),
                brief(from), brief(to),
                WeightBalanceEngine.roundWeight(to.getTotalWeight().subtract(from.getTotalWeight())),
                WeightBalanceEngine.roundMoment(to.getTotalMoment().subtract(from.getTotalMoment())),
                WeightBalanceEngine.roundArm(to.getCgArm().subtract(from.getCgArm())),
                !from.getEnvelopeStatus().equals(to.getEnvelopeStatus()),
                toVersionView(to).adjustments());
    }

    // ---------------------------------------------------------------- 飞机台账状态

    /**
     * 全机队称重基线状态：没有基线的飞机照常返回，明确标“未建立基线”，重心字段一律 null（不猜测默认值）。
     */
    @Transactional(readOnly = true)
    public List<WeightViews.AircraftWeightStatusView> fleetStatus() {
        List<Aircraft> all = aircraftRepository.findAllByOrderByTailNoAsc();
        List<WeightViews.AircraftWeightStatusView> out = new ArrayList<>();
        for (Aircraft a : all) {
            out.add(oneStatus(a));
        }
        return out;
    }

    private WeightViews.AircraftWeightStatusView oneStatus(Aircraft a) {
        var currentOpt = versionRepository.findByAircraftIdAndCurrentFlagTrue(a.getId());
        WbWeighing baselineWeighing = weighingRepository.findByAircraftIdAndCurrentFlagTrue(a.getId()).orElse(null);
        List<WbWeighing> allWeighings = weighingRepository.findByAircraftIdOrderByWeighingDateDescIdDesc(a.getId());
        LocalDate lastDate = allWeighings.isEmpty() ? null : allWeighings.get(0).getWeighingDate();

        if (currentOpt.isEmpty()) {
            String note = null;
            List<WbWeighing> drafts = allWeighings.stream()
                    .filter(w -> MroDict.WB_DRAFT.equals(w.getStatus())).toList();
            if (!drafts.isEmpty()) {
                note = "有 " + drafts.size() + " 条待复核称重，尚未经两人确认成为基线";
            }
            return new WeightViews.AircraftWeightStatusView(
                    a.getId(), a.getTailNo(), a.getModel(), a.getAirline(),
                    false, null, null, null, null, null, null, null, null,
                    baselineWeighing == null ? null : baselineWeighing.getId(),
                    baselineWeighing == null ? null : baselineWeighing.getWeighingNo(),
                    lastDate, null, (int) versionRepository.countByAircraftId(a.getId()), note);
        }
        WbConfigVersion cur = currentOpt.get();
        String note = MroDict.CFG_ADJUSTMENT.equals(cur.getSource())
                ? "当前为最新构型调整版本（基线为称重 V" + baselineVersionNo(a.getId()) + "）"
                : "当前为称重基线";
        return new WeightViews.AircraftWeightStatusView(
                a.getId(), a.getTailNo(), a.getModel(), a.getAirline(),
                true, cur.getId(), cur.getVersionNo(), cur.getChangeDate(),
                cur.getTotalWeight(), cur.getCgArm(),
                cur.getEnvelopeStatus(), MroDict.wbEnvelopeName(cur.getEnvelopeStatus()),
                cur.getEnvelopeMessage(),
                baselineWeighing == null ? null : baselineWeighing.getId(),
                baselineWeighing == null ? null : baselineWeighing.getWeighingNo(),
                lastDate, cur.getRuleNo(), (int) versionRepository.countByAircraftId(a.getId()), note);
    }

    private Integer baselineVersionNo(Long aircraftId) {
        return versionRepository.findByAircraftIdOrderByVersionNoAsc(aircraftId).stream()
                .filter(v -> MroDict.CFG_BASELINE.equals(v.getSource()))
                .map(WbConfigVersion::getVersionNo).findFirst().orElse(null);
    }

    // ---------------------------------------------------------------- 内部

    private void freezeChain(Long aircraftId) {
        for (WbConfigVersion v : versionRepository.findByAircraftIdOrderByVersionNoAsc(aircraftId)) {
            if (Boolean.TRUE.equals(v.getCurrentFlag())) {
                v.setCurrentFlag(false);
                versionRepository.save(v);
            }
        }
    }

    private int nextVersionNo(Long aircraftId) {
        return (int) versionRepository.countByAircraftId(aircraftId) + 1;
    }

    private List<WeightBalanceEngine.EnvelopeNode> loadNodes(Long ruleId, String boundary) {
        return envelopeRepository.findByRuleIdAndBoundaryOrderBySeqNoAsc(ruleId, boundary).stream()
                .map(n -> new WeightBalanceEngine.EnvelopeNode(n.getWeightKg(), n.getArmMm()))
                .toList();
    }

    private WeightViews.ConfigVersionView toVersionView(WbConfigVersion v) {
        List<WeightViews.AdjustmentView> items = adjustmentRepository
                .findByVersionIdOrderByIdAsc(v.getId()).stream().map(a -> new WeightViews.AdjustmentView(
                        a.getId(), a.getDirection(), MroDict.wbDirectionName(a.getDirection()),
                        a.getName(), a.getQuantity(), a.getWeightUnit(), a.getArmUnit(),
                        a.getWeightOriginal(), a.getArmOriginal(), a.getWeightKg(), a.getArmMm(),
                        a.getSignedWeight(), a.getSignedMoment(), a.getReason(), a.getWorkCardNo()))
                .toList();
        String weighingNo = v.getWeighingId() == null ? null
                : weighingRepository.findById(v.getWeighingId()).map(WbWeighing::getWeighingNo).orElse(null);
        WeightRule rule = ruleService.findById(v.getRuleId()).orElse(null);
        WeightViews.EnvelopeView env = new WeightViews.EnvelopeView(
                v.getEnvelopeStatus(), MroDict.wbEnvelopeName(v.getEnvelopeStatus()),
                v.getEnvelopeMessage(), v.getTotalWeight(), v.getTotalMoment(), v.getCgArm(),
                v.getForwardLimit(), v.getAftLimit(), v.getForwardMargin(), v.getAftMargin());
        return new WeightViews.ConfigVersionView(
                v.getId(), v.getAircraftId(), v.getTailNo(), v.getModel(), v.getVersionNo(),
                v.getParentId(), v.getSource(), MroDict.cfgSourceName(v.getSource()),
                v.getWeighingId(), weighingNo, v.getChangeDate(), v.getRuleId(), v.getRuleNo(),
                rule == null ? "" : MroDict.ruleStatusName(rule.getStatus()),
                v.getChangeSummary(), env, Boolean.TRUE.equals(v.getCurrentFlag()),
                v.getOperator(), v.getCreatedAt(), items);
    }

    private WeightViews.VersionBrief brief(WbConfigVersion v) {
        int count = adjustmentRepository.findByVersionIdOrderByIdAsc(v.getId()).size();
        return new WeightViews.VersionBrief(
                v.getId(), v.getVersionNo(), MroDict.cfgSourceName(v.getSource()),
                v.getChangeDate(), v.getRuleNo(), MroDict.ruleStatusName(
                        ruleService.findById(v.getRuleId()).map(WeightRule::getStatus).orElse("")),
                v.getTotalWeight(), v.getTotalMoment(), v.getCgArm(),
                v.getEnvelopeStatus(), MroDict.wbEnvelopeName(v.getEnvelopeStatus()),
                v.getEnvelopeMessage(), count);
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
}
