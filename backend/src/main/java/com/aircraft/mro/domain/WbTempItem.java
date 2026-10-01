package com.aircraft.mro.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 称重时需要扣除或补入的临时项（修正项），如称重磅上的支撑工装（扣除）、
 * 称重时缺失、空重本应包含的设备（补入）。
 *
 * <p>每行自带臂长（该临时项相对基准点的力臂，原单位）；符号由 kind 决定：
 * DEDUCT 从称得重量中扣除，ADD 补入。修正同时计入总重与总力矩（统一 kg/mm 冻结）。
 */
@Data
@Entity
@Table(name = "wb_temp_item")
public class WbTempItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "weighing_id", nullable = false)
    private Long weighingId;

    /** DEDUCT 扣除 / ADD 补入 */
    @Column(name = "kind", nullable = false, length = 8)
    private String kind;

    /** 项目名称，如 主翼支撑工装 / 缺失的厨房电烤箱 */
    @Column(name = "name", nullable = false, length = 64)
    private String name;

    /** 单项重量（原单位，恒为正数；方向看 kind） */
    @Column(name = "weight_original", nullable = false, precision = 12, scale = 3)
    private BigDecimal weightOriginal;

    /** 该项臂长（原单位：mm 或 in） */
    @Column(name = "arm_original", nullable = false, precision = 12, scale = 3)
    private BigDecimal armOriginal;

    /** 重量（kg，冻结换算） */
    @Column(name = "weight_kg", precision = 12, scale = 3)
    private BigDecimal weightKg;

    /** 臂长（mm，冻结换算） */
    @Column(name = "arm_mm", precision = 12, scale = 3)
    private BigDecimal armMm;

    /** 有符号力矩 kg·mm：ADD 为正、DEDUCT 为负 */
    @Column(name = "signed_moment", precision = 18, scale = 3)
    private BigDecimal signedMoment;

    /** 有符号重量 kg：ADD 为正、DEDUCT 为负（单行，不含数量概念） */
    @Column(name = "signed_weight", precision = 12, scale = 3)
    private BigDecimal signedWeight;

    /** 原因说明 */
    @Column(name = "reason", length = 255)
    private String reason;

    /** 原始重量单位：KG / LB */
    @Column(name = "weight_unit", nullable = false, length = 4)
    private String weightUnit;

    /** 原始臂长单位：MM / IN */
    @Column(name = "arm_unit", nullable = false, length = 4)
    private String armUnit;
}
