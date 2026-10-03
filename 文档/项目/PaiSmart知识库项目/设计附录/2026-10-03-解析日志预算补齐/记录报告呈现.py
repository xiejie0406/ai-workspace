"""记录已完成的静态报告呈现，不执行浏览器或真实业务。"""
import hashlib
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROUND = Path(__file__).resolve().parent
BUSINESS = ROUND.parent / '2026-10-03-真实业务验收准备'
record_path = BUSINESS / '验收记录.json'
record = json.loads(record_path.read_text(encoding='utf-8'))
previous = json.loads((ROUND / '日志补齐前-真实验收记录.json').read_text(encoding='utf-8'))
assert record['reportRendering']['status'] == '待本轮报告呈现检查'
assert len(record['businessCases']) == 31
assert all(c['result'] == '未执行' and not c['evidence'] for c in record['businessCases'])

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

screenshots = []
for name in ('报告呈现-日志预算首页.jpg', '报告呈现-日志预算解析清单.jpg'):
    path = BUSINESS / name
    assert path.read_bytes().startswith(b'\xff\xd8\xff')
    screenshots.append({'file': name, 'sha256': digest(path), 'saved': True,
                        'readBack': True, 'kind': '静态报告呈现'})

frontend = ROUND.parent / '2026-10-03-迁移补齐-前端技术复验.html'
retained = dict(previous['reportRendering']['frontendReport'])
assert digest(frontend) == retained['sha256']
retained.update({'executedThisRound': False,
                 'evidenceKind': '未变报告沿用前轮呈现记录，本轮未重新打开',
                 'evidenceSource': '../2026-10-03-解析日志预算补齐/日志补齐前-真实验收记录.json'})
record['reportRendering'] = {
    'executed': True, 'businessEvidence': False,
    'verifiedAt': datetime.now(timezone(timedelta(hours=8))).isoformat(),
    'channel': 'Codex内置浏览器', 'browserId': '2',
    'scope': '本机127.0.0.1:18151静态验收准备报告，未进入业务页面或调用业务后台',
    'reportSha256': digest(BUSINESS / 'verification-report.html'),
    'caseCards': 31, 'visibleCardsAfterClearSearch': 31,
    'counts': {'通过': 0, '未通过': 0, '受阻': 0, '未执行': 31, '不适用': 0},
    'filterChecks': [{'query': '解析、分块和背压', 'visibleCases': 1, 'anchor': 'case-6'}],
    'horizontalOverflow': False, 'consoleErrors': 0, 'consoleWarnings': 0,
    'screenshots': screenshots, 'frontendReport': retained,
    'previousEvidence': '../2026-10-03-解析日志预算补齐/日志补齐前-真实验收记录.json',
    'cleanup': '本轮报告标签页已关闭，18151静态预览已停止；18151/18152均无监听，限定生命周期夹具无残留Java进程。未操作业务进程。'
}
record_path.write_text(json.dumps(record, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({'screenshots': len(screenshots), 'caseCards': 31,
                  'businessExecuted': False, 'reportSHA256': record['reportRendering']['reportSha256']}))
