import collections, datetime, hashlib, json, re, shutil, struct, subprocess, zipfile
from pathlib import Path

workspace = Path(r'D:\2025Ai\26-05-23\java-workspace')
base = workspace / 'ruoyi-backend/ruoyi-paicli'
evidence = Path(__file__).parent
java_home = Path(r'C:\Users\admin\AppData\Local\Programs\Eclipse Adoptium\jdk-21.0.10.7-hotspot')
sha = lambda data: hashlib.sha256(data).hexdigest()
file_sha = lambda path: sha(path.read_bytes())
save = lambda path, data: path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
stamp = datetime.datetime.now(datetime.timezone.utc).isoformat()
modules = ['paicli-core', 'paicli-runner', 'paicli-cli', 'paicli-eval']
rows = []
for module in modules:
    for path in (base / module).rglob('*'):
        if not path.is_file():
            continue
        relative = path.relative_to(base / module).as_posix()
        if relative == 'pom.xml' or relative.startswith('src/') or (module == 'paicli-eval' and relative.startswith('benchmarks/')) or (module == 'paicli-core' and relative in ['source-migration-manifest.json', 'migrate_reference.py']):
            rows.append({'path': path.relative_to(base).as_posix(), 'size': path.stat().st_size, 'sha256': file_sha(path)})
for directory in ['distribution', 'reference-tools']:
    for path in (base / directory).rglob('*'):
        if path.is_file() and path.name != 'package-manifest.json':
            rows.append({'path': path.relative_to(base).as_posix(), 'size': path.stat().st_size, 'sha256': file_sha(path)})
rows.append({'path': 'pom.xml', 'size': (base / 'pom.xml').stat().st_size, 'sha256': file_sha(base / 'pom.xml')})
rows.sort(key=lambda row: row['path'])
list_sha = sha(json.dumps(rows, ensure_ascii=False, separators=(',', ':')).encode('utf-8'))
manifest_path = evidence / 'native-full-source-manifest.json'
backup_manifest = evidence / 'native-full-source-manifest-before-clean.json'
if manifest_path.exists() and not backup_manifest.exists():
    shutil.copy2(manifest_path, backup_manifest)
save(manifest_path, {'schemaVersion': 1, 'sourceRoot': str(base), 'capturedAt': stamp,
    'scope': 'native source/tests/resources/POM + original Eval fixtures + static distribution/reference assets; target/API and generated package-manifest excluded',
    'fileCount': len(rows), 'listSha256': list_sha, 'buildState': 'final package completed from frozen live source; direct targets, not private snapshot', 'files': rows})

source_map = json.loads((base / 'paicli-core/source-migration-manifest.json').read_text(encoding='utf-8'))
source_errors = [row['source'] for row in source_map if not (workspace / row['target']).is_file() or file_sha(workspace / row['source']).lower() != row['sourceSha256'].lower()]
assets = json.loads((base / 'distribution/source-assets-manifest.json').read_text(encoding='utf-8'))
asset_errors = [row['source'] for row in assets['files'] if file_sha(Path(assets['sourceRoot']) / row['source']).lower() != row['sha256'].lower() or file_sha(base / row['target']).lower() != row['sha256'].lower()]
parser = lambda path: re.findall(r'\b[A-Z][A-Z_]+\b', re.search(r'enum CommandType\s*\{(.*?)\}', path.read_text(encoding='utf-8'), re.S).group(1))
source_commands = parser(workspace / '参考项目/paicli-main/src/main/java/com/paicli/cli/CliCommandParser.java')
target_commands = parser(base / 'paicli-cli/src/main/java/com/paicli/cli/CliCommandParser.java')

jar_specs = [('paicli-core','paicli-core-3.9.2.jar'), ('paicli-runner','paicli-runner-3.9.2.jar'), ('paicli-cli','paicli-cli-3.9.2.jar'), ('paicli-eval','paicli-eval-3.9.2.jar'), ('paicli-eval','paicli-eval-3.9.2-agentbench-runner.jar')]
jars = []
for module, name in jar_specs:
    path = base / module / 'target' / name
    with zipfile.ZipFile(path) as archive:
        own_classes = [item for item in archive.namelist() if item.endswith('.class') and item.startswith(('com/paicli/', 'com/example/'))]
        versions = collections.Counter(struct.unpack('>H', archive.read(item)[6:8])[0] for item in own_classes)
        class_diffs = []
        for item in own_classes:
            files = [base / dependency / 'target/classes' / item for dependency in modules]
            actual = next((file for file in files if file.is_file()), None)
            if actual is None or actual.read_bytes() != archive.read(item):
                class_diffs.append(item)
        jars.append({'path': str(path), 'sha256': file_sha(path), 'size': path.stat().st_size,
            'ownClassCount': len(own_classes), 'ownClassMajorVersions': dict(versions), 'ownClassDiffFromTarget': class_diffs,
            'mainManifest': archive.read('META-INF/MANIFEST.MF').decode('utf-8')})

