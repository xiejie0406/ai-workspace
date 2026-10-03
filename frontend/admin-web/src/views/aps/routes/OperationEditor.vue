<template>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <el-form label-width="110px" :disabled="readOnly || saving" @submit.prevent="submit">
    <el-row :gutter="16"><el-col :span="12"><el-form-item label="工序编码" required><el-input v-model="form.code" /></el-form-item></el-col><el-col :span="12"><el-form-item label="工序名称" required><el-input v-model="form.name" /></el-form-item></el-col></el-row>
    <el-form-item label="加工模式"><el-select v-model="form.mode"><el-option v-for="(label,key) in modes" :key="key" :label="label" :value="key" /></el-select></el-form-item>
    <el-form-item label="中断与质量"><el-checkbox v-model="form.interruptible">允许受控中断</el-checkbox><el-checkbox v-model="form.qualityGateRequired">产出需要质量放行</el-checkbox></el-form-item>
    <el-form-item v-if="form.mode==='BATCH'" label="装载容量" required><el-input-number v-model="form.batchCapacity" :min="0.000001" /><el-input v-model="form.batchUomCode" placeholder="容量单位" style="width:140px" /></el-form-item>
    <el-card v-for="(phase,index) in form.phases" :key="index" class="phase-card">
      <template #header><strong>第{{ index+1 }}阶段</strong><el-button v-if="!readOnly" link @click="form.phases.splice(index,1)">移除阶段</el-button></template>
      <el-row :gutter="16"><el-col :span="12"><el-form-item label="阶段名称" required><el-input v-model="phase.name" /></el-form-item></el-col><el-col :span="12"><el-form-item label="阶段类型"><el-select v-model="phase.phaseType"><el-option v-for="(label,key) in phases" :key="key" :label="label" :value="key" /></el-select></el-form-item></el-col></el-row>
      <el-form-item label="时间模型"><el-select v-model="phase.durationModel"><el-option label="固定时间" value="FIXED"/><el-option label="每件时间" value="PER_UNIT"/><el-option label="固定加每件" value="FIXED_PLUS_UNIT"/></el-select></el-form-item>
      <el-form-item v-if="phase.durationModel!=='PER_UNIT'" label="固定秒数"><el-input-number v-model="phase.fixedSeconds" :min="0" :precision="0" /></el-form-item>
      <el-form-item v-if="phase.durationModel!=='FIXED'" label="每件秒数"><el-input-number v-model="phase.secondsPerUnit" :min="0" /></el-form-item>
      <el-form-item label="资源占用"><el-select v-model="phase.resourceHoldPolicy"><el-option label="仅本阶段" value="PHASE_ONLY"/><el-option label="直到下一阶段" value="UNTIL_NEXT_PHASE"/><el-option label="整个作业" value="WHOLE_JOB"/></el-select></el-form-item>
      <el-form-item v-if="form.interruptible && phase.phaseType==='RUN'" label="中断参数"><el-input-number v-model="phase.maxSegments" :min="2" placeholder="最大分段"/><el-input-number v-model="phase.minSegmentSeconds" :min="1" placeholder="最短分段秒数"/><el-input-number v-model="phase.resumeSetupSeconds" :min="0" placeholder="恢复准备秒数"/><el-text>首版各分段使用相同资源。</el-text></el-form-item>
      <el-card v-for="(req,r) in phase.requirements" :key="r" shadow="never" class="requirement">
        <el-row :gutter="12"><el-col :span="8"><el-form-item label="角色"><el-select v-model="req.resourceType" @change="req.fixedResourceId=undefined"><el-option v-for="(label,key) in resourceTypes" :key="key" :label="label" :value="key" /></el-select></el-form-item></el-col><el-col :span="8"><el-form-item label="席位"><el-input-number v-model="req.seatCount" :min="1" :precision="0" /></el-form-item></el-col><el-col :span="8"><el-checkbox v-model="req.optional">可选要求</el-checkbox><el-button v-if="!readOnly" link @click="phase.requirements.splice(r,1)">移除</el-button></el-col></el-row>
        <el-form-item label="工作中心"><el-select v-model="req.workCenterId" filterable clearable @change="req.fixedResourceId=undefined"><el-option v-for="c in centers" :key="c.id" :label="`${c.code} · ${c.name}`" :value="c.id" /></el-select></el-form-item>
        <el-form-item label="固定资源"><el-select v-model="req.fixedResourceId" clearable filterable placeholder="留空由排程选择"><el-option v-for="resource in eligible(req)" :key="resource.id" :label="`${resource.code} · ${resource.name}`" :value="resource.id" /></el-select></el-form-item>
        <el-form-item label="技能与等级"><el-input v-model="req.requiredSkillCode" placeholder="可选技能编码" style="width:200px"/><el-input-number v-model="req.minimumSkillLevel" :min="1" :max="10" /></el-form-item>
        <el-checkbox v-if="form.interruptible && phase.phaseType==='RUN'" v-model="req.holdOnPause">暂停期间保留此席位资源</el-checkbox>
      </el-card>
      <el-button v-if="!readOnly" @click="phase.requirements.push(newRequirement())">添加人员/设备要求</el-button>
      <el-text v-if="phase.phaseType==='WAIT'" type="info">无资源等待可不添加要求；其他阶段必须配置必要资源。</el-text>
    </el-card>
    <el-button v-if="!readOnly" @click="form.phases.push(newPhase())">添加阶段</el-button>
    <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" maxlength="500" /></el-form-item>
  </el-form>
  <div class="footer"><el-button :disabled="saving" @click="$emit('cancel')">关闭</el-button><el-button v-if="!readOnly" v-hasPermi="[form.id ? 'aps:routing:edit' : 'aps:routing:add']" type="primary" :loading="saving" @click="submit">保存草稿</el-button></div>
