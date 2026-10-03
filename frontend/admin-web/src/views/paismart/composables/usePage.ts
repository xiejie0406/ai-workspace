import { onBeforeUnmount, ref, shallowRef } from 'vue'
import type { Page, Query } from '@/types/paismart'
export function errorText(error: unknown): string { return error instanceof Error ? error.message : typeof error === 'string' ? error : '请求未完成，请检查连接或权限后重试' }
export function usePage<T>(loader: (query: Query, signal?: AbortSignal) => Promise<Page<T>>, initial: Query = {}) {
  const rows = shallowRef<T[]>([]), total = ref(0), loading = ref(false), error = ref('')
  const query = ref<Query>({ pageNum: 1, pageSize: 20, ...initial })
  let serial = 0, controller: AbortController | undefined
  async function load(reset = false) {
    if (reset) query.value.pageNum = 1
    const current = ++serial
    controller?.abort(); controller = new AbortController(); loading.value = true; error.value = ''
    try { const result = await loader({ ...query.value }, controller.signal); if (current === serial) { rows.value = result.rows ?? []; total.value = result.total ?? 0 } }
    catch (e) { if (current === serial && !controller.signal.aborted) error.value = errorText(e) }
    finally { if (current === serial) loading.value = false }
  }
  onBeforeUnmount(() => { serial++; controller?.abort() })
  return { rows, total, query, loading, error, load }
}
export function stateLabel(state?: string) { return ({ UPLOADING: '上传中', UPLOADED: '已上传', PENDING: '等待处理', QUEUED: '等待处理', RETRY: '等待重试', RUNNING: '处理中', SUCCEEDED: '已完成', COMPLETE: '已完成', COMPLETED: '已完成', COMPLETED_WITH_ERRORS: '已完成，部分样本失败', FAILED: '失败', READY: '可检索', PARSED: '已解析', INDEXING: '索引中', DELETED: '已删除', CANCELED: '已取消', CANCELLED: '已取消', STOPPED: '已停止', ACTIVE: '使用中', INACTIVE: '未启用', PAID: '已支付', CLOSED: '已关闭', UNPAID: '待支付', CREATED: '已创建', RESOLVED: '已处置' } as Record<string, string>)[state ?? ''] ?? state ?? '未知' }
export const terminal = (state: string) => ['SUCCEEDED', 'COMPLETE', 'COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELED', 'CANCELLED', 'STOPPED', 'DONE', 'INTERRUPTED'].includes(state.toUpperCase())
