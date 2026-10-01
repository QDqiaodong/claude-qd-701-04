package com.aircraft.mro;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.aircraft.mro.common.BizException;
import com.aircraft.mro.domain.Aircraft;
import com.aircraft.mro.domain.MroDict;
import com.aircraft.mro.dto.WeightDtos;
import com.aircraft.mro.dto.WeightViews;
import com.aircraft.mro.repository.AircraftRepository;
import com.aircraft.mro.service.AdBootstrap;
import com.aircraft.mro.service.WbBootstrap;
import com.aircraft.mro.service.WbConfigService;
import com.aircraft.mro.service.WeighingService;
import com.aircraft.mro.service.WeightRuleService;

/**
 * 模块八端到端验收（H2/MySQL 兼容模式 + JPA 自动建表）：
 * <ol>
 *   <li>折线节点命中 / 节点之间线性插值判定；</li>
 *   <li>前、后边界越界给出方向与精确差值；总重越限、读数缺失、支点不符、数值非法都不得判包线内；</li>
 *   <li>英制 lb/in 称重与公制 kg/mm 同一计算事实，原单位与统一单位双份可查，往返不累积误差；</li>
 *   <li>旧规则版本追溯：历史称重引用旧版冻结，新规则发布不改写历史；</li>
 *   <li>构型调整链：只能从当前版本派生、不能在旧版本分叉、跨机调整项被拒、越界版本精确标注；</li>
 *   <li>无基线飞机照常展示且明确“未建立基线”，不猜测默认重心；</li>
 *   <li>两人复核（登记/复核一/复核二须为三人）且越界称重不能成为基线。</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("adtest")
class WeightBalanceIntegrationTest {

    @Autowired WeightRuleService ruleService;
    @Autowired WeighingService weighingService;
    @Autowired WbConfigService configService;
    @Autowired AircraftRepository aircraftRepository;
    @Autowired JdbcTemplate jdbc;

    /** 关闭模块七与模块八的启动引导（示例数据由各用例精确构造） */
    @TestConfiguration
    static class NoBootstrap {
        @Bean
        @Primary
        AdBootstrap adBootstrap() {
            return new AdBootstrap(null, null, null, null, null, null, null, null, null) {
                @Override public void boot() { }
                @Override public void seedCredentialHistory() { }
                @Override public void ensureBaselineJob() { }
            };
        }

        @Bean
        @Primary
        WbBootstrap wbBootstrap() {
            return new WbBootstrap(null, null, null, null, null, null, null) {
                @Override public void boot() { }
                @Override public void seed() { }
            };
        }
    }

    @BeforeEach
    void clean() {
        for (String t : List.of("wb_adjustment", "wb_config_version", "wb_temp_item",
                "wb_reading", "wb_weighing", "weight_rule_envelope", "weight_rule_pivot",
                "weight_rule", "aircraft")) {
            jdbc.execute("DELETE FROM " + t);
        }
    }

    // ------------------------------------------------------------ 构造工具

    private Long aircraft(String tailNo, String model) {
        Aircraft a = new Aircraft();
        a.setTailNo(tailNo);
        a.setModel(model);
        a.setAirline("验收航空");
        a.setAirworthy("AIRWORTHY");
        a.setTotalFh(BigDecimal.ZERO);
        a.setTotalCycles(0);
        a.setUpdatedAt(LocalDateTime.now());
        return aircraftRepository.save(a).getId();
    }

    private Long publishB737V1(LocalDate effective) {
        return ruleService.publish(ruleService.createDraft(b737Rule(effective)).id(),
                new WeightDtos.RulePublishReq() {{ setEffectiveDate(effective); }}).id();
    }

    private WeightDtos.RuleSaveReq b737Rule(LocalDate effective) {
        WeightDtos.RuleSaveReq r = new WeightDtos.RuleSaveReq();
        r.setModel("B737");
        r.setUnitSystem("METRIC");
        r.setEffectiveDate(effective);
        r.setDatumName("FS0");
        r.setMinWeight(new BigDecimal("35000"));
        r.setMaxWeight(new BigDecimal("70000"));
        r.setPivots(List.of(
                pivot("NOSE", 1, "1850"), pivot("LEFT_MAIN", 2, "7200"),
                pivot("RIGHT_MAIN", 3, "7200")));
        r.setForwardNodes(List.of(
                node("35000", "6250"), node("45000", "6350"),
                node("60000", "6450"), node("70000", "6500")));
        r.setAftNodes(List.of(
                node("35000", "7000"), node("45000", "6900"),
                node("60000", "6800"), node("70000", "6700")));
        return r;
    }

    private WeightDtos.RuleSaveReq b737RuleV2(LocalDate effective) {
        WeightDtos.RuleSaveReq r = b737Rule(effective);
        r.setForwardNodes(List.of(
                node("35000", "6350"), node("45000", "6470"),
                node("60000", "6570"), node("70000", "6620")));
        r.setAftNodes(List.of(
                node("35000", "6980"), node("45000", "6880"),
                node("60000", "6780"), node("70000", "6680")));
        return r;
    }

    private WeightDtos.RuleSaveReq arjRule(LocalDate effective) {
        WeightDtos.RuleSaveReq r = new WeightDtos.RuleSaveReq();
        r.setModel("ARJ21");
        r.setUnitSystem("IMPERIAL");
        r.setEffectiveDate(effective);
        r.setDatumName("FS0");
        r.setMinWeight(new BigDecimal("26000"));   // lb
        r.setMaxWeight(new BigDecimal("62000"));
        r.setPivots(List.of(
                pivot("NOSE", 1, "200"), pivot("LEFT_MAIN", 2, "560"),
                pivot("RIGHT_MAIN", 3, "560")));
        r.setForwardNodes(List.of(
                node("26000", "400"), node("32500", "412"),
                node("45000", "425"), node("62000", "430")));
        r.setAftNodes(List.of(
                node("26000", "560"), node("32500", "545"),
                node("45000", "525"), node("62000", "510")));
        return r;
    }

    private WeightDtos.PivotReq pivot(String code, int order, String arm) {
        WeightDtos.PivotReq p = new WeightDtos.PivotReq();
        p.setPivotCode(code);
        p.setDisplayOrder(order);
        p.setArm(new BigDecimal(arm));
        return p;
    }

    private WeightDtos.NodeReq node(String w, String a) {
        WeightDtos.NodeReq n = new WeightDtos.NodeReq();
        n.setWeight(new BigDecimal(w));
        n.setArm(new BigDecimal(a));
        return n;
    }

    private WeightDtos.WeighingSaveReq weighing(Long aircraftId, LocalDate date, String unitSystem,
                                                String nose, String left, String right) {
        WeightDtos.WeighingSaveReq w = new WeightDtos.WeighingSaveReq();
        w.setAircraftId(aircraftId);
        w.setWeighingDate(date);
        w.setScaleNo("SCALE-T-01");
        w.setUnitSystem(unitSystem);
        w.setTarePerPivot(false);
        w.setCommonTare(BigDecimal.ZERO);
        w.setFuelCondition("排空");
        w.setFuelQty(BigDecimal.ZERO);
        w.setOilCondition("加满");
        w.setOilQty(BigDecimal.ZERO);
        w.setWeighedBy("登记人甲");
        w.setReadings(List.of(
                reading("NOSE", nose, "0"), reading("LEFT_MAIN", left, "0"),
                reading("RIGHT_MAIN", right, "0")));
        return w;
    }

    private WeightDtos.ReadingReq reading(String code, String gross, String tare) {
        WeightDtos.ReadingReq r = new WeightDtos.ReadingReq();
        r.setPivotCode(code);
        r.setGross(gross == null ? null : new BigDecimal(gross));
        r.setTare(new BigDecimal(tare));
        return r;
    }

    private WeightDtos.WeighingConfirmReq confirm(String r1, String r2) {
        WeightDtos.WeighingConfirmReq c = new WeightDtos.WeighingConfirmReq();
        c.setReviewedBy(r1);
        c.setConfirmedBy(r2);
        return c;
    }

    private WeightDtos.DeriveReq derive(Long aircraftId, LocalDate date,
                                       List<WeightDtos.AdjustmentReq> items) {
        WeightDtos.DeriveReq d = new WeightDtos.DeriveReq();
        d.setAircraftId(aircraftId);
        d.setChangeDate(date);
        d.setUnitSystem("METRIC");
        d.setOperator("质量经理");
        d.setItems(items);
        return d;
    }

    private WeightDtos.AdjustmentReq adjustment(String dir, String name, String weight,
                                                String arm, int qty, String card) {
        WeightDtos.AdjustmentReq a = new WeightDtos.AdjustmentReq();
        a.setDirection(dir);
        a.setName(name);
        a.setWeight(new BigDecimal(weight));
        a.setArm(new BigDecimal(arm));
        a.setQuantity(qty);
        a.setReason("验收调整");
        a.setWorkCardNo(card);
        return a;
    }

    // ------------------------------------------------------------ 1. 规则版本与解析

    @Test
    void rule_versioning_and_date_based_resolution() {
        Long v1 = publishB737V1(LocalDate.of(2025, 1, 1));
        Long v2 = ruleService.publish(ruleService.createDraft(b737RuleV2(LocalDate.of(2026, 6, 1))).id(),
                new WeightDtos.RulePublishReq() {{ setEffectiveDate(LocalDate.of(2026, 6, 1)); }}).id();

        // 2026-05 称重仍解析到 V1；2026-07 解析到 V2
        WeightViews.RuleResolvedView may = ruleService.resolveView("B737", LocalDate.of(2026, 5, 1));
        WeightViews.RuleResolvedView jul = ruleService.resolveView("B737", LocalDate.of(2026, 7, 1));
        assertEquals(v1, may.ruleId());
        assertEquals(v2, jul.ruleId());

        // V1 已被取代但仍只读可查
        WeightViews.RuleView old = ruleService.getRule(v1);
        assertEquals(MroDict.RULE_SUPERSEDED, old.status());
        // 历史规则不能改
        BizException ex = assertThrows(BizException.class, () -> ruleService.updateDraft(v1, b737Rule(LocalDate.of(2025,1,1))));
        assertTrue(ex.getMessage().contains("不得修改"));
    }

    @Test
    void rule_validates_envelope_nodes() {
        // 节点不升序
        WeightDtos.RuleSaveReq bad = b737Rule(LocalDate.of(2025, 1, 1));
        bad.setForwardNodes(List.of(node("45000", "6350"), node("35000", "6250"),
                node("60000", "6450"), node("70000", "6500")));
        assertTrue(assertThrows(BizException.class, () -> ruleService.createDraft(bad))
                .getMessage().contains("严格升序"));

        // 折线不覆盖到最大总重
        WeightDtos.RuleSaveReq shortRange = b737Rule(LocalDate.of(2025, 1, 1));
        shortRange.setAftNodes(List.of(node("35000", "7000"), node("60000", "6800")));
        assertTrue(assertThrows(BizException.class, () -> ruleService.createDraft(shortRange))
                .getMessage().contains("不早于许用最大总重"));
    }

    // ------------------------------------------------------------ 2. 称重判定：插值 / 越界 / 非法输入

    @Test
    void weighing_interpolates_between_nodes_and_confirms_baseline() {
        publishB737V1(LocalDate.of(2025, 1, 1));
        Long ac = aircraft("B-6032", "B737");
        // 总重 45040 落在节点之间；前起 6840、主起各 19100 → cg≈6436 在 V1 包线内
        WeightViews.WeighingView wv = weighingService.create(
                weighing(ac, LocalDate.of(2026, 5, 15), "METRIC", "6840", "19100", "19100"));
        assertEquals("IN_ENVELOPE", wv.result().status());
        // 前边界为 45040 处的线性插值：6350 + 100×40/1500 = 6350.267（落在 45000~60000 段）
        assertEquals(new BigDecimal("6350.267"), wv.result().forwardLimit());
        assertEquals(0, wv.missingCount());

        WeightViews.WeighingView confirmed = weighingService.confirm(wv.id(), confirm("复核人乙", "确认人丙"));
        assertEquals("CONFIRMED", confirmed.status());
        assertTrue(confirmed.currentFlag());
    }

    @Test
    void weighing_forward_out_reports_delta_and_cannot_be_baseline() {
        publishB737V1(LocalDate.of(2025, 1, 1));
        Long ac = aircraft("B-6688", "B737");
        WeightViews.WeighingView wv = weighingService.create(
                weighing(ac, LocalDate.of(2026, 8, 2), "METRIC", "14000", "15500", "15500"));
        // cg 5535.556，前边界 6350 → 偏前 814.444
        assertEquals("OUT_OF_ENVELOPE", wv.result().status());
        assertTrue(wv.result().message().contains("前边界"));
        assertTrue(wv.result().message().contains("814.444"), wv.result().message());
        BizException ex = assertThrows(BizException.class,
                () -> weighingService.confirm(wv.id(), confirm("复核人乙", "确认人丙")));
        assertTrue(ex.getMessage().contains("不能据此建立称重基线"));
    }

    @Test
    void weighing_aft_out_via_rear_install_reports_delta() {
        publishB737V1(LocalDate.of(2025, 1, 1));
        Long ac = aircraft("B-6101", "B737");
        // 先给一个包线内基线：总重 45000，cg≈6413
        WeightViews.WeighingView base = weighingService.create(
                weighing(ac, LocalDate.of(2026, 1, 10), "METRIC", "7000", "19000", "19000"));
        assertEquals("IN_ENVELOPE", base.result().status());
        weighingService.confirm(base.id(), confirm("复核人乙", "确认人丙"));
        // 尾部安装 2×1500kg@18000mm → 重心大幅后移越过后边界
        WeightViews.ConfigVersionView v = configService.derive(derive(ac, LocalDate.of(2026, 2, 1),
                List.of(adjustment("INSTALL", "尾部卫星通讯天线配重", "1500", "18000", 2, "WC-2026-0901"))));
        assertEquals("OUT_OF_ENVELOPE", v.result().status());
        assertTrue(v.result().message().contains("后边界"), v.result().message());
        assertTrue(v.result().aftMargin().signum() < 0);
    }

    @Test
    void missing_reading_or_overweight_never_says_inside() {
        publishB737V1(LocalDate.of(2025, 1, 1));
        Long ac = aircraft("B-6102", "B737");

        // 前起读数缺失
        WeightDtos.WeighingSaveReq missing = weighing(ac, LocalDate.of(2026, 3, 1), "METRIC",
                null, "19000", "19000");
        WeightViews.WeighingView wv1 = weighingService.create(missing);
        assertEquals("INDETERMINATE", wv1.result().status());
        assertEquals(1, wv1.missingCount());
        assertNull(wv1.result().totalWeight());
        assertTrue(wv1.result().message().contains("缺失"));

        // 总重超重（80000 > 70000）
        WeightViews.WeighingView wv2 = weighingService.create(
                weighing(ac, LocalDate.of(2026, 3, 2), "METRIC", "20000", "30000", "30000"));
        assertEquals("INDETERMINATE", wv2.result().status());
        assertTrue(wv2.result().message().contains("超出规则许用最大总重"));
    }

    @Test
    void pivot_not_in_rule_and_negative_value_rejected() {
        publishB737V1(LocalDate.of(2025, 1, 1));
        Long ac = aircraft("B-6103", "B737");
        WeightDtos.WeighingSaveReq wrong = weighing(ac, LocalDate.of(2026, 3, 3), "METRIC",
                "7000", "19000", "19000");
        wrong.getReadings().get(0).setPivotCode("TAIL");
        BizException e1 = assertThrows(BizException.class, () -> weighingService.preview(wrong));
        assertTrue(e1.getMessage().contains("不属于当前规则"));

        WeightDtos.WeighingSaveReq neg = weighing(ac, LocalDate.of(2026, 3, 4), "METRIC",
                "-1", "19000", "19000");
        assertTrue(assertThrows(BizException.class, () -> weighingService.preview(neg))
                .getMessage().contains("非法数值"));
    }

    // ------------------------------------------------------------ 3. 公英制双单位 + 往返 + 重复精度

    @Test
    void imperial_weighing_matches_unified_fact_and_keeps_original() {
        ruleService.publish(ruleService.createDraft(arjRule(LocalDate.of(2025, 1, 1))).id(),
                new WeightDtos.RulePublishReq() {{ setEffectiveDate(LocalDate.of(2025, 1, 1)); }});
        Long ac = aircraft("B-3027", "ARJ21");

        WeightDtos.WeighingSaveReq req = weighing(ac, LocalDate.of(2026, 9, 5), "IMPERIAL",
                "4000", "14250", "14250");
        WeightViews.WeighingView first = weighingService.create(req);

        // 原始读数保留 lb（原值不补零，按数值比较）；统一数值为 kg，支点臂长 560in=14224mm
        assertEquals("LB", first.readings().get(1).weightUnit());
        assertEquals(0, first.readings().get(1).grossOriginal().compareTo(new BigDecimal("14250")));
        assertEquals(new BigDecimal("6463.691"), first.readings().get(1).grossKg());
        assertEquals(new BigDecimal("14224.000"), first.readings().get(1).armMm());
        // 总重为各支点净重统一精度（3 位）换算后的合计：1814.369 + 6463.691×2 = 14741.751 kg。
        // 注意口径：先逐支点独立换算（真实磅秤每个支点独立读数）再合计，
        // 而不是把 32500lb 一次性换算（那样会得 14741.752）——前者才与逐支点明细对应同一计算事实。
        BigDecimal sumKg = first.readings().stream()
                .map(WeightViews.ReadingView::netKg).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("14741.751"), sumKg);
        assertEquals(new BigDecimal("14741.751"), first.result().totalWeight());
        assertEquals("IN_ENVELOPE", first.result().status());

        // 同一份入参重放（重复计算）结果逐位相同——不产生累积误差
        WeightViews.WeighingView second = weighingService.preview(req);
        assertEquals(first.result().totalWeight(), second.result().totalWeight());
        assertEquals(first.result().totalMoment(), second.result().totalMoment());
        assertEquals(first.result().cgArm(), second.result().cgArm());
        assertEquals(first.result().forwardLimit(), second.result().forwardLimit());
        assertEquals(first.result().message(), second.result().message());

        // 已保存单据再打开也还是同一冻结事实（不会因重算而漂）
        WeightViews.WeighingView reloaded = weighingService.detail(first.id());
        assertEquals(first.result().totalMoment(), reloaded.result().totalMoment());
        assertEquals(first.result().cgArm(), reloaded.result().cgArm());
    }

    // ------------------------------------------------------------ 4. 旧规则版本追溯（不被新版覆盖）

    @Test
    void new_rule_does_not_recompute_history() {
        Long v1 = publishB737V1(LocalDate.of(2025, 1, 1));
        Long ac = aircraft("B-6032", "B737");
        WeightViews.WeighingView old = weighingService.create(
                weighing(ac, LocalDate.of(2026, 5, 15), "METRIC", "6840", "19100", "19100"));
        assertEquals(v1, old.ruleId());
        assertEquals("IN_ENVELOPE", old.result().status());
        BigDecimal oldFwd = old.result().forwardLimit();
        weighingService.confirm(old.id(), confirm("复核人乙", "确认人丙"));

        // 发布收紧的 V2
        Long v2 = ruleService.publish(ruleService.createDraft(b737RuleV2(LocalDate.of(2026, 6, 1))).id(),
                new WeightDtos.RulePublishReq() {{ setEffectiveDate(LocalDate.of(2026, 6, 1)); }}).id();

        // 历史称重与基线构型版本仍引用 V1、仍是包线内、前边界插值不变
        WeightViews.WeighingView frozen = weighingService.detail(old.id());
        assertEquals(v1, frozen.ruleId());
        assertEquals(oldFwd, frozen.result().forwardLimit());
        assertEquals("IN_ENVELOPE", frozen.result().status());

        WeightViews.ConfigChainView chain = configService.chain(ac);
        WeightViews.ConfigVersionView base = chain.versions().get(0);
        assertEquals(v1, base.ruleId());
        assertEquals("IN_ENVELOPE", base.result().status());

        // 而在 V2 生效后新增的前向调整（把重心拉到 6366）会被新版前边界 6474 判越界
        WeightViews.ConfigVersionView nv = configService.derive(derive(ac, LocalDate.of(2026, 7, 10),
                List.of(adjustment("INSTALL", "前电子舱设备", "600", "1200", 1, "WC-2026-0305"))));
        assertEquals(v2, nv.ruleId());
        assertEquals("OUT_OF_ENVELOPE", nv.result().status());
        assertTrue(nv.result().message().contains("前边界"));
        // 历史基线结果仍按当时展示
        assertEquals("IN_ENVELOPE",
                configService.versionDetail(base.id()).result().status());
    }

    // ------------------------------------------------------------ 5. 构型调整链规则

    @Test
    void config_chain_requires_current_version_and_blocks_fork_and_cross_aircraft() {
        publishB737V1(LocalDate.of(2025, 1, 1));
        Long ac1 = aircraft("B-6032", "B737");
        Long ac2 = aircraft("B-6688", "B737");

        // 无基线不能派生
        BizException noBase = assertThrows(BizException.class,
                () -> configService.derive(derive(ac1, LocalDate.of(2026, 2, 1),
                        List.of(adjustment("INSTALL", "X", "10", "7000", 1, "WC-X")))));
        assertTrue(noBase.getMessage().contains("尚未建立称重基线"));

        WeightViews.WeighingView base = weighingService.create(
                weighing(ac1, LocalDate.of(2026, 1, 1), "METRIC", "7000", "19000", "19000"));
        weighingService.confirm(base.id(), confirm("复核人乙", "确认人丙"));

        WeightViews.ConfigVersionView v1c = configService.derive(derive(ac1, LocalDate.of(2026, 2, 1),
                List.of(adjustment("INSTALL", "厨房设备", "100", "10000", 2, "WC-1"))));
        WeightViews.ConfigVersionView v2c = configService.derive(derive(ac1, LocalDate.of(2026, 3, 1),
                List.of(adjustment("REMOVE", "旧座椅", "20", "6000", 4, "WC-2"))));

        // 试图在旧版本 v1c（已非当前）上分叉 → 拒绝，必须从当前 v2c 继续
        WeightDtos.DeriveReq fork = derive(ac1, LocalDate.of(2026, 4, 1),
                List.of(adjustment("INSTALL", "Y", "10", "7000", 1, "WC-3")));
        fork.setParentVersionId(v1c.id());
        assertTrue(assertThrows(BizException.class, () -> configService.derive(fork))
                .getMessage().contains("不能在历史版本上分叉"));

        // 跨机并入：用 ac2 身份但 parent 指向 ac1 版本 → ac2 无基线，直接拦在“未建立基线”
        WeightDtos.DeriveReq cross = derive(ac2, LocalDate.of(2026, 4, 1),
                List.of(adjustment("INSTALL", "Y", "10", "7000", 1, "WC-3")));
        cross.setParentVersionId(v2c.id());
        assertTrue(assertThrows(BizException.class, () -> configService.derive(cross))
                .getMessage().contains("尚未建立称重基线"));

        // 链完整：BASELINE → ADJ → ADJ，只有最后一个 current
        WeightViews.ConfigChainView chain = configService.chain(ac1);
        assertEquals(3, chain.versions().size());
        assertEquals("BASELINE", chain.versions().get(0).source());
        assertEquals("ADJUSTMENT", chain.versions().get(1).source());
        assertEquals("ADJUSTMENT", chain.versions().get(2).source());
        assertEquals(v2c.id(), chain.currentVersionId());
        assertEquals(1, chain.versions().stream().filter(WeightViews.ConfigVersionView::currentFlag).count());

        // 版本对比（基线 → 当前）：总重增量 = +200 -80 = +120kg；目标版本只含本次拆除 4 件
        WeightViews.VersionCompareView cmp = configService.compare(ac1, null, null);
        assertEquals(new BigDecimal("120.000"), cmp.deltaWeight());
        assertEquals(1, cmp.toAdjustments().size());
        assertEquals(4, cmp.toAdjustments().stream()
                .mapToInt(WeightViews.AdjustmentView::quantity).sum());
        assertEquals(0, cmp.from().adjustmentCount());

        // 对比指定的两个调整版本 v1c → v2c，只看到拆除的 -80kg
        WeightViews.VersionCompareView step = configService.compare(ac1, v1c.id(), v2c.id());
        assertEquals(new BigDecimal("-80.000"), step.deltaWeight());
    }

    @Test
    void reweighing_opens_new_frozen_chain() {
        publishB737V1(LocalDate.of(2025, 1, 1));
        Long ac = aircraft("B-6032", "B737");
        WeightViews.WeighingView b1 = weighingService.create(
                weighing(ac, LocalDate.of(2026, 1, 1), "METRIC", "7000", "19000", "19000"));
        weighingService.confirm(b1.id(), confirm("复核人乙", "确认人丙"));
        configService.derive(derive(ac, LocalDate.of(2026, 2, 1),
                List.of(adjustment("INSTALL", "设备", "50", "7000", 1, "WC-1"))));
        assertEquals(2, configService.chain(ac).versions().size());

        // 重新称重确认 → 旧链冻结，新链根为新基线（旧版本仍可查）
        WeightViews.WeighingView b2 = weighingService.create(
                weighing(ac, LocalDate.of(2026, 9, 1), "METRIC", "7100", "19000", "19000"));
        weighingService.confirm(b2.id(), confirm("复核人乙", "确认人丙"));
        WeightViews.ConfigChainView chain = configService.chain(ac);
        assertEquals(3, chain.versions().size());
        WeightViews.ConfigVersionView newBase = chain.versions().get(2);
        assertEquals("BASELINE", newBase.source());
        assertTrue(newBase.currentFlag());
        assertFalse(chain.versions().get(0).currentFlag());
        assertFalse(chain.versions().get(1).currentFlag());
    }

    // ------------------------------------------------------------ 6. 无基线飞机台账

    @Test
    void fleet_lists_baseless_aircraft_without_guessing_cg() {
        publishB737V1(LocalDate.of(2025, 1, 1));
        aircraft("B-9001", "B737"); // 无任何称重
        List<WeightViews.AircraftWeightStatusView> fleet = configService.fleetStatus();
        WeightViews.AircraftWeightStatusView none = fleet.stream()
                .filter(f -> f.tailNo().equals("B-9001")).findFirst().orElseThrow();
        assertFalse(none.baselineEstablished());
        assertNull(none.currentCgArm());
        assertNull(none.currentTotalWeight());
        assertNull(none.envelopeStatus());
    }

    // ------------------------------------------------------------ 7. 两人复核人身份门禁

    @Test
    void confirmation_requires_three_distinct_people() {
        publishB737V1(LocalDate.of(2025, 1, 1));
        Long ac = aircraft("B-6032", "B737");
        WeightViews.WeighingView wv = weighingService.create(
                weighing(ac, LocalDate.of(2026, 5, 15), "METRIC", "6840", "19100", "19100"));
        assertTrue(assertThrows(BizException.class,
                () -> weighingService.confirm(wv.id(), confirm("登记人甲", "确认人丙")))
                .getMessage().contains("第一复核人不能与称重登记人"));
        assertTrue(assertThrows(BizException.class,
                () -> weighingService.confirm(wv.id(), confirm("复核人乙", "复核人乙")))
                .getMessage().contains("第二复核人不能与第一复核人"));
        // 合法三人确认成功
        assertTrue(weighingService.confirm(wv.id(), confirm("复核人乙", "确认人丙")).currentFlag());
        // 重复确认拒绝
        assertTrue(assertThrows(BizException.class,
                () -> weighingService.confirm(wv.id(), confirm("复核人乙", "确认人丙")))
                .getMessage().contains("不能重复复核"));
    }
}
