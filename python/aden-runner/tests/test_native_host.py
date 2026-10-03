import io
import struct

import pytest

from aden_runner.native_host.__main__ import (
    MAX_MESSAGE,
    pipe_name,
    read_message,
    serve,
    validate_message,
    write_message,
)


def test_unicode_frame_roundtrip():
    stream = io.BytesIO()
    message = {
        "protocolVersion": 1,
        "messageId": "a",
        "type": "hello",
        "payload": {"label": "采集"},
    }
    write_message(stream, message)
    stream.seek(0)
    assert read_message(stream) == message
    validate_message(message)


@pytest.mark.parametrize(
    "data",
    [
        struct.pack("<I", MAX_MESSAGE + 1),
        struct.pack("<I", 4) + b"{}",
        struct.pack("<I", 2) + b"[]",
    ],
)
def test_invalid_frames(data):
    with pytest.raises((ValueError, EOFError)):
        read_message(io.BytesIO(data))


@pytest.mark.parametrize(
    "patch",
    [
        {"type": "shell"},
        {"protocolVersion": 2},
        {"payload": {"workspaceId": "other"}},
        {"messageId": "../a"},
    ],
)
def test_reject_untrusted_envelope(patch):
    message = {"protocolVersion": 1, "messageId": "a", "type": "hello", "payload": {}}
    with pytest.raises(ValueError):
        validate_message(message | patch)


def test_reject_out_of_bounds_chunk():
    with pytest.raises(ValueError):
        validate_message(
            {
                "protocolVersion": 1,
                "messageId": "a",
                "type": "asset.chunk",
                "nonce": "synthetic-nonce-0123456789",
                "sequence": "1",
                "expiresAt": "2026-10-01T00:02:00Z",
                "payload": {"dataBase64": "YQ==", "offset": 2, "totalSize": 1, "sha256": "a" * 64},
            }
        )


def test_pipe_name_override(monkeypatch):
    monkeypatch.setenv("ADEN_COLLECTOR_PIPE", r"\\.\pipe\aden-collector-test")
    assert pipe_name() == r"\\.\pipe\aden-collector-test"
    monkeypatch.setenv("ADEN_COLLECTOR_PIPE", r"\\remote\pipe\aden-collector-test")
    with pytest.raises(ValueError):
        pipe_name()


def test_long_lived_bridge_keeps_two_message_correlations():
    class Bridge:
        def __init__(self):
            self.reply = io.BytesIO()
            self.ids = []

        def write(self, data):
            request = read_message(io.BytesIO(data))
            self.ids.append(request["messageId"])
            self.reply = io.BytesIO()
            write_message(
                self.reply,
                {"protocolVersion": 1, "messageId": request["messageId"], "ok": True},
            )
            self.reply.seek(0)

        def read(self, size):
            return self.reply.read(size)

        def flush(self):
            pass

    bridge = Bridge()
    source = io.BytesIO()
    for message_id in ["hello1", "status2"]:
        write_message(
            source,
            {"protocolVersion": 1, "messageId": message_id, "type": "hello", "payload": {}},
        )
    source.seek(0)
    destination = io.BytesIO()
    serve(source, destination, bridge)
    destination.seek(0)
    assert bridge.ids == ["hello1", "status2"]
    assert read_message(destination)["messageId"] == "hello1"
    assert read_message(destination)["messageId"] == "status2"


def proved_message():
    return {"protocolVersion": 1, "messageId": "a", "type": "capture.status", "payload": {},
            "nonce": "synthetic-nonce-0123456789", "sequence": "9007199254740993", "expiresAt": "2026-10-01T00:02:00.000Z"}


def test_connection_proof_preserves_decimal_sequence():
    value = proved_message()
    validate_message(value)
    stream = io.BytesIO()
    write_message(stream, value)
    stream.seek(0)
    assert read_message(stream)["sequence"] == "9007199254740993"


@pytest.mark.parametrize("patch", [
    {"nonce": ""}, {"sequence": 1}, {"sequence": "01"}, {"sequence": "0"},
    {"expiresAt": "2026-10-01T00:02:00+08:00"}, {"expiresAt": "2026-99-01T00:02:00Z"},
    {"unknown": True},
])
def test_reject_malformed_connection_proof(patch):
    with pytest.raises(ValueError):
        validate_message(proved_message() | patch)


def test_legacy_non_hello_requires_upgrade():
    with pytest.raises(ValueError, match="升级"):
        validate_message({"protocolVersion": 1, "messageId": "a", "type": "capture.status", "payload": {}})
