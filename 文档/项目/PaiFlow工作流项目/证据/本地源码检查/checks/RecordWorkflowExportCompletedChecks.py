"""根据实际冻结产物局部追加唯一记录；不重跑检查，不覆盖原文。"""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import shutil

ROOT = Path(__file__).resolve().parents[6]
DOC = ROOT / '文档/项目/PaiFlow工作流项目'
EV = DOC / '证据/本地源码检查'
C = EV / 'checks'

def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()

def read(p):
    return json.loads(p.read_text('utf-8-sig'))

def ref(p):
    assert p.is_file(), p
    return {'path': p.relative_to(ROOT).as_posix(), 'sha256': sha(p)}

def save_new(p, value):
    assert not p.exists(), p
    p.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

def main():
    aggregate = EV / '工作流YAML导出本地源码检查.json'
    proof = C / '工作流YAML导出记录局部追加检查.json'
    assert not aggregate.exists() and not proof.exists()
    gate_path = C / '工作流YAML导出非法ID定向复验冻结输入.json'
    gate = read(gate_path)
    assert len(gate['inputs']) == 20
    assert all(sha(ROOT / item['path']) == item['sha256'] for item in gate['inputs'])
    merged_path = EV / '工作流YAML导出定向检查合并结果.json'
    merged = read(merged_path)
    assert merged['actualTestExecutions'] == 53 and merged['freshUniqueIdentities'] == 52
    assert merged['latestIdentityTotals'] == {'tests': 52, 'failures': 0, 'errors': 0, 'skipped': 0}
    ledger_path = EV / '工作流YAML导出检查用例沿用核对.json'
    ledger = read(ledger_path)
    assert ledger['currentTests'] == 597
    assert tuple(len(ledger[k]) for k in ('carriedUnchanged', 'rerunExisting', 'newlyExecuted')) == (545, 32, 20)
    index_path = EV / '工作流YAML导出接口索引冻结核对.json'
    index = read(index_path)
    assert index['currentInputCounts']['java'] == 111
    assert index['currentInputCounts']['frontend'] == 562 and index['currentInputCounts']['supporting'] == 95
    assert index['summary']['originalEndpointVariants'] == 389 and index['summary']['targetPaiflowEndpointVariants'] == 176
    assert index['summary']['businessRuntimeVerifiedOriginalRoutes'] == 0
    wire_path = EV / '工作流YAML导出实际MVC响应.json'
    wire = read(wire_path)
    assert wire['actualStatus'] == 200 and wire['bodyUTF8Bytes'] == 1790
    assert wire['beforeTruth'] == wire['afterTruth']
    consumer_path = C / '工作流YAML导出实际MVC原React下载消费核对.json'
    consumer = read(consumer_path)
    assert consumer['positiveDownloadEffectsEqual'] and not consumer['sourceWindowChanges']
    build_path = EV / '工作流YAML下载原React生产构建结果.json'
    build = read(build_path)
    assert build['status'] == 'technical_checks_passed' and build['sourceInputCount'] == 1703 and build['outputCount'] == 242
    assert not build['sourceWindowChanges']
    preservation_path = C / '工作流YAML导出局部范围保全核对.json'
    preservation = read(preservation_path)
    assert preservation['designOnlyOneParagraphInserted'] and preservation['frontGuardAndThreeTemporaryUrlRenamesExactlyInvertToPreservedOriginal']
    assert len(preservation['frontendChanges']) == 1 and not preservation['runtimeChangesFromLast271Execution']
    independent_path = C / '工作流YAML导出最终事实独立核对.json'
    independent = read(independent_path)
    assert not independent['inconsistencies']
    maven_input_path = EV / '工作流YAML导出非法ID定向复验输入.json'
    maven_inputs = read(maven_input_path)['files']
    changed_after = [{'path': p, 'atMaven': h, 'current': sha(ROOT / p)} for p, h in maven_inputs.items() if sha(ROOT / p) != h]
    assert {row['path'] for row in changed_after} == {'contracts/paiflow/generate-source-api-inventory.py', 'contracts/paiflow/source-api-inventory.json'}
    names = ['checks/工作流原YAML导出合同金样.json', 'checks/工作流YAML导出当前投影独立原合同核对.json',
        'checks/工作流YAML下载JSON判别原React独立核对.json', 'checks/工作流YAML导出安全边界独立核对.json',
        'checks/工作流YAML导出TDZ修后最终独立核对.json', 'checks/工作流YAML导出RFD断言局部更正核对.json',
        'checks/工作流YAML导出设计修改前原文/输入.json', 'checks/工作流YAML下载URL遮蔽修复前原文/输入.json',
        'checks/工作流YAML导出辅助重载编译修复冻结输入.json']
    payload = {'schemaVersion': 'paiflow.workflow-export.local-checks.v1', 'recordedAt': datetime.now(timezone.utc).isoformat(),
        'scope': '原当前草稿YAML下载；沿用ReactFlow/AntDesign/Recoil/Zustand/ReactRouter与页面',
        'finalGate': ref(gate_path), 'java': {**ref(merged_path), 'freshIdentities': 52, 'actualExecutions': 53, 'retainedFailure': True, 'sourceInputsEach': 231},
        'identityLedger': {**ref(ledger_path), 'current': 597, 'carried': 545, 'rerunExisting': 32, 'new': 20, 'whole597Rerun': False},
        'index': ref(index_path), 'afterMavenAuthorizedIndexChanges': changed_after,
        'actualIsolatedMvc': {**ref(wire_path), 'bytes': 1790, 'status': 200, 'currentRevision': 2, 'unchangedLatestVersionPointer': 1, 'beforeAfterTruthEqual': True},
        'actualWireConsumer': ref(consumer_path), 'frontendBuild': {**ref(build_path), 'sourceInputs': 1703, 'outputFiles': 242, 'installedOrPublished': False},
        'preservation': ref(preservation_path), 'independentFacts': ref(independent_path),
        'references': [ref(EV / name) for name in names], 'runtime271Rerun': False, 'frontendPrior110Rerun': False,
        'wholeFrontendTypeCheckRerun': False, 'wholeFrontendTypeCheckPassed': False,
        'limits': ['原13金样并非全部作为当前成功输入；4已知typed投影实际同源比对，unknown/layout/null与精确UID处理按批准设计扩展。',
            '读取当前草稿，保非秘密资源引用，不建立App/版本/Run或执行导入。', '当前权限与事务为随机H2实际测试，登录主体受控，不是完整JWT/Redis/真实数据库检查。',
            '原/current AST内存消费者与生产打包不等于浏览器下载或业务验收。', '已有19份SQL未在真实MySQL执行；模块default OFF，旧数据/UID/App映射与完整迁移仍未完成。'],
        'realDbWrites': False, 'providerCalls': 0, 'servicesStarted': False, 'gitWrites': False, 'businessVerified': False, 'migrationComplete': False}
    record = DOC / '设计资料核对记录.md'
    readme = ROOT / 'ruoyi-backend/ruoyi-paiflow/README.md'
    assert sha(record) == '0ab2ebc23039e2fd514db5898c12663c77321b4df18c9f4f1cb710f4d9a6a4eb'
    assert sha(readme) == 'c3a261f4361688fb6edce8bb57475b277c3715f607e67462820ccf8346b6e7a8'
    backup = C / '工作流YAML导出记录追加前原文'
    assert not backup.exists()
    backup.mkdir()
    copies = []
    for path in (record, readme):
        destination = backup / path.name
        shutil.copy2(path, destination)
        copies.append({'path': path.relative_to(ROOT).as_posix(), 'copy': destination.relative_to(ROOT).as_posix(), 'sha256': sha(path)})
    save_new(backup / '输入.json', {'scope': 'Doc26/README当前未提交正文原字节保全，随后只追加第27节', 'copies': copies})
    save_new(aggregate, payload)
    addition = '''

## 27. 2026-10-03原工作流YAML下载接通

按完整设计3.4局部补齐原画布按钮和智能体页共用的`GET /workflow/export/{id}`，目标入口为`/paiflow/legacy/workflow/export/{id}`。复用原Long内部ID、平台workflow查询权限、真实空间/成员和既有默认个人域；默认域不存在时只拒绝读取，不新建空间。导出读取当前草稿，生成后再次核对当前权限、主体、工作流存在和所在域；不编译、创建版本、派发、访问Provider或建立App绑定。新增[只读Service](../../../ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/WorkflowExportService.java)与[原下载Controller](../../../ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/adapter/legacy/LegacyWorkflowExportController.java)，既有Java/SQL/平台设施原文保留，见[局部保全核对](证据/本地源码检查/checks/工作流YAML导出局部范围保全核对.json)。

[原合同金样](证据/本地源码检查/checks/工作流原YAML导出合同金样.json)实际运行原DTO和原导出片段，共13个来源夹具；当前4个合法已知字段投影与原结果实际相等，另核对8类原字段/泛型/别名及无初始化默认值，见[同源投影核对](证据/本地源码检查/checks/工作流YAML导出当前投影独立原合同核对.json)。flowMeta只保原7字段的非null值并加dslVersion v1，advancedConfig保持原JSON字符串；flowData按原Biz字段类型保数值/布尔/字符串转换，未知布局、开放业务值及显式null按已批准设计2.2保全。不能称原13夹具全部作为当前成功输入：原JSON null画布与损坏节点在当前中心拒绝，原NON_NULL裁剪和当前未知/null保全也有明确设计差异。

原页面会写入节点发起者UID，只在导出副本的精确`flowData/nodes/i/data/nodeParam/uid`路径将非空String清空，并附不含值的中文YAML注释；空串/null保留，非字符串拒绝，原存储不变。业务default、schema、HTTP参数里的同名UID及非秘密app/model/repo/service引用继续保留，不能因此获得授权；凭据仍按唯一JSON边界拒绝，不靠静默删字段制造可用文件。已存canvas/metadata/advanced按既有16MiB对象边界核对，完整JSON wrapper和实际UTF8 YAML均限32MiB；有界Writer在生成时阻止缩进放大，不等生成大正文后才拒绝。独立纯方法检查与其范围见[安全边界核对](证据/本地源码检查/checks/工作流YAML导出安全边界独立核对.json)。

附件成功保持原200/octet-stream/UTF8及numeric filename；本入口局部异常映射仍使用若依AjaxResult并返回真实4xx/5xx。共享异常与认证设施原文未改；认证过滤器可能以HTTP200返回错误JSON，故原现有http工具只在workflow export分支新增application/json判别。首版新增判别暴露原临时url变量遮蔽，失败原文/日志已保全，只将三处临时变量改名后，原/current实际AST各12场景通过：正常YAML与其他合法文件保持一致，3个工作流200 JSON拒绝是批准差异，见[下载矩阵](证据/本地源码检查/checks/工作流YAML下载JSON判别原React独立核对.json)。本批前端562源中仅既有http一处变化，引入1703个原文件仍为1691原字节和12个既有适配；ReactFlow、Ant Design、Recoil、Zustand、React Router及页面布局继续沿用。

首Maven在新增测试辅助方法与父类重载冲突处停止，实际0项执行；只改测试内辅助名。随后[六类52项实际结果](证据/本地源码检查/工作流YAML导出辅助重载修复后六类定向检查结果.json)为51通过、1断言失败：非法ID实际已400，但Spring 7.0.7为带扩展名的路径自动补`inline;filename=f.txt`安全头。核对本次真实jar后仅修该断言，保持非附件/JSON400/无expose/无YAML/Service零调用，[单项复验](证据/本地源码检查/工作流YAML导出非法ID定向复验结果.json)通过，其余51未重复运行。实际共53次测试执行，最新52个不同身份通过，每次231输入窗0；新增20、既有32重跑，[当前身份597](证据/本地源码检查/工作流YAML导出检查用例沿用核对.json)为545沿用加52本批身份，不称597全量重跑。编译失败、首次断言失败及修前当前原文都保留。

12项真实H2/Mapper/事务检查覆盖当前草稿而非旧版本、原typed字段、未知/null/UID保全、秘密与损坏字段拒绝、已有默认域、只读成员、SQL撤权/成员移除/主体变化及最终工作流删除或改域、生成预算与零写入；8项MVC合同明确mock Service。[真实H2/MVC响应](证据/本地源码检查/工作流YAML导出实际MVC响应.json)为1790字节，实际200、octet-stream、UTF8、Content-Length和正文hash一致；revision2当前草稿导出，latestVersionId1仅是未改变的既有指针，不是导出所选版本。导出前后canvas/metadata/advanced/revision/version及Run/事件/Checkpoint/空间计数一致。 [实际原React消费](证据/本地源码检查/checks/工作流YAML导出实际MVC原React下载消费核对.json)把同一真实wire交原/current按钮及XHR AST，Blob逐字相同、各创建/click/revoke一次，下载名仍由原调用方给出为中文😀工作流.yml；假token/store/DOM观察点受控，不等于浏览器、完整认证或业务验收。

[原React最新生产打包](证据/本地源码检查/工作流YAML下载原React生产构建结果.json)实际通过，1703输入窗0、242输出，写入独立证据目录，未覆盖既有dist或安装/发布。整工程类型检查本批未重跑，历史3698条诊断尚不能记通过；此前110项前端检查及Python271项只沿用，Python478源与271执行冻结相同。本批[源码索引](源码覆盖索引.md)生成/核对各一次，现111Java/176路由/562前端/95支持，原389/68来源/365导出/23直接调用基线保全；本批Maven后仅授权更新generator与inventory两份输入，业务验证仍0。[最终独立事实核对](证据/本地源码检查/checks/工作流YAML导出最终事实独立核对.json)只核对冻结原文、实际证据和身份，不重跑检查。

001至019 SQL仍未在真实MySQL执行；本批无DDL、旧包导入、真实数据库/Provider/Redis、服务启动、浏览器验收、发布或Git写入。模块default OFF，正式App及旧UID/资源映射尚缺真实配置，YAML下载通过不表示导入或完整迁移完成。下一已确认缺口为画布初始化的节点模板与两类策略目录三GET，按[来源细化](证据/本地源码检查/checks/工作流初始化三GET最小范围来源细化.json)继续实施。当前产物集中于[本批单一汇总](证据/本地源码检查/工作流YAML导出本地源码检查.json)，本节追加前Doc26/README已[原字节保全](证据/本地源码检查/checks/工作流YAML导出记录追加前原文/输入.json)。
'''
    readme_addition = '''

原工作流当前草稿YAML下载现接通`GET /paiflow/legacy/workflow/export/{id}`，只读现行授权工作流，不编译、建版本/Run或调用资源。原7字段meta+v1与typed字段保原语义，未知布局/null按设计保全；精确节点发起者UID仅导出副本清空并中文注释，业务UID/非秘密引用保留。JSON wrapper与实际UTF8 YAML各32MiB有界，成功保原octet-stream/UTF8。既有http工具仅新增workflow导出200 JSON拒绝，原React技术栈及页面沿用。

本批6类52项先51通过/1框架RFD头断言失败，只修该断言后单项通过，实际53次执行；当前597身份为545沿用加52本批身份，未重跑全套。真实H2/Mapper/事务/MVC输出1790字节且读取前后真相相同，原/current XHR内存消费同字节；最新React生产打包1703输入窗0/242输出通过，独立检查目录产物未安装或发布，整工程类型仍未通过。首编译0项、首失败/反例与复验证据均保留，准确范围见[唯一实施记录第27节](../../文档/项目/PaiFlow工作流项目/设计资料核对记录.md#27-2026-10-03原工作流YAML下载接通)及[本批单一汇总](../../文档/项目/PaiFlow工作流项目/证据/本地源码检查/工作流YAML导出本地源码检查.json)。default OFF；无真实数据库/Provider/Redis、旧包导入、服务启动、业务验收、发布或Git写，完整迁移继续推进。
'''
    for path, text in ((record, addition), (readme, readme_addition)):
        original = path.read_bytes()
        newline = '\r\n' if b'\r\n' in original else '\n'
        encoded = text.replace('\n', newline).encode('utf-8')
        path.write_bytes(original + encoded)
        assert path.read_bytes()[:len(original)] == original
    save_new(proof, {'scope': '只追加唯一Doc27和README，两份前缀原字节保全', 'backup': ref(backup / '输入.json'),
        'record': ref(record), 'readme': ref(readme), 'aggregate': ref(aggregate), 'prefixesExactlyPreserved': True, 'businessVerified': False})
    print(json.dumps({'aggregate': ref(aggregate), 'appendProof': ref(proof)}, ensure_ascii=False), flush=True)

if __name__ == '__main__':
    main()
