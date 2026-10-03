const fs=require('node:fs');
const path=require('node:path');
const ts=require('typescript');
const root=path.resolve(__dirname,'..');
const reference=path.resolve(root,'../../参考项目/PaiFlow-main/console/frontend');
const reportPath=path.resolve(root,'../../文档/项目/PaiFlow工作流项目/附录/原前端类型基线比较.json');
const configPath=path.join(root,'tsconfig.json');
const config=ts.readConfigFile(configPath,ts.sys.readFile);
if(config.error) throw new Error(ts.flattenDiagnosticMessageText(config.error.messageText,'\n'));
const parsed=ts.parseJsonConfigFileContent(config.config,ts.sys,root,{noEmit:true},configPath);
function referenceFile(file){ const relative=path.relative(root,file);return relative.startsWith('..')||relative.includes('node_modules')?null:path.join(reference,relative); }
function collect(baseline){
 const host=ts.createCompilerHost(parsed.options);
 const read=host.readFile;
 if(baseline)host.readFile=file=>{const original=referenceFile(file);return original&&fs.existsSync(original)?fs.readFileSync(original,'utf8'):read(file);};
 const files=baseline?parsed.fileNames.filter(file=>{const original=referenceFile(file);return original&&fs.existsSync(original);}):parsed.fileNames;
 const program=ts.createProgram(files,parsed.options,host);
 return ts.getPreEmitDiagnostics(program).map(d=>({file:d.file?path.relative(root,d.file.fileName).replaceAll('\\','/'):'<configuration>',code:d.code,message:ts.flattenDiagnosticMessageText(d.messageText,'\n')}));
}
const baseline=collect(true), current=collect(false), counts=new Map();
for(const item of baseline){const key=JSON.stringify(item);counts.set(key,(counts.get(key)||0)+1);}
const added=current.filter(item=>{const key=JSON.stringify(item),count=counts.get(key)||0;if(count){counts.set(key,count-1);return false;}return true;});
fs.writeFileSync(reportPath,JSON.stringify({date:'2026-10-03',method:'同一TypeScript版本/配置/依赖，CompilerHost回读所有对应参考源文件；基线根文件排除新增适配文件，诊断按文件/错误号/内容多重集比较，未修改原文件',baselineErrors:baseline.length,currentErrors:current.length,newErrors:added,diagnostics:current},null,2)+'\n');
console.log(JSON.stringify({baselineErrors:baseline.length,currentErrors:current.length,newErrors:added}));
process.exitCode=added.length?1:0;
