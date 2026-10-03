// 最终证据一致性只读核对，不运行Maven、PythonRuntime、前端或索引生成器。
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../../../../../..'),base='文档/项目/PaiFlow工作流项目/证据/本地源码检查/',checks=base+'checks/';
const read=p=>JSON.parse(fs.readFileSync(path.join(root,p),'utf8')),bytes=p=>fs.readFileSync(path.join(root,p)),sha=p=>crypto.createHash('sha256').update(bytes(p)).digest('hex');
const mismatch=[],notes=[],references=[],fileSet=new Set();
function check(condition,scope,details){if(!condition)mismatch.push({scope,details});}
function reference(p,expected){fileSet.add(p);const actual=fs.existsSync(path.join(root,p))?sha(p):null;if(expected)check(actual===expected,'reference_hash',{path:p,expected,actual});const r={path:p,sha256:actual};references.push(r);return r;}
const paths={gate:checks+'调试显示历史八类定向检查冻结输入.json',inputs:base+'调试显示历史八类定向检查输入.json',result:base+'调试显示历史八类定向检查结果.json',execution:base+'调试显示历史八类定向检查执行.json',ledger:base+'调试显示历史检查用例沿用核对.json',index:base+'调试显示历史接口索引冻结核对.json',mvc:base+'调试显示历史实际MVC响应.json',budget:base+'调试显示历史实际大答案预算.json',consumer:checks+'调试显示历史实际MVC原React独立消费核对.json',sync:checks+'调试显示历史索引局部同步结果.json'};
Object.values(paths).forEach(p=>reference(p));
const gate=read(paths.gate),inputs=read(paths.inputs),result=read(paths.result),execution=read(paths.execution),ledger=read(paths.ledger),index=read(paths.index),mvc=read(paths.mvc),budget=read(paths.budget),consumer=read(paths.consumer),sync=read(paths.sync);
const snapshotPath=base+'调试显示历史接口源码快照.json',snapshot=read(snapshotPath);reference(snapshotPath,index.inventorySha256);
const snapshots=[['java',snapshot.targetSnapshotHashes],['frontend',snapshot.targetFrontendSnapshotHashes],['supporting',snapshot.targetSupportingSnapshotHashes]];
for(const row of gate.inputs)fileSet.add(row.path);
for(const p of Object.keys(inputs.files))fileSet.add(p);
for(const[,map]of snapshots)for(const p of Object.keys(map))fileSet.add(p);
for(const row of gate.sourceGates||[])reference(row.path,row.sha256);
for(const row of index.currentSnapshotCopies)reference(row.path,row.sha256);
for(const row of index.actualArtifacts)reference(row.path,row.sha256);
for(const row of [ledger.prior,ledger.fresh,ledger.impactEvidence,index.carriedTechnicalEvidence.mavenResult,index.carriedTechnicalEvidence.identityLedger,sync.freeze,sync.inventory,sync.audit,sync.index,index.metadataChange.index,index.frontendImport.manifest,index.frontendImport.differenceRecord])reference(row.path,row.sha256);
for(const row of [index.execution.generator.log,index.execution.checker.log])reference(row.path,row.sha256);
reference(base+'调试显示历史八类定向检查.log',execution.logSha256);
reference('contracts/paiflow/check-source-api-inventory.py',index.checkerSha256);
const audit=read(index.currentSnapshotCopies.find(row=>row.path.endsWith('接口覆盖快照.json')).path);
for(const [p,h]of Object.entries(audit.generatorHashes||{}))reference(p,h);
reference(consumer.checker.path,consumer.checker.sha256);
for(const row of Object.values(consumer.sourceArtifacts))if(row&&row.path)reference(row.path,row.sha256);
for(const row of consumer.gates?Object.values(consumer.gates):[])if(row&&row.path)reference(row.path,row.sha256);
for(const p of Object.keys(consumer.sourceHashesAfter||{}))fileSet.add(p);
const prior=read(ledger.prior.path);check(prior.currentTests===554,'prior_identity_count',prior.currentTests);
const suiteFiles=result.suites.map(s=>({path:base+'调试显示历史八类定向检查本次JUnit快照/TEST-'+s.name+'.xml',sha256:s.sha256}));
suiteFiles.forEach(row=>reference(row.path,row.sha256));
fileSet.add(path.relative(root,__filename).replaceAll('\\','/'));
const before=Object.fromEntries([...fileSet].sort().map(p=>[p,fs.existsSync(path.join(root,p))?sha(p):null]));
const gateDifferences=gate.inputs.filter(row=>before[row.path]!==row.sha256).map(row=>({path:row.path,expected:row.sha256,actual:before[row.path]}));
check(gate.inputs.length===12&&gateDifferences.length===0,'final_gate',{inputs:gate.inputs.length,differences:gateDifferences});
const currentMavenDifferences=Object.entries(inputs.files).filter(([p,h])=>before[p]!==h).map(([p,h])=>({path:p,expected:h,actual:before[p]}));
const expectedPostMaven=['contracts/paiflow/generate-source-api-inventory.py','contracts/paiflow/source-api-inventory.json'];
check(JSON.stringify(currentMavenDifferences.map(r=>r.path).sort())===JSON.stringify(expectedPostMaven.sort()),'post_maven_allowed_index_delta',currentMavenDifferences);
const retained=read(index.retainedPreviousSnapshot.preparation.path);
for(const row of currentMavenDifferences){const old=(retained.copies||[]).find(r=>path.basename(r.path)===path.basename(row.path));check(old&&old.sha256===row.expected,'post_maven_before_copy',{path:row.path,old:old||null,expected:row.expected});if(old)reference(old.path,row.expected);}
notes.push('97执行时的227源码窗口0与当前227全相同是不同事实；后续授权索引生成器与机器清单两文件变更单列，不记为Maven执行窗漂移。');
const indexDifferences={};for(const[k,map]of snapshots){indexDifferences[k]=Object.entries(map).filter(([p,h])=>before[p]!==h).map(([p,h])=>({path:p,expected:h,actual:before[p]}));check(indexDifferences[k].length===0,'current_index_snapshot_'+k,indexDifferences[k]);}
check(Object.keys(inputs.files).length===227&&inputs.sourceInputCount===227&&result.sourceInputCount===227,'maven_source_count',inputs.sourceInputCount);
check(result.status==='technical_checks_passed'&&result.exitCode===0&&execution.exitCode===0,'maven_recorded_status',result.status);
check(result.totals.tests===97&&['failures','errors','skipped'].every(k=>result.totals[k]===0),'maven_totals',result.totals);
check(result.suites.length===8&&result.cases.length===97&&result.expectedTests===97&&inputs.expectedTests===97,'maven_counts',{suites:result.suites.length,cases:result.cases.length});
check([result.sourceWindowChanges,execution.sourceWindowChanges,result.missingCases,result.unexpectedCases].every(a=>a.length===0),'maven_recorded_window_and_identity',result.sourceWindowChanges);
const id=r=>Array.isArray(r)?r.join('#'):r.class+'#'+r.name,set=rows=>new Set(rows.map(id)),sorted=s=>[...s].sort(),sameSet=(a,b)=>JSON.stringify(sorted(a))===JSON.stringify(sorted(b));
const fresh=set(result.cases),expected=set(inputs.expectedCases),carry=set(ledger.carriedUnchanged),rerun=set(ledger.rerunExisting),added=set(ledger.newlyExecuted),old=set([...prior.carriedUnchanged,...prior.rerunAffected]),combined=new Set([...carry,...fresh]);
check(fresh.size===97&&sameSet(fresh,expected)&&result.cases.every(r=>r.outcome==='passed'),'fresh_exact_identity',fresh.size);
check(carry.size===480&&rerun.size===74&&added.size===23,'ledger_identity_sizes',{carry:carry.size,rerun:rerun.size,added:added.size});
check([...carry].every(x=>old.has(x)&&!fresh.has(x))&&[...rerun].every(x=>old.has(x)&&fresh.has(x))&&[...added].every(x=>fresh.has(x)&&!old.has(x)),'ledger_identity_origins','480 old-only + 74 prior-and-fresh +23 new');
check(sameSet(new Set([...carry,...rerun]),old)&&sameSet(new Set([...rerun,...added]),fresh)&&combined.size===577&&ledger.currentTests===577,'identity_union',combined.size);
const discovered=[];for(const p of Object.keys(snapshot.targetSupportingSnapshotHashes).filter(p=>p.endsWith('Test.java'))){const text=bytes(p).toString('utf8'),pkg=/^package\s+([^;]+);/m.exec(text)?.[1];if(!pkg)continue;const klass=pkg+'.'+path.basename(p,'.java');for(const m of text.matchAll(/@Test\s+(?:public\s+)?void\s+(\w+)\s*\(/g))discovered.push({class:klass,name:m[1]});}
check(sameSet(set(discovered),combined)&&set(discovered).size===577,'current_declared_test_identity_union',set(discovered).size);
const xmlCases=[],xmlSuites=[];for(let i=0;i<suiteFiles.length;i++){const text=bytes(suiteFiles[i].path).toString('utf8'),parseAttrs=s=>Object.fromEntries([...s.matchAll(/([\w:-]+)="([^"]*)"/g)].map(m=>[m[1],m[2]])),header=parseAttrs(/<testsuite\s+([^>]+)>/s.exec(text)[1]);const summary={name:header.name,...Object.fromEntries(['tests','failures','errors','skipped'].map(k=>[k,Number(header[k])]))};xmlSuites.push(summary);for(const match of text.matchAll(/<testcase\s+([^>]+)>/g)){const attrs=parseAttrs(match[1]);xmlCases.push({class:attrs.classname,name:attrs.name});}check(['name','tests','failures','errors','skipped'].every(k=>summary[k]===result.suites[i][k]),'copied_junit_suite',summary);}
check(xmlCases.length===97&&sameSet(set(xmlCases),fresh),'copied_junit_identity',xmlCases.length);
check(bytes(base+'调试显示历史八类定向检查.log').toString('utf8').includes('BUILD SUCCESS'),'maven_log_record','BUILD SUCCESS');
const counts=index.currentInputCounts;check(counts.java===109&&counts.frontend===562&&counts.supporting===93,'index_counts',counts);
check(snapshot.originalEndpoints.length===389&&snapshot.targetPaiflowEndpoints.length===175&&index.summary.originalEndpointVariants===389&&index.summary.targetPaiflowEndpointVariants===175,'index_route_counts',{original:389,target:175});
check(index.summary.businessRuntimeVerifiedOriginalRoutes===0&&index.businessVerified===false&&index.migrationComplete===false&&index.execution.testsExecutedByThisIndexAction===0,'index_no_business_or_test_claim',index.execution.testsExecutedByThisIndexAction);
check(index.execution.generatorExecutions===1&&index.execution.checkerExecutions===1&&index.execution.generator.exitCode===0&&index.execution.checker.exitCode===0,'recorded_index_executions',index.execution.generatorExecutions);
check(index.readAndGenerationWindow.sameAsMaven227Window===false&&Object.values(index.readAndGenerationWindow.sourceChanges).every(a=>a.length===0),'separate_index_recorded_window',index.readAndGenerationWindow);
const technical=index.carriedTechnicalEvidence;check(technical.currentTestIdentities===577&&technical.carriedUnchanged===480&&technical.freshTests===97&&technical.rerunExisting===74&&technical.newTests===23,'index_technical_counts',technical);
check(index.metadataChange.addedRouteBoundaries.length===3&&index.metadataChange.allStatusesBusinessUnverified===true&&index.deltaFromPreviousSnapshot.frontend.length===0,'index_added_boundary_scope',index.metadataChange.addedRouteBoundaries.length);
const equal=(a,b)=>{try{assert.deepStrictEqual(a,b);return true;}catch{return false;}};
const bodyBytes=Buffer.from(mvc.bodyUTF8Base64,'base64');check(crypto.createHash('sha256').update(bodyBytes).digest('hex')===mvc.bodySha256,'mvc_wire_sha',mvc.bodySha256);const body=JSON.parse(bodyBytes.toString('utf8'));
check(equal(body,mvc.body)&&equal(body,mvc.listResponse),'mvc_wire_body_consistency','wire/body/listResponse');
check(mvc.actualStatus===200&&mvc.actualContentType==='application/json'&&body.code===0&&body.data.length===3,'actual_list_recorded_http',{status:mvc.actualStatus,contentType:mvc.actualContentType,rows:body.data.length});
const fields=['id','uid','workflowId','question','answer','data','sid','questionItem','answerItem','createTime','deleted','type','chatId'].sort();
check(body.data.every(r=>JSON.stringify(Object.keys(r).sort())===JSON.stringify(fields)),'mvc_original_thirteen_fields',fields);
check(body.data.every(r=>r.sid===mvc.runId&&r.workflowId===mvc.workflowId&&r.uid==='ruoyi:'+mvc.actualBinding.ownerUserId),'mvc_recorded_owner_binding',mvc.actualBinding);
check(mvc.actualClearReply.data===3&&mvc.clearAgainResponse.data===0&&mvc.listAfterClearResponse.data.length===0,'mvc_clear_counts',{first:mvc.actualClearReply.data,again:mvc.clearAgainResponse.data});
const stable=JSON.stringify(mvc.runtimeSnapshots.beforeDisplay);check(stable===JSON.stringify(mvc.runtimeSnapshots.afterSaveAndList)&&stable===JSON.stringify(mvc.runtimeSnapshots.afterClear)&&mvc.runtimeTruthUnchanged===true,'recorded_runtime_truth_unchanged','Run/CP/events deep equal');
check(mvc.runtimeSnapshots.beforeDisplay.run[0][6]==='RUNNING'&&mvc.runtimeSnapshots.beforeDisplay.checkpoint[0][9]==='WAITING','recorded_nonterminal_qa_waiting',{run:mvc.runtimeSnapshots.beforeDisplay.run[0][6],checkpoint:mvc.runtimeSnapshots.beforeDisplay.checkpoint[0][9]});
check(budget.runResultUTF8Bytes<2*1024*1024&&budget.originalEightFieldDisplayUTF8Bytes>2*1024*1024&&budget.originalEightFieldDisplayUTF8Bytes<20*1024*1024,'recorded_large_result_display_relation',{result:budget.runResultUTF8Bytes,display:budget.originalEightFieldDisplayUTF8Bytes});
check(budget.wholeBodyRejectedUTF8Bytes>20*1024*1024&&budget.fieldRejectedUTF8Bytes>16*1024*1024-1&&budget.fieldRejectedWholeBodyUTF8Bytes<20*1024*1024,'recorded_display_rejection_relation',{body:budget.wholeBodyRejectedUTF8Bytes,field:budget.fieldRejectedUTF8Bytes,fieldBody:budget.fieldRejectedWholeBodyUTF8Bytes});
check(budget.runResultLimitUnchanged===2*1024*1024&&budget.controlLimitUnchanged===16*1024&&budget.storedOriginalAnswerEqual&&budget.storedOriginalAnswerItemAfterOuterQuoteRemovalEqual&&budget.runtimeTruthUnchangedByDisplay,'recorded_budget_and_storage_flags',budget.runResultLimitUnchanged);
check(mvc.businessVerified===false&&budget.businessVerified===false&&consumer.businessVerified===false,'artifact_business_limits',false);
check(consumer.astCasesEach===6&&consumer.realLeafSSREach===6&&consumer.currentResultsEqualOriginal&&consumer.sourceWindowChanges.length===0,'independent_consumer_recorded_counts',{AST:consumer.astCasesEach,SSR:consumer.realLeafSSREach});
for(const[p,h]of Object.entries(consumer.sourceHashesAfter||{}))check(before[p]===h,'independent_consumer_current_source',{path:p,expected:h,actual:before[p]});
// 最后才加入的备份引用同样记录当前只读window，不把旧备份冒充当前源码。
for(const p of fileSet)if(!(p in before))before[p]=fs.existsSync(path.join(root,p))?sha(p):null;
const after=Object.fromEntries(Object.keys(before).sort().map(p=>[p,fs.existsSync(path.join(root,p))?sha(p):null]));
const window=Object.keys(before).filter(p=>before[p]!==after[p]).map(p=>({path:p,before:before[p],after:after[p]}));check(window.length===0,'independent_read_window',window);
const report={checkedAt:new Date().toISOString(),scope:'批26最终冻结/hash/实际用例identity/JUnit证据/隔离MVC与预算产物/源码索引scope独立一致性核对，不重跑任何原检查',status:mismatch.length?'facts_inconsistent':'facts_consistent_with_explicit_scope_limits',inconsistencies:mismatch,references,sourceHashesBefore:before,sourceHashesAfter:after,sourceWindowChanges:window,
 finalGate:{path:paths.gate,sha256:before[paths.gate],inputs:12,currentDifferences:gateDifferences},mavenRecorded:{tests:97,suites:8,testsByClass:xmlSuites,newTests:23,rerunExisting:74,carriedUnchanged:480,combinedIdentities:combined.size,sourceInputsAtExecution:227,executionWindowChanges:result.sourceWindowChanges,currentPostExecutionIndexDifferences:currentMavenDifferences,whole577Rerun:false,independentlyRerunHere:false},
 identityChecks:{freshAndExpectedExact:sameSet(fresh,expected),copiedJUnitExact:sameSet(set(xmlCases),fresh),currentDeclared577Exact:sameSet(set(discovered),combined),prior554Plus23Exact:sameSet(new Set([...old,...added]),combined),countsAreIdentitiesNotBusinessAcceptance:true},
 indexRecorded:{counts,routes:175,originalRoutes:389,businessRuntimeVerified:0,currentDifferences:indexDifferences,recordedReadWindow:index.readAndGenerationWindow,indexGeneratorOrCheckerRunHere:false},
 actualArtifactsChecked:{mvc:reference(paths.mvc),largeBudget:reference(paths.budget),recordedListHTTP:{status:mvc.actualStatus,contentType:mvc.actualContentType},rows:3,originalFieldsEach:13,clear:[mvc.actualClearReply.data,mvc.clearAgainResponse.data],recordedRunState:'RUNNING',recordedCheckpointState:'WAITING',runtimeDeepEqual:stable===JSON.stringify(mvc.runtimeSnapshots.afterClear),largeWireOmitted:true,largeBodyHashesRecomputed:false},
 sourceReviewCarry:{finalBudget:20*1024*1024,fieldBudget:16*1024*1024-1,priorSourceReviewFacts:16,priorBudgetCorrection:checks+'调试显示历史显示预算独立差异补记.json',shapeSSRRerunHere:false,Runtime271RerunHere:false,Front110RerunHere:false},notes,
 limitations:['97为root已执行的八类Maven定向证据，本脚本只复核JSON/XML和hash；577为480沿用+97本轮identity，不是577修后全套重跑或577业务通过。','Maven227执行窗、最新索引109/562/93生成窗、本次独立读取窗是不同窗口；当前Mavenmanifest两项授权索引delta不能被写成0。','实际MVC只有list保存了完整status/contenttype，save和nullable保存有wire，clear为解析计数和JUnit断言；不补未记录元数据。','大答案省略正文，只验证产物文件hash、记录数值关系与源码/JUnit归属；不重算省略正文hash、不回放大payload，不将Service超限断言冒称独立真实HTTP。','H2/standalone MockMVC、内存API/store spies及原React SSR叶子不能证明真实MySQL/JWT/Redis/Provider/浏览器/发布/正式App；type2及旧UID/Spark SID数据导入仍未接通。','不审核尚未形成的aggregate正文，未改生产/测试/前端/Runtime/contracts/target/root文档，未重做索引。'],productionModified:false,targetWritten:false,businessVerified:false,migrationComplete:false};
const output=path.join(__dirname,'调试显示历史最终事实独立核对.json');assert.equal(fs.existsSync(output),false,'不可覆盖原证据');fs.writeFileSync(output,JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify({status:report.status,inconsistencies:mismatch,finalGateInputs:12,checkedReadWindowFiles:Object.keys(before).length,sourceWindowChanges:window.length,fresh:97,currentIdentityUnion:combined.size,currentMavenPostIndexChanges:currentMavenDifferences.length,proofSha256:crypto.createHash('sha256').update(fs.readFileSync(output)).digest('hex')}));
process.exitCode=mismatch.length?1:0;
