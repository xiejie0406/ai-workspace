<template>
  <section aria-label="图片附件">
    <input ref="fileInput" type="file" multiple accept="image/png,image/jpeg" class="file-input" aria-label="选择 PNG 或 JPEG 图片" @change="selectFiles" />
    <el-button v-hasPermi="['paicli:artifact:write']" :disabled="disabled || uploading || !workspaceId" @click="fileInput?.click()">上传图片</el-button><span class="pc-muted"> PNG / JPEG，来源文件不超过 50MB、4000 万像素；服务端最终校验并压缩。</span>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="modelValue.length && !supportsImages" title="当前模型不支持图片。附件已保留，请切换图片模型或移除附件后发送。" type="warning" :closable="false" />
    <ul class="attachments"><li v-for="item in modelValue" :key="item.id"><span>{{ item.fileName }} · {{ item.width }} × {{ item.height }} · {{ Math.ceil(item.size / 1024) }}KB</span><el-button v-hasPermi="['paicli:artifact:read']" link :disabled="disabled || uploading" @click="preview(item)">预览</el-button><el-button link type="danger" :disabled="disabled || uploading" @click="remove(item.id)">移除</el-button></li></ul>
    <el-dialog v-model="previewOpen" title="附件预览" width="700px" class="pc-dialog" @closed="clearPreview"><img v-if="previewUrl" :src="previewUrl" :alt="previewName" style="max-width:100%;max-height:65vh;object-fit:contain" /></el-dialog>
    <p v-if="uploading" role="status">正在校验并上传图片…</p>
  </section>
</template>
<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import type { PcAttachment } from '@/types/paicli'
import { downloadArtifact, uploadAttachment } from '@/api/paicli/sessions'
import { checkPermi } from '@/utils/permission'
import { errorText, operationKeys } from '../model'
import { prepareImageFile } from '../image'
const props = defineProps<{ workspaceId: string; modelValue: PcAttachment[]; supportsImages: boolean; disabled?: boolean }>(), emit = defineEmits<{ 'update:modelValue': [images: PcAttachment[]] }>()
const fileInput = ref<HTMLInputElement>(), uploading = ref(false), error = ref(''), previewOpen = ref(false), previewUrl = ref(''), previewName = ref(''), keys = operationKeys()
let previewGeneration = 0
async function addFiles(files: File[]) {
  if (props.disabled || uploading.value || !props.workspaceId || !checkPermi(['paicli:artifact:write'])) return
  const workspace = props.workspaceId; uploading.value = true; error.value = ''
  try {
    if (props.modelValue.length + files.length > 8) throw new Error('每轮最多 8 张图片，请分批提交。')
    for (const file of files) {
      if (workspace !== props.workspaceId || props.disabled) return
      const prepared = await prepareImageFile(file)
      const intent = { workspace, name: file.name, size: file.size, modified: file.lastModified }, result = await uploadAttachment(workspace, prepared, keys.get(intent))
      keys.done(intent)
      if (workspace === props.workspaceId) emit('update:modelValue', [...props.modelValue, result.data])
    }
  } catch (cause) { error.value = errorText(cause) } finally { uploading.value = false }
}
async function selectFiles(event: Event) { const input = event.target as HTMLInputElement; await addFiles(Array.from(input.files ?? [])); input.value = '' }
async function paste(event: ClipboardEvent) { if(props.disabled || !checkPermi(['paicli:artifact:write']))return;const files = Array.from(event.clipboardData?.files ?? []).filter(file => file.type.startsWith('image/')); if (files.length) { event.preventDefault(); await addFiles(files) } }
function remove(id: string) { emit('update:modelValue', props.modelValue.filter(image => image.id !== id)) }
function clearPreview() { if (previewUrl.value) URL.revokeObjectURL(previewUrl.value); previewUrl.value = '' }
async function preview(item: PcAttachment) { if(!checkPermi(['paicli:artifact:read']))return;const generation = ++previewGeneration, workspace = props.workspaceId; try { const blob = await downloadArtifact(item.id); if (generation !== previewGeneration || workspace !== props.workspaceId) return; if (!['image/png','image/jpeg'].includes(blob.type)) throw new Error('附件内容类型无法预览。'); clearPreview(); previewUrl.value = URL.createObjectURL(blob); previewName.value = item.fileName; previewOpen.value = true } catch (cause) { if (generation === previewGeneration) error.value = errorText(cause) } }
watch(() => props.workspaceId, () => { previewGeneration++; previewOpen.value = false; clearPreview(); error.value = '' })
onBeforeUnmount(() => { previewGeneration++; clearPreview() })
defineExpose({ paste, uploading })
</script>
<style scoped>.file-input{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0,0,0,0)}.attachments{padding-left:20px}.attachments li{display:flex;gap:10px;align-items:center;flex-wrap:wrap}</style>
