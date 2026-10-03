import type { BusinessTask } from '@/api/fashion/proposal'

export function taskTypeLabel(task: BusinessTask) {
  if (task.kind === 'try_on') return '模特试衣'
  return ({product_attribute_suggestion:'商品属性建议',product_attributes:'商品属性建议',requirement_analysis:'需求分析',requirement_draft:'需求分析'} as Record<string,string>)[task.taskType] || task.taskType
}
export function taskStatusLabel(value: unknown) {
  if (typeof value !== 'string' || !value) return '未记录'
  return ({queued:'排队中',running:'执行中',unknown:'状态待确认',review:'待复核',adopted:'已采用',rejected:'未采用',failed:'失败',succeeded:'执行成功',cancel_requested:'已请求取消',cancelled:'已取消',expired:'已过期',not_applied:'待确认',applied:'已采用',stale:'已过期'} as Record<string,string>)[value] || value
}
export function taskBillingLabel(value: unknown) {
  if (typeof value !== 'string' || !value) return '费用状态未知'
  return ({unknown:'费用待确认',not_billed:'未计费',billed:'已计费',settled:'已对账',estimated:'估计费用',pending:'待对账'} as Record<string,string>)[value] || value
}
export function taskAdoptionLabel(task: Pick<BusinessTask,'adopted'|'reviewStatus'>) {
  return task.adopted ? '已采用' : task.reviewStatus === 'rejected' ? '未采用' : '待决定'
}
function text(value: unknown, missing = '未记录') {
  return typeof value === 'string' && value !== '' || typeof value === 'number' || typeof value === 'boolean' ? String(value) : missing
}
function object(value: unknown): Record<string,unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string,unknown> : {}
}
/** 详情已返回的 null 也是最新事实，不能回退到列表里较早的费用或失败信息。 */
export function businessTaskDetailRows(task: BusinessTask, data: Record<string,unknown>) {
  const latest = (key: string, fallback: unknown) => Object.prototype.hasOwnProperty.call(data,key) ? data[key] : fallback
  const status = latest('status',task.status)
  const reviewStatus = task.kind === 'ai_run' ? latest('applyStatus',task.reviewStatus) : status
  const adopted = task.kind === 'ai_run' ? reviewStatus === 'applied' : status === 'adopted'
  const currency = text(latest('currency',task.currency),'')
  const cost = (key: 'estimatedCost'|'actualCost') => {
    const value = latest(key,task[key])
    return typeof value === 'number' && Number.isFinite(value) ? text(value) + (currency ? ' '+currency : '') : '未知'
  }
  const fields: [string,string][] = [
    ['任务编号',task.id],['任务类型',taskTypeLabel(task)],['关联对象',task.relatedName],
    ['来源编号',text(task.poolId||task.productId||task.sourceId)],['发起人',text(task.ownerName??task.ownerId)],
    ['执行状态',taskStatusLabel(status)],['复核状态',taskStatusLabel(reviewStatus)],
    ['采用状态',taskAdoptionLabel({adopted,reviewStatus:text(reviewStatus,'')})],
    ['服务 / 模型',text(latest('providerCode',task.providerCode))+' / '+text(latest('model',task.model))],
    ['创建时间',text(latest('createdAt',task.createdAt))],
    ['提交时间',text(latest('submittedAt',latest('startedAt',task.submittedAt)))],
    ['完成时间',text(latest('finishedAt',task.finishedAt),'尚未完成')],
    ['输入版本',text(data.draftVersion??data.inputVersion)],
    ['结果数',text(data.resultCount??(Array.isArray(data.results)?data.results.length:undefined))],
    ['重试次数',text(data.retryCount)],['预计费用',cost('estimatedCost')],['实际费用',cost('actualCost')],
    ['费用状态',taskBillingLabel(latest('billingStatus',task.billingStatus))],
    ['失败原因',text(latest('errorMessage',task.errorMessage),'无失败记录')]
  ]
  if (typeof data.stale === 'boolean') fields.push(['输入是否过期',data.stale?'已变化，请重新核对':'与当前版本一致'])
  if (typeof data.runAttempt === 'number') fields.push(['执行尝试次数',text(data.runAttempt)])
  const review = object(data.review), checks = object(review.checks)
  if (Object.keys(review).length) {
    fields.push(['复核结果',typeof review.adopted === 'boolean' ? review.adopted?'采用':'不采用':'未记录'],
      ['复核图片序号',typeof review.resultNo === 'number'?text(review.resultNo+1):'未记录'],
      ['复核人',text(review.reviewer)],['复核时间',text(review.reviewedAt)],['复核说明',text(review.reason,'未填写')])
    for (const [key,label] of [['style','款式'],['color','颜色'],['completeness','完整性'],['quality','图像质量']]) {
      fields.push(['复核检查：'+label,typeof checks[key] === 'boolean'?checks[key]?'已确认':'未确认':'未记录'])
    }
  }
  return fields.map(([label,value])=>({label,value}))
}
