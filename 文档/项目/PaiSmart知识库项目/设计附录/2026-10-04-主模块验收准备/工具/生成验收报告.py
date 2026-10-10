"""从唯一验收执行记录生成固定 HTML；不访问业务、模型或浏览器。"""
from pathlib import Path
from html import escape
from urllib.parse import quote, unquote, urlsplit
from html import unescape
from collections import Counter
import json
import os
import re

HERE = Path(__file__).resolve().parent.parent
RECORD = HERE / '证据/验收执行记录.json'
REPORT = HERE / 'verification-report.html'
WORKSPACE = HERE.parents[4]


def module_report_path(module):
    return HERE / '证据/模块报告' / module / 'verification-report.html'


def progressed_modules(record):
    """仅从唯一记录中明确执行、沿用或已有截图的步骤选出实际推进模块。"""
    return [module for module in record.get('moduleOrder', [])
            if any(execution_kind(step) in ('current', 'currentTechnical', 'reused')
                   or bool(step.get('screenshots'))
                   for flow in record.get('flows', []) if flow.get('module') == module
                   for step in flow.get('steps', []))]


def module_baseline_ids(record, module):
    """只展开本模块明确引用的原业务点，不复制其他模块的原结论。"""
    ids = set()
    for flow in record.get('flows', []):
        if flow.get('module') != module:
            continue
        ids.update(p['id'] for p in flow.get('existingProof', {}).get('records', []) if p.get('id'))
        for step in flow.get('steps', []):
            ids.update(step.get('usesBaselinePoints', []))
    return ids


def rebase_module_links(body, output, local_ids):
    """复用现有卡片；本模块定位保留，跨模块定位精确引用总报告。"""
    def replace(match):
        target = unescape(match.group(2))
        url = urlsplit(target)
        if url.scheme or url.netloc:
            return match.group(0)
        if not url.path and url.fragment in local_ids:
            return match.group(0)
        source = HERE / unquote(url.path) if url.path else REPORT
        relative = os.path.relpath(source, output.parent).replace('\\', '/')
        path = href(relative) + ('?' + url.query if url.query else '') + ('#' + url.fragment if url.fragment else '')
        return match.group(1) + '="' + e(path) + '"'
    return re.sub(r'(href|src)="([^"]+)"', replace, body)


def module_display_text(body):
    """长记录标识和摘要保留在原证据；只简化可见文字，不改链接或定位属性。"""
    statuses={'COMPLETED_WITH_ERRORS':'完成但有失败','GENERATION_FAILED':'生成失败','JUDGE_FAILED':'裁判失败','NEEDS_EVIDENCE':'缺少依据','SUPPORTED':'有依据','READY':'已就绪','ACTIVE':'使用中','INACTIVE':'未启用','PREPARED':'待激活','COMPLETED':'已完成','SUCCEEDED':'成功','FAILED':'失败','RUNNING':'运行中','PENDING':'待处理','CANCELLED':'已取消','STOPPED':'已停止','STARTED':'已开始','UNCERTAIN':'结果待核','RESERVED':'已预留','SETTLED':'已结算','BALANCE':'余额','SETTLE':'结算','RELEASE':'释放'}
    state_pattern=r'(?<![A-Za-z0-9_])('+ '|'.join(map(re.escape,statuses)) +r')(?![A-Za-z0-9_])'
    parts=re.split(r'(<[^>]+>)',body)
    for index in range(0,len(parts),2):
        parts[index]=re.sub(r'\b[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}\b','同一记录（完整标识见原始证据）',parts[index])
        parts[index]=re.sub(r'\b[0-9a-fA-F]{64}\b','已记录摘要（见原始证据）',parts[index])
        parts[index]=re.sub(state_pattern,lambda match:statuses[match.group(1)],parts[index])
    return ''.join(parts)

def e(value):
    if isinstance(value, (list, dict)):
        value = json.dumps(value, ensure_ascii=False)
    return escape(str(value if value is not None else ''))

def href(path):
    return quote(str(path).replace('\\', '/'), safe='/:#')

def local_link(path, label=None):
    return f'<a href="{href(path)}">{e(label or path)}</a>'

def paragraph(label, value):
    return f'<p><b>{e(label)}：</b>{e(value)}</p>' if value else ''

def execution_kind(step):
    """按当前步骤的明确执行方式分类；原 points 不参与本次统计。"""
    mode = str(step.get('executionMode', '')).strip()
    if mode.startswith('本次技术执行'):
        return 'currentTechnical'
    if '沿用' in mode:
        return 'reused'
    if mode.startswith(('本次实际执行', '本轮实际执行', '本次复验', '本轮复验', '本次新执行')):
        return 'current'
    if mode in ('current', 'retest'):
        return 'current'
    if mode in ('reuse', 'baseline') or step.get('usesBaselinePoints'):
        return 'reused'
    return 'unclassified'


def verification_type(item, flow=None):
    """技术用途必须显式标明；既有记录继续采用业务验收口径。"""
    return item.get('verificationType', (flow or {}).get('verificationType', '业务验收'))


