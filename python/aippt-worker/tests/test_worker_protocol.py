import asyncio
import json
from datetime import datetime, timedelta, timezone
import httpx
import pytest
from app.core.config import Settings
from app.worker.protocol import CenterClient, ClaimedTask, LeaseLost
from app.worker.runner import Worker, public_error
from app.worker.catalog import catalog_version


def task(**overrides):
    return ClaimedTask.model_validate({"taskId": "task-1", "attempt": 2, "leaseToken": "lease", "fencingToken": 7,
        "type": "parse_source", "payload": {"source": {"id": "src", "kind": "text", "text": "输入正文"}},
        "deadline": None, "catalogVersion": catalog_version(), **overrides})


@pytest.mark.asyncio
async def test_center_protocol_attempt_fencing_assets_and_null_claim():
    records = []
    def handler(request):
        records.append(request)
        if request.method == "GET": return httpx.Response(200, content=b"private")
        if request.url.path.endswith("claim"): return httpx.Response(200, json={"code": 200, "data": None})
        if request.url.path.endswith("assets"): return httpx.Response(200, json={"code": 200, "data": {"asset_id": "a1", "url": "/aippt/assets/a1"}})
        return httpx.Response(200, json={"code": 200, "data": {"leaseValid": True}})
    center = CenterClient(Settings(worker_key="key"), transport=httpx.MockTransport(handler))
    try:
        assert await center.claim(catalog_version()) is None
        claimed = task()
        await center.heartbeat(claimed)
        await center.complete(claimed, {"sections": []}, [])
        await center.upload_asset(claimed, b"bytes", "image.png", "image/png", "image")
        assert await center.read_asset(claimed, "a1", 20) == b"private"
        for request in records:
            assert request.headers["X-Aippt-Worker-Key"] == "key"
        body = json.loads(records[1].content)
        assert body["attempt"] == 2 and body["fencingToken"] == 7
        assert b'name="sha256"' in records[3].content and b'name="attempt"' in records[3].content
        assert records[4].url.params["attempt"] == "2"
        with pytest.raises(ValueError): await center.read_asset(claimed, "a1", 1)
    finally: await center.close()


@pytest.mark.asyncio
async def test_center_rejects_expired_lease_without_repeating_write():
    center = CenterClient(Settings(worker_key="key"), transport=httpx.MockTransport(lambda req: httpx.Response(409)))
    try:
        with pytest.raises(LeaseLost): await center.complete(task(), {}, [])
    finally: await center.close()


@pytest.mark.asyncio
async def test_claim_default_long_poll_preserves_lease_and_old_positional_call():
    requests = []
    def respond(request):
        requests.append(request)
        return httpx.Response(200, json={"code": 200, "data": task().model_dump()})
    center = CenterClient(Settings(worker_key="key"), transport=httpx.MockTransport(respond))
    try:
        claimed = await center.claim(catalog_version())
        assert isinstance(claimed, ClaimedTask)
        assert claimed.lease() == {"attempt": 2, "leaseToken": "lease", "fencingToken": 7}
        assert json.loads(requests[0].content) == {
            "workerId": center.worker_id, "catalogVersion": catalog_version(), "waitMillis": 10000,
        }
        assert requests[0].headers["X-Aippt-Worker-Key"] == "key"
        assert center.client.timeout.read == 30
    finally: await center.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("types", [[], ["quality", "export"], ["quality", "quality"]])
async def test_claim_optional_filter_and_immediate_wait_null_response(types):
    requests = []
    def respond(request):
        requests.append(json.loads(request.content))
        return httpx.Response(200, json={"code": 200, "data": None})
    center = CenterClient(Settings(worker_key="key"), transport=httpx.MockTransport(respond))
    try:
        assert await center.claim(catalog_version(), wait_millis=0, types=types) is None
        assert requests == [{"workerId": center.worker_id, "catalogVersion": catalog_version(), "waitMillis": 0, "types": types}]
    finally: await center.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("wait", [-1, 10001, True, False, 1.5, "1000", None])
async def test_claim_invalid_wait_never_sends_request(wait):
    requests = []
    center = CenterClient(Settings(worker_key="key"), transport=httpx.MockTransport(lambda request: requests.append(request)))
    try:
        with pytest.raises(ValueError): await center.claim(catalog_version(), wait_millis=wait)
        assert not requests
    finally: await center.close()


@pytest.mark.asyncio
@pytest.mark.parametrize("types", ["quality", ("quality",), ["unknown"], [1], [True]])
async def test_claim_invalid_types_never_sends_request(types):
    requests = []
    center = CenterClient(Settings(worker_key="key"), transport=httpx.MockTransport(lambda request: requests.append(request)))
    try:
        with pytest.raises(ValueError): await center.claim(catalog_version(), types=types)
        assert not requests
    finally: await center.close()


