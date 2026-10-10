"""只读核对源码和私有编译产物；不启动业务或执行测试。"""
from pathlib import Path
import csv
import hashlib
import io
import json
import zipfile

evidence = Path(__file__).resolve().parent
root = next(p for p in evidence.parents if (p / 'ruoyi-backend/pom.xml').is_file())
location = json.loads((evidence / '私有编译定位.json').read_text(encoding='utf-8-sig'))
before = json.loads((evidence / '实施前核查.json').read_text(encoding='utf-8-sig'))
stage = Path(location['stage'])
sha = lambda data: hashlib.sha256(data).hexdigest()
digest = lambda p: sha(p.read_bytes()) if p.is_file() else None
module = Path(location['backend']) / 'ruoyi-paismart/target/ruoyi-paismart-3.9.2.jar'
admin = Path(location['backend']) / 'ruoyi-admin/target/ruoyi-admin.jar'
with zipfile.ZipFile(admin) as package:
    embedded = package.read('BOOT-INF/lib/ruoyi-paismart-3.9.2.jar')
    picture = package.read('BOOT-INF/lib/ruoyi-aipicture-3.9.2.jar')
    slides = package.read('BOOT-INF/lib/ruoyi-aippt-3.9.2.jar')
with zipfile.ZipFile(io.BytesIO(picture)) as package:
    picture_resources = 'contracts/aipicture/v1/document.schema.json' in package.namelist()
with zipfile.ZipFile(io.BytesIO(slides)) as package:
    slides_resources = 'aippt/catalog-manifest.json' in package.namelist() and 'aippt/catalog/sample-deck.json' in package.namelist()
with zipfile.ZipFile(io.BytesIO(embedded)) as package:
    classes = sorted(n for n in package.namelist() if 'Sub2Api' in n and n.endswith('.class'))
    config = package.read('application-paismart.yml').decode('utf-8')
    gateway_claim = b'requireProbeAttempt' in package.read('com/ruoyi/paismart/application/intelligence/ModelGateway.class')
    provider_claim = b'attemptExpiresAt' in package.read('com/ruoyi/paismart/application/intelligence/ProviderService.class')
rows = list(csv.DictReader((evidence / '私有编译源码摘要.csv').open(encoding='utf-8-sig')))
relevant = [r for r in rows if r['source'].startswith(('ruoyi-backend/ruoyi-paismart/', 'frontend/admin-web/src/views/paismart/', 'frontend/admin-web/src/api/paismart/', 'frontend/admin-web/src/types/paismart/', 'contracts/paismart/'))]
changed = [r['source'] for r in relevant if digest(root / r['source']) != r['sha256'] or digest(stage / r['snapshot']) != r['sha256']]
frontend_index = Path(location['frontendOutput']) / 'index.html'
recovery_hint = any('六分钟内只回读状态' in p.read_text(encoding='utf-8') for p in frontend_index.parent.glob('static/js/*.js'))
shared = {name: {'before': value, 'after': digest(root / name), 'unchanged': value == digest(root / name)}
          for name, value in before['sharedArtifactsBefore'].items()}
result = {'scope': '离线源码与私有制品字节核对；未执行测试、服务或业务调用',
          'module': {'path': str(module), 'sha256': digest(module)},
          'admin': {'path': str(admin), 'sha256': digest(admin), 'embeddedModuleSHA256': sha(embedded)},
          'moduleEmbeddedMatch': digest(module) == sha(embedded), 'sub2ApiClasses': classes,
          'attemptGuardPackaged': gateway_claim and provider_claim,
          'sharedContractResourcesPackaged': picture_resources and slides_resources,
          'frontendRecoveryHintPackaged': recovery_hint,
          'sourceCount': len(relevant), 'sourceChangedSinceSnapshot': changed,
          'frontend': {'index': str(frontend_index), 'sha256': digest(frontend_index)},
          'testSourcesCopied': location['testSourcesCopied'], 'packagedConfig': config,
          'sharedArtifacts': shared}
(evidence / '私有制品核对.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({k: result[k] for k in ('moduleEmbeddedMatch', 'sub2ApiClasses', 'sourceCount', 'sourceChangedSinceSnapshot', 'frontend', 'testSourcesCopied')}, ensure_ascii=False, indent=2))
if not result['moduleEmbeddedMatch'] or not classes or changed or not frontend_index.is_file() or not all((gateway_claim, provider_claim, recovery_hint, picture_resources, slides_resources)):
    raise SystemExit('私有制品核对未满足一致性要求')
