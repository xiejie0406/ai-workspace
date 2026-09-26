import { fashionRequest } from './client'
import type { ImportDetail } from './types'

export function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url; link.download = filename; document.body.appendChild(link); link.click(); link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
export async function downloadTemplate(kind: 'product' | 'price' | 'stock') {
  const segment = {product:'products', price:'prices', stock:'stocks'}[kind]
  const blob = await fashionRequest<Blob>({path:`imports/${segment}/template`, responseType:'blob'})
  if (blob.type.includes('json')) {
    const value = JSON.parse(await blob.text())
    throw new Error(value.msg || '模板下载失败')
  }
  saveBlob(blob, `fashion-${kind}-${kind==='product'?'v2':'v1'}.xlsx`)
}
export function exportImportErrors(details: ImportDetail[]) {
  // 公式前缀转为文字，防止原始输入在 Excel 中执行。
  const cell=(v: unknown) => { const text=String(v??''); return '"'+(/^[=+@\-\t\r\n]/.test(text)?"'":'')+text.replace(/"/g,'""')+'"' }
  const rows=[['文件行','SKU','字段','错误','原值摘要'], ...details.flatMap(row=>row.errors.map(error=>[row.sourceRowNo,row.businessKey,error.field,error.message,error.valueSummary]))]
  saveBlob(new Blob(['\ufeff'+rows.map(row=>row.map(cell).join(',')).join('\r\n')],{type:'text/csv;charset=utf-8'}),'导入错误明细.csv')
}
