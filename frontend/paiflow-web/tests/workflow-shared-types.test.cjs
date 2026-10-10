const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const ts = require('typescript');
const fs = require('node:fs');

const project = path.resolve(__dirname, '..');
const configPath = path.join(project, 'tsconfig.json');
const config = ts.readConfigFile(configPath, ts.sys.readFile);
const parsed = ts.parseJsonConfigFileContent(config.config, ts.sys, project);
const utilitySource = fs.readFileSync(path.join(project, 'src/components/workflow/utils/reactflowUtils.ts'), 'utf8');
const utilityAst = ts.createSourceFile('utils.ts', utilitySource, ts.ScriptTarget.Latest, true);
const actualSearch = utilityAst.statements.filter(statement =>
  (ts.isFunctionDeclaration(statement) && statement.name?.text === 'findItemById')
  || (ts.isInterfaceDeclaration(statement) && statement.name.text === 'SearchableWorkflowItem'))
  .map(statement => statement.getText(utilityAst)).join('\n');

function diagnostics(source) {
  const fixture = path.join(__dirname, '__workflow_shared_types_fixture__.ts');
  const options = { ...parsed.options, noEmit: true };
  const host = ts.createCompilerHost(options);
  const originalRead = host.readFile.bind(host);
  const originalExists = host.fileExists.bind(host);
  host.readFile = file => path.resolve(file) === fixture ? source : originalRead(file);
  host.fileExists = file => path.resolve(file) === fixture || originalExists(file);
  const originalGet = host.getSourceFile.bind(host);
  host.getSourceFile = (file, languageVersion, ...rest) => path.resolve(file) === fixture
    ? ts.createSourceFile(file, source, languageVersion, true)
    : originalGet(file, languageVersion, ...rest);
  return ts.getPreEmitDiagnostics(ts.createProgram([fixture], options, host));
}

test('原hook变更回调按实际入参保留string/boolean/number类型与第三个节点参数', () => {
  const errors = diagnostics(`
    import type { UseNodeFuncReturn, UseNodeInputRenderReturn, UseNodeOutputRenderReturn } from '../src/components/workflow/types/hooks';
    declare const functions: UseNodeFuncReturn;
    declare const inputs: UseNodeInputRenderReturn;
    declare const renderer: UseNodeOutputRenderReturn;
    functions.handleChangeOutputParam('output', (output, value, node) => {
      output.name = value;
      node.label = value;
    }, '名称');
    functions.handleChangeOutputParam('output', (output, value) => { output.required = value; }, true);
    functions.handleChangeNodeParam((node, value) => { node.nodeParam.mode = value; }, 1);
    inputs.handleChangeInputParam('input', (input, value) => { input.name = value; }, '输入');
    renderer.renderOutputComponent({ id: 'output', name: 'result', schema: { type: 'string' } });
  `);
  assert.deepEqual(errors.map(error => ts.flattenDiagnosticMessageText(error.messageText, '\n')), []);
});

test('原hook具体入参不再伪装unknown，数值写入名称仍被strict拒绝', () => {
  const errors = diagnostics(`
    import type { UseNodeFuncReturn } from '../src/components/workflow/types/hooks';
    declare const functions: UseNodeFuncReturn;
    functions.handleChangeOutputParam('output', (output, value) => { output.name = value; }, 42);
  `);
  assert.equal(errors.length, 1);
  assert.equal(errors[0].code, 2322);
  assert.ok(errors[0].file.fileName.endsWith('__workflow_shared_types_fixture__.ts'));
});

test('实际递归查找返回叶子可选schema，统一引用树泛型保留value字段', () => {
  const errors = diagnostics(`
    import type { WorkflowProperty, OutputType, ReferenceType } from '../src/components/workflow/types';
    import type { UseNodeOutputRenderReturn, UseNodeModelsReturn } from '../src/components/workflow/types/hooks';
    ${actualSearch}
    const outputs: OutputType[] = [{ id: 'root', name: 'result', schema: { type: 'object', properties: [
      { id: 'child', name: 'child', type: 'string', default: '' }
    ] }}];
    const found = findItemById(outputs, 'child');
    if (found) { found.name = 'renamed'; const type: string | undefined = found.schema?.type; }
    const refs: ReferenceType[] = [{ id: 'ref', value: 'variable', label: '变量' }];
    const ref = findItemById(refs, 'ref');
    if (ref) { const value: string = ref.value; }
    declare const renderer: UseNodeOutputRenderReturn;
    renderer.renderOutputComponent({ id: 'leaf', name: 'leaf', type: 'number', default: 0 });
    const numericCatalog: UseNodeModelsReturn = { models: [{ llmId: 42, extension: { approved: true } }],
      model: { llmId: 42 }, isThinkModel: undefined };
  `);
  assert.deepEqual(errors.map(error => ts.flattenDiagnosticMessageText(error.messageText, '\n')), []);
});

