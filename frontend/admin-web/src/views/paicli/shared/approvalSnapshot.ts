import type {PcApproval} from '@/types/paicli'
import {requireRestoreProof} from './restoreProof'
/** 审阅目标与差异必须来自冻结的正式预览；产物ID不扩大普通下载权限。 */
export function approvalSnapshotProblem(approval:PcApproval){
  if(!['revert_turn','SNAPSHOT_RESTORE'].includes(approval.toolName)&&!approval.snapshotContext)return ''
  const context=approval.snapshotContext
  if(!context||!/^\d+$/.test(context.snapshotMetadataId)||!/^\d+$/.test(context.previewReceiptId)||!context.previewToolCallId)return '缺少本次恢复冻结的正式预览与回执，不能批准。'
  try{const proof=requireRestoreProof({...context.restorePreview,snapshotId:context.snapshotMetadataId,commitId:context.snapshotId,version:context.snapshotVersion},context.snapshotMetadataId);if(proof.currentFingerprint!==context.currentFingerprint||!proof.canRestore||proof.conflicts.length)return '当前冻结预览存在冲突或不能恢复，请重新申请。'}catch{return '冻结预览缺少真实指纹与逐文件证据，不能批准。'}
  const artifact=context.previewArtifact
  if(artifact&&(!/^\d+$/.test(artifact.artifactId)||!/^[a-f0-9]{64}$/i.test(artifact.sha256)||!Number.isSafeInteger(artifact.size)||artifact.size<0||!approval.sessionId))return '完整差异缺少冻结产物摘要、长度或会话绑定，不能批准。'
  return ''
}
