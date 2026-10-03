"""资料冻结后执行；只读核对七份当前文档，唯一写出既有最终文档核对.json。

用法：python 最终文档只读核对.py --write-current-report --parser-results <本轮19项results.json>
不生成业务证据，不启动浏览器/Maven/Worker，不修改基线、清单或HTML。
"""
import argparse
import ast
import csv
from collections import Counter
from contextlib import redirect_stdout
from datetime import datetime, timezone
import hashlib
import html
from html.parser import HTMLParser
import io
import json
from pathlib import Path
import re
import sys
from urllib.parse import unquote, urlsplit
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[5]
PROJECT = ROOT / '文档/项目/PaiSmart知识库项目'
APPENDIX = PROJECT / '设计附录'
ROUND = Path(__file__).resolve().parent
BUSINESS = APPENDIX / '2026-10-03-真实业务验收准备'
CURRENT = [PROJECT / '完整迁移设计.md', ROOT / 'ruoyi-backend/ruoyi-paismart/README.md',
           ROOT / 'contracts/paismart/README.md', BUSINESS / '逐项验收清单.md',
           BUSINESS / 'verification-report.html', BUSINESS / '验收记录.json',
           APPENDIX / '2026-10-03-迁移补齐-前端技术复验.html']
BASELINE = ROUND / '完整迁移设计-本轮复核前.md'
OUTPUT = BUSINESS / '最终文档核对.json'
PROBLEMS = []


def relative(path):
    path = Path(path).resolve()
    return path.relative_to(ROOT).as_posix() if path.is_relative_to(ROOT) else str(path)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def text(path):
    return Path(path).read_text(encoding='utf-8-sig')


def load(path):
    return json.loads(text(path))


def assert_fact(condition, message, path=None, line=None):
    if not condition:
        issue = {'message': message}
        if path is not None:
            issue['path'] = relative(path)
        if line is not None:
            issue['line'] = line
        PROBLEMS.append(issue)
    return bool(condition)


def safe_check(name, action):
    try:
        return action()
    except Exception as failure:
        PROBLEMS.append({'check': name, 'message': type(failure).__name__ + ': ' + str(failure)})
        return {'checkFailed': True}


