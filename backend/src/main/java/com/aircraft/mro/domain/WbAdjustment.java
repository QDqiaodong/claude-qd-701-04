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
 * 构型调整项：安装或拆除一项设备。
 *
 * <p>仅挂在 ADJUSTMENT 版本上；重量、臂长保留原始数值与原单位，统一值（kg/mm）冻结。
 * 方向：INSTALL 安装（重量/力矩加）、REMOVE 拆除（减）。
 * 关联工卡为可追溯凭证（可空）；调整项只能属于本架飞机的版本，跨机并入在服务端拒绝。
 */
@Data
@Entity
@Table(name = "wb_adjustment")
public class WbAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "version_id", nullable = false)
    private Long versionId;

    /** 冗余飞机 id：跨机并入调整项时在数据库层即可识别拦截 */
    @Column(name = "aircraft_id", nullable = false)
    private Long aircraftId;

    /** INSTALL 安装 / REMOVE 拆除 */
    @Column(name = "direction", nullable = false, length = 8)
    private String direction;

    /** 设备名称 */
    @Column(name = "name", nullable = false, length = 64)
    private String name;

    /** 单件重量（原单位，正数） */
    @Column(name = "weight_original", nullable = false, precision = 12, scale = 3)
    private BigDecimal weightOriginal;

    /** 安装/拆除位置臂长（原单位） */
    @Column(name = "arm_original", nullable = false, precision = 12, scale = 3)
    private BigDecimal armOriginal;

    /** 数量（正整数） */
    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    /** 单件重量 kg */
    @Column(name = "weight_kg", nullable = false, precision = 12, scale = 3)
    private BigDecimal weightKg;

    /** 臂长 mm */
    @Column(name = "arm_mm", nullable = false, precision = 12, scale = 3)
    private BigDecimal armMm;

    /** 有符号力矩 kg·mm（已含数量，INSTALL 正 / REMOVE 负） */
    @Column(name = "signed_moment", nullable = false, precision = 18, scale = 3)
    private BigDecimal signedMoment;

    /** 有符号总重增量 kg（已含数量） */
    @Column(name = "signed_weight", nullable = false, precision = 12, scale = 3)
    private BigDecimal signedWeight;

    /** 原因 */
    @Column(name = "reason", length = 255)
    private String reason;

    /** 关联工卡号 */
    @Column(name = "work_card_no", length = 32)
    private String workCardNo;

    @Column(name = "weight_unit", nullable = false, length = 4)
    private String weightUnit;

    @Column(name = "arm_unit", nullable = false, length = 4)
    private String armUnit;
}
