<template>
  <div v-if="quota" class="quota-summary">
    <div>{{ quota.mode === 'DAILY' ? '每日限额' : '余额扣减' }} · 剩余 {{ number(quota.remainingTokens) }} Token</div>
    <div>已用 {{ number(quota.usedTokens) }} · 预占 {{ number(quota.reservedTokens) }}</div>
    <div v-if="quota.mode === 'DAILY'">每日上限 {{ number(quota.limitTokens) }}</div>
    <div>今日请求 {{ number(quota.requestCount) }} · 累计请求 {{ number(quota.totalRequestCount) }}</div>
  </div>
  <span v-else class="ps-muted">未返回额度快照</span>
</template>
<script setup lang="ts">
import type { QuotaScope } from '@/types/paismart'
defineProps<{quota?:QuotaScope}>()
const number=(value:number|null|undefined)=>value==null?'未返回':value.toLocaleString()
</script>
<style scoped>.quota-summary{line-height:1.9;font-variant-numeric:tabular-nums}</style>
