"""仅解析源文件和统计，不 import 产品、不运行节点/API/服务/测试。"""
import ast
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[5]
TARGET = ROOT / 'python/paiflow-runtime'
REFERENCE = ROOT / '参考项目/PaiFlow-main/core'
OUTPUT = Path(__file__).with_name('Worker迁移代码核对.json')

def source(path):
    return path.read_text(encoding='utf-8-sig')

def stats(folder, pattern):
    ignored = {'__pycache__', '.venv', 'node_modules', 'target', 'build', 'dist', '_reference', 'upstream-dependencies'}
    files = sorted(path for path in folder.rglob(pattern) if not ignored.intersection(path.relative_to(folder).parts))
    tests = [path for path in files if {'test','tests','testing'}.intersection(path.relative_to(folder).parts)
             or path.name.startswith('test_') or path.name.endswith('_test.py') or path.name == 'conftest.py']
    production = [path for path in files if path not in tests]
    def measure(paths):
        return {'files': len(paths), 'lines': sum(len(source(path).splitlines()) for path in paths)}
    return {'total': measure(files), 'production': measure(production), 'test': measure(tests)}

def methods(path, classname):
    node = next(node for node in ast.parse(source(path)).body if isinstance(node, ast.ClassDef) and node.name == classname)
    return sorted(member.name for member in node.body if isinstance(member, (ast.FunctionDef, ast.AsyncFunctionDef)))

registry_tree = ast.parse(source(TARGET / 'workflow/engine/nodes/cache_node.py'))
classes = {node.targets[0].id: [node.value.args[0].value, node.value.args[1].value]
    for node in registry_tree.body if isinstance(node, ast.Assign) and isinstance(node.value, ast.Call)
    and isinstance(node.value.func, ast.Name) and node.value.func.id == 'LazyNodeClass'}
registry = next(node.value for node in registry_tree.body if isinstance(node, ast.Assign)
    and any(isinstance(target, ast.Name) and target.id == 'tool_classes' for target in node.targets))
rows = []
ports = {
 'ifly-code': ['sandbox.execute', '受控执行器/权限/隔离实现仍须中心接入'],
 'spark-llm': ['model.invoke/frames/cancel、history/read/write', '4类模型中心生产端待补'],
 'extractor-parameter': ['model.invoke chat或Spark function_call', '4类模型中心生产端待补'],
 'decision-making': ['model.invoke chat或Spark function_call、history/read/write', '4类模型中心生产端待补'],
 'question-answer': ['checkpoint/reply；动态direct模型填槽model.invoke', '静态恢复已有中心；动态模型生产端待补；原option动态方法为pass'],
 'node-variable': ['variables get/get_all/set、正式executionContext', '中心本轮已补端口源码/DDL草案，未数据库执行'],
 'knowledge-base': ['knowledge.retrieve', '知识实体/固定检索资源/真实检索生产端缺失'],
 'knowledge-pro-base': ['knowledge.answer/frames/cancel', '专业RAG检索回答生产端缺失，不能别名普通retrieve'],
 'plugin': ['tools.read或tools.invoke', '固定OpenAPI资源/权限/实际Link出口生产端缺失'],
 'flow': ['workflow.invoke/workflow-frames/cancel', '中心父子run关联/版本/终态流生产端缺失'],
 'agent': ['固定agent/模型/工具/MCP/子流程/知识依赖；model.invoke及对应子操作', '新增原ReAct受控工厂；生产资源和逐子操作许可尚缺，继续明确拒绝'],
 'database': ['data.query/data.mutate', '参数化正式事务生产端缺失；CUSTOM SQL明确拒绝'],
 'rpa': ['rpa.execute/frames/cancel', '正式Runner/固定设备与权限/停止证明生产端缺失'],
}
for key, value in zip(registry.keys, registry.values):
    kind = key.value
    module, classname = classes[value.id]
    relative = Path(*module.split('.')).with_suffix('.py')
    original_methods, target_methods = methods(REFERENCE / relative, classname), methods(TARGET / relative, classname)
    rows.append({'type': kind, 'class': classname, 'reference': (REFERENCE / relative).relative_to(ROOT).as_posix(),
        'target': (TARGET / relative).relative_to(ROOT).as_posix(), 'sourceMethods': original_methods,
        'targetMethods': target_methods, 'missingMethods': sorted(set(original_methods) - set(target_methods)),
        'port': ports.get(kind, ['原Factory/Chains/DFS/VariablePool/Callbacks算法', '纯算法已接线；迭代只准入单层纯节点'])[0],
        'scope': ports.get(kind, ['', '纯算法已接线；迭代只准入单层纯节点'])[1]})

