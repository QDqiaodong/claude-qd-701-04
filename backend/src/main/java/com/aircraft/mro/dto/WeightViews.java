package com.aircraft.mro.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 模块八出参视图（record）。
 *
 * <p>每条计量事实都同时给两套数：
 * {@code *Kg / *Mm / *Moment} 为统一单位（kg、mm、kg·mm）的冻结计算结果；
 * {@code *Original + weightUnit/armUnit} 为登记时的原始读数与原单位。
 * “原始单位 / 统一单位”两种页面展示读取的是同一份事实，不允许出现两套计算结果。
 */
public final class WeightViews {

    private WeightViews() {
    }

    public record PivotView(
            Long id,
            String pivotCode,
            String pivotName,
            Integer displayOrder,
            BigDecimal armMm,
            BigDecimal armOriginalValue,
            String armOriginalUnit) {
    }

    public record NodeView(
            Long id,
            String boundary,
            String boundaryName,
            Integer seqNo,
            BigDecimal weightKg,
            BigDecimal armMm,
            BigDecimal weightOriginalValue,
            BigDecimal armOriginalValue,
            String originalUnitSystem) {
    }

    public record RuleListItem(
            Long id,
            String ruleNo,
            String model,
            Integer versionNo,
            String status,
            String statusName,
            LocalDate effectiveDate,
            String unitSystem,
            String unitSystemName,
            BigDecimal minWeightKg,
            BigDecimal maxWeightKg,
            Integer forwardNodeCount,
            /** 是否已被任何称重/构型版本引用（引用后即不可修改/删除） */
            boolean referenced,
            LocalDateTime createdAt) {
    }

