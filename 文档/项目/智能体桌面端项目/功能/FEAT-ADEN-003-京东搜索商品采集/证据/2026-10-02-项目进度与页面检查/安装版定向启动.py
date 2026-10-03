"""只打开已安装应用、沿用已有profile，显式传递不含Electron脚本模式的环境。"""
import json
import os
from pathlib import Path
import subprocess
from datetime import datetime
from zoneinfo import ZoneInfo

root = Path('D:/2025Ai/26-05-23/java-workspace')
out = Path(__file__).resolve().parent
exe = Path('C:/Users/admin/AppData/Local/Programs/Aden Local Test/Aden Local Test.exe')
config = json.loads((exe.parent / 'resources/app/local-test.json').read_text(encoding='utf-8-sig'))
assert config['channel'] == 'local-test' and config['apiBaseUrl'] == 'http://127.0.0.1:18093'
start = json.loads((root / '.runtime/four-projects/aden/installed-start-result.json').read_text(encoding='utf-8-sig'))
profile = Path(start['desktopProfile'])
assert profile.is_dir()
environment = dict(os.environ)
removed = []
for key in ('ELECTRON_RUN_AS_NODE','ELECTRON_RENDERER_URL','ADEN_DESKTOP_SMOKE','ADEN_API_BASE_URL','NODE_OPTIONS'):
    if key in environment:
        removed.append(key)
    environment.pop(key, None)
with (out/'安装版定向启动.stdout.log').open('wb') as stdout, (out/'安装版定向启动.stderr.log').open('wb') as stderr:
    process = subprocess.Popen([str(exe), '--user-data-dir='+str(profile)], cwd=exe.parent, env=environment, stdout=stdout, stderr=stderr)
    try:
        exit_code = process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        exit_code = None
data = {'checkedAt': datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(), 'pid': process.pid, 'path': str(exe), 'profile': str(profile), 'removedEnvironmentNames': removed, 'exitCodeWithin5Seconds': exit_code}
(out/'安装版定向启动.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(data,ensure_ascii=False,indent=2))
