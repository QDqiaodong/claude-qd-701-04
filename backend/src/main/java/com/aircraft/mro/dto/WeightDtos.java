package com.aircraft.mro.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.aircraft.mro.service.WeightBalanceEngine;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 模块八（飞机称重与重心）入参。
 * 规则/称重/调整项的数值均按该单据选定的单位制提交：METRIC=kg/mm，IMPERIAL=lb/in，
 * 服务端一次性换算成统一单位冻结，前端不参与任何重心计算。
 */
public final class WeightDtos {

    private WeightDtos() {
    }

    // ==================== 规则版本 ====================

    @Data
    public static class PivotReq {
        /** 支点代码：NOSE / LEFT_MAIN / RIGHT_MAIN */
        @NotBlank(message = "支点代码不能为空")
        private String pivotCode;

        /** 支点名称（可空，默认取词典名） */
        private String pivotName;

        /** 显示顺序，可空 */
        private Integer displayOrder;

        /** 臂长（随规则单位制：mm 或 in） */
        @NotNull(message = "支点臂长不能为空")
        private BigDecimal arm;
    }

    @Data
    public static class NodeReq {
        /** 节点重量（kg 或 lb） */
        @NotNull(message = "包线节点重量不能为空")
        private BigDecimal weight;

        /** 该重量下的边界臂长（mm 或 in） */
        @NotNull(message = "包线节点臂长不能为空")
        private BigDecimal arm;
    }

    @Data
    public static class RuleSaveReq {
        @NotBlank(message = "机型不能为空")
        private String model;

        /** 生效日期（草稿也先记录；发布时可再改） */
        @NotNull(message = "生效日期不能为空")
        private LocalDate effectiveDate;

        /** METRIC 公制 kg/mm / IMPERIAL 英制 lb/in */
        @NotBlank(message = "规则单位制不能为空")
        private String unitSystem;

        private String datumName;
        private String datumNote;

        /** 许用最小总重（kg 或 lb） */
        @NotNull(message = "许用最小总重不能为空")
        private BigDecimal minWeight;

        /** 许用最大总重（kg 或 lb） */
        @NotNull(message = "许用最大总重不能为空")
        private BigDecimal maxWeight;

        /** 各起落架支点（至少 2 个，且 code 不重复） */
        @NotNull(message = "至少需要两个起落架支点")
        private List<PivotReq> pivots;

        /** 前重心边界折线节点（升序，至少 2 个） */
        @NotNull(message = "前重心边界节点不能为空")
        private List<NodeReq> forwardNodes;

        /** 后重心边界折线节点（升序，至少 2 个） */
        @NotNull(message = "后重心边界节点不能为空")
        private List<NodeReq> aftNodes;
    }

    @Data
    public static class RulePublishReq {
        /** 发布时重新指定生效日期（可空，沿用草稿日期） */
        private LocalDate effectiveDate;
    }

    // ==================== 称重 ====================

    @Data
    public static class ReadingReq {
        @NotBlank(message = "支点代码不能为空")
        private String pivotCode;

        /** 毛重读数（原单位）；null=读数缺失，缺失不得判定包线 */
        private BigDecimal gross;

        /** 皮重（原单位）；称重单按支点分别去皮时使用，可空 */
        private BigDecimal tare;
    }

    @Data
    public static class TempItemReq {
        /** DEDUCT 扣除 / ADD 补入 */
        @NotBlank(message = "临时项方向不能为空")
        private String kind;

        @NotBlank(message = "临时项名称不能为空")
        private String name;

        /** 重量（原单位，正数） */
        @NotNull(message = "临时项重量不能为空")
        private BigDecimal weight;

        /** 臂长（原单位） */
        @NotNull(message = "临时项臂长不能为空")
        private BigDecimal arm;

        /** 重量单位，可空（取称重单单位制）：KG / LB */
        private String weightUnit;

        /** 臂长单位，可空（取称重单单位制）：MM / IN */
        private String armUnit;

        private String reason;
    }

    @Data
    public static class WeighingSaveReq {
        @NotNull(message = "飞机不能为空")
        private Long aircraftId;

        @NotNull(message = "称重日期不能为空")
        private LocalDate weighingDate;

        @NotBlank(message = "秤具编号不能为空")
        private String scaleNo;

        /** METRIC / IMPERIAL，原始读数的单位制 */
        @NotBlank(message = "称重单位制不能为空")
        private String unitSystem;

        /** 显式指定规则版本；null=按机型与称重日期自动解析生效规则 */
        private Long ruleId;

        /** true=逐支点去皮（用 readings[].tare）；false=使用统一皮重 */
        private Boolean tarePerPivot;

        /** 统一皮重（原单位），tarePerPivot=false 时必填 */
        private BigDecimal commonTare;

        private String fuelCondition;
        /** 称重时燃油量（原单位），仅记录条件，不自动参与空重修正（修正走临时项） */
        private BigDecimal fuelQty;
        private String oilCondition;
        private BigDecimal oilQty;
        private String remark;

        @NotBlank(message = "称重登记人不能为空")
        private String weighedBy;

        private List<ReadingReq> readings;
        private List<TempItemReq> tempItems;
    }

    @Data
    public static class WeighingConfirmReq {
        /** 第一复核人（必须与登记人不同） */
        @NotBlank(message = "第一复核人不能为空")
        private String reviewedBy;

        /** 第二复核人/确认人（必须与登记人、第一复核人都不同） */
        @NotBlank(message = "第二复核人不能为空")
        private String confirmedBy;
    }

    /** 试算请求：不入库，仅返回计算明细（前端表单“试算”也走服务端） */
    public static class PreviewReq extends WeighingSaveReq {
    }

    // ==================== 构型调整派生 ====================

    @Data
    public static class AdjustmentReq {
        /** INSTALL 安装 / REMOVE 拆除 */
        @NotBlank(message = "调整方向不能为空")
        private String direction;

        @NotBlank(message = "设备名称不能为空")
        private String name;

        @NotNull(message = "单件重量不能为空")
        private BigDecimal weight;

        @NotNull(message = "臂长不能为空")
        private BigDecimal arm;

        @NotNull(message = "数量不能为空")
        private Integer quantity;

        /** 重量单位，可空（取本次派生单位制）：KG / LB */
        private String weightUnit;

        /** 臂长单位，可空（取本次派生单位制）：MM / IN */
        private String armUnit;

        private String reason;
        private String workCardNo;
    }

    @Data
    public static class DeriveReq {
        @NotNull(message = "飞机不能为空")
        private Long aircraftId;

        @NotNull(message = "调整日期不能为空")
        private LocalDate changeDate;

        /** 本次调整登记所用单位制：METRIC / IMPERIAL */
        @NotBlank(message = "单位制不能为空")
        private String unitSystem;

        @NotBlank(message = "操作人不能为空")
        private String operator;

        /** 仅允许从该机型当前版本派生；可空=自动取当前版本。传错（非当前/非本机）直接拒绝 */
        private Long parentVersionId;

        @NotNull(message = "至少需要一条构型调整项")
        private List<AdjustmentReq> items;
    }

    /** 版本对比请求：对比同机两个构型版本（默认当前版本与基线） */
    @Data
    public static class CompareReq {
        private Long fromVersionId;
        private Long toVersionId;
    }

    // 让静态常量在 DTO 层可见，避免散落字面量
    public static final String METRIC = WeightBalanceEngine.METRIC;
    public static final String IMPERIAL = WeightBalanceEngine.IMPERIAL;
}
