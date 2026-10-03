const SOURCE_LIMIT = 50 * 1024 * 1024, API_LIMIT = 5 * 1024 * 1024, PIXEL_LIMIT = 40_000_000
/** 小于 API 上限的图片保持原尺寸；超限才缩放，PNG 保留透明通道。 */
export async function prepareImageFile(file: File): Promise<File> {
  if (!['image/png','image/jpeg'].includes(file.type)) throw new Error(`${file.name} 不是支持的 PNG / JPEG 图片。`)
  if (file.size > SOURCE_LIMIT) throw new Error(`${file.name} 超出 50MB 来源文件上限。`)
  const bitmap = await createImageBitmap(file)
  try {
    if (bitmap.width * bitmap.height > PIXEL_LIMIT) throw new Error(`${file.name} 超出 4000 万像素限制。`)
    if (file.size <= API_LIMIT) return file
    let scale = Math.min(1,2000 / Math.max(bitmap.width,bitmap.height))
    const canvas = document.createElement('canvas'), context = canvas.getContext('2d')
    if (!context) throw new Error('当前浏览器无法处理图片，请上传不超过 5MB 的已压缩图片。')
    for (let attempt = 0; attempt < 6; attempt++) {
      canvas.width = Math.max(1,Math.round(bitmap.width * scale)); canvas.height = Math.max(1,Math.round(bitmap.height * scale)); context.clearRect(0,0,canvas.width,canvas.height); context.drawImage(bitmap,0,0,canvas.width,canvas.height)
      const blob = await new Promise<Blob>((resolve,reject) => canvas.toBlob(value => value ? resolve(value) : reject(new Error('图片压缩失败，原附件未提交。')),file.type,file.type === 'image/jpeg' ? 0.85 : undefined))
      if (blob.size <= API_LIMIT) return new File([blob],file.name,{ type:file.type,lastModified:file.lastModified })
      scale *= 0.8
    }
    throw new Error('压缩后仍超过 5MB API 上限，请缩小图片再上传。')
  } finally { bitmap.close() }
}
