"""中心租约驱动的 Python 常驻领取入口；不启动原 API 或数据库。"""
from __future__ import annotations

import argparse
import asyncio
import logging
import os
import signal

from .boundaries import install_legacy_guard
from .center_client import CenterClient
from .errors import WorkerError


async def serve(center, *, capacity=4, polling_seconds=1.0, heartbeat_seconds=10.0):
    if type(capacity) is not int or not 1 <= capacity <= 128 or not 0 < polling_seconds <= 60:
        raise ValueError("Worker 并发/领取间隔无效")
    install_legacy_guard()
    from .engine_adapter import create_task_runner
    runner = create_task_runner(center, capacity=capacity, heartbeat_seconds=heartbeat_seconds)
    stopping = asyncio.Event()
    loop = asyncio.get_running_loop()
    for name in ("SIGINT", "SIGTERM"):
        number = getattr(signal, name, None)
        if number is not None:
            try:
                loop.add_signal_handler(number, stopping.set)
            except (NotImplementedError, RuntimeError):
                # Windows 的主线程信号交由 asyncio.run 取消任务。
                pass
    running = set()
    logger = logging.getLogger("paiflow.worker")

    async def execute(task):
        try:
            await runner.run(task)
        except WorkerError as error:
            logger.error("中心任务执行边界未完成，错误码=%s", error.code)
        except Exception:
            # 不输出远端正文、任务输入和凭据。
            logger.error("中心任务执行边界未完成，需按中心运行记录核对")

    backoff = polling_seconds
    try:
        while not stopping.is_set():
            running = {task for task in running if not task.done()}
            if len(running) >= capacity:
                await asyncio.wait(running, timeout=polling_seconds, return_when=asyncio.FIRST_COMPLETED)
                continue
            try:
                assignment = await center.claim()
                backoff = polling_seconds
                if assignment is not None:
                    running.add(asyncio.create_task(execute(assignment)))
                    continue
            except WorkerError as error:
                logger.error("中心领取不可用，错误码=%s", error.code)
                backoff = min(30.0, backoff * 2)
            try:
                await asyncio.wait_for(stopping.wait(), timeout=backoff)
            except asyncio.TimeoutError:
                pass
    finally:
        for task in running:
            task.cancel()
        if running:
            done, pending = await asyncio.wait(running, timeout=5)
            for task in done:
                if not task.cancelled():
                    task.exception()
            if pending:
                logger.error("Worker 停止未全部确认，中心运行状态须继续核对")


def main():
    parser = argparse.ArgumentParser(description="PaiFlow 中心任务 Python Worker")
    parser.add_argument("--center", default=os.environ.get("PAIFLOW_CENTER_URL"))
    parser.add_argument("--capacity", type=int, default=4)
    parser.add_argument("--polling-seconds", type=float, default=1)
    parser.add_argument("--heartbeat-seconds", type=float, default=10)
    options = parser.parse_args()
    token = os.environ.get("PAIFLOW_WORKER_SERVICE_TOKEN")
    if not options.center or not token:
        parser.error("必须配置 PAIFLOW_CENTER_URL 和 PAIFLOW_WORKER_SERVICE_TOKEN")
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    center = CenterClient(options.center, token)
    try:
        asyncio.run(serve(center, capacity=options.capacity, polling_seconds=options.polling_seconds,
                          heartbeat_seconds=options.heartbeat_seconds))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
