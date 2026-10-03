import test from 'node:test';
import assert from 'node:assert/strict';
import { fakeIndexedDB } from './idb-fixture.mjs';
let blockCleanup = false;
let failCleanup = false;
let cleanupEntered;
let releaseCleanup;
globalThis.indexedDB = fakeIndexedDB({ beforeDelete: async key => {
    if (!key.endsWith('|capture')) return;
    if (failCleanup) throw new Error('合成暂存清理失败');
    if (blockCleanup) { cleanupEntered(); await new Promise(resolve => { releaseCleanup = resolve; }); }
} });
const listeners = {};
const calls = [];
const sentMessages = [];
let progress;
let changed = false;
let sourceSaved = false;
let failedResource = true;
let failUpload = false;
let denyHello = false;
const event = name => ({ addListener: fn => { listeners[name] = fn; } });
const record = { source: 'JD', fields: { sku: '123', title: '合成' }, images: [{ imageId: 'one', url: 'https://img10.360buyimg.com/one.png', group: 'MAIN' }, { imageId: 'two', url: 'https://img10.360buyimg.com/two.png', group: 'DETAIL' }], blocks: [], parserVersion: 'test', fingerprint: 'same', completeness: {} };
globalThis.chrome = { runtime: { id: 'mnejmmlalapfhnanlnckfdhmfpbahidm', getURL: p => 'chrome-extension://mnejmmlalapfhnanlnckfdhmfpbahidm/' + p, onMessage: event('request'), sendMessage: async (m) => { sentMessages.push(m); if(m.job) progress = m.job; }, connectNative: () => ({ onMessage: event('native'), onDisconnect: event('disconnect'), postMessage: m => { calls.push(m); if ((failUpload && m.type === 'asset.chunk') || (denyHello && m.type === 'hello')) { queueMicrotask(()=>listeners.native({protocolVersion:1,messageId:m.messageId,ok:false,error:{code:denyHello?'AUTH_REQUIRED':'OFFLINE',message:'合成断连或注销'}})); return; } if (m.type === 'capture.prepare')
                sourceSaved = true; queueMicrotask(() => listeners.native({ protocolVersion: 1, messageId: m.messageId, ok: true, data: m.type === 'capture.prepare' ? { generation: 7 } : m.type === 'capture.status' ? { generation: 7, images: [{ imageId: 'one', status: 'SAVED' }, { imageId: 'two', status: 'FAILED' }] } : m.type === 'capture.commit' ? { status: m.payload.failures?.length ? 'PARTIAL' : 'SAVED' } : { workspaceId: 'ws', principalId: 'user-1', proofRequired:true, nonce:'synthetic-nonce-0123456789', expiresAt:new Date(Date.now()+120000).toISOString() } })); } }) }, action: { onClicked: event('action') }, sidePanel: { open: async () => { } }, storage: { session: { set: async () => { }, get: async () => ({ job: progress }) } }, tabs: { query: async () => [{ id: 1 }] }, scripting: { executeScript: async () => [{ documentId: changed && sourceSaved ? 'new' : 'original', result: structuredClone(record) }] }, permissions: { contains: async () => true }, alarms: { create(){}, onAlarm: event('alarm') } };
globalThis.fetch = async (url) => { if (url.includes('two') && failedResource)
    return new Response('denied', { status: 403 }); return new Response(new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10, 0])); };
globalThis.createImageBitmap = async () => ({ width: 1, height: 1, close() { } });
await import('../background.js');
const invoke = action => new Promise(resolve => listeners.request(action, { id: chrome.runtime.id, url: chrome.runtime.getURL('panel.html') }, resolve));

