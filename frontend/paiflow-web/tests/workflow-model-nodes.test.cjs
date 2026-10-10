const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const React = require('react');
const { renderToString } = require('react-dom/server');
const root = path.resolve(__dirname, '../src/components/workflow');
function component(file, name, globals) {
  const source = fs.readFileSync(path.join(root, file), 'utf8');
  const ast = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const statement = ast.statements.find(item => ts.isVariableStatement(item)
    ? item.declarationList.declarations.some(declaration => declaration.name.getText(ast) === name)
    : ts.isFunctionDeclaration(item) && item.name?.text === name);
  assert.ok(statement, `实际组件 ${name}`);
  const exports = {};
  vm.runInNewContext(ts.transpileModule(statement.getText(ast) + `\nexport { ${name} };`, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React },
  }).outputText, { exports, React, useTranslation: () => ({ t: key => key }), ...globals });
  return exports[name];
}
test('实际模型格式切换回text保持唯一固定输出和已有业务输出', () => {
  let select;
  let serial = 0;
  const refs = [];
  const section = component('nodes/llm/index.tsx', 'OutputSection', {
    uuid: () => `new-${++serial}`,
    useNodeCommon: () => ({ isThinkModel: true, currentNode: undefined }),
    useFlowsManager: selector => selector({ canvasesDisabled: false,
      getCurrentStore: () => selector => selector({ updateNodeRef: id => refs.push(id) }) }),
    Outputs: props => props.children,
    FlowSelect: props => { select = props; return null; },
    // 此用例检查实际组件输出重建，输出校验/异常默认生成是隔离边界。
    checkedNodeOutputData: () => true,
    generateOrUpdateObject: outputs => Object.fromEntries(outputs.map(item => [item.name, item.schema.default])),
    isJSON: () => false,
  });
  const business = { id: 'business', name: 'amount', schema: { type: 'integer', default: 0 } };
  const data = { nodeParam: { respFormat: 2 }, outputs: [
    { id: 'reason', name: 'REASONING_CONTENT', schema: { type: 'string' } },
    { id: 'text', name: 'output', schema: { type: 'string' } }, business,
  ] };
  renderToString(React.createElement(section, { id: 'spark-llm::actual', data,
    handleChangeNodeParam: (fn, value) => fn(data, value) }));
  for (const value of [0, 2, 0]) select.onChange(value);
  assert.deepEqual(Array.from(data.outputs, item => item.name), ['REASONING_CONTENT', 'output', 'amount']);
  assert.equal(data.outputs[2], business);
  assert.deepEqual(refs, ['spark-llm::actual', 'spark-llm::actual']);
});
test('实际参数提取类型编辑能补齐缺失schema并保留已有schema扩展', () => {
  const selects = [];
  const outputs = [{ id: 'missing', name: 'age' },
    { id: 'existing', name: 'score', schema: { type: 'number', default: 0, description: '原说明' } }];
  const section = component('nodes/extractor-parameterNode/components/OutputParams.tsx', 'index', {
    useFlowsManager: selector => selector({ canvasesDisabled: false }),
    useNodeCommon: () => ({ outputs, renderTypeInput: () => null,
      handleChangeOutputParam: (id, fn, value) => fn(outputs.find(item => item.id === id), value),
      handleCustomOutputGenerate: () => {}, handleAddOutputLine: () => {}, handleRemoveOutputLine: () => {} }),
    FlowSelect: props => { selects.push(props); return null; },
    FlowNodeInput: () => null, remove: 'remove', inputAddIcon: 'add',
  });
  renderToString(React.createElement(section, { id: 'extractor-parameter::actual', data: { nodeParam: {} } }));
  selects[0].onChange('integer');
  selects[1].onChange('integer');
  assert.equal(outputs[0].schema.type, 'integer');
  assert.deepEqual(JSON.parse(JSON.stringify(outputs[1].schema)), { type: 'integer', default: 0, description: '原说明' });
});
test('实际决策意图编辑沿现有节点setter保存目标字段并保留默认分支及其他意图', () => {
  const intents = [{ id: 'first', intentType: 2, name: '原意图', description: '原描述' },
    { id: 'default', intentType: 1, name: 'DEFAULT', description: '' }];
  let node = { id: 'decision-making::actual', data: { nodeParam: { intentChains: intents } } };
  let sectionProps;
  let saves = 0;
  let dirty = 0;
  const state = { setNode: (id, update) => { assert.equal(id, node.id); node = update(node); },
    delayCheckNode: () => {}, takeSnapshot: () => {}, edges: [], setEdges: () => {}, removeNodeRef: () => {} };
  const detail = component('nodes/decision-making/index.tsx', 'DecisionMakingDetail', {
    memo: value => value, useCallback: React.useCallback, useMemo: React.useMemo,
    cloneDeep: require('lodash').cloneDeep,
    useNodeCommon: () => ({ handleChangeNodeParam: () => {} }),
    useFlowsManager: selector => selector({ getCurrentStore: () => selector => selector(state),
      canvasesDisabled: false, autoSaveCurrentFlow: () => saves++, canPublishSetNot: () => dirty++ }),
    ModelSection: () => null, SingleInput: () => null, FixedOutputs: () => null,
    ExceptionHandling: () => null, AdvancedConfigSection: () => null,
    IntentSection: props => { sectionProps = props; return null; },
  });
  renderToString(React.createElement(detail, { id: node.id, data: node.data }));
  sectionProps.handleChangeParam('first', (intent, value) => { intent.name = value; }, '修改意图');
  sectionProps.handleChangeParam('first', (intent, value) => { intent.description = value; }, '修改描述');
  assert.equal(node.data.nodeParam.intentChains[0].name, '修改意图');
  assert.equal(node.data.nodeParam.intentChains[0].description, '修改描述');
  assert.equal(node.data.nodeParam.intentChains[1].name, 'DEFAULT');
  assert.equal(saves, 2);
  assert.equal(dirty, 2);
});
for (const missing of [true, false]) {
  test(`实际决策添加意图${missing ? '补齐缺失数组并保存' : '保持DEFAULT为最后分支'}`, () => {
    const original = [{ id: 'existing', intentType: 2, name: '原意图', description: '原描述' },
      { id: 'default', intentType: 1, name: 'DEFAULT', description: '' }];
    let node = { id: 'decision-making::actual', data: { nodeParam: missing ? {} : { intentChains: original } } };
    let content;
    let snapshots = 0;
    let dirty = 0;
    const section = component('nodes/decision-making/index.tsx', 'IntentSection', {
      useCallback: React.useCallback, cloneDeep: require('lodash').cloneDeep, uuid: () => 'new-intent',
      FLowCollapse: props => { content = props.content; return props.content; },
      FlowNodeInput: () => null, FlowNodeTextArea: () => null, remove: 'remove',
    });
    renderToString(React.createElement(section, { id: node.id, intentChains: missing ? [] : original,
      setNode: (id, update) => { assert.equal(id, node.id); node = update(node); },
      setEdges: () => {}, takeSnapshot: () => snapshots++, removeNodeRef: () => {}, edges: [],
      canPublishSetNot: () => dirty++, handleChangeParam: () => {}, canvasesDisabled: false, delayCheckNode: () => {} }));
    content.props.children[1].props.onClick();
    const chains = node.data.nodeParam.intentChains;
    assert.equal(chains.length, missing ? 1 : 3);
    assert.equal(chains[missing ? 0 : 1].id, 'intent-one-of::new-intent');
    if (!missing) {
      assert.equal(chains[0].name, '原意图');
      assert.equal(chains[2].id, 'default');
      assert.equal(chains[2].name, 'DEFAULT');
    }
    assert.equal(snapshots, 1);
    assert.equal(dirty, 1);
  });
}
