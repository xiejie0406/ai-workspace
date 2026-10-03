import type { PcModel } from '@/types/paicli'
export function modelTemplateFields(template?:PcModel) {
  return template ? {template:template.modelId,contextWindow:template.contextWindow,supportsImages:template.supportsImages,supportsThinking:template.supportsThinking,supportsTools:template.supportsTools,capabilityKnown:template.capabilityKnown,maxOutputTokens:template.maxOutputTokens,thinking:template.thinking,reasoningEffort:template.reasoningEffort,reasoningHistory:template.reasoningHistory,dsml:template.dsml} : {template:undefined,contextWindow:128000,supportsImages:false,supportsThinking:false,supportsTools:false,capabilityKnown:false,maxOutputTokens:undefined,thinking:null,reasoningEffort:null,reasoningHistory:null,dsml:null}
}
