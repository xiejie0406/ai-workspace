const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const ts=require('typescript');
function fixture(reply){
  const calls=[],exports={};
  const source=fs.readFileSync(path.join(__dirname,'../src/services/database.ts'),'utf8');
  vm.runInNewContext(ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText,{exports,Blob,FormData,require:name=>{assert.equal(name,'@/utils/http');return {default:{get:async(...args)=>{calls.push(['GET',...args]);return reply();},post:async(...args)=>{calls.push(['POST',...args]);return reply();}}};}});
  return {module:exports,calls};
}
function response(content,type='application/vnd.openxmlformats-officedocument.spreadsheetml.sheet') {return {data:new Blob([content],{type}),headers:{'content-type':type,'content-disposition':'attachment; filename="fields.xlsx"'}};}
test('字段模板直接下载中心文件，原字段/测试/正式数据模板共用Blob协议',async()=>{
  const file=response('PK\u0003\u0004真实二进制fixture');const f=fixture(()=>file);
  assert.equal(await f.module.downloadFieldTemplate(),file);assert.equal(f.calls[0][1],'/db/field-template');assert.equal(f.calls[0][2].responseType,'blob');
  assert.equal(await f.module.downloadTableTemplate({tbId:9}),file);assert.deepEqual(JSON.parse(JSON.stringify(f.calls[1][2].params)),{tbId:9});
  assert.equal(await f.module.exportData({tbId:9,execDev:1}),file);assert.equal(f.calls[2][1],'/db/export-table-data');
});
test('HTTP200 JSON错误不保存为表格，保留message/msg/desc失败原因',async()=>{
  for(const field of ['message','msg','desc'])for(const method of ['downloadFieldTemplate','downloadTableTemplate','exportData']) {
    const f=fixture(()=>response(JSON.stringify({code:403,[field]:'无权下载当前表'}),'application/json'));
    await assert.rejects(f.module[method]({tbId:9,execDev:1}),/无权下载当前表/);assert.equal(f.calls.length,1);
  }
});
test('错误MIME、登录页面、空文件和畸形JSON明确拒绝且不重复请求',async()=>{
  for(const file of [response('{"code":503,"message":"资源未启用"}','application/octet-stream'),response('<!doctype html><html>login</html>','text/html'),response(''),response('{broken','application/json'),{data:null,headers:{}}]) {
    const f=fixture(()=>file);await assert.rejects(f.module.downloadTableTemplate({tbId:9}));assert.equal(f.calls.length,1);
  }
});
test('后台网络失败直接传播，不隐式改为外部模板链接或空成功',async()=>{
  const f=fixture(()=>{throw new Error('connection lost');});await assert.rejects(f.module.downloadFieldTemplate(),/connection lost/);assert.equal(f.calls.length,1);
});
test('保留列从中心实际数据规则读取，保持原表单value字符串契约',async()=>{
  const f=fixture(()=>({value:'id,uid,create_time'}));const result=await f.module.getDisableFields();assert.equal(result.value,'id,uid,create_time');assert.equal(f.calls[0][1],'/db/reserved-fields');
});
