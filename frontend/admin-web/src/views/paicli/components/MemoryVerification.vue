<template><div><el-tag>{{stateName(memory.status)}}</el-tag><p class="pc-muted">版本 {{memory.version}}</p><el-alert v-if="stale" title="可能已过时：超过 30 天未核实，使用前请重新核对来源。" type="warning" :closable="false" /><p v-else-if="memory.status==='VERIFIED' && !knownTime" class="pc-muted">核实时间未上报，使用前请核对来源。</p></div></template>
<script setup lang="ts">
import {computed,onBeforeUnmount,onMounted,ref} from 'vue'
import type {PcMemory} from '@/types/paicli'
import {stateName} from '../model'
import {isMemoryStale,memoryVerificationTime} from '../shared/memoryFreshness'
const props=defineProps<{memory:PcMemory;now?:number}>(),clock=ref(Date.now())
const stale=computed(()=>isMemoryStale(props.memory,props.now ?? clock.value)),knownTime=computed(()=>{const at=memoryVerificationTime(props.memory);return !!at&&Number.isFinite(Date.parse(at))})
let timer:ReturnType<typeof setInterval>|undefined
onMounted(()=>{timer=setInterval(()=>{clock.value=Date.now()},60000)});onBeforeUnmount(()=>clearInterval(timer))
</script>
