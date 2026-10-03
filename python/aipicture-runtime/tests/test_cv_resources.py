from __future__ import annotations

import base64
import csv
import hashlib
import importlib.metadata
import io
import json
from pathlib import Path
import zipfile

import pytest

from scripts import prepare_cv_resources as cv


@pytest.fixture
def private_workspace(tmp_path, monkeypatch):
    runtime = tmp_path / 'python/aipicture-runtime'
    runtime.mkdir(parents=True)
    (runtime / 'uv.lock').write_bytes((cv.RUNTIME / 'uv.lock').read_bytes())
    monkeypatch.setattr(cv, 'WORKSPACE', tmp_path)
    monkeypatch.setattr(cv, 'RUNTIME', runtime)
    monkeypatch.setattr(cv, 'PRIVATE', tmp_path / '.runtime/aipicture/vision')
    return tmp_path


def test_default_inspection_never_downloads_installs_initializes_or_creates_directory(private_workspace, monkeypatch, capsys):
    def forbidden(*_args, **_kwargs):
        raise AssertionError('只读模式触发了下载/安装/初始化')
    monkeypatch.setattr(cv.subprocess, 'run', forbidden)
    monkeypatch.setattr(cv.urllib.request, 'build_opener', forbidden)
    monkeypatch.setattr(cv, 'self_check', forbidden)
    (private_workspace / '.env').write_text('AIPICTURE_DASHSCOPE_API_KEY=test-secret-must-not-leak\n', encoding='utf-8')
    before = {path.relative_to(private_workspace).as_posix() for path in private_workspace.rglob('*')}
    assert cv.main([]) == 0
    output = capsys.readouterr().out
    assert 'test-secret-must-not-leak' not in output
    result = json.loads(output)
    assert result['mode'] == 'read_only'
    assert result['provider_config_presence_only']['AIPICTURE_DASHSCOPE_API_KEY'] is True
    assert result['inference_executed'] is result['quality_accepted'] is False
    assert before == {path.relative_to(private_workspace).as_posix() for path in private_workspace.rglob('*')}


def test_private_resource_paths_reject_storage_sibling_and_outside_workspace(private_workspace):
    with pytest.raises(ValueError, match='私有目录'):
        cv.private_path('..', 'storage', 'unrelated.txt')
    with pytest.raises(ValueError, match='私有目录'):
        cv.private_path('..', '..', '..', '..', 'outside.txt')


def test_existing_wrong_model_is_preserved_without_network(private_workspace, monkeypatch):
    path = cv.private_path('models', 'u2net.onnx')
    path.parent.mkdir(parents=True)
    path.write_bytes(b'not-an-onnx-model')
    monkeypatch.setattr(cv.urllib.request, 'build_opener', lambda *_args: pytest.fail('不应覆盖或下载'))
    with pytest.raises(ValueError, match='校验不符'):
        cv.download(cv.RELEASE + path.name, path, 175997641, 'md5', cv.MODELS[0][2], 175997641)
    assert path.read_bytes() == b'not-an-onnx-model'


def test_single_opencv_distribution_is_locked_and_other_locked_versions_preserved():
    packages, _ = cv.lock_plan()
    assert packages['opencv-python']['version'] == '5.0.0.93'
    assert not any(name.startswith('opencv-') and name != 'opencv-python' for name in packages)
    assert packages['rembg']['version'] == '2.0.85'
    assert packages['rapidocr-onnxruntime']['version'] == '1.4.4'


def test_mixed_opencv_lock_rejected_before_install(private_workspace):
    with (cv.RUNTIME / 'uv.lock').open('a', encoding='utf-8') as stream:
        stream.write('\n[[package]]\nname = "opencv-python-headless"\nversion = "4.14.0.94"\n')
    with pytest.raises(ValueError, match='多种 cv2'):
        cv.lock_plan()


def test_runtime_config_keeps_models_within_rembg_home_and_has_no_provider_secret(private_workspace):
    env = cv.environment_values()
    assert env['U2NET_HOME'] == env['REMBG_HOME']
    for name, value in env.items():
        if name.endswith('_MODEL_PATH') or name.endswith('_ENCODER_PATH') or name.endswith('_DECODER_PATH'):
            assert Path(value).is_relative_to(Path(env['U2NET_HOME']))
    assert not any('KEY' in name or 'SECRET' in name or 'DATABASE' in name for name in env)
    assert 'AIPICTURE_ENABLED' not in env


def test_ocr_models_checked_against_wheel_record_and_unknown_zip_members_not_extracted(private_workspace):
    wheel_path = cv.private_path('packages', 'fixture.whl')
    wheel_path.parent.mkdir(parents=True)
    rows = []
    with zipfile.ZipFile(wheel_path, 'w') as archive:
        for name in cv.OCR_FILES:
            data = ('offline-fixture-' + name).encode()
            member = 'rapidocr_onnxruntime/models/' + name
            archive.writestr(member, data)
            sha = base64.urlsafe_b64encode(hashlib.sha256(data).digest()).decode().rstrip('=')
            rows.append((member, 'sha256=' + sha, str(len(data))))
        record = io.StringIO()
        csv.writer(record).writerows(rows)
        archive.writestr('rapidocr_onnxruntime-1.4.4.dist-info/RECORD', record.getvalue())
        archive.writestr('../../outside.txt', 'must not be extracted')
    wheel = {'url': 'https://files.pythonhosted.org/fixture.whl', 'size': wheel_path.stat().st_size, 'hash': 'sha256:' + cv.digest(wheel_path)}
    actual = cv.ocr_from_verified_wheel(wheel, write=True)
    assert {item['file'] for item in actual} == set(cv.OCR_FILES)
    assert not (private_workspace / 'outside.txt').exists()
    assert cv.ocr_from_verified_wheel(wheel, write=False) == actual
    model = cv.private_path('models', cv.OCR_FILES[0])
    model.write_bytes(b'changed-model')
    with pytest.raises(ValueError, match='锁定 wheel'):
        cv.ocr_from_verified_wheel(wheel, write=False)
    assert model.read_bytes() == b'changed-model'


