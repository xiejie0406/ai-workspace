"""只读核对本轮私有编译产物，不启动应用、Worker 或执行业务。"""
from pathlib import Path
import csv, hashlib, json, zipfile

root = Path(__file__).resolve().parents[5]
evidence = Path(__file__).resolve().parent
location = json.loads((evidence / '私有构建定位.json').read_text(encoding='utf-8-sig'))
stage = Path(location['stage'])
backend = Path(location['backend'])
sha = lambda data: hashlib.sha256(data).hexdigest()
digest = lambda path: sha(path.read_bytes())
module = backend / 'ruoyi-paismart/target/ruoyi-paismart-3.9.2.jar'
admin = backend / 'ruoyi-admin/target/ruoyi-admin.jar'
worker = backend / 'ruoyi-paismart/target/logic-worker/ruoyi-paismart-3.9.2.jar'
with zipfile.ZipFile(admin) as z:
    embedded = z.read('BOOT-INF/lib/ruoyi-paismart-3.9.2.jar')
    admin_bc = sorted(n for n in z.namelist() if n.startswith('BOOT-INF/lib/bc') and n.endswith('.jar'))
with zipfile.ZipFile(module) as z:
    names = set(z.namelist())
    config = z.read('application-paismart.yml').decode('utf-8')
    resources = sorted(n for n in names if n.startswith(('db/', 'eval/')) or n.endswith(('ParserWorker.class', 'NOTICE.txt', 'LICENSE.txt')))
    parser_classes = {n: sha(z.read(n)) for n in names if '/infrastructure/intelligence/' in n and any(t in n for t in ['ParserWorker', 'IsolatedDocumentParser', 'IworkBodyExtractor']) and n.endswith('.class')}
lib = worker.parent / 'lib'
dependencies = [{'file': p.name, 'sha256': digest(p)} for p in sorted(lib.glob('*.jar'))]
shared = {name: {'before': before, 'after': digest(root/name) if (root/name).is_file() else None} for name,before in location['sharedArtifactsBefore'].items()}
for value in shared.values(): value['unchanged'] = value['before'] == value['after']
source_rows = []
for p in sorted((root/'ruoyi-backend/ruoyi-paismart/src/main').rglob('*')):
    if p.is_file():
        rel = p.relative_to(root).as_posix()
        copy = backend / p.relative_to(root/'ruoyi-backend')
        source_rows.append({'Path': rel, 'SHA256': digest(p), 'BuildSHA256': digest(copy) if copy.is_file() else '', 'Match': copy.is_file() and digest(p)==digest(copy)})
for folder in ['frontend/admin-web/src/views/paismart', 'frontend/admin-web/src/api/paismart', 'contracts/paismart']:
    for p in sorted((root/folder).rglob('*')):
        if p.is_file(): source_rows.append({'Path': p.relative_to(root).as_posix(), 'SHA256': digest(p), 'BuildSHA256': '', 'Match': ''})
with (evidence/'当前源码摘要.csv').open('w',encoding='utf-8-sig',newline='') as f:
    writer=csv.DictWriter(f,fieldnames=['Path','SHA256','BuildSHA256','Match']);writer.writeheader();writer.writerows(source_rows)
old_manifest = root/'文档/项目/PaiSmart知识库项目/设计附录/2026-10-03-终版交付源码哈希.csv'
historical = {row['Path'].replace('\\','/'):row['SHA256'].lower() for row in csv.DictReader(old_manifest.open(encoding='utf-8-sig'))}
def previous(path):
    # 历史清单可能以完整路径或相对路径记载。
    for name,value in historical.items():
        if name.endswith(path): return value
    return None
historical_impact=[]
for row in source_rows:
    old=previous(row['Path'])
    if old: historical_impact.append({'path':row['Path'],'historicalSHA256':old,'currentSHA256':row['SHA256'],'unchanged':old==row['SHA256']})
result = {
    'scope':'编译、打包与字节摘要核对；无测试、业务操作或服务启动',
    'module': {'path':str(module),'sha256':digest(module)},
    'admin': {'path':str(admin),'sha256':digest(admin),'embeddedModuleSHA256':sha(embedded)},
    'worker': {'path':str(worker),'sha256':digest(worker),'dependencyCount':len(dependencies)},
    'moduleEmbeddedWorkerMatch':len({digest(module),sha(embedded),digest(worker)})==1,
    'backendMainSourcesMatchPrivateBuild':all(row['Match'] for row in source_rows if row['Path'].startswith('ruoyi-backend/')),
    'sharedArtifacts':shared,
    'adminBouncyCastleJars':admin_bc,
    'workerBouncyCastleJars':[x['file'] for x in dependencies if x['file'].startswith('bc')],
    'packagedResources':resources,
    'packagedDisabledConfig':config,
    'parserClassSHA256':parser_classes,
    'historicalSourceImpact':historical_impact,
    'checks':{'testSourcesCopied':False,'mavenTestsSkipped':True,'currentBusinessAcceptanceExecuted':0,'freshUnitComponentIntegrationParserMigrationTests':0},
    'frontend':{'output':str(stage/'frontend-dist'),'indexSHA256':digest(stage/'frontend-dist/index.html')},
    'dependencies':dependencies,
}
(evidence/'私有交付产物核对.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:result[k] for k in ['moduleEmbeddedWorkerMatch','backendMainSourcesMatchPrivateBuild','sharedArtifacts','worker','adminBouncyCastleJars','workerBouncyCastleJars']},ensure_ascii=False,indent=2))