</template>
<script setup lang="ts">
import { reactive, ref } from 'vue'
import { saveOperation } from '@/api/aps/manufacturing'
import type { ApsOperation, ApsPhase, ApsRequirement } from '@/types/aps/manufacturing'
import type { ApsWorkCenter, ApsResource } from '@/types/aps/resource'
import { operationError } from '@/components/aps/shared/site-time'
const props=defineProps<{ value?:ApsOperation; centers:ApsWorkCenter[]; resources:Pick<ApsResource,'id'|'name'|'code'|'type'|'status'|'workCenterId'>[]; readOnly?:boolean }>()
const emit=defineEmits<{ saved:[ApsOperation]; cancel:[] }>()
const modes={MANUAL:'人工',MAN_MACHINE:'人机协同',AUTO:'自动',BATCH:'批量加工',WAIT:'等待',TRANSPORT:'转运'}
const phases={SETUP:'准备',RUN:'加工',UNLOAD:'卸料',WAIT:'等待',TRANSPORT:'转运'}
const resourceTypes={PERSON:'人员',MACHINE:'设备',WORKSTATION:'工位',TOOL:'工具'}
const newRequirement=():ApsRequirement=>({requirementNo:1,resourceType:'PERSON',seatCount:1,minimumSkillLevel:1,optional:false})
const newPhase=():ApsPhase=>({phaseNo:1,phaseType:'RUN',name:'加工',durationModel:'PER_UNIT',fixedSeconds:0,secondsPerUnit:10,resourceHoldPolicy:'PHASE_ONLY',requirements:[newRequirement()]})
const form=reactive<Partial<ApsOperation> & {phases:ApsPhase[]}>(props.value?JSON.parse(JSON.stringify(props.value)):{code:'',name:'',mode:'MAN_MACHINE',interruptible:false,qualityGateRequired:false,status:'DRAFT',rowVersion:0,phases:[newPhase()]})
const saving=ref(false),error=ref('')
const eligible=(req:ApsRequirement)=>props.resources.filter(r=>r.status==='ACTIVE'&&r.type===req.resourceType&&(!req.workCenterId||r.workCenterId===req.workCenterId))
async function submit(){if(saving.value||props.readOnly)return;saving.value=true;error.value='';try{
  if(!form.code?.trim()||!form.name?.trim())throw new Error('请填写工序编码和名称')
  if(!form.phases.length)throw new Error('至少需要一个阶段')
  const value={...form,phases:form.phases.map((phase,index)=>{
    if(!phase.name?.trim())throw new Error(`第${index+1}阶段需要名称`)
    const fixed=phase.durationModel==='PER_UNIT'?0:phase.fixedSeconds,unit=phase.durationModel==='FIXED'?0:phase.secondsPerUnit
    if(fixed<0||unit<0||fixed+unit<=0)throw new Error(`第${index+1}阶段需要有效标准时间`)
    if(phase.phaseType!=='WAIT'&&!phase.requirements.some(r=>!r.optional))throw new Error(`第${index+1}阶段缺少必要资源要求`)
    const segmented=!!form.interruptible && phase.phaseType==='RUN'
    if(segmented && (!(phase.maxSegments!>=2)||!(phase.minSegmentSeconds!>0)))throw new Error(`第${index+1}阶段需要最大分段及最短分段秒数`)
    return {...phase,phaseNo:index+1,fixedSeconds:fixed,secondsPerUnit:unit,maxSegments:segmented?phase.maxSegments:1,minSegmentSeconds:segmented?phase.minSegmentSeconds:0,resumeSetupSeconds:segmented?(phase.resumeSetupSeconds||0):0,segmentResourcePolicy:'SAME_RESOURCES' as const,requirements:phase.requirements.map((req,r)=>({...req,requirementNo:r+1,holdOnPause:segmented&&!!req.holdOnPause,workCenterId:req.workCenterId||undefined,fixedResourceId:req.fixedResourceId||undefined,requiredSkillCode:req.requiredSkillCode?.trim()||undefined,minimumSkillLevel:req.requiredSkillCode?.trim()?req.minimumSkillLevel:undefined}))}
  })}
  emit('saved',await saveOperation(value))
}catch(e){error.value=operationError(e)}finally{saving.value=false}}
</script>
<style scoped>.phase-card{margin-bottom:16px}.requirement{margin:12px 0}.footer{display:flex;justify-content:flex-end;gap:12px;margin-top:20px}</style>
