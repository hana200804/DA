"""API nhận diện nội bộ cho backend Spring Boot (docs/07 §7)."""

import asyncio
import json
import logging
import os
from collections.abc import Callable
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from pathlib import Path
from time import perf_counter
from uuid import uuid4

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, ValidationError
from starlette.concurrency import run_in_threadpool
from starlette.datastructures import UploadFile

from signa_ai.detection import Box, Detector
from signa_ai.labels import load_label_map

from .images import MAX_IMAGE_BYTES, decode_image, image_form
from .yolov8 import DEFAULT_CONFIDENCE, YoloV8Detector

logger = logging.getLogger(__name__)
MODEL_SLOTS = {"yolov5": 4, "yolov8": 4, "detr": 1}
QUEUE_SIZE = 8
ERROR_CODES = {
    400: "VALIDATION_ERROR",
    413: "PAYLOAD_TOO_LARGE",
    415: "UNSUPPORTED_MEDIA_TYPE",
    500: "INTERNAL_ERROR",
    503: "RECOGNITION_UNAVAILABLE",
}


class DetectForm(BaseModel):
    model_config = ConfigDict(extra="forbid")

    model: str
    conf: float | None = Field(default=None, ge=0, le=1)
    iou: float | None = Field(default=None, ge=0, le=1)


class ModelInfo(BaseModel):
    modelName: str
    modelVersion: str
    labelMapVersion: int


class ModelEntry(ModelInfo):
    defaultConf: float = Field(ge=0, le=1)
    modelCard: dict


class ModelList(BaseModel):
    models: list[ModelEntry]


class Prediction(BaseModel):
    box: Box
    signTypeCode: str
    confidence: float


class DetectResponse(ModelInfo):
    inferenceMs: int
    detections: list[Prediction]


@dataclass
class LoadedModel:
    detector: Detector
    card: dict
    default_conf: float
    pending: int = 0
    slots: asyncio.Semaphore = field(init=False)

    def __post_init__(self) -> None:
        self.slots = asyncio.Semaphore(MODEL_SLOTS[self.detector.name])

    @property
    def info(self) -> ModelInfo:
        return ModelInfo(
            modelName=self.detector.name,
            modelVersion=self.detector.version,
            labelMapVersion=self.detector.label_map_version,
        )

    def infer(self, upload: UploadFile, form: DetectForm) -> DetectResponse:
        image = decode_image(upload.file)
        start = perf_counter()
        try:
            conf = self.default_conf if form.conf is None else form.conf
            detections = self.detector.predict(image, conf=conf, iou=form.iou)
        except Exception as exc:
            logger.exception("Suy luận thất bại cho mô hình %s", self.detector.name)
            raise HTTPException(500, "Suy luận mô hình thất bại") from exc

        return DetectResponse(
            **self.info.model_dump(),
            inferenceMs=round((perf_counter() - start) * 1000),
            detections=[
                Prediction(
                    box=item.box, signTypeCode=item.sign_type_code, confidence=item.confidence
                )
                for item in detections
            ],
        )


def load_models() -> list[LoadedModel]:
    configured_path = os.getenv("SIGNA_YOLOV8_WEIGHTS")
    if not configured_path:
        logger.warning("Chưa cấu hình SIGNA_YOLOV8_WEIGHTS")
        return []

    try:
        weights = Path(configured_path).resolve()
        if weights.suffix != ".pt" or not weights.is_file():
            raise ValueError("SIGNA_YOLOV8_WEIGHTS phải trỏ đến checkpoint .pt cục bộ")
        labels = load_label_map()
        card = json.loads(weights.with_name("model_card.json").read_text(encoding="utf-8"))
        info = ModelInfo.model_validate(card, strict=True)
        if (
            info.modelName != YoloV8Detector.name
            or not info.modelVersion.strip()
            or info.labelMapVersion != labels.version
        ):
            raise ValueError("Thông tin mô hình trong model_card.json không khớp cấu hình")

        operating_point = card.get("operating_point")
        if operating_point is not None and not isinstance(operating_point, dict):
            raise ValueError("operating_point trong model_card.json phải là một đối tượng")
        default_conf = (operating_point or {}).get("threshold")
        if default_conf is None:
            default_conf = float(os.getenv("SIGNA_YOLOV8_CONF", str(DEFAULT_CONFIDENCE)))
        if (
            isinstance(default_conf, bool)
            or not isinstance(default_conf, (float, int))
            or not 0 <= default_conf <= 1
        ):
            raise ValueError("Ngưỡng vận hành YOLOv8 phải là số trong khoảng [0, 1]")

        detector = YoloV8Detector(weights, labels, info.modelVersion, os.getenv("SIGNA_DEVICE"))
        return [LoadedModel(detector, card, default_conf)]
    except Exception:
        logger.exception("Không thể nạp mô hình yolov8")
        return []


