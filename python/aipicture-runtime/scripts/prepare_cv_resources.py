"""默认只读；--apply 才安装/下载，--self-check 仅调用本地真实 CPU 模型。"""
from __future__ import annotations

import argparse
import base64
import csv
import hashlib
import importlib.metadata
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import tomllib
import urllib.parse
import urllib.request
import uuid
import zipfile

RUNTIME = Path(__file__).resolve().parents[1]
WORKSPACE = RUNTIME.parents[1]
PRIVATE = WORKSPACE / '.runtime/aipicture/vision'
PYTHON = RUNTIME / '.venv/Scripts/python.exe'
LOCKED = {'rembg': '2.0.85', 'rapidocr-onnxruntime': '1.4.4', 'onnxruntime': '1.30.0', 'opencv-python': '5.0.0.93'}
RELEASE = 'https://github.com/danielgatis/rembg/releases/download/v0.0.0/'
MODELS = (
    ('u2net.onnx', 175997641, '60024c5c889badc19c04ad937298a77b'),
    ('sam_vit_b_01ec64.encoder.quant.onnx', 108836115, '26fc0e01d2fa34ed2d3f91259118482d'),
    ('sam_vit_b_01ec64.decoder.quant.onnx', 8755366, '45391530307d1aee79b2a1507769e6c7'),
)
OCR_FILES = ('ch_PP-OCRv4_det_infer.onnx', 'ch_PP-OCRv4_rec_infer.onnx', 'ch_ppocr_mobile_v2.0_cls_infer.onnx')
LICENSES = {
    'rembg-MIT.txt': 'https://raw.githubusercontent.com/danielgatis/rembg/v2.0.85/LICENSE.txt',
    'U2Net-Apache-2.0.txt': 'https://raw.githubusercontent.com/xuebinqin/U-2-Net/89d9a2e60d4e114de742feb17ea62445f1c9f4e5/LICENSE',
    'SAM-Apache-2.0.txt': 'https://raw.githubusercontent.com/facebookresearch/segment-anything/571794162e0887c15d12b809505b902c7bf8b4db/LICENSE',
    'RapidOCR-Apache-2.0.txt': 'https://raw.githubusercontent.com/RapidAI/RapidOCR/v1.4.4/LICENSE',
}
SAFE_HOSTS = {'github.com', 'release-assets.githubusercontent.com', 'objects.githubusercontent.com',
              'raw.githubusercontent.com', 'files.pythonhosted.org'}


def private_path(*parts: str) -> Path:
    base = (WORKSPACE / '.runtime/aipicture').resolve()
    if not base.is_relative_to(WORKSPACE.resolve()):
        raise ValueError('图片私有目录不能通过链接指向工作区外')
    target = PRIVATE.joinpath(*parts).resolve()
    if not target.is_relative_to(base) or not target.is_relative_to(PRIVATE.resolve()):
        raise ValueError('资源路径超出图片私有目录')
    return target


def digest(path: Path, algorithm: str = 'sha256') -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def lock_plan() -> tuple[dict, dict]:
    lock = tomllib.loads((RUNTIME / 'uv.lock').read_text(encoding='utf-8'))
    packages = {item['name']: item for item in lock['package']}
    for name, version in LOCKED.items():
        if packages.get(name, {}).get('version') != version:
            raise ValueError(f'锁定依赖 {name} 与已核实版本不一致，请重新核对资源适配')
    if any(name in packages for name in ('opencv-python-headless', 'opencv-contrib-python', 'opencv-contrib-python-headless')):
        raise ValueError('锁文件包含多种 cv2 分发，先统一 OpenCV 依赖')
    wheel = packages['rapidocr-onnxruntime']['wheels'][0]
    expected = '971d7d5f223a7a808662229df1ef69893809d8457d834e6373d3854bc1782cbf'
    if wheel['hash'] != 'sha256:' + expected or wheel['size'] != 14915192:
        raise ValueError('RapidOCR 1.4.4 wheel 与已核实来源不一致')
    return packages, wheel


