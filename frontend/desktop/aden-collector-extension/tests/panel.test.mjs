import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../../aden-desktop/package.json', import.meta.url));
const { JSDOM } = require('jsdom');
const html = await readFile(new URL('../panel.html', import.meta.url), 'utf8');
const dom = new JSDOM(html, { url: 'https://extension.test/panel.html' });
globalThis.window = dom.window; globalThis.document = dom.window.document; globalThis.confirm = () => true;
dom.window.HTMLDialogElement.prototype.showModal = function () { this.open = true; };
dom.window.HTMLDialogElement.prototype.close = function () { this.open = false; };
let receive, revision = 0; const calls = [];
const output = id => ({ documentId:'doc-1', fingerprint:'sku-1', pageFingerprint:'a'.repeat(64), sectionId:id, resultRevision:String(++revision),
    fields:{title:'合成商品',sku:'123',sourceUrl:'https://item.jd.com/123.html',parameters:'容量：500ml；材质：'},
    blocks:id === 'detail' ? [{type:'description',text:'合成详情'}] : [], images:[], sourceIds:['source-'+id],
    parameterRows:id === 'params' ? [{name:'容量',value:'500ml',rawText:'容量：500ml',unit:'ml',sourceId:'param-1',sectionId:'params'},{name:'材质',value:'',rawText:'材质：',unknownReason:'页面未显示',sourceId:'param-2',sectionId:'params'}] : [] });
globalThis.chrome = {runtime:{sendMessage:async request => {calls.push(request);return {ok:true,data:request.action === 'section.extract' ? output(request.sectionId) : request.action === 'lookup' ? {exists:false} : {}};},onMessage:{addListener:listener=>{receive=listener;}}},permissions:{request:async()=>true}};
await import('../panel.js');
const tick = () => new Promise(resolve=>setTimeout(resolve,0));
async function extract(id) {document.querySelector('[data-section-extract="'+id+'"]').click();await tick();}
function row(id) {return document.querySelector('[data-section-extract="'+id+'"]').closest('.scope-section');}

