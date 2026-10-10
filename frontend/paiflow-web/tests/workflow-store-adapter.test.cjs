const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { execFileSync } = require('node:child_process');
const ts = require('typescript');

function execute(relative, imports, baseline = false, reference = false) {
  const file = path.join(__dirname, reference
    ? '../../../参考项目/PaiFlow-main/console/frontend/src/components/workflow'
    : '../src/components/workflow', relative);
  const source = baseline
    ? execFileSync('git', ['show', `HEAD:frontend/paiflow-web/src/components/workflow/${relative}`], { cwd: path.join(__dirname, '../../..'), encoding: 'utf8' })
    : fs.readFileSync(file, 'utf8');
  const exports = {};
  vm.runInNewContext(ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, {
    exports, setTimeout, navigator: {}, document: {},
    require(name) {
      if (!(name in imports)) throw new Error(`Unexpected import ${name}`);
      return imports[name];
    },
  });
  return exports;
}

const utilityImports = {
  lodash: require('lodash'), uuid: require('uuid'), ajv: require('ajv'),
  i18next: { default: { t: key => key } }, '@/utils': { isJSON: value => {
    try { JSON.parse(value); return true; } catch { return false; }
  } },
};
const utilities = execute('utils/reactflowUtils.ts', utilityImports);
const helpers = execute('store/flow-function.ts', {
  '@/components/workflow/utils/reactflowUtils': utilities,
  uuid: require('uuid'), lodash: require('lodash'), reactflow: require('reactflow'),
  antd: { message: { error() {} } }, './use-flows-manager': { default: { getState() { return { canPublishSetNot() {} }; } } },
});

test('默认值文本转换与当前React DOM实际函数一致，默认值对象保持原样', () => {
  const reactSource = fs.readFileSync(path.join(__dirname, '../node_modules/react-dom/cjs/react-dom.development.js'), 'utf8');
  const ast = ts.createSourceFile('react-dom.js', reactSource, ts.ScriptTarget.Latest, true, ts.ScriptKind.JS);
  const wanted = new Set(['getToStringValue', 'toString', 'checkFormFieldValueStringCoercion', 'willCoercionThrow', 'testStringCoercion', 'typeName']);
  const collected = [];
  const collect = node => {
    if (ts.isFunctionDeclaration(node) && wanted.has(node.name?.text)) collected.push(node.getText(ast));
    ts.forEachChild(node, collect);
  };
  collect(ast);
  const definitions = collected.join('\n');
  const native = vm.runInNewContext(`${definitions}; ({ getToStringValue, toString })`, { error() {} });
  const object = { count: 2 };
  const values = ['描述', '', 23, 0, -0, false, true, null, undefined, ['a', 'b'], object,
    new Date('2026-10-05T00:00:00Z'), { valueOf: () => 7, toString: () => 'different-hint' }, Symbol('unsupported'), () => 'unsupported', 12n];
  for (const value of values) {
    const expected = value == null ? '' : native.toString(native.getToStringValue(value));
    assert.equal(utilities.workflowValueToText(value), expected);
  }
  assert.deepEqual(object, { count: 2 });
  assert.equal(utilities.isWorkflowDisplayValue(['文本', 23, false, null, ['子项']]), true);
  assert.equal(utilities.isWorkflowDisplayValue({ count: 2 }), false);
  assert.equal(utilities.isWorkflowDisplayValue(['文本', { count: 2 }]), false);
});

