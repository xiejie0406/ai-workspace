import { ref } from 'vue'
import { errorText } from './model'
export function useOperations() {
  const busy = ref(false), error = ref('')
  async function run<T>(operation: () => Promise<T>): Promise<T | undefined> {
    if (busy.value) return
    busy.value = true; error.value = ''
    try { return await operation() } catch (cause) { error.value = errorText(cause); return undefined } finally { busy.value = false }
  }
  return { busy, error, run }
}