@pytest.mark.parametrize("worker_id", ["", "w" * 129, "🙂" * 65])
def test_center_worker_id_matches_java_length_limit(worker_id):
    with pytest.raises(ValueError): CenterClient(Settings(worker_key="key", worker_id=worker_id))


@pytest.mark.asyncio
async def test_center_accepts_worker_id_at_utf16_boundary():
    center = CenterClient(Settings(worker_key="key", worker_id="🙂" * 64), transport=httpx.MockTransport(lambda request: httpx.Response(200, json={"data": None})))
    try: assert await center.claim(catalog_version()) is None
    finally: await center.close()


@pytest.mark.asyncio
async def test_claim_rejects_invalid_lease_in_returned_task():
    from pydantic import ValidationError
    invalid = task().model_dump(); invalid["fencingToken"] = 0
    center = CenterClient(Settings(worker_key="key"), transport=httpx.MockTransport(lambda request: httpx.Response(200, json={"data": invalid})))
    try:
        with pytest.raises(ValidationError): await center.claim(catalog_version())
    finally: await center.close()


class FakeCenter:
    def __init__(self, *, heartbeat_fail=False): self.completed = []; self.failed = []; self.heartbeat_fail = heartbeat_fail
    async def progress(self, *args): return {"accepted":True}
    async def heartbeat(self, *args):
        if self.heartbeat_fail: raise LeaseLost()
        return {"leaseValid": True}
    async def complete(self, *args): self.completed.append(args)
    async def fail(self, *args): self.failed.append(args)
    async def close(self): pass


class FakeHandlers:
    def __init__(self, *, delay=0, error=None): self.delay=delay; self.error=error; self.calls=0
    async def run(self, task):
        self.calls += 1
        await asyncio.sleep(self.delay)
        if self.error: raise self.error
        return {"result": "candidate"}, []


@pytest.mark.asyncio
@pytest.mark.parametrize("once,expected_wait", [(True, 0), (False, 10000)])
async def test_runner_once_and_slot_keep_claim_receipt_and_fencing(once, expected_wait):
    requests = []
    def respond(request):
        body = json.loads(request.content)
        requests.append((request.url.path, body))
        if request.url.path.endswith("/claim"):
            return httpx.Response(200, json={"data": task().model_dump()})
        if request.url.path.endswith("/complete"):
            worker.stop.set()
        return httpx.Response(200, json={"data": {"accepted": True, "leaseValid": True}})
    cfg = Settings(worker_key="key", concurrency=1)
    center = CenterClient(cfg, transport=httpx.MockTransport(respond))
    handlers = FakeHandlers()
    worker = Worker(cfg, center=center, handlers=handlers)
    await asyncio.wait_for(worker.run(once=once), timeout=5)
    assert requests[0][1]["waitMillis"] == expected_wait
    assert "types" not in requests[0][1]
    assert handlers.calls == 1 and center.client.is_closed
    assert [path.rsplit("/", 1)[-1] for path, _ in requests] == ["claim", "progress", "heartbeat", "complete"]
    for _, body in requests[1:]:
        assert {key: body[key] for key in task().lease()} == task().lease()


@pytest.mark.asyncio
async def test_slot_null_uses_existing_stop_aware_backoff_without_algorithm():
    requests = []
    def respond(request):
        requests.append(json.loads(request.content))
        worker.stop.set()
        return httpx.Response(200, json={"data": None})
    cfg = Settings(worker_key="key", concurrency=1, claim_interval_seconds=60)
    center = CenterClient(cfg, transport=httpx.MockTransport(respond))
    handlers = FakeHandlers()
    worker = Worker(cfg, center=center, handlers=handlers)
    await asyncio.wait_for(worker.run(), timeout=5)
    assert len(requests) == 1 and requests[0]["waitMillis"] == 10000
    assert handlers.calls == 0 and center.client.is_closed


@pytest.mark.asyncio
async def test_cancelling_pending_long_poll_closes_transport_without_starting_task():
    entered = asyncio.Event(); interrupted = asyncio.Event(); requests = []
    async def respond(request):
        requests.append(json.loads(request.content)); entered.set()
        try: await asyncio.Event().wait()
        finally: interrupted.set()
    cfg = Settings(worker_key="key", concurrency=1)
    center = CenterClient(cfg, transport=httpx.MockTransport(respond))
    handlers = FakeHandlers()
    worker = Worker(cfg, center=center, handlers=handlers)
    running = asyncio.create_task(worker.run())
    await asyncio.wait_for(entered.wait(), timeout=5)
    running.cancel()
    with pytest.raises(asyncio.CancelledError): await running
    assert interrupted.is_set() and center.client.is_closed
    assert requests[0]["waitMillis"] == 10000 and handlers.calls == 0


