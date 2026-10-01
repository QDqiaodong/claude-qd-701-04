package com.aircraft.mro.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 一次实机称重记录。
 *
 * <p>读数/皮重/油液/临时项均保留原始数值与原单位（{@code unit_system}），
 * 统一单位（kg/mm）的换算结果与最终计算事实整体冻结在本表 + 明细行里，
 * 复核成基线后任何重算都只复述冻结结果，不得用新规则覆盖。
 *
 * <p>状态机：DRAFT（登记/可修改）→ CONFIRMED（两人复核确认，成为当前称重基线）。
 * 同一架飞机至多一条 CONFIRMED 且 current_flag=1；重新称重确认后旧基线置 0、结果仍按当时展示。
 */
@Data
@Entity
@Table(name = "wb_weighing")
public class WbWeighing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 称重单号 WB-2026-0001 */
    @Column(name = "weighing_no", nullable = false, length = 32)
    private String weighingNo;

    @Column(name = "aircraft_id", nullable = false)
    private Long aircraftId;

    /** 登记时的机号快照 */
    @Column(name = "tail_no", nullable = false, length = 16)
    private String tailNo;

    /** 登记时的机型快照 */
    @Column(name = "model", nullable = false, length = 16)
    private String model;

    @Column(name = "weighing_date", nullable = false)
    private LocalDate weighingDate;

    /** 秤具编号（整套称重磅单的设备标识） */
    @Column(name = "scale_no", nullable = false, length = 32)
    private String scaleNo;

    /** 本次登记采用的单位制：METRIC(kg/mm) / IMPERIAL(lb/in)，读数原值按此保存 */
    @Column(name = "unit_system", nullable = false, length = 8)
    private String unitSystem;

    /** 引用的规则版本 id（按称重日期解析，定稿后冻结，规则日后改版不影响本单） */
    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    /** 规则版本编号冗余，便于旧版本追溯展示 */
    @Column(name = "rule_no", nullable = false, length = 32)
    private String ruleNo;

    /** 皮重是否各支点分别录入：1 分别 / 0 使用统一皮重 */
    @Column(name = "tare_per_pivot", nullable = false)
    private Boolean tarePerPivot;

    /** 统一皮重（原单位，按支点相同时使用，可空） */
    @Column(name = "common_tare_original", precision = 12, scale = 3)
    private BigDecimal commonTareOriginal;

    /** 当时燃油条件描述，如 排空至不可用燃油 */
    @Column(name = "fuel_condition", length = 255)
    private String fuelCondition;

    /** 称重时燃油量（原单位：kg 或 lb；0 表示已排空） */
    @Column(name = "fuel_qty_original", precision = 12, scale = 3)
    private BigDecimal fuelQtyOriginal;

    /** 当时滑油条件描述，如 滑油加满至刻度 */
    @Column(name = "oil_condition", length = 255)
    private String oilCondition;

    /** 称重时滑油量（原单位） */
    @Column(name = "oil_qty_original", precision = 12, scale = 3)
    private BigDecimal oilQtyOriginal;

    /** 备注 */
    @Column(name = "remark", length = 255)
    private String remark;

    // ---------------- 冻结的计算事实（统一单位 kg/mm，HALF_UP 定标） ----------------
    /** 各支点净重合计（kg） */
    @Column(name = "result_total_weight", precision = 12, scale = 3)
    private BigDecimal resultTotalWeight;

    /** 总力矩 kg·mm */
    @Column(name = "result_total_moment", precision = 18, scale = 3)
    private BigDecimal resultTotalMoment;

    /** 重心臂 mm */
    @Column(name = "result_cg_arm", precision = 12, scale = 3)
    private BigDecimal resultCgArm;

    /** 判定：IN_ENVELOPE 包线内 / OUT_OF_ENVELOPE 包线外 / INDETERMINATE 无法判定（缺读数/超重等） */
    @Column(name = "envelope_status", length = 20)
    private String envelopeStatus;

    /** 判定中文说明，含插值得到的前后边界与越界方向/差值 */
    @Column(name = "envelope_message", length = 512)
    private String envelopeMessage;

    /** 判定当时前边界臂长 mm（插值结果） */
    @Column(name = "result_forward_limit", precision = 12, scale = 3)
    private BigDecimal resultForwardLimit;

    /** 判定当时后边界臂长 mm（插值结果） */
    @Column(name = "result_aft_limit", precision = 12, scale = 3)
    private BigDecimal resultAftLimit;

    /** 相对前边界差值 mm（cgArm - forwardLimit，负=越过前边界） */
    @Column(name = "result_forward_margin", precision = 12, scale = 3)
    private BigDecimal resultForwardMargin;

    /** 相对后边界差值 mm（aftLimit - cgArm，负=越过后边界） */
    @Column(name = "result_aft_margin", precision = 12, scale = 3)
    private BigDecimal resultAftMargin;

    /** DRAFT 待复核 / CONFIRMED 已复核成基线 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 称重登记人 */
    @Column(name = "weighed_by", length = 32)
    private String weighedBy;

    /** 第一复核人 */
    @Column(name = "reviewed_by", length = 32)
    private String reviewedBy;

    /** 第二复核人（与登记人、第一复核人都不同才允许确认） */
    @Column(name = "confirmed_by", length = 32)
    private String confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    /** 1=该机当前称重基线（至多一条），旧基线为 0 */
    @Column(name = "current_flag", nullable = false)
    private Boolean currentFlag;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
