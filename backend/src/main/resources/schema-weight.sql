-- ============================================================================
-- 模块八：飞机称重与重心（Weight & Balance）
--   weight_rule            机型称重规则版本（可版本化：DRAFT/EFFECTIVE/SUPERSEDED，引用即冻结）
--   weight_rule_pivot      规则下各起落架支点臂长（mm，原值/原单位双份）
--   weight_rule_envelope   随重量变化的前/后重心边界折线节点（kg/mm，线性插值判定）
--   wb_weighing            实机称重单（原始读数 + 冻结的统一单位计算事实 + 两人复核）
--   wb_reading             称重单支点读数（毛/皮/净、臂长、力矩，原单位与 kg/mm 双份）
--   wb_temp_item           称重时扣除/补入的临时项
--   wb_config_version      由当前称重基线派生的构型版本（只追加链，历史冻结不重算）
--   wb_adjustment          构型调整项（安装/拆除，重量/臂长/数量/原因/关联工卡）
--
-- 所有计量列统一单位：重量 kg（3 位小数）、臂长 mm（3 位）、力矩 kg·mm（3 位）；
-- *_original 列保留登记时的原始读数（kg 或 lb、mm 或 in）。
-- 示例数据由后端 WbBootstrap 在启动时幂等写入（保证换算与计算口径与引擎一致），
-- 本脚本只建表，不写 INSERT。
-- ============================================================================

USE aircraft_mro;

DROP TABLE IF EXISTS wb_adjustment;
DROP TABLE IF EXISTS wb_config_version;
DROP TABLE IF EXISTS wb_temp_item;
DROP TABLE IF EXISTS wb_reading;
DROP TABLE IF EXISTS wb_weighing;
DROP TABLE IF EXISTS weight_rule_envelope;
DROP TABLE IF EXISTS weight_rule_pivot;
DROP TABLE IF EXISTS weight_rule;

