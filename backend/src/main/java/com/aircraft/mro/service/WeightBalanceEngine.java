package com.aircraft.mro.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 飞机称重与重心计算引擎（纯函数，无状态）。
 *
 * <h3>单位与精度口径</h3>
 * 统一单位为千克(kg)与毫米(mm)：
 * <ul>
 *   <li>1 lb = 0.45359237 kg（国际磅精确定义）；</li>
 *   <li>1 in = 25.4 mm（精确定义，十进制有限小数，公英制等臂长可精确互转）。</li>
 * </ul>
 * 重量/臂长统一在 {@value #WEIGHT_SCALE} / {@value #ARM_SCALE} 位小数下定标（HALF_UP），
 * 力矩为 kg·mm、{@value #MOMENT_SCALE} 位小数。换算只在【录入时】做一次并冻结入库；
 * 页面上的英制/公制切换只是对同一冻结事实的视图换算，重复计算得到完全相同的数值，不产生累积误差。
 */
public final class WeightBalanceEngine {

    private WeightBalanceEngine() {
    }

    public static final String METRIC = "METRIC";
    public static final String IMPERIAL = "IMPERIAL";
    public static final String UNIT_KG = "KG";
    public static final String UNIT_LB = "LB";
    public static final String UNIT_MM = "MM";
    public static final String UNIT_IN = "IN";

    public static final String BOUNDARY_FORWARD = "FORWARD";
    public static final String BOUNDARY_AFT = "AFT";

    public static final String DIR_INSTALL = "INSTALL";
    public static final String DIR_REMOVE = "REMOVE";
    public static final String KIND_ADD = "ADD";
    public static final String KIND_DEDUCT = "DEDUCT";

    /** 包线内 */
    public static final String ENV_IN = "IN_ENVELOPE";
    /** 包线外（总重合法但重心越过前/后边界） */
    public static final String ENV_OUT = "OUT_OF_ENVELOPE";
    /** 无法判定：读数缺失、支点不匹配、数值非法、总重越限等——任何情况下都不允许给“包线内” */
    public static final String ENV_INDETERMINATE = "INDETERMINATE";

    public static final int WEIGHT_SCALE = 3;
    public static final int ARM_SCALE = 3;
    public static final int MOMENT_SCALE = 3;
    /** 视图反向换算（kg→lb、mm→in）保留 6 位小数，保证往返不再叠加可见误差 */
    public static final int CONVERT_BACK_SCALE = 6;

    public static final BigDecimal LB_TO_KG = new BigDecimal("0.45359237");
    public static final BigDecimal KG_TO_LB = new BigDecimal("2.2046226218487758");
    public static final BigDecimal IN_TO_MM = new BigDecimal("25.4");

    // ---------------------------------------------------------------- 单位换算

    public static boolean isMetricSystem(String unitSystem) {
        return METRIC.equals(unitSystem);
    }

    public static String weightUnitOf(String unitSystem) {
        return isMetricSystem(unitSystem) ? UNIT_KG : UNIT_LB;
    }

    public static String armUnitOf(String unitSystem) {
        return isMetricSystem(unitSystem) ? UNIT_MM : UNIT_IN;
    }

    /** 任意重量单位 → kg（3 位定标）。null 原样返回（缺失交给校验层判定）。 */
    public static BigDecimal toKg(BigDecimal value, String unit) {
        if (value == null) {
            return null;
        }
        if (UNIT_KG.equals(unit)) {
            return value.setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);
        }
        if (UNIT_LB.equals(unit)) {
            return value.multiply(LB_TO_KG).setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);
        }
        throw new IllegalArgumentException("未知重量单位：" + unit);
    }

    /** 任意臂长单位 → mm（3 位定标）。 */
    public static BigDecimal toMm(BigDecimal value, String unit) {
        if (value == null) {
            return null;
        }
        if (UNIT_MM.equals(unit)) {
            return value.setScale(ARM_SCALE, RoundingMode.HALF_UP);
        }
        if (UNIT_IN.equals(unit)) {
            return value.multiply(IN_TO_MM).setScale(ARM_SCALE, RoundingMode.HALF_UP);
        }
        throw new IllegalArgumentException("未知臂长单位：" + unit);
    }

    /** kg → lb（视图换算，6 位中间精度，展示再取 3 位） */
    public static BigDecimal kgToLb(BigDecimal kg) {
        if (kg == null) {
            return null;
        }
        return kg.multiply(KG_TO_LB).setScale(CONVERT_BACK_SCALE, RoundingMode.HALF_UP);
    }

    /** mm → in（视图换算，25.4 为精确有限小数，等臂长往返无损） */
    public static BigDecimal mmToIn(BigDecimal mm) {
        if (mm == null) {
            return null;
        }
        return mm.divide(IN_TO_MM, CONVERT_BACK_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal roundWeight(BigDecimal v) {
        return v == null ? null : v.setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal roundArm(BigDecimal v) {
        return v == null ? null : v.setScale(ARM_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal roundMoment(BigDecimal v) {
        return v == null ? null : v.setScale(MOMENT_SCALE, RoundingMode.HALF_UP);
    }

    /** 非法数值判定：null 由调用方区分缺失；非有限、负数一律非法。 */
    public static boolean isIllegallyNegative(BigDecimal v) {
        return v != null && v.signum() < 0;
    }

    // ---------------------------------------------------------------- 包线节点

    /** 折线节点（统一单位 kg/mm）。 */
    public record EnvelopeNode(BigDecimal weight, BigDecimal arm) {
    }

    /**
     * 包线判定结果。
     *
     * @param status         IN_ENVELOPE / OUT_OF_ENVELOPE / INDETERMINATE
     * @param forwardLimit   该总重下线性插值得出的前边界臂长 mm（无法判定时为 null）
     * @param aftLimit       后边界臂长 mm
     * @param forwardMargin  重心距前边界余量 mm（cg−前边界，负=越过前边界）
     * @param aftMargin      重心距后边界余量 mm（后边界−cg，负=越过后边界）
     * @param message        中文判定说明（含越界方向与差值）
     */
    public record EnvelopeResult(
            String status,
            BigDecimal forwardLimit,
            BigDecimal aftLimit,
            BigDecimal forwardMargin,
            BigDecimal aftMargin,
            String message) {
    }

    /**
     * 在总重处对一条折线做线性插值。重量恰为节点取节点值；落在两节点之间按比例插值；
     * 落在折线覆盖区间之外不做外推（返回 null，由上层判“无法判定”）。
     */
    public static BigDecimal interpolateArm(List<EnvelopeNode> nodes, BigDecimal weight) {
        if (nodes == null || nodes.isEmpty() || weight == null) {
            return null;
        }
        List<EnvelopeNode> sorted = nodes.stream()
                .sorted(Comparator.comparing(EnvelopeNode::weight))
                .toList();
        BigDecimal first = sorted.get(0).weight();
        BigDecimal last = sorted.get(sorted.size() - 1).weight();
        if (weight.compareTo(first) < 0 || weight.compareTo(last) > 0) {
            return null;
        }
        for (int i = 0; i < sorted.size(); i++) {
            EnvelopeNode cur = sorted.get(i);
            int cmp = weight.compareTo(cur.weight());
            if (cmp == 0) {
                return roundArm(cur.arm());
            }
            if (cmp < 0) {
                EnvelopeNode prev = sorted.get(i - 1);
                BigDecimal w1 = prev.weight();
                BigDecimal w2 = cur.weight();
                BigDecimal span = w2.subtract(w1);
                if (span.signum() == 0) {
                    return roundArm(cur.arm());
                }
                // arm1 + (arm2-arm1) * (w-w1)/(w2-w1)
                BigDecimal ratio = weight.subtract(w1).divide(span, 10, RoundingMode.HALF_UP);
                BigDecimal arm = prev.arm().add(cur.arm().subtract(prev.arm()).multiply(ratio));
                return roundArm(arm);
            }
        }
        return null;
    }

    /**
     * 包线判定：先查总重是否在许用范围，再对前后折线在该总重下插值，最后比较重心臂。
     */
    public static EnvelopeResult evaluate(BigDecimal totalWeight,
                                          BigDecimal cgArm,
                                          BigDecimal minWeight,
                                          BigDecimal maxWeight,
                                          List<EnvelopeNode> forwardNodes,
                                          List<EnvelopeNode> aftNodes) {
        if (totalWeight == null || cgArm == null || minWeight == null || maxWeight == null) {
            return new EnvelopeResult(ENV_INDETERMINATE, null, null, null, null,
                    "计算结果缺失，无法判定包线");
        }
        if (totalWeight.signum() <= 0) {
            return new EnvelopeResult(ENV_INDETERMINATE, null, null, null, null,
                    "总重为 0 或非法数值，无法计算重心臂");
        }
        // 总重超出许用范围：不允许得出包线结论（既不算内也不按边界外推）
        if (totalWeight.compareTo(minWeight) < 0) {
            BigDecimal delta = roundWeight(minWeight.subtract(totalWeight));
            return new EnvelopeResult(ENV_INDETERMINATE, null, null, null, null,
                    "总重 " + roundWeight(totalWeight) + " kg 低于规则许用最小总重 "
                            + roundWeight(minWeight) + " kg（差 " + delta + " kg），不能判定包线");
        }
        if (totalWeight.compareTo(maxWeight) > 0) {
            BigDecimal delta = roundWeight(totalWeight.subtract(maxWeight));
            return new EnvelopeResult(ENV_INDETERMINATE, null, null, null, null,
                    "总重 " + roundWeight(totalWeight) + " kg 超出规则许用最大总重 "
                            + roundWeight(maxWeight) + " kg（超重 " + delta + " kg），不能判定包线");
        }
        BigDecimal forward = interpolateArm(forwardNodes, totalWeight);
        BigDecimal aft = interpolateArm(aftNodes, totalWeight);
        if (forward == null || aft == null) {
            return new EnvelopeResult(ENV_INDETERMINATE, null, null, null, null,
                    "包线折线节点不覆盖总重 " + roundWeight(totalWeight)
                            + " kg，规则数据不完整，不能判定包线");
        }
        if (forward.compareTo(aft) > 0) {
            return new EnvelopeResult(ENV_INDETERMINATE, forward, aft, null, null,
                    "该总重下前边界 " + forward + " mm 反而大于后边界 " + aft + " mm，规则矛盾，不能判定");
        }
        BigDecimal cg = roundArm(cgArm);
        BigDecimal fwdMargin = roundArm(cg.subtract(forward));
        BigDecimal aftMargin = roundArm(aft.subtract(cg));
        if (fwdMargin.signum() < 0) {
            BigDecimal over = roundArm(forward.subtract(cg));
            return new EnvelopeResult(ENV_OUT, forward, aft, fwdMargin, aftMargin,
                    "重心臂 " + cg + " mm 越过【前边界】" + forward + " mm，重心偏前 "
                            + over + " mm（允许范围 " + forward + " ~ " + aft + " mm）");
        }
        if (aftMargin.signum() < 0) {
            BigDecimal over = roundArm(cg.subtract(aft));
            return new EnvelopeResult(ENV_OUT, forward, aft, fwdMargin, aftMargin,
                    "重心臂 " + cg + " mm 越过【后边界】" + aft + " mm，重心偏后 "
                            + over + " mm（允许范围 " + forward + " ~ " + aft + " mm）");
        }
        return new EnvelopeResult(ENV_IN, forward, aft, fwdMargin, aftMargin,
                "重心臂 " + cg + " mm 落在包线内（前边界 " + forward + " mm，后边界 " + aft
                        + " mm；前余量 " + fwdMargin + " mm，后余量 " + aftMargin + " mm）");
    }

    /**
     * 由总力矩/总重求重心臂：cgArm = totalMoment / totalWeight（3 位定标）。
     */
    public static BigDecimal cgArm(BigDecimal totalMoment, BigDecimal totalWeight) {
        Objects.requireNonNull(totalMoment, "totalMoment");
        Objects.requireNonNull(totalWeight, "totalWeight");
        if (totalWeight.signum() <= 0) {
            return null;
        }
        return totalMoment.divide(totalWeight, ARM_SCALE, RoundingMode.HALF_UP);
    }
}
