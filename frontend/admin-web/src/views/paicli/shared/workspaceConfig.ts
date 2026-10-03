import type { PcWorkspace, PcSearchConfig, PcEmbeddingConfig } from '@/types/paicli'

/** 保留中心工作区版本和业务字段；客户端只做可解释的输入检查，中心仍裁决用途与权限。 */
export function workspaceKnowledgePayload(workspace:PcWorkspace,search?:PcSearchConfig,embedding?:PcEmbeddingConfig):Partial<PcWorkspace> {
  function reference(value?:string){const ref=value?.trim();if(ref&&!ref.startsWith(`ai.paicli.${workspace.id}.`))throw new Error('密钥必须引用当前工作区 ai.paicli 用途，不能输入密钥正文');return ref||undefined}
  if(embedding&&(!embedding.model.trim()||!embedding.baseUrl.trim()))throw new Error('向量模型 ID 与服务地址不能为空')
  const optional=(value?:string)=>value?.trim() || undefined
  const searchConfig=search?{provider:search.provider,baseUrl:optional(search.baseUrl),model:optional(search.model),searchEngine:optional(search.searchEngine),secretRef:reference(search.secretRef)}:null
  const embeddingConfig=embedding?{...embedding,model:embedding.model.trim(),baseUrl:embedding.baseUrl.trim(),secretRef:reference(embedding.secretRef)}:null
  return {...workspace,searchConfig,embeddingConfig}
}
