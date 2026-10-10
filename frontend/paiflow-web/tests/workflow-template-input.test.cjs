const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const React = require('react');
const { renderToString } = require('react-dom/server');
const project = path.resolve(__dirname, '..');
const current = fs.readFileSync(path.join(project, 'src/components/workflow/ui/flow-template-editor.tsx'), 'utf8');
const reference = fs.readFileSync(path.join(project, '../../参考项目/PaiFlow-main/console/frontend/src/components/workflow/ui/flow-template-editor.tsx'), 'utf8');
const utils = fs.readFileSync(path.join(project, 'src/components/workflow/utils/index.ts'), 'utf8');
const plain = value => JSON.parse(JSON.stringify(value));
function declarations(source, names) {
  const ast = ts.createSourceFile('actual.tsx', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  return ast.statements.filter(statement => ts.isVariableStatement(statement)
    && statement.declarationList.declarations.some(declaration => names.includes(declaration.name.getText(ast))))
    .map(statement => statement.getText(ast)).join('\n');
}
function fixture({ source = current, content = '{{objectInput.deep.l', cursor = content.length,
  selection = true, line = true, text = content, options = [{ id: 'object', label: 'objectInput',
    children: [{ id: 'deep', label: 'deep', children: [{ id: 'leaf', label: 'leaf' }] }] }], replace = false } = {}) {
  const changes = [];
  const child = { nodeType: 3, textContent: content };
  const parent = { nodeType: 1, tagName: 'DIV', childNodes: [child] };
  child.parentNode = line ? parent : null;
  const range = { startOffset: cursor, startContainer: child, getBoundingClientRect: () => ({ x: 12, y: 30 }) };
  const nativeSelection = selection === true ? { rangeCount: 1, getRangeAt: () => range }
    : selection === 'empty' ? { rangeCount: 0 } : null;
  const exports = {};
  vm.runInNewContext(ts.transpileModule(`
    ${declarations(utils, ['getCurrentLineContent', 'findPathToNode', 'findNodeByKey', 'handleReplaceInput', 'handleReplaceSpan'])}
    ${declarations(source, ['useFlowTemplateInput'])}
    export { useFlowTemplateInput };
  `, { fileName: 'actual.tsx', compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText, {
    exports, useMemoizedFn: require('ahooks').useMemoizedFn, ...require('lodash'),
    window: { getSelection: () => nativeSelection }, Node: { ELEMENT_NODE: 1, TEXT_NODE: 3 }, setTimeout,
  });
  let api;
  const savedSelection = { current: null };
  const insert = { current: { cursorPosition: 0, offset: { offsetLeft: 0, offsetRight: 0 } } };
  const editor = { current: text === null ? null : { innerText: text } };
  const pasting = { current: false }, composing = { current: false }, replacement = { current: replace };
  renderToString(React.createElement(() => {
    api = exports.useFlowTemplateInput({ inputsOption: options, treeData: options,
      filterArr: (...args) => { changes.push(['filter', plain(args)]); return options; },
      willInertInfo: insert, currentSelection: savedSelection,
      setShowDropdown: value => changes.push(['show', value]), setTreeData: value => changes.push(['tree', plain(value)]),
      setDropdownPosition: value => changes.push(['position', plain(value)]),
      setMatchingInformation: value => changes.push(['matching', plain(value)]),
      getCursorPosition: () => changes.push(['cursor']), setFocusedKey: value => changes.push(['focused', value]),
      setKeyboardNavigationActive: value => changes.push(['keyboard', value]),
      setIsFirstOpen: value => changes.push(['first', value]), onChange: value => changes.push(['saved', value]),
      editorRef: editor, isPastingRef: pasting, setEditorContent: value => changes.push(['content', value]),
      setTemplateValue: value => changes.push(['template', value]), replaceSpanRef: replacement,
      insertOption: (...args) => changes.push(['insert', ...args]), isComposingRef: composing,
    });
    return null;
  }));
  return { api, changes, savedSelection, insert, range, editor, pasting, replacement };
}
test('点击缺Selection/空选区/缺当前行时安全收起，不伪造插入定位', () => {
  for (const context of [{ selection: false }, { selection: 'empty' }, { line: false }]) {
    const f = fixture(context);
    assert.doesNotThrow(() => f.api.handleClick());
    assert.equal(f.savedSelection.current, null);
    assert.deepEqual(plain(f.insert.current), { cursorPosition: 0, offset: { offsetLeft: 0, offsetRight: 0 } });
    assert.deepEqual(f.changes, [['show', false], ['focused', null], ['keyboard', false], ['first', false]]);
  }
});
test('点击未闭合嵌套模板使用原输入片段，不附加undefined', () => {
  const f = fixture();
  f.api.handleClick();
  const args = f.changes.find(change => change[0] === 'filter')[1];
  assert.equal(args[1], 'objectInput.deep.l');
  assert.equal(args[3], 'objectInput.deep.l');
  assert.equal(f.savedSelection.current, f.range);
  assert.equal(f.insert.current.cursorPosition, '{{objectInput.deep.l'.length);
});
test('普通点击/闭合前后片段/空模板定位与原参考hook一致', () => {
  const run = source => ['ordinary', '{{}}', '{{objectInput.deep.leaf}}'].map(content => {
    const f = fixture({ source, content, cursor: content === '{{}}' ? 2 : content.includes('{{') ? 19 : 3 });
    f.api.handleClick();
    return plain({ changes: f.changes, insert: f.insert.current, savedRange: f.savedSelection.current === f.range });
  });
  assert.deepEqual(run(current), run(reference));
});
test('原延迟保存保留换行/粘贴重置、连续编辑仅保存最后值，与参考一致', async () => {
  const run = async source => {
    const f = fixture({ source, content: 'ordinary text', text: 'one\n\ntwo' });
    f.api.handleInput();
    f.pasting.current = true;
    f.editor.current.innerText = 'pasted\n\ntext';
    f.api.handleInput();
    f.editor.current.innerText = 'latest\n\ntext';
    f.api.handleInput();
    await new Promise(resolve => setTimeout(resolve, 550));
    return plain({ changes: f.changes, pasting: f.pasting.current });
  };
  const actual = await run(current);
  assert.deepEqual(actual, await run(reference));
  assert.deepEqual(actual.changes.filter(change => change[0] === 'saved'), [['saved', 'latest\ntext']]);
  assert.deepEqual(actual.changes.filter(change => change[0] === 'content'), [['content', 'pasted\n\ntext']]);
  assert.equal(actual.pasting, false);
});
test('没有选区/当前行或DOM卸载仍保存真实文本，跳过光标DOM转换', async () => {
  for (const context of [{ selection: false }, { selection: 'empty' }, { line: false }, { text: null }]) {
    const f = fixture({ content: 'ordinary text', text: 'input\n\ntext', ...context });
    assert.doesNotThrow(() => f.api.handleInput());
    await new Promise(resolve => setTimeout(resolve, 550));
    const expected = context.text === null ? '' : 'input\ntext';
    assert.deepEqual(f.changes.filter(change => change[0] === 'saved'), [['saved', expected]]);
    assert.deepEqual(f.changes.filter(change => change[0] === 'template'), [['template', expected]]);
  }
});
test('原树路径选择区分叶子/父项并触发保存，失效或空选择不插入空路径', async () => {
  const run = async source => {
    const f = fixture({ source, content: 'ordinary text', text: 'chosen' });
    f.api.handleTreeSelect(['leaf']);
    f.api.handleTreeSelect(['deep']);
    await new Promise(resolve => setTimeout(resolve, 550));
    return plain(f.changes);
  };
  const actual = await run(current);
  assert.deepEqual(actual, await run(reference));
  assert.deepEqual(actual.filter(change => change[0] === 'insert'), [['insert', 'objectInput.deep.leaf', true], ['insert', 'objectInput.deep', false]]);
  const stale = fixture({ content: 'ordinary text' });
  stale.api.handleTreeSelect(['removed-option']);
  stale.api.handleTreeSelect([]);
  assert.deepEqual(stale.changes, []);
});