def flow_statistics(record):
    flows = record.get('flows', [])
    return {
        'business': Counter(f.get('result', '未执行') for f in flows if verification_type(f) != '技术验证'),
        'technical': Counter(f.get('result', '未执行') for f in flows if verification_type(f) == '技术验证'),
    }


def execution_statistics(record):
    """流程步骤是统计单元；coreWorkflow 与 points 仅展示，不重复计数。"""
    steps = [s for f in record.get('flows', []) if verification_type(f) != '技术验证'
             for s in f.get('steps', []) if verification_type(s, f) != '技术验证']
    technical = [s for f in record.get('flows', []) for s in f.get('steps', [])
                 if verification_type(s, f) == '技术验证']
    return {
        'currentExecutedSteps': sum(execution_kind(s) == 'current' for s in steps),
        'reusedSteps': sum(execution_kind(s) == 'reused' for s in steps),
        'reusedPassedSteps': sum(execution_kind(s) == 'reused' and s.get('result') == '通过' for s in steps),
        'passedSteps': sum(s.get('result') == '通过' for s in steps),
        'currentTechnicalExecutedSteps': sum(execution_kind(s) == 'currentTechnical' for s in technical),
        'currentTechnicalPassedSteps': sum(execution_kind(s) == 'currentTechnical' and s.get('result') == '通过' for s in technical),
        'reusedTechnicalSteps': sum(execution_kind(s) == 'reused' for s in technical),
        'technicalPassedSteps': sum(s.get('result') == '通过' for s in technical),
    }


def module_progress_table(record):
    """首页直接派生现有流程事实；不维护第二套模块状态或改变执行计数。"""
    body = '<h3>'+str(len(record.get('moduleOrder', [])))+' 个模块做到哪里</h3>'
    body += '<p>下面引用现有模块位置，并列出清单中的首个未完成业务步骤。它是继续查阅入口，实际执行仍按上方依赖计划与授权范围安排；独立技术通过、局部取证和沿用事实不代表模块完整通过。</p>'
    body += paragraph('共同角色与执行范围', record.get('authorization'))
    body += '<table class="module-progress"><thead><tr><th>模块</th><th>已证实及当前限制</th><th>待验步骤与入口</th><th>角色、样本与继续条件</th></tr></thead><tbody>'
    for number, module in enumerate(record.get('moduleOrder', []), 1):
        flows = [f for f in record.get('flows', []) if f.get('module') == module]
        business = [f for f in flows if verification_type(f) != '技术验证']
        position = next((f.get('moduleKnownCurrent') for f in flows if f.get('moduleKnownCurrent')), '')
        if not position:
            position = '；'.join(f.get('currentPosition', '') for f in business if f.get('currentPosition'))
        position = position or '现有记录未提供模块当前位置；不能据此认定已经通过。'
        pending = next(((f, s, index) for f in business
                        for index, s in enumerate(f.get('steps', []), 1)
                        if verification_type(s, f) != '技术验证'
                        and s.get('result') not in ('通过', '不适用')), None)
        conditions = ''
        if pending:
            flow, step, index = pending
            next_step = local_link('#'+flow['id'], flow.get('name', flow['id']))
            next_step += paragraph('步骤', str(step.get('order', index))+'．'+str(step.get('name') or step.get('action') or '未命名步骤'))
            next_step += paragraph('当前判定', step.get('result', '未执行'))
            next_step += paragraph('本步不足', step.get('evidenceGap') or step.get('actual'))
            conditions += paragraph('角色与前提', flow.get('preconditions'))
            conditions += paragraph('沿用或准备样本', flow.get('independentInput'))
            conditions += paragraph('已确认缺项', flow.get('environmentMissing'))
            conditions += paragraph('调用及费用边界', flow.get('budget'))
            if not conditions:
                conditions = '<p>现有流程未提供继续条件；执行前核对对应模块正文。</p>'
        else:
            next_step = '<p>清单没有待验业务步骤；仍需核对必需证据、完整矩阵及用户接受，不能自动宣布模块完整通过。</p>' if business else '<p>现有记录没有独立业务流程；技术结果另列。</p>'
        body += '<tr><td>'+local_link('#module-'+str(number), str(number)+'．'+module)+'</td><td>'+e(position)+'</td><td>'+next_step+'</td><td>'+conditions+'</td></tr>'
    return body+'</tbody></table>'


def pictures(step, seen):
    result = []
    for pic in step.get('screenshots', []):
        path = pic if isinstance(pic, str) else pic['path']
        proof = step.get('screenshotProof', '') if isinstance(pic, str) else pic.get('proof', '')
        if path in seen:
            result.append(f'<p class="reuse">本流程同图：{local_link("证据/"+path, Path(path).name)}。本步证明：{e(proof)}</p>')
            continue
        seen.add(path)
        mode = step.get('executionMode') or '截图来源未注明；不能据此认定本次采集或沿用'
        result.append(f'<figure><a href="{href("证据/"+path)}"><img loading="lazy" src="{href("证据/"+path)}" alt="{e(proof or step.get("name", "实际业务截图"))}"></a><figcaption>{e(mode)} · {e(proof)} · {local_link("证据/"+path,"查看清晰原图")}</figcaption></figure>')
    return ''.join(result)

