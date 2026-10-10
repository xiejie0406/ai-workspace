"""原Python Worker与父进程真实中心Service的测试级stdio桥，不启动HTTP服务。

命令参数与Java桥相同：父中心UTC clockInstant、私有capturePath。
stdout只发送UTF-8 JSON Lines；父进程必须逐请求返回真实中心回执。
这里不生成业务ACK、固定任务、工具参数或幂等键。
"""
from __future__ import annotations

import asyncio
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch
from urllib.parse import urlsplit


class BridgeProtocolError(RuntimeError):
    """仅表示测试管道失效，不替代正式Worker/Run业务状态。"""


class StdioTransport:
    def __init__(self, protocol, stdin):
        self.protocol, self.stdin = protocol, stdin
        self.lock = asyncio.Lock()
        self.requests, self.responses, self.events = [], [], []
        self.assignment = None

    async def request(self, method, url, headers, encoded, timeout, max_response_bytes):
        # 心跳、事件及工具调用共用同一锁，不能将别人的回执交给当前请求。
        async with self.lock:
            parsed = urlsplit(url)
            if (method != "POST" or parsed.scheme != "https"
                    or parsed.netloc != "centre-fixture.invalid"
                    or not parsed.path.startswith("/internal/paiflow/v1/")
                    or parsed.query or parsed.fragment or len(self.requests) >= 128):
                raise BridgeProtocolError("未声明的stdio请求")
            if not isinstance(encoded, bytes):
                raise BridgeProtocolError("stdio请求缺少原编码体")
            body = json.loads(encoded.decode("utf-8"))
            if not isinstance(body, dict):
                raise BridgeProtocolError("stdio请求体须为对象")
            request_id = len(self.requests) + 1
            request = {"type": "request", "id": request_id, "method": method,
                       "path": parsed.path, "body": body}
            self.requests.append(request)
            if parsed.path.endswith("/events"):
                self.events.append(body)
            self.protocol.write(json.dumps(request, ensure_ascii=False, allow_nan=False,
                                           separators=(",", ":")) + "\n")
            self.protocol.flush()
            # 测试IO在独立线程读取，不阻断原TaskRunner心跳/取消协程。
            line = await asyncio.wait_for(asyncio.to_thread(self.stdin.readline, 24 * 1024 * 1024 + 1), timeout)
            if not line or len(line) > 24 * 1024 * 1024 or not line.endswith("\n"):
                raise BridgeProtocolError("父中心stdio回执缺失或超过预算")
            # 沿用正式JSON边界，拒绝重复键和非JSON数值。
            from worker.task_runner import _unique_object, _deny_constant
            response = json.loads(line, object_pairs_hook=_unique_object, parse_constant=_deny_constant)
            self.responses.append(response)
            if (not isinstance(response, dict) or set(response) != {"id", "status", "body"}
                    or type(response["id"]) is not int or response["id"] != request_id
                    or type(response["status"]) is not int or not 100 <= response["status"] <= 599):
                raise BridgeProtocolError("父中心stdio回执不属于实际请求")
            result = json.dumps(response["body"], ensure_ascii=False, allow_nan=False,
                                separators=(",", ":")).encode("utf-8")
            if len(result) > max_response_bytes:
                raise BridgeProtocolError("父中心stdio正文超过正式客户端预算")
            if parsed.path.endswith("/runs/claim"):
                envelope = response["body"]
                if isinstance(envelope, dict) and envelope.get("code") == 200:
                    self.assignment = envelope.get("data")
            return response["status"], result


async def execute(clock_instant: str, capture_path: Path, protocol, stdin):
    transport = StdioTransport(protocol, stdin)
    result, error, fixed, worker_error_type = None, None, None, ()
    try:
        # 先隔离stdout再导入原引擎，依赖/算法日志不得污染管道。
        from worker.center_client import CenterClient
        from worker.engine_adapter import create_task_runner
        from worker.errors import WorkerError as worker_error_type
        fixed = datetime.fromisoformat(clock_instant.replace("Z", "+00:00"))
        if fixed.tzinfo is None or fixed.utcoffset().total_seconds() != 0:
            raise BridgeProtocolError("父中心Clock须为明确UTC时间")

        class ParentClock(datetime):
            @classmethod
            def now(cls, tz=None):
                return fixed.astimezone(tz) if tz else fixed.replace(tzinfo=None)

        client = CenterClient("https://centre-fixture.invalid", "isolated-stdio-python-worker", transport=transport)
        # 与Java测试桥一样只固定隔离Clock；原任务/deadline/原算法保持原样。
        with patch("worker.task_runner.datetime", ParentClock):
            claimed = await client.claim()
            if claimed is None:
                raise BridgeProtocolError("父中心未返回正式Python任务")
            result = await create_task_runner(client, heartbeat_seconds=10, capacity=1).run(claimed)
    except Exception as failure:
        # 异常正文可能含远端内容；实际请求/回执已单独保留，不另泄露异常文本。
        error = {"kind": type(failure).__name__, "code": failure.code if isinstance(failure, worker_error_type) else None}
    capture = {"scope": "原Python TaskRunner/Factory/CenterClient与父中心真实Service逐请求stdio闭环；无HTTP服务/真实Provider",
               "clockInstant": fixed.astimezone(timezone.utc).isoformat() if fixed is not None else clock_instant,
               "clockBoundary": "仅TaskRunner测试Clock固定父中心时间；assignment及deadline未经修改",
               "assignment": transport.assignment, "requests": transport.requests,
               "responses": transport.responses, "events": transport.events,
               "result": result, "error": error}
    capture_path.parent.mkdir(parents=True, exist_ok=True)
    capture_path.write_text(json.dumps(capture, ensure_ascii=False, allow_nan=False, indent=2), encoding="utf-8")
    protocol.write(json.dumps({"type": "result", "result": result, "capture": capture},
                              ensure_ascii=False, allow_nan=False, separators=(",", ":")) + "\n")
    protocol.flush()
    return 0 if error is None and isinstance(result, dict) and result.get("state") == "COMPLETED" else 1


def main():
    if len(sys.argv) != 3:
        raise SystemExit("需要父中心UTC clockInstant与私有capturePath")
    protocol = sys.stdout
    protocol.reconfigure(encoding="utf-8", errors="strict", write_through=True)
    sys.stderr.reconfigure(encoding="utf-8", errors="strict", write_through=True)
    sys.stdin.reconfigure(encoding="utf-8", errors="strict")
    sys.stdout = sys.stderr
    # 与既有probe的PYTHONPATH指向同一项目，允许父Java测试从自己的cwd启动。
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    return asyncio.run(execute(sys.argv[1], Path(sys.argv[2]).resolve(), protocol, sys.stdin))


if __name__ == "__main__":
    raise SystemExit(main())
