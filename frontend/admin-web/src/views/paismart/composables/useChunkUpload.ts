import { ref, shallowRef } from 'vue'
import { createUpload, mergeUpload, putChunk, uploadStatus } from '@/api/paismart/upload'
import { newKey } from '@/api/paismart/client'
import type { Document, Id, Upload } from '@/types/paismart'
import { errorText } from './usePage'
export async function sha256(blob: Blob): Promise<string> {
  if (!crypto.subtle) throw new Error('当前浏览器未提供安全摘要能力，请使用 HTTPS 或本机受信页面')
  return [...new Uint8Array(await crypto.subtle.digest('SHA-256', await blob.arrayBuffer()))].map(v => v.toString(16).padStart(2, '0')).join('')
}
export function missingChunks(total: number, completed: number[]) { const existing = new Set(completed); return Array.from({ length: total }, (_, i) => i).filter(i => !existing.has(i)) }
export function useChunkUpload() {
  const manifest = shallowRef<Upload>(), busy = ref(false), progress = ref(0), phase = ref(''), error = ref(''), result = shallowRef<Document>()
  let controller: AbortController | undefined, uploadKey = newKey(), mergeKey = newKey(), lastSignature = ''
  function pause() { controller?.abort(); busy.value = false; phase.value = '已暂停，可选择同一文件继续' }
  function reset(){if(busy.value)return;controller?.abort();manifest.value=undefined;result.value=undefined;progress.value=0;phase.value='';error.value='';lastSignature='';uploadKey=newKey();mergeKey=newKey()}
  async function run(file: File, spaceId: Id, visibility: string, resumeId?: Id) {
    if (busy.value) return
    busy.value = true; error.value = ''; result.value = undefined; controller = new AbortController()
    const signal = controller.signal
    const isCurrent = () => !signal.aborted && controller?.signal === signal
    try {
      if (!file.size) throw new Error('不能上传空文件')
      phase.value = '计算文件摘要'; const hash = await sha256(file); if (!isCurrent()) return
      const signature = `${hash}/${spaceId}/${visibility}`
      if (signature !== lastSignature) { uploadKey = newKey(); mergeKey = newKey(); manifest.value = undefined; lastSignature = signature }
      let current: Upload
      if (resumeId) { current = (await uploadStatus(resumeId)).data; if (!isCurrent()) return; if ((current.sha256 && current.sha256.toLowerCase() !== hash) || (current.byteSize && current.byteSize !== file.size) || (!current.sha256 && current.filename && current.filename !== file.name) || (current.spaceId && String(current.spaceId)!==String(spaceId)) || (current.visibility && current.visibility!==visibility)) throw new Error('所选文件、空间或可见范围与续传记录不一致，请选择原文件及原范围') }
      else { current = (await createUpload({ filename: file.name, byteSize: file.size, sha256: hash, spaceId, visibility }, uploadKey)).data; if (!isCurrent()) return; current = (await uploadStatus(current.uploadId)).data }
      if (!isCurrent()) return
      if(current.state==='MERGED'){manifest.value=current;phase.value='读取已合并的原文档';const document=(await mergeUpload(current.uploadId,mergeKey)).data;if(!isCurrent())return;result.value=document;progress.value=100;phase.value='原上传已合并，已读取原文档';return}
      if (!Number.isInteger(current.chunkSize) || current.chunkSize <= 0) throw new Error('服务端返回无效分片大小')
      manifest.value = current
      const count = Math.ceil(file.size / current.chunkSize), pending = missingChunks(count, current.completedChunks ?? [])
      progress.value = Math.round((count - pending.length) * 100 / count)
      phase.value = '上传文件分片'
      for (const n of pending) {
        if (!isCurrent()) return
        const chunk = file.slice(n * current.chunkSize, Math.min(file.size, (n + 1) * current.chunkSize))
        const chunkHash = await sha256(chunk)
        if (!isCurrent()) return
        await putChunk(current.uploadId, n, chunk, chunkHash, signal)
        if (!isCurrent()) return
        current.completedChunks = [...new Set([...current.completedChunks, n])]; progress.value = Math.round(current.completedChunks.length * 100 / count)
      }
      if (!isCurrent()) return
      phase.value = '校验并合并'; const document = (await mergeUpload(current.uploadId, mergeKey)).data; if (!isCurrent()) return; result.value = document; phase.value = '上传已合并，解析状态以文档列表为准'
    } catch (e) { if (isCurrent()) { error.value = errorText(e); phase.value = '上传未完成，可重试续传' } }
    finally { if (isCurrent()) busy.value = false }
  }
  return { manifest, busy, progress, phase, error, result, run, pause,reset }
}
