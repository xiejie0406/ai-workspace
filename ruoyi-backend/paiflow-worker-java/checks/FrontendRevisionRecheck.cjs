// 修复后独立复验；读取真实helper/http.ts及既有沙箱，保留首轮失败探针与日志。
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const {createRequire} = require('node:module');
const file = path.resolve(__dirname, '../../../frontend/paiflow-web/tests/workflow-revision.test.cjs');
const requireLocal = createRequire(file);
const sandbox = {__dirname:path.dirname(file),URL,URLSearchParams,console,setImmediate,require:name=>name==='node:test'?{test:()=>{}}:requireLocal(name),emit:value=>console.log(JSON.stringify(value)),done:promise=>promise.catch(error=>{console.error(error);process.exitCode=1;})};
vm.runInNewContext(fs.readFileSync(file,'utf8') + `
done((async()=>{
 const f=httpFixture(); await httpRead(f,4);
 const delayed=await f.hooks.request({url:'/workflow?id=9',method:'get',headers:{}});
 const write=await f.hooks.request({url:'/workflow',method:'put',headers:{},data:{id:9,flowId:'flow-nine',data:{nodes:[{id:'new'}],edges:[]}}});
 const current={id:9,flowId:'flow-nine',revision:5,spaceId:5,data:'{"nodes":[{"id":"new"}],"edges":[]}'};
 assert.equal(f.hooks.response({config:write,data:{code:0,data:current},headers:{etag:'"5"'}}),current);
 let delivered=false;
 await assert.rejects(Promise.resolve(f.hooks.response({config:delayed,data:{code:0,data:{...current,revision:4,data:'{"nodes":[{"id":"old"}],"edges":[]}'}},headers:{etag:'"4"'}})).then(()=>delivered=true),error=>error.code===409);
 const next=await f.hooks.request({url:'/workflow',method:'put',headers:{},data:{id:9,flowId:'flow-nine',data:JSON.parse(current.data)}});
 assert.equal(delivered,false);assert.equal(next.headers['If-Match'],'"5"');assert.equal(next.data.data.nodes[0].id,'new');
 emit({probe:'stale-response-recheck',oldResponseRejected:!delivered,currentCondition:next.headers['If-Match'],currentNode:next.data.data.nodes[0].id,network:false});
 const cache=fixture();load(cache);agentLoad(cache,9,2,true);
 const paths=['/../../workflow','/a/../workflow','/%2e%2e/workflow','/%2e./workflow','/a/%2E%2E/workflow','/workflow#fragment'];
 for(const url of paths){const req=request(cache,'put',url,{id:9});assert.equal(req.ticket,undefined);assert.equal(req.config.headers['If-Match'],undefined);}
 const agentPaths=['/../../bot/update','/x/%2e%2e/bot/update'];
 for(const url of agentPaths){const req=request(cache,'post',url,{botId:9});assert.equal(req.ticket,undefined);assert.equal(req.config.headers['If-Match'],undefined);assert.equal(req.config.headers['X-Workflow-Revision'],undefined);}
 assert.equal(request(cache,'put','/workflow?q=../%2e%2e',{id:9}).config.headers['If-Match'],'"4"');
 emit({probe:'exact-path-recheck',rejectedPaths:paths.length+agentPaths.length,queryCompatibility:true,network:false});
})());`,sandbox,{filename:file});
