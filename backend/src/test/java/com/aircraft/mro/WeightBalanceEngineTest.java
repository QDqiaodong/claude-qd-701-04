package com.aircraft.mro;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aircraft.mro.service.WeightBalanceEngine;

/**
 * 称重/重心引擎纯函数验收：
 * 折线节点命中、节点间线性插值、前后边界越界方向与差值、总重越限不判包线、
 * 公英制换算（含往返与等臂长无损）、重复计算无累积误差、非法数值拦截。
 */
class WeightBalanceEngineTest {

    private static final List<WeightBalanceEngine.EnvelopeNode> FWD = List.of(
            n("35000", "6250"), n("45000", "6350"), n("60000", "6450"), n("70000", "6500"));
    private static final List<WeightBalanceEngine.EnvelopeNode> AFT = List.of(
            n("35000", "7000"), n("45000", "6900"), n("60000", "6800"), n("70000", "6700"));

    private static WeightBalanceEngine.EnvelopeNode n(String w, String a) {
        return new WeightBalanceEngine.EnvelopeNode(new BigDecimal(w), new BigDecimal(a));
    }

    @Test
    void interpolation_hitsNodeExactly() {
        assertEquals(new BigDecimal("6350.000"),
                WeightBalanceEngine.interpolateArm(FWD, new BigDecimal("45000")));
    }

    @Test
    void interpolation_betweenNodesIsLinear() {
        // 45000→6350、60000→6450；46000 处应为 6350 + 100*(1000/15000)=6356.667
        BigDecimal arm = WeightBalanceEngine.interpolateArm(FWD, new BigDecimal("46000"));
        assertEquals(new BigDecimal("6356.667"), arm);

        // 中点
        BigDecimal mid = WeightBalanceEngine.interpolateArm(FWD, new BigDecimal("52500"));
        assertEquals(new BigDecimal("6400.000"), mid);
    }

    @Test
    void interpolation_outsideNodesDoesNotExtrapolate() {
        assertNull(WeightBalanceEngine.interpolateArm(FWD, new BigDecimal("30000")));
        assertNull(WeightBalanceEngine.interpolateArm(FWD, new BigDecimal("70001")));
    }

    @Test
    void evaluate_insideEnvelopeBetweenNodes() {
        // 总重 46000，重心 6600 落在 [6356.667, 6893.333] 内
        WeightBalanceEngine.EnvelopeResult r = WeightBalanceEngine.evaluate(
                new BigDecimal("46000"), new BigDecimal("6600"),
                new BigDecimal("35000"), new BigDecimal("70000"), FWD, AFT);
        assertEquals(WeightBalanceEngine.ENV_IN, r.status());
        assertEquals(new BigDecimal("6356.667"), r.forwardLimit());
        assertTrue(r.forwardMargin().signum() > 0 && r.aftMargin().signum() > 0);
    }

    @Test
    void evaluate_forwardBoundaryViolationReportsDirectionAndDelta() {
        // 总重 45000，前边界 6350；重心 6300 越过前边界 50mm
        WeightBalanceEngine.EnvelopeResult r = WeightBalanceEngine.evaluate(
                new BigDecimal("45000"), new BigDecimal("6300"),
                new BigDecimal("35000"), new BigDecimal("70000"), FWD, AFT);
        assertEquals(WeightBalanceEngine.ENV_OUT, r.status());
        assertTrue(r.message().contains("前边界"), r.message());
        assertTrue(r.message().contains("偏前 50.000"), r.message());
        assertEquals(new BigDecimal("-50.000"), r.forwardMargin());
    }

    @Test
    void evaluate_aftBoundaryViolationReportsDirectionAndDelta() {
        // 总重 45000，后边界 6900；重心 6960 越过后边界 60mm
        WeightBalanceEngine.EnvelopeResult r = WeightBalanceEngine.evaluate(
                new BigDecimal("45000"), new BigDecimal("6960"),
                new BigDecimal("35000"), new BigDecimal("70000"), FWD, AFT);
        assertEquals(WeightBalanceEngine.ENV_OUT, r.status());
        assertTrue(r.message().contains("后边界"), r.message());
        assertTrue(r.message().contains("偏后 60.000"), r.message());
        assertEquals(new BigDecimal("-60.000"), r.aftMargin());
    }