def environment_values() -> dict[str, str]:
    models = private_path('models')
    return {'U2NET_HOME': str(models), 'REMBG_HOME': str(models), 'AIPICTURE_OFFLINE_TEST': 'false',
            'AIPICTURE_MATTING_PROVIDER': 'rembg', 'AIPICTURE_MATTING_MODEL_PATH': str(models / MODELS[0][0]),
            'AIPICTURE_SAM_ENCODER_PATH': str(models / MODELS[1][0]),
            'AIPICTURE_SAM_DECODER_PATH': str(models / MODELS[2][0]), 'AIPICTURE_OCR_PROVIDER': 'rapidocr',
            **{f'AIPICTURE_OCR_{kind}_MODEL_PATH': str(models / name) for kind, name in zip(('DET', 'REC', 'CLS'), OCR_FILES, strict=True)}}


def config_presence() -> dict[str, bool]:
    names = ('AIPICTURE_DASHSCOPE_API_KEY', 'DASHSCOPE_API_KEY', 'AIPICTURE_PROVIDER_OUTPUT_HOSTS',
             'AIPICTURE_MATTING_MODEL_PATH', 'AIPICTURE_SAM_ENCODER_PATH', 'AIPICTURE_SAM_DECODER_PATH',
             'AIPICTURE_OCR_DET_MODEL_PATH', 'AIPICTURE_OCR_REC_MODEL_PATH', 'AIPICTURE_OCR_CLS_MODEL_PATH')
    values = {name: os.environ.get(name, '') for name in names}
    for path in (WORKSPACE / '.env', RUNTIME / '.env', WORKSPACE / '参考项目/ai-picture-editor-main/backend/.env', private_path('runtime.env')):
        if not path.is_file():
            continue
        for line in path.read_text(encoding='utf-8-sig').splitlines():
            match = re.match(r'^([A-Za-z][A-Za-z0-9_]+)=(.*)$', line)
            if match and match[1].upper() in names and not values.get(match[1].upper()):
                values[match[1].upper()] = match[2].strip().strip('\"\'')
    # 不输出值，也不将原项目或其他项目密钥注入运行环境。
    return {name: bool(value and value not in ('[]', 'null', 'None')) for name, value in values.items()}


def inspect() -> dict:
    _, wheel = lock_plan()
    packages = {}
    for name, version in LOCKED.items():
        try:
            installed = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            installed = None
        packages[name] = {'locked': version, 'installed': installed, 'matches': installed == version}
    files = []
    for name, size, md5 in MODELS:
        path = private_path('models', name)
        files.append({'file': name, 'exists': path.is_file(), 'bytes': path.stat().st_size if path.is_file() else None,
                      'release_bytes': size, 'official_md5': md5, 'sha256': digest(path) if path.is_file() else None,
                      'checksum_matches': path.is_file() and path.stat().st_size == size and digest(path, 'md5') == md5})
    for name in OCR_FILES:
        path = private_path('models', name)
        files.append({'file': name, 'exists': path.is_file(), 'sha256': digest(path) if path.is_file() else None,
                      'validation': 'Apply时核对锁定wheel与RECORD；SelfCheck时再次回读wheel验证'})
    return {'mode': 'read_only', 'private_directory_exists': PRIVATE.is_dir(), 'packages': packages, 'models': files,
            'provider_config_presence_only': config_presence(), 'configuration_variables': list(environment_values()),
            'download_plan': {'model_bytes': sum(item[1] for item in MODELS), 'ocr_wheel_bytes': wheel['size'],
                              'licenses': LICENSES, 'dependency_install': 'uv sync --locked --extra cv --no-python-downloads'},
            'validation': 'files_and_metadata_only', 'inference_executed': False, 'quality_accepted': False}


class StrictRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        parsed = urllib.parse.urlsplit(newurl)
        if parsed.scheme != 'https' or parsed.hostname not in SAFE_HOSTS or parsed.username or parsed.password:
            raise ValueError('资源重定向地址不在批准的官方 HTTPS 主机中')
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download(url: str, path: Path, maximum: int, algorithm: str | None = None, expected: str | None = None, exact_size: int | None = None) -> None:
    path = private_path(str(path.relative_to(PRIVATE.resolve())))
    if path.exists():
        if not path.is_file() or (exact_size is not None and path.stat().st_size != exact_size) or (expected and digest(path, algorithm or 'sha256') != expected):
            raise ValueError(f'{path.name} 已存在但校验不符，保留文件，停止准备')
        return
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != 'https' or parsed.hostname not in SAFE_HOSTS or parsed.username or parsed.password:
        raise ValueError('资源只允许批准的官方 HTTPS 地址')
    path.parent.mkdir(parents=True, exist_ok=True)
    partial = private_path(str(path.relative_to(PRIVATE.resolve())) + '.' + uuid.uuid4().hex + '.part')
    total = 0
    with urllib.request.build_opener(StrictRedirect()).open(url, timeout=60) as response, partial.open('xb') as stream:
        while chunk := response.read(1024 * 1024):
            total += len(chunk)
            if total > maximum:
                raise ValueError(f'{path.name} 下载超过限定大小；保留本次私有part文件')
            stream.write(chunk)
    if (exact_size is not None and total != exact_size) or (expected and digest(partial, algorithm or 'sha256') != expected):
        raise ValueError(f'{path.name} 来源校验失败；保留本次私有part文件，不采用')
    partial.rename(path)  # Windows 目标存在时拒绝覆盖。


def ocr_from_verified_wheel(wheel: dict, *, write: bool) -> list[dict]:
    path = private_path('packages', Path(urllib.parse.urlsplit(wheel['url']).path).name)
    if not path.is_file() or path.stat().st_size != wheel['size'] or digest(path) != wheel['hash'].split(':', 1)[1]:
        raise ValueError('缺少经锁文件 SHA256 校验的 RapidOCR wheel')
    result = []
    with zipfile.ZipFile(path) as archive:
        records = list(csv.reader(io.StringIO(archive.read('rapidocr_onnxruntime-1.4.4.dist-info/RECORD').decode('utf-8'))))
        by_name = {row[0]: row for row in records}
        for name in OCR_FILES:
            member = 'rapidocr_onnxruntime/models/' + name
            data = archive.read(member)  # 只读取固定成员，不执行任意 ZIP 解包。
            _, expected_hash, expected_size = by_name[member]
            actual = base64.urlsafe_b64encode(hashlib.sha256(data).digest()).decode().rstrip('=')
            if expected_hash != 'sha256=' + actual or len(data) != int(expected_size) or len(data) > 30 * 1024 * 1024:
                raise ValueError(f'{name} wheel RECORD 校验失败')
            target = private_path('models', name)
            if target.exists() and (not target.is_file() or digest(target) != hashlib.sha256(data).hexdigest()):
                raise ValueError(f'{name} 已存在但与锁定 wheel 不同，保留文件')
            if not target.exists():
                if not write:
                    raise ValueError(f'{name} 尚未准备')
                target.parent.mkdir(parents=True, exist_ok=True)
                with target.open('xb') as stream:
                    stream.write(data)
            result.append({'file': name, 'bytes': len(data), 'sha256': digest(target), 'source': wheel['url'], 'license': 'Apache-2.0'})
    return result


def _assert_runtime_stopped() -> None:
    # 清单可能缺失或过期；枚举图片模块命令覆盖基础 Python 子进程及手动重启。
    # 不打印命令行/进程环境，清单中的存活 PID 作为额外的保守停止条件。
    script = (
        "$ErrorActionPreference='Stop'; Set-StrictMode -Version Latest; "
        "try { $active=@(Get-CimInstance Win32_Process | Where-Object { "
        "$_.CommandLine -match '(^|\\s)-m\\s+aipicture_runtime\\s+(serve|worker)(\\s|$)' }); "
        "if($active.Count) { exit 3 }; "
        "if(Test-Path -LiteralPath $env:AIPICTURE_RESOURCE_MANIFEST -PathType Leaf) { "
        "$m=Get-Content -LiteralPath $env:AIPICTURE_RESOURCE_MANIFEST -Raw | ConvertFrom-Json; "
        "foreach($p in $m.processes) { if($p.name -like 'python-*' -and "
        "(Get-Process -Id $p.pid -ErrorAction SilentlyContinue)) { exit 3 } } }; exit 0 "
        "} catch { exit 2 }"
    )
    check = subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command', script],
        env={**os.environ, 'AIPICTURE_RESOURCE_MANIFEST': str(WORKSPACE / '.runtime/aipicture/processes.json')},
        capture_output=True, timeout=15)
    if check.returncode:
        raise ValueError('图片Python通道仍在运行或无法核对进程；先停止serve/worker')


