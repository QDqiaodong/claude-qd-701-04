<template>
  <div>
    <section class="panel">
      <div class="panel-head">
        <h3>飞机称重与重心</h3>
        <span class="panel-sub">
          规则版本可发布新版、旧版冻结不重算；换算/重心/包线全部由服务端在统一精度（kg、mm、3 位小数）计算并冻结
        </span>
        <div class="panel-extra">
          <el-radio-group v-model="displayMode" size="small">
            <el-radio-button label="unified">统一单位 KG/MM</el-radio-button>
            <el-radio-button label="original">原始单位</el-radio-button>
          </el-radio-group>
          <el-button size="small" @click="reloadAll">刷新</el-button>
        </div>
      </div>

      <el-tabs v-model="tab" @tab-change="onTab">
        <!-- ================= 1. 机队基线台账 ================= -->
        <el-tab-pane label="基线台账" name="fleet">
          <el-table :data="fleet" border stripe size="small">
            <el-table-column prop="tailNo" label="机号" width="92" />
            <el-table-column prop="model" label="机型" width="72" />
            <el-table-column label="基线状态" width="150">
              <template #default="{ row }">
                <el-tag v-if="row.baselineEstablished" type="success" size="small">
                  当前 V{{ row.currentVersionNo }}
                </el-tag>
                <el-tag v-else type="info" size="small">未建立基线</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="当前总重 / 重心臂" width="230">
              <template #default="{ row }">
                <template v-if="row.baselineEstablished">
                  {{ totalWeightText(row.currentTotalWeight, 'METRIC', 'unified') }}
                  / {{ cgArmText(row.currentCgArm, 'METRIC', 'unified') }}
                </template>
                <span v-else class="muted">—（不猜测默认重心）</span>
              </template>
            </el-table-column>
            <el-table-column label="当前包线" width="110">
              <template #default="{ row }">
                <el-tag v-if="row.envelopeStatus" size="small" :type="envTag(row.envelopeStatus)">
                  {{ row.envelopeStatusName }}
                </el-tag>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column prop="currentRuleNo" label="引用规则" width="120" />
            <el-table-column label="上次称重" width="120">
              <template #default="{ row }">{{ row.lastWeighingDate || '—' }}</template>
            </el-table-column>
            <el-table-column prop="note" label="备注" min-width="200" show-overflow-tooltip />
            <el-table-column label="操作" width="210" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openChain(row)">调整链/对比</el-button>
                <el-button link type="success" @click="openWeighingForm(row)">登记称重</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- ================= 2. 称重规则版本 ================= -->
        <el-tab-pane label="称重规则版本" name="rules">
          <div class="toolbar">
            <el-select v-model="ruleModel" placeholder="全部机型" clearable size="small" style="width: 130px"
                       @change="loadRules">
              <el-option v-for="m in models" :key="m" :label="m" :value="m" />
            </el-select>
            <el-button size="small" type="primary" @click="openRuleForm()">新建规则版本</el-button>
            <span class="muted">草稿可改；一经称重引用即永久冻结；新版发布不改写历史称重</span>
          </div>
          <el-table :data="rules" border stripe size="small">
            <el-table-column prop="ruleNo" label="规则编号" width="130" />
            <el-table-column prop="model" label="机型" width="72" />
            <el-table-column prop="versionNo" label="版本" width="60" />
            <el-table-column label="状态" width="150">
              <template #default="{ row }">
                <el-tag size="small" :type="ruleStatusTag(row.status)">{{ row.statusName }}</el-tag>
                <el-tag v-if="row.referenced" size="small" type="warning" style="margin-left: 4px">
                  已被引用
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="effectiveDate" label="生效日期" width="110" />
            <el-table-column prop="unitSystemName" label="单位制" width="130" />
            <el-table-column label="许用总重 KG" width="180">
              <template #default="{ row }">{{ row.minWeightKg }} ~ {{ row.maxWeightKg }}</template>
            </el-table-column>
            <el-table-column prop="forwardNodeCount" label="前边界节点" width="90" />
            <el-table-column label="操作" width="180" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="viewRule(row)">支点/包线</el-button>
                <el-button v-if="row.status === 'DRAFT'" link type="warning" @click="openRuleForm(row)">
                  编辑
                </el-button>
                <el-button v-if="row.status === 'DRAFT'" link type="success" @click="publishRule(row)">
                  发布
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- ================= 3. 称重记录 ================= -->
        <el-tab-pane label="称重记录" name="weighings">
          <div class="toolbar">
            <el-select v-model="weighingFilter" placeholder="全部飞机" clearable filterable size="small"
                       style="width: 180px" @change="loadWeighings">
              <el-option v-for="a in fleet" :key="a.aircraftId"
                         :label="a.tailNo + ' · ' + a.model" :value="a.aircraftId" />
            </el-select>
            <el-button size="small" type="primary" @click="openWeighingForm()">登记称重</el-button>
          </div>
          <el-table :data="weighings" border stripe size="small" row-key="id">
            <el-table-column prop="weighingNo" label="称重单号" width="130" />
            <el-table-column prop="tailNo" label="机号" width="86" />
            <el-table-column prop="weighingDate" label="称重日期" width="105" />
            <el-table-column prop="unitSystemName" label="原单位制" width="120" />
            <el-table-column prop="ruleNo" label="引用规则" width="120" />
            <el-table-column label="总重 / 重心臂" width="240">
              <template #default="{ row }">
                {{ totalWeightText(row.totalWeight, row.unitSystem, displayMode) }}
                / {{ cgArmText(row.cgArm, row.unitSystem, displayMode) }}
              </template>
            </el-table-column>
            <el-table-column label="包线判定" min-width="320">
              <template #default="{ row }">
                <el-tag size="small" :type="envTag(row.envelopeStatus)">{{ row.envelopeStatusName }}</el-tag>
                <span class="cell-note">{{ row.envelopeMessage }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="120">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'CONFIRMED' ? 'success' : 'info'">
                  {{ row.statusName }}
                </el-tag>
                <el-tag v-if="row.currentFlag" size="small" type="success" style="margin-left:4px">当前基线</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="160" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="viewWeighing(row)">计算明细</el-button>
                <el-button v-if="row.status === 'DRAFT'" link type="warning" @click="editWeighing(row)">
                  修改
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- ================= 4. 构型调整链 ================= -->
        <el-tab-pane :label="chain ? '构型调整链（' + chain.tailNo + '）' : '构型调整链'" name="chain">
          <div v-if="!chain" class="empty-hint">
            请从「基线台账」选择一架飞机查看完整调整链；无基线飞机会明确标注“未建立基线”。
          </div>
          <template v-else>
            <div class="toolbar">
              <el-tag type="info">链版本数 {{ chain.versions.length }}</el-tag>
              <el-tag v-if="chain.baselineEstablished" type="success">已建立基线</el-tag>
              <el-tag v-else type="warning">未建立基线</el-tag>
              <el-button size="small" type="primary" :disabled="!chain.baselineEstablished"
                         @click="openDeriveForm()">从当前版本派生调整</el-button>
              <el-button size="small" @click="openCompare()">版本对比</el-button>
            </div>
            <el-timeline>
              <el-timeline-item v-for="v in chain.versions" :key="v.id" :timestamp="v.changeDate"
                                placement="top" :type="v.currentFlag ? 'success' : 'info'">
                <el-card shadow="never" class="chain-card">
                  <div class="chain-head">
                    <b>V{{ v.versionNo }} · {{ v.sourceName }}</b>
                    <el-tag size="small" :type="envTag(v.result.status)">{{ v.result.statusName }}</el-tag>
                    <el-tag v-if="v.currentFlag" size="small" type="success">当前版本</el-tag>
                    <span class="muted">{{ v.weighingNo ? '称重单 ' + v.weighingNo : '' }} · 规则 {{ v.ruleNo }}
                      （{{ v.ruleStatusName }}）</span>
                  </div>
                  <div class="chain-fact">
                    总重 {{ totalWeightText(v.result.totalWeight, 'METRIC', 'unified') }}　·
                    重心臂 {{ cgArmText(v.result.cgArm, 'METRIC', 'unified') }}　·
                    总力矩 {{ momentText(v.result.totalMoment, 'METRIC', 'unified') }}
                  </div>
                  <div class="chain-msg" :class="{ out: v.result.status !== 'IN_ENVELOPE' }">
                    {{ v.result.message }}
                  </div>
                  <div v-if="v.changeSummary" class="muted">调整：{{ v.changeSummary }}</div>
                  <el-table v-if="v.adjustments && v.adjustments.length" :data="v.adjustments"
                            size="small" border style="margin-top: 6px">
                    <el-table-column label="方向" width="70">
                      <template #default="{ row }">
                        <el-tag size="small" :type="row.direction === 'INSTALL' ? 'success' : 'danger'">
                          {{ row.directionName }}
                        </el-tag>
                      </template>
                    </el-table-column>
                    <el-table-column prop="name" label="设备" min-width="140" />
                    <el-table-column prop="quantity" label="数量" width="56" />
                    <el-table-column label="单件重量 / 臂长" width="230">
                      <template #default="{ row }">
                        {{ weightText(row.weightOriginal, row.weightUnit, row.weightKg, displayMode) }}
                        / {{ armText(row.armOriginal, row.armUnit, row.armMm, displayMode) }}
                      </template>
                    </el-table-column>
                    <el-table-column prop="workCardNo" label="工卡" width="130" />
                    <el-table-column prop="reason" label="原因" min-width="120" show-overflow-tooltip />
                  </el-table>
                </el-card>
              </el-timeline-item>
            </el-timeline>
          </template>
        </el-tab-pane>
      </el-tabs>
    </section>

    <!-- ============ 规则编辑弹窗 ============ -->
    <el-dialog v-model="ruleFormVisible" :title="ruleForm.id ? '编辑规则草稿 ' + ruleForm.ruleNo : '新建称重规则版本'"
               width="860px" @closed="resetRuleForm">
      <el-form :model="ruleForm" label-width="110px">
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="机型">
              <el-select v-model="ruleForm.model" :disabled="!!ruleForm.id" style="width:100%">
                <el-option v-for="m in models" :key="m" :label="m" :value="m" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="生效日期">
              <el-date-picker v-model="ruleForm.effectiveDate" type="date" value-format="YYYY-MM-DD"
                              style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="单位制">
              <el-radio-group v-model="ruleForm.unitSystem">
                <el-radio label="METRIC">kg/mm</el-radio>
                <el-radio label="IMPERIAL">lb/in</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="基准点">
          <el-input v-model="ruleForm.datumName" placeholder="如 机身站位 FS0" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item :label="'许用最小总重(' + ruleWUnit + ')'">
              <el-input-number v-model="ruleForm.minWeight" :min="0" :precision="3" :controls="false"
                               style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item :label="'许用最大总重(' + ruleWUnit + ')'">
              <el-input-number v-model="ruleForm.maxWeight" :min="0" :precision="3" :controls="false"
                               style="width:100%" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-divider content-position="left">起落架支点臂长（{{ ruleAUnit }}，相对基准点）</el-divider>
        <el-table :data="ruleForm.pivots" size="small" border>
          <el-table-column label="支点" width="200">
            <template #default="{ row, $index }">
              <el-select v-model="ruleForm.pivots[$index].pivotCode" size="small">
                <el-option label="前起落架 NOSE" value="NOSE" />
                <el-option label="左主起 LEFT_MAIN" value="LEFT_MAIN" />
                <el-option label="右主起 RIGHT_MAIN" value="RIGHT_MAIN" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column :label="'臂长(' + ruleAUnit + ')'">
            <template #default="{ row, $index }">
              <el-input-number v-model="ruleForm.pivots[$index].arm" :min="0" :precision="3"
                               :controls="false" size="small" style="width:100%" />
            </template>
          </el-table-column>
          <el-table-column width="60">
            <template #default="{ $index }">
              <el-button link type="danger" @click="ruleForm.pivots.splice($index, 1)">删</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-button size="small" style="margin-top:6px" @click="addPivot">+ 支点</el-button>

        <el-divider content-position="left">重心包线折线节点（重量 {{ ruleWUnit }} / 臂长 {{ ruleAUnit }}）</el-divider>
        <el-row :gutter="16">
          <el-col :span="12">
            <div class="node-title">前边界（重量升序）</div>
            <node-editor v-model="ruleForm.forwardNodes" />
          </el-col>
          <el-col :span="12">
            <div class="node-title">后边界（重量升序）</div>
            <node-editor v-model="ruleForm.aftNodes" />
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="ruleFormVisible = false">取消</el-button>
        <el-button type="primary" @click="submitRule">保存草稿</el-button>
      </template>
    </el-dialog>

    <!-- ============ 规则只读详情 ============ -->
    <el-dialog v-model="ruleDetailVisible" title="规则版本详情（支点与包线折线）" width="780px">
      <template v-if="ruleDetail">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item :label="ruleDetail.ruleNo">{{ ruleDetail.ruleNo }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ ruleDetail.statusName }}</el-descriptions-item>
          <el-descriptions-item label="生效">{{ ruleDetail.effectiveDate }}</el-descriptions-item>
          <el-descriptions-item label="单位制" :span="3">{{ ruleDetail.unitSystemName }}（统一存 kg/mm）</el-descriptions-item>
          <el-descriptions-item label="基准点" :span="3">{{ ruleDetail.datumName }} — {{ ruleDetail.datumNote }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="ruleDetail.pivots" size="small" border style="margin-top:8px">
          <el-table-column prop="pivotName" label="支点" />
          <el-table-column prop="pivotCode" label="代码" width="140" />
          <el-table-column label="臂长（原始 / 统一）">
            <template #default="{ row }">
              {{ row.armOriginalValue }} {{ row.armOriginalUnit }}　/　{{ row.armMm }} MM
            </template>
          </el-table-column>
        </el-table>
        <el-row :gutter="12" style="margin-top:8px">
          <el-col :span="12">
            <div class="node-title">前边界节点（KG / MM）</div>
            <el-table :data="ruleDetail.forwardNodes" size="small" border>
              <el-table-column prop="seqNo" label="#" width="44" />
              <el-table-column prop="weightKg" label="重量 KG" />
              <el-table-column prop="armMm" label="臂长 MM" />
            </el-table>
          </el-col>
          <el-col :span="12">
            <div class="node-title">后边界节点（KG / MM）</div>
            <el-table :data="ruleDetail.aftNodes" size="small" border>
              <el-table-column prop="seqNo" label="#" width="44" />
              <el-table-column prop="weightKg" label="重量 KG" />
              <el-table-column prop="armMm" label="臂长 MM" />
            </el-table>
          </el-col>
        </el-row>
      </template>
    </el-dialog>

    <!-- ============ 称重登记/修改弹窗（数据提交服务端试算） ============ -->
    <el-dialog v-model="weighingFormVisible" :title="weighingForm.id ? '修改待复核称重' : '登记实机称重'"
               width="900px" @closed="resetWeighingForm">
      <el-form :model="weighingForm" label-width="105px">
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="飞机">
              <el-select v-model="weighingForm.aircraftId" filterable style="width:100%">
                <el-option v-for="a in fleet" :key="a.aircraftId"
                           :label="a.tailNo + ' · ' + a.model" :value="a.aircraftId" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="称重日期">
              <el-date-picker v-model="weighingForm.weighingDate" type="date" value-format="YYYY-MM-DD"
                              style="width:100%" @change="onWeighingInputChange" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="单位制">
              <el-radio-group v-model="weighingForm.unitSystem" @change="onWeighingInputChange">
                <el-radio label="METRIC">kg/mm</el-radio>
                <el-radio label="IMPERIAL">lb/in</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="秤具编号"><el-input v-model="weighingForm.scaleNo" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="登记人"><el-input v-model="weighingForm.weighedBy" /></el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="去皮方式">
          <el-radio-group v-model="weighingForm.tarePerPivot" @change="onWeighingInputChange">
            <el-radio :label="false">统一皮重</el-radio>
            <el-radio :label="true">逐支点去皮</el-radio>
          </el-radio-group>
          <el-input-number v-if="!weighingForm.tarePerPivot" v-model="weighingForm.commonTare"
                           :min="0" :precision="3" size="small" style="margin-left:12px;width:160px" />
        </el-form-item>

        <el-divider content-position="left">各支点读数（{{ wWeightUnit }}，毛重缺失将无法判定包线）</el-divider>
        <el-alert v-if="resolvedRule" :title="resolvedRule.message" type="success" :closable="false"
                  style="margin-bottom:8px" />
        <el-table :data="weighingForm.readings" size="small" border>
          <el-table-column prop="pivotName" label="支点" width="170" />
          <el-table-column label="臂长（规则值，不可改）" width="150">
            <template #default="{ row }">{{ row.armMm }} MM</template>
          </el-table-column>
          <el-table-column :label="'毛重 ' + wWeightUnit">
            <template #default="{ row, $index }">
              <el-input-number v-model="weighingForm.readings[$index].gross" :precision="3"
                               :controls="false" size="small" style="width:100%"
                               @change="onWeighingInputChange" />
            </template>
          </el-table-column>
          <el-table-column v-if="weighingForm.tarePerPivot" :label="'皮重 ' + wWeightUnit">
            <template #default="{ row, $index }">
              <el-input-number v-model="weighingForm.readings[$index].tare" :precision="3" :min="0"
                               :controls="false" size="small" style="width:100%"
                               @change="onWeighingInputChange" />
            </template>
          </el-table-column>
        </el-table>

        <el-row :gutter="12" style="margin-top:8px">
          <el-col :span="12">
            <el-form-item label="燃油条件"><el-input v-model="weighingForm.fuelCondition" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item :label="'燃油量 ' + wWeightUnit">
              <el-input-number v-model="weighingForm.fuelQty" :precision="3" :min="0" :controls="false"
                               style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="滑油条件"><el-input v-model="weighingForm.oilCondition" /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item :label="'滑油量 ' + wWeightUnit">
              <el-input-number v-model="weighingForm.oilQty" :precision="3" :min="0" :controls="false"
                               style="width:100%" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-divider content-position="left">临时扣除 / 补入项</el-divider>
        <el-table :data="weighingForm.tempItems" size="small" border>
          <el-table-column label="方向" width="92">
            <template #default="{ row, $index }">
              <el-select v-model="weighingForm.tempItems[$index].kind" size="small"
                         @change="onWeighingInputChange">
                <el-option label="扣除" value="DEDUCT" />
                <el-option label="补入" value="ADD" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="名称" width="160">
            <template #default="{ row, $index }">
              <el-input v-model="weighingForm.tempItems[$index].name" size="small" />
            </template>
          </el-table-column>
          <el-table-column :label="'重量 ' + wWeightUnit" width="130">
            <template #default="{ row, $index }">
              <el-input-number v-model="weighingForm.tempItems[$index].weight" :precision="3" :min="0"
                               :controls="false" size="small" style="width:100%"
                               @change="onWeighingInputChange" />
            </template>
          </el-table-column>
          <el-table-column :label="'臂长 ' + wArmUnit" width="130">
            <template #default="{ row, $index }">
              <el-input-number v-model="weighingForm.tempItems[$index].arm" :precision="3" :min="0"
                               :controls="false" size="small" style="width:100%"
                               @change="onWeighingInputChange" />
            </template>
          </el-table-column>
          <el-table-column label="原因" min-width="120">
            <template #default="{ row, $index }">
              <el-input v-model="weighingForm.tempItems[$index].reason" size="small" />
            </template>
          </el-table-column>
          <el-table-column width="50">
            <template #default="{ $index }">
              <el-button link type="danger" @click="weighingForm.tempItems.splice($index, 1)">删</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-button size="small" style="margin-top:6px" @click="addTempItem">+ 临时项</el-button>
        <el-form-item label="备注" style="margin-top:8px"><el-input v-model="weighingForm.remark" /></el-form-item>
      </el-form>

      <!-- 服务端试算结果（前端不算，只展示） -->
      <div v-if="preview" class="preview-box">
        <div class="preview-head">
          <b>服务端试算</b>
          <el-tag size="small" :type="envTag(preview.result.status)">{{ preview.result.statusName }}</el-tag>
          <span class="muted">规则 {{ preview.ruleNo }}（{{ preview.ruleStatusName }}）</span>
        </div>
        <div class="preview-msg">{{ preview.result.message }}</div>
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="总重">
            {{ totalWeightText(preview.result.totalWeight, weighingForm.unitSystem, displayMode) }}
          </el-descriptions-item>
          <el-descriptions-item label="总力矩">
            {{ momentText(preview.result.totalMoment, weighingForm.unitSystem, displayMode) }}
          </el-descriptions-item>
          <el-descriptions-item label="重心臂">
            {{ cgArmText(preview.result.cgArm, weighingForm.unitSystem, displayMode) }}
          </el-descriptions-item>
          <el-descriptions-item label="前边界(插值)">
            {{ cgArmText(preview.result.forwardLimit, weighingForm.unitSystem, displayMode) }}
          </el-descriptions-item>
          <el-descriptions-item label="后边界(插值)">
            {{ cgArmText(preview.result.aftLimit, weighingForm.unitSystem, displayMode) }}
          </el-descriptions-item>
          <el-descriptions-item label="缺失支点">{{ preview.missingCount }}</el-descriptions-item>
        </el-descriptions>
      </div>

      <template #footer>
        <el-button @click="weighingFormVisible = false">取消</el-button>
        <el-button @click="doPreview">服务端试算</el-button>
        <el-button type="primary" @click="submitWeighing">保存待复核</el-button>
      </template>
    </el-dialog>

    <!-- ============ 称重计算明细弹窗 ============ -->
    <el-dialog v-model="weighingDetailVisible" title="称重计算明细（冻结事实）" width="900px">
      <template v-if="weighingDetail">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item :label="weighingDetail.weighingNo">{{ weighingDetail.weighingNo }}</el-descriptions-item>
          <el-descriptions-item label="飞机">{{ weighingDetail.tailNo }} · {{ weighingDetail.model }}</el-descriptions-item>
          <el-descriptions-item label="日期">{{ weighingDetail.weighingDate }}</el-descriptions-item>
          <el-descriptions-item label="秤具">{{ weighingDetail.scaleNo }}</el-descriptions-item>
          <el-descriptions-item label="原单位制">{{ weighingDetail.unitSystemName }}</el-descriptions-item>
          <el-descriptions-item label="引用规则">
            {{ weighingDetail.ruleNo }}（{{ weighingDetail.ruleStatusName }}）
          </el-descriptions-item>
          <el-descriptions-item label="燃油" :span="3">{{ weighingDetail.fuelCondition }}
            （{{ weighingDetail.fuelQtyOriginal }}）</el-descriptions-item>
          <el-descriptions-item label="滑油" :span="3">{{ weighingDetail.oilCondition }}
            （{{ weighingDetail.oilQtyOriginal }}）</el-descriptions-item>
        </el-descriptions>

        <el-table :data="weighingDetail.readings" size="small" border style="margin-top:8px">
          <el-table-column prop="pivotName" label="支点" width="120" />
          <el-table-column label="毛重">
            <template #default="{ row }">
              {{ weightText(row.grossOriginal, row.weightUnit, row.grossKg, displayMode) }}
            </template>
          </el-table-column>
          <el-table-column label="皮重">
            <template #default="{ row }">
              {{ weightText(row.tareOriginal, row.weightUnit, row.tareKg, displayMode) }}
            </template>
          </el-table-column>
          <el-table-column label="净重">
            <template #default="{ row }">
              <span :class="{ 'miss': row.grossMissing }">
                {{ row.grossMissing ? '读数缺失' : weightText(row.netOriginal, row.weightUnit, row.netKg, displayMode) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="臂长">
            <template #default="{ row }">{{ armText(null, null, row.armMm, 'unified') }}</template>
          </el-table-column>
          <el-table-column label="力矩 KG·MM">
            <template #default="{ row }">{{ row.moment === null ? '—' : row.moment }}</template>
          </el-table-column>
        </el-table>

        <el-table v-if="weighingDetail.tempItems.length" :data="weighingDetail.tempItems"
                  size="small" border style="margin-top:8px">
          <el-table-column prop="kindName" label="方向" width="70" />
          <el-table-column prop="name" label="临时项" min-width="140" />
          <el-table-column label="重量">
            <template #default="{ row }">
              {{ weightText(row.weightOriginal, row.weightUnit, row.weightKg, displayMode) }}
            </template>
          </el-table-column>
          <el-table-column label="臂长">
            <template #default="{ row }">
              {{ armText(row.armOriginal, row.armUnit, row.armMm, displayMode) }}
            </template>
          </el-table-column>
          <el-table-column label="有符号力矩 KG·MM">
            <template #default="{ row }">{{ row.signedMoment }}</template>
          </el-table-column>
          <el-table-column prop="reason" label="原因" min-width="120" />
        </el-table>

        <el-alert class="result-alert" :type="envAlert(weighingDetail.result.status)"
                  :closable="false" show-icon>
          <template #title>
            <b>{{ weighingDetail.result.statusName }}</b>：{{ weighingDetail.result.message }}
          </template>
          <div class="result-grid">
            <span>总重：{{ totalWeightText(weighingDetail.result.totalWeight, weighingDetail.unitSystem, displayMode) }}</span>
            <span>总力矩：{{ momentText(weighingDetail.result.totalMoment, weighingDetail.unitSystem, displayMode) }}</span>
            <span>重心臂：{{ cgArmText(weighingDetail.result.cgArm, weighingDetail.unitSystem, displayMode) }}</span>
            <span>前边界：{{ cgArmText(weighingDetail.result.forwardLimit, weighingDetail.unitSystem, displayMode) }}</span>
            <span>后边界：{{ cgArmText(weighingDetail.result.aftLimit, weighingDetail.unitSystem, displayMode) }}</span>
          </div>
        </el-alert>

        <div v-if="weighingDetail.status === 'DRAFT'" class="confirm-bar">
          <el-divider content-position="left">两人复核确认（须不同于登记人且互不相同；越界不可确认）</el-divider>
          <el-input v-model="review.reviewedBy" placeholder="第一复核人" style="width:180px" />
          <el-input v-model="review.confirmedBy" placeholder="第二复核人" style="width:180px;margin:0 8px" />
          <el-button type="primary" @click="doConfirm(weighingDetail)">复核确认成基线</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- ============ 派生调整弹窗 ============ -->
    <el-dialog v-model="deriveFormVisible" title="从当前构型版本派生调整版本" width="860px">
      <el-form label-width="90px">
        <el-row :gutter="12">
          <el-col :span="8">
            <el-form-item label="调整日期">
              <el-date-picker v-model="deriveForm.changeDate" type="date" value-format="YYYY-MM-DD"
                              style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="单位制">
              <el-radio-group v-model="deriveForm.unitSystem">
                <el-radio label="METRIC">kg/mm</el-radio>
                <el-radio label="IMPERIAL">lb/in</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="操作人"><el-input v-model="deriveForm.operator" /></el-form-item>
          </el-col>
        </el-row>
        <el-table :data="deriveForm.items" size="small" border>
          <el-table-column label="方向" width="92">
            <template #default="{ row, $index }">
              <el-select v-model="deriveForm.items[$index].direction" size="small">
                <el-option label="安装" value="INSTALL" />
                <el-option label="拆除" value="REMOVE" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="设备" min-width="130">
            <template #default="{ row, $index }">
              <el-input v-model="deriveForm.items[$index].name" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="数量" width="80">
            <template #default="{ row, $index }">
              <el-input-number v-model="deriveForm.items[$index].quantity" :min="1" :controls="false"
                               size="small" style="width:100%" />
            </template>
          </el-table-column>
          <el-table-column :label="'单件重 ' + dwUnit" width="120">
            <template #default="{ row, $index }">
              <el-input-number v-model="deriveForm.items[$index].weight" :precision="3" :min="0"
                               :controls="false" size="small" style="width:100%" />
            </template>
          </el-table-column>
          <el-table-column :label="'臂长 ' + daUnit" width="120">
            <template #default="{ row, $index }">
              <el-input-number v-model="deriveForm.items[$index].arm" :precision="3" :controls="false"
                               size="small" style="width:100%" />
            </template>
          </el-table-column>
          <el-table-column label="工卡" width="120">
            <template #default="{ row, $index }">
              <el-input v-model="deriveForm.items[$index].workCardNo" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="原因" min-width="100">
            <template #default="{ row, $index }">
              <el-input v-model="deriveForm.items[$index].reason" size="small" />
            </template>
          </el-table-column>
          <el-table-column width="50">
            <template #default="{ $index }">
              <el-button link type="danger" @click="deriveForm.items.splice($index, 1)">删</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-button size="small" style="margin-top:6px" @click="addAdjustment">+ 调整项</el-button>
      </el-form>
      <template #footer>
        <el-button @click="deriveFormVisible = false">取消</el-button>
        <el-button type="primary" @click="doDerive">生成新版本（冻结上一版）</el-button>
      </template>
    </el-dialog>

    <!-- ============ 版本对比弹窗 ============ -->
    <el-dialog v-model="compareVisible" title="构型版本对比" width="820px">
      <template v-if="compare">
        <el-row :gutter="12">
          <el-col :span="10">
            <el-select v-model="compareFrom" size="small" style="width:100%" @change="loadCompare">
              <el-option v-for="v in chain.versions" :key="v.id"
                         :label="'V' + v.versionNo + ' ' + v.sourceName + ' ' + v.changeDate" :value="v.id" />
            </el-select>
          </el-col>
          <el-col :span="10">
            <el-select v-model="compareTo" size="small" style="width:100%" @change="loadCompare">
              <el-option v-for="v in chain.versions" :key="v.id"
                         :label="'V' + v.versionNo + ' ' + v.sourceName + ' ' + v.changeDate" :value="v.id" />
            </el-select>
          </el-col>
        </el-row>
        <el-descriptions :column="1" border size="small" style="margin-top:10px">
          <el-descriptions-item label="总重变化（KG）">
            <b :class="compare.deltaWeight > 0 ? 'delta-up' : 'delta-down'">
              {{ compare.deltaWeight > 0 ? '+' : '' }}{{ compare.deltaWeight }}
            </b>
          </el-descriptions-item>
          <el-descriptions-item label="总力矩变化（KG·MM）">
            {{ compare.deltaMoment > 0 ? '+' : '' }}{{ compare.deltaMoment }}
          </el-descriptions-item>
          <el-descriptions-item label="重心臂变化（MM）">
            {{ compare.deltaCgArm > 0 ? '+' : '' }}{{ compare.deltaCgArm }}
          </el-descriptions-item>
          <el-descriptions-item label="包线结果是否变化">
            <el-tag size="small" :type="compare.envelopeStatusChanged ? 'warning' : 'success'">
              {{ compare.envelopeStatusChanged ? '发生变化' : '未变化（各自保留当时规则与判定，未重算）' }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>
        <div class="node-title" style="margin-top:10px">
          {{ compare.to.versionNo }} 版本判定：{{ compare.to.envelopeStatusName }} — {{ compare.to.envelopeMessage }}
        </div>
        <el-table :data="compare.toAdjustments" size="small" border>
          <el-table-column prop="directionName" label="方向" width="70" />
          <el-table-column prop="name" label="设备" min-width="140" />
          <el-table-column prop="quantity" label="数量" width="60" />
          <el-table-column label="重量 KG" width="110">
            <template #default="{ row }">{{ row.weightKg }}</template>
          </el-table-column>
          <el-table-column label="臂长 MM" width="110">
            <template #default="{ row }">{{ row.armMm }}</template>
          </el-table-column>
          <el-table-column prop="workCardNo" label="工卡" width="120" />
        </el-table>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, defineComponent, h, inject, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { weightApi } from '../api'
import {
  totalWeightText, cgArmText, momentText, weightText, armText
} from './weight-units'

const mro = inject('mro')
const models = ['B737', 'A320', 'A330', 'ARJ21']

const tab = ref('fleet')
const displayMode = ref('unified')
const fleet = ref([])
const rules = ref([])
const ruleModel = ref('')
const weighings = ref([])
const weighingFilter = ref(null)
const chain = ref(null)

// ---------------- 内联的包线节点编辑器（简单函数式组件） ----------------
const NodeEditor = defineComponent({
  props: { modelValue: { type: Array, required: true } },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    const update = (i, field, val) => {
      const copy = props.modelValue.map((n) => ({ ...n }))
      copy[i][field] = val
      emit('update:modelValue', copy)
    }
    const add = () => emit('update:modelValue', [...props.modelValue, { weight: null, arm: null }])
    const del = (i) => emit('update:modelValue', props.modelValue.filter((_, idx) => idx !== i))
    return () => h('div', [
      h('table', { class: 'node-tbl' }, [
        h('thead', null, h('tr', null, [h('th', null, '#'), h('th', null, '重量'), h('th', null, '臂长'), h('th', null, '')])),
        h('tbody', null, props.modelValue.map((n, i) => h('tr', null, [
          h('td', null, String(i + 1)),
          h('td', null, h('input', {
            class: 'node-inp', value: n.weight ?? '', type: 'number', step: '0.001',
            onInput: (e) => update(i, 'weight', e.target.value === '' ? null : Number(e.target.value))
          })),
          h('td', null, h('input', {
            class: 'node-inp', value: n.arm ?? '', type: 'number', step: '0.001',
            onInput: (e) => update(i, 'arm', e.target.value === '' ? null : Number(e.target.value))
          })),
          h('td', null, h('button', { type: 'button', class: 'node-del', onClick: () => del(i) }, '删'))
        ])))
      ]),
      h('button', { type: 'button', class: 'node-add', onClick: add }, '+ 节点')
    ])
  }
})

const envTag = (s) => (s === 'IN_ENVELOPE' ? 'success' : s === 'OUT_OF_ENVELOPE' ? 'danger' : 'info')
const envAlert = (s) => (s === 'IN_ENVELOPE' ? 'success' : s === 'OUT_OF_ENVELOPE' ? 'error' : 'warning')
const ruleStatusTag = (s) => (s === 'EFFECTIVE' ? 'success' : s === 'DRAFT' ? 'info' : 'warning')

// ---------------- 加载 ----------------
async function reloadAll() {
  await loadFleet()
  await Promise.all([loadRules(), loadWeighings()])
}
async function loadFleet() {
  fleet.value = await weightApi.fleet()
}
async function loadRules() {
  rules.value = await weightApi.rules(ruleModel.value || undefined)
}
async function loadWeighings() {
  weighings.value = await weightApi.weighings(weighingFilter.value || undefined)
}
function onTab() {
  if (tab.value === 'rules') loadRules()
  if (tab.value === 'weighings') loadWeighings()
  if (tab.value === 'fleet') loadFleet()
}

// ---------------- 规则表单 ----------------
const ruleFormVisible = ref(false)
const ruleDetailVisible = ref(false)
const ruleDetail = ref(null)
const blankRule = () => ({
  id: null, ruleNo: '', model: 'B737', effectiveDate: today(), unitSystem: 'METRIC',
  datumName: '机身站位 FS0', datumNote: '', minWeight: null, maxWeight: null,
  pivots: [
    { pivotCode: 'NOSE', displayOrder: 1, arm: null },
    { pivotCode: 'LEFT_MAIN', displayOrder: 2, arm: null },
    { pivotCode: 'RIGHT_MAIN', displayOrder: 3, arm: null }
  ],
  forwardNodes: [{ weight: null, arm: null }, { weight: null, arm: null }],
  aftNodes: [{ weight: null, arm: null }, { weight: null, arm: null }]
})
const ruleForm = ref(blankRule())
const ruleWUnit = computed(() => (ruleForm.value.unitSystem === 'IMPERIAL' ? 'LB' : 'KG'))
const ruleAUnit = computed(() => (ruleForm.value.unitSystem === 'IMPERIAL' ? 'IN' : 'MM'))

function openRuleForm(row) {
  if (row) {
    weightApi.rule(row.id).then((detail) => {
      ruleForm.value = {
        id: detail.id, ruleNo: detail.ruleNo, model: detail.model,
        effectiveDate: detail.effectiveDate, unitSystem: detail.unitSystem,
        datumName: detail.datumName, datumNote: detail.datumNote,
        minWeight: detail.minWeightOriginal, maxWeight: detail.maxWeightOriginal,
        pivots: detail.pivots.map((p) => ({
          pivotCode: p.pivotCode, displayOrder: p.displayOrder, arm: p.armOriginalValue
        })),
        forwardNodes: detail.forwardNodes.map((n) => ({
          weight: n.weightOriginalValue, arm: n.armOriginalValue
        })),
        aftNodes: detail.aftNodes.map((n) => ({
          weight: n.weightOriginalValue, arm: n.armOriginalValue
        }))
      }
      ruleFormVisible.value = true
    })
  } else {
    ruleForm.value = blankRule()
    ruleFormVisible.value = true
  }
}
function resetRuleForm() { ruleForm.value = blankRule() }
function addPivot() { ruleForm.value.pivots.push({ pivotCode: 'NOSE', arm: null }) }
async function submitRule() {
  const payload = { ...ruleForm.value }
  delete payload.id
  delete payload.ruleNo
  if (ruleForm.value.id) {
    await weightApi.updateRule(payload, ruleForm.value.id)
    ElMessage.success('草稿已更新')
  } else {
    await weightApi.createRule(payload)
    ElMessage.success('草稿已创建')
  }
  ruleFormVisible.value = false
  loadRules()
}
async function publishRule(row) {
  const rule = await weightApi.rule(row.id)
  await weightApi.publishRule(row.id, rule.effectiveDate)
  ElMessage.success('规则已发布生效')
  loadRules()
}
async function viewRule(row) {
  ruleDetail.value = await weightApi.rule(row.id)
  ruleDetailVisible.value = true
}

// ---------------- 称重表单 ----------------
const weighingFormVisible = ref(false)
const weighingDetailVisible = ref(false)
const weighingDetail = ref(null)
const preview = ref(null)
const resolvedRule = ref(null)
const review = ref({ reviewedBy: '', confirmedBy: '' })

const blankWeighing = () => ({
  id: null, aircraftId: mro.aircraftId.value || null, weighingDate: today(), scaleNo: '',
  unitSystem: 'METRIC', tarePerPivot: true, commonTare: 0,
  fuelCondition: '', fuelQty: 0, oilCondition: '', oilQty: 0, remark: '', weighedBy: '',
  readings: [], tempItems: []
})
const weighingForm = ref(blankWeighing())
const wWeightUnit = computed(() => (weighingForm.value.unitSystem === 'IMPERIAL' ? 'LB' : 'KG'))
const wArmUnit = computed(() => (weighingForm.value.unitSystem === 'IMPERIAL' ? 'IN' : 'MM'))

function openWeighingForm(row) {
  const form = blankWeighing()
  if (row) form.aircraftId = row.aircraftId
  weighingForm.value = form
  preview.value = null
  resolvedRule.value = null
  weighingFormVisible.value = true
  onWeighingInputChange()
}
function resetWeighingForm() {
  preview.value = null
  resolvedRule.value = null
}
function addTempItem() {
  weighingForm.value.tempItems.push({ kind: 'DEDUCT', name: '', weight: null, arm: null, reason: '' })
}

let previewTimer = null
function onWeighingInputChange() {
  clearTimeout(previewTimer)
  previewTimer = setTimeout(async () => {
    const f = weighingForm.value
    if (!f.aircraftId || !f.weighingDate) {
      resolvedRule.value = null
      return
    }
    const model = (fleet.value.find((a) => a.aircraftId === f.aircraftId) || {}).model
    if (!model) return
    try {
      resolvedRule.value = await weightApi.resolveRule(model, f.weighingDate)
      // 按规则支点生成读数行（臂长属于规则，前端不允许改）
      const rule = await weightApi.rule(resolvedRule.value.ruleId)
      const byCode = Object.fromEntries(f.readings.map((r) => [r.pivotCode, r]))
      f.readings = rule.pivots.map((p) => ({
        pivotCode: p.pivotCode, pivotName: p.pivotName, armMm: p.armMm,
        gross: byCode[p.pivotCode] ? byCode[p.pivotCode].gross : null,
        tare: byCode[p.pivotCode] ? byCode[p.pivotCode].tare : 0
      }))
    } catch (e) {
      resolvedRule.value = null
    }
  }, 250)
}

async function doPreview() {
  preview.value = await weightApi.previewWeighing({ ...weighingForm.value, ruleId: resolvedRule.value?.ruleId })
}
async function submitWeighing() {
  const payload = { ...weighingForm.value, ruleId: resolvedRule.value?.ruleId }
  if (payload.id) {
    await weightApi.updateWeighing(payload.id, payload)
    ElMessage.success('称重记录已更新')
  } else {
    delete payload.id
    await weightApi.createWeighing(payload)
    ElMessage.success('称重记录已登记，待复核')
  }
  weighingFormVisible.value = false
  loadWeighings()
  loadFleet()
}
async function editWeighing(row) {
  const detail = await weightApi.weighing(row.id)
  weighingForm.value = {
    id: detail.id, aircraftId: detail.aircraftId, weighingDate: detail.weighingDate,
    scaleNo: detail.scaleNo, unitSystem: detail.unitSystem,
    tarePerPivot: detail.tarePerPivot, commonTare: detail.commonTareOriginal,
    fuelCondition: detail.fuelCondition, fuelQty: detail.fuelQtyOriginal,
    oilCondition: detail.oilCondition, oilQty: detail.oilQtyOriginal,
    remark: detail.remark, weighedBy: detail.weighedBy,
    readings: detail.readings.map((r) => ({
      pivotCode: r.pivotCode, pivotName: r.pivotName, armMm: r.armMm,
      gross: r.grossOriginal, tare: r.tareOriginal
    })),
    tempItems: detail.tempItems.map((t) => ({
      kind: t.kind, name: t.name, weight: t.weightOriginal, arm: t.armOriginal, reason: t.reason
    }))
  }
  resolvedRule.value = { ruleId: detail.ruleId, message: '沿用历史引用规则 ' + detail.ruleNo }
  preview.value = null
  weighingFormVisible.value = true
}
async function viewWeighing(row) {
  weighingDetail.value = await weightApi.weighing(row.id)
  review.value = { reviewedBy: '', confirmedBy: '' }
  weighingDetailVisible.value = true
}
async function doConfirm(detail) {
  await weightApi.confirmWeighing(detail.id, { ...review.value })
  ElMessage.success('已复核确认，成为当前称重基线')
  weighingDetailVisible.value = false
  loadWeighings()
  loadFleet()
  if (chain.value && chain.value.aircraftId === detail.aircraftId) openChainById(detail.aircraftId)
}

// ---------------- 调整链与派生 ----------------
async function openChain(row) {
  tab.value = 'chain'
  chain.value = await weightApi.chain(row.aircraftId)
}
async function openChainById(id) {
  chain.value = await weightApi.chain(id)
}
const deriveFormVisible = ref(false)
const deriveForm = ref({})
const dwUnit = computed(() => (deriveForm.value.unitSystem === 'IMPERIAL' ? 'LB' : 'KG'))
const daUnit = computed(() => (deriveForm.value.unitSystem === 'IMPERIAL' ? 'IN' : 'MM'))

function openDeriveForm() {
  deriveForm.value = {
    aircraftId: chain.value.aircraftId, changeDate: today(), unitSystem: 'METRIC',
    operator: '', items: [{ direction: 'INSTALL', name: '', quantity: 1, weight: null, arm: null,
      workCardNo: '', reason: '' }]
  }
  deriveFormVisible.value = true
}
function addAdjustment() {
  deriveForm.value.items.push({ direction: 'INSTALL', name: '', quantity: 1, weight: null,
    arm: null, workCardNo: '', reason: '' })
}
async function doDerive() {
  await weightApi.derive(deriveForm.value)
  ElMessage.success('已生成新构型版本')
  deriveFormVisible.value = false
  chain.value = await weightApi.chain(chain.value.aircraftId)
  loadFleet()
}

// ---------------- 版本对比 ----------------
const compareVisible = ref(false)
const compare = ref(null)
const compareFrom = ref(null)
const compareTo = ref(null)
async function openCompare() {
  const vers = chain.value.versions
  compareFrom.value = vers[0].id
  compareTo.value = chain.value.currentVersionId || vers[vers.length - 1].id
  compareVisible.value = true
  await loadCompare()
}
async function loadCompare() {
  compare.value = await weightApi.compare(chain.value.aircraftId, {
    fromVersionId: compareFrom.value, toVersionId: compareTo.value
  })
}

function today() {
  return new Date().toISOString().slice(0, 10)
}

onMounted(reloadAll)
</script>

<style scoped>
.panel-sub { margin-right: 12px; }
.panel-extra { display: flex; gap: 8px; align-items: center; }
.toolbar { display: flex; gap: 10px; align-items: center; margin-bottom: 10px; }
.muted { color: #909399; font-size: 12px; }
.cell-note { margin-left: 8px; color: #606266; font-size: 12px; }
.empty-hint { color: #909399; padding: 30px; text-align: center; border: 1px dashed #dcdfe6; border-radius: 6px; }
.chain-card { margin-bottom: 2px; }
.chain-head { display: flex; gap: 8px; align-items: center; }
.chain-fact { margin: 6px 0; font-size: 13px; }
.chain-msg { font-size: 13px; color: #67c23a; margin-bottom: 4px; }
.chain-msg.out { color: #f56c6c; font-weight: 600; }
.node-title { font-weight: 600; font-size: 13px; margin-bottom: 4px; }
.preview-box { border: 1px solid #dcdfe6; border-radius: 6px; padding: 10px; background: #fafafa; }
.preview-head { display: flex; gap: 8px; align-items: center; margin-bottom: 6px; }
.preview-msg { color: #606266; font-size: 13px; margin-bottom: 8px; }
.miss { color: #f56c6c; font-weight: 600; }
.result-alert { margin-top: 10px; }
.result-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 4px 12px; margin-top: 6px; font-size: 13px; }
.confirm-bar { margin-top: 10px; }
.delta-up { color: #f56c6c; }
.delta-down { color: #67c23a; }
:deep(.node-tbl) { width: 100%; border-collapse: collapse; font-size: 12px; }
:deep(.node-tbl th), :deep(.node-tbl td) { border: 1px solid #ebeef5; padding: 3px; text-align: center; }
:deep(.node-inp) { width: 96%; }
:deep(.node-del) { border: none; background: none; color: #f56c6c; cursor: pointer; }
:deep(.node-add) { margin-top: 4px; }
</style>
