import type { PlanStep, Turn } from '@/api/agent'
import type { LayerDocument } from '@/api/sessions'

const REGION = new Set(['erase_region', 'replace_region', 'promote_object_to_layer'])
const REMOTE = new Set(['generate_image', 'replace_background', 'expand_canvas', 'upscale_image', 'erase_region', 'replace_region', 'split_layers', 'promote_object_to_layer', 'generate_marketing'])
const PARAM_LABELS: Record<string, string> = {
  layer_id: '目标图层', prompt: '描述', text: '文字', ratio: '画幅', ratios: '投放画幅', count: '候选数量', kind: '营销类型', caption: '文案',
  brightness: '亮度', contrast: '对比度', highlights: '高光', shadows: '阴影', temperature: '色温', tint: '色调', saturation: '饱和度',
  vibrance: '自然饱和度', sharpness: '锐化', clarity: '清晰度', vignette: '晕影', scale: '增强倍数', include_text: '同时拆出文字',
  direction: '方向', opacity: '透明度', visible: '可见', position: '排序', factor: '缩放倍数', scale_x: '水平缩放', scale_y: '垂直缩放',
  rotation: '绝对角度', angle: '旋转角度', x: '水平位置', y: '垂直位置', dx: '水平移动', dy: '垂直移动', rect: '裁剪范围',
  font_size: '字号', fill: '文字颜色', negative_prompt: '反向提示', seed: '随机种子', name: '图层名称', formats: '输出格式',
  asset_ids: '源素材', reference_asset_ids: '参考素材', operations: '批量流水线',
}

/** 供用户确认的说明；仅展示服务端正式计划，不参与工具权限或执行裁决。 */
export function planSummary(step: PlanStep, turn: Turn, document?: LayerDocument) {
  const params = step.params ?? {}
  const layerId = typeof params.layer_id === 'string' ? params.layer_id : null
  const defaultKind = step.tool === 'set_layer_text' ? 'text' : 'image'
  const target = layerId ? document?.layers.find((layer) => layer.id === layerId || layer.name === layerId)?.name || layerId
    : REGION.has(step.tool) && turn.mask_asset_id ? '固定选区下最上层可见图像'
      : [...(document?.layers ?? [])].reverse().find((layer) => layer.kind === defaultKind && layer.visible && layer.opacity > 0)?.name || (defaultKind === 'text' ? '默认文字层' : '默认最上层可见图像')
  let scope = `目标图层：${target}`
  if (REGION.has(step.tool)) scope += turn.mask_asset_id
    ? `；已固定选区 ${turn.mask_width ?? '?'}×${turn.mask_height ?? '?'}，与图层取交集`
    : step.tool === 'promote_object_to_layer' ? '；当前无选区，需要先建立选区' : '；无选区，作用整个目标图层'
  else if (['crop_canvas', 'expand_canvas', 'upscale_image', 'generate_marketing', 'prepare_delivery_sizes', 'split_layers'].includes(step.tool)) scope = '作用范围：当前画布及可见图层'
  else if (step.tool === 'replace_background') scope = '作用范围：当前画布背景，保留主体'
  else if (step.tool === 'generate_image') scope = '作用范围：新图像候选，使用计划中列出的参考素材'
  else if (step.tool === 'batch_process') scope = `作用范围：${Array.isArray(params.asset_ids) ? params.asset_ids.length : '?'} 张源素材，依次执行流水线`
  let output = '结果写入正式画布，可通过历史撤销'
  if (['generate_image', 'generate_marketing', 'prepare_delivery_sizes'].includes(step.tool)) output = '结果进入图片墙，由你选择采用'
  else if (step.tool === 'batch_process') output = '逐项保存处理结果，成功项可打包下载'
  else if (['replace_background', 'expand_canvas', 'upscale_image'].includes(step.tool)) output = '执行时为单图层单候选，结果写回画布；已拆层、多图层或多候选只进图片墙'
  const batchRemote = step.tool === 'batch_process' && Array.isArray(params.operations) && params.operations.some((op) => op && typeof op === 'object' && REMOTE.has(String((op as Record<string, unknown>).tool)))
  const cost = REMOTE.has(step.tool) || batchRemote ? '图像模型：调用已配置供应商，可能产生费用；实际金额由供应商计费' : '本地算法或文档处理，不调用付费图像模型'
  const entries = Object.entries(params).filter(([, value]) => value != null).map(([key, value]) => [PARAM_LABELS[key] || key, typeof value === 'object' ? JSON.stringify(value) : String(value)] as const)
  return { scope, output, cost, entries }
}
