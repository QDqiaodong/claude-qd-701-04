/**
 * 模块八展示层单位工具。
 *
 * 注意：这里只做【已经由后端冻结的统一单位结果】的展示换算，绝不参与任何重量合计、
 * 力矩或重心/包线计算——那些事实一律以后端返回值为准。页面“原始单位 / 统一单位”
 * 两种模式读到的是同一份后端事实：
 *   - 统一模式：直接显示后端的 kg/mm 值；
 *   - 原始模式：直接显示后端保存的 *_original 原始读数（不经过任何换算），
 *     对于由统一值派生的派生列（如总重/重心臂），才用精确常数做只读换算展示。
 */
export const LB_PER_KG = 2.2046226218487758
export const MM_PER_IN = 25.4

export function kgToLbDisplay(kg) {
  if (kg === null || kg === undefined || kg === '') return ''
  return round3(Number(kg) * LB_PER_KG)
}

export function mmToInDisplay(mm) {
  if (mm === null || mm === undefined || mm === '') return ''
  return round3(Number(mm) / MM_PER_IN)
}

/** kg·mm → lb·in（仅力矩展示换算） */
export function kgmmToLbin(m) {
  if (m === null || m === undefined || m === '') return ''
  return round3((Number(m) * LB_PER_KG) / MM_PER_IN)
}

export function round3(n) {
  return Math.round(n * 1000) / 1000
}

/** 重量：原始模式优先用原始读数，派生列才换算 */
export function weightText(originalValue, originalUnit, kgValue, mode) {
  if (mode === 'original') {
    if (originalValue !== null && originalValue !== undefined && originalValue !== '') {
      return `${originalValue} ${originalUnit || ''}`.trim()
    }
    if (kgValue === null || kgValue === undefined) return '—'
    return `${kgToLbDisplay(kgValue)} LB`
  }
  return kgValue === null || kgValue === undefined ? '—' : `${kgValue} KG`
}

/** 臂长：原始模式优先用原始值，派生列（cg/边界）才换算 */
export function armText(originalValue, originalUnit, mmValue, mode) {
  if (mode === 'original') {
    if (originalValue !== null && originalValue !== undefined && originalValue !== '') {
      return `${originalValue} ${originalUnit || ''}`.trim()
    }
    if (mmValue === null || mmValue === undefined) return '—'
    return `${mmToInDisplay(mmValue)} IN`
  }
  return mmValue === null || mmValue === undefined ? '—' : `${mmValue} MM`
}

export function totalWeightText(kgValue, unitSystem, mode) {
  if (kgValue === null || kgValue === undefined) return '—'
  if (mode === 'original' && unitSystem === 'IMPERIAL') return `${kgToLbDisplay(kgValue)} LB`
  return `${kgValue} KG`
}

export function cgArmText(mmValue, unitSystem, mode) {
  if (mmValue === null || mmValue === undefined) return '—'
  if (mode === 'original' && unitSystem === 'IMPERIAL') return `${mmToInDisplay(mmValue)} IN`
  return `${mmValue} MM`
}

export function momentText(m, unitSystem, mode) {
  if (m === null || m === undefined) return '—'
  if (mode === 'original' && unitSystem === 'IMPERIAL') return `${kgmmToLbin(m)} LB·IN`
  return `${m} KG·MM`
}