def result_label(item, flow=None):
    status = item.get('result', '未执行')
    if status == '通过' and verification_type(item, flow) == '技术验证':
        return 'API技术通过' if (flow or item).get('id') == 'provider-image-input' else '技术通过'
    return status


def step_card(step, number, seen, flow=None):
    status = step.get('result', '未执行')
    cls = 'pass' if status == '通过' else 'fail' if status == '未通过' else 'pending'
    identity = f' data-flow-id="{e(flow["id"])}" data-step-order="{number}"' if flow else ''
    body = f'<section class="step {cls}"{identity}><h4>步骤 {number}. {e(step.get("name", ""))} <span class="tag">{e(result_label(step, flow))}</span></h4>'
    for key, label in [('action','操作'),('input','输入与承接'),('expected','正确预期'),('dataCheck','独立核对'),('screenshotStage','取证位置'),('actual','实际及交给下一步的结果'),('handoff','步骤之间的衔接'),('executionMode','证据使用方式'),('executedAt','实际执行时间'),('evidenceSource','证据来源与条目'),('evidenceReason','替代证据理由'),('screenshotStatus','截图情况')]:
        body += paragraph(label, step.get(key))
    body += pictures(step, seen)
    for path in step.get('evidence', []):
        body += f'<p>数据依据：{local_link("证据/"+path,Path(path).name)}</p>'
    if not step.get('screenshots'):
        body += '<p class="empty">本步没有可用于判定的页面截图。'+e(step.get('evidenceGap', '尚未执行或尚未补齐该步骤证据。'))+'</p>' if step.get('screenshotStatus') != '不适用' else '<p class="reuse">无页面步骤：用上列数据或离线产物核对，截图不适用。</p>'
    return body+'</section>'

def flow_card(flow, number, seen):
    seen=set()  # 各流程独立展示实际图，避免读者跨流程查找截图。
    body = f'<details class="flow" id="{e(flow["id"])}" data-name="{e(flow["name"])}" data-result="{e(flow.get("result", "未执行"))}"><summary>{number}. {e(flow["name"])} <span class="tag">{e(result_label(flow))}</span></summary><div class="flowbody">'
    if verification_type(flow) == '技术验证':
        body += '<p class="reuse">本流程是独立技术验证；结果不计入页面业务通过或模块完整业务通过。</p>'
    for key,label in [('preconditions','前提与关键字段'),('independentInput','独立业务输入'),('expectedOutcome','最终业务产物与预期'),('requirementSummary','来源要求'),('horizontalRequirement','横向要求来源'),('implementation','迁移与实现'),('currentPosition','目前完成位置'),('environmentMissing','待补条件'),('authorization','执行边界'),('budget','用量与费用条件'),('reuse','沿用规则')]:
        body += paragraph(label,flow.get(key))
    points=flow.get('existingProof',{}).get('records',[])
    if points:
        body+='<p><b>已有窄范围证据：</b>'+ '；'.join(local_link('#baseline-'+p['id'],p.get('name',p['id'])) for p in points) +'。保留原范围与版本；本完整流程不自动通过。</p>'
    technical=flow.get('technicalEvidence',[])
    if isinstance(technical,str):
        technical=[technical]
    for evidence in technical if isinstance(technical,list) else []:
        if not isinstance(evidence,str) or not evidence:
            continue
        body+='<p>技术记录（不计为页面业务通过）：'+local_link('证据/'+evidence,Path(evidence).name)+'</p>'
    body += ''.join(step_card(s,i,seen,flow) for i,s in enumerate(flow.get('steps',[]),1))
    matrix=flow.get('sampleMatrix',[])
    if matrix:
        body += '<h4>必须逐个执行的样本矩阵</h4><ul>'+''.join(f'<li>{e(row)}</li>' for row in matrix)+'</ul><p>本流程只有全部必需样本/分支取得证据才能通过；表内声明不算样本已执行。</p>'
    return body+'</div></details>'

def baseline_card(point, record, number):
    seen = set()
    body = '<details class="flow" id="baseline-'+e(point['id'])+'"><summary>'+str(number)+'. '+e(point['name'])+' <span class="tag">原范围通过 · 本次沿用</span></summary><div class="flowbody">'+paragraph('原输入/操作',point.get('steps'))+paragraph('原预期',point.get('expected'))+paragraph('已核实结果',point.get('actual'))+paragraph('原执行时间',point.get('executedAt'))+paragraph('适用限制',point.get('limitations'))
    body += paragraph('原验证版本','原记录的本机中心版本，保持原验证时间和证据；精确构建标识见唯一记录')+paragraph('当前沿用范围','保留原执行版本与时间；适用到当前本机中心，精确构建标识见唯一记录。')+paragraph('沿用影响判断',point.get('versionImpactReason',record.get('impactAnalysis')))+paragraph('本次执行方式','沿用；未再次执行原模型调用、追加额度或上传。')
    body += ''.join('<p>数据证据：'+local_link('证据/'+path,Path(path).name)+'</p>' for path in point.get('evidence',[]))
    for index, pic in enumerate(point.get('screenshots',[]),1):
        body += f'<h4>原清单证据阶段 {index}. {e(Path(pic).stem)}</h4>'+pictures({'name':point['name'],'screenshots':[{'path':pic,'proof':Path(pic).stem+'；仅证明原业务点对应的可见阶段，适用范围按上述原预期和限制。'}]},seen)
    return body+''.join(paragraph('保留观察',item.get('reason')) for item in point.get('observations',[]))+'</div></details>'


