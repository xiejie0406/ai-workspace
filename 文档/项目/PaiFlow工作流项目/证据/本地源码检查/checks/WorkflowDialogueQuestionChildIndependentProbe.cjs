// 实际原/current getDialogues、MessageAsk、renderer AST与已有ReactDOMServer；不调用候选Service。
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../../../../../..'),modules=path.join(root,'frontend/paiflow-web/node_modules');
const ts=require(path.join(modules,'typescript')),React=require(path.join(modules,'react')),SSR=require(path.join(modules,'react-dom/server'));
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(path.join(root,p))).digest('hex');
const gatePath='文档/项目/PaiFlow工作流项目/证据/本地源码检查/checks/调试显示历史答案显示修正后源码冻结输入.json';
const gate=JSON.parse(fs.readFileSync(path.join(root,gatePath),'utf8'));
assert.equal(hash(gatePath),'66106353f3193cb62be29e0959f90f28274a5e76b94404d8dc8a8db6993c1401');
for(const source of gate.inputs)assert.equal(hash(source.path),source.sha256,'必须按新冻结源核对');
const origin='参考项目/PaiFlow-main/console/frontend/src/components/workflow',current='frontend/paiflow-web/src/components/workflow';
const paths=[...gate.inputs.map(source=>source.path),`${origin}/drawer/chat-debugger/components/chat-content.tsx`,`${current}/drawer/chat-debugger/components/chat-content.tsx`,`${origin}/store/flow-chat-function.ts`,`${current}/store/flow-chat-function.ts`];
const before=Object.fromEntries(paths.map(p=>[p,hash(p)]));
function declaration(file,key){const text=fs.readFileSync(path.join(root,file),'utf8'),ast=ts.createSourceFile(file,text,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);let node;function walk(n){if(ts.isVariableDeclaration(n)&&n.name.getText(ast)===key)node=n;ts.forEachChild(n,walk);}walk(ast);assert.ok(node,key);return key==='renderInputElement'?node.initializer.arguments[0].getText(ast):node.parent.parent.getText(ast);}
const service=fs.readFileSync(path.join(root,gate.inputs.find(s=>s.path.endsWith('/WorkflowDialogService.java')).path),'utf8');
assert.ok(service.includes('requireStringConversion(input.get("default"))'));
assert.equal(service.includes('input.get("name")'),false,'只读确认新源码尚未验证渲染名称，不称实际Service接受执行');
const tests=[
 {id:'two-inputs-object-name',items:[{name:{key:'x'},default:'正文'},{name:'B',default:''}],error:'Error'},
 {id:'two-inputs-array-object-name',items:[{name:[{key:'x'}],default:'正文'},{name:'B',default:''}],error:'Error'},
 {id:'single-hidden-object-name',items:[{name:{key:'x'},default:'正文'}]},
 {id:'two-inputs-null-name',items:[{name:null,default:'正文'},{name:'B',default:''}]},
 {id:'two-inputs-false-zero-name',items:[{name:false,default:'正文'},{name:0,default:''}]},
 {id:'two-inputs-literal-array-name',items:[{name:['A',0,false,null],default:'正文'},{name:'B',default:''}]},
 {id:'file-object-entry-name',items:[{fileType:'file',default:[{name:{key:'x'}}]}],error:'Error'},
 {id:'file-array-object-entry-name',items:[{fileType:'file',default:[{name:[{key:'x'}]}]}],error:'Error'},
 {id:'file-literal-array-entry-name',items:[{fileType:'file',default:[{name:['文件',0,false,null]}]}]},
 {id:'file-null-entry-name',items:[{fileType:'file',default:[{name:null}]}]},
 {id:'file-missing-null-entry',items:[{fileType:'file',default:[null]}]},
 {id:'image-string-hidden-object-entry-name',items:[{fileType:'image',type:'string',default:[{name:{key:'x'},url:'private-no-fetch'}]}]},
 {id:'image-other-type-shown-object-entry-name',items:[{fileType:'image',type:'array',default:[{name:{key:'x'}}]}],error:'Error'},
 {id:'falsy-file-type-hidden-entry-name',items:[{fileType:false,default:[{name:{key:'x'}}]}]},
 {id:'unknown-null-unicode',items:[{name:'😀',default:'正文😀',unknown:null},{name:'B',default:0}]},
 {id:'empty-input-array',items:[]}
];
function evaluate(flow,content){let rows;const box={module:{exports:{}},React,icons:{chatUser:'private-no-fetch'},getDialogueAPI:()=>({then:cb=>cb(rows)}),uuid:()=> 'private',typeList:new Map(),MarkdownRender:({content})=>React.createElement('private-markdown',null,typeof content==='string'?content:null),Image:()=>React.createElement('private-image')};
 const code=declaration(flow,'getDialogues')+'\n'+declaration(content,'MessageAsk')+'\nconst privateRenderer='+declaration(content,'renderInputElement')+';\nmodule.exports={getDialogues,MessageAsk,privateRenderer};';
 vm.runInNewContext(ts.transpileModule(code,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022,jsx:ts.JsxEmit.React}}).outputText,box);
 return tests.map(test=>{rows=[{id:1,chatId:'private',question:JSON.stringify(test.items),answer:'{}'}];let parsed;box.module.exports.getDialogues('private',value=>parsed=value.chatList);let rendered,error;try{rendered=SSR.renderToStaticMarkup(React.createElement(box.module.exports.MessageAsk,{chat:parsed.find(row=>row.type==='ask'),renderInputElement:box.module.exports.privateRenderer}));}catch(e){error={name:e.name,message:e.message};}
 assert.equal(error?.name||null,test.error||null,test.id);return{id:test.id,exactStoredQuestion:rows[0].question,error:error||null,returnedHtml:rendered??null};});}
