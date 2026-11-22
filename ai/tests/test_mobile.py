import json

import numpy as np
import pytest

from signa_ai.detection import to_coco_predictions
from signa_ai.labels import load_label_map
from signa_ai.mobile import decode_tflite, letterbox, write_labels_json


@pytest.mark.parametrize("size", [480, 640])
def test_letterbox_and_inverse(size):
    image = np.full((3, 7, 3), [0, 20, 255], dtype=np.uint8)
    padded, transform = letterbox(image, size)
    bottom = transform.top + transform.resized_height
    assert padded.shape == (size, size, 3)
    np.testing.assert_array_equal(padded[0, 0], [114, 114, 114])
    np.testing.assert_array_equal(padded[transform.top, 0], [0, 20, 255])
    np.testing.assert_array_equal(padded[bottom, 0], [114, 114, 114])
    assert size - bottom - transform.top in (0, 1)
    boxes = np.array([[0, transform.top, size, bottom], [-2, 0, size + 4, size]])
    np.testing.assert_allclose(transform.to_original(boxes), [[0, 0, 7, 3]] * 2)


def test_letterbox_interpolates_pixel_centers():
    image = np.array([[[0, 0, 0], [100, 100, 100]]], dtype=np.uint8)
    padded, _ = letterbox(image, 4)
    assert padded[:, :, 0].tolist() == [
        [114] * 4,
        [0, 25, 75, 100],
        [0, 25, 75, 100],
        [114] * 4,
    ]


@pytest.mark.parametrize(
    ("model", "dtype", "zero_point"),
    [
        ("yolov8", np.float16, 0),
        ("yolov8", np.int8, -100),
        ("yolov5", np.float32, 0),
        ("yolov5", np.uint8, 20),
    ],
)
def test_decode_nms_and_coco_ids(model, dtype, zero_point):
    labels = load_label_map()
    _, transform = letterbox(np.zeros((240, 480, 3), dtype=np.uint8), 480)
    v5 = model == "yolov5"
    class_start = 5 if v5 else 4
    rows = np.zeros((5, class_start + len(labels)), dtype=np.float32)
    rows[:, :4] = [0.5, 0.5, 0.5, 0.25]
    rows[0, class_start + 45] = 0.75
    rows[1, class_start + 45] = 0.50  # Trùng box và lớp.
    rows[2, class_start + 2] = 0.50  # Trùng box, khác lớp.
    rows[3, class_start + 45] = 0.125  # Dưới ngưỡng confidence.
    rows[4, class_start + 3] = 0.875  # Nằm hoàn toàn trong đệm.
    rows[4, 1] = 0.0625
    rows[4, 3] = 0.0625
    if v5:
        rows[:, 4] = 0.5

    scale = 1 / 64
    tensor = rows[None] if v5 else rows.T[None]
    if np.issubdtype(dtype, np.integer):
        tensor = np.rint(tensor / scale + zero_point)
    detections = decode_tflite(
        tensor.astype(dtype),
        transform,
        labels,
        model=model,
        quantization=(scale, zero_point),
        conf=0.2,
    )
    predictions = to_coco_predictions(81, detections, labels)
    assert [p["category_id"] for p in predictions] == [45, 2]
    assert [p["image_id"] for p in predictions] == [81, 81]
    assert predictions[0]["bbox"] == pytest.approx([120, 60, 240, 120])
    assert [d.confidence for d in detections] == pytest.approx(
        [0.375, 0.25] if v5 else [0.75, 0.50]
    )


def test_decode_runs_nms_before_clipping_boxes_at_image_edge():
    labels = load_label_map()
    _, transform = letterbox(np.zeros((100, 100, 3), dtype=np.uint8), 100)
    output = np.zeros((1, 4 + len(labels), 2), dtype=np.float32)
    # Hai box cùng lớp có biên x [-30, 50] và [0, 50]. IoU 0,625 trước cắt, 1 sau cắt.
    output[0, :5, 0] = [0.10, 0.3, 0.8, 0.2, 0.9]
    output[0, :5, 1] = [0.25, 0.3, 0.5, 0.2, 0.8]

    detections = decode_tflite(output, transform, labels, model="yolov8")

    assert [d.confidence for d in detections] == pytest.approx([0.9, 0.8])
    for detection in detections:
        box = detection.box
        assert (box.x, box.y, box.width, box.height) == pytest.approx((0, 20, 50, 20))


def test_decode_rejects_missing_quantization_and_wrong_shape():
    labels = load_label_map()
    _, transform = letterbox(np.zeros((4, 4, 3), dtype=np.uint8), 4)
    with pytest.raises(ValueError, match="scale"):
        decode_tflite(np.zeros((1, 50, 1), dtype=np.int8), transform, labels, model="yolov8")
    with pytest.raises(ValueError, match="kích thước"):
        decode_tflite(np.zeros((1, 1, 50)), transform, labels, model="yolov8")


def test_labels_json_matches_all_46_classes(tmp_path):
    path = tmp_path / "labels.json"
    write_labels_json(path)
    data = json.loads(path.read_text(encoding="utf-8"))
    labels = load_label_map()
    assert data["version"] == labels.version
    assert {key: value["name"] for key, value in data["categories"].items()} == labels.categories
    assert [row["id"] for row in data["classes"]] == list(range(46))
    for row in data["classes"]:
        sign = labels.by_id(row["id"])
        assert row == dict(
            id=sign.id,
            code=sign.code,
            category=sign.category,
            name=sign.name,
            speed_kmh=sign.speed_kmh,
        )
