package com.aircraft.mro.service;

import static com.aircraft.mro.service.WeightBalanceEngine.IMPERIAL;
import static com.aircraft.mro.service.WeightBalanceEngine.KIND_DEDUCT;
import static com.aircraft.mro.service.WeightBalanceEngine.METRIC;
import static com.aircraft.mro.service.WeightBalanceEngine.DIR_INSTALL;
import static com.aircraft.mro.service.WeightBalanceEngine.UNIT_IN;
import static com.aircraft.mro.service.WeightBalanceEngine.UNIT_KG;
import static com.aircraft.mro.service.WeightBalanceEngine.UNIT_LB;
import static com.aircraft.mro.service.WeightBalanceEngine.UNIT_MM;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.aircraft.mro.domain.MroDict;
import com.aircraft.mro.domain.WbWeighing;
import com.aircraft.mro.dto.WeightDtos;
import com.aircraft.mro.dto.WeightViews;
import com.aircraft.mro.repository.AircraftRepository;
import com.aircraft.mro.repository.WbWeighingRepository;
import com.aircraft.mro.repository.WeightRuleRepository;

/**
 * 模块八启动引导（幂等，可反复执行）：
 * <ol>
 *   <li>B737 两版称重规则：V1（2025-01-01 生效，历史称重引用它）、V2（2026-06-01 生效，包线收紧）；</li>
 *   <li>ARJ21 英制规则 V1（lb/in 录入，验证公英制换算与原单位保留）；A320 一版公制规则；</li>
 *   <li>B-6032 一次合格称重（公制，含临时扣除项）→ 两人复核成当前基线 → 两条构型调整版本；</li>
 *   <li>B-6688 一次【重心越过前边界】的待复核称重（演示越界方向/差值，且不能成为基线）；</li>
 *   <li>B-3027（ARJ21）一次合格英制称重（演示 lb/in 登记、kg/mm 判定双单位同一事实）。</li>
 * </ol>
 * 规则/称重单均带固定编号，重复启动不重复生成；历史版本状态（SUPERSEDED/冻结链）永不改写。
 */
@Component
public class WbBootstrap {

    private static final Logger log = LoggerFactory.getLogger(WbBootstrap.class);

    private final WeightRuleRepository ruleRepository;
    private final WeightRuleService ruleService;
    private final WeighingService weighingService;
    private final WbConfigService configService;
    private final WbWeighingRepository weighingRepository;
    private final AircraftRepository aircraftRepository;
    private final TransactionTemplate tx;

