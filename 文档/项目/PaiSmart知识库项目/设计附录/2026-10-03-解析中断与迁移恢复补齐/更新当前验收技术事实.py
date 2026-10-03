"""从实际技术记录更新既有唯一设计与验收视图，不执行任何业务操作。"""
from datetime import datetime
import hashlib
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
EVIDENCE = HERE.parent
PROJECT = EVIDENCE.parent
ROOT = PROJECT.parents[2]
PREP = EVIDENCE / '2026-10-03-真实业务验收准备'


def read(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser_path = Path(sys.argv[1]).resolve()
    assert parser_path.is_relative_to(HERE)
    parser = read(parser_path)
    backend = read(EVIDENCE / '2026-10-03-终版后端测试汇总.json')
    frontend = read(EVIDENCE / '2026-10-03-迁移补齐-前端技术复验.json')
    delivery = read(EVIDENCE / '2026-10-03-终版交付产物核对.json')
    migration = read(HERE / '迁移转换守卫-技术结果.json')
    assert backend['totals'] == {'tests': 361, 'failures': 0, 'errors': 0, 'skipped': 0}
    assert len(backend['suites']) == 36 and frontend['unit']['passed'] == 72
    assert frontend['unit']['failed'] == 0 and frontend['typecheck']['exitCode'] == frontend['productionBuild']['exitCode'] == 0
    assert migration['after']['tests'] == 17 and migration['after']['exitCode'] == 0
    assert parser['total'] == parser['passed'] == 19 and all(r['passed'] for r in parser['results'])
    assert delivery['NestedModuleMatches'] and delivery['WorkerModuleMatches']
    assert all(v is False for v in delivery['AllDefaultFlagsOff'].values())
    assert Path(parser['workerBatch']).resolve() == (ROOT / delivery['WorkerJar']).parent.resolve()
    lifecycle_report = EVIDENCE / '2026-10-03-解析迁移恢复补齐后通过报告/TEST-com.ruoyi.paismart.infrastructure.intelligence.IsolatedParserLifecycleTest.xml'
    lifecycle = ET.parse(lifecycle_report).getroot()
    assert {k: int(lifecycle.get(k)) for k in ['tests', 'failures', 'errors', 'skipped']} == {'tests': 2, 'failures': 0, 'errors': 0, 'skipped': 0}
    files = [ROOT / 'ruoyi-backend/ruoyi-paismart/src/main/java/com/ruoyi/paismart/infrastructure/intelligence/IsolatedDocumentParser.java', ROOT / 'ruoyi-backend/ruoyi-paismart/src/test/java/com/ruoyi/paismart/infrastructure/intelligence/IsolatedParserLifecycleTest.java']
    lifecycle_result = {'time': datetime.now().isoformat(), 'tests': 2, 'failures': 0, 'errors': 0, 'skipped': 0,
        'scope': '真实本地Java父子夹具仅核对包装器中断/超时；固定Worker格式正文另列19项',
        'checks': ['调用方中断标记及InterruptedException原因保留', '已捕获父子进程退出', 'Windows重定向文件解锁后目录回收', '并发许可可复用'],
        'report': str(lifecycle_report.relative_to(EVIDENCE)),
        'priorFailures': ['解析进程-稳定旧源失败报告', '解析进程-首轮修后报告', '解析进程-残留定位失败报告'],
        'files': [{'path': str(p.relative_to(ROOT)), 'sha256': sha(p)} for p in files],
        'businessAcceptanceExecuted': False,
        'limits': ['仅捕获当时可见子树；父提前退出后脱离后代需OS约束', '没有操作系统低权限沙箱或资源/网络部署验证', '真实业务31项未执行']}
    (HERE / '解析进程-技术结果.json').write_text(json.dumps(lifecycle_result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    data = read(PREP / '验收记录.json')
    assert len(data['businessCases']) == 31 and all(c['result'] == '未执行' and not c['evidence'] for c in data['businessCases'])
    data['readiness'][0]['fact'] = '用户明确回复“认可设计及建议方案，开始编码”，覆盖源码、必要本地构建和检查。最新后端36类361项通过，已确认的解析恢复、对象搬迁、映射幂等和三处页面反馈差距已补齐；源码批准不等于真实业务接受。'
    data['technicalBaseline'] = [
        {'name': '后端本地测试', 'href': '../2026-10-03-终版后端测试汇总.json', 'fact': '当前源码完整实际运行36类361项，零失败/错误/跳过；新增解析生命周期2项和对象搬迁租约边界6项。原353及更早版本报告、失败均保留。本地H2/替身不证明真实MySQL、对象服务或模型联机。'},
        {'name': '前端技术记录', 'href': '../2026-10-03-迁移补齐-前端技术复验.html', 'fact': '最新类型检查、10文件72项和独立目录生产构建通过；本轮7张Mock图实际操作、保存并回读。旧18图按历史/影响说明保留，旧甲乙会话图不证明当前引用布局。稳定Mock0错误、1条原Element Plus分页弃用警告；未进入真实平台或代理后台。'},
        {'name': '共享装配和固定Worker', 'href': '../2026-10-03-终版交付产物核对.json', 'fact': '最新模块、admin内嵌模块和固定Worker主JAR摘要一致；240依赖、4个BC1.85家族、V005及样本/NOTICE入包，12个默认开关关闭。只构建和核对，未启动业务；COS1项按未变源码沿用。'},
        {'name': '运行与恢复步骤', 'href': '../../../../../ruoyi-backend/ruoyi-paismart/README.md', 'fact': '实际开关副作用、离线迁移输出、停止回退及当前限制。进程结束信号后的Windows日志锁定采用有限清理重试；父提前退出的脱离后代仍需OS运行约束。'},
        {'name': '正式契约复验', 'href': '../2026-10-03-解析迁移恢复补齐后契约静态复验.log', 'fact': '当前89路径108操作、1037引用及97前端路径匹配，完整OpenAPI解析及WS/Kafka样例通过。本轮没有新增HTTP接口或DDL。'},
        {'name': '离线迁移转换校验', 'href': '../2026-10-03-解析中断与迁移恢复补齐/迁移转换守卫-技术结果.json', 'fact': '17项Python检查通过；同一来源跨批次必须匹配实际转换指纹，变化映射、损坏/旧裸归档及身份回执不符在首个业务写入前拒绝。只使用JSON及SQLite内存SQL子集，未执行真实MySQL。'},
        {'name': '解析生命周期复验', 'href': '../2026-10-03-解析中断与迁移恢复补齐/解析进程-技术结果.json', 'fact': '中断/超时2项真实本地父子夹具通过：捕获树退出、目录回收、许可复用、中断标记及原因保留。原源码和各次失败未覆盖；不代表OS沙箱或所有派生进程均受控。'},
        {'name': '固定Worker实际格式复验', 'href': '../' + str(parser_path.relative_to(EVIDENCE)).replace('\\', '/'), 'fact': '本轮在新固定Worker实际重跑19份原脱敏样例，原输入摘要及独立正文marker全部匹配，19项通过。没有把旧基线沿用冒充新执行，也不据此宣称全部生产格式或语言包已验收。'},
        {'name': '对象搬迁取消与恢复边界', 'href': '../2026-10-03-对象搬迁租约修复/对象搬迁-技术结果.json', 'fact': '搬迁6项加既有运维14项及解析2项的定向22项通过；最终租约、文档指针和成功收据同事务，复制在事务外。响应丢失保留UNCERTAIN，重试读回核验不盲目覆盖，源对象保留。真实对象写入未执行。'}]
    cases = {c['number']: c for c in data['businessCases']}
    additions = {
        3: ('普通成员查看当前主空间，设置后刷新本人信息；资格/信息失败应明确提示，不能显示为未设置。', '主空间显示与ps_member_space.primary_flag及本人primarySpaceId一致。', '当前主空间及设置后本人信息回读'),
        6: ('在批准范围内分别取消正在解析任务和触发超时，核对本次父子进程退出、临时文件回收、并发可继续使用及错误原因。', '保留失败与中断原因；对本次进程树及目录单独核对，脱离后代需已批准OS约束。', '解析中断/超时、进程与文件回收及后续任务'),
        10: ('关闭后重开或切换知识空间，核对旧查询及结果清空；制造迟到响应与失败新检索，旧结果不得再次显示。', '请求空间、结果来源及清空状态逐次对应，迟到响应不能跨空间回写。', '跨空间重开、迟到响应隔离及新检索失败状态'),
        16: ('无需打开引用抽屉，先核对回答、历史消息及右侧引用列表直接显示可疑来源待核实及理由，未知状态不得显示已核验。', '标记和理由来自正式保存核验字段，切换历史后仍一致且以安全文本呈现。', '回答/历史与右栏待核实理由及未知核验状态'),
        29: ('在已批准的故障演练范围内模拟最终切换前取消/租约换手，以及对象已接受但响应丢失；读回后重试，错误目标不能覆盖。', '取消/换手不能穿过最终指针与成功收据的事务边界；UNCERTAIN按摘要和大小读回确认，源对象保留。', '取消/换手、UNCERTAIN恢复与错误目标拒绝'),
        31: ('另取不同批次号，分别复用相同转换和改变用户/组织/可见性/密钥引用或对象计划的映射；遇旧裸归档/损坏指纹须停止人工核对。', '跨批次相同转换不覆盖后续业务状态；变化转换在首个业务写入前拒绝，逐来源转换指纹及身份回执对应原批准映射。', '跨批次转换指纹、变化映射拒绝及旧归档人工核对')}
    for number, (step, verification, screenshot) in additions.items():
        if step not in cases[number]['steps']:
            cases[number]['steps'].insert(-1, step)
            cases[number]['verification'] += ' ' + verification
            cases[number]['requiredScreenshots'].append(screenshot)
    data['reportRendering'] = {'executed': False, 'businessEvidence': False, 'previousEvidence': '../2026-10-03-解析中断与迁移恢复补齐/真实业务验收准备-恢复补齐前记录.json', 'scope': '当前报告改动后待重新打开；此前呈现记录及截图原文已保留。'}
    data['technicalFactsUpdatedAt'] = datetime.now().isoformat()
    (PREP / '验收记录.json').write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    master_path = PROJECT / '完整迁移设计.md'
    text = master_path.read_text(encoding='utf-8')
    text = text.replace('继续复核发现的恢复与页面状态反馈差距正在修复', '继续复核发现的恢复与页面状态反馈差距已补齐并通过本地检查')
    text = text.replace('离线转换11项通过，审核SQL在业务插入前核对history1—5', '离线转换17项通过，审核SQL在业务插入前核对history1—5及跨批次转换指纹')
    text = text.replace('最终34类353项后端检查、9文件60项前端检查及类型/独立构建通过', '最新36类361项后端检查、10文件72项前端检查及类型/独立构建通过')
    # 原353/60版本不再指向会继续更新的当前产物；保留历史正文及固定证据。
    old_paragraph = next(line for line in text.splitlines() if line.startswith('最终源码已完整运行34类353项'))
    historical = old_paragraph.replace('最终源码已', '本次继续复核前的源码已').replace('[当前测试汇总](设计附录/2026-10-03-终版后端测试汇总.json)', '[此前测试汇总](设计附录/2026-10-03-解析中断与迁移恢复补齐/2026-10-03-终版后端测试汇总.json)').replace('[当前前端技术报告](设计附录/2026-10-03-迁移补齐-前端技术复验.html)', '[此前前端技术结果](设计附录/2026-10-03-引用主空间检索修复前快照/前版60项技术结果.json)')
    text = text.replace(old_paragraph, historical)
    old_delivery = next(line for line in text.splitlines() if line.startswith('本次新模块JAR、admin包内模块'))
    text = text.replace(old_delivery, old_delivery.replace('本次新模块JAR', '继续复核前的模块JAR').replace('[当前交付核对](设计附录/2026-10-03-终版交付产物核对.json)', '[此前交付核对](设计附录/2026-10-03-解析中断与迁移恢复补齐/2026-10-03-终版交付产物核对.json)'))
    pending = next(line for line in text.splitlines() if line.startswith('2026-10-03继续按原操作及失败恢复复核'))
    current = '2026-10-03继续按原操作及失败恢复复核发现的6处差距已在原模块内补齐：解析中断/超时有限等待已捕获父子退出，并处理Windows日志短暂锁定；普通搬迁最终租约、文档当前版本/指针与成功收据同事务，复制留在事务外，响应丢失保留UNCERTAIN并读回确认；离线迁移跨批次绑定实际转换指纹，变化映射及旧裸归档不冒充幂等成功；回答/历史和右栏直接显示待核实理由；检索重开/换空间清空旧状态并隔离迟到响应；普通用户查看并刷新当前主空间。原源码、原失败、首修失败和修后通过证据均保留，未改变第1—5节需求、若依导航/身份/数据源或增加DDL。'
    current += '\n\n当前源码完整复验36类361项后端、10文件72项前端及类型/独立构建全部通过，离线迁移17项通过；见[当前后端汇总](设计附录/2026-10-03-终版后端测试汇总.json)、[本次完整测试日志](设计附录/2026-10-03-解析迁移恢复补齐后全模块复验.log)、[当前前端技术报告](设计附录/2026-10-03-迁移补齐-前端技术复验.html)、[迁移转换校验](设计附录/2026-10-03-解析中断与迁移恢复补齐/迁移转换守卫-技术结果.json)、[搬迁边界](设计附录/2026-10-03-对象搬迁租约修复/对象搬迁-技术结果.json)及[解析生命周期](设计附录/2026-10-03-解析中断与迁移恢复补齐/解析进程-技术结果.json)。7张当前Mock图回读完成，旧18图按影响/历史说明保留，稳定Mock0错误/1条原分页弃用警告。'
    current += '\n\n模块、admin内嵌与本批固定Worker主JAR哈希一致，240依赖及12默认关闭开关核对通过，见[当前交付核对](设计附录/2026-10-03-终版交付产物核对.json)及[本批依赖清单](设计附录/2026-10-03-解析运行依赖-' + Path(parser['workerBatch']).name + '.json)。本轮在此批Worker实际重跑原19个格式样例，全部匹配原输入摘要和独立正文预期，见[本轮实际进程复验](设计附录/' + str(parser_path.relative_to(EVIDENCE)).replace('\\', '/') + ')；COS1项仅按未变源码沿用。正式契约最新静态复验通过，见[契约复验](设计附录/2026-10-03-解析迁移恢复补齐后契约静态复验.log)。原参考工程808文件0变化0新增，见[只读复核](设计附录/2026-10-03-恢复补齐后原工程只读复核.json)。'
    current += '\n\n真实31项仍全部未执行，已将以上受影响操作纳入原序号清单。目标通道、原MySQL和原数据范围未明确，真实写入授权没有扩大。平台登录/菜单、真实存储检索及供应商、实际导入/切换/回退、OS低权限/进程组约束与容量仍未验证；源码、Mock、固定格式及构建不能替代完整迁移的真实执行结果，用户接受及发布未取得。'
    text = text.replace(pending, current)
    master_path.write_text(text, encoding='utf-8')
    print(json.dumps({'updated': True, 'backend': 361, 'frontend': 72, 'python': 17, 'parserNewlyExecuted': 19, 'businessExecuted': 0}, ensure_ascii=False))


if __name__ == '__main__':
    main()
