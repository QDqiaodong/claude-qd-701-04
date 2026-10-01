package com.aircraft.mro.service;

import static com.aircraft.mro.service.WeightBalanceEngine.ENV_IN;
import static com.aircraft.mro.service.WeightBalanceEngine.ENV_INDETERMINATE;
import static com.aircraft.mro.service.WeightBalanceEngine.IMPERIAL;
import static com.aircraft.mro.service.WeightBalanceEngine.KIND_ADD;
import static com.aircraft.mro.service.WeightBalanceEngine.KIND_DEDUCT;
import static com.aircraft.mro.service.WeightBalanceEngine.METRIC;
import static com.aircraft.mro.service.WeightBalanceEngine.UNIT_IN;
import static com.aircraft.mro.service.WeightBalanceEngine.UNIT_KG;
import static com.aircraft.mro.service.WeightBalanceEngine.UNIT_LB;
import static com.aircraft.mro.service.WeightBalanceEngine.UNIT_MM;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aircraft.mro.common.BizException;
import com.aircraft.mro.domain.Aircraft;
import com.aircraft.mro.domain.MroDict;
import com.aircraft.mro.domain.WbReading;
import com.aircraft.mro.domain.WbTempItem;
import com.aircraft.mro.domain.WbWeighing;
import com.aircraft.mro.domain.WeightRule;
import com.aircraft.mro.domain.WeightRulePivot;
import com.aircraft.mro.dto.WeightDtos;
import com.aircraft.mro.dto.WeightViews;
import com.aircraft.mro.repository.AircraftRepository;
import com.aircraft.mro.repository.WbReadingRepository;
import com.aircraft.mro.repository.WbTempItemRepository;
import com.aircraft.mro.repository.WbWeighingRepository;
import com.aircraft.mro.repository.WeightRulePivotRepository;

/**
 * 实机称重服务：登记/修改待复核称重、服务端试算、两人复核确认成为当前基线。
 *
 * <p>计算口径见 {@link WeightBalanceEngine}：原单位读数原样保存，统一单位（kg/mm）只在保存时
 * 计算一次并冻结；之后任何打开页面/试算/重复保存都重放同一套纯函数，得到完全一致的数值，
 * 不因新规则发布而改写旧单（规则以 rule_id 固化引用）。
 */
@Service
public class WeighingService {

    private final WbWeighingRepository weighingRepository;
    private final WbReadingRepository readingRepository;
    private final WbTempItemRepository tempItemRepository;
    private final WeightRulePivotRepository pivotRepository;
    private final com.aircraft.mro.repository.WeightRuleEnvelopeRepository envelopeRepository;
    private final AircraftRepository aircraftRepository;
    private final WeightRuleService ruleService;
    private final WbConfigService configService;

    public WeighingService(WbWeighingRepository weighingRepository,
                           WbReadingRepository readingRepository,
                           WbTempItemRepository tempItemRepository,
                           WeightRulePivotRepository pivotRepository,
                           com.aircraft.mro.repository.WeightRuleEnvelopeRepository envelopeRepository,
                           AircraftRepository aircraftRepository,
                           WeightRuleService ruleService,
                           @Lazy WbConfigService configService) {
        this.weighingRepository = weighingRepository;
        this.readingRepository = readingRepository;
        this.tempItemRepository = tempItemRepository;
        this.pivotRepository = pivotRepository;
        this.envelopeRepository = envelopeRepository;
        this.aircraftRepository = aircraftRepository;
        this.ruleService = ruleService;
        this.configService = configService;
    }

    // ---------------------------------------------------------------- 查询

