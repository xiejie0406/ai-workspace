<template>
  <el-dialog :model-value="modelValue" title="选择文件位置" width="600px" class="pc-dialog" @update:model-value="$emit('update:modelValue', $event)">
    <p class="pc-muted">目录位于助手实际运行的机器。未选择时使用个人默认目录。已有项目由运行环境提供可用目录，无需填写指纹或配置成员。</p>
    <el-radio-group v-model="selected" class="pc-directories">
      <el-radio v-for="item in directories" :key="item.id" :value="item.id" :disabled="item.state !== 'VERIFIED'"><strong>{{ item.label }}</strong><span>{{ item.path || '尚未准备' }}</span></el-radio>
    </el-radio-group>
    <el-empty v-if="!directories.length" description="个人目录尚未准备，请准备环境后重试。" :image-size="60" />
    <el-collapse><el-collapse-item title="使用已有项目目录"><label class="pc-label" for="pc-directory-path">执行机器上的项目路径</label><el-input id="pc-directory-path" v-model="path" placeholder="例如：个人目录内的项目路径，由执行环境核对" :disabled="active || busy" /><p class="pc-muted">这是执行机器上的路径；浏览器选择本机文件不能授权远端目录。核对成功后才加入可选目录。</p><el-button v-hasPermi="['paicli:session:write']" :loading="busy" :disabled="active || !path.trim()" @click="verify">核对目录</el-button><OperationTask :task-id="taskId" :directory-context-id="currentId" title="核对项目目录" @settled="directorySettled" /><el-alert v-if="error" :title="error" type="error" :closable="false" /></el-collapse-item></el-collapse>
    <p v-if="active" class="pc-muted">本次执行或终端正在使用当前目录。请结束后选择，或新建对话。</p>
    <template #footer><el-button @click="$emit('update:modelValue', false)">返回</el-button><el-button type="primary" :disabled="active || !selected || !directories.some(item => item.id === selected && item.state === 'VERIFIED')" @click="$emit('select', selected); $emit('update:modelValue', false)">使用此目录</el-button></template>
  </el-dialog>
</template>
<script setup lang="ts">
import { ref, watch } from 'vue'
import type { PcDirectoryContext } from '@/types/paicli'
import type {PcTaskDetail} from '@/types/paicli'
import {checkPermi} from '@/utils/permission'
import {requestPersonalDirectory} from '@/api/paicli/personal'
import OperationTask from './OperationTask.vue'
import {useOperations} from '../useOperations'
import {operationKeys} from '../model'
const props = defineProps<{modelValue: boolean; directories: PcDirectoryContext[]; currentId: string; active?: boolean}>()
const emit=defineEmits<{ 'update:modelValue': [value: boolean]; select: [id: string]; refresh: [] }>()
const selected = ref(props.currentId)
const path=ref(''),taskId=ref(''),{busy,error,run}=useOperations(),keys=operationKeys()
async function verify(){if(props.active || !path.value.trim() || !checkPermi(['paicli:session:write']))return;const intent={path:path.value.trim()};await run(async()=>{taskId.value=(await requestPersonalDirectory(intent.path,keys.get(intent))).data.id;keys.done(intent)})}
function directorySettled(task:PcTaskDetail){if(task.status==='COMPLETED')emit('refresh')}
watch(() => props.modelValue, open => { if (open) selected.value = props.currentId })
</script>
<style scoped>.pc-directories{display:flex;flex-direction:column;align-items:stretch;gap:12px}.pc-directories :deep(.el-radio){height:auto;white-space:normal;align-items:flex-start}.pc-directories span{display:block;font-size:12px;color:var(--el-text-color-secondary);margin-top:6px;overflow-wrap:anywhere}</style>