def create_app(loader: Callable[[], list[LoadedModel]] = load_models) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI):
        app.state.models = {item.detector.name: item for item in loader()}
        yield

    app = FastAPI(title="Signa Recognition", lifespan=lifespan)

    @app.middleware("http")
    async def trace_request(request: Request, call_next):
        request.state.request_id = request.headers.get("X-Request-Id") or str(uuid4())
        response = await call_next(request)
        response.headers["X-Request-Id"] = request.state.request_id
        return response

    @app.exception_handler(HTTPException)
    async def error_response(request: Request, exc: HTTPException) -> JSONResponse:
        return JSONResponse(
            {
                "code": ERROR_CODES[exc.status_code],
                "message": exc.detail,
                "requestId": request.state.request_id,
            },
            status_code=exc.status_code,
            headers=exc.headers,
        )

    @app.get("/healthz", responses={503: {"description": "Thiếu mô hình"}})
    async def healthz(request: Request) -> dict[str, str]:
        missing = MODEL_SLOTS.keys() - request.app.state.models.keys()
        if missing:
            raise HTTPException(503, f"Chưa nạp mô hình: {', '.join(sorted(missing))}")
        return {"status": "ok"}

    @app.get("/v1/models")
    async def models(request: Request) -> ModelList:
        loaded = request.app.state.models.values()
        return ModelList(
            models=[
                ModelEntry(
                    **item.info.model_dump(), defaultConf=item.default_conf, modelCard=item.card
                )
                for item in loaded
            ]
        )

    form_schema = DetectForm.model_json_schema()
    form_schema["properties"]["image"] = {"type": "string", "format": "binary"}
    form_schema["properties"]["model"]["enum"] = list(MODEL_SLOTS)
    form_schema["required"].append("image")

    @app.post(
        "/v1/detect",
        openapi_extra={
            "requestBody": {
                "required": True,
                "content": {
                    "multipart/form-data": {"schema": form_schema},
                },
            }
        },
        responses={status: {"description": code} for status, code in ERROR_CODES.items()},
    )
    async def detect(request: Request) -> DetectResponse:
        async with image_form(request) as form:
            image = form.get("image")
            if not isinstance(image, UploadFile):
                raise HTTPException(400, "Cần trường ảnh image")
            if image.size > MAX_IMAGE_BYTES:
                raise HTTPException(413, "Ảnh nhận diện tối đa 10 MB")

            try:
                params = DetectForm.model_validate(
                    {key: value for key, value in form.items() if key != "image"}
                )
            except ValidationError as exc:
                raise HTTPException(400, "Cần model hợp lệ; conf và iou phải thuộc [0, 1]") from exc
            if params.model not in MODEL_SLOTS:
                raise HTTPException(400, "model phải là yolov5, yolov8 hoặc detr")

            loaded = request.app.state.models.get(params.model)
            if loaded is None:
                raise HTTPException(503, f"Mô hình {params.model} chưa được nạp")
            if loaded.pending >= MODEL_SLOTS[params.model] + QUEUE_SIZE:
                raise HTTPException(503, "Hàng chờ nhận diện đã đầy", headers={"Retry-After": "1"})

            loaded.pending += 1
            try:
                async with loaded.slots:
                    return await run_in_threadpool(loaded.infer, image, params)
            finally:
                loaded.pending -= 1

    return app


app = create_app()
