import asyncio
import tempfile
from contextlib import asynccontextmanager
from io import BytesIO
from threading import Event

import httpx
import pytest
from PIL import Image

from service import images
from service.app import LoadedModel, create_app
from signa_ai.detection import Box, Detection

pytestmark = pytest.mark.anyio


class FakeDetector:
    version = "yolov8n-v1.1-temp"
    label_map_version = 1

    def __init__(self, name="yolov8"):
        self.name = name

    def predict(self, image, *, conf=None, iou=None):
        assert image.shape == (24, 32, 3)
        assert image.dtype.name == "uint8"
        self.thresholds = (conf, iou)
        return [Detection(Box(2, 3, 10, 12), "P.123a", 0.85)]


@pytest.fixture
def loaded():
    return LoadedModel(FakeDetector(), {"status": "temporary", "datasetVersion": "v1.1"})


@pytest.fixture
def anyio_backend():
    return "asyncio"


@asynccontextmanager
async def running(app):
    async with (
        app.router.lifespan_context(app),
        httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client,
    ):
        yield client


@pytest.fixture
async def client(loaded):
    async with running(create_app(lambda: [loaded])) as client:
        yield client


def image_file(format="PNG", *, padding=0):
    buffer = BytesIO()
    Image.new("RGB", (32, 24), (230, 40, 10)).save(buffer, format=format)
    return ("val.img", buffer.getvalue() + b"\0" * padding, "application/octet-stream")


@pytest.mark.parametrize("format", ["JPEG", "PNG", "WEBP"])
async def test_detect_returns_original_pixel_box_and_versions(client, loaded, format):
    response = await client.post(
        "/v1/detect",
        data={"model": "yolov8", "conf": "0", "iou": "0.5"},
        files={"image": image_file(format)},
        headers={"X-Request-Id": "test-request"},
    )
    assert response.status_code == 200
    assert response.headers["X-Request-Id"] == "test-request"
    body = response.json()
    assert body.pop("inferenceMs") >= 0
    assert body == {
        "modelName": "yolov8",
        "modelVersion": "yolov8n-v1.1-temp",
        "labelMapVersion": 1,
        "detections": [
            {
                "box": {"x": 2, "y": 3, "width": 10, "height": 12},
                "signTypeCode": "P.123a",
                "confidence": 0.85,
            }
        ],
    }
    assert loaded.detector.thresholds == (0.0, 0.5)


async def test_available_models_and_partial_readiness(client):
    assert (await client.get("/v1/models")).json() == {
        "models": [
            {
                "modelName": "yolov8",
                "modelVersion": "yolov8n-v1.1-temp",
                "labelMapVersion": 1,
                "modelCard": {"status": "temporary", "datasetVersion": "v1.1"},
            }
        ]
    }
    health = await client.get("/healthz")
    assert health.status_code == 503
    assert "detr, yolov5" in health.json()["message"]
    response = await client.post(
        "/v1/detect", data={"model": "detr"}, files={"image": image_file()}
    )
    assert response.status_code == 503
    assert response.json()["code"] == "RECOGNITION_UNAVAILABLE"
    assert "detr" in response.json()["message"]


async def test_health_requires_all_three_models():
    def load_all():
        return [LoadedModel(FakeDetector(name), {}) for name in ("yolov5", "yolov8", "detr")]

    async with running(create_app(load_all)) as client:
        response = await client.get("/healthz")
        assert response.status_code == 200
        assert response.json() == {"status": "ok"}


@pytest.mark.parametrize("configured", [False, True])
async def test_starts_without_existing_weights(monkeypatch, tmp_path, configured):
    monkeypatch.delenv("SIGNA_YOLOV8_WEIGHTS", raising=False)
    if configured:
        monkeypatch.setenv("SIGNA_YOLOV8_WEIGHTS", str(tmp_path / "best.pt"))

    async with running(create_app()) as client:
        assert (await client.get("/healthz")).status_code == 503
        assert (await client.get("/v1/models")).json() == {"models": []}


@pytest.mark.parametrize(
    "data",
    [{}, {"model": "unknown"}, {"model": "yolov8", "conf": "nan"}, {"model": "yolov8", "iou": "2"}],
)
async def test_invalid_model_and_thresholds(client, data):
    response = await client.post("/v1/detect", data=data, files={"image": image_file()})
    assert response.status_code == 400
    assert response.json()["code"] == "VALIDATION_ERROR"
    assert response.json()["requestId"] == response.headers["X-Request-Id"]


async def test_rejects_wrong_media_type_and_missing_or_corrupt_parts(client):
    assert (await client.post("/v1/detect", json={})).status_code == 415
    missing = await client.post("/v1/detect", files={"model": (None, "yolov8")})
    assert missing.status_code == 400
    truncated = await client.post(
        "/v1/detect",
        headers={"Content-Type": "multipart/form-data; boundary=signa"},
        content=b'--signa\r\nContent-Disposition: form-data; name="model"\r\n\r\nyolov8',
    )
    assert truncated.status_code == 400

    for file in [image_file("GIF"), ("fake.jpg", b"not an image", "image/jpeg")]:
        response = await client.post("/v1/detect", data={"model": "yolov8"}, files={"image": file})
        assert response.status_code == 415
        assert response.json()["code"] == "UNSUPPORTED_MEDIA_TYPE"


async def test_large_valid_upload_stays_in_memory(client, monkeypatch):
    def fail_if_file_is_created(*args, **kwargs):
        pytest.fail("Parser đã ghi ảnh nhận diện ra đĩa")

    monkeypatch.setattr(tempfile, "TemporaryFile", fail_if_file_is_created)
    name, png, content_type = image_file()
    at_limit = png.ljust(images.MAX_IMAGE_BYTES, b"\0")
    response = await client.post(
        "/v1/detect", data={"model": "yolov8"}, files={"image": (name, at_limit, content_type)}
    )
    assert response.status_code == 200
    for size in (images.MAX_IMAGE_BYTES + 1, images.MAX_REQUEST_BYTES + 1):
        response = await client.post(
            "/v1/detect",
            data={"model": "yolov8"},
            files={"image": (name, png.ljust(size, b"\0"), content_type)},
        )
        assert response.status_code == 413
        assert response.json()["code"] == "PAYLOAD_TOO_LARGE"


async def test_rejects_image_with_too_many_pixels(client, monkeypatch):
    monkeypatch.setattr(images, "MAX_IMAGE_PIXELS", 100)
    response = await client.post(
        "/v1/detect", data={"model": "yolov8"}, files={"image": image_file()}
    )
    assert response.status_code == 413


async def test_queue_returns_retry_after_and_drains(client, loaded, monkeypatch):
    release = Event()
    predict = loaded.detector.predict

    def blocked_predict(*args, **kwargs):
        assert release.wait(timeout=10)
        return predict(*args, **kwargs)

    monkeypatch.setattr(loaded.detector, "predict", blocked_predict)
    request = {"data": {"model": "yolov8"}, "files": {"image": image_file()}}
    active = [asyncio.create_task(client.post("/v1/detect", **request)) for _ in range(12)]
    try:
        async with asyncio.timeout(5):
            while loaded.pending < 12:
                await asyncio.sleep(0.001)
        busy = await client.post("/v1/detect", **request)
        assert busy.status_code == 503
        assert busy.headers["Retry-After"] == "1"
    finally:
        release.set()
        responses = await asyncio.gather(*active)
    assert all(response.status_code == 200 for response in responses)
    assert (await client.post("/v1/detect", **request)).status_code == 200
