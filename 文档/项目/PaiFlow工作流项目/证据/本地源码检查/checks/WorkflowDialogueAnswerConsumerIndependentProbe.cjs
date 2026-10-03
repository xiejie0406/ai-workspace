// 冻结首版Service只作源码断言；实际执行原/current组件+已安装ReactDOM SSR，不调用候选Service。
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../../../../../..'),modules=path.join(root,'frontend/paiflow-web/node_modules'),ts=require(path.join(modules,'typescript')),React=require(path.join(modules,'react')),SSR=require(path.join(modules,'react-dom/server'));
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(path.join(root,p))).digest('hex');
const gatePath='文档/项目/PaiFlow工作流项目/证据/本地源码检查/checks/调试显示历史生产源码首版冻结输入.json',gate=JSON.parse(fs.readFileSync(path.join(root,gatePath),'utf8'));
assert.equal(hash(gatePath),'d6012de14874ed98519967e3a56bb68df9151006e35f196d99b1eb5499713908');
for(const source of gate.inputs)assert.equal(hash(source.path),source.sha256,'必须按首版冻结源核对');
const origin='参考项目/PaiFlow-main/console/frontend/src/components/workflow',current='frontend/paiflow-web/src/components/workflow';
const paths=[...gate.inputs.map(source=>source.path),`${origin}/drawer/chat-debugger/components/chat-content.tsx`,`${current}/drawer/chat-debugger/components/chat-content.tsx`,`${origin}/store/flow-chat-function.ts`,`${current}/store/flow-chat-function.ts`];
const before=Object.fromEntries(paths.map(p=>[p,hash(p)]));
function declaration(file,key){const text=fs.readFileSync(path.join(root,file),'utf8'),ast=ts.createSourceFile(file,text,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX),node=ast.statements.find(node=>ts.isVariableStatement(node)&&node.declarationList.declarations.some(d=>d.name.getText(ast)===key));assert.ok(node,key);return node.getText(ast);}
const service=fs.readFileSync(path.join(root,gate.inputs.find(source=>source.path.endsWith('/WorkflowDialogService.java')).path),'utf8');assert.ok(service.includes('parsed instanceof Map<?,?>'));assert.equal(service.includes('answer.get('),false,'本首版未实施答案字段形状校验；仅源码，不称实际Service调用');
const cases=[
 {id:'visible-option-object',content:'已有文本',option:{},error:'TypeError'},
 {id:'visible-option-string',content:'已有文本',option:'literal',error:'TypeError'},
 {id:'visible-option-true',content:'已有文本',option:true,error:'TypeError'},
 {id:'visible-option-one',content:'已有文本',option:1,error:'TypeError'},
 {id:'visible-option-false',content:'已有文本',option:false},
 {id:'visible-option-zero',content:'已有文本',option:0},
 {id:'visible-option-empty-string',content:'已有文本',option:''},
 {id:'hidden-option-object',content:'',option:{}},
 {id:'visible-option-null',content:'已有文本',option:null},
 {id:'visible-option-empty-array',content:'已有文本',option:[]},
 {id:'visible-option-null-item',content:'已有文本',option:[null]},
 {id:'visible-option-missing-id-text',content:'已有文本',option:[{}]},
 {id:'visible-option-object-id',content:'已有文本',option:[{id:{key:'x'},text:'文字'}],error:'Error'},
 {id:'visible-option-object-text',content:'已有文本',option:[{id:'A',text:{key:'x'}}],error:'Error'},
 {id:'visible-option-null-fields',content:'已有文本',option:[{id:null,text:null}]},
 {id:'visible-option-falsy-literals',content:'已有文本',option:[{id:0,text:false}]},
 {id:'visible-option-unicode',content:'雪😀',option:[{id:'A😀',text:'文本😀',content_type:'text'}]}
];
function evaluate(flow,content){let rows;const box={module:{exports:{}},React,uuid:()=> 'fixture',getDialogueAPI:()=>({then:cb=>cb(rows)}),isJSON:value=>{try{JSON.parse(value);return true;}catch{return false;}},MarkdownRender:({content})=>React.createElement('private-markdown',{'data-original-type':typeof content},typeof content==='string'?content:null),JSONPretty:()=>null};
 const code=declaration(flow,'getDialogues')+'\n'+declaration(content,'MessageReplyContent')+'\nmodule.exports={getDialogues,MessageReplyContent};';vm.runInNewContext(ts.transpileModule(code,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022,jsx:ts.JsxEmit.React}}).outputText,box);
 return cases.map(item=>{const answer={messageContent:'',reasoningContent:'',content:item.content,option:item.option};rows=[{id:1,chatId:'private',question:'[]',answer:JSON.stringify(answer)}];let parsed;box.module.exports.getDialogues('private',value=>parsed=value.chatList);const chat=parsed.find(row=>row.type==='answer');let rendered,error;try{rendered=SSR.renderToStaticMarkup(React.createElement(box.module.exports.MessageReplyContent,{chat,debuggering:false,index:1,chatList:parsed,handleResumeChat:()=>{throw new Error('点击未授权执行');}}));}catch(e){error={name:e.name,message:e.message};}
 assert.equal(error?.name||null,item.error||null,item.id);return{id:item.id,exactStoredAnswer:rows[0].answer,error:error||null,returnedHtml:rendered??null};});}
const original=evaluate(paths.at(-2),paths.at(-4)),actualCurrent=evaluate(paths.at(-1),paths.at(-3));assert.deepEqual(original,actualCurrent);const after=Object.fromEntries(paths.map(p=>[p,hash(p)]));assert.deepEqual(before,after);
const out=path.join(__dirname,'调试显示历史首版答案显示反例独立核对.json');assert.equal(fs.existsSync(out),false);
const result={checkedAt:new Date().toISOString(),scope:'首版Service displayMap接受边界只读审查+实际原/current getDialogues→MessageReplyContent→ReactDOMServer有限验证',gate:{path:gatePath,sha256:hash(gatePath)},caseCount:cases.length,sourceHashesBefore:before,sourceHashesAfter:after,sourceWindowChanges:[],originalResults:original,currentEqualsOriginal:true,
 sourceFinding:{service:'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/WorkflowDialogService.java',originalServiceSha256:gate.inputs.find(input=>input.path.endsWith('/WorkflowDialogService.java')).sha256,acceptanceProof:'display源码仅要求答案Map、Unicode和depth；本probe未编译或调用候选Service/Jackson/Mapper',confirmedConsumerFailures:['visible-option-object','visible-option-string','visible-option-true','visible-option-one','visible-option-object-id','visible-option-object-text'],minimumFix:'只在三文本truthy的原显示guard成立时校验答案字段；truthy option必须Array，Map id/text为真实React child error；falsy false/0/空串/null保留原无展示语义，不凭想象要求id/text必填。'},
 limitations:['实际使用已有React/ReactDOMServer而非createElement替身，但MarkdownRender和JSONPretty叶子为无副作用stub；只证实option map及option id/text的实际React child边界','并非浏览器/ReactDOM页面验收，无网络/DB/Provider/Engine/Maven/target写','没有运行候选Service；接受形状结论为源码审查，与实际React消费者反例组合，真实Service回归由root执行'],productionModified:false,businessVerified:false};fs.writeFileSync(out,JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify({cases:cases.length,confirmedOriginalConsumerErrors:6,sourceWindowChanges:0,sha256:crypto.createHash('sha256').update(fs.readFileSync(out)).digest('hex')}));