    public record RuleView(
            Long id,
            String ruleNo,
            String model,
            Integer versionNo,
            String status,
            String statusName,
            LocalDate effectiveDate,
            String unitSystem,
            String unitSystemName,
            String datumName,
            String datumNote,
            BigDecimal minWeightKg,
            BigDecimal maxWeightKg,
            BigDecimal minWeightOriginal,
            BigDecimal maxWeightOriginal,
            Integer forwardNodeCount,
            boolean referenced,
            List<PivotView> pivots,
            List<NodeView> forwardNodes,
            List<NodeView> aftNodes,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /** 称重日生效规则解析结果（用于登记页提示将引用哪个版本） */
    public record RuleResolvedView(
            String model,
            LocalDate weighingDate,
            Long ruleId,
            String ruleNo,
            Integer versionNo,
            String statusName,
            String message) {
    }

    public record ReadingView(
            Long id,
            String pivotCode,
            String pivotName,
            BigDecimal armMm,
            String weightUnit,
            BigDecimal grossOriginal,
            BigDecimal tareOriginal,
            BigDecimal netOriginal,
            BigDecimal grossKg,
            BigDecimal tareKg,
            BigDecimal netKg,
            BigDecimal moment,
            /** 毛重读数缺失：任何支点缺失都不得给出包线内结论 */
            boolean grossMissing) {
    }

    public record TempItemView(
            Long id,
            String kind,
            String kindName,
            String name,
            String weightUnit,
            String armUnit,
            BigDecimal weightOriginal,
            BigDecimal armOriginal,
            BigDecimal weightKg,
            BigDecimal armMm,
            BigDecimal signedWeight,
            BigDecimal signedMoment,
            String reason) {
    }

    /** 计算事实块（试算与已保存称重/构型版本共用同一结构） */
    public record EnvelopeView(
            String status,
            String statusName,
            String message,
            BigDecimal totalWeight,
            BigDecimal totalMoment,
            BigDecimal cgArm,
            BigDecimal forwardLimit,
            BigDecimal aftLimit,
            BigDecimal forwardMargin,
            BigDecimal aftMargin) {
    }

    public record WeighingListItem(
            Long id,
            String weighingNo,
            Long aircraftId,
            String tailNo,
            String model,
            LocalDate weighingDate,
            String unitSystem,
            String unitSystemName,
            String ruleNo,
            String ruleStatusName,
            BigDecimal totalWeight,
            BigDecimal cgArm,
            String envelopeStatus,
            String envelopeStatusName,
            String envelopeMessage,
            String status,
            String statusName,
            boolean currentFlag) {
    }

    public record WeighingView(
            Long id,
            String weighingNo,
            Long aircraftId,
            String tailNo,
            String model,
            LocalDate weighingDate,
            String scaleNo,
            String unitSystem,
            String unitSystemName,
            Long ruleId,
            String ruleNo,
            String ruleStatusName,
            boolean tarePerPivot,
            BigDecimal commonTareOriginal,
            String fuelCondition,
            BigDecimal fuelQtyOriginal,
            String oilCondition,
            BigDecimal oilQtyOriginal,
            String remark,
            List<ReadingView> readings,
            List<TempItemView> tempItems,
            EnvelopeView result,
            /** 支点读数缺失数（为 0 才允许给出确定性结论） */
            int missingCount,
            String status,
            String statusName,
            String weighedBy,
            String reviewedBy,
            String confirmedBy,
            LocalDateTime confirmedAt,
            boolean currentFlag,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    public record AdjustmentView(
            Long id,
            String direction,
            String directionName,
            String name,
            Integer quantity,
            String weightUnit,
            String armUnit,
            BigDecimal weightOriginal,
            BigDecimal armOriginal,
            BigDecimal weightKg,
            BigDecimal armMm,
            BigDecimal signedWeight,
            BigDecimal signedMoment,
            String reason,
            String workCardNo) {
    }

    public record ConfigVersionView(
            Long id,
            Long aircraftId,
            String tailNo,
            String model,
            Integer versionNo,
            Long parentId,
            String source,
            String sourceName,
            Long weighingId,
            String weighingNo,
            LocalDate changeDate,
            Long ruleId,
            String ruleNo,
            String ruleStatusName,
            String changeSummary,
            EnvelopeView result,
            boolean currentFlag,
            String operator,
            LocalDateTime createdAt,
            List<AdjustmentView> adjustments) {
    }

    /** 飞机在本模块的台账状态：无基线也照常返回，明确标“未建立基线”，不猜测默认重心 */
    public record AircraftWeightStatusView(
            Long aircraftId,
            String tailNo,
            String model,
            String airline,
            boolean baselineEstablished,
            Long currentVersionId,
            Integer currentVersionNo,
            LocalDate currentVersionDate,
            BigDecimal currentTotalWeight,
            BigDecimal currentCgArm,
            String envelopeStatus,
            String envelopeStatusName,
            String envelopeMessage,
            Long baselineWeighingId,
            String baselineWeighingNo,
            LocalDate lastWeighingDate,
            String currentRuleNo,
            int versionCount,
            /** 无基线但存在待复核/超界称重记录时给出提示，不影响“未建立基线”结论 */
            String note) {
    }

    /** 构型调整链：完整版本列表 + 当前版本指针 */
    public record ConfigChainView(
            Long aircraftId,
            String tailNo,
            String model,
            boolean baselineEstablished,
            Long currentVersionId,
            Integer currentVersionNo,
            List<ConfigVersionView> versions) {
    }

    public record VersionBrief(
            Long versionId,
            Integer versionNo,
            String sourceName,
            LocalDate changeDate,
            String ruleNo,
            String ruleStatusName,
            BigDecimal totalWeight,
            BigDecimal totalMoment,
            BigDecimal cgArm,
            String envelopeStatus,
            String envelopeStatusName,
            String envelopeMessage,
            int adjustmentCount) {
    }

    /** 版本对比：差值按统一单位 kg/mm/kg·mm；两个版本各自保留当时规则与判定，不重算 */
    public record VersionCompareView(
            Long aircraftId,
            String tailNo,
            String model,
            VersionBrief from,
            VersionBrief to,
            BigDecimal deltaWeight,
            BigDecimal deltaMoment,
            BigDecimal deltaCgArm,
            boolean envelopeStatusChanged,
            List<AdjustmentView> toAdjustments) {
    }
}
