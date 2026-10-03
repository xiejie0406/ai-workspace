"""源参数模型的纯 DTO 版本；用于三端唯一 JSON Schema，无数据库依赖。"""
import enum
import uuid
from typing import Annotated, Literal
from pydantic import BaseModel as PydanticBaseModel, ConfigDict, Field, field_validator, model_validator
from aipicture_runtime.ratios import Ratio, DELIVERY_RATIOS

class BaseModel(PydanticBaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

MAX_PROMPT = 1500
MAX_REFERENCES = 3
MAX_BATCH_ASSETS = 20
ExportFormat = Literal["png", "jpg"]
BATCH_TOOLS = ("remove_background", "replace_background", "adjust_image", "upscale_image", "expand_canvas", "prepare_delivery_sizes")


class LayerRef(BaseModel):
    """改哪一层。与选区正交：选区限定区域，layer_id 限定图层。"""
    layer_id: str | None = Field(default=None, description='要修改的图层 id 或名字，如 background、subject、物体2。不填则作用在选区下最上层可见图像。')

class MaskRef(BaseModel):
    """改哪块区域。两个字段都由服务端填入，不暴露给模型。"""
    mask_asset_id: str | None = None
    revision: int | None = None

class GenerateIn(BaseModel):
    prompt: Annotated[str, Field(min_length=1, max_length=MAX_PROMPT)]
    ratio: Ratio = Ratio.SQUARE
    count: Annotated[int, Field(ge=1, le=6)] = 4
    negative_prompt: Annotated[str | None, Field(max_length=MAX_PROMPT)] = None
    seed: Annotated[int | None, Field(ge=0, le=2147483647)] = None
    reference_asset_ids: Annotated[list[uuid.UUID], Field(max_length=MAX_REFERENCES)] = []

    @field_validator('prompt')
    @classmethod
    def _require_prompt(cls, value: str) -> str:
        value = value.strip()
        if not value:
            raise ValueError('提示词不能为空')
        return value

    @field_validator('negative_prompt')
    @classmethod
    def _blank_to_none(cls, value: str | None) -> str | None:
        return value.strip() or None if value else None

class RemoveBackgroundIn(LayerRef):
    pass

class AdjustIn(LayerRef):
    brightness: float = Field(default=0, ge=-1, le=1)
    contrast: float = Field(default=0, ge=-1, le=1)
    highlights: float = Field(default=0, ge=-1, le=1)
    shadows: float = Field(default=0, ge=-1, le=1)
    temperature: float = Field(default=0, ge=-1, le=1)
    tint: float = Field(default=0, ge=-1, le=1)
    saturation: float = Field(default=0, ge=-1, le=1)
    vibrance: float = Field(default=0, ge=-1, le=1)
    sharpness: float = Field(default=0, ge=-1, le=1)
    clarity: float = Field(default=0, ge=-1, le=1)
    vignette: float = Field(default=0, ge=0, le=1)

class ReplaceBackgroundIn(BaseModel):
    prompt: str = Field(min_length=1, max_length=500)
    count: int = Field(default=1, ge=1, le=4)
    negative_prompt: str | None = None

class ExpandCanvasIn(BaseModel):
    ratio: Ratio
    prompt: str = Field(default='自然延伸画面边缘，保持主体完整', max_length=500)

class UpscaleImageIn(BaseModel):
    scale: int = Field(default=2, ge=2, le=4)

class RegionIn(LayerRef, MaskRef):
    prompt: str = Field(default='', max_length=500)

class ReplaceRegionIn(LayerRef, MaskRef):
    prompt: str = Field(min_length=1, max_length=500)

class SplitLayersIn(BaseModel):
    include_text: bool = False

class PromoteIn(MaskRef):
    name: str | None = Field(default=None, max_length=40)

class MarketingKind(enum.StrEnum):
    PRODUCT = 'product'
    SCENE = 'scene'
    MODEL = 'model'
    POSTER = 'poster'

class GenerateMarketingIn(BaseModel):
    kind: MarketingKind = Field(description='product 商品主图、scene 场景氛围图、model 模特上身、poster 促销海报')
    caption: str | None = Field(default=None, max_length=200, description='海报文案或补充要求')
    count: int = Field(default=1, ge=1, le=4)
    ratio: Ratio = Field(default=Ratio.SQUARE, description='输出比例，默认 1:1')

class PrepareDeliverySizesIn(BaseModel):
    ratios: list[Ratio] = Field(default_factory=lambda: list(DELIVERY_RATIOS), min_length=1, max_length=len(Ratio), description='要输出的投放比例。默认 1:1、4:5、9:16。主体不被裁切。')
    prompt: str = Field(default='自然延伸画面边缘，保持主体完整，不要裁切商品', max_length=500)

class BatchOpIn(BaseModel):
    tool: Literal['remove_background', 'replace_background', 'adjust_image', 'upscale_image', 'expand_canvas', 'prepare_delivery_sizes']
    params: dict = Field(default_factory=dict)

class BatchIn(BaseModel):
    asset_ids: list[uuid.UUID] = Field(min_length=1, max_length=MAX_BATCH_ASSETS)
    operations: list[BatchOpIn] = Field(min_length=1, max_length=len(BATCH_TOOLS))
    formats: list[ExportFormat] = Field(default_factory=lambda: ['png'], min_length=1, max_length=2)

class FlipIn(LayerRef):
    direction: Literal['horizontal', 'vertical']

class OpacityIn(LayerRef):
    opacity: float = Field(ge=0, le=1)

class VisibleIn(LayerRef):
    visible: bool

class TextIn(LayerRef):
    text: str = Field(max_length=500)
    font_size: float | None = Field(default=None, ge=8, le=400)
    fill: str | None = Field(default=None, pattern='^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})$')

class ScaleIn(LayerRef):
    factor: float | None = Field(default=None, gt=0, le=8)
    scale_x: float | None = Field(default=None, gt=0, le=8)
    scale_y: float | None = Field(default=None, gt=0, le=8)
    x: float | None = None
    y: float | None = None

    @model_validator(mode='after')
    def _need_target(self) -> 'ScaleIn':
        if self.factor is None and self.scale_x is None and (self.scale_y is None):
            raise ValueError('需要相对倍率或绝对缩放')
        return self

class RotateIn(LayerRef):
    angle: float | None = Field(default=None, ge=-360, le=360)
    rotation: float | None = Field(default=None, ge=-360, le=360)

    @model_validator(mode='after')
    def _need_angle(self) -> 'RotateIn':
        if self.angle is None and self.rotation is None:
            raise ValueError('需要旋转角度')
        return self

class ReorderIn(LayerRef):
    place: Literal['top', 'bottom', 'up', 'down']

class MoveIn(LayerRef):
    x: float | None = None
    y: float | None = None
    dx: float | None = None
    dy: float | None = None

    @model_validator(mode='after')
    def _need_delta(self) -> 'MoveIn':
        if self.x is None and self.y is None and (self.dx is None) and (self.dy is None):
            raise ValueError('需要坐标或位移')
        return self

class CropRect(BaseModel):
    x: float = Field(ge=0, le=1)
    y: float = Field(ge=0, le=1)
    width: float = Field(gt=0, le=1)
    height: float = Field(gt=0, le=1)

    @model_validator(mode='after')
    def _inside(self) -> 'CropRect':
        if self.x + self.width > 1.0001 or self.y + self.height > 1.0001:
            raise ValueError('裁剪框超出画布')
        return self

class CropIn(BaseModel):
    ratio: Ratio | None = None
    rect: CropRect | None = None

    @model_validator(mode='after')
    def _need_one(self) -> 'CropIn':
        if (self.ratio is None) == (self.rect is None):
            raise ValueError('裁剪需要比例或裁剪框之一')
        return self