test('页面访问权限失效返回中文授权入口且不会连接或保存',async()=>{
    const original=chrome.scripting.executeScript;const before=calls.length;
    chrome.scripting.executeScript=async()=>{throw new Error('Cannot access contents of the page. Extension manifest must request permission to access the respective host.');};
    try{const response=await invoke({action:'preview'});assert.equal(response.ok,false);assert.match(response.error,/授权京东商品页/);assert.doesNotMatch(response.error,/Cannot access/);assert.equal(calls.length,before);}
    finally{chrome.scripting.executeScript=original;}
});
test('模拟桥流程顺序等待ACK，上传文件字节并记录图片部分失败', async () => { const result = await invoke({ action: 'capture', groups: ['MAIN', 'DETAIL'] }); assert(result.ok); assert.deepEqual(calls.map(x => x.type), ['hello', 'capture.prepare', 'asset.chunk', 'capture.commit']); const chunk = calls.find(x => x.type === 'asset.chunk').payload; assert.equal(chunk.generation, 7); assert.equal(chunk.totalSize, 9); assert.equal(chunk.offset, 0); assert.equal(Buffer.from(chunk.dataBase64, 'base64')[0], 137); assert.equal(calls.at(-1).payload.failures[0].imageId, 'two'); assert.equal(progress.saved, 1); assert.match(progress.message, /部分保存/); });
test('失败图片重试先查服务端且只重新上传缺失图片', async () => { calls.length = 0; failedResource = false; const result = await invoke({ action: 'retry' }); assert(result.ok); assert.deepEqual(calls.map(x => x.type), ['hello', 'capture.status', 'asset.chunk', 'capture.commit']); assert.equal(calls.find(x => x.type === 'asset.chunk').payload.imageId, 'two'); assert.equal(progress.saved, 2); });
test('准备后document变化拒绝图片上传，保留内容并标注失败', async () => { calls.length = 0; sourceSaved = false; changed = true; const result = await invoke({ action: 'capture', groups: ['MAIN'] }); assert(result.ok); assert.equal(calls.filter(x => x.type === 'asset.chunk').length, 0); assert.match(calls.at(-1).payload.failures[0].error, /页面或规格/); });
test('不接受内容脚本伪造侧栏消息', () => { assert.equal(listeners.request({ action: 'capture' }, { id: chrome.runtime.id, url: 'https://item.jd.com/123.html' }, () => { throw new Error('不应响应'); }), undefined); });
test('拒绝未知图片分组', async () => { const result = await invoke({ action: 'capture', groups: ['COOKIE'] }); assert.equal(result.ok, false); assert.match(result.error, /分组/); });
test('上传断连后保存真实字节，原页关闭仍可只用暂存恢复且不再fetch', async () => {
    changed=false; sourceSaved=false; failedResource=false; failUpload=true;
    await invoke({action:'capture',groups:['DETAIL']});
    const captureId=progress.captureId;
    const listed=await invoke({action:'spool.list'});
    assert(listed.data.some(row=>row.captureId===captureId));
    failUpload=false; changed=true;
    const fetchBefore=globalThis.fetch;
    globalThis.fetch=async()=>{throw new Error('离线恢复不得访问源站');};
    try {
        const result=await invoke({action:'spool.resume',captureId});
        assert(result.ok,result.error);
        assert.match(progress.message,/全部保存/);
        assert(!(await invoke({action:'spool.list'})).data.some(row=>row.captureId===captureId));
    } finally {globalThis.fetch=fetchBefore;}
});
test('收到注销拒绝后清理暂存，未认证local不返回旧捕获', async () => {
    changed=false;sourceSaved=false;failUpload=true;
    await invoke({action:'capture',groups:['DETAIL']});
    denyHello=true;
    const denied=await invoke({action:'spool.list'});
    assert.equal(denied.ok,false);
    assert.equal((await invoke({action:'local'})).data.captureId,undefined);
    denyHello=false;failUpload=false;
    assert.deepEqual((await invoke({action:'spool.list'})).data,[]);
});
test('后端成功后等待IDB清理事务完成才广播采集完成', async () => {
    changed=false;sourceSaved=false;failedResource=false;failUpload=false;denyHello=false;
    blockCleanup=true;
    const entered=new Promise(resolve=>{cleanupEntered=resolve;});
    const operation=invoke({action:'capture',groups:['MAIN']});
    await entered;
    assert.equal(progress.running,true);
    assert.doesNotMatch(progress.message,/本次选定内容已保存/);
    blockCleanup=false;releaseCleanup();
    const response=await operation;
    assert.equal(response.ok,true);
    assert.equal(progress.running,false);
    assert.match(progress.message,/本次选定内容已保存/);
});
test('本地清理失败保留后端成功结果并明确本地待清理', async () => {
    failCleanup=true;sourceSaved=false;
    try {
        const response=await invoke({action:'capture',groups:['MAIN']});
        assert.equal(response.ok,true);
        assert(progress.result);
        assert.match(progress.message,/后端已确认保存.*暂存清理失败/);
        assert.equal(progress.running,false);
    } finally { failCleanup=false; }
});
test('预览遵循七项范围和图片排除，内容变化时拒绝使用旧预览保存', async () => {
    changed=false; sourceSaved=false;
    const scope={basic:true,params:true,intro:true,detail:true,main:true,gallery:true,detailImages:false};
    const selection={scope,customRules:[],excludedImageIds:[]};
    const shown=await invoke({action:'preview',selection});
    assert.equal(shown.ok,true);
    assert.deepEqual(shown.data.images.map(image=>image.imageId),['one']);
    const denied=await invoke({action:'capture',groups:['MAIN','GALLERY','DETAIL'],selection,expected:{fingerprint:'same',documentId:'original',content:'stale'}});
    assert.equal(denied.ok,false);
    assert.match(denied.error,/重新提取/);
    assert.equal(sourceSaved,false);
});
test('统一提交绑定当前分区版本、确认缺失并只 prepare 一次；新信封带精确证明', async () => {
    calls.length=0;changed=false;sourceSaved=false;
    await invoke({action:'preview.clear'});
    const oldScript=chrome.scripting.executeScript;
    chrome.scripting.executeScript=async ({args})=>{
        const id=args?.[0]?.sectionId;
        return [{documentId:'document-sections',result:{source:'JD',fields:{title:'分区合成',sku:'123',sourceUrl:'https://item.jd.com/123.html'},blocks:id==='basic'?[{type:'title',text:'分区合成',sourceId:'title-source'}]:[],images:[],parameterRows:[],sourceIds:id==='basic'?['title-source']:[],sectionId:id,missingReason:id==='params'?'参数未加载':'',parserVersion:'test',fingerprint:'section-page',completeness:{}}}];
    };
    const selection={scope:{basic:true,params:true,intro:false,detail:false,main:false,gallery:false,detailImages:false},regionRules:[],excludedRowIds:[]};
    try{
        const basic=await invoke({action:'section.extract',sectionId:'basic',rangeRevision:'1',selection});assert.equal(basic.ok,true,basic.error);
        const params=await invoke({action:'section.extract',sectionId:'params',rangeRevision:'2',selection});assert.equal(params.ok,true,params.error);
        const expected={documentId:'document-sections',fingerprint:'section-page',selectionRevision:'3',confirmationRevision:'1',sections:[{sectionId:'basic',rangeRevision:'1',resultRevision:basic.data.resultRevision},{sectionId:'params',rangeRevision:'2',resultRevision:params.data.resultRevision}]};
        const denied=await invoke({action:'capture',groups:[],selection,expected});assert.equal(denied.ok,false);assert.match(denied.error,/确认缺失/);assert.equal(calls.filter(call=>call.type==='capture.prepare').length,0);
        const saved=await invoke({action:'capture',groups:[],selection,expected:{...expected,missingConfirmed:true}});assert.equal(saved.ok,true,saved.error);
        const prepare=calls.find(call=>call.type==='capture.prepare');assert.equal(calls.filter(call=>call.type==='capture.prepare').length,1);
        assert.equal(prepare.payload.extraction.pageFingerprint.length,64);assert.deepEqual(prepare.payload.extraction.selectedSections,['basic','params']);assert.equal(prepare.payload.extraction.sections[1].missingConfirmed,true);
        const hello=calls.filter(call=>call.type==='hello').at(-1);assert.equal(hello.nonce,undefined);assert.equal(typeof prepare.sequence,'string');assert.equal(prepare.nonce,'synthetic-nonce-0123456789');assert.equal(typeof prepare.expiresAt,'string');
        const stale=await invoke({action:'capture',groups:[],selection,expected:{...expected,missingConfirmed:true,sections:[{sectionId:'basic',rangeRevision:'99',resultRevision:basic.data.resultRevision},{sectionId:'params',rangeRevision:'2',resultRevision:params.data.resultRevision}]}});assert.equal(stale.ok,false);assert.match(stale.error,/分区预览已失效/);
    }finally{chrome.scripting.executeScript=oldScript;await invoke({action:'preview.clear'});}
});
test('清除预览期间的迟到解析不能重新建立有效分区', async () => {
    const oldScript=chrome.scripting.executeScript;let resolveParse;let entered;
    const started=new Promise(resolve=>{entered=resolve;});
    chrome.scripting.executeScript=async()=>{entered();return new Promise(resolve=>{resolveParse=resolve;});};
    const selection={scope:{basic:true},regionRules:[],excludedRowIds:[]};
    try{
        const pending=invoke({action:'section.extract',sectionId:'basic',rangeRevision:'1',selection});await started;
        await invoke({action:'preview.clear'});resolveParse([{documentId:'late',result:{...record,sectionId:'basic'}}]);
        const stale=await pending;assert.equal(stale.ok,false);assert.match(stale.error,/取消或过期/);
    }finally{chrome.scripting.executeScript=oldScript;}
});
test('Worker接受100条范围，拒绝第101条和伪造超限请求且不破坏已有预览', async () => {
    const oldScript=chrome.scripting.executeScript;let scriptCalls=0;
    await invoke({action:'preview.clear'});calls.length=0;
    chrome.scripting.executeScript=async ({args})=>{scriptCalls++;return [{documentId:'range-document',result:{source:'JD',fields:{title:'范围合成',sku:'123',sourceUrl:'https://item.jd.com/123.html'},sectionId:args?.[0]?.sectionId,blocks:[],images:[],parameterRows:[],sourceIds:['title'],fingerprint:'range-page',parserVersion:'test',completeness:{}}}];};
    const rules=['basic','params','intro','detail','main'].flatMap(sectionId=>Array.from({length:20},(_,i)=>({sectionId,selector:'#'+sectionId+i,sourceId:sectionId+i,name:'合成范围'+i,mode:'exclude'})));
    const selection={scope:{basic:true},regionRules:rules,excludedRowIds:[]};
    try {
        const first=await invoke({action:'section.extract',sectionId:'basic',rangeRevision:'1',selection});assert.equal(first.ok,true,first.error);
        const before=scriptCalls;const overflow={...selection,regionRules:[...rules,{sectionId:'gallery',selector:'#last',sourceId:'last',name:'不得漏掉的排除',mode:'exclude'}]};
        const rejected=await invoke({action:'section.extract',sectionId:'basic',rangeRevision:'2',selection:overflow});assert.equal(rejected.ok,false);assert.match(rejected.error,/最多设置 100/);assert.equal(scriptCalls,before);
        const saveRejected=await invoke({action:'capture',groups:[],selection:overflow});assert.equal(saveRejected.ok,false);assert.match(saveRejected.error,/最多设置 100/);assert.equal(scriptCalls,before);assert.equal(calls.filter(call=>call.type==='capture.prepare').length,0);
        const saved=await invoke({action:'capture',groups:[],selection,expected:{documentId:'range-document',fingerprint:'range-page',selectionRevision:'1',confirmationRevision:'1',sections:[{sectionId:'basic',rangeRevision:'1',resultRevision:first.data.resultRevision}]}});
        assert.equal(saved.ok,true,saved.error);assert.equal(calls.filter(call=>call.type==='capture.prepare').length,1);
    } finally {chrome.scripting.executeScript=oldScript;await invoke({action:'preview.clear'});}
});
test('Worker参数排除必须来自已核对快照，新文档不能沿用同结构旧排除', async () => {
    const oldScript=chrome.scripting.executeScript;let documentId='row-document';let drift=false;
    const rows=[{sourceId:'row1',name:'容量',value:'500ml',rawText:'容量：500ml',sectionId:'params',excluded:false},{sourceId:'row2',name:'颜色',value:'蓝色',rawText:'颜色：蓝色',sectionId:'params',excluded:false}];
    await invoke({action:'preview.clear'});calls.length=0;sentMessages.length=0;
    chrome.scripting.executeScript=async ({args})=>[{documentId,result:drift&&args?.[0]?.parameterRowContext?{error:{message:'参数行位置或原文已变化，原排除已失效，请重新提取并核对参数'}}:{source:'JD',fields:{title:'参数合成',sku:'123',sourceUrl:'https://item.jd.com/123.html',parameters:'容量：500ml；颜色：蓝色'},sectionId:args?.[0]?.sectionId,blocks:[],images:[],parameterRows:structuredClone(rows),sourceIds:['row1','row2'],fingerprint:'same-row-page',parserVersion:'test',completeness:{}}}];
    const selection={scope:{params:true},regionRules:[],excludedRowIds:[]};
    try {
        const initial=await invoke({action:'section.extract',sectionId:'params',rangeRevision:'1',selection});assert.equal(initial.ok,true,initial.error);
        const context={documentId,fingerprint:initial.data.fingerprint,rows:rows.map(({sourceId,rawText,name,value})=>({sourceId,rawText,name,value}))};
        const bound={...selection,excludedRowIds:['row2'],parameterRowContext:context};
        const unbound=await invoke({action:'section.extract',sectionId:'params',rangeRevision:'2',selection:{...selection,excludedRowIds:['row2']}});assert.equal(unbound.ok,false);assert.match(unbound.error,/缺少已核对预览/);
        documentId='new-row-document';const newDoc=await invoke({action:'section.extract',sectionId:'params',rangeRevision:'2',selection:bound});assert.equal(newDoc.ok,false);assert.match(newDoc.error,/所属页面或规格/);assert(sentMessages.some(message=>message.event==='preview.invalidated'));
        documentId=context.documentId;await invoke({action:'section.extract',sectionId:'params',rangeRevision:'3',selection});drift=true;
        const changedRows=await invoke({action:'section.extract',sectionId:'params',rangeRevision:'4',selection:bound});assert.equal(changedRows.ok,false);assert.match(changedRows.error,/参数行位置或原文/);
        assert(sentMessages.some(message=>message.event==='section.invalidated'&&message.sectionId==='params'&&message.clearParameterExclusions===true));assert.equal(calls.filter(call=>call.type==='capture.prepare').length,0);
    } finally {chrome.scripting.executeScript=oldScript;await invoke({action:'preview.clear'});}
});
test('旧排除的迟到失效错误不能清除新文档已核对预览', async () => {
    const oldScript=chrome.scripting.executeScript;let documentId='old-params-document';let release,entered;
    const started=new Promise(resolve=>{entered=resolve;});const rows=[{sourceId:'r1',rawText:'容量：500ml',name:'容量',value:'500ml',sectionId:'params',excluded:false}];
    const selection={scope:{params:true},regionRules:[],excludedRowIds:[]};
    await invoke({action:'preview.clear'});calls.length=0;
    chrome.scripting.executeScript=async ({args})=>{
        if(args?.[0]?.parameterRowContext){entered();return new Promise(resolve=>{release=resolve;});}
        return [{documentId,result:{source:'JD',fields:{title:'迟到合成',sku:'123',sourceUrl:'https://item.jd.com/123.html',parameters:'容量：500ml'},sectionId:args?.[0]?.sectionId,blocks:[],images:[],parameterRows:rows,sourceIds:['r1'],fingerprint:'params-page',parserVersion:'test',completeness:{}}}];
    };
    try {
        const first=await invoke({action:'section.extract',sectionId:'params',rangeRevision:'1',selection});assert.equal(first.ok,true,first.error);
        const bound={...selection,excludedRowIds:['r1'],parameterRowContext:{documentId,fingerprint:'params-page',rows:rows.map(({sourceId,rawText,name,value})=>({sourceId,rawText,name,value}))}};
        const pending=invoke({action:'section.extract',sectionId:'params',rangeRevision:'2',selection:bound});await started;
        await invoke({action:'preview.clear'});documentId='new-params-document';
        const latest=await invoke({action:'section.extract',sectionId:'params',rangeRevision:'3',selection});assert.equal(latest.ok,true,latest.error);sentMessages.length=0;
        release([{documentId:'old-params-document',result:{error:{message:'参数行位置或原文已变化，原排除已失效，请重新提取并核对参数'}}}]);assert.equal((await pending).ok,false);
        assert(!sentMessages.some(message=>['section.invalidated','preview.invalidated'].includes(message.event)));
        const saved=await invoke({action:'capture',groups:[],selection,expected:{documentId,fingerprint:'params-page',selectionRevision:'1',confirmationRevision:'1',sections:[{sectionId:'params',rangeRevision:'3',resultRevision:latest.data.resultRevision}]}});assert.equal(saved.ok,true,saved.error);
    } finally {chrome.scripting.executeScript=oldScript;await invoke({action:'preview.clear'});}
});
