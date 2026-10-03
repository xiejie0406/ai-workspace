import type {PcRestorePreview} from '@/types/paicli'
/** 真实预览必须完整；文件名列表不能伪装成当前哈希/差异证明。 */
export function requireRestoreProof(value:unknown,id:string):PcRestorePreview{
  if(!value||typeof value!=='object')throw new Error('中心尚未上报完整恢复预览。')
  const proof=value as PcRestorePreview
  const hash=(value:unknown)=>typeof value==='string'&&/^[a-f0-9]{64}$/i.test(value)
  if(proof.snapshotId!==id||typeof proof.commitId!=='string'||!proof.commitId||!Number.isInteger(proof.version)||proof.version<1||!hash(proof.currentFingerprint)||!Array.isArray(proof.files)||!Array.isArray(proof.conflicts)||typeof proof.canRestore!=='boolean'||proof.files.some(file=>!file||typeof file.path!=='string'||!['ADD','MODIFY','DELETE','UNCHANGED'].includes(file.status)||(file.status==='ADD'?file.currentHash!==null||!hash(file.targetHash):file.status==='DELETE'?!hash(file.currentHash)||file.targetHash!==null:!hash(file.currentHash)||!hash(file.targetHash))||(file.diff!==undefined&&typeof file.diff!=='string')))throw new Error('恢复预览缺少真实当前指纹、文件哈希或冲突判断，禁止提交恢复。')
  return proof
}
