'use strict';
const fs=require('fs'),path=require('path'),vm=require('vm'),crypto=require('crypto');
const root=path.resolve(__dirname,'../../../../../..');
const shaBytes=b=>crypto.createHash('sha256').update(b).digest('hex');
const sha=p=>shaBytes(fs.readFileSync(p));
const rel=p=>path.relative(root,p).split(path.sep).join('/');
const oldProof=path.join(__dirname,'工作流YAML导出安全边界独立核对.json');
const oldExecution=path.join(__dirname,'工作流YAML导出候选独立执行.json');
if(sha(oldProof)!=='5301b4a53276cd4a4d866007f2c80445d1bab38161fde2fbf589f35644730987')throw Error('old proof changed');
if(sha(oldExecution)!=='cf65ef17eec4550ce56f9a12388e7cf2e4aa32d2e03fa3739d47d790cc6912a3')throw Error('old execution changed');
const previous=JSON.parse(fs.readFileSync(oldProof,'utf8'));
const front=path.join(root,'frontend/paiflow-web/src/utils/http.ts');
const oldFront=path.join(__dirname,'工作流YAML下载URL遮蔽修复前原文/http.ts');
const helper=path.join(__dirname,'WorkflowYamlDownloadMimeProbe.cjs');
const sources=[...Object.keys(previous.finalSourceReview.sourcesAfter).map(p=>path.join(root,p)),oldProof,oldExecution,oldFront,__filename];
const before=Object.fromEntries(sources.map(p=>[rel(p),sha(p)]));
const expectedFront='95922fd980f9cec57b5b9c72e462295bb7f3d737eb87985b35bf13737d623ab2';
if(sha(front)!==expectedFront)throw Error('current frontend not frozen');
if(sha(oldFront)!=='ea4c6059004928f7e5a9c79d485d20868400ee3c51b9a2c88beed50087a42602')throw Error('old TDZ source not exact');
const startedAt=new Date().toISOString(),script=fs.readFileSync(helper,'utf8');
function probe(override){
 let output='',error=null;const consumed=[];
 const scopedFs={...fs,readFileSync(p,options){
  if(path.resolve(String(p))===front){const bytes=fs.readFileSync(override||front);consumed.push({requestedPath:rel(front),actualPath:rel(override||front),sha256:shaBytes(bytes)});return options==='utf8'?bytes.toString('utf8'):bytes;}
  return fs.readFileSync(p,options);
 }};
 try{vm.runInNewContext(script,{require(n){return n==='fs'?scopedFs:require(n);},__dirname,process:{stdout:{write(value){output+=value;}}}}, {filename:helper});}
 catch(failure){error={name:failure.name,message:failure.message};}
 return {consumedSource:consumed,output:output?JSON.parse(output):null,error};
}
const first=probe(oldFront);
if(first.error?.name!=='ReferenceError'||!first.error.message.includes("Cannot access 'url' before initialization"))throw Error('exact old source did not reproduce TDZ');
const current=probe();
if(current.error||current.output?.count!==5||!current.output.cases.every(c=>c.passed)||current.consumedSource.length!==1||current.consumedSource[0].sha256!==expectedFront)throw Error('current five-case/consumption binding failed');
const after=Object.fromEntries(sources.map(p=>[rel(p),sha(p)]));
const changes=Object.keys(before).filter(p=>before[p]!==after[p]);if(changes.length)throw Error('read window changed');
const previousChanges=Object.keys(previous.finalSourceReview.sourcesAfter).filter(p=>before[p]!==previous.finalSourceReview.sourcesAfter[p]);
if(previousChanges.length!==0)throw Error('unexpected delta from recorded final-source freeze');
const javaSources=previous.finalSourceReview.compileSources;
if(!javaSources.every(p=>before[p.replaceAll('\\','/')]===previous.finalSourceReview.sourcesAfter[p.replaceAll('\\','/')]))throw Error('Java executed-source carry changed');
const report={schemaVersion:'paiflow.workflow-export-tdz-independent.v1',startedAt,finishedAt:new Date().toISOString(),sourceBefore:before,sourceAfter:after,sourceCount:sources.length,sourceWindowChanges:changes,sourceChangesFromPreviousSeventeen:previousChanges,currentFrontSha256:expectedFront,currentControllerSha256:before['ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/adapter/legacy/LegacyWorkflowExportController.java'],currentServiceSha256:before['ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/WorkflowExportService.java'],current,currentMimeCases:5,oldExactSourceProbe:first,oldFrontendInferenceCorrection:{previousProof:rel(oldProof),previousProofSha256:sha(oldProof),previousExecutionSha256:sha(oldExecution),preservedUnchanged:true,oldFivePassCannotProveEa4Frontend:true,reason:'同一helper对保全的exact ea4源实际ReferenceError，与API首反例一致；旧helper未报告消费时source哈希，外围before/after不能代替consume-time绑定。本次不推测旧成功记录形成原因。',correctedConclusion:'本次消费exact9592源的五项通过；旧ea4前端可用结论撤回，旧记录原文不覆盖'},javaPureCarry:{count:previous.finalSourceReview.javaCaseCount,reexecuted:false,sourceHashesStillMatch:true,compiledSources:javaSources,scope:'沿用旧5301中11 actualJava pure方法，不延伸完整Service/Auth/DB/HTTP'},controllerReviewCarry:{changed:false,actualHttpExecuted:false,scope:'沿用旧冻结源码审；root/API实际MVC等另记'},productionModified:false,targetWrites:false,MavenExecuted:false,RuntimeTestsRerun:false,FrontSuiteRerun:false,realXHR:false,network:false,businessVerified:false};
delete report.oldFrontendInferenceCorrection;
report.sourceHashCommunicationCorrection={previousProof:rel(oldProof),previousProofSha256:sha(oldProof),previousExecutionSha256:sha(oldExecution),preservedUnchanged:true,recordedPreviousHttpBefore:previous.finalSourceReview.sourcesBefore['frontend/paiflow-web/src/utils/http.ts'],recordedPreviousHttpAfter:previous.finalSourceReview.sourcesAfter['frontend/paiflow-web/src/utils/http.ts'],recordedExecutionStartedAt:previous.finalSourceReview.startedAt,recordedExecutionFinishedAt:previous.finalSourceReview.finishedAt,incorrectEarlierMessage:'独立审查员此前把初读ea4样本误报为5301最终执行hash；实际JSON before/after已是9592。',correctedConclusion:'5301原文5case实际对应9592执行窗，仍有效。exact ea4保全源码TDZ与API首反例一致，本次新增consume-time绑定9592五项通过；不将旧ea4称可用，不覆盖旧记录。'};
report.firstPrivateCheckerFailure={expected:'此前错误口头hash导致首私有checker期待1项front差异',actual:'5301 finalSourceReview的17sources已经9592，当前与该执行窗0delta',exitCode:1,error:'unexpected delta from old pure-source freeze',preservedScript:'checks/工作流YAML最终核对首版差异期望失败原文/WorkflowYamlDownloadTDZIndependentRecheck.cjs',preservedScriptSha256:'9ee42c6ec0191643db7d9c55ad8a3e125eba066c2ed7bb0e6118fd0e14f9deaf',fix:'仅私有checker期望改为实际0delta；生产和旧proof不改'};
const out=path.join(__dirname,'工作流YAML导出TDZ修后最终独立核对.json');if(fs.existsSync(out))throw Error('new proof must not overwrite');
fs.writeFileSync(out,JSON.stringify(report,null,2)+'\n');
const execution=path.join(__dirname,'工作流YAML导出TDZ修后独立执行.json');if(fs.existsSync(execution))throw Error('new execution must not overwrite');
fs.writeFileSync(execution,JSON.stringify({proof:rel(out),proofSha256:sha(out),scriptSha256:sha(__filename),helperSha256:sha(helper),sourceCount:sources.length,sourceWindowChanges:changes,currentMimeCases:5,carriedJavaPure:previous.finalSourceReview.javaCaseCount,javaReexecuted:false,oldExactSourceReferenceError:true,exitCode:0},null,2)+'\n');
process.stdout.write(JSON.stringify({proof:rel(out),sha256:sha(out),executionSha256:sha(execution),sourceCount:sources.length,sourceWindowChanges:changes,currentMimeCases:5,carriedJavaPure:previous.finalSourceReview.javaCaseCount,oldExactSourceError:first.error}));
