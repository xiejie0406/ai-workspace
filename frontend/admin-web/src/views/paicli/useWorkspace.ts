import { ref } from 'vue'
import { useRoute } from 'vue-router'
import { listWorkspaces } from '@/api/paicli/workspaces'
import type { PcWorkspace } from '@/types/paicli'
import { errorText } from './model'
export function useWorkspace() {
  const route = useRoute(), workspaces = ref<PcWorkspace[]>([]), workspaceId = ref(''), workspaceError = ref('')
  async function loadWorkspaces() { try { workspaces.value = (await listWorkspaces({ pageNum:1, pageSize:100 })).rows; if (!workspaceId.value) { const requested = typeof route.query.workspaceId === 'string' ? route.query.workspaceId : ''; workspaceId.value = workspaces.value.some(item => item.id === requested) ? requested : workspaces.value[0]?.id ?? '' } } catch (cause) { workspaceError.value = errorText(cause) } }
  return { workspaces, workspaceId, workspaceError, loadWorkspaces }
}