test('实际递归查找不会把原直接叶子伪装成必带schema的顶层输出', () => {
  const errors = diagnostics(`
    import type { WorkflowProperty, OutputType } from '../src/components/workflow/types';
    ${actualSearch}
    const properties: WorkflowProperty[] = [{ id: 'leaf', name: 'leaf', type: 'string' }];
    const found = findItemById(properties, 'leaf');
    if (found) { const output: OutputType = found; }
  `);
  assert.equal(errors.length, 1);
  assert.equal(errors[0].code, 2322);
  assert.ok(errors[0].file.fileName.endsWith('__workflow_shared_types_fixture__.ts'));
});

test('结束/迭代真实props不要求模型凭据，并保留模板与streamOutput具体类型', () => {
  const errors = diagnostics(`
    import type { EndDetailProps, IteratorDetailProps, UseNodeCommonReturn } from '../src/components/workflow/types';
    const end: EndDetailProps = { id: 'node-end::end', data: { nodeParam: {
      outputMode: 1, template: '{{result}}', reasoningTemplate: '{{reason}}', streamOutput: true,
    } } };
    const iterator: IteratorDetailProps = { id: 'iteration::iterator', data: { nodeParam: { IterationStartNodeId: 'inner-start' } } };
    declare const common: UseNodeCommonReturn;
    const isEnd: boolean = common.isEndNode;
  `);
  assert.deepEqual(errors.map(error => ts.flattenDiagnosticMessageText(error.messageText, '\n')), []);
});

test('结束节点props拒绝数值模板和文本streamOutput，不放宽原字段类型', () => {
  const errors = diagnostics(`
    import type { EndDetailProps } from '../src/components/workflow/types';
    const end: EndDetailProps = { id: 'node-end::end', data: { nodeParam: { template: 42, streamOutput: 'true' } } };
  `);
  assert.equal(errors.length, 2);
  assert.ok(errors.every(error => error.code === 2322 && error.file.fileName.endsWith('__workflow_shared_types_fixture__.ts')));
});

test('模板编辑器与Inputs实际props接线保留文本回调、DOMblur和可选禁加', () => {
  const errors = diagnostics(`
    import type { FlowTemplateEditorProps, InputsProps } from '../src/components/workflow/types';
    const editor: FlowTemplateEditorProps = { id: 'node-end::end', data: { nodeParam: {} }, value: '{{result}}',
      onChange: value => { const text: string = value; },
      onBlur: event => { const div: HTMLDivElement = event.currentTarget; }, minHeight: 100 };
    const iteration: InputsProps = { id: 'iteration::parent', data: { nodeParam: {
      enableChatHistoryV2: { isEnabled: true, rounds: 3 }
    } }, allowAdd: false, children: '输入' };
    const ordinary: InputsProps = { id: 'message::ordinary', data: { nodeParam: {} } };
  `);
  assert.deepEqual(errors.map(error => ts.flattenDiagnosticMessageText(error.messageText, '\n')), []);
});

test('模板编辑器与Inputs具体字段拒绝数值文本及文本禁加，不以unknown放宽', () => {
  const errors = diagnostics(`
    import type { FlowTemplateEditorProps, InputsProps } from '../src/components/workflow/types';
    const editor: FlowTemplateEditorProps = { id: 'end', data: { nodeParam: {} }, value: 42 };
    const inputs: InputsProps = { id: 'iteration', data: { nodeParam: {} }, allowAdd: 'false' };
  `);
  assert.equal(errors.length, 2);
  assert.ok(errors.every(error => error.code === 2322 && error.file.fileName.endsWith('__workflow_shared_types_fixture__.ts')));
});

