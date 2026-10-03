"""按已冻结实际产物追加唯一记录；保全旧正文，不执行源码、索引或业务操作。"""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[6]
DOCS = ROOT / '文档/项目/PaiFlow工作流项目'
EVIDENCE = DOCS / '证据/本地源码检查'
CHECKS = EVIDENCE / 'checks'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def ref(path):
    assert path.is_file(), path
    return {'path': path.relative_to(ROOT).as_posix(), 'sha256': sha(path)}


def new_json(path, data):
    assert not path.exists(), path
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def main():
    aggregate = EVIDENCE / '调试显示历史本地源码检查.json'
    append_proof = CHECKS / '调试显示历史记录局部追加检查.json'
    assert not aggregate.exists() and not append_proof.exists()
    gate_path = CHECKS / '调试显示历史八类定向检查冻结输入.json'
    gate = read(gate_path)
    assert len(gate['inputs']) == 12
    assert all(sha(ROOT / v['path']) == v['sha256'] for v in gate['inputs'])
    results_path = EVIDENCE / '调试显示历史八类定向检查结果.json'
    results = read(results_path)
    assert results['totals'] == {'tests': 97, 'failures': 0, 'errors': 0, 'skipped': 0}
    assert results['exitCode'] == 0 and results['sourceInputCount'] == 227
    assert not results['sourceWindowChanges'] and not results['missingCases'] and not results['unexpectedCases']
    maven_input_path = EVIDENCE / '调试显示历史八类定向检查输入.json'
    maven_inputs = read(maven_input_path)['files']
    post_maven_changes = [{'path': p, 'mavenFrozenSha256': h, 'currentSha256': sha(ROOT / p)}
                          for p, h in maven_inputs.items() if sha(ROOT / p) != h]
    assert {v['path'] for v in post_maven_changes} == {
        'contracts/paiflow/generate-source-api-inventory.py', 'contracts/paiflow/source-api-inventory.json'}
    ledger_path = EVIDENCE / '调试显示历史检查用例沿用核对.json'
    ledger = read(ledger_path)
    assert ledger['currentTests'] == 577
    assert (len(ledger['carriedUnchanged']), len(ledger['rerunExisting']), len(ledger['newlyExecuted'])) == (480, 74, 23)
    index_path = EVIDENCE / '调试显示历史接口索引冻结核对.json'
    index = read(index_path)
    assert index['currentInputCounts']['java'] == 109
    assert index['currentInputCounts']['frontend'] == 562 and index['currentInputCounts']['supporting'] == 93
    assert index['summary']['originalEndpointVariants'] == 389
    assert index['summary']['targetPaiflowEndpointVariants'] == 175
    assert index['summary']['businessRuntimeVerifiedOriginalRoutes'] == 0
    assert not index['auditMismatches'] and all(not v for v in index['currentSnapshotMismatches'].values())
    actual_path = EVIDENCE / '调试显示历史实际MVC响应.json'
    actual = read(actual_path)
    budget_path = EVIDENCE / '调试显示历史实际大答案预算.json'
    budget = read(budget_path)
    assert actual['runtimeTruthUnchanged'] and budget['runtimeTruthUnchangedByDisplay']
    assert budget['runResultUTF8Bytes'] == 1100105 and budget['originalEightFieldDisplayUTF8Bytes'] == 2200519
    assert budget['storedOriginalAnswerEqual'] and budget['storedOriginalAnswerItemAfterOuterQuoteRemovalEqual']
    assert budget['runResultLimitUnchanged'] == 2097152 and budget['controlLimitUnchanged'] == 16384
    consumer_path = CHECKS / '调试显示历史实际MVC原React独立消费核对.json'
    consumer = read(consumer_path)
    assert consumer['currentResultsEqualOriginal'] and not consumer['sourceWindowChanges']
    assert consumer['astCasesEach'] == 6 and consumer['realLeafSSREach'] == 6
    assert consumer['rows']['count'] == 3 and consumer['rows']['fieldCountEach'] == 13
    assert consumer['rows']['clearIntegerCounts'] == [3, 0] and consumer['rows']['afterClear'] == []
    independent_path = CHECKS / '调试显示历史最终事实独立核对.json'
    independent = read(independent_path)
    # 独立核对的事实字段由其执行者维护；这里只引用已实际保存的产物，不重写结论。
    assert not independent['inconsistencies']
    evidence_names = [
        'checks/调试显示历史显示预算修正后源码冻结输入.json',
        'checks/调试显示历史显示预算补例持久化源码冻结输入.json',
        'checks/工作流原调试对话历史合同金样.json',
        'checks/工作流调试对话JSON形状独立核对.json',
        'checks/工作流调试对话数组项独立核对.json',
        'checks/调试显示历史首版答案显示反例独立核对.json',
        'checks/调试显示历史问题名称React子节点独立反例.json',
        'checks/调试显示历史授权存取修后独立源码审查.json',
        'checks/调试显示历史显示预算独立差异补记.json',
        'checks/工作流原调试对话双份答案字节边界独立核对.json',
        'checks/调试显示历史局部追加与前端运行器保全核对.json',
        'checks/调试显示历史实际MVC原React独立消费核对.json',
        'checks/调试显示历史最终事实独立核对.json',
        'checks/调试显示历史设计与共享入口修改前原文/输入.json',
        'checks/调试显示历史记录追加前原文/输入.json',
        'checks/调试显示历史索引更新前原文/输入.json',
    ]
    preservation = read(CHECKS / '调试显示历史局部追加与前端运行器保全核对.json')
    assert preservation['frontendSourcesCompared'] == 562 and not preservation['frontendChanges']
    assert preservation['runtimeSourcesCompared'] == 478 and not preservation['runtimeChanges']
    payload = {
        'schemaVersion': 'paiflow.workflow-dialog.local-checks.v1',
        'recordedAt': datetime.now(timezone.utc).isoformat(),
        'scope': '原工作流type=1调试显示历史追加、本人最近10条和软清除；保留原React前端与同一Run真相',
        'authorization': '用户授权按修订方案开始本地源码迁移；前端外壳保持原React技术栈',
        'productionGate': ref(CHECKS / '调试显示历史显示预算修正后源码冻结输入.json'),
        'mavenGate': ref(gate_path),
        'actualMaven': {**ref(results_path), 'tests': 97, 'classes': 8, 'failures': 0, 'errors': 0, 'skipped': 0,
                        'newTests': 23, 'existingRerun': 74, 'sourceInputs': 227, 'sourceWindowChanges': []},
        'afterMavenAuthorizedIndexChanges': {'inputManifest': ref(maven_input_path), 'files': post_maven_changes,
                                             'scope': '测试期间227窗0；之后授权更新两份索引输入，不能称当前227全同hash；12份代码/设计冻结仍匹配'},
        'testIdentityLedger': {**ref(ledger_path), 'currentIdentities': 577, 'carriedUnchanged': 480,
                               'freshActual': 97, 'full577Rerun': False},
        'actualMVC': {**ref(actual_path), 'listUTF8Bytes': consumer['wireChecks']['list']['utf8Bytes'],
                      'listSha256': consumer['wireChecks']['list']['sha256'], 'rows': 3, 'fieldsEach': 13,
                      'clearIntegerCounts': [3, 0], 'afterClear': [], 'runtimeTruthUnchanged': True},
        'actualLargeAnswer': {**ref(budget_path), 'runResultUTF8Bytes': 1100105,
                             'originalEightFieldDisplayUTF8Bytes': 2200519, 'storedAnswerUTF8Bytes': budget['storedAnswerUTF8Bytes'],
                             'storedAnswerItemUTF8Bytes': budget['storedAnswerItemUTF8Bytes'],
                             'wholeBodyRejectedUTF8Bytes': budget['wholeBodyRejectedUTF8Bytes'],
                             'fieldRejectedUTF8Bytes': budget['fieldRejectedUTF8Bytes'],
                             'fieldRejectedWholeBodyUTF8Bytes': budget['fieldRejectedWholeBodyUTF8Bytes'],
                             'displayBodyLimitBytes': 20971520, 'displayFieldLimitBytes': 16777215,
                             'runResultLimitUnchangedBytes': 2097152, 'controlLimitUnchangedBytes': 16384,
                             'largeWireDeliberatelyOmitted': True},
        'independentConsumer': {**ref(consumer_path), 'originalAndCurrentASTCasesEach': 6,
                                'realReactLeafSSREach': 6, 'sourceInputs': 21, 'sourceWindowChanges': [],
                                'limits': 'Markdown/Image/JSONPretty等叶子依赖受控；未运行页面、真实HTTP或大正文回放'},
        'sourceIndex': {**ref(index_path), 'java': 109, 'routes': 175, 'frontend': 562, 'supporting': 93,
                        'originalRoutes': 389, 'originalSourceFiles': 68, 'originalFrontendExports': 365,
                        'directCalls': 23, 'frontendImportedFiles': 1703, 'originalBytesRetained': 1691,
                        'existingAdapters': 12, 'newFrontendChangesThisBatch': 0, 'businessVerifiedRoutes': 0},
        'retainedUnchangedTechnicalEvidence': {'frontendSources': 562, 'runtimeSources': 478,
                                              'frontend110Rerun': False, 'runtime271Rerun': False},
        'evidence': [ref(EVIDENCE / name) for name in evidence_names],
        'implementedBoundaries': [
            'POST仅中心当前用户拥有的实际Run、同空间/工作流/固定debug版本与现行权限；每POST新增显示段，不以SID、内容或chatId去重，也不要求Run终态。',
            '显示UID与管理字段由中心产生；客户端正文仅是显示观察，不能覆盖Run结果、事件、检查点、版本或授权。',
            'GET list返回本人未删除type=1最近10条、原13 nullable字段；clear只本人软删并返回Integer，清后Run/事件/等待检查点逐列保持。',
            'question数组/null及answer对象/null严格解析但保全原字符串、未知字段、明确null和falsy；仅拒绝已被原React证实无法显示的子节点/字符串转换形状。',
            'answerItem沿原规则仅去首尾一层ASCII引号，不做JSON解转义；缺type查询核权限后空/0，type=2正式App明确503。',
        ],
        'limitations': [
            'H2 Mapper/事务与MockMVC是真实隔离执行，账号/调度仍受控；MySQL、JWT、Redis、Provider、浏览器业务验收未执行。',
            '019及001至018只为未执行MySQL草案，不创建/初始化新业务库；原业务连接与旧UID/App/Run映射仍待核实。',
            '原question/question_item为TEXT、answer/answer_item为LONGTEXT、data为MEDIUMTEXT；当前显示表5字段MEDIUMTEXT预算不构成旧数据长度或导入证明，旧数据不得截断。',
            '正式App会话/history、历史变量和全局变量、真实资源/渠道、旧历史导入与跨进程恢复仍按完整迁移方案接续；本批不能宣告全迁移完成。',
            '前端当前完整类型检查仍未通过、最新生产bundle未重建；本批前端源码零变化，不冒称页面已验收。',
        ],
        'servicesStarted': False, 'network': False, 'realDatabaseWrites': False, 'sqlExecuted': False,
        'oldDataImported': False, 'providerCalled': False, 'gitWrites': False, 'published': False,
        'businessVerified': False, 'migrationComplete': False,
    }
    doc = DOCS / '设计资料核对记录.md'
    readme = ROOT / 'ruoyi-backend/ruoyi-paiflow/README.md'
    backups = CHECKS / '调试显示历史记录追加前原文'
    old_doc, old_readme = (backups / '1-设计资料核对记录.md').read_bytes(), (backups / '2-README.md').read_bytes()
    assert sha(doc) == 'be5f149707ce9d2abedec190ddded5a5738ef3bea192fb9db1502de815c783e1'
    assert sha(readme) == 'c8eae1f640fee5cadc623730906784478de52f0c7baa4a1c0261e7a58ced9da7'
    assert doc.read_bytes() == old_doc and readme.read_bytes() == old_readme
    assert b'## 26.' not in old_doc
    new_json(aggregate, payload)
    doc_add = '''

## 26. 2026-10-03原工作流调试显示历史接通

本节按完整设计3.4局部接通原`POST /workflow/dialog`、`GET /workflow/dialog/list`与`GET /workflow/dialog/clear`，复用legacy请求入口、平台权限、现有空间和中心Run。原ReactFlow、Ant Design、Recoil、Zustand、React Router及页面继续保留，本批前端生产源码零变化。设计只增加一段显示历史要求，共享WorkflowService和DebugBridge只追加中心版本/Run核对入口；[当前原文保全](证据/本地源码检查/checks/调试显示历史设计与共享入口修改前原文/输入.json)与[局部保全核对](证据/本地源码检查/checks/调试显示历史局部追加与前端运行器保全核对.json)保留修改前未提交字节，没有用Git HEAD覆盖现状。

[原合同金样](证据/本地源码检查/checks/工作流原调试对话历史合同金样.json)核定8字段保存请求、chatId字符串/null返回、13字段历史行、最近10条和清除Integer计数。sid为本次真实Run ID，内部workflowId为Long，不把flow UUID或旧Spark SID当关联。同一Run中的多段静态QA分别追加，Run还在等待时也能保存；每个POST沿原insert语义新增，不以相同SID、chatId或正文制造幂等。保存必须匹配当前用户拥有的实际Run、空间、工作流、实际固定debug版本及既有DSL/hash/引擎/当前权限；客户端uid、管理ID、时间、版本和删除状态不能授权。显示正文只保存用户可见观察，不替代正式结果或运行真相。

[WorkflowDialogService](../../../ruoyi-backend/ruoyi-paiflow/src/main/java/com/ruoyi/paiflow/service/WorkflowDialogService.java)保留question数组/null与answer对象/null的原JSON字符串、未知字段、明确null及falsy，使用严格JSON读取拒绝尾随正文和损坏Unicode。根据原React实际叶子SSR反例，只拒绝会在原数组显示、React子节点或字符串转换中报错的形状；隐藏名称/选项与合法空值保持原语义。answerItem只沿原规则去首尾一层ASCII引号，不解转义。列表在当前权限下只查本人type=1未删最近10条，按时间和ID倒序；清除只软删本人显示行，返回实际计数。缺type的查询/清除先核权限后空/0，正式App type=2仍明确503，不伪造会话。

原页面会把答案重复编码进8字段请求，[同源字节核对](证据/本地源码检查/checks/工作流原调试对话双份答案字节边界独立核对.json)确认单份有效Run结果可能产生超过2MiB的显示POST，因此显示整体预算独立为20MiB，五个文本字段各按MEDIUMTEXT的UTF-8上限16MiB−1核对；Run结果2MiB、控制消息16KiB保持。[实际大答案产物](证据/本地源码检查/调试显示历史实际大答案预算.json)确认1,100,105字节真实Run结果及2,200,519字节原8字段显示请求成功，答案与去外层引号显示项原串相等；23,069,177字节整体与18,000,000字节单字段分别413且零新增，后者整包18,000,676仍小于20MiB。证据只留长度/hash，不输出大正文。独立授权源码核对为16项事实，预算修正另有[限定差异补记](证据/本地源码检查/checks/调试显示历史显示预算独立差异补记.json)，不把静态审查当事务执行。

11:12:13 UTC开始的[八类定向检查](证据/本地源码检查/调试显示历史八类定向检查结果.json)实际97项全部通过，0失败/错误/跳过、exit0，227输入读取窗变化0。新增真实H2 Mapper/平台菜单/空间/Workflow/Run/事务14项与mock Service MVC合同9项；既有构建、流、QA、纯迭代及编解码74项本轮实际重跑。[身份沿用账](证据/本地源码检查/调试显示历史检查用例沿用核对.json)当前577为480未受影响沿用加97本轮执行，不能称577全部本轮重跑。H2实际覆盖同Run重复追加、稳定最后10条、本人清除2后0且其他有效成员记录保留、权限撤销后的实际写入回滚、主体/固定版本/快照错配及严格显示边界。H2仅对夹具UTC_TIMESTAMP参数做已有方言适配，生产SQL和数据约束未放宽。

[真实H2/MVC响应](证据/本地源码检查/调试显示历史实际MVC响应.json)原UTF-8列表1418字节、200 application/json、3行各13字段；实际保存、查询、clear计数3再0、清后空数组，前后Run、事件及等待Checkpoint逐列相同。首8字段请求由[原/当前消费者独立核对](证据/本地源码检查/checks/调试显示历史实际MVC原React独立消费核对.json)的两版handleSave生成同形，读取/保存/清除各6 AST场景及各6真实React叶子SSR一致，21输入读窗0；falsy和outer-null两条H2保存样本只作为消费证据，不称原handleSave自然生成全部样本。首次私有断言误把appendDivider=true尾分隔线要求到默认回读，原script/log保留，仅修私有预期，生产0改。Markdown/Image/JSONPretty等依赖受控，没有挂页面或真实HTTP；大答案仅核已存长度/hash，不重造大正文。

[接口索引](源码覆盖索引.md)生成与核对各一次，现109Java/175路由/562前端/93支持文件；原389接口、68来源、365服务导出与23直接调用基线保全，引入1703文件仍为1691原字节加12既存适配，本批前端差异0。764输入生成窗与Maven227窗分别保存；Maven执行后授权更新generator和inventory两份索引输入，不称当前227文件全部同hash，最终12份代码/设计冻结仍匹配，业务验证仍0。Python478源保持前271冻结，仅沿用271；前110同源检查也只沿用。整工程类型仍未通过，最新bundle未重建，不以本节技术结果冒称页面通过。

[019显示记录草案](../../../ruoyi-backend/ruoyi-paiflow/sql/019-paiflow-workflow-dialog.sql)及001至018未在真实MySQL执行；没有新建/克隆业务库。原question/question_item为TEXT，answer/answer_item为LONGTEXT，data为MEDIUMTEXT；新表显示字段预算不证明旧数据可直接导入，旧UID、App、Spark SID和实际长度仍需核对，不能截断或猜最新Run。未连接真实业务库、Provider/Redis，未启服务、发布、Git写入或执行浏览器业务验收。正式App/history、历史和全局变量、资源与渠道及跨进程恢复继续按完整迁移目标实施，本节未宣布全部完成。证据集中在[本批单一汇总](证据/本地源码检查/调试显示历史本地源码检查.json)，本节追加前正文与README原文另已[保全](证据/本地源码检查/checks/调试显示历史记录追加前原文/输入.json)。
'''
    readme_add = '''

原工作流type=1调试显示历史现接通保存、最近10条与本人软清除，返回原chatId字符串/null、13字段行及Integer计数。每POST追加一段，不按SID/内容去重，也不要求Run结束；中心核实际当前用户Run、空间、工作流和固定debug版本，显示正文不替代运行结果。原React前端本批零改。显示整体20MiB、单字段UTF-8 MEDIUMTEXT边界与Run结果2MiB/控制16KiB分开；实际2,200,519字节原显示请求成功、整包/字段超限413零新增。SQL019及001至018均是未执行MySQL草案，旧UID/App/Spark SID与原LONGTEXT历史未导入，不可截断。

本批8类97项实际全部通过（新增23、既有74重跑），227输入窗0；当前577为480沿用加97本轮执行，未重跑577全套。实际H2/Mapper/事务/MVC覆盖多段追加、个人隔离、撤权回滚与clear 3后0，Run/事件/等待Checkpoint保持；原/current AST各6与React叶子SSR各6一致，受控叶子依赖不代表浏览器业务验收。准确产物、源码/私有反例及范围见[唯一实施记录第26节](../../文档/项目/PaiFlow工作流项目/设计资料核对记录.md#26-2026-10-03原工作流调试显示历史接通)和[单一本地检查汇总](../../文档/项目/PaiFlow工作流项目/证据/本地源码检查/调试显示历史本地源码检查.json)。default OFF；无真实数据库/Provider/Redis、服务启动、发布或Git写入，完整迁移和业务验收仍未完成。
'''
    doc.write_bytes(old_doc + doc_add.encode('utf-8'))
    readme.write_bytes(old_readme + readme_add.encode('utf-8'))
    assert doc.read_bytes().startswith(old_doc) and readme.read_bytes().startswith(old_readme)
    assert all(sha(ROOT / v['path']) == v['sha256'] for v in gate['inputs'])
    new_json(append_proof, {
        'checkedAt': datetime.now(timezone.utc).isoformat(), 'scope': '唯一记录追加第26及backend README追加，原正文逐字节保留',
        'originalDocumentBackup': ref(backups / '1-设计资料核对记录.md'),
        'originalReadmeBackup': ref(backups / '2-README.md'),
        'currentDocument': ref(doc), 'currentReadme': ref(readme),
        'originalDocumentPrefixBytes': len(old_doc), 'originalReadmePrefixBytes': len(old_readme),
        'originalDocumentPrefixEqual': True, 'originalReadmePrefixEqual': True,
        'aggregate': ref(aggregate), 'final12FrozenInputsUnchanged': True,
        'indexUpdatedSeparately': ref(index_path), 'businessVerified': False, 'migrationComplete': False,
    })
    print(json.dumps({'aggregate': ref(aggregate), 'record': ref(doc), 'readme': ref(readme), 'appendProof': ref(append_proof)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
