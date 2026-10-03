// 只调用原/当前AST parser和MessageAsk函数；React.createElement为内存树，不挂载页面。
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const root=path.resolve(__dirname,'../../../../../..'),ts=require(path.join(root,'frontend/paiflow-web/node_modules/typescript'));
const orig='参考项目/PaiFlow-main/console/frontend/src/components/workflow',cur='frontend/paiflow-web/src/components/workflow';
const paths=[`${orig}/store/flow-chat-function.ts`,`${cur}/store/flow-chat-function.ts`,`${orig}/drawer/chat-debugger/components/chat-content.tsx`,`${cur}/drawer/chat-debugger/components/chat-content.tsx`,
 '参考项目/PaiFlow-main/console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/service/workflow/WorkflowService.java','参考项目/PaiFlow-main/console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/entity/table/workflow/WorkflowDialog.java'];
const sha=value=>crypto.createHash('sha256').update(value).digest('hex'),read=p=>fs.readFileSync(path.join(root,p),'utf8'),hashes=()=>Object.fromEntries(paths.map(p=>[p,sha(fs.readFileSync(path.join(root,p)))]));
const before=hashes();
function tree(file){return ts.createSourceFile(file,read(file),ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);}
function declaration(file,key){const ast=tree(file),node=ast.statements.find(node=>ts.isVariableStatement(node)&&node.declarationList.declarations.some(d=>d.name.getText(ast)===key));assert.ok(node,key);return node.getText(ast).replace(/^export\s+/,'');}
function inputRenderer(file){const ast=tree(file),hook=ast.statements.find(node=>ts.isVariableStatement(node)&&node.declarationList.declarations.some(d=>d.name.getText(ast)==='useChatContent'));assert.ok(hook);let selected;function walk(node){if(ts.isVariableDeclaration(node)&&node.name.getText(ast)==='renderInputElement')selected=node.initializer.arguments[0];ts.forEachChild(node,walk);}walk(hook);assert.ok(selected);return selected.getText(ast);}
const cases=[
 {id:'array-empty',question:'[]',answer:'{}',parse:true,render:true},
 {id:'array-unicode-null-unknown',question:JSON.stringify([{name:'AGENT_USER_INPUT',type:'string',default:'中文😀\t\n',opaque:null}]),answer:JSON.stringify({messageContent:'',reasoningContent:'',content:'',option:null,opaque:null}),parse:true,render:true},
 {id:'question-object',question:'{"AGENT_USER_INPUT":"文本"}',answer:'{}',parse:true,render:false},
 {id:'question-empty-object',question:'{}',answer:'{}',parse:true,render:false},
 {id:'question-json-null',question:'null',answer:'{}',parse:true,render:true},
 {id:'question-outer-null',question:null,answer:'{}',parse:true,render:true},
 {id:'question-number',question:'0',answer:'{}',parse:true,render:false},
 {id:'question-string',question:'"文本"',answer:'{}',parse:true,render:false},
 {id:'question-boolean',question:'false',answer:'{}',parse:true,render:false},
 {id:'question-empty-string',question:'',answer:'{}',parse:false},
 {id:'question-missing',answer:'{}',parse:false},
 {id:'answer-empty-object',question:'[]',answer:'{}',parse:true,render:true},
 {id:'answer-json-null',question:'[]',answer:'null',parse:true,render:true},
 {id:'answer-outer-null',question:'[]',answer:null,parse:true,render:true},
 {id:'answer-null-fields',question:'[]',answer:'{"messageContent":null,"reasoningContent":null,"content":null,"option":null}',parse:true,render:true},
 {id:'answer-falsy-fields',question:'[]',answer:'{"messageContent":false,"reasoningContent":0,"content":""}',parse:true,render:true},
 {id:'answer-unicode',question:'[]',answer:JSON.stringify({content:'雪😀\n\t',messageContent:'',reasoningContent:'',extra:{opaque:null}}),parse:true,render:true},
 {id:'answer-json-array',question:'[]',answer:'[]',parse:true,render:true},
 {id:'answer-json-number',question:'[]',answer:'7',parse:true,render:true},
 {id:'answer-empty-string',question:'[]',answer:'',parse:false},
 {id:'answer-missing',question:'[]',parse:false},
 {id:'answer-malformed',question:'[]',answer:'not-json',parse:false}
];
function evaluate(flow,content){const results=[];for(const input of cases){let displayed,parseError,renderError;const row={id:1,chatId:'fixture-display-group',...input};delete row.id;row.id=1;delete row.parse;delete row.render;
 const box={module:{exports:{}},uuid:()=> 'private-display-id',getDialogueAPI:()=>({then:cb=>cb([row])}),React:{createElement:(tag,props,...children)=>({tag:typeof tag==='string'?tag:'private-component',props,children})},icons:{chatUser:'private-icon'},MarkdownRender:()=>{},typeList:new Map(),Image:()=>{}};
 const code=declaration(flow,'getDialogues')+'\n'+declaration(content,'MessageAsk')+`\nconst privateInputRenderer=${inputRenderer(content)};module.exports={getDialogues,MessageAsk,privateInputRenderer};`;
 vm.runInNewContext(ts.transpileModule(code,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022,jsx:ts.JsxEmit.React}}).outputText,box);
 try{box.module.exports.getDialogues('fixture',patch=>displayed=patch.chatList);}catch(error){parseError=error.name;}
 assert.equal(!parseError,input.parse,input.id+' parser');if(displayed){try{box.module.exports.MessageAsk({chat:displayed.find(item=>item.type==='ask'),renderInputElement:box.module.exports.privateInputRenderer});}catch(error){renderError=error.name;}assert.equal(!renderError,input.render,input.id+' MessageAsk');}
 const answer=displayed?.find(item=>item.type==='answer');results.push({id:input.id,exactRow:row,parsed:!parseError,parseError:parseError||null,messageAskInvoked:!!displayed,messageAskReturnedTree:displayed?!renderError:null,messageAskError:renderError||null,parsedQuestion:displayed?.find(item=>item.type==='ask')?.inputs,answerSelected:answer?{messageContent:answer.messageContent,reasoningContent:answer.reasoningContent,content:answer.content,option:answer.option}:null});
 }return JSON.parse(JSON.stringify(results));}
