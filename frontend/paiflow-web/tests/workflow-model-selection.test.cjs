const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const React = require('react');
const { renderToString } = require('react-dom/server');
const root = path.resolve(__dirname, '../src/components/workflow/nodes/components');
function load(file, names, globals) {
  const source = fs.readFileSync(path.join(root, file), 'utf8');
  const ast = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const selected = ast.statements.filter(item => ts.isVariableStatement(item)
    ? item.declarationList.declarations.some(declaration => names.includes(declaration.name.getText(ast)))
    : ts.isFunctionDeclaration(item) && names.includes(item.name?.text));
  const exports = {};
  vm.runInNewContext(ts.transpileModule(selected.map(item => item.getText(ast)).join('\n') + `\nexport { ${names.join(',')} };`, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React },
  }).outputText, { exports, React, ...globals });
  return exports;
}
function deferred() { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; }
function modelHelpers() {
  return load('model-params/index.tsx', ['modelRecord', 'modelNumber', 'readModelInfo', 'readModelFields', 'serviceModelFields', 'modelParameterKey'], {});
}
test('实际模型选择A后B，晚到A的默认配置不会覆盖当前B及触发保存', async () => {
  const requests = [];
  const replies = { A: deferred(), B: deferred() };
  let node = { id: 'decision-making::actual', data: { nodeParam: {} } };
  let saves = 0;
  const state = { updateNodeRef: () => {}, setNode: (id, update) => { assert.equal(id, node.id); node = update(node); } };
  const hooks = load('model-select/index.tsx', ['useModelSelect'], {
    ...modelHelpers(), useRef: () => ({ current: 0 }),
    useNodeCommon: () => ({ currentNode: undefined }), useTranslation: () => ({ t: key => key }),
    useUserStore: selector => selector({ user: { uid: 42 } }),
    useFlowsManager: selector => selector({ getCurrentStore: () => selector => selector(state), autoSaveCurrentFlow: () => saves++ }),
    useMemoizedFn: fn => fn, cloneDeep: require('lodash').cloneDeep,
    getCustomModelConfigDetail: (id, source) => { requests.push(`/llm/self-model-config?id=${id}&llmSource=${source}`); return replies[id].promise; },
    getModelConfigDetail: () => { throw new Error('此正例只走原自定义模型接口'); },
  });
  const models = [{ id: 'A', llmId: 'A', llmSource: 0, name: '原A' }, { id: 'B', llmId: 'B', llmSource: 0, name: '原B' }];
  const { handleModelChange } = hooks.useModelSelect(node.id, models);
  handleModelChange(node.data, 'A');
  handleModelChange(node.data, 'B');
  replies.B.resolve([{ key: 'temperature', constraintType: 'range', default: 0 }]);
  await Promise.resolve(); await Promise.resolve();
  assert.equal(node.data.nodeParam.extraParams.temperature, 0);
  assert.equal(saves, 1);
  replies.A.resolve([{ key: 'temperature', constraintType: 'range', default: 0.9 }]);
  await Promise.resolve(); await Promise.resolve();
  assert.equal(node.data.nodeParam.llmId, 'B');
  assert.equal(node.data.nodeParam.extraParams.temperature, 0);
  assert.equal(saves, 1);
  assert.deepEqual(requests, ['/llm/self-model-config?id=A&llmSource=0', '/llm/self-model-config?id=B&llmSource=0']);
});
test('实际参数配置展示映射原别名时保持共享模型目录原始key和默认零值', () => {
  const field = { key: 'max_tokens', name: '最大token', constraintType: 'range', default: 0 };
  const model = { llmSource: 2, serviceId: 'service', config: { serviceBlock: { service: [{ fields: [field] }] } } };
  let configs;
  const hooks = load('model-params/index.tsx', ['useConfigs'], { ...modelHelpers(), useEffect: fn => fn() });
  hooks.useConfigs(model, value => { configs = value; });
  assert.equal(configs[0].key, 'maxTokens');
  assert.equal(configs[0].default, 0);
  assert.equal(field.key, 'max_tokens');
});
test('相同模型重复选择仍只接受最后一次请求的默认值', async () => {
  const replies = [deferred(), deferred()];
  let calls = 0;
  let saves = 0;
  let node = { id: 'decision-making::actual', data: { nodeParam: {} } };
  const state = { updateNodeRef: () => {}, setNode: (id, update) => { node = update(node); } };
  const hooks = load('model-select/index.tsx', ['useModelSelect'], {
    ...modelHelpers(), useRef: () => ({ current: 0 }),
    useNodeCommon: () => ({}), useTranslation: () => ({ t: key => key }),
    useUserStore: selector => selector({ user: { uid: 42 } }),
    useFlowsManager: selector => selector({ getCurrentStore: () => selector => selector(state), autoSaveCurrentFlow: () => saves++ }),
    useMemoizedFn: fn => fn, cloneDeep: require('lodash').cloneDeep,
    getCustomModelConfigDetail: () => replies[calls++].promise,
  });
  const { handleModelChange } = hooks.useModelSelect(node.id, [{ id: 'A', llmId: 'A', llmSource: 0 }]);
  handleModelChange(node.data, 'A'); handleModelChange(node.data, 'A');
  replies[1].resolve([{ key: 'temperature', constraintType: 'range', default: 0 }]);
  await Promise.resolve(); await Promise.resolve();
  replies[0].resolve([{ key: 'temperature', constraintType: 'range', default: 0.9 }]);
  await Promise.resolve(); await Promise.resolve();
  assert.equal(calls, 2); assert.equal(saves, 1);
  assert.equal(node.data.nodeParam.extraParams.temperature, 0);
});
test('实际source2模型默认参数使用service fallback和原别名，保留false/0且不修改HTTP对象', async () => {
  const fields = [{ key: 'max_tokens', constraintType: 'range', default: 0 },
    { key: 'search_disable', constraintType: 'switch', default: false },
    { key: 'top_k', constraintType: 'range', default: 0 }, { key: 'private', constraintType: 'hidden', default: 123 }];
  const original = { config: { serviceBlock: { '@@serviceId@@': [{ fields }] } } };
  let node = { id: 'decision-making::actual', data: { nodeParam: { extraParams: { stale: 8 }, source: 'openai' } } };
  let saved = 0;
  let request;
  const hooks = load('model-select/index.tsx', ['useModelSelect'], {
    ...modelHelpers(), useRef: () => ({ current: 0 }),
    useNodeCommon: () => ({}), useTranslation: () => ({ t: key => key }),
    useUserStore: selector => selector({ user: { uid: 42 } }),
    useFlowsManager: selector => selector({ getCurrentStore: () => selector => selector({ updateNodeRef: () => {},
      setNode: (id, update) => { node = update(node); } }), autoSaveCurrentFlow: () => saved++ }),
    useMemoizedFn: fn => fn, cloneDeep: require('lodash').cloneDeep,
    getModelConfigDetail: (id, source) => { request = `/llm/inter1?id=${id}&llmSource=${source}`; return Promise.resolve(original); },
  });
  hooks.useModelSelect(node.id, [{ id: 7, llmId: 8, llmSource: 2, serviceId: 'service' }]).handleModelChange(node.data, 8);
  await Promise.resolve(); await Promise.resolve();
  assert.equal(request, '/llm/inter1?id=8&llmSource=2');
  assert.equal(node.data.nodeParam.maxTokens, 0); assert.equal(node.data.nodeParam.topK, 0);
  assert.equal(node.data.nodeParam.searchDisable, false);
  assert.equal(node.data.nodeParam.source, undefined); assert.equal(node.data.nodeParam.extraParams, undefined);
  assert.equal(node.data.nodeParam.private, undefined); assert.equal(saved, 1);
  assert.deepEqual(fields.map(field => field.key), ['max_tokens', 'search_disable', 'top_k', 'private']);
});
for (const source of [0, 2]) {
  test(`实际source${source}参数面板保存范围/开关，null失焦恢复default0`, () => {
    const range = { key: 'temperature', name: '温度', constraintType: 'range', default: 0,
      constraintContent: [{ name: 0 }, { name: 1 }], precision: 0.1 };
    const toggle = { key: 'searchDisable', constraintType: 'switch', default: false };
    const data = { nodeParam: source === 0 ? { extraParams: { temperature: 0, keep: '原扩展' } } : { temperature: 0, keep: '原扩展' } };
    const items = [];
    const panel = load('model-params/index.tsx', ['ModelParams'], {
      ...modelHelpers(), useTranslation: () => ({ t: key => key }), memo: value => value,
      useRef: () => ({ current: null }), useState: () => [[range, toggle], () => {}], useMemoizedFn: fn => fn,
      useConfigs: () => {}, useClickOutside: () => {}, debuggerIcon: 'debugger', close: 'close',
      ParamItem: props => { items.push(props); return null; },
    }).ModelParams;
    renderToString(React.createElement(panel, { currentSelectModel: { llmSource: source }, nodeParam: data.nodeParam,
      setShowModelParmas: () => {}, handleChangeNodeParam: (fn, value) => fn(data, value) }));
    let input;
    let slider;
    let switchProps;
    const controls = load('model-params/index.tsx', ['ParamRange', 'ParamSwitch'], {
      ...modelHelpers(), Slider: props => { slider = props; return null; },
      Switch: props => { switchProps = props; return null; },
      FlowInputNumber: props => { input = props; return null; },
    });
    renderToString(React.createElement(controls.ParamRange, items[0]));
    assert.equal(slider.value, 0); assert.equal(input.value, 0);
    slider.onChange(0.5);
    assert.equal(source === 0 ? data.nodeParam.extraParams.temperature : data.nodeParam.temperature, 0.5);
    input.onChange(null);
    assert.equal(source === 0 ? data.nodeParam.extraParams.temperature : data.nodeParam.temperature, null);
    renderToString(React.createElement(controls.ParamRange, { ...items[0], nodeParam: data.nodeParam }));
    assert.equal(input.value, source === 0 ? null : undefined, '保留原nullish选择和受控清空值');
    input.onBlur();
    assert.equal(source === 0 ? data.nodeParam.extraParams.temperature : data.nodeParam.temperature, 0);
    renderToString(React.createElement(controls.ParamSwitch, items[1]));
    switchProps.onChange(true);
    assert.equal(source === 0 ? data.nodeParam.extraParams.searchDisable : data.nodeParam.searchDisable, source === 0);
    assert.equal(source === 0 ? data.nodeParam.extraParams.keep : data.nodeParam.keep, '原扩展');
  });
}