def module_report_html(record, module, css, controls, script):
    """同一记录的模块视图：完整本模块流程，跨模块仅定位引用。"""
    flows = [flow for flow in record.get('flows', []) if flow.get('module') == module]
    scoped = dict(record, flows=flows)
    execution = execution_statistics(scoped)
    counts = flow_statistics(scoped)
    position = next((flow.get('moduleKnownCurrent') for flow in flows if flow.get('moduleKnownCurrent')), '')
    body = '<header><p class="eyebrow">PaiSmart · 独立模块业务验收</p><h1>'+e(module)+'验收报告</h1><p>记录版本 '+e(record['recordVersion'])+'；最后更新 '+e(record.get('updatedAt'))+'。用户接受：<b>'+e(record['userAcceptance'])+'</b>。</p><p>本页是同一唯一执行记录的模块视图；技术通过、局部通过和已有窄范围证据不能代表整个模块通过。</p></header>'
    body += '<nav>'+local_link('verification-report.html','项目总报告')+local_link('模块验收/'+module+'.md','本模块唯一验收正文')+local_link('主模块验收文档.md','共同前提')+local_link('证据/验收执行记录.json','唯一执行记录')+''.join(local_link('#'+flow['id'], str(i)+'．'+flow['name']) for i,flow in enumerate(flows,1))+'</nav><main>'+controls
    body += '<section id="overview"><h2>本模块范围与继续位置</h2>'+paragraph('完整范围',str(len(flows))+'个完整流程；所有正常、拒绝和异常步骤及样本矩阵均在下方展示，未执行内容保留。')+paragraph('已证实及当前限制',position)+paragraph('共同角色与授权边界',record.get('authorization'))+paragraph('用户接受',record['userAcceptance'])
    implementation=record.get('moduleImplementation',{}).get(module)
    body += paragraph('要求实现或迁移',implementation or '当前唯一记录未单列本模块完整、部分、未实现的数量；各流程的“迁移与实现”保留实际承接及缺口，不能由源码存在或局部检查反推全部实现。')
    body += paragraph('业务流程结果','；'.join(key+' '+str(value)+'条' for key,value in counts['business'].items()) or '没有单列业务流程')+paragraph('独立技术流程结果',('；'.join(key+' '+str(value)+'条' for key,value in counts['technical'].items()) or '没有单列技术流程')+'；不计为页面业务或模块完整通过。')
    technical_summary=record.get('moduleTechnicalSummary',{}).get(module)
    body += paragraph('相关技术检查与限制',technical_summary or '唯一记录未单列本模块技术摘要；适用技术证据见下方所属流程。技术证据不替代页面业务和完整模块结果。')
    body += paragraph('业务步骤执行范围',f'本次执行 {execution["currentExecutedSteps"]} 步；沿用 {execution["reusedSteps"]} 步，其中沿用通过 {execution["reusedPassedSteps"]} 步。')+paragraph('技术步骤执行范围',f'本次技术执行 {execution["currentTechnicalExecutedSteps"]} 步；技术通过共 {execution["technicalPassedSteps"]} 步。')
    screenshot_counts=Counter(step.get('screenshotStatus','未注明') for flow in flows for step in flow.get('steps',[]))
    body += paragraph('截图完整度','；'.join(status+' '+str(count)+'步' for status,count in screenshot_counts.items())+'。逐步缺口及回读声明见下方；有图片不代表必需截图齐全或业务通过。')
    body += '<h3>各流程接续入口</h3>'
    for flow in flows:
        pending = next(((i,step) for i,step in enumerate(flow.get('steps',[]),1) if step.get('result') not in ('通过','不适用')), None)
        body += '<h4>'+local_link('#'+flow['id'],flow['name'])+'</h4>'+paragraph('目前位置',flow.get('currentPosition'))
        if pending:
            index,step=pending
            body += paragraph('下一待验步骤',str(index)+'．'+str(step.get('name','')))+paragraph('未完成原因与当前观察',step.get('evidenceGap') or step.get('actual'))
        else:
            body += paragraph('下一待验步骤','已列步骤无未通过项；仍须核对完整样本矩阵、必需证据和用户接受，不能自动判完整模块通过。')
        body += paragraph('角色、环境与状态',flow.get('preconditions'))+paragraph('沿用或准备同一样本',flow.get('independentInput'))+paragraph('待补条件',flow.get('environmentMissing'))+paragraph('费用与执行边界',flow.get('budget'))
    body += '</section>' + ''.join(flow_card(flow,index,set()) for index,flow in enumerate(flows,1))
    baseline_ids = module_baseline_ids(record,module)
    points = [point for point in record.get('points',[]) if point['id'] in baseline_ids]
    if points:
        body += '<section id="baseline"><h2>本模块引用的原窄范围证据</h2><p>只展开本模块实际引用的原证据；其他模块事实通过项目总报告定位，不纳入本模块统计。</p>'+''.join(baseline_card(point,record,index) for index,point in enumerate(points,1))+'</section>'
    review=record.get('moduleReportReview',{}).get(module,{})
    body += '<section><h2>报告检查与用户决定</h2>'+paragraph('报告视觉检查',review.get('reason') or '报告视觉检查未执行：本模块新独立报告尚未在浏览器打开核对；当前生成工具仅做静态检查，不能沿用总报告视觉检查结果。')+paragraph('用户接受',record['userAcceptance'])+'<p>由同一“工具/生成验收报告.py”读取唯一实际记录生成。链接、定位、图片签名和步骤覆盖的静态检查不能代替截图回读、报告视觉检查或业务验收；本模块技术记录见各流程，不复制其他模块技术结论。</p></section></main>'
    ids = set(re.findall(r'\bid="([^"]+)"',body))
    body = module_display_text(rebase_module_links(body,module_report_path(module),ids))
    return '<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>PaiSmart '+e(module)+'验收报告</title><style>'+css+'</style></head><body>'+body+'<script>'+script+'</script></body></html>'


