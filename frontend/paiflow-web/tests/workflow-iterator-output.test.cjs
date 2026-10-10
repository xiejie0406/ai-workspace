const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const React = require('react');
const { renderToString } = require('react-dom/server');
const { cloneDeep } = require('lodash');

const root = path.resolve(__dirname, '../src/components/workflow');
function execute(source, imports, globals = {}) {
  const exports = {};
  vm.runInNewContext(ts.transpileModule(source, {
    fileName: 'actual-workflow.tsx',
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React },
  }).outputText, { exports, setTimeout, navigator: {}, document: {}, ...globals, require(name) {
    if (!(name in imports)) throw new Error(`Unexpected import ${name}`);
    return imports[name];
  } });
  return exports;
}
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const utilities = execute(read('utils/reactflowUtils.ts'), {
  lodash: require('lodash'), uuid: require('uuid'), ajv: require('ajv'),
  i18next: { default: { t: key => key } }, '@/utils': { isJSON: value => {
    try { JSON.parse(value); return true; } catch { return false; }
  } },
});
let activeManager;
const helpers = execute(read('store/flow-function.ts'), {
  '@/components/workflow/utils/reactflowUtils': utilities,
  uuid: require('uuid'), lodash: require('lodash'), reactflow: require('reactflow'),
  antd: { message: { error() {} } },
  './use-flows-manager': { default: { getState: () => activeManager.getState() } },
});
// 直接加载原 hook 函数的 AST 文本，用真实 React/ahooks/Zustand 执行，隔离 UI 和保存网络边界。
const hookSource = read('hooks/use-node-common.tsx');
const hookAst = ts.createSourceFile('use-node-common.tsx', hookSource, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
const actualHooks = hookAst.statements.filter(statement => ts.isVariableStatement(statement)
  && statement.declarationList.declarations.some(declaration => ['useNodeInfo', 'useNodeFunc', 'useNodeOutputRender', 'useNodeInputRender', 'useNodeModels', 'OutputTypeSelector'].includes(declaration.name.getText(hookAst))))
  .map(statement => statement.getText(hookAst)).join('\n');
const constantSource = read('constant/index.tsx');
const constantAst = ts.createSourceFile('constant.tsx', constantSource, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
const outputTypes = constantAst.statements.flatMap(statement => ts.isVariableStatement(statement)
  ? Array.from(statement.declarationList.declarations) : []).find(declaration => declaration.name.getText(constantAst) === 'originOutputTypeList');
const originOutputTypeList = vm.runInNewContext(outputTypes.initializer.getText(constantAst));
function fixture(file, names = ['first', 'middle', 'last'], graph = { nodes: [], edges: [] }) {
  const output = name => ({ id: `outer-${name}`, name, schema: { type: 'array-string', default: '' } });
  const input = name => ({ id: `inner-${name}`, name, schema: { type: 'string', value: {
    type: 'ref', content: { nodeId: 'inner-source', id: `value-${name}`, name },
  } } });
  const nodes = [
    { id: 'iteration::parent', nodeType: 'iteration', position: { x: 0, y: 0 }, data: {
      label: '迭代', nodeParam: {}, inputs: [{ id: 'outer-input', name: 'items', schema: { type: 'array-string', value: {
        type: 'ref', content: {},
      } } }], outputs: names.map(output),
    } },
    // 原内部 End 与父输出 ID 各自生成；名称是原运行引擎收集结果的键，顺序不构成关联。
    { id: 'iteration-node-end::child', nodeType: 'iteration-node-end', position: { x: 0, y: 0 }, data: {
      label: '内部结束', parentId: 'iteration::parent', nodeParam: { outputMode: 0 },
      inputs: [...names].reverse().map(input), outputs: [],
    } },
    { id: 'iteration-node-end::other', nodeType: 'iteration-node-end', position: { x: 0, y: 0 }, data: {
      label: '别的迭代结束', parentId: 'iteration::other', nodeParam: {}, inputs: [input('middle')], outputs: [],
    } },
    { id: 'node-end::consumer', nodeType: 'node-end', position: { x: 0, y: 0 }, data: {
      label: '外部结束', nodeParam: {}, inputs: [{ id: 'consumer', name: 'result', schema: { type: 'array-string', value: {
        type: 'ref', content: { nodeId: 'iteration::parent', id: 'outer-middle', name: 'middle' },
      } } }], outputs: [],
    } },
    { id: 'iteration-node-start::child', nodeType: 'iteration-node-start', position: { x: 0, y: 0 }, data: {
      parentId: 'iteration::parent', label: '内部开始', nodeParam: {}, inputs: [],
      outputs: [{ id: 'outer-input', name: 'items', schema: { type: 'string', default: '' } }],
    } },
    ...graph.nodes,
  ];
  const store = execute(read(`store/${file}`), { zustand: require('zustand'), './flow-function': {
    ...helpers, initialStatus: { ...helpers.initialStatus, nodes, edges: [{ id: 'consumer-edge', source: nodes[0].id, target: nodes[3].id }, ...graph.edges] },
  } }).default;
  const manager = require('zustand').create(() => ({ getCurrentStore: () => store, nodeList: [],
    agentModels: [], questionAnswerModels: [], decisionMakingModels: [], extractorParameterModels: [],
    sparkLlmModels: [{ llmId: 'text-id', name: '文本模型', metadata: { approved: true } },
      { llmId: 42, name: '数值ID模型', metadata: { approved: true } }, null, 'invalid', { llmId: {}, name: '无效ID' }],
    showIterativeModal: false, setNodeInfoEditDrawerlInfo() {}, setChatDebuggerResult() {},
    setVersionManagement() {}, setAdvancedConfiguration() {}, setOpenOperationResult() {},
    autoSaveCurrentFlow() {}, canPublishSetNot() {},
  }));
  activeManager = manager;
  let capturedTypeProps;
  const hookModule = execute(`
    import React, { useMemo, useState } from 'react';
    import { useMemoizedFn } from 'ahooks';
    import { cloneDeep } from 'lodash';
    import { v4 as uuid } from 'uuid';
    import useFlowsManager from 'manager';
    import { generateReferences, isObject, findPathById, deleteFieldByPath, findItemById, removeItemById, isBaseType, renderType, workflowValueToText } from 'utils';
    import { originOutputTypeList } from 'constant';
    import { FlowTypeCascader, FlowNodeTextArea } from 'ui';
    import { useTranslation } from 'translation';
    import { isJSON } from 'json';
    ${actualHooks}
    export { useNodeInfo, useNodeFunc, useNodeInputRender, OutputTypeSelector, useNodeModels };
  `, { react: { ...React, default: React }, ahooks: require('ahooks'), lodash: require('lodash'),
    uuid: require('uuid'), manager: { default: manager }, utils: utilities,
    constant: { originOutputTypeList }, translation: { useTranslation: () => ({ t: key => key }) },
    ui: { FlowTypeCascader: props => { capturedTypeProps = props; return null; }, FlowNodeTextArea: () => null },
    json: { isJSON: value => { try { JSON.parse(value); return true; } catch { return false; } } },
  });
  const hook = hookModule.useNodeFunc;
  let api;
  let inputApi;
  renderToString(React.createElement(() => {
    api = hook({ id: nodes[0].id, data: nodes[0].data });
    inputApi = hookModule.useNodeInputRender({ id: nodes[0].id, data: nodes[0].data });
    return null;
  }));
  const node = id => store.getState().nodes.find(item => item.id === id);
  return { store, api, inputApi, manager, hookModule, parent: () => node(nodes[0].id), end: () => node(nodes[1].id), other: () => node(nodes[2].id),
    outputTypeChange(output) {
      renderToString(React.createElement(hookModule.OutputTypeSelector, { id: nodes[0].id, data: nodes[0].data, output }));
      return capturedTypeProps.onChange;
    },
    modelInfo(parameters) {
      let result;
      renderToString(React.createElement(() => {
        result = hookModule.useNodeModels({ id: 'spark-llm::model', data: { nodeParam: parameters } });
        return null;
      }));
      return result;
    },
  };
}

function variableStatements(file, names) {
  const source = read(file);
  const ast = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  return ast.statements.filter(statement => ts.isVariableStatement(statement)
    && statement.declarationList.declarations.some(declaration => names.includes(declaration.name.getText(ast))))
    .map(statement => statement.getText(ast)).join('\n');
}

test('实际模板effect换挂载元素后，清理原元素监听而非新的ref元素', () => {
  const effects = [];
  const target = () => {
    const listeners = new Map();
    return { listeners, addEventListener(type, callback) { listeners.set(type, callback); },
      removeEventListener(type, callback) { if (listeners.get(type) === callback) listeners.delete(type); } };
  };
  const first = target();
  const next = target();
  const globalTarget = target();
  const module = execute(`import { useEffect } from 'effects';
    ${variableStatements('ui/flow-template-editor.tsx', ['useFlowTemplateEditorEffect'])}
    export { useFlowTemplateEditorEffect };`, { effects: { useEffect: effect => effects.push(effect) } }, { window: globalTarget });
  const editorRef = { current: first };
  const isPastingRef = { current: false };
  module.useFlowTemplateEditorEffect({ showDropdown: false, setFocusedKey() {}, setKeyboardNavigationActive() {},
    isFirstOpen: false, setIsFirstOpen() {}, beforeUpdateNodeInputData: { current: false }, updateNodeInputData: false,
    setTemplateValue() {}, setEditorContent() {}, value: '', editorRef, isArrowDownPressed: false,
    dropdownRef: { current: null }, treeData: [], focusedKey: null, keyboardNavigationActive: false,
    setIsArrowDownPressed() {}, setDropdownPosition() {}, setIsEmpty() {}, templateValue: '', isPastingRef, setShowDropdown() {},
  });
  // 只隔离React的提交调度；监听建立、原生事件回调与cleanup均执行生产effect主体。
  const cleanups = effects.map(effect => effect()).filter(callback => typeof callback === 'function');
  assert.equal(first.listeners.size, 2);
  let stopped = 0;
  first.listeners.get('keydown')({ ctrlKey: true, key: 'v', stopPropagation() { stopped++; } });
  assert.equal(isPastingRef.current, true);
  first.listeners.get('wheel')({ stopPropagation() { stopped++; } });
  assert.equal(stopped, 2);
  editorRef.current = next;
  cleanups.forEach(cleanup => cleanup());
  assert.equal(first.listeners.size, 0, '原DOM元素不得遗留keydown/wheel监听');
  assert.equal(next.listeners.size, 0);
  assert.equal(globalTarget.listeners.size, 0);
});

test('实际模板变量hook在无原生selection/已卸载parent时不改选区或抛错', () => {
  const module = execute(`import React, { useMemo } from 'react'; import { useMemoizedFn } from 'ahooks';
    import { cloneDeep } from 'lodash'; import { v4 as uuid } from 'uuid';
    ${variableStatements('utils/index.ts', ['findNodeByValue'])}
    ${variableStatements('ui/flow-template-editor.tsx', ['useFlowTemplateEditor'])}
    export { useFlowTemplateEditor };`, {
    react: { ...React, default: React }, ahooks: require('ahooks'), lodash: require('lodash'), uuid: require('uuid'),
  }, { window: { getSelection: () => null } });
  let api;
  let changes = 0;
  const range = { deleteContents() { changes++; } };
  renderToString(React.createElement(() => {
    api = module.useFlowTemplateEditor({ currentSelection: { current: range },
      willInertInfo: { current: { cursorPosition: 0, offset: { offsetLeft: 0, offsetRight: 0 } } },
      setShowDropdown() { changes++; }, setDropdownPosition() { changes++; }, setMatchingInformation() {},
      zoom: 100, references: [], inputs: [], treeData: [], parentRef: { current: null } });
    return null;
  }));
  assert.doesNotThrow(() => api.getCursorPosition());
  assert.doesNotThrow(() => api.insertOption('input.child', true));
  assert.equal(changes, 0);
});

test('实际模板变量正常前缀/嵌套对象/数组与原参考相同，定位保持原缩放公式', () => {
  const current = variableStatements('ui/flow-template-editor.tsx', ['useFlowTemplateEditor']);
  const referenceFile = path.resolve(__dirname, '../../../参考项目/PaiFlow-main/console/frontend/src/components/workflow/ui/flow-template-editor.tsx');
  const referenceText = fs.readFileSync(referenceFile, 'utf8');
  const ast = ts.createSourceFile('reference-editor.tsx', referenceText, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const reference = ast.statements.find(statement => ts.isVariableStatement(statement)
    && statement.declarationList.declarations.some(declaration => declaration.name.getText(ast) === 'useFlowTemplateEditor')).getText(ast);
  const references = [{ id: 'object', label: 'payload', value: 'payload', type: 'object', children: [
    { id: 'deep', label: 'deep', value: 'payload.deep', type: 'object', children: [{ id: 'leaf', label: 'leaf', value: 'payload.deep.leaf', type: 'string' }] },
  ] }, { id: 'array', label: 'items', value: 'items', type: 'array-object', children: [{ id: 'title', label: 'title', value: 'items.title', type: 'string' }] }];
  const inputs = [{ id: 'literal', name: 'literal', schema: { type: 'string', value: { type: 'literal', content: 'text' } } },
    { id: 'object-input', name: 'objectInput', schema: { type: 'object', value: { type: 'ref', content: { name: 'payload' } } } },
    { id: 'array-input', name: 'arrayInput', schema: { type: 'array-object', value: { type: 'ref', content: { name: 'items' } } } }];
  const run = source => {
    let position;
    const module = execute(`import React, { useMemo } from 'react'; import { useMemoizedFn } from 'ahooks';
      import { cloneDeep } from 'lodash'; import { v4 as uuid } from 'uuid';
      ${variableStatements('utils/index.ts', ['findNodeByValue'])} ${source} export { useFlowTemplateEditor };`, {
      react: { ...React, default: React }, ahooks: require('ahooks'), lodash: require('lodash'), uuid: require('uuid'),
    }, { window: { getSelection: () => ({ rangeCount: 1, getRangeAt: () => ({ getBoundingClientRect: () => ({ top: 30, left: 14 }) }) }) } });
    let api;
    renderToString(React.createElement(() => {
      api = module.useFlowTemplateEditor({ currentSelection: { current: null },
        willInertInfo: { current: { cursorPosition: 0, offset: { offsetLeft: 0, offsetRight: 0 } } },
        setShowDropdown() {}, setDropdownPosition(value) { position = value; }, setMatchingInformation() {},
        zoom: 50, references: cloneDeep(references), inputs: cloneDeep(inputs), treeData: references,
        parentRef: { current: { getBoundingClientRect: () => ({ top: 10, left: 4 }) } } });
      return null;
    }));
    api.getCursorPosition();
    const filters = ['obj', 'objectInput.', 'objectInput.deep.l', 'arrayInput[0].'].map(value =>
      api.filterArr(api.inputsOption, value, { offsetLeft: value.length, offsetRight: 0 }, value));
    return JSON.parse(JSON.stringify({ options: api.inputsOption, filters, position }, (key, value) => key === 'id' ? '<generated>' : value));
  };
  const actual = run(current);
  assert.deepEqual(actual, run(reference));
  assert.deepEqual(actual.position, { top: 60, left: 20 });
  assert.equal(actual.filters[2][0].value, 'payload.deep.leaf');
  assert.equal(actual.filters[3][0].value, 'items.title');
});

test('实际模板nested trailing dot/重复partial片段只移除最后片段，保留父路径', () => {
  const module = execute(`import React, { useMemo } from 'react'; import { useMemoizedFn } from 'ahooks';
    import { cloneDeep } from 'lodash'; import { v4 as uuid } from 'uuid';
    ${variableStatements('utils/index.ts', ['findNodeByValue'])}
    ${variableStatements('ui/flow-template-editor.tsx', ['useFlowTemplateEditor'])}
    export { useFlowTemplateEditor };`, {
    react: { ...React, default: React }, ahooks: require('ahooks'), lodash: require('lodash'), uuid: require('uuid'),
  });
  let api;
  const references = [{ id: 'payload', label: 'payload', value: 'payload', type: 'object', children: [
    { id: 'deep', label: 'deep', value: 'payload.deep', type: 'object', children: [{ id: 'leaf', label: 'leaf', value: 'payload.deep.leaf', type: 'string' }] },
  ] }];
  const input = { id: 'input', name: 'objectInput', schema: { type: 'object', value: { type: 'ref', content: { name: 'payload' } } } };
  renderToString(React.createElement(() => {
    api = module.useFlowTemplateEditor({ currentSelection: { current: null },
      willInertInfo: { current: { cursorPosition: 0, offset: { offsetLeft: 0, offsetRight: 0 } } },
      setShowDropdown() {}, setDropdownPosition() {}, setMatchingInformation() {},
      zoom: 100, references, inputs: [input], treeData: [], parentRef: { current: null } });
    return null;
  }));
  for (const value of ['objectInput.deep.', 'objectInput.deep.de', 'objectInput.deep.l']) {
    const children = api.filterArr(api.inputsOption, value, { offsetLeft: value.length, offsetRight: 0 }, value);
    assert.deepEqual(Array.from(children, child => child.value), ['payload.deep.leaf'], value);
  }
  assert.deepEqual(Array.from(api.filterArr(api.inputsOption, 'unknown.deep.', { offsetLeft: 13, offsetRight: 0 }, 'unknown.deep.'), child => child.value), []);
});

for (const file of ['use-flow-store.ts', 'use-iterator-flow-store.ts']) {
  test(`${file} 实际Inputs字段回调保留名称/字面/引用与固定节点schema，不修改上游`, () => {
    for (const nodeType of ['message', 'plugin']) {
      const source = { id: 'node-start::fields', nodeType: 'node-start', position: { x: 0, y: 0 }, data: {
        label: '开始', nodeParam: {}, outputs: [{ id: 'source-field', name: 'number', schema: { type: 'number' } }], inputs: [],
      } };
      const input = { id: 'editable-input', name: 'input', schema: { type: 'object', value: { type: 'ref', content: {} } } };
      const target = { id: `${nodeType}::fields`, nodeType, position: { x: 0, y: 0 }, data: {
        label: '字段', nodeParam: {}, inputs: [input], outputs: [],
      } };
      const { store, manager, hookModule } = fixture(file, [], { nodes: [source, target], edges: [
        { id: 'fields-edge', source: source.id, target: target.id },
      ] });
      const captured = {};
      const fields = execute(`${read('nodes/components/inputs/index.tsx')}\nexport { NameField, ReferenceField, RemoveButton };`, {
        react: { ...React, default: React }, 'react-i18next': { useTranslation: () => ({ t: key => key }) },
        '@/components/workflow/ui': { FlowNodeInput: props => { captured.input = props; return null; },
          FlowSelect: props => { captured.select = props; return null; },
          FlowCascader: props => { captured.reference = props; return null; }, FLowCollapse: () => null },
        '@/components/workflow/nodes/components/chat-history': { default: () => null },
        '@/components/workflow/store/use-flows-manager': { default: manager },
        '@/components/workflow/hooks/use-node-common': { useNodeCommon: props => ({ ...hookModule.useNodeInfo(props),
          ...hookModule.useNodeFunc(props), ...hookModule.useNodeInputRender(props) }) },
        '@/components/workflow/nodes/components/single-input': { EnabledChatHistory: () => null },
        '@/assets/imgs/workflow/input-add-icon.png': { default: 'add' }, '@/assets/imgs/workflow/input-remove-icon.png': { default: 'remove' },
      });
      let api;
      renderToString(React.createElement(() => { api = hookModule.useNodeInputRender({ id: target.id, data: target.data }); return null; }));
      const current = () => store.getState().nodes.find(node => node.id === target.id).data.inputs[0];
      renderToString(React.createElement(fields.NameField, { id: target.id, item: current(), handleChangeInputParam: api.handleChangeInputParam }));
      captured.input.onChange('renamed');
      assert.equal(current().name, 'renamed');
      renderToString(React.createElement(fields.TypeSelector, { id: target.id, data: target.data, item: current() }));
      captured.select.onChange('literal');
      assert.equal(current().schema.value.type, 'literal');
      assert.equal(current().schema.value.content, '');
      assert.equal(current().schema.type, nodeType === 'plugin' ? 'object' : 'string');
      renderToString(React.createElement(fields.LiteralField, { id: target.id, item: current(), handleChangeInputParam: api.handleChangeInputParam }));
      captured.input.onChange('literal value');
      assert.equal(current().schema.value.content, 'literal value');
      captured.select.onChange('ref');
      assert.deepEqual(plain(current().schema.value.content), {});
      renderToString(React.createElement(fields.ValueField, { id: target.id, data: target.data, item: current() }));
      const selected = utilities.generateReferences(store.getState().nodes, store.getState().edges, target.id)[0].children[0].references[0];
      captured.reference.handleTreeSelect(selected);
      assert.deepEqual(plain(current().schema.value.content), { id: 'source-field', nodeId: source.id, name: 'number' });
      assert.equal(current().schema.type, nodeType === 'plugin' ? 'object' : 'number');
      assert.deepEqual(plain(store.getState().nodes.find(node => node.id === source.id)), plain(source));
    }
  });
  test(`${file} 实际文本处理详情传当前id，模板能找到上游对象子变量且保留修改/blur`, () => {
    const source = { id: 'node-start::template-source', nodeType: 'node-start', position: { x: 0, y: 0 }, data: {
      label: '开始', nodeParam: {}, inputs: [], outputs: [{ id: 'source-output', name: 'payload', schema: {
        type: 'object', properties: [{ id: 'source-child', name: 'child', type: 'string' },
          { id: 'source-deep', name: 'deep', type: 'object', properties: [{ id: 'source-leaf', name: 'leaf', type: 'string' }] }],
      } }],
    } };
    const target = { id: 'text-joiner::detail', nodeType: 'text-joiner', position: { x: 0, y: 0 }, data: {
      label: '文本处理', nodeParam: { mode: 0, prompt: '{{input.child}}' }, inputs: [{ id: 'template-input', name: 'input',
        schema: { type: 'object', value: { type: 'ref', content: { id: 'source-output', nodeId: source.id, name: 'payload' } } } }],
      outputs: [{ id: 'template-output', name: 'output', schema: { type: 'string' } }],
    } };
    const { store, manager, hookModule } = fixture(file, [], { nodes: [source, target], edges: [
      { id: 'template-edge', source: source.id, target: target.id },
    ] });
    const template = execute(`
      import React, { useMemo } from 'react'; import { useMemoizedFn } from 'ahooks';
      import { cloneDeep } from 'lodash'; import { v4 as uuid } from 'uuid';
      ${variableStatements('utils/index.ts', ['findNodeByValue'])}
      ${variableStatements('ui/flow-template-editor.tsx', ['useFlowTemplateEditor'])}
      export { useFlowTemplateEditor };
    `, { react: { ...React, default: React }, ahooks: require('ahooks'), lodash: require('lodash'), uuid: require('uuid') });
    let captured;
    let editorProps;
    const detail = execute(read('nodes/text-handle/index.tsx'), {
      react: { ...React, default: React }, lodash: require('lodash'), uuid: require('uuid'),
      'react-i18next': { useTranslation: () => ({ t: key => key }) },
      antd: { Button: () => null, Input: () => null, Select: () => null },
      '@/components/workflow/store/use-flows-manager': { default: manager },
      '@/components/workflow/nodes/components/inputs': { default: () => null },
      '@/components/workflow/hooks/use-node-common': { useNodeCommon: hookModule.useNodeInfo },
      '@/components/workflow/ui': { FLowTree: () => null,
        FLowCollapse: props => React.createElement('section', null, props.label, props.content),
        FlowTemplateEditor: props => {
          editorProps = props;
          const info = hookModule.useNodeInfo(props);
          captured = template.useFlowTemplateEditor({ ...info, zoom: 100, treeData: [], parentRef: { current: null },
            currentSelection: { current: null }, willInertInfo: { current: { offset: {} } },
            setShowDropdown() {}, setDropdownPosition() {}, setMatchingInformation() {},
          });
          return null;
        } },
      '@/assets/imgs/workflow/input-add-icon.png': { default: 'add' },
      '@/assets/imgs/main/icon_nav_dropdown.svg': { default: 'select' },
    });
    renderToString(React.createElement(detail.TextHandleDetail, { id: target.id, data: target.data }));
    assert.deepEqual(Array.from(captured.inputsOption[0].children || [], option => option.value), ['payload.child', 'payload.deep'],
      '模板变量应保留上游对象子属性，缺id会让真实generateReferences返回空树');
    assert.equal(editorProps.id, target.id);
    assert.equal(captured.filterArr(captured.inputsOption, 'input.', { offsetLeft: 6, offsetRight: 0 }, 'input.')[0].value, 'payload.child');
    assert.equal(captured.filterArr(captured.inputsOption, 'input.deep.', { offsetLeft: 11, offsetRight: 0 }, 'input.deep.')[0].value, 'payload.deep.leaf');
    editorProps.onChange('{{input.deep.leaf}}修改');
    assert.equal(store.getState().nodes.find(node => node.id === target.id).data.nodeParam.prompt, '{{input.deep.leaf}}修改');
    assert.deepEqual(plain(store.getState().nodes.find(node => node.id === source.id)), plain(source));
    // 使用原 store 的实际延迟校验，回调必须指向当前文本节点。
    let blurredId;
    store.setState({ delayCheckNode: id => { blurredId = id; } });
    // 旧回调指向当时校验函数；明确核实际组件传入的 nodeId，不改其调用方式。
    const sectionSource = variableStatements('nodes/text-handle/index.tsx', ['RuleSection']);
    const section = execute(`import React from 'react'; import { FLowCollapse, FlowTemplateEditor } from 'ui'; ${sectionSource}; export { RuleSection };`, {
      react: { ...React, default: React }, ui: { FLowCollapse: props => React.createElement('section', null, props.content),
        FlowTemplateEditor: props => { editorProps = props; return null; } },
    });
    renderToString(React.createElement(section.RuleSection, { id: target.id, data: target.data, nodeParam: target.data.nodeParam,
      handleChangeNodeParam() {}, delayCheckNode: store.getState().delayCheckNode, t: key => key }));
    editorProps.onBlur();
    assert.equal(blurredId, target.id);
  });
}

for (const file of ['use-flow-store.ts', 'use-iterator-flow-store.ts']) {
  test(`${file} 实际IteratorDetail落实禁加prop，普通Inputs默认允许且保留禁用/分割模式`, () => {
    const { store, manager, hookModule, parent } = fixture(file);
    // SSR 的 Zustand 默认读取初始快照；本用例每次渲染模拟当前编辑状态。
    const currentManagerApi = { ...manager, getInitialState: manager.getState };
    const useCurrentManager = selector => require('zustand').useStore(currentManagerApi, selector);
    const collapses = [];
    const inputsModule = execute(read('nodes/components/inputs/index.tsx'), {
      react: { ...React, default: React }, 'react-i18next': { useTranslation: () => ({ t: key => key }) },
      '@/components/workflow/ui': { FlowNodeInput: () => null, FlowSelect: () => null,
        FlowCascader: () => null, FLowCollapse: props => {
          collapses.push(props); return React.createElement('section', null, props.label, props.content);
        } },
      '@/components/workflow/nodes/components/chat-history': { default: () => null },
      '@/components/workflow/store/use-flows-manager': { default: useCurrentManager },
      // 隔离聚合 hook 的未涉 UI/模型/连线部分；输入信息、变更回调仍执行原 hook 与真实 store。
      '@/components/workflow/hooks/use-node-common': { useNodeCommon: props => ({
        ...hookModule.useNodeInfo(props), ...hookModule.useNodeFunc(props),
        ...hookModule.useNodeInputRender(props),
      }) },
      '@/components/workflow/nodes/components/single-input': { EnabledChatHistory: () => null },
      '@/assets/imgs/workflow/input-add-icon.png': { default: 'actual-input-add-icon' },
      '@/assets/imgs/workflow/input-remove-icon.png': { default: 'actual-input-remove-icon' },
    });
    const iterator = execute(read('nodes/iterator/index.tsx'), {
      react: { ...React, default: React }, 'react-i18next': { useTranslation: () => ({ t: key => key }) },
      '@/components/workflow/nodes/components/inputs': inputsModule,
      '@/components/workflow/nodes/components/outputs': { default: () => null },
      './components/flow-container': { default: () => null },
    });
    const html = renderToString(React.createElement(iterator.IteratorDetail, { id: parent().id, data: parent().data }));
    assert.ok(!html.includes('actual-input-add-icon'), '迭代详情入口明确禁止新增输入');
    const ordinary = { id: 'message::ordinary', nodeType: 'message', position: { x: 0, y: 0 },
      data: { label: '消息', nodeParam: {}, inputs: [], outputs: [] } };
    store.setState({ nodes: [...store.getState().nodes, ordinary] });
    const render = extra => renderToString(React.createElement(inputsModule.default, {
      id: ordinary.id, data: ordinary.data, ...extra,
    }));
    assert.ok(render().includes('actual-input-add-icon'));
    const findAdd = element => {
      if (!React.isValidElement(element)) return undefined;
      const children = React.Children.toArray(element.props.children);
      if (element.type === 'div' && children.some(child => React.isValidElement(child)
        && child.type === 'img' && child.props.src === 'actual-input-add-icon')) return element;
      return children.map(findAdd).find(Boolean);
    };
    findAdd(collapses.at(-1).content).props.onClick();
    assert.equal(store.getState().nodes.find(node => node.id === ordinary.id).data.inputs.length, 1);
    assert.ok(render({ allowAdd: true }).includes('actual-input-add-icon'));
    assert.ok(!render({ allowAdd: false }).includes('actual-input-add-icon'));
    manager.setState({ canvasesDisabled: true });
    assert.ok(!render().includes('actual-input-add-icon'));
    manager.setState({ canvasesDisabled: false });
    assert.ok(!render({ data: { ...ordinary.data, nodeParam: { mode: 1 } } }).includes('actual-input-add-icon'));
  });
}

test('实际模型hook保留原string/number目录ID与未知业务扩展，仅拒无效动态形状', () => {
  const { modelInfo } = fixture('use-flow-store.ts');
  const numeric = modelInfo({ llmId: 42, isThink: true });
  assert.deepEqual(plain(numeric.models).map(model => model.llmId), ['text-id', 42]);
  assert.equal(numeric.model.name, '数值ID模型');
  assert.deepEqual(plain(numeric.model.metadata), { approved: true });
  assert.equal(numeric.isThinkModel, true);
  assert.equal(modelInfo({ llmId: 'text-id', isThink: false }).model.name, '文本模型');
  const missing = modelInfo({ llmId: 'unavailable' });
  assert.equal(missing.model, undefined);
  assert.equal(missing.isThinkModel, undefined);
});
function textModeFixture(file, outputs) {
  const textNode = { id: 'text-joiner::actual', nodeType: 'text-joiner', position: { x: 0, y: 0 },
    data: { nodeParam: { mode: 0, prompt: '{{input}}' }, inputs: [{ id: 'old-input', name: 'old',
      schema: { type: 'string', value: { type: 'literal', content: '业务内容' } } }], outputs } };
  const { store } = fixture(file, ['first'], { nodes: [textNode], edges: [] });
  const ast = ts.createSourceFile('text.tsx', read('nodes/text-handle/index.tsx'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  let declaration;
  const visit = node => {
    if (ts.isVariableDeclaration(node) && node.name.getText(ast) === 'handleChangeNodeParam') declaration = node.getText(ast);
    ts.forEachChild(node, visit);
  };
  visit(ast);
  let change;
  const saves = [];
  // 执行实际详情回调、模式组件及真实 Zustand setNode；隔离保存网络和布局组件。
  const actions = execute(`import React, { useCallback } from 'react';
    import { cloneDeep } from 'lodash'; import { v4 as uuid } from 'uuid';
    export const Capture = () => { const ${declaration}; capture(handleChangeNodeParam); return null; };
    ${variableStatements('nodes/text-handle/index.tsx', ['ModeSelector'])}
    export { ModeSelector };`, { react: { ...React, default: React }, lodash: require('lodash'), uuid: require('uuid') }, {
    id: textNode.id, setNode: store.getState().setNode,
    autoSaveCurrentFlow: () => saves.push('save'), canPublishSetNot: () => saves.push('invalidate'),
    capture: callback => { change = callback; }, FLowCollapse: props => props.content,
  });
  renderToString(React.createElement(actions.Capture));
  let content;
  const element = actions.ModeSelector({ id: textNode.id, nodeParam: textNode.data.nodeParam,
    handleChangeNodeParam: change, updateNodeRef: id => saves.push(`refs:${id}`), t: key => key });
  content = element.props.content;
  const buttons = React.Children.toArray(content.props.children.props.children);
  return { store, node: () => store.getState().nodes.find(node => node.id === textNode.id), saves,
    click: mode => buttons[mode].props.onClick() };
}

test('实际模板内容渲染与原参考保持文本/变量span/换行语义，尚未挂载时不写DOM', () => {
  const reference = path.resolve(root, '../../../../../参考项目/PaiFlow-main/console/frontend/src/components/workflow/ui/flow-template-editor.tsx');
  const ast = ts.createSourceFile('reference.tsx', fs.readFileSync(reference, 'utf8'), ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const original = ast.statements.filter(statement => ts.isVariableStatement(statement)
    && statement.declarationList.declarations.some(declaration => declaration.name.getText(ast) === 'useEditorHandlers'))
    .map(statement => statement.getText(ast)).join('\n');
  // DOM 创建接口隔离；两版实际 hook 在真实 React/ahooks 下操作同一结构语义，不宣称浏览器布局验收。
  class Element {
    constructor(tag) { this.tag = tag; this.children = []; this.style = {}; this.text = ''; }
    appendChild(child) { this.children.push(child); }
    set innerHTML(value) { assert.equal(value, ''); this.children = []; this.text = ''; }
    set textContent(value) { this.text = value; this.children = []; }
    get textContent() { return this.text + this.children.map(child => child.textContent).join(''); }
  }
  const document = { createElement: tag => new Element(tag), createTextNode: text => ({ textContent: text }) };
  const render = (source, content, mounted = true) => {
    const editor = new Element('editor');
    let api;
    const module = execute(`import { useMemoizedFn } from 'ahooks'; ${source}
      export const Capture = () => { capture(useEditorHandlers({ editorRef })); return null; };`, {
      ahooks: require('ahooks'),
    }, { document, editorRef: { current: mounted ? editor : null }, capture: value => { api = value; } });
    renderToString(React.createElement(module.Capture));
    api.setEditorContent(content);
    return editor;
  };
  const current = variableStatements('ui/flow-template-editor.tsx', ['useEditorHandlers']);
  for (const content of ['', '普通业务文本', 'before {{payload.deep}} after\n\n{{input}}', ' <script>文字</script>\n  '])
    assert.deepEqual(plain(render(current, content)), plain(render(original, content)));
  const rendered = render(current, '{{payload.deep}}\n');
  assert.equal(rendered.children[0].children[1].tag, 'span');
  assert.equal(rendered.children[0].children[1].style.color, 'blue');
  assert.equal(rendered.children[1].children.at(-1).tag, 'br');
  assert.deepEqual(render(current, '尚未挂载', false).children, []);
});

for (const file of ['use-flow-store.ts', 'use-iterator-flow-store.ts']) {
  test(`${file} 文本实际模式组件保留拼接/分割输出、输入及保存顺序`, () => {
    const { node, click, saves } = textModeFixture(file, [{ id: 'output', name: 'output', schema: { type: 'string' } }]);
    click(1);
    assert.equal(node().data.nodeParam.mode, 1);
    assert.equal(node().data.outputs[0].schema.type, 'array-string');
    assert.equal(node().data.inputs.length, 1);
    assert.equal(node().data.inputs[0].name, 'input');
    assert.ok(node().data.inputs[0].id && node().data.inputs[0].id !== 'old-input');
    assert.deepEqual(plain(node().data.inputs[0].schema.value), { type: 'ref', content: {} });
    const splitInput = plain(node().data.inputs);
    click(0);
    assert.equal(node().data.outputs[0].schema.type, 'string');
    assert.deepEqual(plain(node().data.inputs), splitInput);
    assert.deepEqual(saves, ['save', 'invalidate', 'refs:text-joiner::actual', 'save', 'invalidate', 'refs:text-joiner::actual']);
  });
  test(`${file} 文本模式缺原输出时拒绝操作且不部分更改mode或触发保存`, () => {
    for (const outputs of [undefined, [], [{ id: 'broken', name: 'broken' }]]) {
      const { node, click, saves } = textModeFixture(file, outputs);
      const before = plain(node());
      assert.throws(() => click(1));
      assert.deepEqual(plain(node()), before);
      assert.deepEqual(saves, []);
    }
  });
}

const plain = value => JSON.parse(JSON.stringify(value));
for (const file of ['use-flow-store.ts', 'use-iterator-flow-store.ts']) {
  test(`${file} 迭代单/首/中/末输出删除按原名称关联，不靠不同ID或数组位置`, () => {
    for (const [names, remove] of [[['middle'], 'middle'], [['first', 'middle', 'last'], 'first'],
      [['first', 'middle', 'last'], 'middle'], [['first', 'middle', 'last'], 'last']]) {
      const { api, parent, end, other } = fixture(file, names);
      const before = plain(end().data.inputs);
      const unrelated = plain(other());
      api.handleRemoveOutputLine(`outer-${remove}`);
      assert.deepEqual(Array.from(parent().data.outputs, item => item.name), names.filter(name => name !== remove));
      assert.deepEqual(plain(end().data.inputs), before.filter(item => item.name !== remove));
      assert.deepEqual(plain(other()), unrelated);
      assert.ok(end().data.inputs.every(item => typeof item === 'object'));
    }
  });
  test(`${file} 同一旧回调连续多次删除和失效ID不会删除其他内部输入`, () => {
    const { store, api, parent, end } = fixture(file);
    // 操作前重排当前父输出，旧 hook 捕获的数据不可作为位置依据。
    store.getState().setNode(parent().id, node => ({ ...node, data: {
      ...node.data, outputs: [...node.data.outputs].reverse(),
    } }));
    api.handleRemoveOutputLine('outer-middle');
    api.handleRemoveOutputLine('outer-first');
    const before = plain(end());
    api.handleRemoveOutputLine('outer-first');
    api.handleRemoveOutputLine('deleted');
    assert.deepEqual(Array.from(parent().data.outputs, item => item.name), ['last']);
    assert.deepEqual(Array.from(end().data.inputs, item => item.name), ['last']);
    assert.deepEqual(plain(end()), before);
  });
  test(`${file} 修改描述/required不改内部名称，改名保留原引用`, () => {
    const { api, end } = fixture(file);
    const before = plain(end().data.inputs.find(item => item.name === 'middle'));
    api.handleChangeOutputParam('outer-middle', (output, value) => { output.schema.default = value; }, '描述');
    api.handleChangeOutputParam('outer-middle', (output, value) => { output.required = value; }, true);
    assert.deepEqual(plain(end().data.inputs.find(item => item.name === 'middle')), before);
    api.handleChangeOutputParam('outer-middle', (output, value) => { output.name = value; }, 'renamed');
    assert.deepEqual(plain(end().data.inputs.find(item => item.name === 'renamed')), { ...before, name: 'renamed' });
  });
  test(`${file} 嵌套属性删除保留顶层内部输出，新增顶层输出同步原ref形状`, () => {
    const { store, api, parent, end } = fixture(file);
    store.getState().setNode(parent().id, node => { node.data.outputs[0].schema.properties = [
      { id: 'nested-field', name: 'middle', type: 'string' },
    ]; return cloneDeep(node); });
    const before = plain(end().data.inputs);
    api.handleRemoveOutputLine('nested-field');
    assert.deepEqual(plain(end().data.inputs), before);
    api.handleAddOutputLine();
    assert.equal(parent().data.outputs.length, 4);
    assert.equal(end().data.inputs.length, 4);
    const added = end().data.inputs[3];
    assert.equal(added.name, '');
    assert.deepEqual(plain(added.schema), { type: '', value: { type: 'ref', content: {} } });
  });
  test(`${file} 新增多个空名称输出以同一ID区分，旧回调不触及已删除迭代`, () => {
    const { store, api, parent, end } = fixture(file, ['middle']);
    api.handleAddOutputLine();
    api.handleAddOutputLine();
    const first = parent().data.outputs[1].id;
    const second = parent().data.outputs[2].id;
    api.handleChangeOutputParam(second, (output, value) => { output.name = value; }, 'second-new');
    assert.equal(end().data.inputs.find(input => input.id === second).name, 'second-new');
    assert.equal(end().data.inputs.find(input => input.id === first).name, '');
    api.handleRemoveOutputLine(first);
    assert.deepEqual(Array.from(end().data.inputs, input => input.name), ['middle', 'second-new']);
    const retained = plain(end());
    store.setState({ nodes: store.getState().nodes.filter(node => node.id !== parent().id) });
    api.handleRemoveOutputLine('outer-middle');
    assert.deepEqual(plain(end()), retained);
  });
  test(`${file} 原wrapped schema嵌套属性与内部同名歧义不误删顶层关联`, () => {
    const { store, api, parent, end } = fixture(file);
    store.getState().setNode(parent().id, node => { node.data.outputs[0].schema.properties = [
      { id: 'wrapped', name: 'container', schema: { type: 'object', properties: [
        { id: 'wrapped-nested', name: 'middle', schema: { type: 'string', default: '保留业务值' } },
      ] } },
    ]; return cloneDeep(node); });
    const before = plain(end().data.inputs);
    api.handleRemoveOutputLine('wrapped-nested');
    assert.deepEqual(plain(end().data.inputs), before);
    assert.deepEqual(plain(parent().data.outputs[0].schema.properties[0].schema.properties), []);
    store.getState().setNode(end().id, node => { node.data.inputs.push({
      ...cloneDeep(node.data.inputs.find(input => input.name === 'middle')), id: 'second-middle',
    }); return cloneDeep(node); });
    const ambiguous = plain(end().data.inputs);
    api.handleRemoveOutputLine('outer-middle');
    assert.deepEqual(plain(end().data.inputs), ambiguous, '旧独立ID同名歧义不能按位置任意清除内部输入');
  });
  test(`${file} 原真实类型选择器保留direct/wrapped/file/fileList分支及内部End名称`, () => {
    for (const [selection, expected] of [
      [['integer'], { type: 'integer', properties: [] }],
      [['file', 'pdf'], { schema: { type: 'string', properties: [] }, fileType: 'file', allowedFileType: ['pdf'] }],
      [['fileList', 'Array<Image>'], { schema: { type: 'array-string' }, fileType: 'file', allowedFileType: ['Image'] }],
    ]) {
      const { store, parent, end, outputTypeChange } = fixture(file);
      const leaf = { id: 'nested-selector', name: 'nested', type: 'object', properties: [{ id: 'old', name: 'old', type: 'string' }] };
      store.getState().setNode(parent().id, node => { node.data.outputs[0].schema.properties = [leaf]; return cloneDeep(node); });
      const before = plain(end().data.inputs);
      outputTypeChange(leaf)(selection);
      const result = parent().data.outputs[0].schema.properties[0];
      for (const [field, value] of Object.entries(expected)) assert.deepEqual(plain(result[field]), value);
      assert.deepEqual(plain(end().data.inputs), before);
      const unchanged = plain(parent());
      const callback = outputTypeChange(leaf);
      callback(['file']);
      callback(['fileList', 42]);
      callback(null);
      assert.deepEqual(plain(parent()), unchanged);
    }
    const { parent, end, outputTypeChange } = fixture(file);
    const output = parent().data.outputs[0];
    outputTypeChange(output)(['number']);
    assert.equal(parent().data.outputs[0].schema.type, 'number');
    assert.deepEqual(plain(parent().data.outputs[0].schema.properties), []);
    assert.equal(end().data.inputs.find(input => input.name === 'first').name, 'first');
  });
  test(`${file} 内部开始节点替换后旧输入回调仍更新当前同一迭代节点`, () => {
    const { store, parent, inputApi } = fixture(file);
    const original = store.getState().nodes.find(node => node.nodeType === 'iteration-node-start');
    const replacement = { ...cloneDeep(original), id: 'iteration-node-start::replacement', data: {
      ...cloneDeep(original.data), outputs: [],
    } };
    store.setState({ nodes: [...store.getState().nodes.filter(node => node.id !== original.id), replacement] });
    inputApi.handleChangeInputParam('outer-input', (input, value) => { input.name = value; }, 'renamed-input');
    assert.equal(parent().data.inputs[0].name, 'renamed-input');
    const current = store.getState().nodes.find(node => node.id === replacement.id);
    assert.deepEqual(plain(current.data.outputs), [{ id: 'outer-input', name: 'renamed-input', schema: { type: 'string', default: '' } }]);
  });
  test(`${file} 默认值显示保留原React文本框转换及direct/wrapped保存路径`, () => {
    const { store, parent, end, inputApi } = fixture(file);
    for (const [value, expected] of [['描述', '描述'], [23, '23'], [true, 'true'], [false, 'false'],
      [null, ''], [undefined, ''], [['a', 'b'], 'a,b'], [{ count: 2 }, '[object Object]']]) {
      const element = inputApi.renderTypeInput({ id: 'leaf', name: 'leaf', default: value, type: 'string' });
      assert.equal(element.props.value, expected);
    }
    const wrapped = inputApi.renderTypeInput(parent().data.outputs[0]);
    wrapped.props.onChange('新描述');
    assert.equal(parent().data.outputs[0].schema.default, '新描述');
    const direct = { id: 'direct-description', name: 'direct', type: 'string', default: '旧描述' };
    store.getState().setNode(parent().id, node => { node.data.outputs[0].schema.properties = [direct]; return cloneDeep(node); });
    const beforeEnd = plain(end().data.inputs);
    inputApi.renderTypeInput(direct).props.onChange('直接属性的新描述');
    assert.equal(parent().data.outputs[0].schema.properties[0].default, '直接属性的新描述');
    assert.equal(parent().data.outputs[0].schema.properties[0].schema, undefined);
    assert.deepEqual(plain(end().data.inputs), beforeEnd);
    const missing = inputApi.renderTypeInput({ name: '尚无ID', type: 'string' });
    missing.props.onChange('不得误写');
    assert.equal(parent().data.outputs[0].schema.default, '新描述');
  });
}
