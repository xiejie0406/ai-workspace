import { useHost } from '@/host/HostContext'

export function promptDraft(userId: string | number) {
  const key = `aipicture:prompt-draft:${userId}`
  return {
    save(text: string) {
      try { if (text.trim()) sessionStorage.setItem(key, text); else sessionStorage.removeItem(key) } catch { /* 隐私模式不影响创作 */ }
    },
    read() { try { return sessionStorage.getItem(key) ?? '' } catch { return '' } },
    clear() { try { sessionStorage.removeItem(key) } catch { /* 无可清理的存储 */ } },
  }
}
export const usePromptDraft = () => promptDraft(useHost().identity.userId)
