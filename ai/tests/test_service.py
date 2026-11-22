import asyncio
import hashlib
import json
import tempfile
from contextlib import asynccontextmanager
from io import BytesIO
from threading import Event, Lock

import httpx
import pytest
from PIL import Image

from service import images
from service.app import MODEL_SLOTS, QUEUE_SIZE, LoadedModel, create_app
from signa_ai.detection import Box, Detection

pytestmark = pytest.mark.anyio


class FakeDetector:
    label_map_version = 1

    def __init__(self, name="yolov8"):
        self.name = name
        self.version = {
            "yolov5": "yolov5s-v1.1",
            "yolov8": "yolov8n-v1.1-temp",
            "detr": "detr-r50-v1.1",
        }[name]

    def predict(self, image, *, conf=None, iou=None):
        assert image.shape == (24, 32, 3)
        assert image.dtype.name == "uint8"
        self.thresholds = (conf, iou)
        return [Detection(Box(2, 3, 10, 12), "P.123a", 0.85)]


@pytest.fixture
def loaded():
    return LoadedModel(FakeDetector(), {"status": "temporary", "datasetVersion": "v1.1"}, 0.40)


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
        "defaultModel": "yolov8",
        "models": [
            {
                "modelName": "yolov8",
                "modelVersion": "yolov8n-v1.1-temp",
                "labelMapVersion": 1,
                "defaultConf": 0.40,
                "modelCard": {"status": "temporary", "datasetVersion": "v1.1"},
            }
        ],
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
        return [LoadedModel(FakeDetector(name), {}, 0.40) for name in ("yolov5", "yolov8", "detr")]

    async with running(create_app(load_all)) as client:
        response = await client.get("/healthz")
        assert response.status_code == 200
        assert response.json() == {"status": "ok"}


async def test_model_selection_and_default(monkeypatch):
    names = ("yolov5", "yolov8", "detr")
    loaded = [LoadedModel(FakeDetector(name), {}, 0.40) for name in names]
    monkeypatch.delenv("SIGNA_DEFAULT_MODEL", raising=False)
    async with running(create_app(lambda: loaded)) as client:
        for item in loaded:
            detector = item.detector
            for selector in (detector.name, detector.version):
                response = await client.post(
                    "/v1/detect", data={"model": selector}, files={"image": image_file()}
                )
                assert response.status_code == 200
                assert response.json()["modelName"] == detector.name
                assert response.json()["modelVersion"] == detector.version
        response = await client.post("/v1/detect", files={"image": image_file()})
        assert response.json()["modelName"] == "yolov8"

    monkeypatch.setenv("SIGNA_DEFAULT_MODEL", "detr-r50-v1.1")
    async with running(create_app(lambda: loaded)) as client:
        assert (await client.get("/v1/models")).json()["defaultModel"] == "detr"
        response = await client.post("/v1/detect", files={"image": image_file()})
        assert response.json()["modelName"] == "detr"

    # Họ mô hình hợp lệ nhưng chưa nạp: dịch vụ vẫn chạy, /healthz báo thiếu.
    monkeypatch.setenv("SIGNA_DEFAULT_MODEL", "detr")
    async with running(create_app(lambda: loaded[:2])) as client:
        assert (await client.get("/v1/models")).json()["defaultModel"] == "detr"
        assert (await client.get("/healthz")).status_code == 503


@pytest.mark.parametrize("selector", ["yolov7", "detr-r50-v9.9", "YOLOV8"])
async def test_invalid_default_model_stops_startup(monkeypatch, selector):
    loaded = [LoadedModel(FakeDetector(name), {}, 0.40) for name in ("yolov5", "detr")]
    monkeypatch.setenv("SIGNA_DEFAULT_MODEL", selector)
    with pytest.raises(ValueError, match="SIGNA_DEFAULT_MODEL"):
        async with running(create_app(lambda: loaded)):
            pass


async def test_loads_configured_models_and_keeps_others_on_failure(monkeypatch, tmp_path):
    cards = {}
    loaded_names = []
    monkeypatch.setenv("SIGNA_DEVICE", "cpu")
    monkeypatch.setenv("SIGNA_DEFAULT_MODEL", "yolov8")
    for name, adapter in [
        ("yolov5", "YoloV5Detector"),
        ("yolov8", "YoloV8Detector"),
        ("detr", "DetrDetector"),
    ]:
        folder = tmp_path / name
        folder.mkdir()
        weights = folder / "best.pt"
        weights.touch()
        cards[name] = card = {
            "modelName": name,
            "modelVersion": f"{name}-test",
            "labelMapVersion": 1,
            "weightsSha256": hashlib.sha256(b"").hexdigest(),
            "operating_point": {"threshold": 0.5},
        }
        (folder / "model_card.json").write_text(json.dumps(card), encoding="utf-8")
        monkeypatch.setenv(f"SIGNA_{name.upper()}_WEIGHTS", str(weights))

        class FakeAdapter(FakeDetector):
            def __init__(self, path, labels, version, device):
                assert path.parent.name == self.name
                assert labels.version == 1
                assert device == "cpu"
                assert version == f"{self.name}-test"
                loaded_names.append(self.name)
                if self.name == "yolov8":
                    raise ValueError("checkpoint không hợp lệ")
                self.version = version

        FakeAdapter.name = name
        monkeypatch.setattr(f"service.app.{adapter}", FakeAdapter)

    async with running(create_app()) as client:
        assert loaded_names == ["yolov5", "yolov8", "detr"]
        models = (await client.get("/v1/models")).json()["models"]
        assert [item["modelCard"] for item in models] == [cards["yolov5"], cards["detr"]]
        health = await client.get("/healthz")
        assert health.status_code == 503
        assert health.json()["message"] == "Chưa nạp mô hình: yolov8"
        assert (await client.post("/v1/detect", files={"image": image_file()})).status_code == 503