    public WbBootstrap(WeightRuleRepository ruleRepository,
                       WeightRuleService ruleService,
                       WeighingService weighingService,
                       @Lazy WbConfigService configService,
                       WbWeighingRepository weighingRepository,
                       AircraftRepository aircraftRepository,
                       TransactionTemplate tx) {
        this.ruleRepository = ruleRepository;
        this.ruleService = ruleService;
        this.weighingService = weighingService;
        this.configService = configService;
        this.weighingRepository = weighingRepository;
        this.aircraftRepository = aircraftRepository;
        this.tx = tx;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(30)
    public void boot() {
        // 整段引导在单个事务内原子提交；失败整体回滚，不阻断应用启动
        tx.executeWithoutResult(status -> {
            try {
                seed();
            } catch (Exception e) {
                log.warn("模块八示例数据引导跳过：{}", e.getMessage());
                status.setRollbackOnly();
            }
        });
    }

    public void seed() {
        seedRules();
        seedB6032BaselineChain();
        seedB6688ForwardOut();
        seedB3027Imperial();
    }

    // ---------------------------------------------------------------- 规则

    private void seedRules() {
        // ---- B737 V1（2025-01-01 生效）公制 kg/mm ----
        ensureRule("WR-B737-V1", () -> {
            WeightDtos.RuleSaveReq r = baseRule("B737", METRIC, LocalDate.of(2025, 1, 1));
            r.setMinWeight(kg(35000));
            r.setMaxWeight(kg(70000));
            r.setPivots(List.of(
                    pivot("NOSE", 1, mm(1850)),
                    pivot("LEFT_MAIN", 2, mm(7200)),
                    pivot("RIGHT_MAIN", 3, mm(7200))));
            // 前边界随重量后移、后边界随重量前移（折线，非固定数）
            r.setForwardNodes(List.of(
                    node(kg(35000), mm(6250)), node(kg(45000), mm(6350)),
                    node(kg(60000), mm(6450)), node(kg(70000), mm(6500))));
            r.setAftNodes(List.of(
                    node(kg(35000), mm(7000)), node(kg(45000), mm(6900)),
                    node(kg(60000), mm(6800)), node(kg(70000), mm(6700))));
            return r;
        }, LocalDate.of(2025, 1, 1));

        // ---- B737 V2（2026-06-01 生效）前边界整体收紧 120mm，演示“新规则不改写历史称重” ----
        ensureRule("WR-B737-V2", () -> {
            WeightDtos.RuleSaveReq r = baseRule("B737", METRIC, LocalDate.of(2026, 6, 1));
            r.setMinWeight(kg(35000));
            r.setMaxWeight(kg(70000));
            r.setPivots(List.of(
                    pivot("NOSE", 1, mm(1850)),
                    pivot("LEFT_MAIN", 2, mm(7200)),
                    pivot("RIGHT_MAIN", 3, mm(7200))));
            r.setForwardNodes(List.of(
                    node(kg(35000), mm(6350)), node(kg(45000), mm(6470)),
                    node(kg(60000), mm(6570)), node(kg(70000), mm(6620))));
            r.setAftNodes(List.of(
                    node(kg(35000), mm(6980)), node(kg(45000), mm(6880)),
                    node(kg(60000), mm(6780)), node(kg(70000), mm(6680))));
            return r;
        }, LocalDate.of(2026, 6, 1));

        // ---- ARJ21 V1 英制 lb/in（2025-01-01 生效）----
        ensureRule("WR-ARJ21-V1", () -> {
            WeightDtos.RuleSaveReq r = baseRule("ARJ21", IMPERIAL, LocalDate.of(2025, 1, 1));
            r.setMinWeight(lb(26000));
            r.setMaxWeight(lb(62000));
            r.setPivots(List.of(
                    pivot("NOSE", 1, inch(200)),
                    pivot("LEFT_MAIN", 2, inch(560)),
                    pivot("RIGHT_MAIN", 3, inch(560))));
            r.setForwardNodes(List.of(
                    node(lb(26000), inch(400)), node(lb(32500), inch(412)),
                    node(lb(45000), inch(425)), node(lb(62000), inch(430))));
            r.setAftNodes(List.of(
                    node(lb(26000), inch(560)), node(lb(32500), inch(545)),
                    node(lb(45000), inch(525)), node(lb(62000), inch(510))));
            return r;
        }, LocalDate.of(2025, 1, 1));

        // ---- A320 V1 公制（供该机型飞机称重，示例机队里不预置基线，保留“未建立基线”演示）----
        ensureRule("WR-A320-V1", () -> {
            WeightDtos.RuleSaveReq r = baseRule("A320", METRIC, LocalDate.of(2025, 1, 1));
            r.setMinWeight(kg(34000));
            r.setMaxWeight(kg(78000));
            r.setPivots(List.of(
                    pivot("NOSE", 1, mm(1700)),
                    pivot("LEFT_MAIN", 2, mm(7000)),
                    pivot("RIGHT_MAIN", 3, mm(7000))));
            r.setForwardNodes(List.of(
                    node(kg(34000), mm(6100)), node(kg(48000), mm(6200)),
                    node(kg(62000), mm(6320)), node(kg(78000), mm(6420))));
            r.setAftNodes(List.of(
                    node(kg(34000), mm(6900)), node(kg(48000), mm(6800)),
                    node(kg(62000), mm(6720)), node(kg(78000), mm(6620))));
            return r;
        }, LocalDate.of(2025, 1, 1));
    }

    // ---------------------------------------------------------------- B-6032 基线 + 调整链

    private void seedB6032BaselineChain() {
        Long aircraftId = requireAircraftId("B-6032");
        if (aircraftId == null) {
            return;
        }
        LocalDate date = LocalDate.of(2026, 5, 15);

        WbWeighing weighing = ensureWeighing("WB-2026-0001", () -> {
            WeightDtos.WeighingSaveReq w = new WeightDtos.WeighingSaveReq();
            w.setAircraftId(aircraftId);
            w.setWeighingDate(date);
            w.setScaleNo("SCALE-HX-07（2026 年检合格）");
            w.setUnitSystem(METRIC);
            w.setTarePerPivot(true);
            w.setFuelCondition("燃油已排空至不可用残余");
            w.setFuelQty(kg(0));
            w.setOilCondition("滑油加满至刻度线");
            w.setOilQty(kg(18));
            w.setRemark("定检 A 检期间实机称重，水平姿态 0°±0.2°");
            w.setWeighedBy("机械员陈志强");
            w.setReadings(List.of(
                    reading("NOSE", kg(6800), kg(0)),
                    reading("LEFT_MAIN", kg(19100), kg(0)),
                    reading("RIGHT_MAIN", kg(19100), kg(0))));
            // 称重时飞机上带了一件地面支撑工装（200kg，臂 1500mm），须扣除
            w.setTempItems(List.of(temp(KIND_DEDUCT, "地面支撑工装", kg(200), mm(1500),
                    UNIT_KG, UNIT_MM, "称重后取下，空重不应包含")));
            return w;
        });
        if (weighing != null && MroDict.WB_DRAFT.equals(weighing.getStatus())) {
            WeightDtos.WeighingConfirmReq c = new WeightDtos.WeighingConfirmReq();
            c.setReviewedBy("检验员罗建军");
            c.setConfirmedBy("质量经理何国栋");
            weighingService.confirm(weighing.getId(), c);
        }

        // 构型调整 1（当前基线之后，按收紧后的 V2 判定）：后机身加装 600kg，把重心向后拉回到 V2 包线内（合格调整版）
        if (configService.chain(aircraftId).versions().size() <= 1) {
            WeightDtos.DeriveReq d = new WeightDtos.DeriveReq();
            d.setAircraftId(aircraftId);
            d.setChangeDate(LocalDate.of(2026, 6, 20));
            d.setUnitSystem(METRIC);
            d.setOperator("质量经理何国栋");
            d.setItems(List.of(
                    adjustment(DIR_INSTALL, "后厨房水加热器", kg(300), mm(12000), 1,
                            UNIT_KG, UNIT_MM, "客舱厨房升级（后段配重）", "WC-2026-0301"),
                    adjustment(DIR_INSTALL, "后货舱滑油储量罐", kg(300), mm(12000), 1,
                            UNIT_KG, UNIT_MM, "配平前机身改装的后段补偿重量", "WC-2026-0301")));
            configService.derive(d);
        }

        // 构型调整 2（按收紧后的 V2 判定）：前电子舱补装 600kg，重心前移越过 V2 前边界 → 越界版本
        if (configService.chain(aircraftId).versions().size() <= 2) {
            WeightDtos.DeriveReq d = new WeightDtos.DeriveReq();
            d.setAircraftId(aircraftId);
            d.setChangeDate(LocalDate.of(2026, 7, 10));
            d.setUnitSystem(METRIC);
            d.setOperator("质量经理何国栋");
            d.setItems(List.of(
                    adjustment(DIR_INSTALL, "前电子舱新增配电与雷达组件", kg(600), mm(1200), 1,
                            UNIT_KG, UNIT_MM, "气象雷达升级，重量集中在前机身", "WC-2026-0305")));
            configService.derive(d);
        }
    }

    // ---------------------------------------------------------------- B-6688 前边界越界（待复核）

    private void seedB6688ForwardOut() {
        Long aircraftId = requireAircraftId("B-6688");
        if (aircraftId == null) {
            return;
        }
        ensureWeighing("WB-2026-0002", () -> {
            WeightDtos.WeighingSaveReq w = new WeightDtos.WeighingSaveReq();
            w.setAircraftId(aircraftId);
            w.setWeighingDate(LocalDate.of(2026, 8, 2));
            w.setScaleNo("SCALE-DY-11");
            w.setUnitSystem(METRIC);
            w.setTarePerPivot(false);
            w.setCommonTare(kg(0));
            w.setFuelCondition("排空");
            w.setFuelQty(kg(0));
            w.setOilCondition("加满");
            w.setOilQty(kg(18));
            w.setRemark("前货舱刚加装配重后复称：读数显示重心明显偏前（前边界越界示例）");
            w.setWeighedBy("机械员王大鹏");
            // 前起承重过大：总重仍在范围内，但重心臂 5535.6mm 落在前边界 6350mm 之前
            w.setReadings(List.of(
                    reading("NOSE", kg(14000), null),
                    reading("LEFT_MAIN", kg(15500), null),
                    reading("RIGHT_MAIN", kg(15500), null)));
            return w;
        });
        // 保持 DRAFT：服务端不允许把越界称重确认为基线（验收点）
    }

    // ---------------------------------------------------------------- B-3027 英制称重（待复核，演示双单位）

    private void seedB3027Imperial() {
        Long aircraftId = requireAircraftId("B-3027");
        if (aircraftId == null) {
            return;
        }
        ensureWeighing("WB-2026-0003", () -> {
            WeightDtos.WeighingSaveReq w = new WeightDtos.WeighingSaveReq();
            w.setAircraftId(aircraftId);
            w.setWeighingDate(LocalDate.of(2026, 9, 5));
            w.setScaleNo("SCALE-BJ-02（lb 档）");
            w.setUnitSystem(IMPERIAL);
            w.setTarePerPivot(true);
            w.setFuelCondition("排空至不可用油");
            w.setFuelQty(lb(0));
            w.setOilCondition("加满");
            w.setOilQty(lb(40));
            w.setRemark("支线机按英制手册称重（lb/in），系统自动换算 kg/mm 判定（公英制双单位示例）");
            w.setWeighedBy("机械员赵岩");
            // 毛重含轮挡皮重：前 4100-100=4000，主 14300-50=14250
            w.setReadings(List.of(
                    reading("NOSE", lb(4100), lb(100)),
                    reading("LEFT_MAIN", lb(14300), lb(50)),
                    reading("RIGHT_MAIN", lb(14300), lb(50))));
            return w;
        });
    }

    // ---------------------------------------------------------------- 幂等原语

    private Long ensureRule(String ruleNo, java.util.function.Supplier<WeightDtos.RuleSaveReq> supplier,
                            LocalDate effectiveDate) {
        Optional<com.aircraft.mro.domain.WeightRule> exists = ruleRepository.findAllByOrderByModelAscVersionNoDesc()
                .stream().filter(r -> ruleNo.equals(r.getRuleNo())).findFirst();
        if (exists.isPresent()) {
            return exists.get().getId();
        }
        Long id = ruleService.createDraft(supplier.get()).id();
        WeightDtos.RulePublishReq pub = new WeightDtos.RulePublishReq();
        pub.setEffectiveDate(effectiveDate);
        ruleService.publish(id, pub); // 用草稿自身 id 发布；不要用返回对象（merge 场景下 id 可能未回填）
        return id;
    }
    private WbWeighing ensureWeighing(String weighingNo,
                                     java.util.function.Supplier<WeightDtos.WeighingSaveReq> supplier) {
        return weighingRepository.findByWeighingNo(weighingNo)
                .orElseGet(() -> {
                    WeightViews.WeighingView created = weighingService.create(supplier.get());
                    return weighingRepository.findById(created.id()).orElseThrow();
                });
    }

    private Long requireAircraftId(String tailNo) {
        return aircraftRepository.findByTailNo(tailNo)
                .map(a -> a.getId())
                .orElse(null); // 台账里没有该飞机时静默跳过（独立测试库无飞机数据）
    }

    // ---------------------------------------------------------------- 构造小工具

    private WeightDtos.RuleSaveReq baseRule(String model, String unitSystem, LocalDate effective) {
        WeightDtos.RuleSaveReq r = new WeightDtos.RuleSaveReq();
        r.setModel(model);
        r.setUnitSystem(unitSystem);
        r.setEffectiveDate(effective);
        r.setDatumName("基准面 FS0（机身站位零点）");
        r.setDatumNote("水平姿态称重，臂长为各支点至基准面的纵向距离；统一换算 kg/mm 判定");
        return r;
    }

    private WeightDtos.PivotReq pivot(String code, int order, BigDecimal arm) {
        WeightDtos.PivotReq p = new WeightDtos.PivotReq();
        p.setPivotCode(code);
        p.setDisplayOrder(order);
        p.setArm(arm);
        return p;
    }

    private WeightDtos.NodeReq node(BigDecimal weight, BigDecimal arm) {
        WeightDtos.NodeReq n = new WeightDtos.NodeReq();
        n.setWeight(weight);
        n.setArm(arm);
        return n;
    }
    private WeightDtos.ReadingReq reading(String code, BigDecimal gross, BigDecimal tare) {
        WeightDtos.ReadingReq r = new WeightDtos.ReadingReq();
        r.setPivotCode(code);
        r.setGross(gross);
        r.setTare(tare);
        return r;
    }

    private WeightDtos.TempItemReq temp(String kind, String name, BigDecimal weight, BigDecimal arm,
                                        String weightUnit, String armUnit, String reason) {
        WeightDtos.TempItemReq t = new WeightDtos.TempItemReq();
        t.setKind(kind);
        t.setName(name);
        t.setWeight(weight);
        t.setArm(arm);
        t.setWeightUnit(weightUnit);
        t.setArmUnit(armUnit);
        t.setReason(reason);
        return t;
    }

    private WeightDtos.AdjustmentReq adjustment(String direction, String name, BigDecimal weight,
                                                BigDecimal arm, int qty, String weightUnit,
                                                String armUnit, String reason, String cardNo) {
        WeightDtos.AdjustmentReq a = new WeightDtos.AdjustmentReq();
        a.setDirection(direction);
        a.setName(name);
        a.setWeight(weight);
        a.setArm(arm);
        a.setQuantity(qty);
        a.setWeightUnit(weightUnit);
        a.setArmUnit(armUnit);
        a.setReason(reason);
        a.setWorkCardNo(cardNo);
        return a;
    }

    private BigDecimal kg(Number v) {
        return new BigDecimal(v.toString()).setScale(3);
    }

    private BigDecimal lb(Number v) {
        return new BigDecimal(v.toString()).setScale(3);
    }

    private BigDecimal mm(Number v) {
        return new BigDecimal(v.toString()).setScale(3);
    }

    private BigDecimal inch(Number v) {
        return new BigDecimal(v.toString()).setScale(3);
    }
}
