"""只持有算法和机器通道配置，无数据库、用户身份和正式存储凭据。"""
from functools import lru_cache
from typing import Literal
from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AIPPT_", env_file=".env", extra="ignore")
    center_url: str = "http://127.0.0.1:8080"
    worker_key: SecretStr = SecretStr("")
    worker_id: str = "aippt-local-worker"
    concurrency: int = Field(default=3, ge=1, le=16)
    heartbeat_seconds: float = Field(default=15, ge=1, le=60)
    claim_interval_seconds: float = Field(default=2, ge=0.1, le=60)
    task_timeout_seconds: float = Field(default=900, ge=10, le=7200)
    llm_api_key: str = ""
    llm_base_url: str = "https://api.deepseek.com"
    llm_model: str = "deepseek-v4-flash"
    llm_thinking_enabled: bool = False
    llm_timeout_seconds: float = 60
    llm_max_calls: int = Field(default=8, ge=1, le=8)
    llm_max_output_tokens: int = Field(default=8192, ge=256, le=32768)
    llm_max_retries: int = Field(default=2, ge=0, le=2)
    tool_rounds_max: int = Field(default=4, ge=1, le=4)
    repair_rounds_max: int = Field(default=1, ge=0, le=1)
    edit_history_max: int = Field(default=5, ge=0, le=5)
    image_enabled: bool = False
    image_provider: Literal["openai", "bailian"] = "openai"
    image_api_key: str = ""
    image_base_url: str = "https://api.openai.com/v1"
    image_model: str = "gpt-image-1"
    image_workspace_id: str = ""
    unsplash_access_key: str = ""
    image_timeout_seconds: float = 60
    outbound_allowed_hosts: list[str] = []
    max_upload_mb: int = 10
    max_image_mb: int = 8
    max_image_pixels: int = 40_000_000
    max_zip_entries: int = 2000
    max_zip_uncompressed_bytes: int = 100 * 1024 * 1024
    max_pdf_pages: int = 200
    parse_timeout_seconds: float = 60
    max_payload_bytes: int = 8 * 1024 * 1024
    max_tree_depth: int = 12
    max_tree_nodes: int = 512
    max_blocks_per_slide: int = 200
    font_manifest_path: str = ""
    fonts_dir: str = ""
    catalog_dir: str = ""

    @property
    def max_upload_bytes(self) -> int:
        return self.max_upload_mb * 1024 * 1024

    @property
    def max_image_bytes(self) -> int:
        return self.max_image_mb * 1024 * 1024


@lru_cache
def get_settings() -> Settings:
    return Settings()
