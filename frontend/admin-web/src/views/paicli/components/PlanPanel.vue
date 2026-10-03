<template>
  <section class="pc-panel" aria-label="计划与团队审查">
    <h2>计划与团队审查</h2>
    <el-empty v-if="!plan" description="尚未生成计划。选择计划或团队模式后发送任务。" />
    <template v-else>
      <p>版本 {{ plan.version }} · {{ modeName(plan.mode) }} · {{ stateName(plan.status) }}</p>
      <p class="pc-muted">重规划 {{ plan.replanCount }} / {{ plan.maxReplans }}；模型审查结果须结合实际测试与人工批准。</p>
      <el-alert v-for="message in errors" :key="message" type="error" :title="message" :closable="false" />
      <el-alert v-if="plan.approvedVersion && plan.approvedVersion !== plan.version" title="计划已修订，旧版本批准失效。请重新审阅当前版本。" type="warning" :closable="false" />
      <ol class="steps"><li v-for="step in plan.steps" :key="step.id"><div class="pc-card-title"><strong>{{ step.id }} · {{ step.description }}</strong><el-tag>{{ stateName(step.status) }}</el-tag></div><p class="pc-muted">依赖：{{ step.dependencies.join('、') || '无' }} · {{ step.role || 'Worker' }} · 尝试 {{ step.attempts }}</p><p v-if="step.reviewStatus">审查：{{ stateName(step.reviewStatus) }}</p><el-alert v-if="step.error" :title="safeText(step.error)" type="error" :closable="false" /><el-collapse v-if="step.result"><el-collapse-item title="已保留的任务产物"><pre class="pc-pre">{{ safeText(step.result) }}</pre></el-collapse-item></el-collapse></li></ol>
      <label class="pc-label" for="pc-plan-feedback">补充要求</label><el-input id="pc-plan-feedback" v-model="feedback" type="textarea" :rows="3" :disabled="locked || busy" placeholder="修订将生成新版本；已完成产物保留。" />
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <div class="pc-actions"><el-button v-hasPermi="['paicli:session:write']" :disabled="!feedback.trim() || locked" :loading="busy" @click="revise">修订计划</el-button><el-button v-hasPermi="['paicli:session:write']" type="primary" :disabled="locked || !plan.valid || errors.length > 0 || plan.status === 'COMPLETED'" :loading="busy" @click="execute">确认当前版本并执行</el-button><el-button v-if="active" v-hasPermi="['paicli:task:cancel']" type="danger" @click="$emit('cancel')">取消执行</el-button></div>
    </template>
  </section>
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { checkPermi } from '@/utils/permission'
import type { PcPlan } from '@/types/paicli'
import { executePlan, revisePlan } from '@/api/paicli/sessions'
import { modeName, operationKeys, planErrors, safeText, stateName } from '../model'
import { useOperations } from '../useOperations'
const props = defineProps<{ plan?: PcPlan; active: boolean; readonly?:boolean }>(), emit = defineEmits<{ refresh: []; cancel: [] }>()
const feedback = ref(''), { busy, error, run } = useOperations(), keys = operationKeys()
const errors = computed(() => props.plan ? planErrors(props.plan) : [])
// 正在等待计划审阅的活跃任务必须允许批准/修订，不能与正在执行步骤混同。
const locked = computed(() => props.readonly || props.active && !['DRAFT','WAITING_APPROVAL','AWAITING_REVIEW'].includes(props.plan?.status ?? ''))
async function revise() { const plan = props.plan; if (!plan || locked.value || !feedback.value.trim() || !checkPermi(['paicli:session:write'])) return; const intent = { action: 'revise', id: plan.id, version: plan.version, feedback: feedback.value }; await run(async () => { await revisePlan(plan.id, plan.version, feedback.value, keys.get(intent)); keys.done(intent); feedback.value = ''; emit('refresh') }) }
async function execute() { const plan = props.plan; if (!plan || locked.value || errors.value.length || !plan.valid || plan.status==='COMPLETED' || !checkPermi(['paicli:session:write'])) return; const intent = { action: 'execute', id: plan.id, version: plan.version }; await run(async () => { await executePlan(plan.id, plan.version, keys.get(intent)); keys.done(intent); ElMessage.success('已提交当前计划版本，执行状态以中心回执为准。'); emit('refresh') }) }
</script>
<style scoped>.steps{padding-left:20px}.steps li{padding:10px 0;border-bottom:1px solid var(--el-border-color-lighter)}</style>

