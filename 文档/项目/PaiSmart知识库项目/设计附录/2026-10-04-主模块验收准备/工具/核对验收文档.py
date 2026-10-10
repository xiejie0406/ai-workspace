"""核对统一入口、各模块唯一验收正文及API管理主题，不写文件、不调用模型。"""
from pathlib import Path
import json
import re
from html.parser import HTMLParser
from urllib.parse import unquote, urlsplit
from collections import Counter
import importlib.util
from datetime import datetime

HERE = Path(__file__).resolve().parent.parent
generator_spec = importlib.util.spec_from_file_location('paismart_report_generator', HERE / '工具/生成验收报告.py')
generator = importlib.util.module_from_spec(generator_spec)
generator_spec.loader.exec_module(generator)


def image_header_type(data):
    """只验证支持的图片签名；不把文件头检查声称为回读或视觉验收。"""
    if data.startswith(b'\xff\xd8\xff'):
        return 'JPEG'
    if data.startswith(b'\x89PNG\r\n\x1a\n'):
        return 'PNG'
    if data.startswith((b'GIF87a', b'GIF89a')):
        return 'GIF'
    if len(data) >= 12 and data[:4] == b'RIFF' and data[8:12] == b'WEBP':
        return 'WebP'
    return None


def baseline_integrity(record):
    reuse = record.get('baselineReuse', {})
    ids = reuse.get('pointIds', [])
    points = record.get('points', [])
    point_ids = [p.get('id') for p in points]
    original_ids={'model_config','admin_quota','llm_verify','embedding_verify','upload_index','chat_citation','history_usage'}
    return (set(ids) == original_ids and len(ids) == len(set(ids)) and len(point_ids) == len(set(point_ids))
            and reuse.get('count') == len(ids) and set(point_ids) == set(ids)
            and all(p.get('result') == '通过' for p in points))