@pytest.mark.parametrize("configured", [False, True])
async def test_starts_without_existing_weights(monkeypatch, tmp_path, configured):
    monkeypatch.delenv("SIGNA_YOLOV8_WEIGHTS", raising=False)
    if configured:
        monkeypatch.setenv("SIGNA_YOLOV8_WEIGHTS", str(tmp_path / "best.pt"))

    async with running(create_app()) as client:
        assert (await client.get("/healthz")).status_code == 503
        assert (await client.get("/v1/models")).json() == {"defaultModel": "yolov8", "models": []}


async def test_refuses_startup_with_wrong_checkpoint_hash(monkeypatch, tmp_path):
    class UnloadableAdapter:
        name = "yolov8"

        def __init__(self, *_args):
            pytest.fail("Adapter đã đọc checkpoint sai")

    weights = tmp_path / "best.pt"
    weights.write_bytes(b"wrong checkpoint")
    card = {
        "modelName": "yolov8",
        "modelVersion": "yolov8s-v1.0",
        "labelMapVersion": 1,
        "weightsSha256": hashlib.sha256(b"expected checkpoint").hexdigest(),
    }
    weights.with_name("model_card.json").write_text(json.dumps(card), encoding="utf-8")
    monkeypatch.setenv("SIGNA_YOLOV8_WEIGHTS", str(weights))
    monkeypatch.setattr("service.app.YoloV8Detector", UnloadableAdapter)

    with pytest.raises(ValueError, match="SHA-256 của SIGNA_YOLOV8_WEIGHTS không khớp"):
        async with running(create_app()):
            pytest.fail("Dịch vụ đã khởi động với checkpoint sai")


@pytest.mark.parametrize(
    ("card_conf", "env_conf", "expected"),
    [
        (None, None, 0.40),
        (None, "0.55", 0.55),
        (0.401663, "0.55", 0.401663),
        (0, "invalid", 0),
        (1.01, "0.55", None),
        (None, "NaN", None),
    ],
)
async def test_operating_confidence(monkeypatch, tmp_path, card_conf, env_conf, expected):
    class FakeYoloV8Detector(FakeDetector):
        name = "yolov8"

        def __init__(self, *_args):
            super().__init__()

    weights = tmp_path / "best.pt"
    weights.touch()
    card = {
        "modelName": "yolov8",
        "modelVersion": "yolov8n-v1.1-temp",
        "labelMapVersion": 1,
        "weightsSha256": hashlib.sha256(b"").hexdigest(),
    }
    if card_conf is not None:
        card["operating_point"] = {"threshold": card_conf}
    weights.with_name("model_card.json").write_text(json.dumps(card), encoding="utf-8")
    monkeypatch.setattr("service.app.YoloV8Detector", FakeYoloV8Detector)
    monkeypatch.setenv("SIGNA_YOLOV8_WEIGHTS", str(weights))
    monkeypatch.delenv("SIGNA_YOLOV8_CONF", raising=False)
    if env_conf is not None:
        monkeypatch.setenv("SIGNA_YOLOV8_CONF", env_conf)

    app = create_app()
    async with running(app) as client:
        models = (await client.get("/v1/models")).json()["models"]
        if expected is None:
            assert models == []
            return
        assert models[0]["defaultConf"] == expected
        response = await client.post(
            "/v1/detect", data={"model": "yolov8"}, files={"image": image_file()}
        )
        assert response.status_code == 200
        assert app.state.models["yolov8"].detector.thresholds == (expected, None)


