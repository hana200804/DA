import sys
from contextlib import nullcontext
from types import SimpleNamespace

import numpy as np
import pytest

from service.detr import DetrDetector
from service.yolov5 import YOLOV5_COMMIT, YoloV5Detector
from signa_ai.detection import Box
from signa_ai.labels import load_label_map


class FakeTensor:
    def __init__(self, data):
        self.data = np.asarray(data)

    def __getitem__(self, key):
        return FakeTensor(self.data[key])

    def to(self, device):
        assert device == "cpu"
        return self

    def cpu(self):
        return self

    def float(self):
        return self

    def tolist(self):
        return self.data.tolist()

    def numpy(self):
        return self.data


def test_detr_loads_state_dict_and_reprojects_queries(monkeypatch, tmp_path):
    labels = load_label_map()
    weights = tmp_path / "best.pt"
    state = object()

    def load(path, *, map_location, weights_only):
        assert (path, map_location, weights_only) == (weights, "cpu", True)
        return {"model": state, "epoch": 219}

    class DetrModel:
        def __init__(self, config):
            assert config.id2label == labels.names
            assert config.auxiliary_loss
            assert not config.use_pretrained_backbone

        def load_state_dict(self, data):
            assert data is state

        def to(self, device):
            assert device == "cpu"
            return self

        def eval(self):
            return self

        def __call__(self, *, pixel_values, pixel_mask):
            assert pixel_values.data.shape == (1, 3, 640, 640)
            assert pixel_mask.data.shape == (1, 640, 640)
            assert pixel_mask.data[0, 160:480].all()
            assert not pixel_mask.data[0, :160].any()
            assert not pixel_mask.data[0, 480:].any()
            logits = np.full((1, 3, len(labels) + 1), -100.0)
            logits[0, :, 1] = 2
            logits[0, :, -1] = [0, 4, 0]
            boxes = [[[0.5, 0.25, 0.5, 0.25], [0.5, 0.5, 0.5, 0.5], [0.5, 0.125, 0.5, 0.25]]]
            return SimpleNamespace(logits=FakeTensor(logits), pred_boxes=FakeTensor(boxes))

    monkeypatch.setitem(
        sys.modules,
        "torch",
        SimpleNamespace(load=load, from_numpy=FakeTensor, inference_mode=nullcontext),
    )
    monkeypatch.setitem(
        sys.modules,
        "transformers",
        SimpleNamespace(DetrConfig=SimpleNamespace, DetrForObjectDetection=DetrModel),
    )
    detector = DetrDetector(weights, labels, "detr-r50-v1.1", "cpu")
    results = detector.predict(np.zeros((160, 320, 3), dtype=np.uint8), conf=0.5, iou=0)
    assert len(results) == 1
    assert results[0].box == Box(80, 0, 160, 40)
    assert results[0].sign_type_code == labels.by_id(1).code
    assert results[0].confidence == pytest.approx(1 / (1 + np.exp(-2)))


def test_yolov5_uses_original_autoshape_and_rgb_pixels(monkeypatch, tmp_path):
    labels = load_label_map()
    weights = tmp_path / "best.pt"
    image = np.full((24, 32, 3), [230, 40, 10], dtype=np.uint8)
    monkeypatch.setenv("SIGNA_YOLOV5_REPO", str(tmp_path))

    def revision(command, *, text, timeout):
        assert command == ["git", "-C", str(tmp_path), "rev-parse", "HEAD"]
        assert text and timeout > 0
        return YOLOV5_COMMIT + "\n"

    class AutoShape:
        def __init__(self, model):
            assert model == (str(weights), "cpu")
            self.names = labels.names

        def __call__(self, source, *, size):
            assert source is image
            assert size == 640
            assert (self.conf, self.iou, self.max_det) == (0, 0.5, 300)
            return SimpleNamespace(xyxy=[FakeTensor([[2, 3, 12, 15, 0.85, 1]])])

    monkeypatch.setattr("service.yolov5.subprocess.check_output", revision)
    monkeypatch.setitem(sys.modules, "torch", SimpleNamespace(device=str))
    monkeypatch.setitem(
        sys.modules,
        "models.common",
        SimpleNamespace(
            AutoShape=AutoShape, DetectMultiBackend=lambda path, device: (path, device)
        ),
    )
    detector = YoloV5Detector(weights, labels, "yolov5s-v1.1", "cpu")
    results = detector.predict(image, conf=0, iou=0.5)
    assert len(results) == 1
    assert results[0].box == Box(2, 3, 10, 12)
    assert results[0].sign_type_code == labels.by_id(1).code