def test_real_selfcheck_refuses_absent_models_without_mock_or_download(private_workspace, monkeypatch):
    def missing(_name):
        raise importlib.metadata.PackageNotFoundError()
    monkeypatch.setattr(cv.importlib.metadata, 'version', missing)
    with pytest.raises(ValueError, match='缺少真实'):
        cv.self_check()
    assert not cv.PRIVATE.exists()


def test_real_session_provider_inspection_uses_rapidocr_144_recognizer_interface():
    from types import SimpleNamespace as NS
    session = NS(get_providers=lambda: ['CPUExecutionProvider'])
    pixels = NS(_session=NS(inner_session=session))
    segment = NS(_sam=NS(encoder=session, decoder=session))
    ocr = NS(_engine=NS(text_det=NS(infer=NS(session=session)), text_cls=NS(infer=NS(session=session)),
                       text_rec=NS(session=NS(session=session))))
    actual = cv.session_providers(pixels, segment, ocr)
    assert len(actual) == 6 and all(value == ['CPUExecutionProvider'] for value in actual.values())


@pytest.mark.parametrize('process_exit_code', [3, 2])
def test_apply_process_guard_refuses_untracked_runtime_or_failed_inspection_before_writes(private_workspace, monkeypatch, process_exit_code):
    from types import SimpleNamespace
    executable = cv.RUNTIME / '.venv/Scripts/python.exe'
    monkeypatch.setattr(cv, 'PYTHON', executable)
    monkeypatch.setattr(cv.sys, 'executable', str(executable))
    monkeypatch.setattr(cv.shutil, 'which', lambda _name: 'fixture-uv-must-not-be-executed')
    calls = []
    def process_check(command, **kwargs):
        calls.append(command)
        assert command[0] == 'powershell.exe'
        assert 'Get-CimInstance Win32_Process' in command[-1]
        assert kwargs['capture_output'] is True
        return SimpleNamespace(returncode=process_exit_code)
    monkeypatch.setattr(cv.subprocess, 'run', process_check)
    assert not (private_workspace / '.runtime/aipicture/processes.json').exists()
    with pytest.raises(ValueError, match='仍在运行或无法核对进程'):
        cv.apply()
    assert len(calls) == 1 and not cv.PRIVATE.exists()


def test_process_guard_can_verify_stopped_runtime_without_manifest(private_workspace, monkeypatch):
    from types import SimpleNamespace
    monkeypatch.setattr(cv.subprocess, 'run', lambda *_args, **_kwargs: SimpleNamespace(returncode=0))
    cv._assert_runtime_stopped()
    assert not cv.PRIVATE.exists()


def test_install_invocation_pins_picture_project_venv_and_drops_inherited_uv_environment(private_workspace, monkeypatch):
    monkeypatch.setattr(cv, 'PYTHON', cv.RUNTIME / '.venv/Scripts/python.exe')
    monkeypatch.setenv('UV_PROJECT_ENVIRONMENT', str(private_workspace / 'other-project/.venv'))
    monkeypatch.setenv('UV_PROJECT', str(private_workspace / 'other-project'))
    monkeypatch.setenv('UV_INDEX_URL', 'https://unapproved.invalid/simple')
    monkeypatch.setenv('UV_ENV_FILE', str(private_workspace / 'other-project/.env'))
    monkeypatch.setenv('VIRTUAL_ENV', str(private_workspace / 'other-project/.venv'))
    monkeypatch.setenv('AIPICTURE_DASHSCOPE_API_KEY', 'test-secret-must-not-inherit')
    command, env = cv._installation_invocation('fixture-uv')
    assert command[command.index('--project') + 1] == str(cv.RUNTIME.resolve())
    assert env['UV_PROJECT_ENVIRONMENT'] == str((cv.RUNTIME / '.venv').resolve())
    assert {key for key in env if key.startswith('UV_')} == {'UV_PROJECT_ENVIRONMENT'}
    assert 'VIRTUAL_ENV' not in env and 'AIPICTURE_DASHSCOPE_API_KEY' not in env
    assert not cv.PRIVATE.exists()


def test_install_invocation_rejects_interpreter_outside_picture_environment_before_writes(private_workspace, monkeypatch):
    monkeypatch.setattr(cv, 'PYTHON', private_workspace / 'other-project/.venv/Scripts/python.exe')
    with pytest.raises(ValueError, match='不能解析到本工程外'):
        cv._installation_invocation('fixture-uv')
    assert not cv.PRIVATE.exists()