syntax_files = sorted(path for directory in ('worker','workflow','agent','plugin','common','knowledge','sandbox')
    for path in (TARGET / directory).rglob('*.py'))
syntax_errors = []
for path in syntax_files:
    try:
        ast.parse(source(path), filename=str(path))
    except (SyntaxError, UnicodeError) as error:
        syntax_errors.append({'file': path.relative_to(ROOT).as_posix(), 'error': type(error).__name__})

source_counts = {directory: {'reference': stats(REFERENCE / directory, '*.py'),
                            'target': stats(TARGET / directory, '*.py')}
    for directory in ('workflow','agent','plugin/link','plugin/aitools','common')}
java_reference = ROOT / '参考项目/PaiFlow-main/core-workflow-java/src/main/java'
java_target = ROOT / 'ruoyi-backend/paiflow-worker-java/src/main/java'
java_executors = []
for name in ('StartNodeExecutor','EndNodeExecutor','LLMNodeExecutor','PluginNodeExecutor'):
    original = next(java_reference.rglob(name + '.java'))
    target = java_target / original.relative_to(java_reference)
    java_executors.append({'class': name, 'reference': original.relative_to(ROOT).as_posix(),
        'target': target.relative_to(ROOT).as_posix(), 'targetExists': target.is_file(),
        'sourceLines': len(source(original).splitlines()), 'targetLines': len(source(target).splitlines())})

record = {'scope': '2026-10-04 Worker源码/逻辑核对；仅AST与已执行独立Java离线skip-tests编译，未运行产品测试/API/Provider/数据库/服务',
    'countMeaning': '文件/总文本行数仅工作量，不能当成功率；节点方法同名保留不能证明业务接通',
    'definition': '只计指定目录内源码，total保留原始全量；production/test分别统计，tests/test/testing目录、test_或_test.py和conftest.py归测试。排除构建/缓存/.venv/node_modules/_reference/upstream-dependencies。目标剥离原测试、旧控制面、启动与运维脚本，文件和行数下降不作为丢失业务算法的依据。',
    'sourceCounts': source_counts, 'workerPython': stats(TARGET / 'worker', '*.py'),
    'javaReference': stats(java_reference, '*.java'), 'javaTarget': stats(java_target, '*.java'),
    'pythonNodes': rows, 'javaExecutors': java_executors,
    'ast': {'parsedFiles': len(syntax_files), 'errors': syntax_errors},
    'javaCompile': {'command': 'mvn -o -DskipTests compile', 'result': 'BUILD SUCCESS',
        'compiledSources': 87, 'meaning': '独立Java Worker源码编译成功；未运行测试或真实中心/外部调用'},
    'closingJavaCompile': {'command': 'mvn -o -DskipTests compile', 'result': 'BUILD SUCCESS',
        'compilerMessage': 'Nothing to compile - all classes are up to date.', 'elapsedSeconds':4.755,
        'finishedAt': '2026-10-04T16:41:11+08:00', 'meaning': '收口复核沿用本轮已编译87源文件，未重新编译或测试'},
    'hashes': {path.relative_to(ROOT).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest() for path in syntax_files},
    'existingEvidence': ['Worker包内导入修正.json', '修改前原文/worker/'],
    'unverified': ['本轮所有业务测试/真实API/Provider/数据库/安装服务/前端均未执行',
        '旧271项等历史测试不证明本轮源码变更', '原上游未实现服务与缺运行配置不当作迁移遗漏；中心受控Producer缺代码则属真缺口']}
OUTPUT.write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'AST': record['ast'], 'nodes': len(rows), 'missingMethods': sum(len(row['missingMethods']) for row in rows),
                  'counts': source_counts, 'workerPython': record['workerPython'], 'javaTarget':record['javaTarget']}, ensure_ascii=False))
if syntax_errors:
    raise SystemExit(1)
