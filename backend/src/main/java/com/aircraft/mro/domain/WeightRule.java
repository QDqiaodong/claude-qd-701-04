package com.aircraft.mro.domain;

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
 * 称重规则（机型级、可版本化）。
 *
 * <p>同一机型可有多行：至多一行 {@code status=EFFECTIVE}（且不晚于业务日的最新生效版本），
 * 历史版本为 {@code SUPERSEDED}，永久保留、不得改写——已完成称重通过 rule_id 固化引用旧版本。
 *
 * <p>所有几何/重量参数以统一单位存储：重量 kg、臂长 mm（{@code unit_system=METRIC}）；
 * 原单位为英制的规则在登记时一次性换算入库，后续计算不再反算（避免往返累积误差）。
 */
@Data
@Entity
@Table(name = "weight_rule")
public class WeightRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 规则编号：机型 + 版本号，如 WR-B737-V1 */
    @Column(name = "rule_no", nullable = false, length = 32)
    private String ruleNo;

    /** 机型：B737 / A320 / A330 / ARJ21 */
    @Column(name = "model", nullable = false, length = 16)
    private String model;

    /** 版本号，同一机型内从 1 递增 */
    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    /** DRAFT 草稿（尚未生效） / EFFECTIVE 生效中 / SUPERSEDED 已被取代（冻结） */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 生效日期：新称重按「机型相同、EFFECTIVE、生效日期不晚于称重日期」中最新的一条引用 */
    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    /** 登记时使用的单位制：METRIC(kg/mm) / IMPERIAL(lb/in)，仅作来源记录，入库数值一律为 kg/mm */
    @Column(name = "unit_system", nullable = false, length = 8)
    private String unitSystem;

    /** 基准点名称/描述，如 机身站位 FS0（机头前 130in） */
    @Column(name = "datum_name", length = 64)
    private String datumName;

    /** 基准点补充说明（站位约定、水平姿态要求等） */
    @Column(name = "datum_note", length = 255)
    private String datumNote;

    /** 许用最小总重（kg） */
    @Column(name = "min_weight", nullable = false, precision = 12, scale = 3)
    private java.math.BigDecimal minWeight;

    /** 许用最大总重（kg） */
    @Column(name = "max_weight", nullable = false, precision = 12, scale = 3)
    private java.math.BigDecimal maxWeight;

    /** 许用最小总重（登记原值，kg 或 lb） */
    @Column(name = "min_weight_original", precision = 12, scale = 3)
    private java.math.BigDecimal minWeightOriginal;

    /** 许用最大总重（登记原值，kg 或 lb） */
    @Column(name = "max_weight_original", precision = 12, scale = 3)
    private java.math.BigDecimal maxWeightOriginal;

    /** 前重心边界折线节点数（与 envelope 行一致，冗余便于校验） */
    @Column(name = "forward_node_count", nullable = false)
    private Integer forwardNodeCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
