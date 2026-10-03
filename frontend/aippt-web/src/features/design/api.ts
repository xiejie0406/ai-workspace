import { ApiError, request, requestBinary } from '@/api/client'
import { waitTask, type Task } from '@/api/task'
import { tokenStore } from '@/features/auth/token'
import { filenameFromDisposition, saveBlob } from '@/lib/download'
import { layouts, themes } from '@/render/design'
import type { SampleDeck, SampleExport, SampleExportCommand, SampleQualityReport } from './types'

// 与原渲染器读取同一目录；不复制或手工维护第二个版本号。
const manifests = import.meta.glob<{ catalogVersion: string }>(
  '../../../../../contracts/aippt/catalog-manifest.json',
  { eager: true, import: 'default' },
)
export const sampleCatalogVersion = Object.values(manifests)[0]?.catalogVersion

function active(signal: AbortSignal | undefined, token: string | null) {
  if (signal?.aborted || tokenStore.get() !== token) {
    throw new DOMException('操作已取消或登录会话已变化', 'AbortError')
  }
}

export async function fetchSampleDeck(themeId: string, signal?: AbortSignal): Promise<SampleDeck> {
  const deck = await request<SampleDeck>(`/design/sample-deck?theme_id=${encodeURIComponent(themeId)}`, { signal })
  if (!sampleCatalogVersion || deck.catalog_version !== sampleCatalogVersion) {
    throw new ApiError(409, '样例目录版本与当前页面不一致，请刷新页面后重试')
  }
  if (deck.theme_id !== themeId || !themes.has(themeId) || !Array.isArray(deck.slides) || deck.slides.length === 0 || deck.slides.some((slide) => !slide || !layouts.has(slide.layout_id))) {
    throw new ApiError(502, '样例返回了不匹配的主题或页面')
  }
  return deck
}

export async function checkSampleDeck(themeId: string, signal?: AbortSignal): Promise<SampleQualityReport> {
  const token = tokenStore.get()
  const path = `/design/sample-deck/issues?theme_id=${encodeURIComponent(themeId)}`
  let response = await request<(SampleQualityReport | Task) & { catalog_version: string }>(path, { signal })
  if (response.catalog_version !== sampleCatalogVersion) throw new ApiError(409, '样例质量目录版本与当前页面不一致，请刷新页面后重试')
  if ('jobId' in response || 'job_id' in response) {
    await waitTask(response as Task, signal)
    active(signal, token)
    // 以中心当前缓存为准，不能把已完成的旧任务输出当成当前版本报告。
    response = await request<(SampleQualityReport | Task) & { catalog_version: string }>(path, { signal })
  }
  active(signal, token)
  if (response.catalog_version !== sampleCatalogVersion) throw new ApiError(409, '样例质量目录版本已变化，请刷新页面后重试')
  if (!('can_export' in response) || typeof response.can_export !== 'boolean' || !Array.isArray(response.issues)) {
    throw new ApiError(502, '样例质量检查尚未返回有效报告，请重新检查')
  }
  return response
}

/** 请求/重放现有中心导出任务；只有正式制品就绪才通过鉴权接口下载。 */
export async function downloadSampleDeck(command: SampleExportCommand, signal?: AbortSignal): Promise<void> {
  const token = tokenStore.get()
  active(signal, token)
  const accepted = command.accepted ?? await request<SampleExport>('/design/sample-deck/exports', {
    method: 'POST', body: command.body, idempotencyKey: command.key, signal,
  })
  active(signal, token)
  if (typeof accepted.id !== 'string' || !accepted.id) throw new ApiError(502, '样例导出缺少制品编号')
  command.accepted = accepted
  let taskError: unknown = null
  try { await waitTask(accepted, signal) } catch (error) { taskError = error }
  active(signal, token)
  const result = await request<SampleExport>(`/exports/${encodeURIComponent(accepted.id)}`, { signal })
  active(signal, token)
  if (result.status === 'failed' || result.status === 'cancelled') {
    // 明确终态可重新发起；失联仍保留原命令及已经领取的 job。
    command.terminal = true
    if (result.verify?.passed === false) throw new ApiError(422, { ...result.verify, message: '样例 PPTX 回读验证未通过，未返回文件' })
    throw taskError ?? new ApiError(500, result.error ?? '样例导出任务失败')
  }
  if (taskError) throw taskError
  if (result.status !== 'ready') throw new ApiError(502, '样例 PPTX 尚未就绪')
  const response = await requestBinary(`/exports/${encodeURIComponent(accepted.id)}/download`, { signal })
  const blob = await response.blob()
  active(signal, token)
  saveBlob(blob, filenameFromDisposition(response.headers.get('content-disposition'), '演示文稿样例.pptx'))
}
