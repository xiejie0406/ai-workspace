export type GarmentPosition='top'|'bottom'|'dress'|'original'
export interface WorkspaceConfiguration {
  model?:{objectKey:string;sha256:string}
  modelAuthorized:boolean;targetIds:string[];garmentPositions:Record<string,Record<string,GarmentPosition>>
  exportScope:'page'|'all';priceMode:string;imageMode:string;title:string;maxCost?:number
  requestKeys:Record<string,string>
}
export function workspaceKey(userId:string,poolId:string,rowVersion:number){return `fashion:proposal-workspace:${userId}:${poolId}:${rowVersion}`}
export function readWorkspace(key:string):WorkspaceConfiguration|undefined{
  try{const raw=sessionStorage.getItem(key);if(!raw)return;const value=JSON.parse(raw);if(!Array.isArray(value.targetIds)||typeof value.garmentPositions!=='object')return;return value}catch{return undefined}
}
export function saveWorkspace(key:string,value:WorkspaceConfiguration){
  try{sessionStorage.setItem(key,JSON.stringify(value))}catch{/* 存储受限时保留当前组件状态，不能影响已受理任务。 */}
}
export function positionError(items:{key:string;name:string}[],positions:Record<string,GarmentPosition>,supported:string[]){
  for(const item of items){const position=positions[item.key];if(!position)return `请明确“${item.name}”的服装位置`;if(position!=='original'&&!supported.includes(position))return `服务不支持“${item.name}”的服装位置`}
  const included=items.map(item=>positions[item.key]).filter(position=>position!=='original')
  if(!included.length)return '至少选择一件支持试衣的服装，鞋帽等可保留原图'
  if(included.filter(position=>position==='top').length>1||included.filter(position=>position==='bottom').length>1||included.filter(position=>position==='dress').length>1)return '同一试衣任务每个服装位置最多一件'
  if(included.includes('dress')&&(included.includes('top')||included.includes('bottom')))return '连衣裙不能与上装或下装同时提交试衣'
  return ''
}
export async function ensurePptx(blob:Blob){
  const mime=blob.type.split(';')[0].toLowerCase(),head=new Uint8Array(await blob.slice(0,4).arrayBuffer())
  if(['application/vnd.openxmlformats-officedocument.presentationml.presentation','application/zip','application/x-zip-compressed'].includes(mime)&&head[0]===0x50&&head[1]===0x4b&&head[2]===0x03&&head[3]===0x04)return
  if(mime==='application/json'){try{const value=JSON.parse(await blob.text());throw new Error(value.msg||'文件生成失败')}catch(e){if(e instanceof SyntaxError)throw new Error('服务未返回有效PPTX文件');throw e}}
  throw new Error('服务未返回有效PPTX文件，请核对生成结果')
}
