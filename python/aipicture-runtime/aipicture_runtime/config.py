"""独立计算进程配置：没有数据库、Cookie、JWT、队列或业务对象存储凭据。"""
from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AIPICTURE_", extra="ignore")
    enabled: bool = False
    offline_test: bool = False
    service_name: str = "aipicture-worker"
    allowed_caller_service: str = "aipicture-java"
    service_secret: str = ""
    center_url: str = "http://127.0.0.1:8080"
    api_port: int = 7405
    timestamp_skew_seconds: int = 120
    image_provider: str = "dashscope"
    matting_provider: str = "unconfigured"
    ocr_provider: str = "unconfigured"
    # 沿用源 Provider/模型名称，配置密钥不会自动启用真实调用。
    dashscope_api_key: str = ""
    dashscope_base_url: str = "https://dashscope.aliyuncs.com"
    text_to_image_model: str = "qwen-image-3.0-pro"
    image_edit_model: str = "qwen-image-edit-max"
    # 严格超分只加载显式提供的本地模型；原生成式增强可按配置继续使用。
    enhancement_provider: Literal["onnx", "image_edit"] = "onnx"
    super_resolution_models: dict[int, Path] = {}
    super_resolution_sha256: dict[int, str] = {}
    planner_model: str = "qwen-plus"
    provider_output_hosts: tuple[str, ...] = ()
    matting_model_path: Path | None = None
    sam_encoder_path: Path | None = None
    sam_decoder_path: Path | None = None
    ocr_det_model_path: Path | None = None
    ocr_rec_model_path: Path | None = None
    ocr_cls_model_path: Path | None = None
    max_asset_bytes: int = 20 * 1024 * 1024
    max_request_bytes: int = 70 * 1024 * 1024
    max_pixels: int = 50_000_000
    max_assets: int = 64
    max_outputs: int = 200
    max_output_bytes: int = 128 * 1024 * 1024
    # 仅保存传输副本，不保存或裁决正式任务状态；领取循环必须显式配置私有绝对目录。
    delivery_spool_directory: Path | None = None
    font_path: Path | None = None

    @model_validator(mode="after")
    def validate_test_modes(self):
        import re
        if self.delivery_spool_directory is not None and not self.delivery_spool_directory.is_absolute():
            raise ValueError("产物交付缓存须为私有绝对目录")
        if any(scale not in {2, 3, 4} for scale in self.super_resolution_models | self.super_resolution_sha256):
            raise ValueError("超分权重只接受 2、3、4 倍")
        if any(not re.fullmatch(r"[0-9a-fA-F]{64}", value) for value in self.super_resolution_sha256.values()):
            raise ValueError("超分权重指纹必须是完整 SHA256")
        if not self.offline_test and (self.image_provider == "mock" or self.matting_provider == "corner" or self.ocr_provider == "none"):
            raise ValueError("mock/corner/none 仅允许 AIPICTURE_OFFLINE_TEST=true 的离线测试")
        if self.timestamp_skew_seconds < 1 or self.timestamp_skew_seconds > 300:
            raise ValueError("签名时间窗口须为 1–300 秒")
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
