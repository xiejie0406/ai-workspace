const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const React = require('react');
const { renderToString } = require('react-dom/server');
const root = path.resolve(__dirname, '../src/components/workflow');
const current = fs.readFileSync(path.join(root, 'ui/flow-template-editor.tsx'), 'utf8');
const reference = fs.readFileSync(path.resolve(__dirname, '../../../参考项目/PaiFlow-main/console/frontend/src/components/workflow/ui/flow-template-editor.tsx'), 'utf8');
function declarations(source, names) {
  const ast = ts.createSourceFile('actual.tsx', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
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
const actualLineContent = declarations(fs.readFileSync(path.join(root, 'utils/index.ts'), 'utf8'), ['getCurrentLineContent']);
const plain = value => JSON.parse(JSON.stringify(value));
function fixture({ source = current, content = '{{foobar}}', cursor = 5, selection = true, line = true,
  focusedKey = null, options = [{ id: 'first', label: 'foobar' }, { id: 'last', label: 'other' }] } = {}) {
  const changes = [];
  const child = { nodeType: 3, textContent: content };
  const parent = { nodeType: 1, childNodes: [child] };
  child.parentNode = line ? parent : null;
  const range = { startOffset: cursor, startContainer: child, getBoundingClientRect: () => ({ x: 12, y: 30 }) };
  const nativeSelection = selection === true ? { rangeCount: 1, getRangeAt: () => range }
    : selection === 'empty' ? { rangeCount: 0 } : null;
  const module = execute(`
    ${actualLineContent}
    ${declarations(source, ['useDropdownControl'])}
    export { useDropdownControl };`, {
    useMemoizedFn: require('ahooks').useMemoizedFn, cloneDeep: require('lodash').cloneDeep,
    window: { getSelection: () => nativeSelection }, Node: { ELEMENT_NODE: 1 },
  });
  let api;
  const ref = { current: null }, insert = { current: { cursorPosition: 0, offset: { offsetLeft: 0, offsetRight: 0 } } };
  const replace = { current: false };
  renderToString(React.createElement(() => {
    api = module.useDropdownControl({ showDropdown: true, focusedKey, treeData: options,
      inputsOption: options, replaceSpanRef: replace, currentSelection: ref, willInertInfo: insert,
      setFocusedKey: value => changes.push(['focused', value]),
      setKeyboardNavigationActive: value => changes.push(['keyboard', value]),
      setIsFirstOpen: value => changes.push(['first', value]), setShowDropdown: value => changes.push(['show', value]),
      setTreeData: value => changes.push(['tree', plain(value)]),
      setMatchingInformation: value => changes.push(['matching', plain(value)]),
      setDropdownPosition: value => changes.push(['position', plain(value)]),
      dropdownRef: { current: { focus: () => changes.push(['focus']), querySelector: key => ({
        scrollIntoView: value => changes.push(['scroll', key, value.block]),
      }) } }, getCursorPosition: () => changes.push(['cursor']),
      filterArr: (...args) => { changes.push(['filter', plain(args)]); return options; },
      handleTreeSelect: keys => changes.push(['select', plain(keys)]),
    });
    return null;
  }));
  const event = key => ({ key, preventDefault: () => changes.push(['prevent']), stopPropagation: () => changes.push(['stop']) });
  return { api, event, changes, replace, ref, range, insert };
}
test('实际下拉hook缺Selection或无当前行时收起并重置，不构造选区/插入位置', () => {
  for (const context of [{ selection: false }, { selection: 'empty' }, { line: false }]) {
    const f = fixture(context);
    assert.doesNotThrow(() => f.api.handleKeyDown(f.event('ArrowDown')));
    assert.doesNotThrow(() => f.api.onKeyUp(f.event('a')));
    assert.equal(f.ref.current, null);
    assert.deepEqual(plain(f.insert.current), { cursorPosition: 0, offset: { offsetLeft: 0, offsetRight: 0 } });
    assert.ok(f.changes.some(change => change[0] === 'show' && change[1] === false));
    assert.ok(!f.changes.some(change => ['filter', 'position', 'select', 'tree'].includes(change[0])));
    assert.deepEqual(f.changes.slice(-3), [['focused', null], ['keyboard', false], ['first', false]]);
  }
});
test('实际未闭合模板前缀只传真实片段，不给匹配词附加undefined', () => {
  const content = '{{objectInput.deep.l';
  const f = fixture({ content, cursor: content.length });
  f.api.onKeyUp(f.event('l'));
  const args = f.changes.find(change => change[0] === 'filter')[1];
  assert.equal(args[1], 'objectInput.deep.l');
  assert.equal(args[3], 'objectInput.deep.l');
  assert.deepEqual(args[2], { offsetLeft: 'objectInput.deep.l'.length, offsetRight: 0 });
  assert.equal(f.ref.current, f.range);
});
test('正常前后片段/空模板/关闭/箭头循环/Enter/Delete与原参考真实hook对照一致', () => {
  const run = source => {
    const result = [];
    for (const context of [{ content: '{{foobar}}', cursor: 5 }, { content: '{{}}', cursor: 2 },
      { content: 'ordinary text', cursor: 3 }, { focusedKey: null }, { focusedKey: 'last' },
      { focusedKey: 'first' }, { options: [] }]) {
      const f = fixture({ ...context, source });
      f.api.handleKeyDown(f.event('ArrowDown'));
      f.api.onKeyUp(f.event('a'));
      f.api.handleDropdownKeyDown(f.event('ArrowDown'));
      f.api.handleDropdownKeyDown(f.event('ArrowUp'));
      f.api.handleDropdownKeyDown(f.event('Enter'));
      f.api.handleKeyDown(f.event('Delete'));
      f.api.handleKeyDown(f.event('a'));
      result.push(plain({ changes: f.changes, replace: f.replace.current, insert: f.insert.current,
        savedRange: f.ref.current === f.range }));
    }
    return result;
  };
  const actual = run(current);
  assert.deepEqual(actual, run(reference));
  assert.ok(actual[4].changes.some(change => change[0] === 'focused' && change[1] === 'first'), '末项向下循环到首项');
  assert.ok(actual[5].changes.some(change => change[0] === 'focused' && change[1] === 'last'), '首项向上循环到末项');
  assert.ok(actual[5].changes.some(change => change[0] === 'select' && change[1][0] === 'first'));
});
function dropdown(source = current, extra = {}) {
  let props;
  const selections = [];
  const module = execute(declarations(source, ['DropDownList']) + '\nexport { DropDownList };', {
    React, useMemoizedFn: require('ahooks').useMemoizedFn,
    FlowTree: value => { props = value; return React.createElement('div', null,
      ...value.treeData.map(item => React.createElement(React.Fragment, { key: item.id }, value.titleRender(item)))); },
  });
  const html = renderToString(React.createElement(module.DropDownList, { showDropdown: true,
    focusedKey: 'actual', keyboardNavigationActive: true, matchingInformation: { keyWord: 'foo', matchingKeyWord: 'fo' },
    handleTreeSelect: keys => selections.push(keys), dropdownRef: { current: null },
    dropdownPosition: { top: 30, left: 12 }, handleDropdownKeyDown() {}, hasData: true, noProperties: true,
    treeData: [{ id: 'actual', label: 'foobar' }], ...extra }));
  return { html, props, selections };
}
test('实际原选项展示保留高亮/焦点/位置/空子项状态，点击传原变量ID', () => {
  const actual = dropdown();
  assert.equal(actual.html, dropdown(reference).html);
  assert.ok(actual.html.includes('color:#4d53e8') && actual.html.includes('color:#ff9600'));
  assert.deepEqual(plain(actual.props.selectedKeys), ['actual']);
  const title = actual.props.titleRender(actual.props.treeData[0]);
  let stopped = 0;
  title.props.onClick({ stopPropagation: () => stopped++ });
  assert.equal(stopped, 1);
  assert.deepEqual(plain(actual.selections), [['actual']]);
  assert.equal(dropdown(current, { showDropdown: false }).html, '');
  assert.ok(dropdown(current, { hasData: false }).html.includes('该变量无子变量'));
});
test('没有焦点或原变量ID时不给Tree伪造选中键，也不触发错误选择', () => {
  const f = dropdown(current, { focusedKey: null, treeData: [{ label: '缺ID' }] });
  assert.deepEqual(plain(f.props.selectedKeys), []);
  f.props.titleRender(f.props.treeData[0]).props.onClick({ stopPropagation() {} });
  assert.deepEqual(f.selections, []);
});