def flow_report_link(flow, progressed):
    module=flow['module']
    if module in progressed:
        return local_link('证据/模块报告/'+module+'/verification-report.html#'+flow['id'],module+'：'+flow['name'])
    return local_link('模块验收/'+module+'.md',module+'：'+flow['name']+'完整清单')


def flow_summary(flow, number, progressed):
    """总页只保留稳定定位与现状摘要，详细业务步骤归所属模块。"""
    return f'<details class="flow" id="{e(flow["id"])}" data-name="{e(flow["name"])}" data-result="{e(flow.get("result","未执行"))}"><summary>{number}. {e(flow["name"])} <span class="tag">{e(result_label(flow))}</span></summary><div class="flowbody">'+paragraph('当前完成位置',flow.get('currentPosition'))+'<p>完整流程、逐步输入、预期、实际和证据：'+flow_report_link(flow,progressed)+'</p></div></details>'


def common_flow_summary(flow, number):
    return f'<details class="flow" id="{e(flow["id"])}" data-name="{e(flow["name"])}" data-result="{e(flow.get("result","未执行"))}"><summary>{number}. {e(flow["name"])} <span class="tag">{e(result_label(flow))}</span></summary><div class="flowbody">'+paragraph('共同前提与当前位置',flow.get('currentPosition'))+'<p>共同前提完整清单：'+local_link('主模块验收文档.md',flow['name'])+'；实际结果和证据见'+local_link('证据/验收执行记录.json','唯一执行记录')+'。</p></div></details>'


def baseline_summary(point, record, number, progressed):
    owners=[module for module in progressed if point['id'] in module_baseline_ids(record,module)]
    links='；'.join(local_link('证据/模块报告/'+module+'/verification-report.html#baseline-'+point['id'],module+'所引用的原证据') for module in owners)
    return '<details class="flow" id="baseline-'+e(point['id'])+'"><summary>'+str(number)+'. '+e(point['name'])+' <span class="tag">原窄范围通过 · 沿用</span></summary><div class="flowbody">'+paragraph('原执行时间',point.get('executedAt'))+paragraph('适用限制',point.get('limitations'))+'<p>原步骤、结果及截图保留在'+(links or local_link('证据/验收执行记录.json','唯一执行记录'))+'；本摘要不重复完整证据，不增加本次执行数量。</p></div></details>'


def core_workflow_summary(record, progressed):
    body='<ol>'
    for step in record.get('coreWorkflow',[]):
        source=str(step.get('sourceStepReference','')).split('/',1)[0]
        matched=[flow for flow in record.get('flows',[]) if source and flow['id']==source]
        points=set(step.get('usesBaselinePoints',[]))
        if not matched and points:
            matched=[flow for flow in record.get('flows',[]) if points.intersection(p['id'] for p in flow.get('existingProof',{}).get('records',[]) if p.get('id')) or any(points.intersection(s.get('usesBaselinePoints',[])) for s in flow.get('steps',[]))]
        links='；'.join(flow_report_link(flow,progressed) for flow in matched)
        body+='<li><b>'+e(step.get('name','未命名链路步骤'))+'</b>：'+e(step.get('result','未执行'))+'。'+paragraph('证据使用方式',step.get('executionMode'))+'<p>完整所属流程：'+(links or local_link('证据/验收执行记录.json','原步骤关联待核，查看唯一记录'))+'</p></li>'
    return body+'</ol>'