test('迭代画布尺寸实际函数保留原负坐标/小数/空图规则', () => {
  function dimensionFunction(base) {
    const file = path.join(__dirname, base, 'nodes/iterator/components/flow-container/index.tsx');
    const source = fs.readFileSync(file, 'utf8');
    const ast = ts.createSourceFile('flow-container.tsx', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
    let callback;
    const walk = node => {
      if (ts.isVariableDeclaration(node) && node.name.getText(ast) === 'getDimensions') callback = node.initializer.arguments[0];
      ts.forEachChild(node, walk);
    };
    walk(ast);
    return vm.runInNewContext(ts.transpileModule(`const calculate = ${callback.getText(ast)}; calculate;`, {
      compilerOptions: { target: ts.ScriptTarget.ES2022 },
    }).outputText);
  }
  const actual = dimensionFunction('../src/components/workflow');
  const original = dimensionFunction('../../../参考项目/PaiFlow-main/console/frontend/src/components/workflow');
  for (const positions of [[], [{ position: { x: 10, y: 20 } }],
    [{ position: { x: -120.5, y: 10.25 } }, { position: { x: 20.5, y: -200.75 } }, { position: { x: 5, y: 100 } }]]) {
    const before = JSON.stringify(positions);
    assert.equal(JSON.stringify(actual(positions)), JSON.stringify(original(positions)));
    assert.equal(JSON.stringify(positions), before);
  }
});

for (const file of ['use-flow-store.ts', 'use-iterator-flow-store.ts']) {
  test(`${file} 两参换边操作取得本 store，按原规则移除旧边引用`, () => {
    const store = execute(`store/${file}`, {
      zustand: require('zustand'), './flow-function': helpers,
    }, process.env.PAIFLOW_ADAPTER_BASELINE === '1').default;
    const calls = [];
    store.setState({ removeNodeRef: (source, target) => calls.push([source, target]) });
    store.getState().switchNodeRef(
      { source: 'new-source', target: 'new-target', sourceHandle: null, targetHandle: null },
      { id: 'old-edge', source: 'old-source', target: 'old-target' }
    );
    assert.deepEqual(calls, [['old-source', 'old-target']]);
  });

  test(`${file} 节点更新保留其他节点，已删除节点不再执行更新回调或校验`, () => {
    const store = execute(`store/${file}`, {
      zustand: require('zustand'), './flow-function': helpers,
    }).default;
    store.setState({ nodes: [
      { id: 'a', position: { x: 0, y: 0 }, data: { label: '原名称', extension: { active: true } } },
      { id: 'b', position: { x: 1, y: 2 }, data: { label: '保留节点' } },
    ] });
    store.getState().setNode('a', previous => ({ ...previous, selected: true }));
    assert.equal(store.getState().nodes[0].selected, true);
    assert.equal(store.getState().nodes[0].data.extension.active, true);
    assert.equal(store.getState().nodes[1].data.label, '保留节点');
    store.getState().reNameNode('a', '新名称');
    assert.equal(store.getState().nodes[0].data.label, '新名称');
    const before = store.getState().nodes;
    let called = false;
    store.getState().setNode('deleted', () => { called = true; throw new Error('不应调用'); });
    store.getState().reNameNode('deleted', '不得创建节点');
    store.getState().updateNodeNameStatus('deleted');
    assert.equal(called, false);
    assert.equal(store.getState().checkNode('deleted'), false);
    assert.equal(store.getState().nodes, before);
  });

  test(`${file} 删除合法边先清理实际端点引用，失效删除不再清理引用`, () => {
    const store = execute(`store/${file}`, {
      zustand: require('zustand'), './flow-function': helpers,
    }).default;
    const source = 'spark-llm::12345678-1234-1234-1234-123456789abc';
    const target = 'node-end::22345678-1234-1234-1234-123456789abc';
    const id = utilities.getEdgeId(source, target);
    const calls = [];
    store.setState({
      edges: [{ id, source, target }, { id: 'kept', source: 'x', target: 'y' }],
      takeSnapshot() {}, removeNodeRef: (s, t) => calls.push([s, t]),
    });
    store.getState().onEdgesChange([{ type: 'remove', id }]);
    assert.deepEqual(calls, [[source, target]]);
    assert.deepEqual(Array.from(store.getState().edges, edge => edge.id), ['kept']);
    store.getState().onEdgesChange([{ type: 'remove', id: 'malformed' }]);
    assert.deepEqual(calls, [[source, target]]);
    assert.deepEqual(Array.from(store.getState().edges, edge => edge.id), ['kept']);
  });

  test(`${file} Agent/Database/RPA和opaque边ID均以实际边端点删除，重复删除为无副作用`, () => {
    const store = execute(`store/${file}`, {
      zustand: require('zustand'), './flow-function': helpers,
    }).default;
    const types = ['agent', 'database', 'rpa'];
    const target = 'node-end::22345678-1234-1234-1234-123456789abc';
    for (const nodeType of types) {
      const source = `${nodeType}::12345678-1234-1234-1234-123456789abc`;
      for (const id of [utilities.getEdgeId(source, target), `opaque-edge/${nodeType}/中文`]) {
        const calls = [];
        store.setState({
          edges: [{ id, source, target }, { id: 'keep', source: 'x', target: 'y' }],
          takeSnapshot() {}, removeNodeRef(s, t) {
            assert.equal(store.getState().edges[0].id, id, '清理引用发生在删除边之前');
            calls.push([s, t]);
          },
        });
        store.getState().onEdgesChange([{ type: 'remove', id }]);
        assert.deepEqual(calls, [[source, target]]);
        assert.deepEqual(Array.from(store.getState().edges, edge => edge.id), ['keep']);
        store.getState().onEdgesChange([{ type: 'remove', id }]);
        assert.deepEqual(calls, [[source, target]]);
        assert.deepEqual(Array.from(store.getState().edges, edge => edge.id), ['keep']);
      }
    }
  });

  test(`${file} 已存在但实际端点无效的边明确拒绝`, () => {
    const store = execute(`store/${file}`, {
      zustand: require('zustand'), './flow-function': helpers,
    }).default;
    const malformed = { id: 'invalid-edge', source: '', target: 'target' };
    let calls = 0;
    store.setState({ edges: [malformed], takeSnapshot() {}, removeNodeRef() { calls++; } });
    assert.throws(() => store.getState().onEdgesChange([{ type: 'remove', id: malformed.id }]), /工作流边/);
    assert.equal(calls, 0);
    assert.equal(store.getState().edges[0], malformed);
  });

  test(`${file} 混合change批量删除两条路径后清除实际下游引用，重复ID只处理一次`, () => {
    const store = execute(`store/${file}`, {
      zustand: require('zustand'), './flow-function': helpers,
    }).default;
    const nodes = ['origin', 'left', 'right', 'end'].map(id => ({ id, position: { x: 0, y: 0 }, data: { inputs: [] } }));
    nodes[3].data.inputs.push({ id: 'ref', name: 'question', schema: { type: 'string', value: {
      type: 'ref', content: { name: 'result', nodeId: 'origin', id: 'output' },
    } } });
    store.setState({ nodes, edges: [
      { id: 'a', source: 'origin', target: 'left' }, { id: 'b', source: 'origin', target: 'right' },
      { id: 'c', source: 'left', target: 'end' }, { id: 'd', source: 'right', target: 'end' },
    ], takeSnapshot() {} });
    const actualRemove = store.getState().removeNodeRef;
    const calls = [];
    store.setState({ removeNodeRef(s, t, edges) { calls.push([s, t]); actualRemove(s, t, edges); } });
    store.getState().onEdgesChange([
      { type: 'select', id: 'c', selected: true },
      { type: 'remove', id: 'a' }, { type: 'remove', id: 'b' }, { type: 'remove', id: 'a' },
    ]);
    assert.deepEqual(calls, [['origin', 'left'], ['origin', 'right']]);
    assert.equal(store.getState().nodes[3].data.inputs[0].schema.value.content.nodeId, '');
    assert.equal(store.getState().nodes[3].data.inputs[0].schema.value.content.name, '');
    assert.deepEqual(Array.from(store.getState().edges, edge => edge.id), ['c', 'd']);
    assert.equal(store.getState().edges[0].selected, true);
  });

  test(`${file} 批量删除含非法后项时先拒绝整批，不清前项引用或删边`, () => {
    const store = execute(`store/${file}`, {
      zustand: require('zustand'), './flow-function': helpers,
    }).default;
    const edges = [{ id: 'good', source: 'a', target: 'b' }, { id: 'bad', source: '', target: 'c' }];
    let calls = 0;
    store.setState({ edges, takeSnapshot() {}, removeNodeRef() { calls++; } });
    assert.throws(() => store.getState().onEdgesChange([
      { type: 'remove', id: 'good' }, { type: 'remove', id: 'bad' },
    ]), /工作流边/);
    assert.equal(calls, 0);
    assert.equal(store.getState().edges, edges);
  });

  test(`${file} 延迟校验期间已删除的节点不回写或触发保存`, async () => {
    const store = execute(`store/${file}`, {
      zustand: require('zustand'), './flow-function': helpers,
    }).default;
    store.setState({ nodes: [{ id: 'deleted', position: { x: 0, y: 0 }, data: {} }] });
    store.getState().delayCheckNode('deleted');
    store.setState({ nodes: [] });
    await new Promise(resolve => setTimeout(resolve, 550));
    assert.equal(store.getState().nodes.length, 0);
  });
}

test('原图遍历保留边顺序、循环去重及节点名称补空号行为', () => {
  const edges = [
    { source: 'a', target: 'b' }, { source: 'a', target: 'c' },
    { source: 'c', target: 'd' }, { source: 'd', target: 'a' },
  ];
  assert.deepEqual(Array.from(utilities.findChildrenNodes('a', edges)), ['c', 'd', 'b']);
  assert.deepEqual(Array.from(utilities.findParentNodes('a', edges)), ['d', 'c']);
  assert.equal(utilities.getNextName([{ data: { label: '任务_1' } }, { data: { label: '任务_3' } }], '任务'), '任务_2');
  const node = { id: 'old-id', data: { reference: 'old-id', extension: { active: true } } };
  const copied = utilities.handleReplaceNodeId([node], { 'old-id': 'new-id' });
  assert.equal(copied[0].id, 'new-id');
  assert.equal(copied[0].data.reference, 'new-id');
  assert.equal(copied[0].data.extension.active, true);
  assert.equal(node.id, 'old-id');
});

test('递归引用树与原参考实际函数逐字段一致，迭代仅保留数组输出', () => {
  const original = execute('utils/reactflowUtils.ts', utilityImports, false, true);
  const outputs = [
    { id: 'object', name: 'result', schema: { type: 'object', properties: [
      { id: 'direct', name: 'count', type: 'number' },
      { id: 'wrapped', name: 'nested', schema: { type: 'object', properties: [
        { id: 'file', name: 'file', schema: { type: 'string' }, allowedFileType: ['pdf'] },
      ] } },
    ] } },
    { id: 'array', name: 'rows', schema: { type: 'array-object', properties: [
      { id: 'row', name: 'name', schema: { type: 'string' } },
    ] } },
  ];
  const nodes = [
    { id: 'source', nodeType: 'code', data: { label: '上游', outputs } },
    { id: 'target', nodeType: 'message', data: { label: '下游', outputs: [] } },
  ];
  const edges = [{ source: 'source', target: 'target' }];
  const normalized = value => JSON.parse(JSON.stringify(value));
  const generated = normalized(utilities.generateReferences(nodes, edges, 'target'));
  assert.deepEqual(generated, normalized(original.generateReferences(nodes, edges, 'target')));
  const refs = generated[0].children[0].references;
  assert.equal(refs[0].children[0].value, 'result.count');
  assert.equal(refs[0].children[1].children[0].value, 'result.nested.file');
  assert.equal(refs[0].children[1].children[0].fileType, 'pdf');
  nodes[1].nodeType = 'iteration';
  const iteration = normalized(utilities.generateReferences(nodes, edges, 'target'));
  assert.deepEqual(iteration, normalized(original.generateReferences(nodes, edges, 'target')));
  assert.deepEqual(iteration[0].children[0].references.map(ref => ref.id), ['array']);
});

test('递归输出名称校验保留原错误信息与重复名称判定', () => {
  const original = execute('utils/reactflowUtils.ts', utilityImports, false, true);
  const outputs = [{ id: 'root', name: 'result', schema: { type: 'object', properties: [
    { id: 'a', name: 'same', type: 'string' },
    { id: 'b', name: 'same', schema: { type: 'string' } },
    { id: 'c', name: 'invalid name', schema: { type: 'string' } },
  ] } }];
  const copy = JSON.parse(JSON.stringify(outputs));
  assert.equal(utilities.checkedNodeOutputData(outputs, { nodeType: 'code' }), false);
  assert.equal(original.checkedNodeOutputData(copy, { nodeType: 'code' }), false);
  assert.deepEqual(outputs, copy);
  assert.match(outputs[0].schema.properties[0].nameErrMsg, /valueCannotBeRepeated/);
  assert.match(outputs[0].schema.properties[2].nameErrMsg, /canOnlyContainLetters/);
});
