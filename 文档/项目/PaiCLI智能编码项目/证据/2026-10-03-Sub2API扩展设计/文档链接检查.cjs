// 只读核对本次文档及导航的本地引用，不请求外部链接。
const fs=require('fs'),path=require('path');
const root=path.resolve(__dirname,'../../../../../');
const feature='文档/若依框架/功能/FEAT-RUOYI-001-平台与AI基础设施';
const project='文档/项目/PaiCLI智能编码项目';
const files=[
 '文档/README.md','文档/若依框架/README.md',`${feature}/README.md`,
 `${feature}/Sub2API统一文本模型接入设计.md`,`${feature}/统一文本通道线框图.html`,`${feature}/统一文本通道界面图.svg`,
 `${feature}/证据/2026-10-03-Sub2API文本模型设计/调查与文档检查记录.md`,
 `${project}/完整迁移设计.md`,`${project}/Sub2API扩展实现文档.md`,`${project}/图稿/Sub2API供应商扩展设计图.html`,
 `${project}/证据/2026-10-03-Sub2API扩展设计/文档与图稿检查记录.md`
];
const errors=[];let checked=0;
for(const file of files){
 const abs=path.join(root,file),content=fs.readFileSync(abs,'utf8');
 const regex=/\.md$/.test(file)?/!?\[[^\]]*\]\(([^\n)]+)\)/g:/(?:href|src)\s*=\s*["']([^"']+)["']/gi;
 for(const match of content.matchAll(regex)){
  let target=match[1].trim().replace(/^<|>$/g,'').split('#')[0].split('?')[0];
  if(!target||/^(?:https?:|mailto:|data:|javascript:|file:|\/|[A-Za-z]:[/\\])/.test(target))continue;
  try{target=decodeURIComponent(target);checked++;if(!fs.existsSync(path.resolve(path.dirname(abs),target)))errors.push({file,target});}
  catch(error){errors.push({file,message:String(error)});}
 }
}
const report={date:'2026-10-04',scope:'本次文档及导航的本地文件引用，不验证外部网页或业务行为',files:files.length,checked,errors};
fs.writeFileSync(path.join(__dirname,'文档链接检查.json'),JSON.stringify(report,null,2));
console.log(JSON.stringify(report));if(errors.length)process.exitCode=1;
