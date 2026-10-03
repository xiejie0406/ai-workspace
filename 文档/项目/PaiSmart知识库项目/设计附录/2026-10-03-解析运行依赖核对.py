"""只读本次Worker的Maven依赖清单/POM/JAR；保存版本、许可出处及文件哈希，不访问网络或业务服务。"""
from collections import defaultdict
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

workspace = next(p for p in Path(__file__).resolve().parents if (p / 'ruoyi-backend').is_dir())
worker = Path(sys.argv[1]).resolve()
allowed = (workspace / 'ruoyi-backend/ruoyi-paismart/target').resolve()
if not worker.is_relative_to(allowed) or not worker.is_dir():
    raise SystemExit('必须指定本模块target下已生成的Worker批次目录')
evidence = Path(__file__).resolve().parent
repository = Path.home() / '.m2/repository'
namespace = {'m': 'http://maven.apache.org/POM/4.0.0'}
pattern = re.compile(r'^\s*([\w.\-]+):([\w.\-]+):jar:(?:([\w.\-]+):)?([^:\s]+):(compile|runtime)\s*(?:--.*)?$')

def text(node, field):
    value = node.find(field)
    return '' if value is None or value.text is None else value.text.strip()

def pom_path(group, artifact, version):
    return repository / group.replace('.', '/') / artifact / version / f'{artifact}-{version}.pom'

def licenses(path, seen=None):
    seen = set() if seen is None else seen
    if path in seen or len(seen) >= 20 or not path.is_file():
        return [], None
    seen.add(path)
    document = ET.parse(path).getroot()
    # 发布POM允许有或没有Maven XML namespace，均按同一声明读取。
    for element in document.iter():
        element.tag = element.tag.rsplit('}', 1)[-1]
    declarations = [{'name': text(item, 'name'), 'url': text(item, 'url'), 'distribution': text(item, 'distribution')}
                    for item in document.findall('licenses/license')]
    if declarations:
        return declarations, str(path)
    parent = document.find('parent')
    if parent is None:
        return [], None
    fields = [text(parent, key) for key in ('groupId', 'artifactId', 'version')]
    if any(not value or '${' in value for value in fields):
        return [], None
    return licenses(pom_path(*fields), seen)

rows = []
unmatched = []
versions = defaultdict(set)
for line in (worker / '依赖列表.txt').read_text(encoding='utf-8-sig').splitlines():
    # Maven的module注解可能包含ANSI颜色；不把控制序列当作依赖坐标或缺失产物。
    line = re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]', '', line)
    match = pattern.fullmatch(line)
    if not match:
        if ':jar:' in line:
            unmatched.append(line.strip())
        continue
    group, artifact, classifier, version, scope = match.groups()
    filename = f'{artifact}-{version}' + (f'-{classifier}' if classifier else '') + '.jar'
    jar = worker / 'lib' / filename
    pom = pom_path(group, artifact, version)
    declared, declaration_source = licenses(pom)
    embedded = []
    if jar.is_file():
        with zipfile.ZipFile(jar) as archive:
            embedded = [name for name in archive.namelist()
                        if re.search(r'(?:^|/)(?:LICENSE|NOTICE|COPYING)(?:[.\-]|$)', name, re.I)]
    rows.append({'groupId': group, 'artifactId': artifact, 'version': version, 'classifier': classifier,
                 'scope': scope, 'file': filename, 'exists': jar.is_file(),
                 'sha256': hashlib.sha256(jar.read_bytes()).hexdigest() if jar.is_file() else None,
                 'licenseDeclarations': declared, 'licensePom': declaration_source, 'embeddedLicenseFiles': embedded,
                 'sourceLicenseEvidence': [{'file': str(workspace / 'ruoyi-backend/LICENSE'), 'sha256': hashlib.sha256((workspace / 'ruoyi-backend/LICENSE').read_bytes()).hexdigest(), 'scope': '若依平台源码保留的MIT原文；发布POM本身未声明许可'}] if group == 'com.ruoyi' and not declared else [],
                 'verification': '本地发布POM声明及JAR文件核对；不代表法律结论或完整漏洞扫描'})
    versions[(group, artifact, classifier)].add(version)
listed = {item['file'] for item in rows}
extra = [p.name for p in (worker / 'lib').glob('*.jar') if p.name not in listed]
main = list(worker.glob('ruoyi-paismart-*.jar'))
conflicts = [{'groupId': group, 'artifactId': artifact, 'classifier': classifier, 'versions': sorted(values)}
             for (group, artifact, classifier), values in versions.items() if len(values) != 1]
result = {'time': datetime.now().isoformat(), 'workerBatch': str(worker), 'dependencies': rows,
          'mainJar': [{'file': p.name, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()} for p in main],
          'missingArtifacts': [r['file'] for r in rows if not r['exists']], 'extraArtifacts': extra,
          'unmatchedDependencyLines': unmatched, 'versionConflicts': conflicts,
          'missingLicenseDeclarations': [f"{r['groupId']}:{r['artifactId']}:{r['version']}" for r in rows if not r['licenseDeclarations']]}
output = evidence / ('2026-10-03-解析运行依赖-' + worker.name + '.json')
output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({k: v for k, v in result.items() if k != 'dependencies'}, ensure_ascii=False, indent=2))
print('dependencies=' + str(len(rows)) + ', evidence=' + str(output))
raise SystemExit(bool(not rows or len(main) != 1 or unmatched or extra or conflicts or result['missingArtifacts']))
