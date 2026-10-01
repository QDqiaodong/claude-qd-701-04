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
 * 称重单每个支点的读数行：毛重、皮重、净重与臂长，原始单位与统一单位双份保存。
 *
 * <p>净重 = 毛重 − 皮重（同一原单位下先减，再换算，避免先换算再相减的舍入差）。
 * 力矩统一用 kg·mm；原单位展示列（lb/in）只是同一计算事实的视图换算。
 */
@Data
@Entity
@Table(name = "wb_reading")
public class WbReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "weighing_id", nullable = false)
    private Long weighingId;

    /** 对应规则支点 code，必须属于被引用的规则版本 */
    @Column(name = "pivot_code", nullable = false, length = 24)
    private String pivotCode;

    @Column(name = "pivot_name", nullable = false, length = 32)
    private String pivotName;

    /** 规则中该支点臂长（mm，统一） */
    @Column(name = "arm_mm", nullable = false, precision = 12, scale = 3)
    private BigDecimal armMm;

    /** 毛重读数（原单位） */
    @Column(name = "gross_original", precision = 12, scale = 3)
    private BigDecimal grossOriginal;

    /** 皮重（原单位）：支点分别录入时取本行，否则取称重单统一皮重 */
    @Column(name = "tare_original", precision = 12, scale = 3)
    private BigDecimal tareOriginal;

    /** 毛重（kg） */
    @Column(name = "gross_kg", precision = 12, scale = 3)
    private BigDecimal grossKg;

    /** 皮重（kg） */
    @Column(name = "tare_kg", precision = 12, scale = 3)
    private BigDecimal tareKg;

    /** 净重（kg） */
    @Column(name = "net_kg", precision = 12, scale = 3)
    private BigDecimal netKg;

    /** 净重（原单位，毛−皮直接得到） */
    @Column(name = "net_original", precision = 12, scale = 3)
    private BigDecimal netOriginal;

    /** 该支点力矩 kg·mm = netKg × armMm */
    @Column(name = "moment", precision = 18, scale = 3)
    private BigDecimal moment;

    /** 原单位：KG / LB */
    @Column(name = "weight_unit", nullable = false, length = 4)
    private String weightUnit;
}
