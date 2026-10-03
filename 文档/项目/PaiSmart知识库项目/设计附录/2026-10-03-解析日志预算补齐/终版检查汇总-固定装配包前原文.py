"""只读保存终版测试、源码与交付包一致性；不启动业务或修改配置。"""
from datetime import datetime
import csv
import hashlib
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
import zipfile
import yaml

ROOT = Path(__file__).resolve().parents[4]
EVIDENCE = Path(__file__).parent
MODULE = ROOT / 'ruoyi-backend/ruoyi-paismart'
WORKER = Path(sys.argv[1]).resolve()
REPORTS = Path(sys.argv[2]).resolve() if len(sys.argv) > 2 else EVIDENCE / '2026-10-03-BC对齐后通过报告'
SOURCE_HASHES = Path(sys.argv[3]).resolve() if len(sys.argv) > 3 else EVIDENCE / '2026-10-03-BC对齐后源码哈希.csv'
CURRENT_LOG = sys.argv[4] if len(sys.argv) > 4 else '2026-10-03-完整核对-BC对齐后全模块测试.log'
if not WORKER.is_relative_to((MODULE / 'target').resolve()) or not WORKER.is_dir():
    raise SystemExit('必须指定本模块target下本批Worker目录')
if not REPORTS.is_relative_to(EVIDENCE.resolve()) or not SOURCE_HASHES.is_relative_to(EVIDENCE.resolve()):
    raise SystemExit('报告和源码基线必须位于本项目既有设计附录内')

def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

suites = []
for path in sorted(REPORTS.glob('TEST-*.xml')):
    doc = ET.parse(path).getroot()
    suites.append({'class': doc.get('name'), **{key: int(doc.get(key)) for key in ('tests', 'failures', 'errors', 'skipped')},
                   'seconds': float(doc.get('time')), 'report': str(path.relative_to(ROOT)),
                   'evidenceSource': '本次完整模块实际执行；各原始XML与日志保留'})
totals = {key: sum(row[key] for row in suites) for key in ('tests', 'failures', 'errors', 'skipped')}
expected_classes = set()
for java in (MODULE / 'src/test/java').rglob('*Test.java'):
    package = re.search(r'^package\s+([\w.]+);', java.read_text(encoding='utf-8'), re.MULTILINE)
    if not package:
        raise SystemExit('测试源码缺少可核对package：' + str(java))
    expected_classes.add(package.group(1) + '.' + java.stem)
if {row['class'] for row in suites} != expected_classes or len(suites) != len(expected_classes):
    raise SystemExit('本次XML未完整覆盖当前模块测试类，不能输出终版结论')
if totals['tests'] < 1 or any(totals[key] for key in ['failures', 'errors', 'skipped']):
    raise SystemExit('本次完整测试存在失败、错误或跳过，不能输出终版结论')
test_result = {'time': datetime.now().isoformat(), 'scope': f"{len(suites)}类{totals['tests']}项本地技术检查；按本次完整实际XML统计",
               'businessAcceptanceExecuted': False, 'suites': suites, 'totals': totals,
               'priorEvidence': ['2026-10-03-终版全模块原报告', '2026-10-03-BC对齐后通过报告', '2026-10-03-完整核对-IWA安全夹具复验.log'], 'currentLog': CURRENT_LOG}

with SOURCE_HASHES.open(encoding='utf-8-sig', newline='') as stream:
    before = list(csv.DictReader(stream))
current_sources = {path.resolve() for folder in ['src', 'scripts'] for path in (MODULE / folder).rglob('*')
                   if path.is_file() and '__pycache__' not in path.parts}
current_sources.update([MODULE / 'pom.xml', ROOT / 'ruoyi-backend/pom.xml'])
if {Path(row['Path']).resolve() for row in before} != current_sources:
    raise SystemExit('测试源码清单发生新增或删除，不能输出终版结论')
changed = [row['Path'] for row in before if sha(Path(row['Path'])).upper() != row['Hash'].upper()]
if changed:
    raise SystemExit('通过基线影响判断需复查，源码变化：' + repr(changed))
with (EVIDENCE / '2026-10-03-Admin装配兼容源码哈希.csv').open(encoding='utf-8-sig', newline='') as stream:
    admin_sources = list(csv.DictReader(stream))
admin_changed = [row['Path'] for row in admin_sources if sha(Path(row['Path'])).upper() != row['Hash'].upper()]
if admin_changed:
    raise SystemExit('共享装配兼容测试后源码变化：' + repr(admin_changed))
cos_report = EVIDENCE / '2026-10-03-COS共享装配兼容通过报告/TEST-com.qcloud.cos.internal.crypto.CosRuntimeCompatibilityTest.xml'
cos_suite = ET.parse(cos_report).getroot()
cos_result = {key: int(cos_suite.get(key)) for key in ('tests', 'failures', 'errors', 'skipped')}
cos_result['report'] = str(cos_report.relative_to(ROOT))