    @Transactional(readOnly = true)
    public List<WeightViews.WeighingListItem> list(Long aircraftId) {
        List<WbWeighing> list = aircraftId == null
                ? weighingRepository.findAllByOrderByIdDesc()
                : weighingRepository.findByAircraftIdOrderByWeighingDateDescIdDesc(aircraftId);
        List<WeightViews.WeighingListItem> out = new ArrayList<>();
        for (WbWeighing w : list) {
            WeightRule rule = ruleService.findById(w.getRuleId()).orElse(null);
            out.add(new WeightViews.WeighingListItem(
                    w.getId(), w.getWeighingNo(), w.getAircraftId(), w.getTailNo(), w.getModel(),
                    w.getWeighingDate(), w.getUnitSystem(), MroDict.wbUnitSystemName(w.getUnitSystem()),
                    w.getRuleNo(), rule == null ? "" : MroDict.ruleStatusName(rule.getStatus()),
                    w.getResultTotalWeight(), w.getResultCgArm(),
                    w.getEnvelopeStatus(), MroDict.wbEnvelopeName(w.getEnvelopeStatus()),
                    w.getEnvelopeMessage(), w.getStatus(), MroDict.wbStatusName(w.getStatus()),
                    Boolean.TRUE.equals(w.getCurrentFlag())));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public WeightViews.WeighingView detail(Long id) {
        WbWeighing w = weighingRepository.findById(id)
                .orElseThrow(() -> new BizException("称重记录不存在：id=" + id));
        return toView(w);
    }

    // ---------------------------------------------------------------- 登记 / 修改

    @Transactional
    public WeightViews.WeighingView create(WeightDtos.WeighingSaveReq req) {
        WbWeighing w = new WbWeighing();
        w.setStatus(MroDict.WB_DRAFT);
        w.setCurrentFlag(false);
        LocalDateTime now = LocalDateTime.now();
        w.setCreatedAt(now);
        w.setUpdatedAt(now);
        return saveInternal(w, req, true);
    }

    /** 仅待复核称重可修改；已确认基线冻结，只能重新称重。 */
    @Transactional
    public WeightViews.WeighingView update(Long id, WeightDtos.WeighingSaveReq req) {
        WbWeighing w = weighingRepository.findById(id)
                .orElseThrow(() -> new BizException("称重记录不存在：id=" + id));
        if (!MroDict.WB_DRAFT.equals(w.getStatus())) {
            throw new BizException(w.getWeighingNo() + " 已由 " + w.getConfirmedBy()
                    + " 复核确认为基线，结果冻结，不能修改；如需更新请重新称重");
        }
        readingRepository.deleteByWeighingId(id);
        readingRepository.flush();
        tempItemRepository.deleteByWeighingId(id);
        tempItemRepository.flush();
        w.setUpdatedAt(LocalDateTime.now());
        return saveInternal(w, req, false);
    }

    /** 试算：同一套服务端计算，不入库。 */
    @Transactional(readOnly = true)
    public WeightViews.WeighingView preview(WeightDtos.WeighingSaveReq req) {
        WbWeighing w = new WbWeighing();
        w.setStatus(MroDict.WB_DRAFT);
        w.setCurrentFlag(false);
        LocalDateTime now = LocalDateTime.now();
        w.setCreatedAt(now);
        w.setUpdatedAt(now);
        PreparedCalc calc = prepare(w, req);
        w.setWeighingNo("(试算，未保存)");
        return assembleView(w, calc, null);
    }

    private WeightViews.WeighingView saveInternal(WbWeighing w, WeightDtos.WeighingSaveReq req, boolean creating) {
        PreparedCalc calc = prepare(w, req);
        if (creating) {
            w.setWeighingNo(generateNo());
        }
        w = weighingRepository.save(w);
        for (WbReading r : calc.readings) {
            r.setWeighingId(w.getId());
            readingRepository.save(r);
        }
        for (WbTempItem t : calc.tempItems) {
            t.setWeighingId(w.getId());
            tempItemRepository.save(t);
        }
        return toView(weighingRepository.save(w));
    }

    /**
     * 称重计算核心：把入参装配成实体并完成一次统一单位换算与包线判定（纯函数结果整体写入实体）。
     */
    private PreparedCalc prepare(WbWeighing w, WeightDtos.WeighingSaveReq req) {
        Aircraft aircraft = aircraftRepository.findById(req.getAircraftId())
                .orElseThrow(() -> new BizException("飞机不存在：id=" + req.getAircraftId()));
        if (w.getId() == null || !aircraft.getId().equals(w.getAircraftId())) {
            w.setAircraftId(aircraft.getId());
            w.setTailNo(aircraft.getTailNo());
            w.setModel(aircraft.getModel());
        }
        String unitSystem = normalizeUnitSystem(req.getUnitSystem());
        w.setUnitSystem(unitSystem);
        w.setWeighingDate(req.getWeighingDate());
        if (req.getWeighingDate() == null) {
            throw new BizException("称重日期不能为空");
        }
        w.setScaleNo(req.getScaleNo().trim());
        w.setTarePerPivot(Boolean.TRUE.equals(req.getTarePerPivot()));
        w.setCommonTareOriginal(req.getCommonTare());
        w.setFuelCondition(trim(req.getFuelCondition()));
        w.setFuelQtyOriginal(req.getFuelQty());
        w.setOilCondition(trim(req.getOilCondition()));
        w.setOilQtyOriginal(req.getOilQty());
        w.setRemark(trim(req.getRemark()));
        w.setWeighedBy(req.getWeighedBy().trim());

        // 引用规则：显式指定必须同机型且非草稿；否则按称重日期解析生效版（历史日期自然取到旧版）
        WeightRule rule;
        if (req.getRuleId() != null) {
            rule = ruleService.mustGet(req.getRuleId());
            if (!rule.getModel().equals(aircraft.getModel())) {
                throw new BizException("规则 " + rule.getRuleNo() + " 适用于机型 " + rule.getModel()
                        + "，与本机 " + aircraft.getModel() + " 不符，臂长不能跨机型套用");
            }
            if (MroDict.RULE_DRAFT.equals(rule.getStatus())) {
                throw new BizException("不能引用尚未生效的草稿规则：" + rule.getRuleNo());
            }
        } else {
            rule = ruleService.resolveRule(aircraft.getModel(), req.getWeighingDate());
        }
        w.setRuleId(rule.getId());
        w.setRuleNo(rule.getRuleNo());

        String wUnit = METRIC.equals(unitSystem) ? UNIT_KG : UNIT_LB;
        String aUnit = METRIC.equals(unitSystem) ? UNIT_MM : UNIT_IN;

        // 统一皮重换算后非法值检查
        BigDecimal commonTare = req.getCommonTare();
        if (commonTare != null && WeightBalanceEngine.isIllegallyNegative(commonTare)) {
            throw new BizException("统一皮重不能为负数");
        }
        if (!w.getTarePerPivot() && (commonTare == null)) {
            throw new BizException("当前按统一皮重去皮，请填写统一皮重（没有皮重请填 0）");
        }

        List<WeightRulePivot> pivots = pivotRepository.findByRuleIdOrderByDisplayOrderAsc(rule.getId());
        Map<String, WeightRulePivot> pivotMap = new LinkedHashMap<>();
        for (WeightRulePivot p : pivots) {
            pivotMap.put(p.getPivotCode(), p);
        }
        List<WeightDtos.ReadingReq> readingReqs = req.getReadings() == null ? List.of() : req.getReadings();
        Map<String, WeightDtos.ReadingReq> readingMap = new LinkedHashMap<>();
        for (WeightDtos.ReadingReq r : readingReqs) {
            String code = r.getPivotCode().trim().toUpperCase();
            if (!pivotMap.containsKey(code)) {
                throw new BizException("支点 " + code + " 不属于当前规则 " + rule.getRuleNo()
                        + "（该规则支点：" + pivotMap.keySet() + "），不能按该臂长计算");
            }
            if (readingMap.put(code, r) != null) {
                throw new BizException("支点 " + code + " 的读数重复录入");
            }
        }
        if (readingMap.size() != pivotMap.size()) {
            String missing = pivotMap.keySet().stream()
                    .filter(c -> !readingMap.containsKey(c))
                    .collect(Collectors.joining("、"));
            throw new BizException("读数支点与规则不一致，缺少支点：" + missing);
        }

        PreparedCalc calc = new PreparedCalc();
        calc.rule = rule;
        BigDecimal grossTotal = BigDecimal.ZERO;
        BigDecimal tareTotal = BigDecimal.ZERO;
        BigDecimal netTotal = BigDecimal.ZERO;
        BigDecimal momentTotal = BigDecimal.ZERO;
        List<String> missingPivots = new ArrayList<>();

        for (Map.Entry<String, WeightRulePivot> entry : pivotMap.entrySet()) {
            String code = entry.getKey();
            WeightRulePivot pivot = entry.getValue();
            WeightDtos.ReadingReq rr = readingMap.get(code);

            BigDecimal gross = rr.getGross();
            BigDecimal tare = w.getTarePerPivot() ? rr.getTare() : commonTare;
            if (tare == null) {
                tare = BigDecimal.ZERO;
            }
            if (gross != null && WeightBalanceEngine.isIllegallyNegative(gross)) {
                throw new BizException("支点 " + pivot.getPivotName() + " 毛重读数为负数，属非法数值");
            }
            if (WeightBalanceEngine.isIllegallyNegative(tare)) {
                throw new BizException("支点 " + pivot.getPivotName() + " 皮重为负数，属非法数值");
            }

            WbReading entity = new WbReading();
            entity.setPivotCode(code);
            entity.setPivotName(pivot.getPivotName());
            entity.setArmMm(pivot.getArmMm());
            entity.setWeightUnit(wUnit);
            entity.setGrossOriginal(gross);
            entity.setTareOriginal(tare);

            if (gross == null) {
                missingPivots.add(pivot.getPivotName() + "(" + code + ")");
            } else {
                BigDecimal net = gross.subtract(tare);
                BigDecimal grossKg = WeightBalanceEngine.toKg(gross, wUnit);
                BigDecimal tareKg = WeightBalanceEngine.toKg(tare, wUnit);
                BigDecimal netKg = WeightBalanceEngine.toKg(net, wUnit);
                if (netKg.signum() < 0) {
                    missingPivots.add(pivot.getPivotName() + "净重为负(毛重" + gross + wUnit.toLowerCase()
                            + " < 皮重" + tare + wUnit.toLowerCase() + ")");
                }
                BigDecimal moment = WeightBalanceEngine.roundMoment(netKg.multiply(pivot.getArmMm()));
                entity.setNetOriginal(net);
                entity.setGrossKg(grossKg);
                entity.setTareKg(tareKg);
                entity.setNetKg(netKg);
                entity.setMoment(moment);
                grossTotal = grossTotal.add(grossKg);
                tareTotal = tareTotal.add(tareKg);
                netTotal = netTotal.add(netKg);
                momentTotal = momentTotal.add(moment);
            }
            calc.readings.add(entity);
        }

        // 临时项（扣除/补入）
        List<WeightDtos.TempItemReq> tempReqs = req.getTempItems() == null ? List.of() : req.getTempItems();
        BigDecimal tempWeight = BigDecimal.ZERO;
        BigDecimal tempMoment = BigDecimal.ZERO;
        Set<String> kinds = Set.of(KIND_ADD, KIND_DEDUCT);
        int ti = 1;
        for (WeightDtos.TempItemReq t : tempReqs) {
            String kind = t.getKind().trim().toUpperCase();
            if (!kinds.contains(kind)) {
                throw new BizException("临时项第 " + ti + " 行类型只能是 ADD(补入) / DEDUCT(扣除)");
            }
            String twUnit = t.getWeightUnit() == null || t.getWeightUnit().isBlank() ? wUnit
                    : t.getWeightUnit().trim().toUpperCase();
            String taUnit = t.getArmUnit() == null || t.getArmUnit().isBlank() ? aUnit
                    : t.getArmUnit().trim().toUpperCase();
            if (!Set.of(UNIT_KG, UNIT_LB).contains(twUnit) || !Set.of(UNIT_MM, UNIT_IN).contains(taUnit)) {
                throw new BizException("临时项第 " + ti + " 行单位非法（重量 KG/LB，臂长 MM/IN）");
            }
            if (t.getWeight() == null || t.getArm() == null
                    || WeightBalanceEngine.isIllegallyNegative(t.getWeight())
                    || WeightBalanceEngine.isIllegallyNegative(t.getArm())) {
                throw new BizException("临时项「" + t.getName() + "」重量/臂长缺失或为负数，属非法数值");
            }
            BigDecimal wkg = WeightBalanceEngine.toKg(t.getWeight(), twUnit);
            BigDecimal amm = WeightBalanceEngine.toMm(t.getArm(), taUnit);
            int sign = KIND_ADD.equals(kind) ? 1 : -1;
            BigDecimal signedW = WeightBalanceEngine.roundWeight(wkg.multiply(BigDecimal.valueOf(sign)));
            BigDecimal signedM = WeightBalanceEngine.roundMoment(
                    wkg.multiply(amm).multiply(BigDecimal.valueOf(sign)));

            WbTempItem entity = new WbTempItem();
            entity.setKind(kind);
            entity.setName(t.getName().trim());
            entity.setWeightOriginal(t.getWeight());
            entity.setArmOriginal(t.getArm());
            entity.setWeightKg(wkg);
            entity.setArmMm(amm);
            entity.setSignedWeight(signedW);
            entity.setSignedMoment(signedM);
            entity.setReason(trim(t.getReason()));
            entity.setWeightUnit(twUnit);
            entity.setArmUnit(taUnit);
            calc.tempItems.add(entity);

            tempWeight = tempWeight.add(signedW);
            tempMoment = tempMoment.add(signedM);
            ti++;
        }

        calc.missingCount = missingPivots.size();
        BigDecimal totalWeight = netTotal.add(tempWeight);
        BigDecimal totalMoment = momentTotal.add(tempMoment);

        WeightBalanceEngine.EnvelopeResult er;
        if (!missingPivots.isEmpty()) {
            er = new WeightBalanceEngine.EnvelopeResult(ENV_INDETERMINATE, null, null, null, null,
                    "存在缺失/非法支点数据：" + String.join("、", missingPivots)
                            + "。按规则不能给出“包线内”结论");
            totalWeight = null;
            totalMoment = null;
        } else {
            List<WeightBalanceEngine.EnvelopeNode> fwd = loadNodes(rule.getId(), WeightBalanceEngine.BOUNDARY_FORWARD);
            List<WeightBalanceEngine.EnvelopeNode> aft = loadNodes(rule.getId(), WeightBalanceEngine.BOUNDARY_AFT);
            BigDecimal cg = WeightBalanceEngine.cgArm(totalMoment, totalWeight);
            er = WeightBalanceEngine.evaluate(totalWeight, cg, rule.getMinWeight(), rule.getMaxWeight(),
                    fwd, aft);
            totalMoment = WeightBalanceEngine.roundMoment(totalMoment);
        }

        w.setResultTotalWeight(totalWeight == null ? null : WeightBalanceEngine.roundWeight(totalWeight));
        w.setResultTotalMoment(totalMoment);
        w.setResultCgArm(totalMoment == null ? null
                : WeightBalanceEngine.cgArm(totalMoment, totalWeight));
        w.setEnvelopeStatus(er.status());
        w.setEnvelopeMessage(er.message());
        w.setResultForwardLimit(er.forwardLimit());
        w.setResultAftLimit(er.aftLimit());
        w.setResultForwardMargin(er.forwardMargin());
        w.setResultAftMargin(er.aftMargin());
        return calc;
    }

    private List<WeightBalanceEngine.EnvelopeNode> loadNodes(Long ruleId, String boundary) {
        return envelopeRepository.findByRuleIdAndBoundaryOrderBySeqNoAsc(ruleId, boundary).stream()
                .map(n -> new WeightBalanceEngine.EnvelopeNode(n.getWeightKg(), n.getArmMm()))
                .toList();
    }

    // ---------------------------------------------------------------- 复核确认

    /**
     * 两人复核：第一复核人与第二复核人都必须与登记人不同，且两人互不相同；
     * 只有包线内的称重才允许确认为基线。确认后旧称重基线与旧构型链整体冻结，新链以本次称重为根。
     */
    @Transactional
    public WeightViews.WeighingView confirm(Long id, WeightDtos.WeighingConfirmReq req) {
        WbWeighing w = weighingRepository.findById(id)
                .orElseThrow(() -> new BizException("称重记录不存在：id=" + id));
        if (MroDict.WB_CONFIRMED.equals(w.getStatus())) {
            throw new BizException(w.getWeighingNo() + " 已由 " + w.getConfirmedBy() + " 确认，不能重复复核");
        }
        String reviewed = req.getReviewedBy().trim();
        String confirmed = req.getConfirmedBy().trim();
        if (reviewed.equals(w.getWeighedBy())) {
            throw new BizException("第一复核人不能与称重登记人「" + w.getWeighedBy() + "」为同一人");
        }
        if (confirmed.equals(w.getWeighedBy())) {
            throw new BizException("第二复核人不能与称重登记人「" + w.getWeighedBy() + "」为同一人");
        }
        if (confirmed.equals(reviewed)) {
            throw new BizException("第二复核人不能与第一复核人「" + reviewed + "」为同一人，需经不同人复核确认");
        }
        if (!ENV_IN.equals(w.getEnvelopeStatus())) {
            throw new BizException("该称重判定为「" + MroDict.wbEnvelopeName(w.getEnvelopeStatus())
                    + "」：" + w.getEnvelopeMessage() + "——不能据此建立称重基线");
        }

        // 旧基线称重置 current=0（记录与当时判定仍保留展示）
        weighingRepository.findByAircraftIdAndCurrentFlagTrue(w.getAircraftId()).ifPresent(old -> {
            old.setCurrentFlag(false);
            old.setUpdatedAt(LocalDateTime.now());
            weighingRepository.save(old);
        });
        w.setStatus(MroDict.WB_CONFIRMED);
        w.setReviewedBy(reviewed);
        w.setConfirmedBy(confirmed);
        w.setConfirmedAt(LocalDateTime.now());
        w.setCurrentFlag(true);
        w.setUpdatedAt(LocalDateTime.now());
        w = weighingRepository.save(w);

        // 以本次称重为根开启新构型链（旧链由 configService 冻结）
        configService.baselineFromWeighing(w, reviewed, confirmed);
        return toView(w);
    }

    // ---------------------------------------------------------------- 装配

    private WeightViews.WeighingView toView(WbWeighing w) {
        PreparedCalc calc = new PreparedCalc();
        calc.rule = ruleService.findById(w.getRuleId()).orElse(null);
        calc.readings = readingRepository.findByWeighingIdOrderByIdAsc(w.getId());
        calc.tempItems = tempItemRepository.findByWeighingIdOrderByIdAsc(w.getId());
        long missing = calc.readings.stream().filter(r -> r.getGrossOriginal() == null).count();
        calc.missingCount = (int) missing;
        return assembleView(w, calc, calc.rule);
    }

    private WeightViews.WeighingView assembleView(WbWeighing w, PreparedCalc calc, WeightRule ruleIgnored) {
        WeightRule rule = calc.rule;
        List<WeightViews.ReadingView> readings = new ArrayList<>();
        for (WbReading r : calc.readings) {
            readings.add(new WeightViews.ReadingView(
                    r.getId(), r.getPivotCode(), r.getPivotName(), r.getArmMm(),
                    r.getWeightUnit(), r.getGrossOriginal(), r.getTareOriginal(), r.getNetOriginal(),
                    r.getGrossKg(), r.getTareKg(), r.getNetKg(), r.getMoment(),
                    r.getGrossOriginal() == null));
        }
        List<WeightViews.TempItemView> temps = new ArrayList<>();
        for (WbTempItem t : calc.tempItems) {
            temps.add(new WeightViews.TempItemView(
                    t.getId(), t.getKind(), MroDict.wbTempKindName(t.getKind()), t.getName(),
                    t.getWeightUnit(), t.getArmUnit(), t.getWeightOriginal(), t.getArmOriginal(),
                    t.getWeightKg(), t.getArmMm(), t.getSignedWeight(), t.getSignedMoment(), t.getReason()));
        }
        WeightViews.EnvelopeView result = new WeightViews.EnvelopeView(
                w.getEnvelopeStatus(), MroDict.wbEnvelopeName(w.getEnvelopeStatus()),
                w.getEnvelopeMessage(), w.getResultTotalWeight(), w.getResultTotalMoment(),
                w.getResultCgArm(), w.getResultForwardLimit(), w.getResultAftLimit(),
                w.getResultForwardMargin(), w.getResultAftMargin());
        return new WeightViews.WeighingView(
                w.getId(), w.getWeighingNo(), w.getAircraftId(), w.getTailNo(), w.getModel(),
                w.getWeighingDate(), w.getScaleNo(), w.getUnitSystem(),
                MroDict.wbUnitSystemName(w.getUnitSystem()), w.getRuleId(), w.getRuleNo(),
                rule == null ? "" : MroDict.ruleStatusName(rule.getStatus()),
                Boolean.TRUE.equals(w.getTarePerPivot()), w.getCommonTareOriginal(),
                w.getFuelCondition(), w.getFuelQtyOriginal(), w.getOilCondition(), w.getOilQtyOriginal(),
                w.getRemark(), readings, temps, result, calc.missingCount,
                w.getStatus(), MroDict.wbStatusName(w.getStatus()),
                w.getWeighedBy(), w.getReviewedBy(), w.getConfirmedBy(), w.getConfirmedAt(),
                Boolean.TRUE.equals(w.getCurrentFlag()), w.getCreatedAt(), w.getUpdatedAt());
    }

    private String generateNo() {
        long seq = weighingRepository.count() + 1;
        String year = String.valueOf(java.time.Year.now().getValue());
        String no;
        do {
            no = "WB-" + year + "-" + String.format("%04d", seq);
            seq++;
        } while (weighingRepository.existsByWeighingNo(no));
        return no;
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

    private String trim(String s) {
        return s == null ? null : s.trim();
    }

    /** 计算过程的内部载体。 */
    private static class PreparedCalc {
        private WeightRule rule;
        private List<WbReading> readings = new ArrayList<>();
        private List<WbTempItem> tempItems = new ArrayList<>();
        private int missingCount;
    }
}
