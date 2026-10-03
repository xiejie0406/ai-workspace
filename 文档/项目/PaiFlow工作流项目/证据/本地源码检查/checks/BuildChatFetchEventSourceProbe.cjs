// 只执行已安装的原 fetch-event-source 生命周期；全部 fetch/timer/body 均为内存桩。
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '../../../../../../');
const relative = 'frontend/paiflow-web/node_modules/@microsoft/fetch-event-source/lib/esm/fetch.js';
const source = fs.readFileSync(path.join(root, relative), 'utf8');
const sha = b => crypto.createHash('sha256').update(b).digest('hex');
const before = sha(source);
const script = source.replace(/^import .*?;\r?\n/m, '').replace(/\bexport /g, '') + '\nthis.actualFetchEventSource = fetchEventSource;';
const settle = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };
function runtime(fetch, getBytes) {
  const timers = [];
  const sandbox = {
    AbortController,
    document: { addEventListener() {}, removeEventListener() {} },
    window: { fetch, clearTimeout() {}, setTimeout(fn, interval) { timers.push({fn, interval}); return timers.length; } },
    getBytes,
    getLines: cb => cb,
    getMessages: (onId, onRetry, onMessage) => ({onId, onRetry, onMessage}),
  };
  vm.runInNewContext(script, sandbox, {filename: relative});
  return {call: sandbox.actualFetchEventSource, timers};
}
(async () => {
  const cases = [];
  {
    const controller = new AbortController();
    let fetchCount = 0;
    const r = runtime(async () => { fetchCount++; throw new Error('memory transport disconnect'); }, async () => {});
    const promise = r.call('/workflow/chat', {method:'POST', body:'{"flow_id":"fixture","inputs":{},"chatId":"client-alias","regen":false}',signal:controller.signal,openWhenHidden:true,onerror(){controller.abort();}});
    await settle();
    const afterFirstError = {fetchCount, inputSignalAborted:controller.signal.aborted, queuedTimers:r.timers.length};
    r.timers[0].fn();
    await settle();
    await promise;
    cases.push({name:'original-ui-abort-inside-onerror-still-schedules-retry',afterFirstError,afterTimer:{fetchCount,inputSignalAborted:controller.signal.aborted,queuedTimers:r.timers.length},assertionPassed:fetchCount===2 && afterFirstError.inputSignalAborted && afterFirstError.queuedTimers===1});
  }
  {
    const requests = [];
    let bodies = 0;
    const body = '{"flow_id":"fixture","inputs":{"question":"memory"},"chatId":"client-alias","regen":false}';
    const r = runtime(async (_url, options) => {requests.push({method:options.method,body:options.body,headers:{...options.headers}});return {headers:{get:()=> 'text/event-stream'},body:{}};}, async (_body, callbacks) => {bodies++; if(bodies===1){callbacks.onId('7');throw new Error('memory interrupted after event');}});
    const promise = r.call('/workflow/chat', {method:'POST',body,openWhenHidden:true,onerror(){return 0;}});
    await settle();
    r.timers[0].fn();
    await promise;
    cases.push({name:'library-reconnect-post-keeps-body-and-last-event-id',requests,assertionPassed:requests.length===2 && requests[0].body===requests[1].body && requests[1].headers['last-event-id']==='7'});
  }
  {
    let fetchCount=0, onMessageCount=0;
    const r=runtime(async()=>{fetchCount++;return{headers:{get:()=> 'text/event-stream'},body:{}};},async()=>{});
    await r.call('/workflow/chat',{method:'POST',body:'{}',openWhenHidden:true,onmessage(){onMessageCount++;}});
    cases.push({name:'bare-sse-eof-resolves-with-no-terminal-message',fetchCount,onMessageCount,queuedTimers:r.timers.length,promiseResolved:true,assertionPassed:fetchCount===1 && onMessageCount===0 && r.timers.length===0});
  }
  const after = sha(fs.readFileSync(path.join(root,relative)));
  const result={scope:'实际已安装fetch-event-source源码＋内存fetch/timer/解析回调，不执行页面、中心、Provider、HTTP或业务运行',source:{path:relative,sha256Before:before,sha256After:after,changed:before!==after},cases,passed:cases.filter(c=>c.assertionPassed).length,total:cases.length,realHttpRequests:0,centreCalls:0,workflowRuns:0};
  if(result.passed!==result.total || result.source.changed) process.exitCode=1;
  fs.writeFileSync(path.join(__dirname,'原调试构建聊天续接-fetch-event-source内存探针.json'),JSON.stringify(result,null,2)+'\n','utf8');
  process.stdout.write(JSON.stringify(result,null,2)+'\n');
})().catch(error=>{process.stderr.write(String(error.stack)+'\n');process.exitCode=1;});