def execution_evidence_errors(record, evidence_root):
    """只核对声明、分类和证据可达性；不能代替实际内容复核。"""
    execution = generator.execution_statistics(record)
    summary_bad, mode_bad, evidence_bad = [], [], []
    current = record.get('businessSummary', {}).get('currentExecuted')
    new_business = record.get('baselineReuse', {}).get('newBusinessExecuted')
    for label, value in [('businessSummary.currentExecuted', current), ('baselineReuse.newBusinessExecuted', new_business)]:
        if not isinstance(value, int) or isinstance(value, bool) or value < 0:
            summary_bad.append(label+'必须为非负整数')
    if not summary_bad and current != new_business:
        summary_bad.append('本次业务执行数量在两个摘要中不一致')
    explicit_steps = record.get('businessSummary', {}).get('currentExecutedSteps')
    if explicit_steps is not None and (not isinstance(explicit_steps, int) or isinstance(explicit_steps, bool) or explicit_steps != execution['currentExecutedSteps']):
        summary_bad.append('本次执行步骤摘要与流程记录不一致')
    flows = record.get('flows', [])
    technical_counts = generator.flow_statistics(record)['technical']
    expected_technical = {
        'currentExecutedSteps': execution['currentTechnicalExecutedSteps'],
        'currentPassedSteps': execution['currentTechnicalPassedSteps'],
        'passedSteps': execution['technicalPassedSteps'],
        'flows': sum(technical_counts.values()),
        'passedFlows': technical_counts.get('通过', 0),
    }
    technical_summary = record.get('technicalExecutionSummary', {})
    if not isinstance(technical_summary, dict):
        summary_bad.append('技术执行摘要必须是对象')
        technical_summary = {}
    for key, expected in expected_technical.items():
        if key in technical_summary:
            value = technical_summary[key]
            if not isinstance(value, int) or isinstance(value, bool) or value != expected:
                summary_bad.append('技术执行摘要'+key+'与流程记录不一致')
    for flow in flows:
        flow_type = generator.verification_type(flow)
        if flow_type not in ('业务验收', '技术验证'):
            mode_bad.append(flow['name']+'验证类型无效')
        if flow.get('result') == '通过' and any(s.get('result') not in ('通过', '不适用') for s in flow.get('steps', [])):
            evidence_bad.append(flow['name']+'完整流程通过但仍有未通过步骤')
        for number, step in enumerate(flow.get('steps', []), 1):
            label = flow['name']+'步骤'+str(number)
            step_type = generator.verification_type(step, flow)
            kind = generator.execution_kind(step)
            if step_type not in ('业务验收', '技术验证') or step_type != flow_type:
                mode_bad.append(label+'技术与业务验证类型混用或无效')
            if (kind == 'currentTechnical' and step_type != '技术验证') or (kind == 'current' and step_type == '技术验证'):
                mode_bad.append(label+'执行方式与技术或业务类型不一致')
            if kind in ('current', 'currentTechnical') and (step.get('result') in ('未执行', '不适用') or not step.get('executedAt') or not str(step.get('actual', '')).strip()):
                mode_bad.append(label+'本次执行须有执行时间和实际结果')
            if step.get('result') == '通过' and kind == 'unclassified':
                mode_bad.append(label+'通过结果未注明本次或沿用')
            technical_executed = step_type == '技术验证' and (kind == 'currentTechnical' or step.get('result') == '通过')
            if technical_executed:
                try:
                    timestamp = datetime.fromisoformat(str(step.get('executedAt', '')).replace('Z', '+00:00'))
                    if timestamp.tzinfo is None:
                        raise ValueError('缺少时区')
                except ValueError:
                    mode_bad.append(label+'技术执行须有带时区的真实执行时间')
                if not isinstance(step.get('evidenceSource'), str) or not step['evidenceSource'].strip():
                    evidence_bad.append(label+'技术执行缺具体证据来源条目')
                paths = step.get('evidence')
                if not isinstance(paths, list) or not paths:
                    evidence_bad.append(label+'技术执行缺替代证据文件')
                else:
                    for path in paths:
                        if not isinstance(path, str) or not path.strip() or not (evidence_root/path).is_file():
                            evidence_bad.append(label+'技术执行替代证据文件无效')
                if step.get('screenshotStatus') == '不适用' and (not isinstance(step.get('evidenceReason'), str) or not step['evidenceReason'].strip()):
                    evidence_bad.append(label+'无页面技术步骤缺替代证据理由')
            if step.get('result') == '通过':
                photos = step.get('screenshots', [])
                status = step.get('screenshotStatus', '')
                if status == '不适用':
                    if not step.get('evidence'):
                        evidence_bad.append(label+'无页面步骤通过但没有替代证据')
                elif not photos or '已截图' not in status or '回读' not in status:
                    evidence_bad.append(label+'通过结果缺必需截图或逐张回读声明')
                for photo in photos:
                    proof = photo.get('proof') if isinstance(photo, dict) else step.get('screenshotProof')
                    if not proof:
                        evidence_bad.append(label+'截图没有注明证明内容')
    return summary_bad, mode_bad, evidence_bad


class ReportParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links=[]; self.images=[]; self.ids=[]; self.flows=[]; self.steps=[]; self.text=[]
    def handle_starttag(self,tag,attrs):
        attrs=dict(attrs)
        if 'id' in attrs: self.ids.append(attrs['id'])
        if tag=='a' and 'href' in attrs: self.links.append(attrs['href'])
        if tag=='img' and 'src' in attrs: self.images.append(attrs['src'])
        if tag=='details' and 'data-name' in attrs: self.flows.append(attrs.get('id'))
        if tag=='section' and 'data-flow-id' in attrs: self.steps.append((attrs['data-flow-id'],attrs.get('data-step-order')))
    def handle_data(self,data):
        self.text.append(data)


