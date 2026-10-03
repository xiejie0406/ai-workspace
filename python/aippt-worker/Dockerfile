# 构建上下文必须是本 Worker 根目录；基础镜像由运行方核定后显式提供。
# 将已批准的 Linux Python 3.12 完整引用拆成镜像名与 sha256 digest 两参数。
# FROM 固定要求 digest；缺参数/格式错误时不能回落到未固定的 tag。
ARG PYTHON_BASE_IMAGE
ARG PYTHON_BASE_DIGEST
FROM ${PYTHON_BASE_IMAGE}@sha256:${PYTHON_BASE_DIGEST}

ARG PYTHON_BASE_IMAGE
ARG PYTHON_BASE_DIGEST
USER 0:0
RUN ["python", "-c", "import os,re,sys; ref=os.environ.get('PYTHON_BASE_IMAGE','')+'@sha256:'+os.environ.get('PYTHON_BASE_DIGEST',''); valid=re.fullmatch(r'[^@\\s]+@sha256:[0-9a-f]{64}',ref); valid or sys.exit('An explicitly approved image and complete sha256 digest are required'); (sys.version_info[:2]==(3,12) and sys.platform=='linux') or sys.exit('An approved Linux Python 3.12 base is required')"]

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    AIPPT_CATALOG_DIR=/opt/aippt-catalog/catalog \
    AIPPT_FONTS_DIR=/opt/aippt-fonts \
    AIPPT_FONT_MANIFEST_PATH=/opt/aippt-fonts/manifest.json \
    AIPPT_IMAGE_ENABLED=false

WORKDIR /opt/aippt-worker
COPY requirements.lock ./requirements.lock
# 锁文件是完整依赖闭包；只用预构建 wheel，不解析额外依赖或安装系统包。
RUN ["python", "-m", "pip", "install", "--disable-pip-version-check", "--no-cache-dir", "--no-compile", "--no-deps", "--only-binary=:all:", "-r", "requirements.lock"]

COPY app/ ./app/
COPY regression/corpus/ ./regression/corpus/
# 源码归 root 所有并允许最终非 root 用户读取；不引入用户管理工具。
RUN ["python", "-c", "from pathlib import Path; root=Path('/opt/aippt-worker'); root.chmod(0o755); [(p.chmod(0o755 if p.is_dir() else 0o644)) for p in root.rglob('*')]"]

USER 10001:10001
# 运行方挂载同版本 catalog 及其父目录 manifest、字体完整目录（均 readonly）。
# 默认仅本地 health；真实领取任务必须在核定通道下显式追加 run。
ENTRYPOINT ["python", "-m", "app"]
CMD ["health"]