    @Test
    void evaluate_overweightNeverConcludesInside() {
        WeightBalanceEngine.EnvelopeResult over = WeightBalanceEngine.evaluate(
                new BigDecimal("72000"), new BigDecimal("6600"),
                new BigDecimal("35000"), new BigDecimal("70000"), FWD, AFT);
        assertEquals(WeightBalanceEngine.ENV_INDETERMINATE, over.status());
        assertTrue(over.message().contains("超出规则许用最大总重"), over.message());

        WeightBalanceEngine.EnvelopeResult under = WeightBalanceEngine.evaluate(
                new BigDecimal("30000"), new BigDecimal("6600"),
                new BigDecimal("35000"), new BigDecimal("70000"), FWD, AFT);
        assertEquals(WeightBalanceEngine.ENV_INDETERMINATE, under.status());
        assertTrue(under.message().contains("低于规则许用最小总重"), under.message());
    }

    @Test
    void exactConversions_poundAndInch() {
        // 1 lb 精确定义
        assertEquals(new BigDecimal("0.454"), WeightBalanceEngine.toKg(BigDecimal.ONE, "LB"));
        assertEquals(new BigDecimal("100.000"), WeightBalanceEngine.toKg(new BigDecimal("220.462"), "LB"));
        // 1 in = 25.4 mm 精确
        assertEquals(new BigDecimal("25.400"), WeightBalanceEngine.toMm(BigDecimal.ONE, "IN"));
        // 560 in = 14224 mm
        assertEquals(new BigDecimal("14224.000"), WeightBalanceEngine.toMm(new BigDecimal("560"), "IN"));
    }

    @Test
    void roundTrip_metricImperialMetricIsStableAtDisplayPrecision() {
        // kg → lb → kg 在统一精度（3 位）下往返一致：典型空重量级
        List<BigDecimal> samples = List.of(new BigDecimal("142.880"), new BigDecimal("8645.331"),
                new BigDecimal("19100.000"), new BigDecimal("44800.000"), new BigDecimal("0.454"));
        for (BigDecimal kg : samples) {
            BigDecimal lb = WeightBalanceEngine.kgToLb(kg);
            BigDecimal back = WeightBalanceEngine.toKg(lb, "LB");
            assertEquals(kg.setScale(3, RoundingMode.HALF_UP), back,
                    "kg→lb→kg 往返不一致：" + kg);
        }
        // mm → in → mm 等臂长精确无损（25.4 为有限小数）
        for (String mm : new String[]{"200", "560", "6413.393", "10500.000"}) {
            BigDecimal v = new BigDecimal(mm);
            BigDecimal inch = WeightBalanceEngine.mmToIn(v);
            BigDecimal back = WeightBalanceEngine.toMm(inch, "IN");
            assertEquals(v.setScale(3, RoundingMode.HALF_UP), back, "mm→in→mm 往返不一致：" + mm);
        }
    }

    @Test
    void repeatedCalculationProducesIdenticalResult_noAccumulatedError() {
        // 同一组（kg, mm）连续“计算”1000 次，每次都从原数值出发，结果必须逐位相同
        BigDecimal net = new BigDecimal("19100");
        BigDecimal arm = new BigDecimal("7200");
        BigDecimal first = WeightBalanceEngine.roundMoment(net.multiply(arm));
        for (int i = 0; i < 1000; i++) {
            BigDecimal again = WeightBalanceEngine.roundMoment(
                    WeightBalanceEngine.toKg(net, "KG").multiply(WeightBalanceEngine.toMm(arm, "MM")));
            assertEquals(first, again);
        }
        // 重心臂重复相除同样稳定
        BigDecimal moment = new BigDecimal("287280000.000");
        BigDecimal weight = new BigDecimal("44800");
        BigDecimal cg1 = WeightBalanceEngine.cgArm(moment, weight);
        for (int i = 0; i < 1000; i++) {
            assertEquals(cg1, WeightBalanceEngine.cgArm(moment, weight));
        }
        assertNotNull(cg1);
    }

    @Test
    void illegalOrMissingValuesAreRejectedUpstream() {
        assertTrue(WeightBalanceEngine.isIllegallyNegative(new BigDecimal("-1")));
        // 0 总重无法求重心
        assertNull(WeightBalanceEngine.cgArm(new BigDecimal("1000"), BigDecimal.ZERO));
    }
}