def report_reference_errors(report, parser, html_cache=None):
    """相对引用基于各报告本身；跨HTML定位也必须真实存在。"""
    html_cache=html_cache or {}
    broken=[]
    for target in parser.links+parser.images:
        url=urlsplit(target)
        if url.scheme or url.netloc: continue
        path=(report.parent/unquote(url.path)).resolve() if url.path else report.resolve()
        if path==report.resolve():
            ids=parser.ids
        elif path in html_cache:
            ids=html_cache[path].ids
        elif not path.exists():
            broken.append(target); continue
        elif path.suffix.lower()=='.html' and url.fragment:
            linked=ReportParser(); linked.feed(path.read_text(encoding='utf-8')); ids=linked.ids
        else:
            ids=None
        if url.fragment and ids is not None and unquote(url.fragment) not in ids:
            broken.append(target)
    return broken


def module_report_errors(record, total_parser, html_cache=None):
    """独立报告必须只展示所属完整流程和明确引用原证据；不评价业务正确性。"""
    html_cache=html_cache or {}
    problems=[]; summaries=[]
    advanced=generator.progressed_modules(record)
    total_targets={(HERE/unquote(urlsplit(target).path)).resolve() for target in total_parser.links if urlsplit(target).path and not urlsplit(target).scheme}
    for module in record.get('moduleOrder',[]):
        report=generator.module_report_path(module).resolve()
        if module not in advanced:
            if report.is_file() or report in html_cache: problems.append(module+'完全未执行但存在独立报告，须核对记录或报告适用性')
            continue
        if report in html_cache:
            parser=html_cache[report]
        elif report.is_file():
            parser=ReportParser();parser.feed(report.read_text(encoding='utf-8'))
        else:
            problems.append(module+'缺少已推进模块固定独立报告');continue
        flows=[flow for flow in record.get('flows',[]) if flow.get('module')==module]
        expected_flows=[flow['id'] for flow in flows]
        expected_steps=[(flow['id'],str(index)) for flow in flows for index,step in enumerate(flow.get('steps',[]),1)]
        if parser.flows!=expected_flows: problems.append(module+'独立报告流程遗漏、重复或混入其他模块')
        if parser.steps!=expected_steps: problems.append(module+'独立报告完整步骤覆盖不一致')
        visible=''.join(parser.text)
        for label in ('要求实现或迁移','独立技术流程结果','业务流程结果','截图完整度','用户接受','报告视觉检查'):
            if label not in visible: problems.append(module+'缺少独立结果说明：'+label)
        if re.search(r'\b[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}\b|\b[0-9a-fA-F]{64}\b',visible):
            problems.append(module+'可见内容仍含长记录标识或完整摘要，原始信息应在证据中保留')
        if report not in total_targets: problems.append(module+'缺少总报告到独立报告的实际导航')
        duplicated=[key for key,value in Counter(parser.ids).items() if value>1]
        if duplicated: problems.append(module+'独立报告定位重复：'+','.join(duplicated))
        expected_baselines=generator.module_baseline_ids(record,module)
        actual_baselines={value.removeprefix('baseline-') for value in parser.ids if value.startswith('baseline-')}
        if actual_baselines!=expected_baselines: problems.append(module+'独立报告原证据不等于本模块明确引用范围')
        broken=report_reference_errors(report,parser,html_cache)
        problems.extend(module+'失效引用：'+target for target in broken)
        image_bad=[]
        for target in set(parser.images):
            path=(report.parent/unquote(urlsplit(target).path)).resolve()
            if not path.is_file() or not image_header_type(path.read_bytes()[:16]): image_bad.append(target)
        problems.extend(module+'图片缺失或签名无效：'+target for target in image_bad)
        expected_images=set()
        for flow in flows:
            for step in flow.get('steps',[]):
                for photo in step.get('screenshots',[]):
                    expected_images.add((HERE/'证据'/(photo['path'] if isinstance(photo,dict) else photo)).resolve())
        for point in record.get('points',[]):
            if point['id'] in expected_baselines:
                expected_images.update((HERE/'证据'/path).resolve() for path in point.get('screenshots',[]))
        actual_images={(report.parent/unquote(urlsplit(target).path)).resolve() for target in parser.images}
        if expected_images!=actual_images: problems.append(module+'独立报告截图集合与所属步骤及明确引用原证据不一致')
        summaries.append({'模块':module,'完整流程':len(flows),'完整步骤':len(expected_steps),'图片实例':len(parser.images),'不同图片':len(set(parser.images)),'链接':len(parser.links),'明确引用原证据':len(actual_baselines)})
    return advanced,summaries,problems


