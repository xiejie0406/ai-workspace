import type {PcMemory} from '@/types/paicli'
export const MEMORY_STALE_DAYS=30
export function memoryVerificationTime(memory:PcMemory){return memory.verifiedAt || memory.lastVerifiedAt}
export function isMemoryStale(memory:PcMemory,now:number){
  if(memory.status!=='VERIFIED')return false
  const at=memoryVerificationTime(memory),verified=at?Date.parse(at):Number.NaN
  return Number.isFinite(verified)&&verified+MEMORY_STALE_DAYS*24*60*60*1000<now
}