test('页面授权只请求京东且直接来自点击，拒绝后不提取或误报成功',async()=>{
    const previous=chrome.permissions.request;const requested=[];let grant=false;
    chrome.permissions.request=async request=>{requested.push(request);return grant;};
    try{
        const before=calls.length;document.getElementById('pagePermission').click();assert.deepEqual(requested,[{origins:['https://item.jd.com/*']}]);await tick();
        assert.match(document.getElementById('status').textContent,/未授权京东/);assert.equal(calls.length,before);
        grant=true;document.getElementById('pagePermission').click();await tick();assert.match(document.getElementById('status').textContent,/已授权京东.*重新提取/);
    }finally{chrome.permissions.request=previous;}
});
test('保存进度持续展示准确计数，已有计数不重复',()=>{
    for(const [message,saved,total] of [['部分保存',6,8],['重试完成',8,8],['图片 8/8 已保存',8,8]]){
        receive({event:'progress',job:{message,saved,total,running:false}});
        assert.equal(document.getElementById('status').textContent,message.includes('图片')?message:message+'；图片 '+saved+'/'+total+' 已保存');
    }
});
test('七区默认全选，说明页与首页同一选择，最后一项不能取消',()=>{
    const checks=[...document.querySelectorAll('#scopeList input[data-scope]')];assert.equal(checks.length,7);assert(checks.every(item=>item.checked));
    document.querySelector('#scopeList [data-scope=intro]').click();assert.equal(document.querySelector('#settingsScopes [data-scope=intro]').checked,false);
    document.querySelector('#settingsScopes [data-scope=intro]').click();
    for(const id of ['basic','params','intro','detail','main','gallery'])document.querySelector('#scopeList [data-scope='+id+']').click();
    document.querySelector('#scopeList [data-scope=detailImages]').click();assert.equal(document.querySelector('#scopeList [data-scope=detailImages]').checked,true);assert.match(document.getElementById('status').textContent,/至少保留一个/);
    document.getElementById('resetButton').click();
});
test('独立提取与展开参数：未知值、单位可读，收起不丢结果',async()=>{
    await extract('basic');await extract('params');assert.match(row('basic').textContent,/已提取/);assert.match(row('params').textContent,/2 个参数/);
    document.querySelector('[data-section-expand=params]').click();assert.match(row('params').textContent,/500ml/);assert.match(row('params').textContent,/未知/);assert.match(row('params').textContent,/单位：ml/);
    document.querySelector('[data-section-expand=params]').click();assert.match(row('params').textContent,/已提取/);
});
test('点选排除小参数范围只让参数过期，不取消分区或基本信息',async()=>{
    receive({event:'pick.result',result:{key:'params',selector:'#params li:nth-of-type(1)',sourceId:'param-1',sample:'容量：500ml'}});
    document.getElementById('excludePick').click();await tick();assert.equal(document.querySelector('#scopeList [data-scope=params]').checked,true);
    assert.match(row('params').textContent,/待重提取/);assert.match(row('basic').textContent,/已提取/);assert.equal(document.getElementById('capture').disabled,true);
    await extract('params');const request=calls.filter(call=>call.action==='section.extract').at(-1);assert.equal(request.selection.regionRules[0].mode,'exclude');assert.equal(request.selection.regionRules[0].sectionId,'params');
});
test('取消参数选择清预览，再选后需提取；页面变化使全部失效',()=>{
    document.querySelector('#scopeList [data-scope=params]').click();document.querySelector('#scopeList [data-scope=params]').click();assert.match(row('params').textContent,/未提取/);
    receive({event:'preview.invalidated',reason:'SKU 已变化'});assert.match(row('basic').textContent,/待重提取/);assert.equal(document.getElementById('capture').disabled,true);
});
test('所选区全部提取后统一保存一次，并发送冻结版本，不保存单区',async()=>{
    document.getElementById('connect').click();await tick();document.getElementById('preview').click();for(let i=0;i<15;i++)await tick();
    assert.equal(document.getElementById('capture').disabled,false);document.getElementById('capture').click();await tick();
    const request=calls.filter(call=>call.action==='capture').at(-1);assert(request);assert.equal(request.expected.sections.length,7);assert.equal(typeof request.expected.selectionRevision,'string');assert.equal(request.expected.documentId,'doc-1');assert.equal(calls.filter(call=>call.action==='capture').length,1);
});
test('侧栏参数排除发送本页快照，参数漂移局部清除；页面变化不复用旧排除',async()=>{
    document.getElementById('resetButton').click();await extract('basic');await extract('params');
    if(!row('params').querySelector('.parameters'))document.querySelector('[data-section-expand=params]').click();
    row('params').querySelector('.parameters input').click();await extract('params');
    let selection=calls.filter(call=>call.action==='section.extract').at(-1).selection;
    assert.deepEqual(selection.excludedRowIds,['param-1']);assert.equal(selection.parameterRowContext.documentId,'doc-1');assert.equal(selection.parameterRowContext.fingerprint,'sku-1');assert.equal(selection.parameterRowContext.rows[0].rawText,'容量：500ml');
    receive({event:'section.invalidated',sectionId:'params',clearParameterExclusions:true,reason:'参数行位置或原文已变化，原排除已失效，请重新提取并核对参数'});
    assert.match(row('params').textContent,/待重提取/);assert.match(row('basic').textContent,/已提取/);assert.match(document.getElementById('status').textContent,/重新提取并核对参数/);
    await extract('params');selection=calls.filter(call=>call.action==='section.extract').at(-1).selection;assert.deepEqual(selection.excludedRowIds,[]);assert.equal(selection.parameterRowContext,null);
    row('params').querySelector('.parameters input').click();receive({event:'preview.invalidated',reason:'SKU 已变化'});await extract('params');
    selection=calls.filter(call=>call.action==='section.extract').at(-1).selection;assert.deepEqual(selection.excludedRowIds,[]);assert.equal(selection.parameterRowContext,null);
});
test('侧栏第101个范围明确提示上限，保留100个已确认的排除范围',async()=>{
    document.getElementById('resetButton').click();
    for(const key of ['basic','params','intro','detail','main'])for(let i=0;i<20;i++) {
        receive({event:'pick.result',result:{key,selector:'#'+key+i,sourceId:key+i,sample:'合成来源'}});document.getElementById('excludePick').click();
    }
    await tick();await extract('basic');let selection=calls.filter(call=>call.action==='section.extract').at(-1).selection;assert.equal(selection.regionRules.length,100);assert(selection.regionRules.every(rule=>rule.mode==='exclude'));
    receive({event:'pick.result',result:{key:'gallery',selector:'#overflow',sourceId:'overflow',sample:'第101处'}});document.getElementById('excludePick').click();await tick();
    assert.match(document.getElementById('status').textContent,/最多设置 100/);await extract('basic');selection=calls.filter(call=>call.action==='section.extract').at(-1).selection;
    assert.equal(selection.regionRules.length,100);assert(!selection.regionRules.some(rule=>rule.sourceId==='overflow'));
});
