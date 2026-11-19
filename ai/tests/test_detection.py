import pytest

from signa_ai.detection import Box, Detection, Detector, to_coco_predictions
from signa_ai.labels import load_label_map


class FakeDetector:
    name = "yolov8"
    version = "yolov8n-v0.1"
    label_map_version = 0

    def predict(self, image, *, conf=None, iou=None):
        return [Detection(Box(812, 233, 64, 66), "P.123a", 0.93)]


def test_fake_adapter_satisfies_protocol():
    assert isinstance(FakeDetector(), Detector)


def test_box_from_xyxy():
    assert Box.from_xyxy(10, 20, 74, 86) == Box(10, 20, 64, 66)


def test_invalid_values_are_rejected():
    with pytest.raises(ValueError):
        Box(0, 0, -1, 5)
    with pytest.raises(ValueError):
        Detection(Box(0, 0, 1, 1), "P.102", 1.5)


# Nền cho UT-A05: cùng mã ⇒ cùng category_id, bất kể adapter nào sinh ra.
def test_coco_prediction_uses_label_map_id():
    label_map = load_label_map()
    detections = FakeDetector().predict(image=None)
    [record] = to_coco_predictions(7, detections, label_map)
    assert record == {
        "image_id": 7,
        "category_id": label_map.by_code("P.123a").id,
        "bbox": [812, 233, 64, 66],
        "score": 0.93,
    }


def test_unknown_code_fails_loudly():
    with pytest.raises(KeyError):
        to_coco_predictions(1, [Detection(Box(0, 0, 1, 1), "P.999", 0.5)], load_label_map())
