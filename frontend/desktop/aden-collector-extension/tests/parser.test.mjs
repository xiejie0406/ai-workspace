import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { extractProduct } from '../parser.js';
import { approvedResource, imageMime } from '../resources.js';
import { CONFIG } from '../config.js';
const require = createRequire(new URL('../../aden-desktop/package.json', import.meta.url));
const { JSDOM } = require('jsdom');
const fixture = await readFile(new URL('../fixtures/detail.html', import.meta.url), 'utf8');
function documentAt(url = 'http://127.0.0.1:19876/detail.html') {
    const dom = new JSDOM(fixture, { url });
    globalThis.document = dom.window.document;
    globalThis.location = dom.window.location;
    for (const image of document.querySelectorAll('img')) {
        Object.defineProperty(image, 'complete', { value: true, configurable: true });
        Object.defineProperty(image, 'naturalWidth', { value: 10 });
        Object.defineProperty(image, 'naturalHeight', { value: 10 });
    }
    return dom;
}
const rowContext = parsed => ({ documentId: 'synthetic-document', fingerprint: parsed.fingerprint,
    rows: parsed.parameterRows.map(({sourceId, rawText, name, value}) => ({sourceId, rawText, name, value})) });

test('新版京东识别标题规格和条件价格，详情不混入评价、地址或占位', () => {
    const dom = new JSDOM(`<div class="page-right-skuname"><span class="sku-title-name">苹果 iPhone 15 Pro 二手手机</span></div>
      <div class="product-price-panel"><div class="product-price--main">¥3149</div><span class="product-price--activity-item--tag">到手价</span><div class="product-price--gray">¥3499</div></div>
      <div class="page-right-spec"><span class="specification-series-item--selected">苹果 iPhone 15 Pro</span><span class="specification-item-sku--selected">白色钛金属</span><span class="specification-item-sku--selected">256G</span><span>128G</span></div>
      <div class="page-right-AddressComponent">私人地址</div><div class="parameter">规格参数</div>
      <div class="left-tabs-item everyone-reviews"><div class="title">买家评价</div><p>无关买家评价</p><img src="https://img10.360buyimg.com/review.jpg"></div>
      <div class="left-tabs-item"><div class="title">商品详情</div><div><p>商品成色说明</p><div class="page-right-AddressComponent">私人地址</div><img src="https://img10.360buyimg.com/detail.jpg"></div><div class="skeleton">加载占位</div></div>
      <div class="image-zoom-container main-img"><img src="https://img10.360buyimg.com/main.jpg"></div>
      <div class="image-carousel-track"><img src="https://img10.360buyimg.com/gallery.jpg"></div>`, {url:'https://item.jd.com/10044354583400.html'});
    globalThis.document=dom.window.document;globalThis.location=dom.window.location;
    for(const img of document.querySelectorAll('img')){Object.defineProperty(img,'complete',{value:true,configurable:true});Object.defineProperty(img,'naturalWidth',{value:100});}
    const injected=(0,eval)('('+extractProduct.toString()+')'); // 核对序列化注入不依赖模块变量。
    const basic=injected({sectionId:'basic'});assert.equal(basic.fields.title,'苹果 iPhone 15 Pro 二手手机');assert.equal(basic.fields.specification,'苹果 iPhone 15 Pro / 白色钛金属 / 256G');
    assert.equal(basic.fields.priceText,'到手价 ¥3149');assert.equal(basic.fields.price,undefined);assert.doesNotMatch(JSON.stringify(basic),/3499|私人地址|128G/);
    const detail=injected({sectionId:'detail'});assert.equal(detail.blocks[0].text,'商品成色说明');assert.doesNotMatch(JSON.stringify(detail),/买家评价|私人地址|加载占位/);
    assert.deepEqual(injected({sectionId:'detailImages'}).images.map(i=>i.url),['https://img10.360buyimg.com/detail.jpg']);
    assert.equal(injected({sectionId:'main'}).images[0].group,'MAIN');assert.equal(injected({sectionId:'gallery'}).images[0].group,'GALLERY');
    assert.match(injected({sectionId:'params'}).missingReason,/尚未加载/);assert.match(injected({sectionId:'intro'}).missingReason,/尚未加载/);
    for(const img of document.querySelectorAll('img'))Object.defineProperty(img,'complete',{value:false,configurable:true});
    assert.equal(injected({sectionId:'main'}).images.length,0);
    assert.throws(()=>injected({sectionId:'detail',regionRules:[{sectionId:'detail',mode:'include',selector:'.page-right-AddressComponent'}]}),/无法唯一定位|超出商品范围/);
    dom.window.close();
});
test('合成详情仅读商品区块，保留文本金额与SKU，排除个人内容及推荐', () => { documentAt(); const p = extractProduct({ fixtureOrigin: 'http://127.0.0.1:19876' }); assert.equal(p.fields.sku, '100000000000001'); assert.equal(p.fields.price, '129.90'); assert.equal(p.images.length, 1); assert.equal(p.fields.specification, '蓝色 / 标准款'); assert(!JSON.stringify(p).includes('收货地址')); assert(!JSON.stringify(p).includes('secret')); assert(!JSON.stringify(p).includes('无关推荐')); });
test('生产解析不接受localhost，精确限制JD域名与路径', () => { documentAt(); assert.throws(() => extractProduct(), /京东/); documentAt('https://item.jd.com.attacker.test/123.html'); assert.throws(() => extractProduct(), /京东/); documentAt('https://item.jd.com/123.html'); assert.throws(() => extractProduct(), /SKU/); });
test('验证码与SKU变更拒绝采集', () => { documentAt(); document.title = '安全检查'; assert.throws(() => extractProduct({ fixtureOrigin: location.origin }), /验证/); documentAt(); document.querySelector('[data-current-sku]').setAttribute('data-current-sku', '999'); assert.throws(() => extractProduct({ fixtureOrigin: location.origin }), /SKU/); });
test('条件价格不强转数字，未加载图片不收录', () => { documentAt(); document.querySelector('[data-product-price]').textContent = '券后 ¥99'; for (const image of document.querySelectorAll('img'))
    Object.defineProperty(image, 'complete', { value: false, configurable: true }); const p = extractProduct({ fixtureOrigin: location.origin }); assert.equal(p.fields.price, undefined); assert.equal(p.images.length, 0); });