DOC = HERE / "主模块验收文档.md"
API_DOC = HERE / "API密钥管理与测试文档.md"
text = DOC.read_text(encoding="utf-8")
api_text = API_DOC.read_text(encoding="utf-8")
record_path = HERE / '证据/验收执行记录.json'
record = json.loads(record_path.read_text(encoding='utf-8'))
module_bodies = {}
module_files = {}
missing_modules = []
for name in record.get('moduleOrder', []):
    path = HERE / '模块验收' / f'{name}.md'
    if path.exists():
        module_files[name] = path
        module_bodies[name] = path.read_text(encoding='utf-8')
    else:
        missing_modules.append(name)
missing = []
for doc, body in [(DOC, text), (API_DOC, api_text), *[(module_files[name], body) for name, body in module_bodies.items()]]:
    for target in re.findall(r"\]\(([^)]+)\)", body):
        url = urlsplit(target)
        if not url.scheme and not url.netloc and url.path and not (doc.parent / unquote(url.path)).resolve().exists():
            missing.append({"正文": str(doc.relative_to(HERE)), "链接": target})
missing.extend(missing_modules)
default_model = re.search(r"用户指定默认([^\s；|]+)", api_text)
result = {
    "验收正文": DOC.name,
    "API管理与测试正文": API_DOC.name,
    "模块数量": len(re.findall(r"^### \d+\. ", text, re.M)),
    "模块唯一正文": len(module_bodies),
    "缺失模块正文": missing_modules,
    "默认模型": default_model.group(1) if default_model else None,
    "缺失链接": missing,
}
flows=record.get('flows',[])
if flows:
    parser=ReportParser()
    parser.feed((HERE/'verification-report.html').read_text(encoding='utf-8'))
    broken=report_reference_errors(HERE/'verification-report.html',parser)
    duplicated=[k for k,v in Counter(parser.ids).items() if v>1]
    groups={g for f in flows for g in f.get('designAcceptanceGroups',[])}
    sources={g for f in flows for g in f.get('originalCapabilityRows',[])}
    absent=[]
    ownership_bad=[]
    module_steps=0
    for f in flows:
        owner = f.get('module')
        body = module_bodies.get(owner, text)
        if f['id'] not in parser.ids or f['name'] not in body:
            absent.append(f['name'])
        if owner in record.get('moduleOrder', []):
            exact_heading = f"### {f['name']}"
            owners = [name for name, candidate in module_bodies.items() if exact_heading in candidate.splitlines()]
            if owners != [owner] or body.splitlines().count(exact_heading) != 1 or f"#### {f['name']}" in text.splitlines():
                ownership_bad.append(f['name'])
    for name, body in module_bodies.items():
        module_steps += len(re.findall(r'^\*\*步骤 \d+\. ', body, re.M))
        expected_flows = [f for f in flows if f.get('module') == name]
        if len(re.findall(r'^\*\*步骤 \d+\. ', body, re.M)) != sum(len(f['steps']) for f in expected_flows):
            ownership_bad.append(f'{name}步骤数量')
        if f'模块验收/{name}.md' not in text:
            ownership_bad.append(f'{name}缺少主入口引用')
        for section in ('## 当前完成位置与继续条件', '**目前位置**', '**未完成原因**', '**下一待验入口**', '**角色、环境与状态**', '**沿用或准备样本**'):
            if section not in body:
                ownership_bad.append(f'{name}缺少继续条件')
        for target in re.findall(r"\]\(([^)]+)\)", body):
            url=urlsplit(target)
            if url.path and not url.scheme and Path(unquote(url.path)).name == 'verification-report.html':
                reference=ReportParser()
                reference.links=[target]
                broken.extend(report_reference_errors(module_files[name],reference))
    incomplete=[]
    for f in flows:
        if not f.get('steps'): incomplete.append(f['name'])
        for s in f.get('steps',[]):
            if any(not s.get(k) for k in ['action','input','expected','dataCheck','screenshotStage','actual','result','screenshotStatus']): incomplete.append(f['name'])
    headers_bad=[]
    image_types=Counter()
    for target in set(parser.images):
        image_path=(HERE/unquote(urlsplit(target).path)).resolve()
        if image_path.exists():
            kind=image_header_type(image_path.read_bytes()[:16])
            if kind: image_types[kind]+=1
            else: headers_bad.append(target)
    baseline_ok=baseline_integrity(record)
    execution=generator.execution_statistics(record)
    summary_bad,mode_bad,evidence_bad=execution_evidence_errors(record,HERE/'证据')
    flow_counts=generator.flow_statistics(record)
    result.update({'完整流程':len(flows),'步骤':sum(len(f['steps']) for f in flows),'设计分组覆盖':len(groups),'未覆盖设计分组':sorted(set(range(1,32))-groups),'未覆盖正式来源':sorted(set(range(1,30))-sources),'本次新执行业务':record.get('businessSummary',{}).get('currentExecuted'),'原7项基线保留':baseline_ok,'步骤执行统计':execution,'执行统计异常':summary_bad,'执行方式异常':mode_bad,'通过证据异常':evidence_bad,'HTML图片实例':len(parser.images),'HTML不同图片':len(set(parser.images)),'HTML链接':len(parser.links),'失效HTML引用':broken,'重复HTML定位':duplicated,'未展示流程':absent,'步骤字段缺失':incomplete,'图片文件头类型':dict(image_types),'无效图片文件头':headers_bad,'图片检查范围':'仅文件签名；不能代替截图逐张回读或HTML视觉检查'})
    result.update({'完整业务流程':sum(flow_counts['business'].values()),'独立技术流程':sum(flow_counts['technical'].values()),'业务流程结果':dict(flow_counts['business']),'技术流程结果':dict(flow_counts['technical'])})
    result.update({'模块正文流程':sum(1 for f in flows if f.get('module') in record.get('moduleOrder',[])), '模块正文步骤':module_steps,'模块正文归属异常':ownership_bad})
    advanced,module_reports,module_bad=module_report_errors(record,parser)
    total_role_bad=[]
    if parser.steps or parser.images: total_role_bad.append('项目总报告重复展示完整步骤或图片，应只作导航与汇总')
    expected_baselines={'baseline-'+point['id'] for point in record.get('points',[])}
    actual_baselines={value for value in parser.ids if value.startswith('baseline-')}
    if expected_baselines!=actual_baselines: total_role_bad.append('项目总报告原基线定位不完整')
    expected_flow_order=[flow['id'] for flow in flows if flow.get('module') not in record.get('moduleOrder',[])]
    expected_flow_order.extend(flow['id'] for module in record.get('moduleOrder',[]) for flow in flows if flow.get('module')==module)
    if parser.flows!=expected_flow_order:
        total_role_bad.append('项目总报告流程定位遗漏或重复')
    result.update({'已推进独立模块报告':advanced,'独立模块报告核对':module_reports,'独立模块报告异常':module_bad,'独立报告检查范围':'静态链接/HTML定位/图片签名/所属完整流程步骤与原证据范围；不代替视觉检查、截图回读、业务判定或用户接受'})
    result.update({'总报告正文职责异常':total_role_bad,'总报告职责':'只作导航与汇总；全部所属完整步骤及业务图片由独立模块报告核对'})
    missing.extend(broken+duplicated+absent+incomplete+headers_bad)
    missing.extend(ownership_bad)
    missing.extend(result['未覆盖设计分组']+result['未覆盖正式来源'])
    missing.extend(summary_bad+mode_bad+evidence_bad)
    missing.extend(module_bad)
    missing.extend(total_role_bad)
    if not baseline_ok: missing.append('原通过基线标识、数量或通过结果不完整')
print(json.dumps(result, ensure_ascii=False))
raise SystemExit(0 if result["模块数量"] == 16 and len(module_bodies) == 16 and default_model and not missing else 1)
