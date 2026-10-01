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
 * 重心包线折线节点。
 *
 * <p>{@code boundary}=FORWARD 为前边界（重心不得更靠前，即臂长不得更小），
 * {@code boundary}=AFT 为后边界（重心不得更靠后，即臂长不得更大）。
 * 每条折线按 weight_kg 升序排列；判定时总重落在两节点之间按线性插值取边界臂长。
 * 全部以统一单位（kg / mm）存储，节点外不做外推。
 */
@Data
@Entity
@Table(name = "weight_rule_envelope")
public class WeightRuleEnvelope {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    /** FORWARD 前边界 / AFT 后边界 */
    @Column(name = "boundary", nullable = false, length = 8)
    private String boundary;

    /** 节点序号（每条折线内从 1 起） */
    @Column(name = "seq_no", nullable = false)
    private Integer seqNo;

    /** 节点重量（kg） */
    @Column(name = "weight_kg", nullable = false, precision = 12, scale = 3)
    private BigDecimal weightKg;

    /** 该重量下的边界臂长（mm） */
    @Column(name = "arm_mm", nullable = false, precision = 12, scale = 3)
    private BigDecimal armMm;

    /** 登记时的原始重量数值（英制规则保留 lb 原值） */
    @Column(name = "weight_original_value", precision = 12, scale = 3)
    private BigDecimal weightOriginalValue;

    /** 该节点登记时的原始边界臂长数值（in 原值） */
    @Column(name = "arm_original_value", precision = 12, scale = 3)
    private BigDecimal armOriginalValue;

    /** 原始单位制：METRIC / IMPERIAL */
    @Column(name = "original_unit_system", length = 8)
    private String originalUnitSystem;
}
