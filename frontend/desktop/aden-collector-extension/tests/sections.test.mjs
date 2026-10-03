import test from 'node:test';
import assert from 'node:assert/strict';
import { SectionSession, mergeSections } from '../sections.js';
const result = (id, extra = {}) => ({ sectionId:id, documentId:'document-a', fingerprint:'sku-a', resultRevision:'1', fields:{title:'合成',sku:'123',sourceUrl:'https://item.jd.com/123.html'}, images:[],blocks:[],sourceIds:[], ...extra });
test('局部修改仅失效对应区；取消与重选不能复活旧结果', () => {
    const session = new SectionSession();
    session.accept('basic',session.begin('basic'),result('basic'));
    const token = session.begin('params'); session.accept('params',token,result('params'));
    session.setRule('params',{selector:'#p li',sourceId:'row-a',mode:'exclude'});
    assert.equal(session.sections.params.status,'stale'); assert.equal(session.sections.basic.status,'ready');
    assert.equal(session.accept('params',token,result('params')),false);
    session.select('basic',false);session.select('basic',true);assert.equal(session.sections.basic.result,null);assert.equal(session.sections.basic.status,'empty');
});
test('取消与新请求的迟到结果被拒绝，页面身份变化使全部无效', () => {
    const session = new SectionSession(); const before = session.begin('params');session.cancel();assert.equal(session.accept('params',before,result('params')),false);
    session.accept('basic',session.begin('basic'),result('basic'));
    assert.throws(()=>session.accept('params',session.begin('params'),result('params',{fingerprint:'sku-b'})),/页面或规格/);
    assert.equal(session.sections.basic.result,null);assert.equal(session.ready(),false);
    const late=session.begin('params');const latest=session.begin('params');session.accept('params',latest,result('params'));
    assert.equal(session.fail('params',late),false);assert.equal(session.sections.params.status,'ready');
});
test('至少一项且所选区必须全部完成；明确缺失可聚合待确认', () => {
    const session = new SectionSession();for(const id of ['params','intro','detail','main','gallery','detailImages'])session.select(id,false);
    assert.throws(()=>session.select('basic',false),/至少保留一个/);assert.equal(session.ready(),false);
    session.accept('basic',session.begin('basic'),result('basic',{missingReason:'未加载'}));assert.equal(session.ready(),true);assert.match(session.missing()[0],/未加载/);
});
test('重叠来源去重但保留全部区归属，参数重复来源不强行合并', () => {
    const block={sourceId:'same',type:'description',text:'同一来源'}; const image={imageId:'one',sourceId:'same-image',url:'https://img10.360buyimg.com/a.jpg',group:'MAIN'};
    const merged=mergeSections([result('intro',{blocks:[block],images:[image]}),result('detail',{blocks:[block],images:[image]})]);
    assert.equal(merged.blocks.length,1);assert.deepEqual(merged.blocks[0].sectionIds,['intro','detail']);assert.equal(merged.images.length,1);assert.deepEqual(merged.images[0].sectionIds,['intro','detail']);
    assert.throws(()=>mergeSections([result('basic'),result('params',{documentId:'document-b'})]),/同一商品/);
    assert.throws(()=>mergeSections([result('intro',{blocks:[block]}),result('detail',{blocks:[{...block,text:'不同内容'}]})]),/内容发生冲突/);
});
test('参数排除绑定已核对本页行快照，全局身份变化和取消参数清旧排除', () => {
    const session=new SectionSession();const rows=[{sourceId:'p1',name:'容量',value:'500ml',rawText:'容量：500ml'},{sourceId:'p2',name:'颜色',value:'蓝色',rawText:'颜色：蓝色'}];
    session.accept('params',session.begin('params'),result('params',{parameterRows:rows}));session.excludeRow('p2',true);
    assert.deepEqual(session.selection().excludedRowIds,['p2']);assert.deepEqual(session.selection().parameterRowContext,{documentId:'document-a',fingerprint:'sku-a',rows});
    assert.throws(()=>session.excludeRow('p1',true),/预览已失效/);
    session.invalidateAll();assert.deepEqual(session.selection().excludedRowIds,[]);assert.equal(session.selection().parameterRowContext,null);
    session.accept('params',session.begin('params'),result('params',{fingerprint:'sku-b',parameterRows:rows}));session.excludeRow('p1',true);
    session.select('params',false);assert.deepEqual(session.selection().excludedRowIds,[]);assert.equal(session.selection().parameterRowContext,null);
});
test('总范围第100条合法，第101条明确拒绝且全部状态保持，已有项可替换', () => {
    const session=new SectionSession();
    for(const id of ['basic','params','intro','detail','main'])for(let i=0;i<20;i++)session.setRule(id,{selector:'#'+id+i,sourceId:id+i,mode:'exclude'});
    assert.equal(session.selection().regionRules.length,100);
    const before=JSON.stringify(session);
    assert.throws(()=>session.setRule('gallery',{selector:'#gallery0',sourceId:'gallery0',mode:'exclude'}),/最多设置 100/);
    assert.equal(JSON.stringify(session),before);
    session.setRule('basic',{selector:'#basic0',sourceId:'basic0',mode:'include'});assert.equal(session.selection().regionRules.length,100);
    const replacement=JSON.stringify(session);assert.throws(()=>session.setRule('basic',{selector:'#extra',sourceId:'extra',mode:'exclude'}),/本区最多设置 20/);assert.equal(JSON.stringify(session),replacement);
});