test('模板类型收口保留原编辑组件/输入/键盘/选项展示运行代码，变量hook单独核对', () => {
  const reference = path.join(project, '../../参考项目/PaiFlow-main/console/frontend/src/components/workflow/ui/flow-template-editor.tsx');
  const current = path.join(project, 'src/components/workflow/ui/flow-template-editor.tsx');
  const compile = file => {
    const text = fs.readFileSync(file, 'utf8');
    const ast = ts.createSourceFile('editor.tsx', text, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
    // 渲染/effect/dropdown/input 的边界修复有实际回归和原参考对照；未修改的主组件继续逐字对照。
    const names = ['FlowTemplateEditor'];
    const actual = ast.statements.filter(statement => ts.isVariableStatement(statement)
      && statement.declarationList.declarations.some(declaration => names.includes(declaration.name.getText(ast))))
      .map(statement => statement.getText(ast)).join('\n');
    return ts.transpileModule(actual, {
    fileName: 'flow-template-editor.tsx',
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.React },
    }).outputText;
  };
  assert.equal(compile(current), compile(reference));
});

test('模板变量hook复用实际递归对象而非string数组，filter offset使用左右长度对象', () => {
  const errors = diagnostics(`
    import type { UseFlowTemplateEditorReturn, TemplateVariableOption } from '../src/components/workflow/types';
    declare const editor: UseFlowTemplateEditorReturn;
    const options: TemplateVariableOption[] = [{ id: 'literal', label: 'literal' },
      { id: 'object', label: 'object', value: 'payload', children: [{ id: 'child', label: 'child', value: 'payload.child' }] }];
    const filtered: TemplateVariableOption[] = editor.filterArr(options, 'object.', { offsetLeft: 7, offsetRight: 0 }, 'object.');
    const available: TemplateVariableOption[] = editor.inputsOption;
  `);
  assert.deepEqual(errors.map(error => ts.flattenDiagnosticMessageText(error.messageText, '\n')), []);
});

test('模板变量hook拒绝旧错误string数组和数值offset声明', () => {
  const errors = diagnostics(`
    import type { UseFlowTemplateEditorReturn } from '../src/components/workflow/types';
    declare const editor: UseFlowTemplateEditorReturn;
    editor.filterArr(['object'], 'object.', 7, 'object.');
  `);
  assert.ok(errors.some(error => error.code === 2322 && error.file.fileName.endsWith('__workflow_shared_types_fixture__.ts')));
});

