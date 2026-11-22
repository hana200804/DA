import httpx
import pytest

from scripts.benchmark_service import assess

MODEL = {
    "modelName": "yolov8",
    "modelVersion": "yolov8s-v1.0",
    "labelMapVersion": 1,
    "defaultConf": 0.5,
    "modelCard": {"weightsSha256": "test"},
}
DETECTION = {
    "signTypeCode": "P.123a",
    "confidence": 0.8,
    "box": {"x": 2, "y": 3, "width": 10, "height": 12},
}
IMAGE = (42, "val.jpg", b"image", 32, 24)


@pytest.mark.parametrize(
    ("status", "overrides", "error"),
    [
        (201, {}, "cần HTTP 200, nhận HTTP 201"),
        (200, {"detections": {}}, "detections phải là danh sách"),
        (200, {"inferenceMs": -1}, "inferenceMs phải là số nguyên không âm"),
        (200, {"inferenceMs": True}, "inferenceMs phải là số nguyên không âm"),
        (200, {"detections": [{**DETECTION, "box": {"x": 2}}]}, "các trường box phải là số"),
        (
            200,
            {"detections": [{**DETECTION, "box": {**DETECTION["box"], "height": 24}}]},
            "box ngoài biên ảnh",
        ),
    ],
)
def test_rejects_invalid_detect_response(status, overrides, error):
    def respond(_request):
        return httpx.Response(
            status, json={**MODEL, "inferenceMs": 5, "detections": [DETECTION], **overrides}
        )

    with httpx.Client(transport=httpx.MockTransport(respond), base_url="http://test") as client:
        with pytest.raises(ValueError, match=f"yolov8, ảnh 42 .*{error}"):
            assess(client, MODEL, [IMAGE], default=False, codes={"P.123a"})


def test_summarizes_only_recorded_samples():
    inference_times = iter([10, 20, 30, 40, 60])

    def respond(_request):
        return httpx.Response(
            200, json={**MODEL, "inferenceMs": next(inference_times), "detections": [DETECTION]}
        )

    with httpx.Client(transport=httpx.MockTransport(respond), base_url="http://test") as client:
        report, samples = assess(
            client, MODEL, [IMAGE, (43, *IMAGE[1:])], default=True, codes={"P.123a"}
        )

    assert [sample["image_id"] for sample in samples] == [42, 43]
    assert [sample["inferenceMs"] for sample in samples] == [40, 60]
    for sample in samples:
        assert sample["model"] == "yolov8"
        assert sample["status"] == 200
        assert sample["box_count"] == 1
    assert report["inference"]["p50_ms"] == 50
    assert report["inference"]["p95_ms"] == 59
    assert report["http"]["p50_ms"] == pytest.approx(
        (samples[0]["client_ms"] + samples[1]["client_ms"]) / 2
    )
