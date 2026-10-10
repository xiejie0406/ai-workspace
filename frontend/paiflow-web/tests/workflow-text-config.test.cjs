const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const React = require('react');
const { renderToString } = require('react-dom/server');
const { create, useStore } = require('zustand');
const root = path.resolve(__dirname, '../src/components/workflow');
function variables(file, names) {
  const source = fs.readFileSync(path.join(root, file), 'utf8');
  const ast = ts.createSourceFile(file, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  return ast.statements.filter(statement => ts.isVariableStatement(statement)
    && statement.declarationList.declarations.some(declaration => names.includes(declaration.name.getText(ast))))
    .map(statement => statement.getText(ast)).join('\n');
}
function execute(source, globals) {
  const exports = {};
  vm.runInNewContext(ts.transpileModule(source, { fileName: 'actual.tsx', compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React,
  } }).outputText, { exports, ...globals });
  return exports;
}
function fixture(initial, refresh) {
  const requests = [];
  const saveReply = { saved: true, revision: 7 };
  const helpers = execute(variables('store/flow-manager-function.ts', [
    'parseTextNodeConfigList', 'addTextNodeConfig', 'removeTextNodeConfig', 'setTextNodeConfigList',
  ]), {
    textNodeConfigListAPI: async () => { requests.push(['list']); return refresh; },
    textNodeConfigClearAPI: async id => { requests.push(['delete', id]); },
    textNodeConfigSaveAPI: async params => { requests.push(['save', params]); return saveReply; },
  });
  let lastRemoval;
  // 实际生产 helper + 真实 Zustand；服务请求是隔离边界，不声称旧URL/中心CRUD已联机。
  const store = create((set, get) => ({
    textNodeConfigList: initial,
    setTextNodeConfigList: change => helpers.setTextNodeConfigList(change, get, set),
    addTextNodeConfig: params => helpers.addTextNodeConfig(params, get),
    removeTextNodeConfig: id => { lastRemoval = helpers.removeTextNodeConfig(id, get); return lastRemoval; },
    getCurrentStore: () => () => () => {},
  }));
  return { helpers, store, requests, saveReply, removal: () => lastRemoval };
}
const plain = value => JSON.parse(JSON.stringify(value));
test('原分隔符目录保留数值/字符串ID、builtin uid、nullable comment与未知扩展，非法响应写前拒绝', () => {
  const { helpers, store } = fixture([], []);
  const original = [{ id: 1, uid: -1, separator: '\n', comment: null, extension: { retain: true } },
    { id: '2', uid: 'owner-42', separator: '', comment: '空分隔符' }];
  assert.equal(helpers.parseTextNodeConfigList(original), original);
  store.getState().setTextNodeConfigList(original);
  assert.equal(store.getState().textNodeConfigList, original);
  store.getState().setTextNodeConfigList(current => [...current, { id: 3, uid: 'owner', separator: ',' }]);
  assert.equal(store.getState().textNodeConfigList[0], original[0]);
  const retained = store.getState().textNodeConfigList;
  for (const value of [{ rows: original }, [null], [{ id: 1, separator: ',' }],
    [{ id: NaN, uid: -1, separator: ',' }], [{ id: 1, uid: {}, separator: ',' }],
    [{ id: 1, uid: -1, separator: 42 }], [{ id: 1, uid: -1, separator: ',', comment: {} }]]) {
    assert.throws(() => store.getState().setTextNodeConfigList(value));
    assert.equal(store.getState().textNodeConfigList, retained);
  }
});
test('实际异步新增/删除helper先操作后刷新，返回原保存响应或同一刷新数组', async () => {
  const refresh = [{ id: 'config-2', uid: 'owner', separator: ';', extra: null }];
  const { store, requests, saveReply } = fixture([], refresh);
  assert.equal(await store.getState().addTextNodeConfig({ separator: ';' }), saveReply);
  assert.equal(store.getState().textNodeConfigList, refresh);
  assert.equal(await store.getState().removeTextNodeConfig('config-1'), refresh);
  assert.deepEqual(plain(requests), [['save', { separator: ';' }], ['list'], ['delete', 'config-1'], ['list']]);
});
test('实际异步刷新无效目录会reject，保留先前目录而不伪造成功列表', async () => {
  const initial = [{ id: 1, uid: 'owner', separator: ',' }];
  const { store, requests } = fixture(initial, { invalid: true });
  await assert.rejects(store.getState().removeTextNodeConfig(1));
  assert.equal(store.getState().textNodeConfigList, initial);
  assert.deepEqual(plain(requests), [['delete', 1], ['list']]);
});
for (const duplicateRemains of [false, true]) {
  test(`实际分隔符组件删除后${duplicateRemains ? '仍有同值则保留' : '缺同值则清空'}所选分隔符，builtin不提供删除`, async () => {
    const initial = [{ id: 1, uid: -1, separator: '\n' }, { id: 'own', uid: 'owner', separator: ';' }];
    const refresh = duplicateRemains ? [initial[0], { ...initial[1], id: 'other' }] : [initial[0]];
    const { store, requests, removal } = fixture(initial, refresh);
    const currentApi = { ...store, getInitialState: store.getState };
    let select;
    const changes = [];
    const ui = execute(variables('nodes/text-handle/index.tsx', ['SeparatorSection']) + '\nexport { SeparatorSection };', {
      React, useState: React.useState, useCallback: React.useCallback,
      useTranslation: () => ({ t: key => key }),
      useFlowsManager: selector => useStore(currentApi, selector),
      FLowCollapse: props => props.content,
      Select: props => { select = props; return props.dropdownRender(); },
      Button: () => null, Input: () => null, inputAddIcon: 'add', formSelect: 'dropdown',
    });
    renderToString(React.createElement(ui.SeparatorSection, { id: 'text-joiner::actual', nodeParam: { mode: 1, separator: ';' },
      handleChangeNodeParam: (key, value) => changes.push([key, value]) }));
    assert.equal(select.options, initial);
    const rows = select.dropdownRender().props.children[0].props.children;
    assert.equal(rows[0].props.children[1], false, '原数字uid=-1不提供删除入口');
    let stopped = 0;
    rows[1].props.children[1].props.onClick({ stopPropagation: () => stopped++ });
    await removal();
    await Promise.resolve();
    assert.equal(stopped, 1);
    assert.deepEqual(plain(requests), [['delete', 'own'], ['list']]);
    assert.equal(store.getState().textNodeConfigList, refresh);
    assert.deepEqual(changes, duplicateRemains ? [] : [['separator', '']]);
  });
}
