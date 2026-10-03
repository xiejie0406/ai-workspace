/** 只解析中心产物引用，外部URL和base64正文不作为图片源。 */
export function imageArtifactIds(value:unknown):string[] {
  const ids=new Set<string>();let nodes=0
  function visit(item:unknown,depth:number){if(++nodes>512||depth>12)return;if(typeof item==='string'&&depth===0){try{visit(JSON.parse(item),depth+1)}catch{ /* 普通文本保留原展示 */ }return};if(Array.isArray(item)){for(const child of item)visit(child,depth+1);return};if(!item||typeof item!=='object')return;const record=item as Record<string,unknown>;if(record.type==='image_artifact'&&typeof record.artifactId==='string'&&/^\d+$/.test(record.artifactId))ids.add(record.artifactId);for(const child of Object.values(record))visit(child,depth+1)}
  visit(value,0);return [...ids]
}