-- ----------------------------------------------------------------------------
-- 称重规则（机型级、可版本化）
-- ----------------------------------------------------------------------------
CREATE TABLE weight_rule (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    rule_no               VARCHAR(32)  NOT NULL COMMENT '规则编号 WR-机型-V版本',
    model                 VARCHAR(16)  NOT NULL COMMENT '机型 B737/A320/A330/ARJ21',
    version_no            INT          NOT NULL COMMENT '同机型版本号，从 1 起',
    status                VARCHAR(16)  NOT NULL COMMENT 'DRAFT 草稿 / EFFECTIVE 生效中 / SUPERSEDED 已被取代冻结',
    effective_date        DATE         NOT NULL COMMENT '生效日期（按称重日期解析版本）',
    unit_system           VARCHAR(8)   NOT NULL COMMENT '登记单位制 METRIC / IMPERIAL（入库数值一律 kg/mm）',
    datum_name            VARCHAR(64)  NULL COMMENT '基准点名称',
    datum_note            VARCHAR(255) NULL COMMENT '基准点说明',
    min_weight            DECIMAL(12,3) NOT NULL COMMENT '许用最小总重 kg',
    max_weight            DECIMAL(12,3) NOT NULL COMMENT '许用最大总重 kg',
    min_weight_original   DECIMAL(12,3) NULL COMMENT '许用最小总重（登记原值）',
    max_weight_original   DECIMAL(12,3) NULL COMMENT '许用最大总重（登记原值）',
    forward_node_count    INT          NOT NULL DEFAULT 0 COMMENT '前边界折线节点数',
    created_at            DATETIME     NOT NULL,
    updated_at            DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_weight_rule_no (rule_no),
    KEY idx_wr_model_ver (model, version_no),
    KEY idx_wr_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '称重规则版本';

CREATE TABLE weight_rule_pivot (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    rule_id              BIGINT       NOT NULL,
    pivot_code           VARCHAR(24)  NOT NULL COMMENT '支点代码 NOSE/LEFT_MAIN/RIGHT_MAIN',
    pivot_name           VARCHAR(32)  NOT NULL COMMENT '支点中文名',
    display_order        INT          NOT NULL DEFAULT 1,
    arm_mm               DECIMAL(12,3) NOT NULL COMMENT '相对基准点臂长 mm（统一单位）',
    arm_original_value   DECIMAL(12,3) NULL COMMENT '臂长登记原值',
    arm_original_unit    VARCHAR(4)   NULL COMMENT '臂长原单位 MM/IN',
    PRIMARY KEY (id),
    UNIQUE KEY uk_wrp_rule_code (rule_id, pivot_code),
    KEY idx_wrp_rule (rule_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '称重规则·起落架支点臂长';

CREATE TABLE weight_rule_envelope (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    rule_id               BIGINT       NOT NULL,
    boundary              VARCHAR(8)   NOT NULL COMMENT 'FORWARD 前边界 / AFT 后边界',
    seq_no                INT          NOT NULL COMMENT '折线节点序号（按重量升序）',
    weight_kg             DECIMAL(12,3) NOT NULL COMMENT '节点重量 kg',
    arm_mm                DECIMAL(12,3) NOT NULL COMMENT '节点边界臂长 mm',
    weight_original_value DECIMAL(12,3) NULL COMMENT '节点重量登记原值',
    arm_original_value    DECIMAL(12,3) NULL COMMENT '节点臂长登记原值',
    original_unit_system  VARCHAR(8)   NULL COMMENT '登记单位制 METRIC/IMPERIAL',
    PRIMARY KEY (id),
    UNIQUE KEY uk_wre_node (rule_id, boundary, seq_no),
    KEY idx_wre_rule (rule_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '称重规则·重心包线折线节点';

-- ----------------------------------------------------------------------------
-- 实机称重
-- ----------------------------------------------------------------------------
CREATE TABLE wb_weighing (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    weighing_no            VARCHAR(32)  NOT NULL COMMENT '称重单号 WB-年-序号',
    aircraft_id            BIGINT       NOT NULL,
    tail_no                VARCHAR(16)  NOT NULL COMMENT '机号快照',
    model                  VARCHAR(16)  NOT NULL COMMENT '机型快照',
    weighing_date          DATE         NOT NULL,
    scale_no               VARCHAR(32)  NOT NULL COMMENT '秤具编号',
    unit_system            VARCHAR(8)   NOT NULL COMMENT '原始读数单位制 METRIC/IMPERIAL',
    rule_id                BIGINT       NOT NULL COMMENT '引用的规则版本（定稿冻结，不改引）',
    rule_no                VARCHAR(32)  NOT NULL COMMENT '规则编号冗余',
    tare_per_pivot         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1=逐支点去皮 0=统一皮重',
    common_tare_original   DECIMAL(12,3) NULL COMMENT '统一皮重（原单位）',
    fuel_condition         VARCHAR(255) NULL COMMENT '当时燃油条件',
    fuel_qty_original      DECIMAL(12,3) NULL COMMENT '当时燃油量（原单位，仅记录）',
    oil_condition          VARCHAR(255) NULL COMMENT '当时滑油条件',
    oil_qty_original       DECIMAL(12,3) NULL COMMENT '当时滑油量（原单位，仅记录）',
    remark                 VARCHAR(255) NULL,
    result_total_weight    DECIMAL(12,3) NULL COMMENT '冻结：总重 kg（缺读数时 NULL）',
    result_total_moment    DECIMAL(18,3) NULL COMMENT '冻结：总力矩 kg·mm',
    result_cg_arm          DECIMAL(12,3) NULL COMMENT '冻结：重心臂 mm',
    envelope_status        VARCHAR(20)  NULL COMMENT 'IN_ENVELOPE/OUT_OF_ENVELOPE/INDETERMINATE',
    envelope_message       VARCHAR(512) NULL COMMENT '判定说明（含越界方向与差值）',
    result_forward_limit   DECIMAL(12,3) NULL COMMENT '判定时前边界（插值）mm',
    result_aft_limit       DECIMAL(12,3) NULL COMMENT '判定时后边界（插值）mm',
    result_forward_margin  DECIMAL(12,3) NULL COMMENT '距前边界余量 mm（负=越界）',
    result_aft_margin      DECIMAL(12,3) NULL COMMENT '距后边界余量 mm（负=越界）',
    status                 VARCHAR(16)  NOT NULL COMMENT 'DRAFT 待复核 / CONFIRMED 已确认基线',
    weighed_by             VARCHAR(32)  NULL COMMENT '称重登记人',
    reviewed_by            VARCHAR(32)  NULL COMMENT '第一复核人',
    confirmed_by           VARCHAR(32)  NULL COMMENT '第二复核人',
    confirmed_at           DATETIME     NULL,
    current_flag           TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1=该机当前称重基线',
    created_at             DATETIME     NOT NULL,
    updated_at             DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wb_no (weighing_no),
    KEY idx_wb_aircraft (aircraft_id),
    KEY idx_wb_rule (rule_id),
    KEY idx_wb_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '实机称重记录';

CREATE TABLE wb_reading (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    weighing_id     BIGINT       NOT NULL,
    pivot_code      VARCHAR(24)  NOT NULL COMMENT '规则支点代码',
    pivot_name      VARCHAR(32)  NOT NULL,
    arm_mm          DECIMAL(12,3) NOT NULL COMMENT '支点臂长 mm（取自规则版本）',
    gross_original  DECIMAL(12,3) NULL COMMENT '毛重读数（原单位，NULL=缺失）',
    tare_original   DECIMAL(12,3) NULL COMMENT '皮重（原单位）',
    gross_kg        DECIMAL(12,3) NULL COMMENT '毛重 kg',
    tare_kg         DECIMAL(12,3) NULL COMMENT '皮重 kg',
    net_kg          DECIMAL(12,3) NULL COMMENT '净重 kg',
    net_original    DECIMAL(12,3) NULL COMMENT '净重（原单位 毛−皮）',
    moment          DECIMAL(18,3) NULL COMMENT '支点力矩 kg·mm',
    weight_unit     VARCHAR(4)   NOT NULL COMMENT 'KG/LB',
    PRIMARY KEY (id),
    UNIQUE KEY uk_wbr_weighing_pivot (weighing_id, pivot_code),
    KEY idx_wbr_weighing (weighing_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '称重·支点读数';

CREATE TABLE wb_temp_item (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    weighing_id     BIGINT       NOT NULL,
    kind            VARCHAR(8)   NOT NULL COMMENT 'DEDUCT 扣除 / ADD 补入',
    name            VARCHAR(64)  NOT NULL,
    weight_original DECIMAL(12,3) NOT NULL COMMENT '单项重量（原单位）',
    arm_original    DECIMAL(12,3) NOT NULL COMMENT '臂长（原单位）',
    weight_kg       DECIMAL(12,3) NULL COMMENT '重量 kg',
    arm_mm          DECIMAL(12,3) NULL COMMENT '臂长 mm',
    signed_weight   DECIMAL(12,3) NULL COMMENT '有符号重量 kg（ADD+/DEDUCT−）',
    signed_moment   DECIMAL(18,3) NULL COMMENT '有符号力矩 kg·mm',
    reason          VARCHAR(255) NULL,
    weight_unit     VARCHAR(4)   NOT NULL COMMENT 'KG/LB',
    arm_unit        VARCHAR(4)   NOT NULL COMMENT 'MM/IN',
    PRIMARY KEY (id),
    KEY idx_wbt_weighing (weighing_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '称重·临时扣除/补入项';

-- ----------------------------------------------------------------------------
-- 构型调整链
-- ----------------------------------------------------------------------------
CREATE TABLE wb_config_version (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    aircraft_id     BIGINT       NOT NULL,
    version_no      INT          NOT NULL COMMENT '同机版本号（跨重新称重连续递增）',
    parent_id       BIGINT       NULL COMMENT '上一版本 id；称重基线为空',
    source          VARCHAR(12)  NOT NULL COMMENT 'BASELINE 称重基线 / ADJUSTMENT 构型调整',
    weighing_id     BIGINT       NULL COMMENT '基线对应称重单',
    tail_no         VARCHAR(16)  NOT NULL,
    model           VARCHAR(16)  NOT NULL,
    change_date     DATE         NOT NULL,
    rule_id         BIGINT       NOT NULL COMMENT '本版本判定引用的规则（冻结）',
    rule_no         VARCHAR(32)  NOT NULL,
    change_summary  VARCHAR(512) NULL,
    total_weight    DECIMAL(12,3) NOT NULL COMMENT '冻结：总重 kg',
    total_moment    DECIMAL(18,3) NOT NULL COMMENT '冻结：总力矩 kg·mm',
    cg_arm          DECIMAL(12,3) NOT NULL COMMENT '冻结：重心臂 mm',
    envelope_status VARCHAR(20)  NOT NULL COMMENT '当时包线判定（历史不重算）',
    envelope_message VARCHAR(512) NULL,
    forward_limit   DECIMAL(12,3) NULL,
    aft_limit       DECIMAL(12,3) NULL,
    forward_margin  DECIMAL(12,3) NULL COMMENT '距前边界 mm（负=越界）',
    aft_margin      DECIMAL(12,3) NULL COMMENT '距后边界 mm（负=越界）',
    current_flag    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '1=当前版本（同机唯一）',
    operator        VARCHAR(32)  NULL,
    created_at      DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wcv_aircraft_ver (aircraft_id, version_no),
    KEY idx_wcv_aircraft (aircraft_id),
    KEY idx_wcv_parent (parent_id),
    KEY idx_wcv_current (aircraft_id, current_flag)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '称重基线派生构型版本';

CREATE TABLE wb_adjustment (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    version_id      BIGINT       NOT NULL,
    aircraft_id     BIGINT       NOT NULL COMMENT '冗余飞机 id（拦截跨机并入）',
    direction       VARCHAR(8)   NOT NULL COMMENT 'INSTALL 安装 / REMOVE 拆除',
    name            VARCHAR(64)  NOT NULL,
    weight_original DECIMAL(12,3) NOT NULL COMMENT '单件重量（原单位）',
    arm_original    DECIMAL(12,3) NOT NULL COMMENT '臂长（原单位）',
    quantity        INT          NOT NULL COMMENT '数量',
    weight_kg       DECIMAL(12,3) NOT NULL COMMENT '单件重量 kg',
    arm_mm          DECIMAL(12,3) NOT NULL COMMENT '臂长 mm',
    signed_moment   DECIMAL(18,3) NOT NULL COMMENT '有符号力矩 kg·mm（含数量）',
    signed_weight   DECIMAL(12,3) NOT NULL COMMENT '有符号总重增量 kg（含数量）',
    reason          VARCHAR(255) NULL,
    work_card_no    VARCHAR(32)  NULL COMMENT '关联工卡号',
    weight_unit     VARCHAR(4)   NOT NULL,
    arm_unit        VARCHAR(4)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_wba_version (version_id),
    KEY idx_wba_aircraft (aircraft_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '构型调整项（安装/拆除设备）';
