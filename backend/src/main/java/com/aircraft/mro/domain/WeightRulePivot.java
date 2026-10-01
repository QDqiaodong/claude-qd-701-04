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
 * 规则下各起落架支点的臂长（相对于基准点，统一 mm 存储）。
 *
 * <p>{@code pivot_code} 是支点业务键（NOSE / LEFT_MAIN / RIGHT_MAIN ...），
 * 称重读数必须按 code 对得上当前规则的支点，否则不予判定——臂长属于规则版本、不接受称重时自带。
 */
@Data
@Entity
@Table(name = "weight_rule_pivot")
public class WeightRulePivot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    /** 支点代码：NOSE 前起 / LEFT_MAIN 左主起 / RIGHT_MAIN 右主起 */
    @Column(name = "pivot_code", nullable = false, length = 24)
    private String pivotCode;

    /** 支点中文名，如 前起落架 */
    @Column(name = "pivot_name", nullable = false, length = 32)
    private String pivotName;

    /** 显示顺序 */
    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    /** 相对基准点臂长（mm，统一单位） */
    @Column(name = "arm_mm", nullable = false, precision = 12, scale = 3)
    private BigDecimal armMm;

    /** 该支点登记时的原始臂长数值（英制规则保留英寸原值） */
    @Column(name = "arm_original_value", precision = 12, scale = 3)
    private BigDecimal armOriginalValue;

    /** 原始单位：MM / IN */
    @Column(name = "arm_original_unit", length = 4)
    private String armOriginalUnit;
}
