/** 校验实际维护对象清单，服务端仍重新核对对象归属、摘要与版本。 */
export function operationManifest(kind:string,source:string):Record<string,unknown>{
  if(kind==='cleanup-plans')return {}
  if(!['object-migrations','bootstrap-import'].includes(kind))throw new Error('不支持的维护类型')
  let input:unknown;try{input=JSON.parse(source)}catch{throw new Error('请填写有效的 JSON 对象数组')}
  if(!Array.isArray(input)||input.length===0||input.length>1000)throw new Error('清单须包含 1 至 1000 项实际对象')
  const seen=new Set<string>()
  const entries=input.map((raw,index)=>{
    if(!raw||typeof raw!=='object'||Array.isArray(raw))throw new Error(`第 ${index+1} 项必须是对象`)
    const row=raw as Record<string,unknown>,item:Record<string,unknown>={}
    const text=(field:string)=>{const value=row[field];if(typeof value!=='string'||!value.trim())throw new Error(`第 ${index+1} 项缺少 ${field}`);return value.trim()}
    const key=(field:string)=>{const value=text(field);if(!value.startsWith('paismart/')||value.includes('..')||/[\\:]/.test(value))throw new Error(`第 ${index+1} 项 ${field} 超出知识库对象范围`);return value}
    const sha=text('sha256').toLowerCase();if(!/^[a-f0-9]{64}$/.test(sha))throw new Error(`第 ${index+1} 项 SHA-256 摘要无效`)
    if(!Number.isSafeInteger(row.byteSize)||(row.byteSize as number)<=0)throw new Error(`第 ${index+1} 项 byteSize 必须是正整数`)
    item.sha256=sha;item.byteSize=row.byteSize
    if(kind==='object-migrations'){
      item.targetKey=key('targetKey')
      if(row.legacySourceKey!=null){
        item.legacySourceKey=text('legacySourceKey');if(!/^(merged|chunks)\//.test(item.legacySourceKey as string)||(item.legacySourceKey as string).includes('..')||/[\\:]/.test(item.legacySourceKey as string))throw new Error(`第 ${index+1} 项旧源对象键无效`)
        if(row.uploadId!=null){item.uploadId=text('uploadId');if(!Number.isSafeInteger(row.chunkNo)||(row.chunkNo as number)<0)throw new Error(`第 ${index+1} 项 chunkNo 必须是0基整数`);item.chunkNo=row.chunkNo;if(item.targetKey!==`paismart/uploads/${item.uploadId}/${item.chunkNo}`)throw new Error(`第 ${index+1} 项目标必须属于原上传分片`)}
        else {item.id=text('id');if(!(item.targetKey as string).startsWith(`paismart/documents/${item.id}/`))throw new Error(`第 ${index+1} 项目标必须属于原文档`)}
      }else {item.id=text('id');item.sourceKey=key('sourceKey');if(!(item.targetKey as string).startsWith(`paismart/documents/${item.id}/`)||item.sourceKey===item.targetKey)throw new Error(`第 ${index+1} 项搬迁目标必须属于该文档且不同于源对象`)}
    }else{
      item.sourceKey=key('sourceKey')
      if(!(item.sourceKey as string).startsWith('paismart/imports/'))throw new Error(`第 ${index+1} 项导入源必须属于 paismart/imports/`)
      item.filename=text('filename');if(/[\\/\x00]/.test(item.filename as string))throw new Error(`第 ${index+1} 项文件名无效`)
      item.visibility=text('visibility');if(!['PRIVATE','ORGANIZATION','PUBLIC'].includes(item.visibility as string))throw new Error(`第 ${index+1} 项可见范围无效`)
      if(row.spaceId!=null)item.spaceId=text('spaceId')
      if(row.ownerId!=null){if(!Number.isSafeInteger(row.ownerId)||(row.ownerId as number)<=0)throw new Error(`第 ${index+1} 项 ownerId 必须是平台用户整数标识`);item.ownerId=row.ownerId}
      if(row.logicalKey!=null)item.logicalKey=text('logicalKey')
      if(row.repair!=null){if(typeof row.repair!=='boolean')throw new Error(`第 ${index+1} 项 repair 必须是布尔值`);item.repair=row.repair}
    }
    const identity=String(kind==='object-migrations'?(item.uploadId?`${item.uploadId}/${item.chunkNo}`:item.id):item.sourceKey);if(seen.has(identity))throw new Error(`第 ${index+1} 项与前面的对象重复`);seen.add(identity);return item
  })
  return {[kind==='bootstrap-import'?'files':'documents']:entries}
}
