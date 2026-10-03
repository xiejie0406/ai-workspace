// 实际原/current handleSaveDialogue AST生成请求；只记录长度/hash，不保存或输出大正文。
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const root=path.resolve(__dirname,'../../../../../..'),ts=require(path.join(root,'frontend/paiflow-web/node_modules/typescript'));
const original=path.join(root,'参考项目/PaiFlow-main/console/frontend/src/components/workflow/store/flow-chat-function.ts');
const current=path.join(root,'frontend/paiflow-web/src/components/workflow/store/flow-chat-function.ts');
const ddl=path.join(root,'参考项目/PaiFlow-main/docker/PaiFlow/mysql/schema.sql');
const limits=path.join(root,'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/RunProtocolLimits.java');
const output=path.join(__dirname,'工作流原调试对话双份答案字节边界独立核对.json');
assert.ok(!fs.existsSync(output),'不得覆盖已交付证据');
const sha=value=>crypto.createHash('sha256').update(value).digest('hex');
const inputs=[original,current,ddl,limits,__filename];
const before=Object.fromEntries(inputs.map(p=>[path.relative(root,p).replaceAll('\\','/'),sha(fs.readFileSync(p))]));
const startedAt=new Date().toISOString(),MiB=1024*1024;
const text='x'.repeat(Math.floor(11*MiB/10));
const question=[{id:'text',name:'text',type:'string',required:true,content:'small fixture',default:'',fileType:''}];
const answer={messageContent:'',reasoningContent:'',content:text};
const sid='00000000-0000-0000-0000-000000000001',chatId='fixture-chat-group';
function declaration(file,name){const source=fs.readFileSync(file,'utf8'),ast=ts.createSourceFile(file,source,ts.ScriptTarget.Latest,true);const node=ast.statements.find(n=>ts.isVariableStatement(n)&&n.declarationList.declarations.some(d=>d.name.getText(ast)===name));assert.ok(node,name);return {text:node.getText(ast).replace(/^export\s+/,''),line:ast.getLineAndCharacterOfPosition(node.getStart(ast)).line+1};}
function capture(file){
 let body,saveCalls=0,refreshCalls=0;
 const state={chatIdRef:chatId,chatInfoRef:{sid,question:structuredClone(question),answer:structuredClone(answer),answerItem:text}};
 const sandbox={module:{exports:{}},cloneDeep:structuredClone,useFlowsManager:{getState:()=>({currentFlow:{id:'123',flowId:'550e8400-e29b-41d4-a716-446655440000'}})},
  saveDialogueAPI:params=>{saveCalls++;body=structuredClone(params);return new Promise(()=>{});},getDialogues:()=>{refreshCalls++;}};
 const init=declaration(file,'initChatInfo'),fn=declaration(file,'handleSaveDialogue');
 vm.runInNewContext(ts.transpileModule(init.text+'\n'+fn.text+'\nmodule.exports=handleSaveDialogue;',
  {compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText,sandbox);
 sandbox.module.exports(()=>state,()=>{});
 assert.equal(saveCalls,1);assert.equal(refreshCalls,0);assert.ok(state.chatInfoRef.sid===undefined,'原函数同步释放本地chatInfoRef');
 const names=Object.keys(body);assert.deepEqual(names,['chatId','type','workflowId','sid','questionItem','answerItem','question','answer']);
 assert.equal(body.sid,sid);assert.equal(body.type,1);assert.equal(body.questionItem,body.question);
 assert.ok(JSON.parse(body.question) instanceof Array);assert.ok(JSON.parse(body.answer).content===text);
 assert.ok(JSON.parse(body.answerItem)===text,'原answerItem使用JSON.stringify，不是直接正文');
 const wire=Buffer.from(JSON.stringify(body),'utf8');
 const fields=Object.fromEntries(names.map(name=>{const value=body[name],raw=typeof value==='string'?value:JSON.stringify(value),encoded=JSON.stringify(value);
  return [name,{jsType:typeof value,valueUtf8Bytes:Buffer.byteLength(raw,'utf8'),valueSha256:sha(Buffer.from(raw,'utf8')),
   wireEncodedValueUtf8Bytes:Buffer.byteLength(encoded,'utf8'),wireMemberUtf8Bytes:Buffer.byteLength(JSON.stringify(name)+':'+encoded,'utf8')}];}));
 assert.equal(Object.values(fields).reduce((sum,f)=>sum+f.wireMemberUtf8Bytes,0)+names.length+1,wire.length);
 return {wire,summary:{source:path.relative(root,file).replaceAll('\\','/'),handleSaveDialogueLine:fn.line,fieldOrder:names,
  requestUtf8Bytes:wire.length,requestSha256:sha(wire),fields,saveCalls,networkCalls:0,responseResolved:false,refreshCalls,
  containsSameAnswerInTwoFields:true,sourceFunctionResetBeforeAck:true}};
}
const reference=capture(original),now=capture(current);
assert.ok(reference.wire.equals(now.wire),'原/current实际请求bytes不一致（禁止输出大payload）');
assert.ok(reference.summary.requestUtf8Bytes>2*MiB,'本case应超过借用的Run结果预算');
const resultBody={leaseId:'lease-fixture',attempt:1,state:'COMPLETED',output:{answer:text}};
const resultWire=Buffer.from(JSON.stringify(resultBody),'utf8');
assert.ok(resultWire.length<2*MiB,'单份原Run结果应在现2MiB预算内');
assert.ok(fs.readFileSync(limits,'utf8').includes('RESULT_BYTES=2*1024*1024;'),'仅读当前实际Run预算，不修改预算');
const sqlLines=fs.readFileSync(ddl,'utf8').split(/\r?\n/),start=sqlLines.findIndex(line=>line.startsWith('CREATE TABLE `workflow_dialog`'));
assert.ok(start>=0);
const end=sqlLines.findIndex((line,index)=>index>start&&line.startsWith(') ENGINE='));
assert.ok(end>start);
const sqlTypes={};for(let i=start;i<=end;i++){const match=sqlLines[i].match(/^\s*`(question|question_item|answer|answer_item|data)`\s+(text|longtext|mediumtext)\b/);if(match)sqlTypes[match[1]]={sqlType:match[2],line:i+1};}
assert.equal(sqlTypes.question.sqlType,'text');assert.equal(sqlTypes.question_item.sqlType,'text');
assert.equal(sqlTypes.answer.sqlType,'longtext');assert.equal(sqlTypes.answer_item.sqlType,'longtext');assert.equal(sqlTypes.data.sqlType,'mediumtext');
const after=Object.fromEntries(inputs.map(p=>[path.relative(root,p).replaceAll('\\','/'),sha(fs.readFileSync(p))]));
assert.deepEqual(after,before);
const proof={schemaVersion:'paiflow.dialogue-double-copy-byte-probe.v1',startedAt,endedAt:new Date().toISOString(),
 execution:{command:[process.execPath,__filename],workingDirectory:process.cwd(),exitCode:0,nodeVersion:process.version,typeScriptVersion:ts.version},
 sourceHashesBefore:before,sourceHashesAfter:after,sourceWindowChanges:[],
 fixture:{textSource:'固定ASCII字符x；只在内存生成，不保存正文',requestedTextMiB:1.1,textUtf8Bytes:Buffer.byteLength(text,'utf8'),textSha256:sha(Buffer.from(text,'utf8')),
  questionItemCount:question.length,questionNames:question.map(item=>item.name),sidMeaning:'占位UUID，未查询或声称真实Run已存在',chatIdMeaning:'固定显示分组占位'},
 original:reference.summary,current:now.summary,originalCurrentWireByteIdentical:true,
 standardRunResult:{shape:'{leaseId,attempt,state:COMPLETED,output:{answer:<同一正文>}}',actualUtf8Bytes:resultWire.length,
  sha256:sha(resultWire),actualSourceResultBudgetBytes:2*MiB,withinExistingResultBudget:true},
 observedBoundary:{historyRequestExceedsTwoMiB:reference.wire.length>2*MiB,
  historyRequestBytes:reference.wire.length,singleRunResultBytes:resultWire.length,
  explanation:'原页面同时JSON.stringify answer对象和answerItem正文，两份答案又进入HTTP外层JSON；合法单份Run结果不意味着其显示历史包能沿用相同总预算。'},
 originalSql:{table:'workflow_dialog',createTableLine:start+1,types:sqlTypes,
  correction:'原question/question_item是TEXT、answer/answer_item是LONGTEXT、data是MEDIUMTEXT；原表不是全部MEDIUMTEXT。',executed:false},
 previousFrozenEvidence:{path:'文档/项目/PaiFlow工作流项目/证据/本地源码检查/checks/工作流原调试对话历史合同金样.json',sha256:'b948df26f7d350d51345e89cf7cdce6ea97367f6329e636c953575521f08bf9d',overwritten:false},
 restrictions:{productionModified:false,frontendModified:false,testsModified:false,sqlModified:false,targetWritten:false,MavenExecuted:false,
  network:false,databaseAccessed:false,servicesStarted:false,providerCalled:false,businessVerified:false},
 limits:['仅执行原/current选定handleSaveDialogue AST和明确内存API/store spy，不执行React、完整模块、HTTP或Controller。',
  '请求包大小是真实JSON.stringify与UTF8字节；不推断Java当前拒绝状态，拒前/修后由根的实际H2边界方法证明。',
  'Run输出包仅固定形状内存序列化，不执行TaskRunner/RunLease或写运行结果；现Run 2MiB预算保持。',
  '根拟历史总预算20MiB/字段MEDIUMTEXT UTF8限额属于独立源码变化，本probe不声称已实现或验证。']};
fs.writeFileSync(output,JSON.stringify(proof,null,2)+'\n','utf8');
console.log(JSON.stringify({proof:path.relative(root,output).replaceAll('\\','/'),sha256:sha(fs.readFileSync(output)),textUtf8Bytes:proof.fixture.textUtf8Bytes,
 historyRequestUtf8Bytes:reference.wire.length,standardRunResultUtf8Bytes:resultWire.length,originalCurrentWireByteIdentical:true,
 fieldUtf8Bytes:Object.fromEntries(Object.entries(reference.summary.fields).map(([name,info])=>[name,info.valueUtf8Bytes])),sourceWindowChanges:[],largePayloadPrinted:false}));
