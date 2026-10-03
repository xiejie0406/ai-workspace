"""独立只读探针：实际SDK+已有内存HTTPX；禁止socket，无Provider调用，不改运行源码。"""
import asyncio
from dataclasses import replace
import gzip
import hashlib
import io
import json
import logging
from pathlib import Path
import runpy
import sys
from unittest.mock import patch

project = Path(__file__).resolve().parents[6] / "python" / "paiflow-runtime"
sys.path.insert(0, str(project))
fixture = runpy.run_path(str(project / "tests" / "test_openai_producer.py"))
from worker.producers.openai_chat import OpenAIChatProducer
from worker.producers.chat_lifecycle import ChatBoundaryError

def hashes():
    return {name: hashlib.sha256((project / name).read_bytes()).hexdigest() for name in (
        "worker/producers/openai_chat.py", "worker/producers/chat_lifecycle.py",
        "worker/model_bridge.py", "workflow/engine/nodes/base_node.py")}

async def scenario(name, *, url=None, compressed=False, tail=False):
    grant = fixture["grant_fixture"]()
    if url is not None:
        grant = replace(grant, private=replace(grant.private, base_url=url))
    frames = [fixture["chunk"]("原内容", finish="stop")]
    if tail:
        frames.append(fixture["usage_only"](13))
    if compressed:
        raw = gzip.compress(b"data: " + fixture["encoded"](fixture["chunk"]("large" * 400000, finish="stop")) + b"\n\n")
        body = fixture["BodyFixture"]([], raw=[raw])
    else:
        body = fixture["BodyFixture"](frames)
    private = fixture["PrivateFixture"](body)
    if compressed:
        private.http.encoding = "gzip"
    center = fixture["CenterFixture"](grant)
    outcome = error = None
    try:
        outcome = await OpenAIChatProducer(center, private).produce("operation-1")
    except ChatBoundaryError as caught:
        error = {"code": caught.code, "kind": caught.failure_kind, "localClosed": caught.local_closed,
                 "sendAttempted": caught.send_attempted, "providerStopConfirmed": caught.provider_stop_confirmed}
    return {"name": name, "error": error, "privateTransportCreated": private.calls,
            "httpRequests": len(private.http.calls), "transportClosed": private.http.closed,
            "bodyClosed": body.closed, "centerFrames": len(center.frames), "centerFinishes": center.finishes,
            "centerStops": len(center.stops), "centerFailures": len(center.failures),
            "tailUsage": center.frames[-1][1].get("usage", {}).get("total_tokens") if tail and center.frames else None,
            "tailChoicesUnchanged": center.frames[-1][1].get("choices") == [] if tail and center.frames else None,
            "outcomeLocalClosed": outcome.local_closed if outcome is not None else None,
            "requestIdentityEncoding": private.http.calls[0].headers.get("accept-encoding") if private.http.calls else None}

async def main():
    before = hashes()
    output = io.StringIO()
    handler = logging.StreamHandler(output)
    loggers = [logging.getLogger(name) for name in ("openai._base_client", "openai._streaming", "httpx")]
    levels = [logger.level for logger in loggers]
    for logger in loggers:
        logger.addHandler(handler)
        logger.setLevel(logging.DEBUG)
    try:
        with patch("socket.socket.connect", side_effect=AssertionError("network forbidden")):
            results = [await scenario("original NUL input", url="https://provider.invalid/v1/\x00"),
                       await scenario("actual SDK invalid IDN constructor", url="https://\u2603.invalid/v1/"),
                       await scenario("gzip bomb rejected before SDK decode", compressed=True),
                       await scenario("actual finish followed by usage-only", tail=True)]
    finally:
        for logger, level in zip(loggers, levels):
            logger.removeHandler(handler)
            logger.setLevel(level)
    print(json.dumps({"beforeSourceHashes": before, "afterSourceHashes": hashes(), "scenarios": results,
                      "privateSDKLogSuppressed": output.getvalue() == "", "networkPolicy": "socket.connect prohibited; in-memory HTTPX only",
                      "realProviderCalls": 0, "businessVerified": False}, ensure_ascii=False, indent=2))

asyncio.run(main())