test('下载地址精确白名单，不允许私网、用户凭据、HTTP与后缀冒充', () => { assert.equal(approvedResource('https://img10.360buyimg.com/a.jpg', CONFIG).hostname, 'img10.360buyimg.com'); for (const value of ['http://img10.360buyimg.com/a', 'https://127.0.0.1/a', 'https://img10.360buyimg.com.evil/a', 'https://user@img10.360buyimg.com/a', 'https://img10.360buyimg.com:444/a'])
    assert.throws(() => approvedResource(value, CONFIG)); });
test('实际字节检测拒绝SVG与HTML', () => { assert.equal(imageMime(new Uint8Array([137, 80, 78, 71, 13, 10, 26, 10])), 'image/png'); assert.throws(() => imageMime(new TextEncoder().encode('<svg></svg>'))); });
test('注入返回模式保留验证码和SKU拒绝原因，不误报授权失效', () => {
    documentAt(); document.title='安全检查';
    const challenge=extractProduct({fixtureOrigin:location.origin,returnErrors:true});
    assert.equal(challenge.error.code,'PAGE_PARSE_REJECTED');
    assert.match(challenge.error.message,/登录或验证.*手动处理/);
    assert.doesNotMatch(challenge.error.message,/授权/);
    documentAt();document.querySelector('[data-current-sku]').setAttribute('data-current-sku','999');
    const mismatch=extractProduct({fixtureOrigin:location.origin,returnErrors:true});
    assert.match(mismatch.error.message,/SKU 不一致/);
    assert.equal(mismatch.fields,undefined);
});
test('注入返回模式对未知异常只返回受控模板提示', () => {
    documentAt();document.querySelector=()=>{throw new Error('SECRET-UNEXPECTED-CONTENT')};
    const result=extractProduct({fixtureOrigin:location.origin,returnErrors:true});
    assert.equal(result.error.message,'页面商品结构解析失败，请反馈页面模板');
    assert.doesNotMatch(JSON.stringify(result),/SECRET/);
});
test('参数分区保留顺序、重复、单位和未知，排除行保留来源审计', () => {
    documentAt(); document.querySelector('[data-product-parameters]').innerHTML='<li>容量：500ml</li><li>颜色：蓝色</li><li>颜色：深蓝</li><li>材质：</li>';
    const first=extractProduct({fixtureOrigin:location.origin,sectionId:'params'});
    assert.deepEqual(first.parameterRows.map(row=>row.name),['容量','颜色','颜色','材质']);assert.equal(first.parameterRows[0].unit,'ml');assert.match(first.parameterRows[3].unknownReason,/页面/);
    const excluded=extractProduct({fixtureOrigin:location.origin,sectionId:'params',excludedRowIds:[first.parameterRows[1].sourceId],parameterRowContext:rowContext(first)});
    assert.equal(excluded.parameterRows[1].excluded,true);assert(!excluded.fields.parameters.includes('颜色：蓝色'));assert(excluded.fields.parameters.includes('颜色：深蓝'));assert(excluded.sourceIds.includes(first.parameterRows[1].sourceId));
});
test('同 DOM 切换商品不能把旧参数排除带到另一 SKU', () => {
    documentAt(); document.querySelector('[data-product-parameters]').innerHTML='<li>容量：500ml</li><li>颜色：蓝色</li>';
    const first=extractProduct({fixtureOrigin:location.origin,sectionId:'params'});
    const options={fixtureOrigin:location.origin,sectionId:'params',excludedRowIds:[first.parameterRows[1].sourceId],parameterRowContext:rowContext(first)};
    document.querySelector('[data-product-sku]').setAttribute('data-product-sku','200000000000002');
    document.querySelector('[data-current-sku]').setAttribute('data-current-sku','200000000000002');
    assert.throws(()=>extractProduct(options),/所属页面或规格已变化/);
    const fresh=extractProduct({fixtureOrigin:location.origin,sectionId:'params'});
    assert.notEqual(fresh.parameterRows[1].sourceId,first.parameterRows[1].sourceId);
    assert(fresh.parameterRows.every(row=>!row.excluded));
});
test('参数头部插行或重排使旧排除失效，不排除占用旧位置的另一行', () => {
    for(const changed of ['<li>尺寸：大号</li><li>容量：500ml</li><li>颜色：蓝色</li>','<li>颜色：蓝色</li><li>容量：500ml</li>']) {
        documentAt(); const root=document.querySelector('[data-product-parameters]');root.innerHTML='<li>容量：500ml</li><li>颜色：蓝色</li>';
        const first=extractProduct({fixtureOrigin:location.origin,sectionId:'params'});
        root.innerHTML=changed;
        assert.throws(()=>extractProduct({fixtureOrigin:location.origin,sectionId:'params',excludedRowIds:[first.parameterRows[1].sourceId],parameterRowContext:rowContext(first)}),/参数行位置或原文.*重新提取并核对/);
        const fresh=extractProduct({fixtureOrigin:location.origin,sectionId:'params'});assert(fresh.parameterRows.every(row=>!row.excluded));
        assert.equal(fresh.fields.parameters,fresh.parameterRows.map(row=>row.rawText).join('；'));
    }
});
test('同位置参数原文改变要重新核对，返回模式保留明确失效原因', () => {
    documentAt();const root=document.querySelector('[data-product-parameters]');root.innerHTML='<li>容量：500ml</li><li>颜色：蓝色</li>';
    const first=extractProduct({fixtureOrigin:location.origin,sectionId:'params'});root.children[1].textContent='材质：纯棉';
    const parsed=extractProduct({fixtureOrigin:location.origin,sectionId:'params',excludedRowIds:[first.parameterRows[1].sourceId],parameterRowContext:rowContext(first),returnErrors:true});
    assert.match(parsed.error.message,/参数行位置或原文.*原排除已失效/);assert.equal(parsed.parameterRows,undefined);
    const fresh=extractProduct({fixtureOrigin:location.origin,sectionId:'params'});assert.equal(fresh.parameterRows[1].excluded,false);assert.match(fresh.fields.parameters,/材质：纯棉/);
});
test('无核对快照的旧位置排除和超过100条范围不能静默执行或截断', () => {
    documentAt();const first=extractProduct({fixtureOrigin:location.origin,sectionId:'params'});
    assert.throws(()=>extractProduct({fixtureOrigin:location.origin,sectionId:'params',excludedRowIds:[first.parameterRows[0].sourceId]}),/缺少已核对预览/);
    assert.throws(()=>extractProduct({fixtureOrigin:location.origin,sectionId:'params',regionRules:Array.from({length:101},()=>({sectionId:'params',selector:'[data-product-parameters]',mode:'exclude'}))}),/最多设置 100/);
});
test('区域排除只删除参数行，其他行保留；不能读取商品外与个人区域', () => {
    documentAt(); const parameters=document.querySelector('[data-product-parameters]');parameters.id='parameters';parameters.innerHTML='<li id="capacity">容量：500ml</li><li>颜色：蓝色</li>';
    const parsed=extractProduct({fixtureOrigin:location.origin,sectionId:'params',regionRules:[{sectionId:'params',selector:'#capacity',sourceId:'capacity',mode:'exclude'}]});
    assert.equal(parsed.parameterRows.length,1);assert.equal(parsed.parameterRows[0].name,'颜色');assert(parsed.sourceIds.includes('capacity'));
    const outside=document.createElement('section');outside.id='outside';outside.textContent='私密商品外内容';document.body.append(outside);
    assert.throws(()=>extractProduct({fixtureOrigin:location.origin,sectionId:'detail',regionRules:[{sectionId:'detail',selector:'#outside',mode:'include'}]}),/超出商品范围/);
});
test('未识别 SKU 保留待核对内容和来源，不猜 SKU 或要求手动新增', () => {
    documentAt(); document.querySelector('[data-product-sku]').removeAttribute('data-product-sku');document.querySelector('[data-current-sku]').removeAttribute('data-current-sku');
    const product=extractProduct({fixtureOrigin:location.origin,sectionId:'basic'});assert.equal(product.fields.sku,undefined);assert.equal(product.fields.sourceUrl,location.origin+'/detail.html');assert.match(product.missingReason,/待核对/);assert(!JSON.stringify(product).includes('手动新增'));
});
test('自选区域只读取当前 DOM 文本，排除脚本并拒绝任意脚本选择规则', () => {
    documentAt();
    const area = document.createElement('section');
    area.id = 'after-sale';
    area.textContent = '售后保障：支持七天退换';
    const script = document.createElement('script'); script.textContent = 'secret-script'; area.append(script);
    document.querySelector('[data-product-root]').append(area);
    const product = extractProduct({ fixtureOrigin: location.origin, customRules: [
        { name: '售后保障', selector: '#after-sale' },
        { name: '恶意', selector: 'body;script' },
    ] });
    assert(product.blocks.some(block => block.type === 'custom:售后保障' && block.text.includes('七天退换')));
    assert(!JSON.stringify(product.blocks).includes('secret-script'));
    assert(!JSON.stringify(product.blocks).includes('custom:恶意'));
});
