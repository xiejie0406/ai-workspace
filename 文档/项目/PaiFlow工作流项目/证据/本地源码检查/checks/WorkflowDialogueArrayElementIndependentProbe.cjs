// 真实MessageAsk和renderInputElement表达式；只有React.createElement内存树，无DOM/文件请求。
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../../../../../..'),ts=require(path.join(root,'frontend/paiflow-web/node_modules/typescript'));
const paths=['参考项目/PaiFlow-main/console/frontend/src/components/workflow/drawer/chat-debugger/components/chat-content.tsx','frontend/paiflow-web/src/components/workflow/drawer/chat-debugger/components/chat-content.tsx'];
const hash=p=>crypto.createHash('sha256').update(fs.readFileSync(path.join(root,p))).digest('hex'),before=Object.fromEntries(paths.map(p=>[p,hash(p)]));
function selected(file){const source=fs.readFileSync(path.join(root,file),'utf8'),tree=ts.createSourceFile(file,source,ts.ScriptTarget.Latest,true,ts.ScriptKind.TSX);let ask,renderer;function walk(node){if(ts.isVariableDeclaration(node)&&node.name.getText(tree)==='MessageAsk')ask=node.parent.parent.getText(tree);if(ts.isVariableDeclaration(node)&&node.name.getText(tree)==='renderInputElement')renderer=node.initializer.arguments[0].getText(tree);ts.forEachChild(node,walk);}walk(tree);assert.ok(ask&&renderer);return`${ask}\nconst privateRenderer=${renderer};module.exports={MessageAsk,privateRenderer};`;}
const tests=[
 {id:'missing-all-fields',items:[{}]}, {id:'null-item',items:[null]}, {id:'number-item',items:[0]}, {id:'boolean-item',items:[false]}, {id:'string-item',items:['输入']}, {id:'nested-array-item',items:[[]]},
 {id:'default-null',items:[{default:null}]}, {id:'default-boolean-number',items:[{default:false},{default:0}]},
 {id:'normal-unicode-input',items:[{name:'中文😀',type:'string',default:'正文😀\t\n',description:'',required:true,validationSchema:null,errorMsg:'',originErrorMsg:''}]},
 {id:'default-array',items:[{default:['a','b']}]}, {id:'default-object',items:[{default:{field:'value',opaque:null}}]},
 {id:'default-object-own-toString',items:[{default:{toString:0}}],error:'TypeError'},
 {id:'default-array-own-coercion-object',items:[{default:[{toString:null}]}],error:'TypeError'},
 {id:'file-string-valid',items:[{name:'file',type:'string',fileType:'file',default:[{name:'fixture.txt',url:'private-fixture-no-request'}]}]},
 {id:'file-image-valid',items:[{name:'image',type:'string',fileType:'image',default:[{url:'private-fixture-no-request'}]}]},
 {id:'file-default-empty-null',items:[{fileType:'file',default:[]},{fileType:'file',default:null}]},
 {id:'file-default-null-entry',items:[{fileType:'file',default:[null]}]},
 {id:'unknown-and-hidden-null',items:[{name:'保留',default:'',opaque:null,unknown:{nullable:null}}]}
];
function evaluate(file){const box={module:{exports:{}},React:{createElement:(tag,props,...children)=>({tag:typeof tag==='string'?tag:'private-component',props,children})},icons:{chatUser:'private'},MarkdownRender:()=>{},Image:()=>{},typeList:new Map()};vm.runInNewContext(ts.transpileModule(selected(file),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022,jsx:ts.JsxEmit.React}}).outputText,box);
 return tests.map(test=>{let error,renderCalls=0;try{box.module.exports.MessageAsk({chat:{id:'private',inputs:test.items},renderInputElement:(chat,input)=>{renderCalls++;return box.module.exports.privateRenderer(chat,input);}});}catch(e){error=e.name;}assert.equal(error||null,test.error||null,test.id);return{id:test.id,exactInputs:test.items,error:error||null,renderCalls};});}
const original=evaluate(paths[0]),current=evaluate(paths[1]);assert.deepEqual(current,original);const after=Object.fromEntries(paths.map(p=>[p,hash(p)]));assert.deepEqual(after,before);
const evidence={checkedAt:new Date().toISOString(),scope:'原/当前MessageAsk+原renderInputElement实际表达式的私有有限入口；不执行ReactDOM',caseCount:tests.length,sourceHashesBefore:before,sourceHashesAfter:after,sourceWindowChanges:[],originalResults:original,currentEqualsOriginal:true,
 facts:['数组项的name/type/default等均由optional chain或guard读取，未见必须字段的直接解引用；{}、null、primitive项在此入口不会直接崩溃，缺default会显示undefined。此事实不能把这些异常项宣称为正式业务输入。',
 '正常保存项来自Start输出和resume AGENT_USER_INPUT描述对象，保name/type/default/description/required/validationSchema/errorMsg/originErrorMsg、fileType等原字段及unknown/null。不能无来源强制八字段全非空。',
 '无fileType时非string default会执行模板字符串`${input?.default}`；default={toString:0}及default=[{toString:null}]实际抛TypeError。这是JSON对象自身属性阻断原toString，不是代码执行。',
 'file default的Array.isArray/length及entry?.name/entry?.url有guard，空/null/数组null项不会直接解引用；这不证明链接已上传/可访问或图片安全。',
 '多项时name作为React child，复杂对象name未通过本内存createElement校验；实际DOM/React children另有类型要求。本轮只证明源码表达式调用，不声称未知形状都可页面显示。'],
 recommendations:['优先保留原正常array item对象或null及原字段；无直接解引用依据时不要强制原八字段必填。若本批为安全收窄异常primitive项，标成明确profile限制而非原Pydantic要求。',
 '文本可选name/type/fileType/description等若提供应按原字段String/null界限；default需要保原string/number/bool/null、对象/数组及file元数据，不可全部改成字符串。',
 '在显示校验中拒JSON对象key toString/valueOf（至少default子树）或对会进入文本coercion的值做有限安全验证，避免已有原coercion表达式崩溃；不要执行客户端自带方法。unknown普通键/显式null保留。',
 '用codePointCount核chatId<=100而非UTF16 length，合法补充Unicode保留；另有整体UTF8 byte预算。null和空chatId不合并。'],
 limitations:['仅18组私有输入，不是新中心HTTP/真实Run业务','React.createElement为memory tree，没有DOM/Markdown/图片请求；渲染树返回不等同页面通过','两处确认失败按源码原行为保留，生产未修、旧22proof不覆盖'],productionModified:false,targetWritten:false,businessVerified:false};
const out=path.join(__dirname,'工作流调试对话数组项独立核对.json');assert.equal(fs.existsSync(out),false);fs.writeFileSync(out,JSON.stringify(evidence,null,2)+'\n');console.log(JSON.stringify({cases:tests.length,expectedOriginalCoercionErrors:2,sourceWindowChanges:0,sha256:crypto.createHash('sha256').update(fs.readFileSync(out)).digest('hex')}));
