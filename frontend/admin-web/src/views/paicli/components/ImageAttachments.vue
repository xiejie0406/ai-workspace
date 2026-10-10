<template>
  <section aria-label="图片附件">
    <input ref="fileInput" type="file" multiple accept="image/png,image/jpeg" class="file-input" aria-label="选择 PNG 或 JPEG 图片" @change="selectFiles" />
    <el-button v-hasPermi="['paicli:artifact:write']" :disabled="disabled || uploading || !directoryContextId" @click="fileInput?.click()">上传图片</el-button><span class="pc-muted"> PNG / JPEG，来源文件不超过 50MB、4000 万像素；服务端最终校验并压缩。</span>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="modelValue.length && !supportsImages" title="当前模型不支持图片。附件已保留，请切换图片模型或移除附件后发送。" type="warning" :closable="false" />
    <ul class="attachments"><li v-for="item in modelValue" :key="item.id"><img v-if="thumbnailUrls[item.id]" :src="thumbnailUrls[item.id]" :alt="`已添加图片：${item.fileName}`" class="attachment-thumbnail" /><span>{{ item.fileName }} · {{ item.width }} × {{ item.height }} · {{ Math.ceil(item.size / 1024) }}KB</span><el-button v-hasPermi="['paicli:artifact:read']" link :disabled="disabled || uploading" @click="preview(item)">预览</el-button><el-button link type="danger" :disabled="disabled || uploading" @click="remove(item.id)">移除</el-button></li></ul>
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
const props = defineProps<{ directoryContextId: string; modelValue: PcAttachment[]; supportsImages: boolean; disabled?: boolean; maxCount?: number }>(), emit = defineEmits<{ 'update:modelValue': [images: PcAttachment[]] }>()
const fileInput = ref<HTMLInputElement>(), uploading = ref(false), error = ref(''), previewOpen = ref(false), previewUrl = ref(''), previewName = ref(''), keys = operationKeys()
let previewGeneration = 0
const thumbnailUrls=ref<Record<string,string>>({}),thumbnailLoads=new Set<string>()
let thumbnailGeneration=0
function clearThumbnails(){thumbnailGeneration++;Object.values(thumbnailUrls.value).forEach(url=>URL.revokeObjectURL(url));thumbnailUrls.value={};thumbnailLoads.clear()}
async function loadThumbnail(item:PcAttachment){if(!checkPermi(['paicli:artifact:read']) || thumbnailUrls.value[item.id] || thumbnailLoads.has(item.id))return;const generation=thumbnailGeneration,directory=props.directoryContextId;thumbnailLoads.add(item.id);try{const blob=await downloadArtifact(item.id);if(generation!==thumbnailGeneration || directory!==props.directoryContextId || !props.modelValue.some(value=>value.id===item.id) || !checkPermi(['paicli:artifact:read']))return;if(!['image/png','image/jpeg'].includes(blob.type))throw new Error('图片缩略图格式无法读取');thumbnailUrls.value={...thumbnailUrls.value,[item.id]:URL.createObjectURL(blob)}}catch(cause){if(generation===thumbnailGeneration)error.value=errorText(cause)}finally{if(generation===thumbnailGeneration)thumbnailLoads.delete(item.id)}}
watch(()=>props.modelValue,images=>{for(const id of Object.keys(thumbnailUrls.value))if(!images.some(item=>item.id===id)){URL.revokeObjectURL(thumbnailUrls.value[id]);delete thumbnailUrls.value[id]};images.forEach(item=>void loadThumbnail(item))},{immediate:true})
async function addFiles(files: File[]) {
  if (props.disabled || uploading.value || !props.directoryContextId || !checkPermi(['paicli:artifact:write'])) return
  const workspace = props.directoryContextId; uploading.value = true; error.value = ''
  try {
    const maximum = props.maxCount ?? 8
    if (props.modelValue.length + files.length > maximum) throw new Error(`每轮最多 ${maximum} 张图片，请分批提交。`)
    for (const file of files) {
      if (workspace !== props.directoryContextId || props.disabled) return
      const prepared = await prepareImageFile(file)
      const intent = { workspace, name: file.name, size: file.size, modified: file.lastModified }, result = await uploadAttachment(workspace, prepared, keys.get(intent))
      keys.done(intent)
      if (workspace === props.directoryContextId) emit('update:modelValue', [...props.modelValue, result.data])
    }
  } catch (cause) { error.value = errorText(cause) } finally { uploading.value = false }
}
async function selectFiles(event: Event) { const input = event.target as HTMLInputElement; await addFiles(Array.from(input.files ?? [])); input.value = '' }
async function paste(event: ClipboardEvent) { if(props.disabled || !checkPermi(['paicli:artifact:write']))return;const files = Array.from(event.clipboardData?.files ?? []).filter(file => file.type.startsWith('image/')); if (files.length) { event.preventDefault(); await addFiles(files) } }
function remove(id: string) { emit('update:modelValue', props.modelValue.filter(image => image.id !== id)) }
function clearPreview() { if (previewUrl.value) URL.revokeObjectURL(previewUrl.value); previewUrl.value = '' }
async function preview(item: PcAttachment) { if(!checkPermi(['paicli:artifact:read']))return;const generation = ++previewGeneration, workspace = props.directoryContextId; try { const blob = await downloadArtifact(item.id); if (generation !== previewGeneration || workspace !== props.directoryContextId) return; if (!['image/png','image/jpeg'].includes(blob.type)) throw new Error('附件内容类型无法预览。'); clearPreview(); previewUrl.value = URL.createObjectURL(blob); previewName.value = item.fileName; previewOpen.value = true } catch (cause) { if (generation === previewGeneration) error.value = errorText(cause) } }
watch(() => props.directoryContextId, () => { previewGeneration++; previewOpen.value = false; clearPreview(); clearThumbnails(); error.value = '';props.modelValue.forEach(item=>void loadThumbnail(item)) })
onBeforeUnmount(() => { previewGeneration++; clearPreview(); clearThumbnails() })
defineExpose({ paste, addFiles, pick: () => fileInput.value?.click(), uploading, uploadError: error, clearUploadError: () => { error.value = '' } })
</script>
<style scoped>.file-input{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0,0,0,0)}.attachments{padding-left:20px}.attachments li{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.attachment-thumbnail{width:72px;height:64px;object-fit:contain;border:1px solid var(--el-border-color);border-radius:4px}</style>