@pytest.mark.parametrize(
    "data",
    [
        {"model": ""},
        {"model": "unknown"},
        {"model": "yolov8", "conf": "nan"},
        {"model": "yolov8", "iou": "2"},
    ],
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


def test_decode_image_applies_exif_orientation():
    buffer = BytesIO()
    exif = Image.Exif()
    exif[0x0112] = 6  # Orientation: xoay 90° theo chiều kim đồng hồ khi hiển thị
    source = Image.new("RGB", (32, 24), (230, 40, 10))
    source.paste((0, 0, 255), (0, 0, 8, 8))
    source.save(buffer, format="JPEG", exif=exif, quality=100)
    pixels = images.decode_image(BytesIO(buffer.getvalue()))
    assert pixels.shape == (32, 24, 3)
    # Góc trên trái của ảnh gốc sang góc trên phải sau khi xoay.
    assert pixels[3, -4, 2] > 200 and pixels[3, -4, 0] < 60
    assert pixels[3, 3, 0] > 200


async def test_rejects_tiny_image(client):
    buffer = BytesIO()
    Image.new("RGB", (32, images.MIN_IMAGE_EDGE - 1)).save(buffer, format="PNG")
    response = await client.post(
        "/v1/detect",
        data={"model": "yolov8"},
        files={"image": ("tiny.png", buffer.getvalue(), "image/png")},
    )
    assert response.status_code == 400
    assert response.json()["code"] == "VALIDATION_ERROR"


async def test_rejects_image_with_too_many_pixels(client, monkeypatch):
    monkeypatch.setattr(images, "MAX_IMAGE_PIXELS", 100)
    response = await client.post(
        "/v1/detect", data={"model": "yolov8"}, files={"image": image_file()}
    )
    assert response.status_code == 413


async def wait_until(condition, seconds=5):
    async with asyncio.timeout(seconds):
        while not condition():
            await asyncio.sleep(0.001)


@pytest.mark.parametrize(("name", "slots"), [("yolov5", 4), ("yolov8", 4), ("detr", 1)])
async def test_semaphore_limits_concurrent_inference_and_queue(name, slots, monkeypatch):
    assert MODEL_SLOTS[name] == slots
    release = Event()
    lock = Lock()
    running_now = peak = 0
    blocked = LoadedModel(FakeDetector(name), {}, 0.40)
    predict = blocked.detector.predict

    def blocked_predict(*args, **kwargs):
        nonlocal running_now, peak
        with lock:
            running_now += 1
            peak = max(peak, running_now)
        try:
            assert release.wait(timeout=10)
            return predict(*args, **kwargs)
        finally:
            with lock:
                running_now -= 1

    monkeypatch.setattr(blocked.detector, "predict", blocked_predict)
    other = "yolov8" if name != "yolov8" else "yolov5"
    available = LoadedModel(FakeDetector(other), {}, 0.40)
    request = {"data": {"model": name}, "files": {"image": image_file()}}
    capacity = slots + QUEUE_SIZE
    async with running(create_app(lambda: [blocked, available])) as queue_client:
        active = [
            asyncio.create_task(queue_client.post("/v1/detect", **request)) for _ in range(capacity)
        ]
        try:
            await wait_until(lambda: blocked.pending == capacity and running_now == slots)
            # Cho các request đang chờ cơ hội vượt semaphore nếu nó không chặn.
            await asyncio.sleep(0.05)
            assert peak == slots
            request["data"] = {"model": blocked.detector.version}
            busy = await queue_client.post("/v1/detect", **request)
            assert busy.status_code == 503
            assert busy.json()["code"] == "RECOGNITION_UNAVAILABLE"
            assert busy.headers["Retry-After"] == "1"
            response = await queue_client.post(
                "/v1/detect", data={"model": other}, files={"image": image_file()}
            )
            assert response.status_code == 200
        finally:
            release.set()
            responses = await asyncio.gather(*active)
        assert all(response.status_code == 200 for response in responses)
        assert peak == slots and blocked.pending == 0
        assert (await queue_client.post("/v1/detect", **request)).status_code == 200


@pytest.mark.parametrize("name", ["yolov8", "detr"])
async def test_inference_error_returns_500_and_releases_slot(name, monkeypatch):
    loaded = LoadedModel(FakeDetector(name), {}, 0.40)
    predict = loaded.detector.predict

    def broken(*args, **kwargs):
        raise RuntimeError("CUDA out of memory")

    monkeypatch.setattr(loaded.detector, "predict", broken)
    request = {"data": {"model": name}, "files": {"image": image_file()}}
    attempts = MODEL_SLOTS[name] + QUEUE_SIZE + 1
    # Slot bị giữ lại sẽ làm request sau treo, nên cả ca thử có giới hạn thời gian.
    async with asyncio.timeout(5), running(create_app(lambda: [loaded])) as client:
        failed = await asyncio.gather(
            *(client.post("/v1/detect", **request) for _ in range(attempts))
        )
        assert {response.status_code for response in failed} <= {500, 503}
        assert any(response.status_code == 500 for response in failed)
        errors = [response.json() for response in failed if response.status_code == 500]
        assert {error["code"] for error in errors} == {"INTERNAL_ERROR"}
        assert loaded.pending == 0

        monkeypatch.setattr(loaded.detector, "predict", predict)
        responses = await asyncio.gather(
            *(client.post("/v1/detect", **request) for _ in range(MODEL_SLOTS[name]))
        )
        assert all(response.status_code == 200 for response in responses)
        assert loaded.pending == 0
