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
 * 飞机称重基线派生的构型版本。
 *
 * <p>版本是一条只允许在【当前版本】上继续追加的链：BASELINE（复核确认的称重）为链根，
 * 之后每次设备安装/拆除派生一个 ADJUSTMENT 版本，{@code parent_id} 指向上一版本。
 * 新版本一旦生成，上一版本立即冻结（current_flag=0），包线结果原样留存、永不重算。
 * 重新称重确认会开启新链（新 BASELINE，parent 为空），旧链整体冻结展示。
 */
@Data
@Entity
@Table(name = "wb_config_version")
public class WbConfigVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aircraft_id", nullable = false)
    private Long aircraftId;

    /** 同机版本号，按创建先后连续递增（跨重新称重不重置，保证全局可排序） */
    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    /** 上一版本 id；链根（称重基线）为空 */
    @Column(name = "parent_id")
    private Long parentId;

    /** BASELINE 称重基线 / ADJUSTMENT 构型调整派生 */
    @Column(name = "source", nullable = false, length = 12)
    private String source;

    /** 基线版本对应的称重单 id（仅 BASELINE） */
    @Column(name = "weighing_id")
    private Long weighingId;

    @Column(name = "tail_no", nullable = false, length = 16)
    private String tailNo;

    @Column(name = "model", nullable = false, length = 16)
    private String model;

    /** 派生日期（判定取当日生效规则的依据） */
    @Column(name = "change_date", nullable = false)
    private LocalDate changeDate;

    /** 本版本包线判定所引用的规则版本（历史冻结，不随后续改版变动） */
    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "rule_no", nullable = false, length = 32)
    private String ruleNo;

    /** 变更摘要（自动汇总调整项） */
    @Column(name = "change_summary", length = 512)
    private String changeSummary;

    // ---------------- 冻结的计算事实（kg/mm） ----------------
    @Column(name = "total_weight", nullable = false, precision = 12, scale = 3)
    private BigDecimal totalWeight;

    @Column(name = "total_moment", nullable = false, precision = 18, scale = 3)
    private BigDecimal totalMoment;

    @Column(name = "cg_arm", nullable = false, precision = 12, scale = 3)
    private BigDecimal cgArm;

    @Column(name = "envelope_status", nullable = false, length = 20)
    private String envelopeStatus;

    @Column(name = "envelope_message", length = 512)
    private String envelopeMessage;

    @Column(name = "forward_limit", precision = 12, scale = 3)
    private BigDecimal forwardLimit;

    @Column(name = "aft_limit", precision = 12, scale = 3)
    private BigDecimal aftLimit;

    /** 距前边界 mm（负=越界） */
    @Column(name = "forward_margin", precision = 12, scale = 3)
    private BigDecimal forwardMargin;

    /** 距后边界 mm（负=越界） */
    @Column(name = "aft_margin", precision = 12, scale = 3)
    private BigDecimal aftMargin;

    /** 1=当前版本（同机至多一条），其余为历史冻结版本 */
    @Column(name = "current_flag", nullable = false)
    private Boolean currentFlag;

    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