module_jar = MODULE / 'target/ruoyi-paismart-3.9.2.jar'
worker_jar = WORKER / module_jar.name
admin_jar = ROOT / 'ruoyi-backend/ruoyi-admin/target/ruoyi-admin.jar'
nested = 'BOOT-INF/lib/' + module_jar.name
with zipfile.ZipFile(admin_jar) as archive:
    nested_sha = hashlib.sha256(archive.read(nested)).hexdigest()
    application = archive.read('BOOT-INF/classes/application.yml').decode('utf-8')
    admin_bc = sorted(name for name in archive.namelist() if name.startswith('BOOT-INF/lib/bc') and name.endswith('.jar'))
expected_bc = sorted('BOOT-INF/lib/' + family + '-jdk18on-1.85.jar' for family in ['bcprov', 'bcutil', 'bcpkix', 'bcjmail'])
with zipfile.ZipFile(module_jar) as archive:
    config = yaml.safe_load(archive.read('application-paismart.yml'))['paismart']
    retained_notices = all(name in archive.namelist() for name in ['META-INF/LICENSE-PaiSmart', 'META-INF/NOTICE-PaiSmart.txt'])
    evaluation_resources = all(name in archive.namelist() for name in ['eval/sample-eval.jsonl', 'eval/NOTICE.txt', 'db/paismart/V005__evaluation_datasets.sql'])
flags = {
    'module': config['enabled'], 'object-store': config['object-store']['enabled'],
    'object-write': config['object-store']['writes-enabled'], 'object-probe': config['object-store']['probe-enabled'],
    'kafka': config['jobs']['kafka-enabled'], 'external-calls': config['intelligence']['external-calls-enabled'],
    'parse-process': config['intelligence']['parse']['process-enabled'], 'parse-ocr': config['intelligence']['parse']['ocr-enabled'],
    'aliyun-ocr': config['intelligence']['ocr']['aliyun-enabled'], 'payment': config['payment']['enabled'],
    'operations-execute': config['operations']['execute-enabled'], 'legacy-source': config['operations']['legacy-source']['enabled']}
delivery = {'time': datetime.now().isoformat(), 'AdminJar': str(admin_jar.relative_to(ROOT)), 'AdminSHA256': sha(admin_jar),
            'ModuleJar': str(module_jar.relative_to(ROOT)), 'ModuleSHA256': sha(module_jar), 'NestedEntry': nested,
            'NestedModuleSHA256': nested_sha, 'NestedModuleMatches': nested_sha == sha(module_jar),
            'WorkerJar': str(worker_jar.relative_to(ROOT)), 'WorkerSHA256': sha(worker_jar), 'WorkerModuleMatches': sha(worker_jar) == sha(module_jar),
            'WorkerDependencyJars': len(list((WORKER / 'lib').glob('*.jar'))), 'AllDefaultFlagsOff': flags,
            'OptionalConfigImportPresent': 'optional:classpath:application-paismart.yml' in application,
            'BouncyCastleArtifacts': admin_bc, 'BouncyCastleFamilyMatches': admin_bc == expected_bc,
            'SharedCosCompatibility': cos_result, 'SinceCosTestChangedFiles': admin_changed,
            'RetainedSourceNotices': retained_notices, 'EvaluationDatasetResourcesPresent': evaluation_resources, 'SinceFullTestsChangedFiles': changed,
            'Scope': '静态产物一致性；未启动admin业务、未写配置/真实库、未发布；Worker样例结果另记'}
print(json.dumps({'classes': len(suites), 'tests': totals, 'delivery': delivery}, ensure_ascii=False, indent=2))
failed = bool(not delivery['NestedModuleMatches'] or not delivery['WorkerModuleMatches']
                      or any(value is not False for value in flags.values()) or not retained_notices
                      or not evaluation_resources
                      or not delivery['OptionalConfigImportPresent'] or not delivery['BouncyCastleFamilyMatches']
                      or any(cos_result[key] != expected for key, expected in [('tests', 1), ('failures', 0), ('errors', 0), ('skipped', 0)]))
if not failed:
    with (EVIDENCE / '2026-10-03-终版交付源码哈希.csv').open('w', encoding='utf-8-sig', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=['Path', 'SHA256'])
        writer.writeheader()
        for row in before:
            writer.writerow({'Path': row['Path'], 'SHA256': sha(Path(row['Path']))})
    (EVIDENCE / '2026-10-03-终版后端测试汇总.json').write_text(json.dumps(test_result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (EVIDENCE / '2026-10-03-终版交付产物核对.json').write_text(json.dumps(delivery, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
raise SystemExit(failed)