test('文本处理具体参数、异步刷新目录及真实TreeProps支持原父组件形状', () => {
  const treeAst = ts.createSourceFile('tree.tsx', fs.readFileSync(path.join(project, 'src/components/workflow/ui/flow-tree.tsx'), 'utf8'),
    ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const treeFunction = treeAst.statements.find(statement => ts.isFunctionDeclaration(statement) && statement.name.text === 'FLowTree');
  const treeSignature = treeFunction.getText(treeAst).slice(0, treeFunction.body.pos - treeFunction.getStart(treeAst));
  const errors = diagnostics(`
    import type { ChangeTextNodeParameter, TextNodeConfig, TextHandleDetailProps } from '../src/components/workflow/types';
    import type { FlowsManagerStoreType } from '../src/components/workflow/types/zustand/flowsManager';
    import type { TreeProps } from 'antd';
    import type { BasicDataNode, DataNode } from 'antd/es/tree';
    ${treeSignature} { throw new Error('仅检查真实公开声明，运行渲染由构建和父组件检查覆盖'); }
    declare const change: ChangeTextNodeParameter;
    change('mode', 1); change('prompt', '{{input}}'); change('separator', ',');
    const detail: TextHandleDetailProps = { id: 'text-joiner::id', data: { nodeParam: { mode: 1, separator: ',' } } };
    declare const manager: FlowsManagerStoreType;
    const refreshed: Promise<TextNodeConfig[]> = manager.removeTextNodeConfig('own');
    manager.setTextNodeConfigList(current => [...current, { id: 3, uid: 'owner', separator: ',', comment: null }]);
    const parent: TreeProps<DataNode & { metadata: { keep: true } }> = {
      treeData: [{ key: 'result', title: '输出', metadata: { keep: true } }], showLine: true,
      titleRender: node => node.metadata.keep ? node.key.toString() : ''
    };
    FLowTree(parent);
    FLowTree({ treeData: [{ key: 'result', title: '输出' }] });
    FLowTree({ treeData: [{ label: '自定义字段', value: '自定义key' }], fieldNames: { title: 'label', key: 'value' } });
    void detail; void refreshed; void parent;
  `);
  assert.deepEqual(errors.map(error => ts.flattenDiagnosticMessageText(error.messageText, '\n')), []);
});

test('文本参数不会放宽数值prompt/string mode，目录仍拒绝未知响应直接赋值', () => {
  const errors = diagnostics(`
    import type { ChangeTextNodeParameter } from '../src/components/workflow/types';
    import type { FlowsManagerStoreType } from '../src/components/workflow/types/zustand/flowsManager';
    declare const change: ChangeTextNodeParameter;
    declare const manager: FlowsManagerStoreType;
    declare const response: unknown;
    change('prompt', 42); change('mode', '1'); manager.setTextNodeConfigList(response);
  `);
  assert.equal(errors.length, 3);
  assert.ok(errors.every(error => error.code === 2345 && error.file.fileName.endsWith('__workflow_shared_types_fixture__.ts')));
});

test('实际公共类型容纳原混合对象属性、清空引用与多层引用树', () => {
  const errors = diagnostics(`
    import type { InputType, OutputType, ReferenceType } from '../src/components/workflow/types';
    const literal: InputType = { id: 'input', name: 'question', schema: {
      type: 'string', value: { type: 'literal', content: '问题' }
    }};
    const cleared: InputType = { ...literal, schema: {
      type: 'string', value: { type: 'ref', content: {} }
    }};
    const outputs: OutputType[] = [{ id: 'result', name: 'result', schema: {
      type: 'object', default: { count: 0 }, properties: [
        { id: 'count', name: 'count', type: 'number', default: 0 },
        { id: 'nested', name: 'nested', schema: { type: 'object', properties: [
          { id: 'file', name: 'file', schema: { type: 'string' }, allowedFileType: ['pdf'] }
        ] }}
      ]
    }}];
    const references: ReferenceType[] = [{ label: '上游', value: 'node', parentNode: true,
      children: [{ label: '', value: '', references: [{ id: 'result', label: 'result',
        value: 'result', type: 'object', children: [{ id: 'count', label: 'count',
          value: 'result.count', type: 'number' }]
      }] }]
    }];
    void cleared; void outputs; void references;
  `);
  assert.deepEqual(errors.map(error => ts.flattenDiagnosticMessageText(error.messageText, '\n')), []);
});

test('公共类型仍拒绝单对象 children 与数字型输入 content', () => {
  const errors = diagnostics(`
    import type { InputType, ReferenceType } from '../src/components/workflow/types';
    const input: InputType = { id: 'input', name: 'question', schema: {
      type: 'string', value: { type: 'literal', content: 42 }
    }};
    const tree: ReferenceType = { label: '节点', value: 'node', children: {
      label: '字段', value: 'field'
    }};
    void input; void tree;
  `);
  assert.equal(errors.length, 2);
  assert.ok(errors.every(error => error.file && error.file.fileName.endsWith('__workflow_shared_types_fixture__.ts')));
});

test('代码/Agent/模型专属参数复用公共画布结构，不强制其他节点提供LLM凭据', () => {
  const errors = diagnostics(`
    import type { NodeDataType, CodeNodeData, AgentNodeData, ExtractorParameterDetailProps } from '../src/components/workflow/types';
    import type { NodeDataType as HookData, InputItem, OutputItem, SchemaType } from '../src/components/workflow/types/hooks';
    const code: CodeNodeData = { nodeParam: { code: 'result=1', extension: { keep: true } }, outputs: [] };
    const agent: AgentNodeData = { nodeParam: { instruction: { query: '问题', answer: '答案' }, maxLoopCount: 3 } };
    const model: ExtractorParameterDetailProps = { id: 'extractor-parameter::id', data: {
      nodeParam: { model: 'spark', maxTokens: 1024, temperature: 0.5 }, outputs: []
    } };
    const common: NodeDataType = { nodeParam: {}, label: '普通节点' };
    const hook: HookData = common;
    const schema: SchemaType = { type: 'string', value: { type: 'literal', content: '问题' } };
    const input: InputItem = { id: 'input', name: 'question', schema: { ...schema, value: { type: 'ref', content: {} } } };
    const output: OutputItem = { id: 'output', name: 'answer', schema: { type: 'number', default: 0 } };
    void code; void agent; void model; void hook; void input; void output;
  `);
  assert.deepEqual(errors.map(error => ts.flattenDiagnosticMessageText(error.messageText, '\n')), []);
});

test('专属参数仍拒绝代码数值和Agent错误指令结构', () => {
  const errors = diagnostics(`
    import type { CodeNodeData, AgentNodeData } from '../src/components/workflow/types';
    const code: CodeNodeData = { nodeParam: { code: 42 } };
    const agent: AgentNodeData = { nodeParam: { instruction: '错误结构' } };
    void code; void agent;
  `);
  assert.equal(errors.length, 2);
  assert.ok(errors.every(error => error.file && error.file.fileName.endsWith('__workflow_shared_types_fixture__.ts')));
});