def _installation_invocation(uv: str) -> tuple[list[str], dict[str, str]]:
    runtime = RUNTIME.resolve()
    venv = (RUNTIME / '.venv').resolve()
    if not venv.is_relative_to(runtime) or not PYTHON.resolve().is_relative_to(venv):
        raise ValueError('安装目标虚拟环境不能解析到本工程外')
    # --python 仅选择解释器；UV_PROJECT_ENVIRONMENT 才决定 sync 修改的环境。
    # 去除继承的 uv 配置后固定项目和环境，避免影响其他项目。
    child_env = {key: value for key, value in os.environ.items()
                 if not key.upper().startswith(('AIPICTURE_', 'DASHSCOPE_', 'RUOYI_DB_', 'UV_'))
                 and key.upper() != 'VIRTUAL_ENV'}
    child_env['UV_PROJECT_ENVIRONMENT'] = str(venv)
    command = [uv, '--no-config', 'sync', '--project', str(runtime), '--locked', '--extra', 'cv',
               '--python', str(PYTHON.resolve()), '--no-python-downloads', '--cache-dir', str(private_path('uv-cache'))]
    return command, child_env


def apply() -> dict:
    if Path(sys.executable).resolve() != PYTHON.resolve() or sys.version_info < (3, 13):
        raise ValueError('安装仅允许本工程现有 Python>=3.13 虚拟环境')
    _, wheel = lock_plan()
    uv = shutil.which('uv')
    if not uv:
        raise ValueError('uv未安装，本脚本不会全局安装工具')
    install_command, child_env = _installation_invocation(uv)
    _assert_runtime_stopped()
    env_text = '\n'.join(f'{key}={value}' for key, value in environment_values().items()) + '\n'
    env_path = private_path('runtime.env')
    if env_path.exists() and env_path.read_text(encoding='utf-8') != env_text:
        raise ValueError('私有 runtime.env 存在用户修改，保留并停止；请核对配置')
    backup = private_path('installed-packages.before.json')
    backup.parent.mkdir(parents=True, exist_ok=True)
    if not backup.exists():
        snapshot = sorted((dist.metadata['Name'], dist.version) for dist in importlib.metadata.distributions())
        backup.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    for name, size, md5 in MODELS:
        download(RELEASE + name, private_path('models', name), size, 'md5', md5, size)
    download(wheel['url'], private_path('packages', Path(urllib.parse.urlsplit(wheel['url']).path).name), wheel['size'], 'sha256', wheel['hash'].split(':', 1)[1], wheel['size'])
    ocr = ocr_from_verified_wheel(wheel, write=True)
    for name, url in LICENSES.items():
        download(url, private_path('licenses', name), 100 * 1024)
    subprocess.run(install_command, cwd=RUNTIME, env=child_env, check=True)
    # 检查安装结果而不导入模型；真正兼容和推理另由 SelfCheck 执行。
    installed = inspect()
    if not all(item['matches'] for item in installed['packages'].values()):
        raise ValueError('视觉依赖安装版本与锁文件不符，尚不生成启用配置')
    if not env_path.exists():
        with env_path.open('x', encoding='utf-8', newline='\n') as stream:
            stream.write(env_text)
    manifest = {'uv_lock_sha256': digest(RUNTIME / 'uv.lock'), 'packages': LOCKED, 'models': installed['models'],
                'ocr': ocr, 'licenses': {name: {'source': url, 'sha256': digest(private_path('licenses', name))} for name, url in LICENSES.items()},
                'configured': True, 'validation': 'downloads_and_dependencies_only', 'inference_executed': False, 'quality_accepted': False}
    private_path('resource-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return {'mode': 'applied', 'runtime_config_path': str(env_path), 'manifest': manifest}


def session_providers(pixels, segment, ocr) -> dict:
    # RapidOCR 1.4.4 recognizer 使用 session.session；detector/classifier 使用 infer.session。
    return {'matting': pixels._session.inner_session.get_providers(), 'sam_encoder': segment._sam.encoder.get_providers(),
            'sam_decoder': segment._sam.decoder.get_providers(), 'ocr_det': ocr._engine.text_det.infer.session.get_providers(),
            'ocr_rec': ocr._engine.text_rec.session.session.get_providers(), 'ocr_cls': ocr._engine.text_cls.infer.session.get_providers()}


def self_check() -> dict:
    state = inspect()
    if not all(item['matches'] for item in state['packages'].values()) or not all(item['exists'] for item in state['models']):
        raise ValueError('缺少真实依赖/模型；SelfCheck不会安装、下载或使用mock')
    if not all(item['checksum_matches'] for item in state['models'][:3]):
        raise ValueError('U2Net/SAM官方校验不符，不初始化')
    _, wheel = lock_plan()
    ocr_from_verified_wheel(wheel, write=False)
    # 本进程拦截联网；任何模型库隐式下载都会失败。
    def block_network(event, _args):
        if event == 'socket.connect':
            raise RuntimeError('真实CV自检禁止联网、隐式模型下载和Provider调用')
    sys.addaudithook(block_network)
    os.environ.update(environment_values())
    os.environ['AIPICTURE_IMAGE_PROVIDER'] = 'dashscope'
    os.environ['AIPICTURE_ENABLED'] = 'false'
    sys.path.insert(0, str(RUNTIME))
    from PIL import Image, ImageDraw, ImageFont
    from aipicture_runtime.config import get_settings
    from aipicture_runtime.edits import pixels, segment, ocr
    from aipicture_runtime.eval.metrics import alpha_iou
    get_settings.cache_clear()
    output = private_path('selfcheck', uuid.uuid4().hex)
    output.mkdir(parents=True)
    source = (RUNTIME / 'aipicture_runtime/eval/dataset/images/product-white.png').read_bytes()
    expected = (RUNTIME / 'aipicture_runtime/eval/dataset/expect/product-alpha.png').read_bytes()
    started = time.perf_counter()
    matte = pixels.remove_background(source)
    (output / 'matting.png').write_bytes(matte)
    mask = segment.segment_points(source, [(0.5, 0.5)])
    (output / 'sam-selection.png').write_bytes(mask)
    text_image = Image.new('RGB', (1024, 256), 'white')
    font = ImageFont.truetype('C:/Windows/Fonts/arial.ttf', 64)
    ImageDraw.Draw(text_image).text((32, 64), 'AI Picture 2026', fill='black', font=font)
    text_image.save(output / 'ocr-input.png')
    buffer = io.BytesIO()
    text_image.save(buffer, 'PNG')
    recognized = ocr.detect_text(buffer.getvalue())
    providers = session_providers(pixels, segment, ocr)
    if any(value != ['CPUExecutionProvider'] for value in providers.values()):
        raise ValueError('真实自检未使用纯CPU执行，保留产物后停止')
    result = {'mode': 'real_cpu_selfcheck', 'output_directory': str(output), 'seconds': time.perf_counter() - started,
              'source_sha256': hashlib.sha256(source).hexdigest(),
              'providers': providers, 'matting_alpha_iou_source_fixture': alpha_iou(matte, expected),
              'sam_mask_nonempty': Image.open(io.BytesIO(mask)).convert('RGBA').getchannel('A').getbbox() is not None,
              'ocr_boxes': [box.__dict__ for box in recognized],
              'ocr_expected_matches': re.sub(r'\s+', '', ''.join(box.text for box in recognized)).upper() == 'AIPICTURE2026',
              'validation': 'runtime_initialization_and_smoke_only', 'inference_executed': True, 'quality_accepted': False,
              'note': '原合成样本和英文单行只作有限自检；需人工回读产物及真实商品/中文样本，不能据此通过拆层/局部修图验收。'}
    result['smoke_passed'] = result['sam_mask_nonempty'] and result['ocr_expected_matches'] and result['matting_alpha_iou_source_fixture'] >= 0.8
    (output / 'selfcheck.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true', help='已取得资源批准后，安装锁定cv依赖并下载固定模型；不会启用通道')
    parser.add_argument('--self-check', action='store_true', help='只对已准备模型做无网络CPU推理，写私有小规模自检产物')
    args = parser.parse_args(argv)
    try:
        result = apply() if args.apply else inspect()
        if args.self_check:
            result = self_check()
        print(json.dumps(result, ensure_ascii=True, indent=2))
        return 0 if not args.self_check or result['smoke_passed'] else 1
    except Exception as exc:
        print(json.dumps({'status': 'blocked', 'message': str(exc), 'quality_accepted': False}, ensure_ascii=True))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
