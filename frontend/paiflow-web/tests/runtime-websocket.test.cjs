const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

// 直接执行原工具模块的转译结果，仅隔离未调用的uuid和HTTP模块依赖。
const source = fs.readFileSync(path.join(__dirname, '../src/components/workflow/utils/index.ts'), 'utf8');
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
function load(baseURL, runtime = {}, browser = true) {
  const exports = {};
  const sandbox = {
    exports, URL,
    window: browser ? { location: { origin: 'https://paiflow.example' }, __APP_CONFIG__: runtime } : undefined,
    require: name => {
      if (name === 'uuid') return { v4: () => 'unused' };
      if (name === '@/utils/http') return { baseURL };
      throw new Error(`Unexpected import ${name}`);
    },
  };
  vm.runInNewContext(compiled, sandbox, { filename: 'workflow-utils.js' });
  return exports;
}
test('relative centre URL keeps path and follows browser TLS', () => {
  assert.equal(load('/paiflow/legacy').getWsFixedUrl('/stream'), 'wss://paiflow.example/paiflow/legacy/stream');
});
test('HTTP centre and configured independent WS gateway both preserve their path', () => {
  assert.equal(load('http://localhost:19001/paiflow/legacy/').getWsFixedUrl('/stream'), 'ws://localhost:19001/paiflow/legacy/stream');
  assert.equal(load('/unused', { WS_BASE_URL: 'wss://gateway.example/paiflow/' }).getWsFixedUrl('/stream'), 'wss://gateway.example/paiflow/stream');
});
test('invalid gateway credentials and query cannot enter base URL', () => {
  for (const url of ['https://gateway.example/', 'wss://user:pass@gateway.example/', 'wss://gateway.example/?token=example', 'wss://gateway.example/#fragment']) {
    assert.throws(() => load('/paiflow/legacy', { WS_BASE_URL: url }).getWsFixedUrl('/stream'));
  }
});
test('server execution fails explicitly instead of contacting old private hosts', () => {
  assert.throws(() => load('/paiflow/legacy', {}, false).getWsFixedUrl('/stream'));
});
