// 独立只读审查：加载当前前端源码与既有测试沙箱，不请求网络或改前端。
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {createRequire} = require('node:module');
const root = path.resolve(__dirname, '../../..');
const file = path.join(root, 'frontend/paiflow-web/tests/workflow-revision.test.cjs');
const localRequire = createRequire(file);
const source = fs.readFileSync(file, 'utf8');
const observations = [];
const sandbox = {
  __dirname: path.dirname(file), URL, URLSearchParams, console,
  require: name => name === 'node:test' ? {test: () => {}} : localRequire(name),
  emit: value => observations.push(value),
};
vm.runInNewContext(source + `
(async () => {
 const f = httpFixture();
 await httpRead(f, 4);
 const delayed = await f.hooks.request({url:'/workflow?id=9',method:'get',headers:{}});
 const write = await f.hooks.request({url:'/workflow',method:'put',headers:{},data:{id:9,flowId:'flow-nine',data:{nodes:[{id:'new'}],edges:[]}}});
 const fresh = {id:9,flowId:'flow-nine',revision:5,spaceId:5,data:'{"nodes":[{"id":"new"}],"edges":[]}'};
 f.hooks.response({config:write,data:{code:0,data:fresh},headers:{etag:'"5"'}});
 const stale = {...fresh,revision:4,data:'{"nodes":[{"id":"old"}],"edges":[]}'};
 const delivered = f.hooks.response({config:delayed,data:{code:0,data:stale},headers:{etag:'"4"'}});
 const overwrite = await f.hooks.request({url:'/workflow',method:'put',headers:{},data:{id:delivered.id,flowId:delivered.flowId,data:JSON.parse(delivered.data)}});
 emit({probe:'stale-response-delivery',deliveredRevision:delivered.revision,deliveredNode:JSON.parse(delivered.data).nodes[0].id,oldDraftSaveCondition:overwrite.headers['If-Match'],network:false});
 const cache = fixture(); load(cache);
 const escaped = request(cache,'put','/../../workflow',{id:9});
 const axios = require('axios');
 const uri = axios.getUri({...escaped.config,baseURL:centre});
 emit({probe:'dot-segment-prefix-escape',requestUrl:escaped.config.url,attachedCondition:escaped.config.headers['If-Match'],axiosUri:uri,actualResolvedPath:new URL(uri,origin).pathname,network:false});
})();`, sandbox, {filename:file});
setImmediate(() => {
  if (observations.length !== 2) process.exitCode = 1;
  else for (const value of observations) console.log(JSON.stringify(value));
});
