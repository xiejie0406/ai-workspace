import { defineStore } from 'pinia'
import type { WorkspaceBootstrapSnapshot, WorkspaceSnapshot } from '../../../shared/generated/operator-contracts'
import type { WorkspaceRequestContext } from '../../../shared/contracts/desktop-api'
import { useSessionStore } from './session'
import { useTasksStore } from './tasks'
import { useRunnersStore } from './runners'
import { useCapabilitiesStore } from './capabilities'

export const useWorkspaceStore = defineStore('workspace', {
  state: () => ({
    items: [] as WorkspaceSnapshot[],
    selectedId: null as string | null,
    bootstrapSnapshot: null as WorkspaceBootstrapSnapshot | null,
    loading: false,
    error: null as string | null
  }),
  getters: {
    selected: (state) => state.items.find((item) => item.workspaceId === state.selectedId) ?? null
  },
  actions: {
    applyResolved(workspace: WorkspaceSnapshot, bootstrap: WorkspaceBootstrapSnapshot): void {
      this.resetWorkspaceData()
      this.items = [workspace]
      this.selectedId = workspace.workspaceId
      this.bootstrapSnapshot = bootstrap
      this.error = null
      useTasksStore().replace(bootstrap.tasks.items)
      useRunnersStore().replace(bootstrap.runners)
      useCapabilitiesStore().replace(bootstrap.capabilities)
    },
    async loadList(): Promise<void> {
      this.loading = true
      this.error = null
      try {
        this.items = [...(await window.adenDesktop.workspace.list()).value.items]
      } catch (error) {
        this.error = safeMessage(error)
        throw error
      } finally {
        this.loading = false
      }
    },
    async select(workspaceId: string): Promise<void> {
      this.resetWorkspaceData()
      const session = useSessionStore()
      const response = await window.adenDesktop.workspace.select(workspaceId)
      session.updateContext(response.value)
      this.selectedId = workspaceId
      await this.refreshBootstrap()
    },
    async refreshBootstrap(): Promise<void> {
      const context = this.context()
      this.loading = true
      this.error = null
      try {
        const response = await window.adenDesktop.workspace.bootstrap(context)
        if (!sameContext(response.context, context)) throw new Error('已丢弃过期 Workspace bootstrap')
        const wrapped = response.value as { data: WorkspaceBootstrapSnapshot }
        this.bootstrapSnapshot = wrapped.data
        useTasksStore().replace(wrapped.data.tasks.items)
        useRunnersStore().replace(wrapped.data.runners)
        useCapabilitiesStore().replace(wrapped.data.capabilities)
      } catch (error) {
        this.error = safeMessage(error)
        throw error
      } finally {
        this.loading = false
      }
    },
    context(): WorkspaceRequestContext {
      const context = useSessionStore().context
      if (!context.authenticated || !context.workspaceId || context.workspaceId !== this.selectedId) {
        throw new Error('尚未选择有效 Workspace')
      }
      return {
        workspaceId: context.workspaceId,
        sessionEpoch: context.sessionEpoch,
        workspaceEpoch: context.workspaceEpoch
      }
    },
    resetWorkspaceData(): void {
      this.selectedId = null
      this.bootstrapSnapshot = null
      useTasksStore().replace([])
      useRunnersStore().replace([])
      useCapabilitiesStore().replace([])
    }
  }
})

function sameContext(left: { sessionEpoch: number; workspaceEpoch: number; workspaceId: string | null }, right: WorkspaceRequestContext): boolean {
  return left.sessionEpoch === right.sessionEpoch && left.workspaceEpoch === right.workspaceEpoch && left.workspaceId === right.workspaceId
}
function safeMessage(error: unknown): string { return error instanceof Error ? error.message : 'Workspace 操作失败' }