def main():
    record = json.loads(RECORD.read_text(encoding='utf-8'))
    flows=record.get('flows',[])
    modules=record.get('moduleOrder',[])
    progressed=progressed_modules(record)
    audit=record.get('coverageAudit',{})
    flow_counts=flow_statistics(record)
    count=flow_counts['business']
    technical_count=flow_counts['technical']
    seen=set()
    phase=record.get('phaseExecution',{})
    execution=execution_statistics(record)
    currentExecuted=execution['currentExecutedSteps']
    baseline_count=len(record.get('points', []))
    introduction=phase.get('executionScope','本报告展示完整清单和当前证据位置，已有主链路通过事实沿用。')
    body='<header><p class="eyebrow">PaiSmart · 模块验收导航与汇总</p><h1>模块验收进度与报告入口</h1><p>记录版本 '+e(record['recordVersion'])+'；最后更新 '+e(record.get('updatedAt'))+'。用户接受：<b>'+e(record['userAcceptance'])+'</b>。</p><p>'+e(introduction)+' 本页只作导航和汇总，逐步业务正文及证据查看各独立模块报告；不据此认定16模块或完整流程已通过。</p></header>'
    body+='<nav><a href="#overview">范围与计划</a><a href="#chain">上传到答案的完整流程</a><a href="#coverage">原能力覆盖</a><a href="#baseline">沿用原证据</a>'+''.join(f'<a href="#module-{i}">{i}. {e(m)}</a>' for i,m in enumerate(modules,1))+'</nav><main>'
    reused=execution['reusedPassedSteps']
    body+='<section id="overview"><h2>范围与当前进度</h2><div class="metrics">'+''.join(f'<div><strong>{e(v)}</strong><span>{e(k)}</span></div>' for k,v in [('目标模块',len(modules)),('原实际能力分组',29),('设计验收分组',31),('完整清单流程',len(flows)),('完整业务流程',sum(count.values())),('独立技术流程',sum(technical_count.values())),('本次页面业务执行步骤',currentExecuted),('本次技术执行步骤',execution['currentTechnicalExecutedSteps']),('本次技术通过步骤',execution['currentTechnicalPassedSteps']),('沿用窄范围通过证据',baseline_count),('沿用的业务通过步骤',reused),('全部业务通过步骤',execution['passedSteps']),('全部技术通过步骤',execution['technicalPassedSteps']),('完整清单步骤',sum(len(f['steps']) for f in flows))])+'</div>'
    body+=paragraph('计数口径','原项目11个业务路由、15个正式Controller、91个HTTP方法映射；现有迁移设计32条来源中29组实际业务、3类占位/计划/技术工具。接口、技术测试、菜单、来源分组和完整业务流程各自计数。流程中的格式、角色、并发等样本矩阵须逐个展开执行，不能以单样本通过代表矩阵通过。')
    body+=paragraph('完整业务流程结果','；'.join(k+' '+str(v)+'条' for k,v in count.items())+'。这是较宽的业务流程口径；其中'+str(reused)+'个同输入步骤已有适用通过证据并沿用，其他步骤/矩阵没有因此自动通过。')
    body+=paragraph('独立技术流程结果',('；'.join(k+' '+str(v)+'条' for k,v in technical_count.items()) or '没有单列技术流程')+'；技术结果不增加页面业务通过数量，不代表16模块完整通过。')+paragraph('共同前提','沿用原管理员、原个人空间、已验证文字gpt-6.1-sol与阿里云text-embedding-v4/1024及原数据库。当前服务恢复结果见五阶段进度；原主链路保留当时余额与结算，本次新增技术调用以当前费用范围及独立回执为准，不重做已通过问答。登录恢复后回到待验步骤。')
    body+=paragraph('证据适用与影响分析',record.get('impactAnalysis'))
    if phase:
        body+='<h3>五阶段实施与验收</h3><table><thead><tr><th>阶段</th><th>涉及模块</th><th>具体工作与完成标准</th><th>当前结果</th></tr></thead><tbody>'
        for p in phase.get('phases',[]):
            body+='<tr><td>'+e(str(p['order'])+'．'+p['name'])+'</td><td>'+e('、'.join(p['modules']))+'</td><td>'+e(p['changeType'])+'<br>'+e(p['work'])+'<br><b>完成标准：</b>'+e(p['completion'])+'</td><td>'+e(p['status'])+'</td></tr>'
        body+='</tbody></table>'
        for key,label in [('runtimeSummary','当前环境检查'),('implementationSummary','代码与运行版本'),('permissionSummary','普通用户与空间前提'),('browserBlocker','界面继续条件'),('impactAnalysis','本次修改影响'),('paidExecution','调用与费用范围')]:
            body+=paragraph(label,phase.get(key))
    continuation=phase.get('continuation',{})
    body+=paragraph('当前续验位置',continuation.get('position'))+paragraph('继续执行的限制',continuation.get('limits'))
    next_steps=continuation.get('nextSteps') or record.get('executionPlan',[])
    body+='<h3>下一步按依赖执行</h3><ol>'+''.join('<li>'+e(p)+'</li>' for p in next_steps)+'</ol>'
    body+=module_progress_table(record)
    body+='<p>'+local_link('主模块验收文档.md','完整可复用验收正文')+' · '+local_link('API密钥管理与测试文档.md','独立API密钥管理与测试正文')+' · '+local_link('证据/验收执行记录.json','唯一实际记录')+'</p>'
    common=[f for f in flows if f['module'] not in modules]
    if common:
        body+='<h3>共同前提与跨模块检查</h3><p>这些流程不新增业务菜单；共同清单在主验收正文，只有认证/权限受影响时才重新执行相关登录场景。</p>'+''.join(common_flow_summary(f,i) for i,f in enumerate(common,1))
    body+='</section>'
    chain_pending=[s.get('name','未命名步骤') for s in record.get('coreWorkflow',[]) if s.get('result') in ('未执行','受阻')]
    body+='<section id="chain"><h2>链路摘要：上传文档 → 检索片段 → 问答与引用 → 历史</h2><p>按真实业务顺序保留步骤结果与归属链接，原失败观察仍保留；详细输入、预期、实际及原图由独立模块报告展示。本示例不重复完整证据，也不增加执行统计。</p>'
    body+=paragraph('当前未完成步骤','；'.join(chain_pending) if chain_pending else '本示例没有标为未执行或受阻的步骤；仍须核对各步骤必需证据、原失败处理及完整模块的其他流程。')
    body+=core_workflow_summary(record,progressed)+'</section>'
    body+='<section id="coverage"><h2>原能力到当前流程的覆盖</h2><p>29组正式业务均找到目标代码依据；源码存在不能证明运行正确。以下同时列出平台替代、实现缺口和原非业务边界。</p>'
    for f in audit.get('migration',{}).get('findings',[]):
        body+='<details class="finding"><summary>'+e(f['classification'])+'</summary>'+paragraph('核实内容',f['fact'])+paragraph('解决顺序',f['next'])+'</details>'
    body+='<table><thead><tr><th>原来源能力</th><th>目标承接及差异</th><th>对应完整流程</th></tr></thead><tbody>'
    for row in audit.get('migration',{}).get('sourceMapping',[]):
        mapped=[f for f in flows if row['sequence'] in f.get('sourceGroups',[])]
        refs=row.get('targetReferences',[])[:2]
        refhtml=' '.join(local_link(os.path.relpath(WORKSPACE/r['path'],HERE),Path(r['path']).name+':'+str(r['line'])) for r in refs)
        body+='<tr><td>'+e(str(row['sequence'])+'. '+row['name'])+'</td><td>'+e(row.get('logic'))+'<br>'+e(row.get('knownGapOrReplacement',row.get('sourceBoundary','')))+'<br>'+refhtml+'</td><td>'+('；'.join(f'<a href="#{e(f["id"])}">{e(f["name"])}</a>' for f in mapped) or e('原非业务边界，见完整性核对流程'))+'</td></tr>'
    body+='</tbody></table></section>'
    body+='<section id="baseline"><h2>沿用的'+str(baseline_count)+'项主链路证据定位</h2><p>原结果、时间、适用版本和失败/复验观察保留在唯一记录及所属模块报告。本页只提供定位摘要，没有重跑，不等于较宽完整流程通过。</p>'
    body+=''.join(baseline_summary(point,record,index,progressed) for index,point in enumerate(record['points'],1))
    body+='</section>'
    for i,module in enumerate(modules,1):
        ms=[f for f in flows if f['module']==module]
        business_count=sum(verification_type(f) != '技术验证' for f in ms)
        body+=f'<section id="module-{i}"><h2>{i}. {e(module)}</h2>'+paragraph('当前位置',f'本模块 {len(ms)} 个清单流程，其中 {business_count} 个业务流程、{len(ms)-business_count} 个独立技术流程；较宽场景按逐步证据判定。技术通过不代表模块完整业务通过，仍缺样本、配置或入口的内容保持原真实状态。')
        module_body=HERE/'模块验收'/f'{module}.md'
        if module_body.exists():body+='<p>唯一模块正文：'+local_link('模块验收/'+module+'.md',module+'完整验收文档')+'</p>'
        if module in progressed:body+='<p>独立模块报告：'+local_link('证据/模块报告/'+module+'/verification-report.html',module+'完整流程与证据')+'</p>'
        if ms: body+=paragraph('原证据覆盖到的位置',ms[0].get('moduleKnownCurrent'))
        body+=''.join(flow_summary(f,j,progressed) for j,f in enumerate(ms,1))+'</section>'
    body+='<section><h2>技术检查、报告检查与用户决定</h2>'
    for label,key in [('后端原技术检查','java'),('前端原技术检查','frontend'),('补丁检查','patch'),('启动原技术检查','startup'),('管理员异常恢复技术检查','administratorRecovery'),('报告生成与核对工具','reportTools'),('文档格式离线检查','documentFormats'),('图片探针准备与审查','visionProbePreparation')]:
        body+=paragraph(label,record.get('technicalSummary',{}).get(key))
    body+=paragraph('报告视觉检查',record.get('reportReview',{}).get('reason'))+paragraph('用户接受',record['userAcceptance'])+'<p>本报告由“工具/生成验收报告.py”读取唯一实际记录生成并更新固定路径。页面截图证明可见状态；账本、对象摘要、任务、索引等数据用原始记录独立核对。缺少必需证据不得通过。</p></section></main>'
    css='''body{margin:0;background:#f3f5f8;color:#243244;font:16px/1.7 system-ui,"Microsoft YaHei",sans-serif}header,main{max-width:1100px;margin:auto;padding:28px}header{padding-top:46px}.eyebrow{color:#315bb2}h1{font-size:32px}h2{font-size:25px;border-bottom:1px solid #dbe2ec;padding-bottom:12px}h3{font-size:20px}h4{font-size:18px;margin-top:0}nav{max-width:1100px;margin:0 auto;padding:16px 28px;display:flex;flex-wrap:wrap;gap:9px}nav a,.tag{background:#e7edf7;border-radius:6px;padding:5px 10px}a{color:#2258a6}section{scroll-margin-top:20px}main>section{background:white;padding:24px;margin:20px 0;border-radius:12px}.metrics{display:grid;grid-template-columns:repeat(auto-fit,minmax(135px,1fr));gap:12px}.metrics div{padding:14px;background:#f4f7fb;border-radius:8px}.metrics strong{font-size:30px;display:block}.metrics span{font-size:14px}.flow,.finding{border:1px solid #d6dfe9;border-radius:9px;margin:16px 0}summary{padding:15px;cursor:pointer;font-weight:600}.flowbody{padding:8px 20px 20px}.tag{font-size:13px;display:inline-block}.step{border-left:4px solid #c39b3b;background:#fafbfe;padding:20px;margin:20px 0}.step.pass{border-left-color:#299461}.step.fail{border-left-color:#d24949}.empty{padding:14px;background:#fff3d7;color:#745824}.reuse{font-size:14px;color:#5b687b}figure{margin:20px 0}img{display:block;max-width:100%;max-height:760px;height:auto;border:1px solid #dce3ed;border-radius:6px}figcaption{font-size:14px;color:#56677d;padding-top:8px}table{width:100%;border-collapse:collapse;font-size:14px}td,th{border:1px solid #dce3ed;padding:12px;text-align:left;vertical-align:top}td:first-child{min-width:140px}p{overflow-wrap:anywhere}.finding>p{margin:12px 20px}button{padding:10px;margin:10px}input{padding:12px;width:min(600px,90%)}@media(max-width:600px){header,main,nav{padding:18px}main>section{padding:16px}.flowbody{padding:12px}table{display:block;overflow:auto}h1{font-size:27px}}@media print{nav,.filter{display:none}details>div{display:block!important}section,details{break-inside:auto}img{max-height:650px}}'''
    controls='<div class="filter"><label>查找模块或流程 <input id="query" type="search" placeholder="例如：上传、片段、邀请码、OCR"></label><button id="expand">展开全部流程</button><button id="collapse">收起全部流程</button></div>'
    body=body.replace('<main>','<main>'+controls,1)
    body=module_display_text(body)
    script='''document.getElementById('query').addEventListener('input',ev=>{const q=ev.target.value.trim();document.querySelectorAll('details[data-name]').forEach(d=>{d.hidden=!!q&&!d.dataset.name.includes(q);if(q&&!d.hidden)d.open=true;});});document.getElementById('expand').onclick=()=>document.querySelectorAll('details').forEach(d=>{d.open=true});document.getElementById('collapse').onclick=()=>document.querySelectorAll('details').forEach(d=>{d.open=false});document.querySelectorAll('a[href^="#"]').forEach(a=>a.onclick=()=>{const t=document.getElementById(a.getAttribute('href').slice(1));if(t&&t.tagName==='DETAILS'){t.hidden=false;t.open=true;}});'''
    REPORT.write_text('<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>PaiSmart完整业务验收与证据</title><style>'+css+'</style></head><body>'+body+'<script>'+script+'</script></body></html>',encoding='utf-8')
    for module in progressed:
        output=module_report_path(module)
        output.parent.mkdir(parents=True,exist_ok=True)
        output.write_text(module_report_html(record,module,css,controls,script),encoding='utf-8')
    print(json.dumps({'report':str(REPORT),'modules':len(modules),'flows':len(flows),'steps':sum(len(f.get('steps',[])) for f in flows),'flowResults':dict(count),'technicalFlowResults':dict(technical_count),'executionStatistics':execution,'imageInstances':body.count('<img '),'uniqueImages':len(set(re.findall(r'<img[^>]*src="([^"]+)"',body))),'moduleReports':progressed},ensure_ascii=False))

if __name__=='__main__':
    main()
