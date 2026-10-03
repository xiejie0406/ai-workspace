import { create } from 'zustand'
import { fetchCurrentUser, type UserPublic } from '@/features/auth/api'
import { platformLogin, tokenStore } from '@/features/auth/token'
import { platformRequest } from '@/api/client'
interface AuthState {
  user: UserPublic | null; permissions: string[]; roles: string[]; restoring: boolean
  restore: () => Promise<void>; logout: () => void
}
export const useAuthStore = create<AuthState>((set) => ({
  user: null, permissions: [], roles: [], restoring: true,
  restore: async () => {
    const token = tokenStore.get()
    if (!token) { set({ user: null, permissions: [], roles: [], restoring: false }); return }
    try {
      const response = await fetchCurrentUser()
      if (tokenStore.get() !== token) return
      set({ user: { id: String(response.user.userId), email: response.user.email || response.user.userName, name: response.user.nickName || response.user.userName }, permissions: response.permissions, roles: response.roles, restoring: false })
    } catch { if (tokenStore.get() === token || tokenStore.get() === null) set({ user: null, permissions: [], roles: [], restoring: false }) }
  },
  logout: () => { const token = tokenStore.get(); void platformRequest('/logout', { method: 'POST' }).catch(() => {}).finally(() => { const current = tokenStore.get(); if (current !== token && current !== null) return; tokenStore.clear(); set({ user: null, permissions: [], roles: [], restoring: false }); platformLogin() }) },
}))
export function can(permission: string) { const { permissions } = useAuthStore.getState(); return permissions.includes('*:*:*') || permissions.includes(permission) }
export function usePermission(permission: string) { return useAuthStore((state) => state.permissions.includes('*:*:*') || state.permissions.includes(permission)) }