class HTMLFacts(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.ids = set()
        self.references = []
        self.parts = []

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        if 'id' in values:
            self.ids.add(values['id'])
        if tag == 'a' and values.get('name'):
            self.ids.add(values['name'])
        for key in ('href', 'src'):
            if values.get(key):
                self.references.append((self.getpos()[0], values[key], key))

    def handle_data(self, data):
        self.parts.append(data)


def parse_html(content):
    facts = HTMLFacts()
    facts.feed(content)
    return facts


def markdown_anchors(content):
    anchors = set()
    duplicates = Counter()
    for match in re.finditer(r'^#{1,6}\s+(.+?)\s*#*$', content, re.M):
        title = re.sub(r'<[^>]*>', '', match.group(1))
        slug = re.sub(r'[^\w\-\s]', '', title.lower()).replace(' ', '-')
        nth = duplicates[slug]
        duplicates[slug] += 1
        anchors.add(slug if nth == 0 else slug + '-' + str(nth))
    return anchors


def local_reference(source, target):
    target = html.unescape(target.strip())
    if target.startswith('<') and target.endswith('>'):
        target = target[1:-1]
    # Markdown optional title is not part of the URL.
    target = re.sub(r'\s+["\'].+?["\']$', '', target)
    if target.startswith('//') or re.match(r'^(https?|mailto|data|javascript|app|codex|plugin):', target, re.I):
        return None
    if re.match(r'^[A-Za-z]:[\\/]', target):
        return Path(unquote(target)).resolve(), ''
    split = urlsplit(target)
    if split.scheme:
        return None
    file = unquote(split.path).replace('\\', '/')
    return (source.parent / file).resolve() if file else source.resolve(), unquote(split.fragment)


def json_references(value, content, pointer=''):
    found = []
    if isinstance(value, dict):
        for key, child in value.items():
            here = pointer + '/' + key
            if key in {'href', 'document'} and isinstance(child, str):
                literal = json.dumps(child, ensure_ascii=False)
                at = content.find(literal)
                found.append((content.count('\n', 0, at) + 1 if at >= 0 else None, child, 'json:' + here))
            found += json_references(child, content, here)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            found += json_references(child, content, pointer + '/' + str(index))
    return found


def check_links(paths):
    records = []
    files = []
    for source in paths:
        content = text(source)
        if source.suffix == '.md':
            masked = re.sub(r'```.*?```', lambda m: '\n' * m.group(0).count('\n'), content, flags=re.S)
            references = [(masked.count('\n', 0, m.start()) + 1, m.group(1), 'markdown')
                          for m in re.finditer(r'!?\[[^\]\n]*\]\(([^)\n]+)\)', masked)]
        elif source.suffix == '.html':
            references = parse_html(content).references
        else:
            references = json_references(json.loads(content), content)
        count = 0
        for line, raw, kind in references:
            local = local_reference(source, raw)
            if local is None:
                continue
            target, fragment = local
            exists = target.exists()
            entry = {'source': relative(source), 'line': line, 'kind': kind, 'target': raw,
                     'resolved': relative(target), 'exists': exists}
            count += 1
            assert_fact(exists, '本地引用不存在：' + raw, source, line)
            if fragment:
                entry['fragment'] = fragment
                anchor_found = False
                if exists and target.is_file() and target.suffix.lower() in {'.html', '.htm', '.md'}:
                    target_text = text(target)
                    anchors = parse_html(target_text).ids if target.suffix.lower() != '.md' else markdown_anchors(target_text)
                    anchor_found = fragment in anchors
                entry['anchorFound'] = anchor_found
                assert_fact(anchor_found, '本地锚点不存在：' + raw, source, line)
            records.append(entry)
        files.append({'path': relative(source), 'sha256': digest(source), 'bytes': source.stat().st_size,
                      'localReferences': count})
    return files, {'count': len(records), 'fragmentCount': sum('fragment' in r for r in records),
                   'missingLocalReferences': sum(not r['exists'] for r in records),
                   'missingAnchors': sum(r.get('anchorFound') is False for r in records), 'references': records}


def sections(content):
    normalized = content.replace('\r\n', '\n')
    headings = list(re.finditer(r'^## ([1-6])\..*$', normalized, re.M))
    result = {}
    for index, heading in enumerate(headings):
        number = int(heading.group(1))
        if number > 5:
            continue
        end = headings[index + 1].start() if index + 1 < len(headings) else len(normalized)
        result[number] = {'text': normalized[heading.start():end],
                          'startLine': normalized.count('\n', 0, heading.start()) + 1,
                          'endLine': normalized.count('\n', 0, end)}
    return result


def check_requirements():
    # decode bytes directly: read_text would conceal CRLF/LF differences.
    current_raw = CURRENT[0].read_bytes().decode('utf-8-sig')
    old_raw = BASELINE.read_bytes().decode('utf-8-sig')
    current, old = sections(current_raw), sections(old_raw)
    assert_fact(set(current) == set(old) == set(range(1, 6)), '无法唯一定位首五节', CURRENT[0])
    rows = []
    for number in range(1, 6):
        left, right = current[number], old[number]
        same = left['text'] == right['text']
        assert_fact(same, '第' + str(number) + '节超出CRLF/LF差异', CURRENT[0], left['startLine'])
        rows.append({'section': number, 'currentStartLine': left['startLine'], 'currentEndLine': left['endLine'],
                     'baselineStartLine': right['startLine'], 'baselineEndLine': right['endLine'],
                     'normalizedSHA256': hashlib.sha256(left['text'].encode('utf-8')).hexdigest(),
                     'normalizedEqual': same})
    return {'baseline': relative(BASELINE), 'baselineSHA256': digest(BASELINE),
            'normalization': '仅CRLF→LF；范围从对应## N.标题到下一个##标题之前，包含尾部空行；不声称原bytes相同',
            'sections': rows}


def check_business(data):
    cases = data['businessCases']
    assert_fact([case['number'] for case in cases] == list(range(1, 32)), '业务序号必须完整为1—31', CURRENT[5])
    for case in cases:
        number = case['number']
        assert_fact(case.get('result') == '未执行' and case.get('executedAt') is None
                    and case.get('evidence') == [] and case.get('screenshotStatus') == '待截图',
                    '第' + str(number) + '项必须未执行、无业务evidence、未取得截图', CURRENT[5])
        for field in ('requirementSource', 'entryPoints', 'prerequisites', 'steps', 'expected', 'verification', 'requiredScreenshots'):
            assert_fact(bool(case.get(field)), '第' + str(number) + '项缺' + field, CURRENT[5])
    for field in ('businessAcceptanceExecuted', 'userAccepted', 'published'):
        assert_fact(data.get(field) is False, field + '不能误记为true', CURRENT[5])
    rendering = data.get('reportRendering', {})
    assert_fact(rendering.get('businessEvidence') is False, '报告呈现不能作为业务evidence', CURRENT[5])
    for screenshot in rendering.get('screenshots', []):
        target = BUSINESS / screenshot['file']
        assert_fact(target.is_file() and digest(target) == screenshot['sha256'], '历史报告呈现截图文件/hash不符', target)
    counts = {label: sum(c['result'] == label for c in cases) for label in ['通过', '未通过', '受阻', '未执行', '不适用']}
    return {'counts': counts, 'caseIds': [c['number'] for c in cases], 'businessEvidenceEmpty': all(c.get('evidence') == [] for c in cases),
            'executed': data['businessAcceptanceExecuted'], 'userAccepted': data['userAccepted'], 'published': data['published'],
            'reportRenderingBusinessEvidence': rendering.get('businessEvidence'),
            'renderingNote': '仅回读原记录及文件hash；本次未启动浏览器，不声明重新呈现当前HTML'}


def check_generated_views():
    generator = BUSINESS / '生成验收报告.py'
    tree = ast.parse(text(generator), filename=str(generator))
    functions = [node for node in tree.body if isinstance(node, ast.FunctionDef)]
    writes = [node for node in ast.walk(tree) if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
              and node.func.attr == 'write_text']
    for call in writes:
        owner = call.func.value
        if not (isinstance(owner, ast.BinOp) and isinstance(owner.left, ast.Name) and owner.left.id == 'ROOT'
                and isinstance(owner.right, ast.Constant) and owner.right.value in {'逐项验收清单.md', 'verification-report.html'}):
            raise ValueError('生成器新增未审核写入；不能静态安全捕获')
    captured = {}

    class MemoryPath:
        def __init__(self, name=''):
            self.name = name

        def __truediv__(self, name):
            return MemoryPath(name)

        def read_text(self, **kwargs):
            if self.name != '验收记录.json':
                raise ValueError('生成器读取范围变化')
            return text(BUSINESS / self.name)

        def write_text(self, value, **kwargs):
            if self.name not in {'逐项验收清单.md', 'verification-report.html'}:
                raise ValueError('生成器写入范围变化')
            captured[self.name] = value
            return len(value)

    namespace = {'html': html, 'json': json, 'ROOT': MemoryPath(), 'DATA': MemoryPath('验收记录.json')}
    exec(compile(ast.Module(body=functions, type_ignores=[]), str(generator), 'exec'), namespace)
    with redirect_stdout(io.StringIO()):
        namespace['main']()
    assert_fact(set(captured) == {'逐项验收清单.md', 'verification-report.html'}, '生成器未产生两份视图', generator)
    rows = []
    for name, expected in captured.items():
        path = BUSINESS / name
        actual = path.read_bytes().decode('utf-8-sig').replace('\r\n', '\n')
        same = actual == expected.replace('\r\n', '\n')
        assert_fact(same, '验收JSON生成视图与当前磁盘不一致：' + name, path)
        rows.append({'path': relative(path), 'sameAsInMemoryGenerator': same,
                     'generatedNormalizedSHA256': hashlib.sha256(expected.replace('\r\n', '\n').encode('utf-8')).hexdigest()})
    return {'generator': relative(generator), 'generatorSHA256': digest(generator), 'diskWritesByGenerator': 0, 'views': rows}


def root_path(value, parent=None):
    path = Path(value)
    if path.is_absolute():
        return path.resolve()
    normalized = value.replace('\\', '/')
    if normalized.startswith(('文档/', 'ruoyi-backend/', 'frontend/', 'contracts/')):
        return (ROOT / normalized).resolve()
    return ((parent or ROOT) / normalized).resolve()


def check_execution_composition(backend_path, record):
    backend = load(backend_path)
    if 'executionKind' not in backend:
        return {'executionKind': None, 'compositionDeclared': False,
                'scope': '历史记录未声明归并；保持既有逐类JSON/XML和总数核对，不推断执行来源'}
    if backend['executionKind'] != 'DIRECTED_RECHECK_AND_RETAINED_PASSES':
        raise ValueError('未审核的executionKind：' + str(backend['executionKind']))
    manifest_path = root_path(backend['provenanceEvidence'])
    assert_fact(digest(manifest_path) == backend['provenanceSHA256'], '归并manifest摘要与当前后端JSON不符', manifest_path)
    manifest = load(manifest_path)
    composition = backend['executionComposition']
    keys = ('tests', 'failures', 'errors', 'skipped')
    class_names = [suite['class'] for suite in backend['suites']]
    entries = manifest['reports']
    entry_names = [entry['class'] for entry in entries]
    if not assert_fact(len(set(class_names)) == len(class_names) and len(set(entry_names)) == len(entry_names)
                       and set(class_names) == set(entry_names), '归并manifest与后端suite类集合不唯一或不相同', manifest_path):
        raise ValueError('无法逐类唯一对应归并来源')
    suites = {suite['class']: suite for suite in backend['suites']}
    expected_xml = {'TEST-' + name + '.xml' for name in class_names}
    assert_fact({path.name for path in manifest_path.parent.glob('TEST-*.xml')} == expected_xml,
                '当前归并目录XML集合与manifest不相同', manifest_path)
    affected_source = 'ruoyi-backend/ruoyi-paismart/src/test/java/com/ruoyi/paismart/infrastructure/intelligence/IsolatedParserLifecycleTest.java'
    affected_class = 'com.ruoyi.paismart.infrastructure.intelligence.IsolatedParserLifecycleTest'
    groups = {False: {'classes': 0, 'tests': 0}, True: {'classes': 0, 'tests': 0}}
    retained_directories = set()
    origin_rows = []
    for entry in entries:
        name = entry['class']
        suite = suites[name]
        targeted = entry['targetedExecution']
        if type(targeted) is not bool:
            raise ValueError('targetedExecution必须为JSON布尔值：' + name)
        assert_fact(targeted == (name == affected_class), '定向来源必须仅为已改变的生命周期夹具类', manifest_path)
        filename = 'TEST-' + name + '.xml'
        assert_fact(entry['filename'] == filename, '归并filename不能替换类名', manifest_path)
        origin = root_path(entry['origin'])
        current = root_path(suite['report'])
        assert_fact(current == (manifest_path.parent / filename).resolve(), '当前suite未指向归并目录对应XML', backend_path)
        actual_sha = digest(origin)
        same_bytes = current.read_bytes() == origin.read_bytes()
        assert_fact(actual_sha == entry['sha256'] == suite['originSHA256'] and same_bytes,
                    '归并XML与原XML bytes或摘要不一致', current)
        assert_fact(root_path(suite['originReport']) == origin and suite['evidenceSource'] == entry['scope'],
                    '后端suite来源路径/范围与manifest不符', backend_path)
        xml = ET.parse(origin).getroot()
        values = {key: int(xml.attrib[key]) for key in keys}
        assert_fact(xml.get('name') == name and values['tests'] == entry['tests'] == suite['tests']
                    and all(values[key] == suite[key] == 0 for key in keys[1:]),
                    '所选原XML类名、数量或通过状态不符', origin)
        assert_fact(('定向复验' in entry['scope']) if targeted else ('沿用' in entry['scope'] and '未变' in entry['scope']),
                    '逐类scope未明确实际定向与沿用来源', manifest_path)
        groups[targeted]['classes'] += 1
        groups[targeted]['tests'] += values['tests']
        if not targeted:
            retained_directories.add(origin.parent)
        origin_rows.append({'class': name, 'targetedExecution': targeted, 'origin': relative(origin),
                            'current': relative(current), 'sha256': actual_sha, 'sameBytes': same_bytes,
                            'counts': values})
    totals = {'totalClasses': sum(group['classes'] for group in groups.values()),
              'totalTests': sum(group['tests'] for group in groups.values()),
              'retainedFullRunClasses': groups[False]['classes'], 'retainedFullRunTests': groups[False]['tests'],
              'targetedClasses': groups[True]['classes'], 'targetedTests': groups[True]['tests']}
    assert_fact(all(manifest[key] == value for key, value in totals.items())
                and all(composition[key] == totals[key] for key in totals if key not in {'totalClasses', 'totalTests'})
                and totals['totalTests'] == backend['totals']['tests'] and totals['totalClasses'] == len(class_names),
                '实际原XML归并计数与manifest/后端JSON不符', manifest_path)
    assert_fact(totals['targetedClasses'] == 1 and totals['targetedTests'] == 3,
                '本轮受影响生命周期定向范围必须为1类3项', manifest_path)
    # 原完整执行的失败夹具仍保留；35类通过项不能借归并冒称整批重跑。
    if len(retained_directories) != 1:
        raise ValueError('沿用XML必须来自同一首修完整执行目录')
    full_directory = retained_directories.pop()
    assert_fact({path.name for path in full_directory.glob('TEST-*.xml')} == expected_xml,
                '首修完整XML集合与当前类集合不相同', full_directory)
    first_totals = dict.fromkeys(keys, 0)
    for name in class_names:
        original_path = full_directory / ('TEST-' + name + '.xml')
        xml = ET.parse(original_path).getroot()
        values = {key: int(xml.attrib[key]) for key in keys}
        assert_fact(xml.get('name') == name and values['tests'] == suites[name]['tests']
                    and values['failures'] == (1 if name == affected_class else 0)
                    and values['errors'] == values['skipped'] == 0,
                    '首修完整结果不能丢失单项夹具失败或改变其他通过项', original_path)
        for key in keys:
            first_totals[key] += values[key]

    def source_csv(value, hash_value):
        path = root_path(value)
        assert_fact(digest(path) == hash_value, '归并源码CSV摘要不符', path)
        with path.open(encoding='utf-8-sig', newline='') as stream:
            reader = csv.DictReader(stream)
            if reader.fieldnames != ['Algorithm', 'Hash', 'Path']:
                raise ValueError('未审核的源码CSV字段形状：' + str(reader.fieldnames))
            rows = list(reader)
        paths = [root_path(row['Path']) for row in rows]
        assert_fact(len(paths) == len(set(paths)) and all(row['Algorithm'] == 'SHA256' for row in rows),
                    '源码CSV含重复路径或非SHA256项', path)
        return path, {root_path(row['Path']): row['Hash'].lower() for row in rows}

    before_csv, before = source_csv(manifest['firstFullRunSourceCSV'], manifest['firstFullRunSourceCSVSHA256'])
    current_csv, current_sources = source_csv(manifest['currentSourceCSV'], manifest['currentSourceCSVSHA256'])
    module = ROOT / 'ruoyi-backend/ruoyi-paismart'
    expected_sources = {path.resolve() for path in (module / 'src').rglob('*') if path.is_file()}
    expected_sources.update((ROOT / name).resolve() for name in ['ruoyi-backend/pom.xml',
        'ruoyi-backend/ruoyi-paismart/pom.xml', 'ruoyi-backend/ruoyi-paismart/scripts/prepare-legacy-migration.py',
        'ruoyi-backend/ruoyi-paismart/scripts/prepare-parser-worker.ps1',
        'ruoyi-backend/ruoyi-paismart/scripts/tests/test_prepare_legacy_migration.py'])
    assert_fact(set(before) == set(current_sources) == expected_sources,
                '前后源码清单范围必须一致并覆盖当前模块src、两个POM及三个交付脚本', current_csv)
    changed = sorted(relative(path) for path in before.keys() & current_sources.keys() if before[path] != current_sources[path])
    assert_fact(changed == [affected_source] == manifest['changedSinceFirstFullRun'] == composition['changedSinceFirstFullRun']
                and manifest['sameProductionAndOtherTestSources'] is True and composition['sameProductionAndOtherTestSources'] is True,
                '归并仅可沿用生产和其余源码未变、仅生命周期测试夹具改变的结果', manifest_path)
    source_rows = []
    for path, expected in current_sources.items():
        actual = digest(path)
        same = actual == expected
        assert_fact(same, '归并依据的当前源码hash与磁盘不符', path)
        source_rows.append({'path': relative(path), 'sha256': actual, 'matchesCSV': same,
                            'changedSinceFirstFullRun': relative(path) in changed})
    logs = []
    for key, expected in [('firstFullRunLog', first_totals), ('targetedLog',
                           {'tests': totals['targetedTests'], 'failures': 0, 'errors': 0, 'skipped': 0})]:
        path = root_path(manifest[key])
        assert_fact(path == root_path(composition[key]), '归并两批日志路径与后端JSON不一致', path)
        content = re.sub(r'\x1b\[[0-9;]*m', '', text(path))
        matches = re.findall(r'Tests run:\s*(\d+), Failures:\s*(\d+), Errors:\s*(\d+), Skipped:\s*(\d+)', content)
        actual = dict(zip(keys, map(int, matches[-1]))) if matches else None
        assert_fact(actual == expected, '归并实际日志汇总与对应原XML不符', path)
        marker = 'BUILD FAILURE' if key == 'firstFullRunLog' else 'BUILD SUCCESS'
        assert_fact(marker in content, '归并日志缺对应真实构建结果', path)
        if key == 'targetedLog':
            assert_fact(digest(path) == manifest['targetedLogSHA256']
                        and path == root_path(backend['currentLog'], backend_path.parent),
                        '当前定向日志摘要/当前日志入口不符', path)
        logs.append({'kind': key, 'path': relative(path), 'sha256': digest(path), 'actualTotals': actual,
                     'matchesOriginalXML': actual == expected, 'buildResult': marker})
    statements = [('后端scope', backend['scope'], backend_path),
                  ('manifest scope', manifest['scope'], manifest_path),
                  ('验收后端技术基线', next(row['fact'] for row in record['technicalBaseline'] if row['name'] == '后端本地测试'), CURRENT[5]),
                  ('验收源码准备', record['readiness'][0]['fact'], CURRENT[5])]
    for name, statement, path in statements:
        assert_fact('沿用' in statement or '基线' in statement, name + '未注明沿用依据', path)
        assert_fact('定向复验' in statement and str(totals['retainedFullRunTests']) + '项' in statement
                    and str(totals['targetedTests']) + '项' in statement,
                    name + '未准确区分沿用数量与实际定向复验', path)
    return {'executionKind': backend['executionKind'], 'compositionDeclared': True,
            'manifest': relative(manifest_path), 'manifestSHA256': digest(manifest_path),
            'computedComposition': totals, 'firstFullRunOriginalXMLTotals': first_totals,
            'originalXMLBytesPreserved': all(row['sameBytes'] for row in origin_rows), 'reports': origin_rows,
            'sourceRange': '模块src全部文件、共享/模块POM、三个迁移/Worker交付脚本；不推断未列共享依赖源码',
            'sourceFileCount': len(current_sources), 'changedSinceFirstFullRun': changed,
            'firstFullRunSourceCSV': {'path': relative(before_csv), 'sha256': digest(before_csv)},
            'currentSourceCSV': {'path': relative(current_csv), 'sha256': digest(current_csv)},
            'currentSourceHashes': source_rows, 'logs': logs}


def technical_numbers(data, backend_path, frontend_path, python_path):
    backend, frontend, python = load(backend_path), load(frontend_path), load(python_path)
    keys = ('tests', 'failures', 'errors', 'skipped')
    computed = {key: sum(int(suite[key]) for suite in backend['suites']) for key in keys}
    xml_totals = dict.fromkeys(keys, 0)
    for suite in backend['suites']:
        report = root_path(suite['report'])
        xml = ET.parse(report).getroot()
        values = {key: int(xml.attrib[key]) for key in keys}
        assert_fact(all(int(suite[key]) == values[key] for key in keys), 'suite JSON与实际XML计数不符', report)
        for key in keys:
            xml_totals[key] += values[key]
    assert_fact(computed == backend['totals'] == xml_totals, '后端总数、suite总数和XML总数不一致', backend_path)
    assert_fact(all(computed[key] == 0 for key in keys[1:]), '当前后端存在失败、错误或跳过', backend_path)
    assert_fact(backend.get('businessAcceptanceExecuted') is False, '后端检查不能记业务已执行', backend_path)
    unit = frontend['unit']
    front_log = text(frontend_path.parent / unit['log'])
    plain_log = re.sub(r'\x1b\[[0-9;]*m', '', front_log)
    matched = re.search(r'Tests\s+(\d+)\s+passed', plain_log)
    assert_fact(matched is not None and int(matched.group(1)) == unit['passed'], '前端JSON项数与实际log不符', frontend_path)
    front_files = re.search(r'Test Files\s+(\d+)\s+passed', plain_log)
    assert_fact(front_files is not None and int(front_files.group(1)) == unit['files'], '前端JSON文件数与实际log不符', frontend_path)
    assert_fact(unit['exitCode'] == unit['failed'] == frontend['typecheck']['exitCode'] == frontend['productionBuild']['exitCode'] == 0,
                '当前前端技术检查不全为成功', frontend_path)
    assert_fact(frontend.get('businessAcceptanceExecuted') is False and frontend.get('businessAcceptanceCountAdded') == 0,
                '前端Mock检查不能增加业务验收', frontend_path)
    source_hashes = []
    for source, expected in frontend['sourceHashes'].items():
        path = root_path(source)
        actual = digest(path)
        same = actual == expected
        assert_fact(same, '前端sourceHashes不是当前源码hash', path)
        source_hashes.append({'path': relative(path), 'expected': expected, 'actual': actual, 'matches': same})
    after = python['after']
    py_log = text(python_path.parent / after['log'])
    py_count = re.search(r'Ran (\d+) tests? in ', py_log)
    assert_fact(py_count is not None and int(py_count.group(1)) == after['tests'] and re.search(r'^OK\s*$', py_log, re.M) is not None,
                'Python技术JSON与修复后log不符', python_path)
    assert_fact(all(after[key] == 0 for key in ('failures', 'errors', 'skipped', 'exitCode')), 'Python终检存在失败/错误/跳过', python_path)
    for row in python['changedFiles']:
        if row['path'].endswith('.py'):
            assert_fact(digest(root_path(row['path'])) == row['sha256'], 'Python技术记录源码hash不符', root_path(row['path']))
    python_document_snapshots = []
    for row in python['changedFiles']:
        if row['path'].endswith('README.md'):
            current_hash = digest(root_path(row['path']))
            python_document_snapshots.append({'path': relative(root_path(row['path'])),
                                               'snapshotRecordedAt': python.get('date'),
                                               'snapshotSHA256': row['sha256'], 'currentSHA256': current_hash,
                                               'sameAsSnapshot': current_hash == row['sha256'],
                                               'hashScope': '原Python技术结果中的README为当时文档快照；当前文档hash另列，原结果不覆盖',
                                               'laterChangeBasis': 'root2026-10-03最终执行通知确认后续解析说明等仅文档修改；两个Py源码仍按原技术结果严格验hash'})
    assert_fact(python.get('realMySQLExecuted') is False and python.get('businessAcceptanceExecuted') is False,
                'Python检查不得冒称真实MySQL或业务已执行', python_path)
    backend_count, frontend_count, python_count = computed['tests'], unit['passed'], after['tests']
    facts = {row['name']: row['fact'] for row in data['technicalBaseline']}
    links = {row['name']: local_reference(CURRENT[5], row['href']) for row in data['technicalBaseline']}
    python_targets = {python_path.resolve(), (python_path.parent / after['log']).resolve()}
    python_entries = [row for row in data['technicalBaseline'] if links[row['name']] is not None
                      and links[row['name']][0] in python_targets]
    if len(python_entries) != 1:
        raise ValueError('按当前Python技术JSON/log的href必须唯一定位技术条目；实际' + str(len(python_entries)) + '项')
    assert_fact(links['后端本地测试'] is not None and links['后端本地测试'][0] == backend_path.resolve(),
                '验收当前后端数字引用了历史或其他JSON', CURRENT[5])
    assert_fact(links['前端技术记录'] is not None and links['前端技术记录'][0] == CURRENT[6].resolve(),
                '验收当前前端数字引用了历史或其他报告', CURRENT[5])
    back_fact = facts['后端本地测试']
    counts = re.search(r'(\d+)类(\d+)项', back_fact)
    assert_fact(counts is not None and (int(counts.group(1)), int(counts.group(2))) == (len(backend['suites']), backend_count),
                '验收技术基线后端数字不是当前JSON数字', CURRENT[5])
    assert_fact(re.search(r'(?<!\d)' + str(frontend_count) + r'项', facts['前端技术记录']) is not None,
                '验收技术基线前端数字不是当前JSON数字', CURRENT[5])
    assert_fact(re.search(r'(?<!\d)' + str(python_count) + r'项', python_entries[0]['fact']) is not None,
                '验收技术基线Python数字不是当前JSON数字', CURRENT[5])
    assert_fact(str(backend_count) + '项' in data['readiness'][0]['fact'], '源码实施前提仍引用旧后端总数', CURRENT[5])
    design = text(CURRENT[0])
    current_lines = []
    for number, line in enumerate(design.splitlines(), 1):
        if '| 9. 定向技术检查' in line:
            assert_fact(re.search(r'(?<!\d)' + str(backend_count) + r'项', line) is not None
                        and re.search(r'(?<!\d)' + str(frontend_count) + r'项', line) is not None,
                        '主设计当前检查汇总仍是旧数字', CURRENT[0], number)
            current_lines.append(number)
        if '| 8. 系统作业' in line:
            assert_fact(re.search(r'(?<!\d)' + str(python_count) + r'项', line) is not None,
                        '主设计当前离线准备汇总仍是旧数字', CURRENT[0], number)
            current_lines.append(number)
        if re.search(r'\[[^\]]*\]\([^)]*' + re.escape(backend_path.name) + r'\)', line):
            targets = [local_reference(CURRENT[0], m.group(1)) for m in re.finditer(r'\[[^\]]*\]\(([^)]+)\)', line)]
            if any(target is not None and target[0] == backend_path.resolve() for target in targets):
                figures = re.findall(r'(\d+)类(\d+)项', line)
                if figures:
                    assert_fact(any(int(c) == len(backend['suites']) and int(t) == backend_count for c, t in figures),
                                '直接链接当前后端JSON的主设计叙述是旧数字', CURRENT[0], number)
                current_lines.append(number)
    frontend_html = ''.join(parse_html(text(CURRENT[6])).parts)
    assert_fact(str(frontend_count) + '项通过' in frontend_html, '前端技术HTML当前通过数与JSON不符', CURRENT[6])
    return {'backend': {'evidence': relative(backend_path), 'sha256': digest(backend_path), 'classes': len(backend['suites']),
                        'reported': backend['totals'], 'computed': computed, 'actualXML': xml_totals},
            'frontend': {'evidence': relative(frontend_path), 'sha256': digest(frontend_path), 'files': unit['files'],
                         'passed': frontend_count, 'failed': unit['failed'], 'sourceHashes': source_hashes},
            'python': {'evidence': relative(python_path), 'sha256': digest(python_path), 'tests': python_count, 'after': after,
                       'documentSnapshots': python_document_snapshots},
            'currentDesignLinesChecked': current_lines}


def check_parser(path, delivery, record):
    if path is None:
        raise ValueError('本轮新Worker19项实际CLI results.json必须通过--parser-results指定，不能沿用旧intelligence字节码检查')
    parser = load(path)
    assert_fact(parser['total'] == parser['passed'] == 19 and len(parser['results']) == 19,
                '本轮解析样例必须19项全通过', path)
    worker = Path(parser['workerBatch']).resolve()
    assert_fact(worker == root_path(delivery['WorkerJar']).parent, 'CLI实际Worker批次不是当前交付批次', path)
    original = load(APPENDIX / '2026-10-03-终版解析进程复验/062900/results.json')
    expected = {row['file']: (row['sha256'], row['expected']) for row in original['results']}
    assert_fact({r['file']: (r['sha256'], r['expected']) for r in parser['results']} == expected,
                '本轮CLI不是原19份输入/预期', path)
    for row in parser['results']:
        assert_fact(row.get('passed') is True and row.get('exitCode') == 0 and row.get('expectedBodyFound') is True,
                    '本轮CLI逐例结果未通过：' + row['file'], path)
    for artifact in parser['artifacts']:
        assert_fact(digest(root_path(artifact['file'])) == artifact['sha256'], '本轮CLI产物hash与当前磁盘不符', path)
    active_facts = '\n'.join(row['fact'] for row in record['technicalBaseline'])
    assert_fact('19样例为沿用，本轮未新启动' not in active_facts and '解析相关字节码/依赖未变化' not in active_facts,
                '当前验收技术说明仍把已变更的解析实现/本轮19样例写成旧字节码沿用', CURRENT[5])
    parser_links = [local_reference(CURRENT[5], row['href']) for row in record['technicalBaseline']]
    assert_fact(any(link is not None and link[0] == path.resolve() for link in parser_links),
                '当前验收技术说明未引用本轮实际19项CLI结果', CURRENT[5])
    return {'evidence': relative(path), 'sha256': digest(path), 'total': parser['total'], 'passed': parser['passed'],
            'workerBatch': relative(worker), 'newExecution': True, 'originalFixturesAndExpectationsPreserved': True}


def check_delivery(path):
    delivery = load(path)
    for filename, hash_key in [('ModuleJar', 'ModuleSHA256'), ('AdminJar', 'AdminSHA256'), ('WorkerJar', 'WorkerSHA256')]:
        assert_fact(digest(root_path(delivery[filename])) == delivery[hash_key], '当前交付文件hash不符：' + filename, path)
    assert_fact(delivery['ModuleSHA256'] == delivery['NestedModuleSHA256'] == delivery['WorkerSHA256'],
                '当前模块/admin内嵌/Worker主JAR不一致', path)
    flags = delivery['AllDefaultFlagsOff']
    expected_flags = {'module', 'object-store', 'object-write', 'object-probe', 'kafka', 'external-calls',
                      'parse-process', 'parse-ocr', 'aliyun-ocr', 'payment', 'operations-execute', 'legacy-source'}
    assert_fact(isinstance(flags, dict) and set(flags) == expected_flags and all(value is False for value in flags.values()),
                '当前交付12个默认开关key必须齐全且每个value is False', path)
    history_path = ROUND / '2026-10-03-终版交付产物核对.json'
    history = load(history_path)
    assert_fact(root_path(delivery['WorkerJar']) != root_path(history['WorkerJar']), '本轮交付与保留353历史Worker批次未区分', path)
    return delivery, {'current': {'evidence': relative(path), 'sha256': digest(path), 'workerJar': delivery['WorkerJar'],
                                 'moduleSHA256': delivery['ModuleSHA256'], 'defaultFlags': flags},
                      'retained353History': {'evidence': relative(history_path), 'sha256': digest(history_path), 'workerJar': history['WorkerJar']},
                      'evidencePathsDistinct': path.resolve() != history_path.resolve(),
                      'workerPathsDistinct': root_path(delivery['WorkerJar']) != root_path(history['WorkerJar'])}


def main():
    arguments = argparse.ArgumentParser(description=__doc__)
    arguments.add_argument('--write-current-report', action='store_true', help='资料冻结授权后才允许覆盖唯一当前核对JSON')
    arguments.add_argument('--parser-results', type=Path)
    arguments.add_argument('--backend-json', type=Path, default=APPENDIX / '2026-10-03-终版后端测试汇总.json')
    arguments.add_argument('--frontend-json', type=Path, default=APPENDIX / '2026-10-03-迁移补齐-前端技术复验.json')
    arguments.add_argument('--python-json', type=Path, default=ROUND / '迁移转换守卫-技术结果.json')
    arguments.add_argument('--delivery-json', type=Path, default=APPENDIX / '2026-10-03-终版交付产物核对.json')
    args = arguments.parse_args()
    if not args.write_current_report:
        arguments.error('当前仅准备工具；资料冻结后须显式--write-current-report，未执行核对或写入')
    report = {'checkedAt': datetime.now(timezone.utc).isoformat(), 'kind': '最终文档技术核对',
              'scope': '七份当前文档及本轮必要技术JSON、XML/log/hash；仅静态只读检查，唯一写出当前核对JSON；未启动浏览器/Maven/Worker/服务，未执行真实业务',
              'checker': {'path': relative(__file__), 'sha256': digest(__file__)}}
    prior_failure = ROUND / '最终文档核对-首次失败.json'
    if prior_failure.is_file():
        report['priorFailure'] = {'path': relative(prior_failure), 'sha256': digest(prior_failure),
                                  'problems': load(prior_failure)['problems'],
                                  'resolution': '仅修正已核实的数据形状：按href定位Python技术条目，逐键严格核对12个false开关；未改原资料或放宽判定'}
    linked = safe_check('当前文档本地链接/HTML锚点', lambda: check_links(CURRENT))
    if isinstance(linked, tuple):
        report['files'], report['links'] = linked
    else:
        report['links'] = linked
    record = load(CURRENT[5])
    report['requirements'] = safe_check('首五节原文', check_requirements)
    report['businessAcceptance'] = safe_check('31项及授权边界', lambda: check_business(record))
    report['generatedViews'] = safe_check('JSON/MD/HTML同源事实', check_generated_views)
    report['technicalNumbers'] = safe_check('当前技术数字/日志/XML/前端源码hash',
                                           lambda: technical_numbers(record, args.backend_json, args.frontend_json, args.python_json))
    report['backendExecutionProvenance'] = safe_check('可选当前后端归并执行来源/原XML字节/源码与日志',
                                                     lambda: check_execution_composition(args.backend_json, record))
    delivery_result = safe_check('当前及历史交付', lambda: check_delivery(args.delivery_json))
    if isinstance(delivery_result, tuple):
        delivery, report['currentVersusRetainedDeliverables'] = delivery_result
        report['parserCLI'] = safe_check('本轮实际19样例', lambda: check_parser(args.parser_results, delivery, record))
    else:
        report['currentVersusRetainedDeliverables'] = delivery_result
    technical = [args.backend_json, args.frontend_json, args.python_json, args.delivery_json]
    if args.parser_results:
        technical.append(args.parser_results)
    report['technicalJSONs'] = [{'path': relative(path), 'sha256': digest(path)} for path in technical if path.is_file()]
    report['problems'] = PROBLEMS
    report['conclusion'] = '通过' if not PROBLEMS else '未通过'
    report['limitations'] = ['静态检查不代替31项真实业务验收或用户接受。',
                              '未重新浏览器渲染；原报告呈现记录按原范围保留。',
                              '不访问外部URL，不复跑技术构建/测试，不连接数据库或服务。']
    OUTPUT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'output': str(OUTPUT), 'conclusion': report['conclusion'], 'problems': len(PROBLEMS)}, ensure_ascii=False))
    return bool(PROBLEMS)


if __name__ == '__main__':
    raise SystemExit(main())
