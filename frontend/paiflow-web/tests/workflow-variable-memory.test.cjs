const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const React = require('react');
const { renderToString } = require('react-dom/server');
const { create, useStore } = require('zustand');
const cloneDeep = require('lodash').cloneDeep;
const file = path.resolve(__dirname, '../src/components/workflow/nodes/variable-memory/index.tsx');
const plain = value => JSON.parse(JSON.stringify(value));
function load(names, globals) {
  const source = fs.readFileSync(file, 'utf8');
  const ast = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const statements = ast.statements.filter(item => ts.isVariableStatement(item)
    ? item.declarationList.declarations.some(declaration => names.includes(declaration.name.getText(ast)))
    : ts.isFunctionDeclaration(item) && names.includes(item.name?.text));
  const exports = {};
  vm.runInNewContext(ts.transpileModule(statements.map(item => item.getText(ast)).join('\n') + `\nexport { ${names.join(',')} };`, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React },
  }).outputText, { exports, React, ...globals });
  return exports;
}
function fixture(data) {
  const refs = [];
  const store = create(() => ({ nodes: [{ id: 'variable::one', nodeType: 'node-variable', data }], updateNodeRef: id => refs.push(id) }));
  const manager = create(() => ({ getCurrentStore: () => store, showIterativeModal: false, iteratorId: '', canvasesDisabled: false }));
  const common = ({ id, data: rendered }) => ({
    nodeParam: rendered.nodeParam, outputs: rendered.outputs,
    handleChangeNodeParam: (fn, value) => {
      const nodes = cloneDeep(store.getState().nodes);
      fn(nodes.find(node => node.id === id).data, value);
      store.setState({ nodes });
    },
  });
  const component = load(['VariableMemoryDetail'], {
    memo: React.memo, useMemo: React.useMemo, cloneDeep,
    useFlowsManager: selector => useStore(manager, selector),
    useFlowStore: selector => useStore(store, selector),
    useNodeCommon: common, useTranslation: () => ({ t: value => value }),
    FLowCollapse: props => React.createElement(React.Fragment, null, props.content),
    Inputs: () => null, Outputs: () => null, inputAddIcon: '', uuid: () => 'fresh-id',
  }).VariableMemoryDetail;
  function clicks() {
    const callbacks = {};
    const original = React.createElement;
    // Capture the real component's two mode click callbacks; SSR then runs its actual hooks.
    React.createElement = (type, props, ...children) => {
      if (props?.onClick && typeof children[0] === 'string' && children[0].startsWith('workflow.nodes.variableMemoryNode.')) {
        callbacks[children[0].endsWith('setVariableValue') ? 'set' : 'get'] = props.onClick;
      }
      return original(type, props, ...children);
    };
    try { renderToString(original(component, { id: 'variable::one', data: store.getState().nodes[0].data })); }
    finally { React.createElement = original; }
    return callbacks;
  }
  return { store, refs, clicks };
}
function configured(method) {
  return { nodeParam: { method, extension: { original: true } },
    inputs: [{ id: 'saved-input', name: 'value', schema: { type: 'string', value: { type: 'literal', content: '用户原值' } } }],
    outputs: [{ id: 'saved-output', name: 'saved-variable', refId: 'original-ref', schema: { type: 'string', default: '' } }],
    extension: '保留' };
}
for (const method of ['set', 'get']) {
  test(`重复点击当前${method}模式保留真实store已有参数及引用`, () => {
    const before = configured(method);
    const fx = fixture(cloneDeep(before));
    fx.clicks()[method]();
    assert.deepEqual(plain(fx.store.getState().nodes[0].data), before);
    assert.deepEqual(fx.refs, []);
  });
}
for (const method of ['set', 'get']) {
  test(`旧渲染闭包点击${method}时重核store当前method而不重置新配置`, () => {
    const fx = fixture(configured(method === 'set' ? 'get' : 'set'));
    const old = fx.clicks()[method];
    const current = configured(method);
    current.inputs[0].schema.value.content = '响应之后的新配置';
    fx.store.setState({ nodes: [{ ...fx.store.getState().nodes[0], data: cloneDeep(current) }] });
    old();
    assert.deepEqual(plain(fx.store.getState().nodes[0].data), current);
    assert.deepEqual(fx.refs, []);
  });
}
test('真实get到set切换沿用原输入初始化DTO及一次引用更新', () => {
  const fx = fixture(configured('get'));
  fx.clicks().set();
  const data = plain(fx.store.getState().nodes[0].data);
  assert.deepEqual(data.inputs, [{ id: 'fresh-id', name: 'input', schema: { type: 'string', value: { type: 'ref', content: {} } } }]);
  assert.deepEqual(data.outputs, []);
  assert.equal(data.nodeParam.method, 'set');
  assert.deepEqual(data.nodeParam.extension, { original: true });
  assert.deepEqual(fx.refs, ['variable::one']);
});
test('真实set到get切换沿用原输出初始化DTO，不制造引用更新', () => {
  const fx = fixture(configured('set'));
  fx.clicks().get();
  const data = plain(fx.store.getState().nodes[0].data);
  assert.deepEqual(data.inputs, []);
  assert.deepEqual(data.outputs, [{ id: 'fresh-id', name: '', schema: { type: '', description: '' }, required: true }]);
  assert.equal(data.nodeParam.method, 'get');
  assert.equal(data.extension, '保留');
  assert.deepEqual(fx.refs, []);
});
test('读取变量实际选择保留原目录去重、知识库数组类型与refId/名字/类型保存及失焦引用检查', () => {
  const calls = [];
  const store = create(() => ({ updateNodeRef: id => calls.push(['ref', id]), checkNode: id => calls.push(['check', id]) }));
  const manager = create(() => ({ getCurrentStore: () => store }));
  const graphSource = fs.readFileSync(path.resolve(__dirname, '../src/components/workflow/utils/reactflowUtils.ts'), 'utf8');
  const graphAst = ts.createSourceFile('utils.ts', graphSource, ts.ScriptTarget.Latest, true);
  const graphExports = {};
  const statement = graphAst.statements.find(item => ts.isVariableStatement(item) && item.declarationList.declarations.some(d => d.name.getText(graphAst) === 'isRefKnowledgeBase'));
  vm.runInNewContext(ts.transpileModule(statement.getText(graphAst), { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText, { exports: graphExports });
  let select;
  const component = load(['Outputs'], {
    useMemo: React.useMemo, useMemoizedFn: fn => fn, useFlowsManager: selector => useStore(manager, selector),
    FlowSelect: props => { select = props; return null; }, renderType: type => type,
    isRefKnowledgeBase: graphExports.isRefKnowledgeBase, remove: '',
  }).Outputs;
  const input = (id, name, type, content) => ({ id, name, schema: { type: 'string', value: { type, content } } });
  const setNode = { nodeType: 'node-variable', data: { nodeParam: { method: 'set' }, inputs: [
    input('first', 'text', 'literal', '原值'), input('duplicate', 'text', 'literal', '另一值'),
    input('empty', 'empty', 'literal', ''), input('missing', 'missing', 'ref', {}),
    input('knowledge', 'docs', 'ref', { name: 'output', nodeId: 'knowledge-base::one' }),
  ] } };
  const output = { id: 'out', name: '', schema: { type: '', description: '保留说明' }, extension: true };
  renderToString(React.createElement(component, { id: 'variable::read', outputs: [output], currentNodes: [setNode,
    { nodeType: 'node-variable', data: { nodeParam: { method: 'get' }, inputs: [input('ignored', 'ignored', 'literal', '值')] } }],
    handleChangeOutputParam: (id, fn, value) => { assert.equal(id, 'out'); fn(output, value); } }));
  assert.deepEqual(plain(select.options), [{ id: 'first', label: 'text', value: 'text', type: 'string' },
    { id: 'knowledge', label: 'docs', value: 'docs', type: 'array-string' }]);
  select.onChange('docs', select.options[1]);
  assert.deepEqual(output, { id: 'out', name: 'docs', refId: 'knowledge', schema: { type: 'array-string', description: '保留说明' }, extension: true });
  select.onBlur();
  assert.deepEqual(calls, [['ref', 'variable::read'], ['check', 'variable::read']]);
});
