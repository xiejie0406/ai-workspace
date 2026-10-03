"""本轮只读核对；只输出元数据，不读取账号、商品或密钥正文。"""
from pathlib import Path
from datetime import datetime
from zoneinfo import ZoneInfo
from urllib.parse import urlparse
import hashlib
import json
import os
import re
import socket
import subprocess
import winreg

ROOT = Path('D:/2025Ai/26-05-23/java-workspace')
OUT = Path(__file__).resolve().parent
INSTALL = Path('C:/Users/admin/AppData/Local/Programs/Aden Local Test')
report = {'checkedAt': datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(), 'readOnly': True}
app = INSTALL / 'resources/app'
config = json.loads((app / 'local-test.json').read_text(encoding='utf-8-sig'))
package = json.loads((app / 'package.json').read_text(encoding='utf-8-sig'))
report['installation'] = {'path': str(INSTALL / 'Aden Local Test.exe'), 'channel': config.get('channel'), 'origin': config.get('apiBaseUrl'), 'version': package.get('version')}
report['listeners'] = {}
for port in (18093, 16393):
    with socket.socket() as probe:
        probe.settimeout(2)
        report['listeners'][str(port)] = probe.connect_ex(('127.0.0.1', port)) == 0

previous = json.loads((OUT.parent / '2026-10-01-统一设计实现/安装文件哈希.json').read_text(encoding='utf-8-sig'))
files = []
for row in previous['files']:
    component = row['component']
    relative = row['file']
    base = app / 'out' if component == 'desktop' else INSTALL / 'resources/collector/extension' if component == 'extension' else INSTALL / 'resources/collector'
    target = base / relative
    if not target.is_file():
        candidates = list((INSTALL / 'resources/collector').glob(relative))
        target = candidates[0] if len(candidates) == 1 else target
    digest = hashlib.sha256(target.read_bytes()).hexdigest() if target.is_file() else None
    files.append({'component': component, 'file': relative, 'exists': target.is_file(), 'sha256': digest, 'matchesPrevious': digest == row['sha256'].lower()})
report['installedBaseline'] = {'previousCheckedAt': previous['checkedAt'], 'files': files, 'allMatch': all(x['matchesPrevious'] for x in files)}
workspace_out = ROOT / 'frontend/desktop/aden-desktop/out'
report['workspaceBundleMatch'] = [{'file': str(p.relative_to(workspace_out)), 'matches': (app / 'out' / p.relative_to(workspace_out)).is_file() and hashlib.sha256(p.read_bytes()).digest() == hashlib.sha256((app / 'out' / p.relative_to(workspace_out)).read_bytes()).digest()} for p in workspace_out.rglob('*') if p.is_file()]
manifest = json.loads((INSTALL / 'resources/collector/extension/manifest.json').read_text(encoding='utf-8-sig'))
report['extension'] = {'name': manifest.get('name'), 'version': manifest.get('version'), 'manifestVersion': manifest.get('manifest_version'), 'permissions': manifest.get('permissions')}
registration = []
for registry_root in (winreg.HKEY_CURRENT_USER, winreg.HKEY_LOCAL_MACHINE):
    for registry_view in (winreg.KEY_WOW64_64KEY, winreg.KEY_WOW64_32KEY):
        for host_name in ('com.aden.collector', 'com.aden.collector.dev', 'com.aden.collector.localtest'):
            try:
                with winreg.OpenKey(registry_root, 'Software\\Google\\Chrome\\NativeMessagingHosts\\' + host_name, 0, winreg.KEY_READ | registry_view) as key:
                    value = winreg.QueryValueEx(key, '')[0]
                    row = {'host': host_name, 'manifest': value}
                    if Path(value).is_file():
                        host = json.loads(Path(value).read_text(encoding='utf-8-sig'))
                        row['executable'] = host.get('path')
                    registration.append(row)
            except FileNotFoundError:
                pass
report['nativeHostRegistrations'] = registration
state = json.loads((ROOT / '.runtime/four-projects/aden/runner-restored-state.json').read_text(encoding='utf-8-sig'))
report['runner'] = {key: state.get(key) for key in ('origin', 'pid', 'startedAt', 'lastHeartbeatAt')}
report['runner']['newHeartbeatConfirmed'] = False

settings = {}
for line in (ROOT / '.env').read_text(encoding='utf-8-sig').splitlines():
    match = re.fullmatch(r'([A-Z][A-Z0-9_]+)=(.*)', line)
    if match:
        settings[match[1]] = match[2].strip().strip('\"').strip("'")
url = urlparse(settings['RUOYI_DB_URL'].removeprefix('jdbc:'))
assert url.hostname in ('localhost', '127.0.0.1') and url.port == 3306 and url.path == '/ry-vue', '原配置目标不符；停止数据库核对'
database = {'host': url.hostname, 'port': url.port, 'schema': url.path[1:], 'readOnlyMetadata': True}
sql = "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='ry-vue' AND LEFT(table_name,5)='aden_'; SELECT secret_alias,status FROM `ry-vue`.sys_managed_secret WHERE secret_alias='platform.aden.runner-pepper';"
try:
    result = subprocess.run(['C:/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe', '--protocol=TCP', '--host='+url.hostname, '--port=3306', '--user='+settings['RUOYI_DB_USERNAME'], '--batch', '--skip-column-names', '--execute='+sql], env=dict(os.environ, MYSQL_PWD=settings['RUOYI_DB_PASSWORD']), capture_output=True, text=True, timeout=20)
    database['exitCode'] = result.returncode
    if result.returncode == 0:
        rows = result.stdout.splitlines()
        database['adenTableCount'] = int(rows[0])
        database['runnerPepperMetadata'] = [{'purpose': row.split('\t')[0], 'status': row.split('\t')[1]} for row in rows[1:]]
    else:
        database['errorCodes'] = re.findall(r'ERROR \d+', result.stderr)
except (OSError, subprocess.TimeoutExpired) as exc:
    database['checkErrorType'] = type(exc).__name__
    database['currentSchemaConfirmed'] = False
report['originalDatabase'] = database
(OUT / '只读环境核对.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(json.dumps({'checkedAt': report['checkedAt'], 'listeners': report['listeners'], 'originalDatabase': database, 'installation': report['installation'], 'baselineFileCount': len(files), 'allBaselineFilesMatch': report['installedBaseline']['allMatch'], 'workspaceBundleMatch': all(x['matches'] for x in report['workspaceBundleMatch']), 'nativeHostRegistrations': registration, 'runner': report['runner']}, ensure_ascii=False, indent=2))
