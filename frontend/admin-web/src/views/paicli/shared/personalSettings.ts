import type {PcSearchConfig,PcEmbeddingConfig} from '@/types/paicli'
export function personalKnowledgePayload(ownerUserId:string,search?:PcSearchConfig,embedding?:PcEmbeddingConfig) {
  function reference(value?:string) {const ref=value?.trim();if(ref && !new RegExp(`^ai\\.paicli\\.user\\.${ownerUserId}\\.[A-Za-z0-9._-]+$`).test(ref))throw new Error('密钥须使用当前用户用途引用，不能输入原文或其他用户的引用');return ref || undefined}
  if(embedding && (!embedding.model.trim() || !embedding.baseUrl.trim()))throw new Error('向量模型 ID 与服务地址不能为空')
  if(embedding?.expectedDimensions!==undefined && (!Number.isInteger(embedding.expectedDimensions) || embedding.expectedDimensions<1 || embedding.expectedDimensions>8192))throw new Error('预期向量维度须为1至8192的整数')
  if(embedding?.model.trim()==='text-embedding-v4' && embedding.expectedDimensions!==1024)throw new Error('text-embedding-v4 的预期向量维度须为1024')
  const optional=(value?:string)=>value?.trim() || undefined
  return {searchConfig:search ? {provider:search.provider,baseUrl:optional(search.baseUrl),model:optional(search.model),searchEngine:optional(search.searchEngine),secretRef:reference(search.secretRef)} : null,embeddingConfig:embedding ? {...embedding,model:embedding.model.trim(),baseUrl:embedding.baseUrl.trim(),secretRef:reference(embedding.secretRef)} : null}
}