smokes = []
probe_classes = evidence / 'distribution-probe-classes'
cli_jar = str(base / 'paicli-cli/target/paicli-cli-3.9.2.jar')
eval_jar = str(base / 'paicli-eval/target/paicli-eval-3.9.2.jar')
commands = [
    ('windows-cli-help', ['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-File',str(base / 'distribution/windows/paicli.ps1'),'--help']),
    ('windows-runner-help', ['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-File',str(base / 'distribution/windows/runner.ps1'),'--help']),
    ('windows-eval-help', ['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-File',str(base / 'distribution/windows/eval.ps1'),'--help']),
    ('cli-spi', [str(java_home / 'bin/java.exe'),'-cp',str(probe_classes)+';'+cli_jar,'NativeDistributionProbe','cli']),
    ('eval-classpath', [str(java_home / 'bin/java.exe'),'-cp',str(probe_classes)+';'+eval_jar,'NativeDistributionProbe','eval'])]
import os
child_env = dict(os.environ)
child_env['JAVA_HOME'] = str(java_home)
for name in list(child_env):
    if name.startswith('PAICLI_') or name in ['RUNNER_TOKEN','USER_TOKEN','RUNTIME_API_KEY']:
        child_env.pop(name, None)
for label, command in commands:
    result = subprocess.run(command, cwd=base, env=child_env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
    log = evidence / ('native-full-'+label+'.log')
    log.write_bytes(result.stdout)
    smokes.append({'name': label, 'exitCode': result.returncode, 'log': str(log), 'sha256': file_sha(log), 'command': command})

package_log = evidence / 'native-full-capability-final-package.log'
package_text = package_log.read_bytes().decode('utf-8', 'replace')
finished = re.search(r'Finished at:\s*([^\r\n]+)', package_text).group(1)
stat = package_log.stat()
build_started = getattr(stat, 'st_birthtime', stat.st_ctime)
mutated = [row['path'] for row in rows if (base / row['path']).stat().st_mtime > build_started]
diffs_after = [row['path'] for row in rows if file_sha(base / row['path']) != row['sha256']]
wrapper_rows = [{'path': str(path), 'sha256': file_sha(path), 'size': path.stat().st_size} for platform in ['windows','linux','macos'] for path in sorted((base / 'distribution' / platform).iterdir()) if path.is_file()]
report = {'schemaVersion': 2, 'checkedAt': stamp, 'technicalOnly': True, 'realBusinessExecuted': 0,
    'package': {'result': 'PASS' if 'BUILD SUCCESS' in package_text else 'FAIL', 'finishedAt': finished, 'log': str(package_log), 'sha256': file_sha(package_log), 'testsDuringPackage': 'SKIPPED; dedicated regression logs retained separately', 'originalJarsBackup': str(evidence / 'native-jar-backup-20261003T062508Z')},
    'sourceManifest': {'path': str(manifest_path), 'sha256': file_sha(manifest_path), 'fileCount': len(rows), 'listSha256': list_sha, 'changesSinceCaptured': diffs_after, 'sourceFilesModifiedAfterBuildStart': mutated},
    'sourceMigration': {'count': len(source_map), 'sourceModifiedOrTargetMissing': source_errors, 'targetAlgorithmSafetyChanges': 'intentional; target hash equality to original is not claimed'},
    'referenceAssets': {'count': len(assets['files']), 'mismatches': asset_errors, 'originalScriptsExecuted': False},
    'commands': {'parserEntryCountIncludingNoneAndUnknown': len(source_commands), 'entries': source_commands, 'sourceTargetEnumEqual': source_commands == target_commands, 'scope': 'original enum source entry preservation; command centre semantics need independent tests'},
    'tools': {'builtInCount': 17, 'proof': str(evidence / 'native-full-capability-real-restore-ledger.log'), 'internalSnapshotPreviewExposedToModel': False},
    'jars': jars, 'wrappers': wrapper_rows, 'smokes': smokes, 'runtime': {'compilerRelease': 17, 'ownClassMajorExpected': 61, 'executedJdk': str(java_home), 'actualJava17RuntimeExecuted': False},
    'limitations': ['真实Provider/MCP/微信和业务验收未执行', 'Linux/macOS包装未在对应系统运行', '正式Eval沿用真实未通过及Windows SecureDirectoryStream/工具链限制，不重复全量运行', '公开再分发许可证尚待确认', '真实用户快照归档恢复演练未执行']}
save(evidence / 'native-full-package-checks.json', report)
save(base / 'distribution/package-manifest.json', report)
print(json.dumps({'jarCount': len(jars), 'sourceFiles': len(rows), 'sourceDiffs': len(diffs_after), 'mutatedDuringBuild': mutated, 'sourceMappings': len(source_map), 'sourceMapErrors': source_errors, 'assets': len(assets['files']), 'assetErrors': asset_errors, 'commands': len(source_commands), 'smokes': [(row['name'], row['exitCode']) for row in smokes], 'classDiffs': [(Path(row['path']).name, len(row['ownClassDiffFromTarget']), row['ownClassMajorVersions']) for row in jars]}, ensure_ascii=False))
if source_errors or asset_errors or diffs_after or mutated or any(row['exitCode'] for row in smokes) or any(row['ownClassDiffFromTarget'] or set(row['ownClassMajorVersions']) != {61} for row in jars):
    raise SystemExit(1)
