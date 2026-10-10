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
const root = path.resolve(__dirname, '../src/components/workflow');
function load(file, names, globals) {
  const source = fs.readFileSync(path.join(root, file), 'utf8');
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
const file = 'nodes/question-answer/components/answer-settings.tsx';
const plain = value => JSON.parse(JSON.stringify(value));
function inputRef(nodeId) { return { schema: { value: { type: 'ref', content: { nodeId, name: 'query' } } } }; }
function fixture(params, edges, extraNodes = []) {
  let dirty = 0;
  const cleared = [];
  const store = create((set, get) => ({
    nodes: [{ id: 'qa', data: { nodeParam: params, inputs: [inputRef('start')] } }, ...extraNodes], edges,
    setNodes: change => set({ nodes: change(get().nodes) }),
    setEdges: change => set({ edges: typeof change === 'function' ? change(get().edges) : change }),
    removeNodeRef: (source, target, inputEdges) => {
      cleared.push([source, target]);
      refs.removeNodeRef(source, target, inputEdges, get);
    },
  }));
  const manager = create(() => ({ getCurrentStore: () => store, canPublishSetNot: () => dirty++ }));
  const useFlowsManager = selector => useStore(manager, selector);
  useFlowsManager.getState = manager.getState;
  const graph = load('utils/reactflowUtils.ts', ['findChildrenNodes', 'findParentNodes'], {});
  const refs = load('store/flow-function.ts', ['removeNodeRef'], { ...graph, cloneDeep, useFlowsManager });
  let switchProps;
  const component = load(file, ['UserMustAnswer'], {
    useMemo: React.useMemo, useFlowsManager, useTranslation: () => ({ t: key => key }),
    Switch: props => { switchProps = props; return null; }, Tooltip: props => props.children,
    answerSettingsParams: 'settings', uuid: () => 'new-default',
  }).UserMustAnswer;
  function render() {
    const data = store.getState().nodes.find(node => node.id === 'qa').data;
    renderToString(React.createElement(component, { data, handleChangeNodeParam: (fn, value) => {
      const nodes = cloneDeep(store.getState().nodes);
      fn(nodes.find(node => node.id === 'qa').data, value);
      store.setState({ nodes });
    } }));
    return switchProps;
  }
  return { store, render, cleared, dirty: () => dirty };
}
test('直接问答开启必须回答保留普通无handle连线和真实上游引用', () => {
  const edges = [{ id: 'incoming', source: 'start', target: 'qa' }, { id: 'outgoing', source: 'qa', target: 'end' }];
  const fx = fixture({ answerType: 'direct', needReply: false, optionAnswer: [] }, edges,
    [{ id: 'end', data: { inputs: [inputRef('qa')] } }]);
  fx.render().onChange(true);
  assert.deepEqual(plain(fx.store.getState().edges), edges);
  assert.equal(fx.store.getState().nodes[0].data.inputs[0].schema.value.content.nodeId, 'start');
  assert.equal(fx.store.getState().nodes[1].data.inputs[0].schema.value.content.nodeId, 'qa');
  assert.equal(fx.store.getState().nodes[0].data.nodeParam.needReply, true);
  assert.deepEqual(fx.cleared, []);
});
test('直接问答关闭必须回答不要求或制造选项配置', () => {
  const fx = fixture({ answerType: 'direct', needReply: true, directAnswer: { original: '保留' } }, []);
  fx.render().onChange(false);
  const params = fx.store.getState().nodes[0].data.nodeParam;
  assert.equal(params.needReply, false);
  assert.equal(Object.hasOwn(params, 'optionAnswer'), false);
  assert.equal(params.directAnswer.original, '保留');
});
test('选项默认分支fanout删除后用实际引用清理helper，另一个选项仍通达的目标引用保留', () => {
  const options = [{ id: 'default-handle', type: 1, name: 'default' }, { id: 'choice', type: 2, name: '原选项' }];
  const edges = [{ id: 'incoming', source: 'start', target: 'qa' },
    { id: 'default-a', source: 'qa', target: 'a', sourceHandle: 'default-handle' },
    { id: 'default-b', source: 'qa', target: 'b', sourceHandle: 'default-handle' },
    { id: 'choice-a', source: 'qa', target: 'a', sourceHandle: 'choice' },
    { id: 'downstream', source: 'b', target: 'down' }];
  const fx = fixture({ answerType: 'option', needReply: false, optionAnswer: options }, edges,
    ['a', 'b', 'down'].map(id => ({ id, data: { inputs: [inputRef('qa')] } })));
  fx.render().onChange(true);
  assert.deepEqual(fx.store.getState().edges.map(edge => edge.id), ['incoming', 'choice-a', 'downstream']);
  const nodes = fx.store.getState().nodes;
  assert.equal(nodes.find(node => node.id === 'a').data.inputs[0].schema.value.content.nodeId, 'qa');
  assert.equal(nodes.find(node => node.id === 'b').data.inputs[0].schema.value.content.nodeId, '');
  assert.equal(nodes.find(node => node.id === 'down').data.inputs[0].schema.value.content.nodeId, '');
  assert.deepEqual(fx.cleared, [['qa', 'b']]);
  assert.deepEqual(plain(nodes[0].data.nodeParam.optionAnswer), [options[1]]);
});
test('选项问答尚无默认分支时开启必须回答不删除无handle或其他选项连线', () => {
  const edges = [{ id: 'incoming', source: 'start', target: 'qa' },
    { id: 'choice', source: 'qa', target: 'end', sourceHandle: 'choice' }];
  const options = [{ id: 'choice', type: 2, name: '选项', extension: null }];
  const fx = fixture({ answerType: 'option', needReply: false, optionAnswer: options }, edges);
  fx.render().onChange(true);
  assert.deepEqual(plain(fx.store.getState().edges), edges);
  assert.deepEqual(plain(fx.store.getState().nodes[0].data.nodeParam.optionAnswer), options);
  assert.deepEqual(fx.cleared, []);
});
test('选项问答允许不回答时新增唯一原形默认选项，重复处理保留ID和既有分支', () => {
  const choice = { id: 'choice', type: 2, name: '选项', content: '原内容', extension: { preserve: true } };
  const edges = [{ id: 'choice', source: 'qa', target: 'end', sourceHandle: 'choice' }];
  const fx = fixture({ answerType: 'option', needReply: true, optionAnswer: [choice] }, edges);
  fx.render().onChange(false);
  const first = plain(fx.store.getState().nodes[0].data.nodeParam.optionAnswer);
  assert.deepEqual(first, [choice, { id: 'option-one-of::new-default', name: 'default', type: 1, content: '', content_type: 'string' }]);
  fx.render().onChange(false);
  assert.deepEqual(plain(fx.store.getState().nodes[0].data.nodeParam.optionAnswer), first);
  assert.deepEqual(plain(fx.store.getState().edges), edges);
});
for (const retry of [false, true]) {
  test(`实际${retry ? '重试次数' : '会话超时'}控件保留2到5范围、清空null及失焦原默认3`, () => {
    const data = { nodeParam: { answerType: 'option', timeout: 3,
      directAnswer: { maxRetryCounts: 3, extension: '原字段' } } };
    let slider;
    let input;
    const componentName = retry ? 'MaxRetrySettings' : 'ConversationTimeout';
    const components = load(file, retry ? ['updateRetryCount', componentName] : [componentName], {
      useTranslation: () => ({ t: key => key }), Tooltip: props => props.children,
      Slider: props => { slider = props; return null; }, FlowInputNumber: props => { input = props; return null; },
      answerSettingsParams: 'settings',
    });
    const props = { data, handleChangeNodeParam: (fn, value) => fn(data, value) };
    function render() { renderToString(React.createElement(components[componentName], props)); }
    const value = () => retry ? data.nodeParam.directAnswer.maxRetryCounts : data.nodeParam.timeout;
    render();
    assert.equal(slider.min, 2); assert.equal(slider.max, 5); assert.equal(slider.step, 1);
    assert.equal(input.precision, 0); assert.equal(input.min, 2); assert.equal(input.max, 5);
    slider.onChange(4); assert.equal(value(), 4);
    input.onChange(null); assert.equal(value(), null);
    render(); assert.equal(input.value, null); assert.equal(slider.value, undefined);
    input.onBlur(); assert.equal(value(), 3);
    assert.equal(data.nodeParam.directAnswer.extension, '原字段');
    if (retry) {
      assert.equal(renderToString(React.createElement(components[componentName], { ...props,
        data: { nodeParam: { answerType: 'direct' } } })), '');
    }
  });
}
