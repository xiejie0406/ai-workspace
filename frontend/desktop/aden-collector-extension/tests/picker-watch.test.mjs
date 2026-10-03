import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { startPicking, stopPicking } from '../picker.js';
import { watchProductIdentity } from '../page-watch.js';
const require=createRequire(new URL('../../aden-desktop/package.json',import.meta.url));const {JSDOM}=require('jsdom');
const fixture=await readFile(new URL('../fixtures/detail.html',import.meta.url),'utf8');
let messages=[];
function page(){
    const dom=new JSDOM(fixture,{url:'http://127.0.0.1:19876/detail.html'});
    globalThis.document=dom.window.document;globalThis.location=dom.window.location;globalThis.Element=dom.window.Element;globalThis.MutationObserver=dom.window.MutationObserver;globalThis.CSS={escape:value=>value};
    globalThis.chrome={runtime:{sendMessage:async value=>{messages.push(value);}}};messages=[];return dom;
}
test('点选参数的单行，不扩大为整个分区；Esc 清理高亮和未确认选择',async()=>{
    const dom=page();const parameter=document.querySelector('[data-product-parameters] li');parameter.id='material';
    startPicking();parameter.dispatchEvent(new dom.window.MouseEvent('click',{bubbles:true,cancelable:true}));
    assert.equal(messages[0].result.key,'params');assert.equal(messages[0].result.selector,'#material');assert.match(messages[0].result.sourceId,/^dom-/);
    assert.equal(document.querySelector('[aria-hidden=true]'),null);
    startPicking();document.dispatchEvent(new dom.window.KeyboardEvent('keydown',{key:'Escape',bubbles:true}));assert.equal(messages.at(-1).action,'pick.cancel');assert.equal(document.querySelector('[aria-hidden=true]'),null);stopPicking();
});
test('商品外和个人信息区域不能确认点选',()=>{
    const dom=page();startPicking();
    for(const node of [document.querySelector('aside p'),document.querySelector('[data-personal]')])node.dispatchEvent(new dom.window.MouseEvent('click',{bubbles:true,cancelable:true}));
    assert.equal(messages.length,0);stopPicking();
});
test('只读观察 SKU/规格变化通知一次失效，不滚动或点击网页',async()=>{
    page();const fingerprint=JSON.stringify([location.origin,location.pathname,'100000000000001','蓝色 / 标准款']);
    watchProductIdentity({documentId:'fixture-document',fingerprint,fixture:true});assert.equal(messages.length,0);
    document.querySelector('[data-selected-spec]').textContent='红色 / 标准款';await new Promise(resolve=>setTimeout(resolve,65));
    assert.deepEqual(messages,[{action:'page.changed',documentId:'fixture-document'}]);
    document.querySelector('[data-selected-spec]').textContent='黑色';await new Promise(resolve=>setTimeout(resolve,65));assert.equal(messages.length,1);
});

test('新版京东点选限定商品详情与主图；地址和买家评价不允许选入',()=>{
    const dom=page();document.body.innerHTML=`<div class="page-right-skuname"><span class="sku-title-name">商品标题</span></div>
      <div class="page-right-AddressComponent"><p id="address">私人地址</p></div>
      <div class="left-tabs-item everyone-reviews"><p id="review">买家评价</p></div>
      <div class="left-tabs-item"><div class="title">商品详情</div><div><p id="detail">商品说明</p><img id="detailImg" alt="商品详情图"></div></div>
      <div class="image-zoom-container main-img"><img id="mainImg" alt="商品主图"></div>`;
    startPicking();for(const id of ['address','review'])document.getElementById(id).dispatchEvent(new dom.window.MouseEvent('click',{bubbles:true,cancelable:true}));assert.equal(messages.length,0);stopPicking();
    for(const [id,key] of [['detail','detail'],['detailImg','detailImages'],['mainImg','main']]){
        startPicking();document.getElementById(id).dispatchEvent(new dom.window.MouseEvent('click',{bubbles:true,cancelable:true}));assert.equal(messages.at(-1)?.result?.key,key);assert.equal(messages.at(-1).result.selector,'#'+id);stopPicking();
    }
    dom.window.close();
});

test('新版京东所选规格变化立即废弃旧页面指纹，观察只通知一次',async()=>{
    const dom=page();dom.reconfigure({url:'https://item.jd.com/10044354583400.html'});document.body.innerHTML='<span class="specification-series-item--selected">iPhone 15 Pro</span><span class="specification-item-sku--selected">256G</span>';
    const fingerprint=JSON.stringify([location.origin,location.pathname,'10044354583400','iPhone 15 Pro / 256G']);
    watchProductIdentity({documentId:'modern-document',fingerprint});assert.equal(messages.length,0);
    document.querySelector('.specification-item-sku--selected').textContent='128G';await new Promise(resolve=>setTimeout(resolve,70));
    assert.deepEqual(messages,[{action:'page.changed',documentId:'modern-document'}]);dom.window.close();
});