@pytest.mark.asyncio
async def test_worker_success_complete_is_candidate_only():
    center = FakeCenter(); worker = Worker(Settings(worker_key="key"), center=center, handlers=FakeHandlers())
    await worker.execute(task())
    assert len(center.completed) == 1 and not center.failed


@pytest.mark.asyncio
async def test_worker_lost_heartbeat_discards_late_output():
    center = FakeCenter(heartbeat_fail=True)
    cfg = Settings(worker_key="key").model_copy(update={"heartbeat_seconds": .01})
    await Worker(cfg, center=center, handlers=FakeHandlers(delay=.1)).execute(task())
    assert not center.completed and not center.failed


@pytest.mark.asyncio
async def test_worker_deadline_and_catalog_mismatch_have_terminal_failure():
    center = FakeCenter(); worker = Worker(Settings(worker_key="key"), center=center, handlers=FakeHandlers(delay=.1))
    await worker.execute(task(deadline=(datetime.now(timezone.utc)-timedelta(seconds=1)).isoformat()))
    assert center.failed[0][1] == "TASK_TIMEOUT" and not center.completed
    await worker.execute(task(catalogVersion="old"))
    assert center.failed[1][1] == "CATALOG_MISMATCH"


def test_worker_public_error_does_not_echo_secrets():
    result = public_error(ValueError("private text api-key secret"))
    assert "private" not in result[1] and "secret" not in result[1]


@pytest.mark.asyncio
async def test_expired_deadline_never_starts_even_an_immediate_handler():
    center = FakeCenter(); handlers = FakeHandlers()
    await Worker(Settings(worker_key="key"),center=center,handlers=handlers).execute(task(deadline=(datetime.now(timezone.utc)-timedelta(seconds=5)).isoformat()))
    assert handlers.calls == 0 and not center.completed and center.failed[0][1] == "TASK_TIMEOUT"


@pytest.mark.asyncio
async def test_progress_failure_never_starts_algorithm():
    class Center(FakeCenter):
        async def progress(self,*args): raise httpx.ConnectError("fixture")
    center = Center(); handlers = FakeHandlers()
    await Worker(Settings(worker_key="key"),center=center,handlers=handlers).execute(task())
    assert handlers.calls == 0 and not center.completed and center.failed[0][1] == "UPSTREAM_UNAVAILABLE"


@pytest.mark.asyncio
@pytest.mark.parametrize("receipt",[None,{"accepted":False},{"leaseValid":False}])
async def test_unaccepted_progress_receipt_cannot_start_algorithm(receipt):
    class Center(FakeCenter):
        async def progress(self,*args): return receipt
    center = Center(); handlers = FakeHandlers()
    await Worker(Settings(worker_key="key"),center=center,handlers=handlers).execute(task())
    assert handlers.calls == 0 and not center.completed


@pytest.mark.asyncio
async def test_deadline_is_rechecked_after_center_receipt():
    class Center(FakeCenter):
        async def progress(self,*args):
            await asyncio.sleep(.1)
            return {"accepted":True}
    center = Center(); handlers = FakeHandlers()
    await Worker(Settings(worker_key="key"),center=center,handlers=handlers).execute(task(deadline=(datetime.now(timezone.utc)+timedelta(seconds=.03)).isoformat()))
    assert handlers.calls == 0 and not center.completed and center.failed[0][1] == "TASK_TIMEOUT"


@pytest.mark.asyncio
async def test_inflight_timeout_cancels_handler_and_keeps_actual_usage_summary():
    from langchain_core.language_models.fake_chat_models import FakeMessagesListChatModel
    from langchain_core.messages import AIMessage
    from app.worker.execution import EXECUTION_CALLBACK
    from app.worker.handlers import TaskHandlers
    class Slow(TaskHandlers):
        async def generate_outline(self,task):
            model=FakeMessagesListChatModel(responses=[AIMessage(content="fixture",usage_metadata={"input_tokens":2,"output_tokens":1,"total_tokens":3})],callbacks=[EXECUTION_CALLBACK])
            await model.ainvoke("fixture")
            await asyncio.sleep(3)
            return {},[]
    center=FakeCenter(); cfg=Settings(worker_key="key").model_copy(update={"task_timeout_seconds":2})
    await Worker(cfg,center=center,handlers=Slow(center)).execute(task(type="generate_outline",payload={}))
    assert not center.completed and center.failed[0][1] == "TASK_TIMEOUT"
    execution=center.failed[0][-1]
    assert execution["input_tokens"] == 2 and execution["model_calls"] == 1 and execution["usage_status"] == "available"
