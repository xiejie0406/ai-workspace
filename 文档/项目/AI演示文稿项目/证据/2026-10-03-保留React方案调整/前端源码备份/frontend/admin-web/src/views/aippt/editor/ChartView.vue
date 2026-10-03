<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import * as echarts from 'echarts'
import type { ChartBlock, Theme } from '../render/types'
const props=defineProps<{block:ChartBlock;theme:Theme}>();const element=ref<HTMLDivElement|null>(null);let chart:echarts.ECharts|undefined,observer:ResizeObserver|undefined
function render(){if(!chart)return;const b=props.block,p=props.theme.palette;const isPie=b.chart_type==='pie',horizontal=b.chart_type==='bar';chart.setOption({animation:false,color:p.chart_series,backgroundColor:'transparent',textStyle:{color:p.ink,fontFamily:props.theme.fonts.body.web},tooltip:{trigger:isPie?'item':'axis'},legend:{bottom:0,textStyle:{color:p.ink_soft}},grid:{top:24,left:48,right:20,bottom:44,containLabel:true},xAxis:isPie?undefined:{type:horizontal?'value':'category',data:horizontal?undefined:b.categories,axisLabel:{color:p.ink_muted}},yAxis:isPie?undefined:{type:horizontal?'category':'value',data:horizontal?b.categories:undefined,name:b.unit||'',axisLabel:{color:p.ink_muted}},series:isPie?[{name:b.series[0]?.name,type:'pie',radius:'68%',data:b.categories.map((name,i)=>({name,value:b.series[0]?.values[i]||0})),label:{color:p.ink}}]:b.series.map(s=>({name:s.name,type:b.chart_type==='line'?'line':'bar',data:s.values,label:{show:false}}))},true)}
onMounted(()=>{if(element.value){chart=echarts.init(element.value);observer=new ResizeObserver(()=>chart?.resize());observer.observe(element.value);render()}});watch(()=>[props.block,props.theme],render,{deep:true});onBeforeUnmount(()=>{observer?.disconnect();chart?.dispose()})
</script>
<template><div ref="element" class="chart" role="img" :aria-label="`${block.chart_type}图表：${block.categories.map((c,i)=>c+' '+block.series.map(s=>s.name+':'+s.values[i]).join('；')).join('，')}`"/></template>
<style scoped>.chart{width:100%;height:100%;min-height:20px}</style>
