"""原Python LLM历史的测试级stdio桥；所有业务回执由父中心真实Service提供。

参数：父中心UTC时间、私有capturePath、可选期望终态（默认COMPLETED）。
复用普通工具桥的传输边界，不构造历史、模型或租约ACK，不启动HTTP服务。
"""
from __future__ import annotations

import asyncio
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

# 父Java可从任意cwd用脚本绝对路径启动；引用捕获工程的同一模块根。
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from tests.centre_tool_stdio_bridge import BridgeProtocolError, StdioTransport

TERMINAL_STATES = frozenset({'COMPLETED', 'FAILED', 'CANCELLED', 'EFFECT_UNKNOWN'})


async def execute(clock_instant, capture_path, expected_terminal, protocol, stdin):
    if expected_terminal not in TERMINAL_STATES:
        raise BridgeProtocolError('期望终态必须是明确的正式终态')
    transport = StdioTransport(protocol, stdin)
    result, error, fixed, worker_error_type = None, None, None, ()
    try:
        # main已隔离stdout，原算法与依赖日志不能污染协议。
        from worker.center_client import CenterClient
        from worker.engine_adapter import create_task_runner
        from worker.errors import WorkerError as worker_error_type
        fixed = datetime.fromisoformat(clock_instant.replace('Z', '+00:00'))
        if fixed.tzinfo is None or fixed.utcoffset().total_seconds() != 0:
            raise BridgeProtocolError('父中心Clock须为明确UTC时间')

        class ParentClock(datetime):
            @classmethod
            def now(cls, tz=None):
                return fixed.astimezone(tz) if tz else fixed.replace(tzinfo=None)

        client = CenterClient('https://centre-fixture.invalid',
                              'isolated-stdio-python-worker', transport=transport)
        with patch('worker.task_runner.datetime', ParentClock):
            claimed = await client.claim()
            if claimed is None:
                raise BridgeProtocolError('父中心未返回正式Python任务')
            result = await create_task_runner(client, heartbeat_seconds=10, capacity=1).run(claimed)
    except Exception as failure:
        # 远端错误正文只保留在原请求/回执；异常说明不另公开私有文本。
        error = {'kind': type(failure).__name__,
                 'code': failure.code if isinstance(failure, worker_error_type) else None}
    capture = {
        'scope': '原Python TaskRunner/Factory/CenterClient与父中心真实Service逐请求stdio闭环；不启动产品服务，不访问真实Provider',
        'clockInstant': fixed.astimezone(timezone.utc).isoformat() if fixed is not None else clock_instant,
        'clockBoundary': '仅TaskRunner测试Clock固定父中心时间；固定assignment/deadline未改',
        'expectedTerminal': expected_terminal,
        'assignment': transport.assignment, 'requests': transport.requests,
        'responses': transport.responses, 'events': transport.events,
        'result': result, 'error': error,
    }
    capture_path.parent.mkdir(parents=True, exist_ok=True)
    capture_path.write_text(json.dumps(capture, ensure_ascii=False, allow_nan=False, indent=2), encoding='utf-8')
    protocol.write(json.dumps({'type': 'result', 'result': result, 'capture': capture},
                              ensure_ascii=False, allow_nan=False, separators=(',', ':')) + '\n')
    protocol.flush()
    return 0 if error is None and isinstance(result, dict) and result.get('state') == expected_terminal else 1


def main():
    if len(sys.argv) not in (3, 4):
        raise SystemExit('需要父中心UTC时间、私有capturePath及可选期望终态')
    expected = sys.argv[3] if len(sys.argv) == 4 else 'COMPLETED'
    if expected not in TERMINAL_STATES:
        raise SystemExit('期望终态只能为COMPLETED/FAILED/CANCELLED/EFFECT_UNKNOWN')
    protocol = sys.stdout
    protocol.reconfigure(encoding='utf-8', errors='strict', write_through=True)
    sys.stderr.reconfigure(encoding='utf-8', errors='strict', write_through=True)
    sys.stdin.reconfigure(encoding='utf-8', errors='strict')
    sys.stdout = sys.stderr
    return asyncio.run(execute(sys.argv[1], Path(sys.argv[2]).resolve(), expected, protocol, sys.stdin))


if __name__ == '__main__':
    raise SystemExit(main())
