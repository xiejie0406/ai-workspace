<template>
  <el-card class="ps-section" shadow="never">
    <template #header><div class="ps-toolbar"><strong>管理用量汇总</strong><el-radio-group :model-value="days" @update:model-value="$emit('change',Number($event))"><el-radio-button :value="7">近 7 天</el-radio-button><el-radio-button :value="30">近 30 天</el-radio-button></el-radio-group><el-button :loading="loading" @click="$emit('change',days)">刷新汇总</el-button></div></template>
    <el-alert v-if="error" :title="error" type="error" :closable="false"/>
    <template v-if="overview">
      <p class="ps-muted">今日 {{overview.today.day}}，每日额度按已用与预占量预警；余额预警阈值 {{number(overview.lowBalanceThreshold)}} Token。</p>
      <div class="ps-grid"><el-card v-for="item in todayCards" :key="item.label" shadow="never"><span>{{item.label}}</span><strong class="ps-card-value">{{number(item.value)}}</strong><small v-if="item.requests!==undefined">请求 {{number(item.requests)}}</small></el-card></div>
      <h3>近 {{overview.days}} 天用量趋势</h3>
      <div ref="chartElement" class="trend-chart" role="img" :aria-label="`近${overview.days}天Token用量和请求次数趋势`"/>
      <el-collapse><el-collapse-item title="查看趋势明细"><el-table :data="overview.trends"><el-table-column prop="day" label="日期"/><el-table-column prop="chatRequestCount" label="聊天请求"/><el-table-column prop="llmUsedTokens" label="对话 Token"/><el-table-column prop="llmRequestCount" label="对话模型请求"/><el-table-column prop="embeddingUsedTokens" label="向量 Token"/><el-table-column prop="embeddingRequestCount" label="向量模型请求"/></el-table></el-collapse-item></el-collapse>
      <h3>额度预警 · {{overview.alerts.length}} 人次</h3>
      <el-table :data="overview.alerts"><el-table-column prop="username" label="平台账号" min-width="140"/><el-table-column label="用途" width="100"><template #default="{row}">{{scopeLabel(row.scope)}}</template></el-table-column><el-table-column label="等级" width="100"><template #default="{row}"><el-tag :type="row.level==='critical'?'danger':'warning'">{{row.level==='critical'?'高风险':'预警'}}</el-tag></template></el-table-column><el-table-column prop="message" label="预警原因" min-width="180"/><el-table-column label="用量模式" width="110"><template #default="{row}">{{row.mode==='DAILY'?'每日限额':'余额扣减'}}</template></el-table-column><el-table-column prop="usedTokens" label="今日已用"/><el-table-column prop="reservedTokens" label="预占"/><el-table-column label="每日上限"><template #default="{row}">{{row.limitTokens==null?'不适用':number(row.limitTokens)}}</template></el-table-column><el-table-column prop="remainingTokens" label="剩余"/><el-table-column prop="debt" label="欠额"/><el-table-column label="占用比例"><template #default="{row}">{{row.usageRatio==null?'不适用':(row.usageRatio*100).toFixed(1)+'%'}}</template></el-table-column></el-table>
      <el-empty v-if="!overview.alerts.length" description="当前汇总没有额度预警" :image-size="50"/>
      <section v-for="ranking in rankings" :key="ranking.label"><h3>{{ranking.label}}</h3><el-table :data="ranking.rows"><el-table-column type="index" label="排名" width="70"/><el-table-column prop="username" label="平台账号" min-width="140"/><el-table-column prop="usedTokens" label="今日用量"/><el-table-column prop="requestCount" label="请求次数"/><el-table-column prop="reservedTokens" label="预占"/><el-table-column label="每日上限"><template #default="{row}">{{row.limitTokens==null?'不适用':number(row.limitTokens)}}</template></el-table-column><el-table-column prop="remainingTokens" label="剩余"/></el-table></section>
    </template>
    <el-empty v-else-if="!loading&&!error" description="尚未读取管理汇总"/>
  </el-card>
</template>
<script setup lang="ts">
import {computed,nextTick,onBeforeUnmount,ref,watch} from 'vue'
import * as echarts from 'echarts'
import type {UsageOverview} from '@/types/paismart'
const props=defineProps<{overview?:UsageOverview;days:number;loading:boolean;error:string}>();defineEmits<{change:[number]}>()
const number=(value:number|undefined|null)=>value==null?'未返回':value.toLocaleString(),scopeLabel=(scope:string)=>scope.toUpperCase()==='LLM'?'对话':'向量',chartElement=ref<HTMLDivElement>()
let chart:echarts.ECharts|undefined
const todayCards=computed(()=>{const today=props.overview?.today;return today?[{label:'今日聊天请求',value:today.chatRequestCount},{label:'今日对话 Token',value:today.llmUsedTokens,requests:today.llmRequestCount},{label:'今日向量 Token',value:today.embeddingUsedTokens,requests:today.embeddingRequestCount},{label:'高风险预警',value:props.overview!.alerts.filter(a=>a.level==='critical').length}]:[]})
const rankings=computed(()=>[{label:'今日对话用量排行',rows:props.overview?.llmRankings??[]},{label:'今日向量用量排行',rows:props.overview?.embeddingRankings??[]}])
function resize(){chart?.resize()}
watch(()=>props.overview,async overview=>{await nextTick();if(!overview||!chartElement.value)return;chart??=echarts.init(chartElement.value);chart.setOption({tooltip:{trigger:'axis'},legend:{data:['对话 Token','向量 Token','聊天请求','对话模型请求','向量模型请求']},grid:{left:75,right:60,bottom:40,top:65},xAxis:{type:'category',data:overview.trends.map(t=>t.day)},yAxis:[{type:'value',name:'Token'},{type:'value',name:'请求'}],series:[{name:'对话 Token',type:'line',data:overview.trends.map(t=>t.llmUsedTokens)},{name:'向量 Token',type:'line',data:overview.trends.map(t=>t.embeddingUsedTokens)},{name:'聊天请求',type:'line',yAxisIndex:1,data:overview.trends.map(t=>t.chatRequestCount)},{name:'对话模型请求',type:'line',yAxisIndex:1,data:overview.trends.map(t=>t.llmRequestCount)},{name:'向量模型请求',type:'line',yAxisIndex:1,data:overview.trends.map(t=>t.embeddingRequestCount)}]},true)}, {immediate:true})
window.addEventListener('resize',resize)
onBeforeUnmount(()=>{window.removeEventListener('resize',resize);chart?.dispose()})
</script>
<style scoped>.trend-chart{height:360px;width:100%}</style>
