const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const React = require('react');
const { renderToString } = require('react-dom/server');
const project = path.resolve(__dirname, '..');
const relative = 'src/components/workflow/nodes/components/';
const refRoot = path.join(project, '../../参考项目/PaiFlow-main/console/frontend');
function statements(source, names) {
  const ast = ts.createSourceFile('actual.tsx', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  return ast.statements.filter(statement => (ts.isFunctionDeclaration(statement) && names.includes(statement.name?.text))
    || (ts.isVariableStatement(statement) && statement.declarationList.declarations.some(declaration => names.includes(declaration.name.getText(ast)))))
    .map(statement => statement.getText(ast)).join('\n');
}
function compile(source) {
  return ts.transpileModule(source, { fileName: 'actual.tsx', compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, jsx: ts.JsxEmit.React, removeComments: true,
  } }).outputText;
}
// TS 给带注解的单箭头参数保留括号；括号擦除格式不改变实际运行表达式。
const normalizeArrowFormatting = code => code.replace(/\(([A-Za-z_$][\w$]*)\) =>/g, '$1 =>');
function nameElement(item, useReference = false) {
  const exports = {};
  const source = fs.readFileSync(path.join(useReference ? refRoot : project, relative, 'fixed-inputs/index.tsx'), 'utf8');
  const utils = fs.readFileSync(path.join(project, 'src/components/workflow/utils/reactflowUtils.ts'), 'utf8');
  vm.runInNewContext(compile(`${statements(utils, ['isWorkflowDisplayValue'])}
    ${statements(source, ['InputName'])}\nexport { InputName };`), { exports, React, Tooltip: 'tooltip', desciptionIcon: 'original-icon.png' });
  return exports.InputName({ item });
}
function tooltipTitle(element) {
  return React.Children.toArray(element.props.children).find(child => React.isValidElement(child) && child.type === 'tooltip')?.props.title;
}
test('固定输入对象默认值可作为真实React提示内容显示，不抛对象child异常', () => {
  const title = tooltipTitle(nameElement({ name: 'payload', schema: { type: 'object' }, default: { message: '中文', count: 0 } }));
  assert.equal(renderToString(React.createElement(React.Fragment, null, title)), '{&quot;message&quot;:&quot;中文&quot;,&quot;count&quot;:0}');
});
test('固定输入原文字/数值/数组提示及required标记与参考一致，description仍优先', () => {
  for (const item of [{ name: 'name', required: true, description: '原说明', default: '原默认' },
    { name: 'count', default: 9 }, { name: 'tags', default: ['a', 'b'] }, { name: 'false', default: false },
    { name: 'empty', default: null }]) {
    assert.equal(renderToString(nameElement(item)), renderToString(nameElement(item, true)));
    assert.deepEqual(tooltipTitle(nameElement(item)), tooltipTitle(nameElement(item, true)));
  }
});
test('固定输出/单输入/历史组件类型补正擦除后保留原组件与store运行代码', () => {
  for (const file of ['fixed-outputs/index.tsx', 'single-input/index.tsx', 'chat-history/index.tsx']) {
    const names = file.startsWith('single-input') ? ['EnabledChatHistory', 'index'] : ['index'];
    const current = fs.readFileSync(path.join(project, relative, file), 'utf8');
    const reference = fs.readFileSync(path.join(refRoot, relative, file), 'utf8');
    assert.equal(normalizeArrowFormatting(compile(statements(current, names))), normalizeArrowFormatting(compile(statements(reference, names))), file);
  }
  for (const [file, name] of [['start/index.tsx', 'StartDetail'], ['rpa/index.tsx', 'RpaDetail']]) {
    const full = 'src/components/workflow/nodes/' + file;
    const current = fs.readFileSync(path.join(project, full), 'utf8');
    const reference = fs.readFileSync(path.join(refRoot, full), 'utf8');
    assert.equal(normalizeArrowFormatting(compile(statements(current, [name]))), normalizeArrowFormatting(compile(statements(reference, [name]))), file);
  }
});
function outputView({ iteration = false, disabled = false, sourceRoot = project, directProps } = {}) {
  const exports = {};
  const hook = ({ id }) => ({ isIteratorStart: id.startsWith('iteration-node-start::'),
    outputs: [], isStartNode: id.startsWith('node-start::'), handleAddOutputLine() {},
    addUniqueComponentToProperties: outputs => outputs });
  const source = fs.readFileSync(path.join(sourceRoot, relative, 'outputs/index.tsx'), 'utf8');
  vm.runInNewContext(compile(`${statements(source, ['index'])}\nexport { index };`), { exports, React,
    useMemo: React.useMemo, useNodeCommon: hook, useTranslation: () => ({ t: key => key }),
    cloneDeep: require('lodash').cloneDeep, useFlowsManager: select => select({ canvasesDisabled: disabled }),
    FLowCollapse: ({ label, content }) => React.createElement('div', null, label, content),
    FLowTree: () => React.createElement('div', null, '原树入口'), inputAddIcon: 'original-add.png' });
  const data = { nodeParam: {}, outputs: [] };
  let element;
  if (directProps) element = React.createElement(exports.index, { id: 'node-start::fixed', data, ...directProps }, '原输入标题');
  else {
    const startExports = {};
    const startSource = fs.readFileSync(path.join(sourceRoot, 'src/components/workflow/nodes/start/index.tsx'), 'utf8');
    vm.runInNewContext(compile(statements(startSource, ['StartDetail'])), { exports: startExports, React, memo: React.memo,
      useTranslation: () => ({ t: key => key }), useNodeCommon: hook, OutputParams: exports.index });
    element = React.createElement(startExports.StartDetail, { id: iteration ? 'iteration-node-start::fixed' : 'node-start::fixed', data });
  }
  return renderToString(element);
}
test('真实迭代StartDetail→Outputs已禁加入口不显示加行，普通Start仍保留原操作布局', () => {
  assert.ok(!outputView({ iteration: true }).includes('workflow.nodes.common.add'));
  assert.ok(outputView().includes('workflow.nodes.common.add'));
  assert.equal(outputView(), outputView({ sourceRoot: refRoot }));
  assert.ok(!outputView({ disabled: true }).includes('workflow.nodes.common.add'));
  assert.ok(!outputView({ directProps: { disabled: true } }).includes('workflow.nodes.common.add'));
  assert.ok(!outputView({ directProps: { allowAdd: false } }).includes('workflow.nodes.common.add'));
});
test('实际节点输入元数据和历史清空值类型保留，错误字段类型仍被strict拒绝', () => {
  const config = ts.readConfigFile(path.join(project, 'tsconfig.json'), ts.sys.readFile);
  const options = ts.parseJsonConfigFileContent(config.config, ts.sys, project).options;
  const fixture = path.join(__dirname, '__common_io_types__.ts');
  const source = `
    import type { InputType, NodeDataType, InputsProps } from '../src/components/workflow/types';
    const input: InputType = {id:'input-id',name:'payload',required:true,description:'业务说明',
      default:{message:'中文',count:0},schema:{type:'object',value:{type:'literal',content:'{}'}}};
    const data: NodeDataType = {nodeParam:{enableChatHistoryV2:{isEnabled:true,rounds:null}},inputs:[input]};
    const props: Pick<InputsProps,'id'|'data'> = {id:'spark-llm::fixed',data};
    input.description = 9;
    data.nodeParam.enableChatHistoryV2!.rounds = 'three';
    const invalid: Pick<InputsProps,'id'|'data'> = {id:99,data};
    const missingData: Pick<InputsProps,'id'|'data'> = {id:'spark-llm::fixed'};
  `;
  const host = ts.createCompilerHost({...options,noEmit:true});
  const read = host.readFile.bind(host), exists = host.fileExists.bind(host), get = host.getSourceFile.bind(host);
  host.readFile = file => path.resolve(file) === fixture ? source : read(file);
  host.fileExists = file => path.resolve(file) === fixture || exists(file);
  host.getSourceFile = (file, version, ...rest) => path.resolve(file) === fixture
    ? ts.createSourceFile(file, source, version, true) : get(file, version, ...rest);
  const errors = ts.getPreEmitDiagnostics(ts.createProgram([fixture], {...options,noEmit:true}, host));
  assert.equal(errors.length, 4, errors.map(error => ts.flattenDiagnosticMessageText(error.messageText, '\n')).join('\n'));
  assert.ok(errors.every(error => [2322,2741].includes(error.code) && path.resolve(error.file.fileName) === fixture));
});