const original=evaluate(paths[0],paths[2]),current=evaluate(paths[1],paths[3]);assert.deepEqual(original,current);
const trim=read(paths[4]).match(/        String answerItem = dialog\.getAnswerItem\(\);\r?\n        if \(answerItem != null[\s\S]*?        \}/)?.[0];assert.ok(trim);assert.equal(/parse|unescape|trim\(/i.test(trim),false,'原规则仅最外quote substring');
const after=hashes();assert.deepEqual(before,after);
const output={checkedAt:new Date().toISOString(),scope:'原/当前getDialogues AST+MessageAsk函数调用有限形状probe；MessageReply/Markdown/ReactDOM未挂载',status:'有限入口检查通过',caseCount:cases.length,sourceHashesBefore:before,sourceHashesAfter:after,sourceWindowChanges:[],originalResults:original,currentEqualsOriginal:true,
 answerItemSourceRule:{path:paths[4],lines:'1704-1707',snippet:trim,semantics:'外部nullable String原样存；长度>=2且首尾均ASCII双引号时仅各去一个，不解JSON、不反转义、不strip空白。空字符串不变，两个quote变空串，四quote变两个quote，quote包裹反斜杠n仍为两个字符，不变实际换行或emoji。'},
 recommendations:['question应是可解析JSON字符串；原正常save为array，{}虽parser接受但MessageAsk真实调用会TypeError。建议本批page安全仅array或明确保留JSON null；若允许object不能声称原页面可渲染，不转换为数组伪造输入。',
 'answer的JSON空object与messageContent/reasoningContent/content缺失/null/空串均被原parser投影为空串；内JSON null和外null也不会使getDialogues报错，若新profile限定object须明确这是边界收窄。不要补假answer文本或剪掉存储字符串内未知字段/显式null。',
 'answer显示三文本字段建议optional nullable String、option optional nullable array；原false/0在||fallback中变空串，真实非空object等并非文本支持证明，应在新profile显式拒而不coerce为成功。',
 'questionItem/answerItem保持nullable String，不把它们强制JSON object/array或反转义；chatId nullable<=100，null和空串显示分组可能不同，不默默合并。',
 '正式owner/space/Run/version必须中心核对，客户端displaybody只作显示数据；每POST append不造幂等/终态，缺type的list/clear仍先授权再空list/0。'],
 limitations:['有22组手造shape，不是正式Run/数据库数据或业务验收','React.createElement为内存树，MessageAsk实际map表达式运行但不证明DOM/Markdown安全','answerItem本轮仅核实际源码，没有执行原Service；原DTO/Jackson金样沿API b948证据','原/current/Runtime生产0改；没有DB/HTTP/Provider/Maven/target/安装'],businessVerified:false};
const destination=path.join(__dirname,'工作流调试对话JSON形状独立核对.json');assert.equal(fs.existsSync(destination),false,'冻结后不覆盖');fs.writeFileSync(destination,JSON.stringify(output,null,2)+'\n');console.log(JSON.stringify({cases:cases.length,sources:paths.length,sourceChanges:0,originalCurrentEqual:true,objectQuestionParserAcceptedButMessageAskTypeError:true,sha256:sha(fs.readFileSync(destination))}));