const original=evaluate(paths.at(-2),paths.at(-4)),currentResults=evaluate(paths.at(-1),paths.at(-3));assert.deepEqual(currentResults,original);
const after=Object.fromEntries(paths.map(p=>[p,hash(p)]));assert.deepEqual(after,before);
const out=path.join(__dirname,'调试显示历史问题名称React子节点独立反例.json');assert.equal(fs.existsSync(out),false);
const result={checkedAt:new Date().toISOString(),scope:'新Service问题数组guard只读审查+实际原/current getDialogues→MessageAsk→renderInputElement→ReactDOMServer有限验证',gate:{path:gatePath,sha256:hash(gatePath)},caseCount:tests.length,sourceHashesBefore:before,sourceHashesAfter:after,sourceWindowChanges:[],originalResults:original,currentEqualsOriginal:true,
 sourceFinding:{service:'ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/WorkflowDialogService.java',serviceSha256:gate.inputs.find(s=>s.path.endsWith('/WorkflowDialogService.java')).sha256,serviceExecution:false,consumerErrors:tests.filter(t=>t.error).map(t=>t.id),condition:'inputs.length>1时input.name为真实React child；fileType truthy且非image/string分支、default为非空Array时default[0].name为真实React child；Map及递归含Map的Array实际报Objects are not valid as a React child',recommendation:'仅对实际显示的name调用与答案id/text相同的递归child校验；单输入的隐藏name、image/string分支的隐藏file name及falsy/null/primitive数组子项保原行为。不要强制name必填或把隐藏metadata转为String。'},
 limitations:['实际已有React/ReactDOMServer，MarkdownRender与Image叶子为无副作用stub；只证明直接文本子节点边界，无浏览器/上传/网络/正式App','未编译/调用候选Service/Jackson/Mapper，Service接受结论是源码组合，不称实际HTTP或事务','原16/current16结果相等，不改任何生产源/Runtime/tests/target；原18内存树probe不能证明React child合法，本报告追加实际SSR证据且不覆盖旧证据'],productionModified:false,businessVerified:false};
fs.writeFileSync(out,JSON.stringify(result,null,2)+'\n');console.log(JSON.stringify({cases:tests.length,confirmedConsumerErrors:tests.filter(t=>t.error).length,sourceWindowChanges:0,proofSha256:crypto.createHash('sha256').update(fs.readFileSync(out)).digest('hex')}));
