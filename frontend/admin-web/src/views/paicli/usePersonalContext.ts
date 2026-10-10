import { computed, ref } from 'vue'
import { getPersonalContext, preparePersonalContext, listPersonalDirectories } from '@/api/paicli/personal'
import type { PcDirectoryContext, PcPersonalContext } from '@/types/paicli'
import { errorText, operationKeys } from './model'

/** 身份和可用性由若依中心返回，前端不创建工作区、成员或设备记录。 */
export function usePersonalContext() {
  const context = ref<PcPersonalContext>(), directories = ref<PcDirectoryContext[]>([]), loading = ref(false), error = ref('')
  const directoryContextId = ref(''), keys = operationKeys()
  const directory = computed(() => directories.value.find(item => item.id === directoryContextId.value) ?? (context.value?.directoryContext?.id === directoryContextId.value ? context.value.directoryContext : undefined))
  async function load() {
    loading.value = true; error.value = ''; const initialLoad = !context.value
    try {
      context.value = (await getPersonalContext()).data
      directories.value = (await listPersonalDirectories()).data
      if (initialLoad && !directoryContextId.value) directoryContextId.value = context.value.directoryContext?.id ?? ''
    } catch (cause) { error.value = personalError(cause) } finally { loading.value = false }
  }
  async function prepare() {
    loading.value = true; error.value = ''
    const intent = { action: 'personal-bootstrap' }
    try { context.value = (await preparePersonalContext(keys.get(intent))).data; keys.done(intent); directories.value = (await listPersonalDirectories()).data; if (!directoryContextId.value) directoryContextId.value = context.value.directoryContext?.id ?? ''; return context.value }
    catch (cause) { error.value = personalError(cause); return undefined } finally { loading.value = false }
  }
  return { context, directories, directory, directoryContextId, loading, error, load, prepare }
}

function personalError(cause:unknown) { const status=(cause as {response?:{status?:number}})?.response?.status; return status === 404 || status === 405 ? '中心服务正在升级，个人使用能力尚未启用。输入草稿已保留，请完成服务升级后重试。' : errorText(cause) }
