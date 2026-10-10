import type { PcToolCall } from '@/types/paicli'
export interface PcCheckEvidence { id: string; name: string; status: string; exitCode?: number; output: unknown; artifactId?: string }
export interface PcDiffEvidence { id: string; name: string; diff?: string; artifactId?: string; diffArtifact?: {artifactId:string;sha256:string;size:number}; unavailable?:string }
/** 只读取中心工具裁决。模型正文和待批准预览不能充当执行、测试或文件差异。 */
export function executionEvidence(tools: PcToolCall[]) {
  const checks: PcCheckEvidence[] = [], diffs: PcDiffEvidence[] = []
  for (const tool of tools) {
    if (!tool.status || (tool.result === undefined && !tool.error)) continue
    let result: unknown = tool.result
    if (typeof result === 'string') { try { result = JSON.parse(result) } catch { /* 原 CLI 纯文本回执原样显示 */ } }
    const record = result && typeof result === 'object' && !Array.isArray(result) ? result as Record<string, unknown> : undefined
    if (['execute_command','run_command'].includes(tool.toolName)) {
      // 有命令回执也只证明该命令结果，不能自动推断业务检查全部通过。
      const exitCode = typeof record?.exitCode === 'number' ? record.exitCode : undefined
      checks.push({id:tool.id,name:tool.toolName,status:tool.status,exitCode,output:tool.error ?? tool.result,artifactId:tool.artifactId})
    }
    if (['COMPLETED','SUCCEEDED','SUCCESS','CONSUMED'].includes(tool.status) && record) {
      const artifact=record.diffArtifact && typeof record.diffArtifact === 'object' ? record.diffArtifact as Record<string,unknown> : undefined
      const diffArtifact=artifact && typeof artifact.artifactId === 'string' && /^\d+$/.test(artifact.artifactId) && typeof artifact.sha256 === 'string' && /^[a-f0-9]{64}$/i.test(artifact.sha256) && typeof artifact.size === 'number' && Number.isSafeInteger(artifact.size) && artifact.size >= 0 ? {artifactId:artifact.artifactId,sha256:artifact.sha256,size:artifact.size} : undefined
      const diff=typeof record.diff === 'string' && record.diff.length ? record.diff : undefined, unavailable=typeof record.diffUnavailable === 'string' ? record.diffUnavailable : undefined
      if(diff || diffArtifact || unavailable)diffs.push({id:tool.id,name:typeof record.path === 'string' ? record.path : tool.toolName,diff,diffArtifact,unavailable})
    }
  }
  return {checks,diffs}
}
