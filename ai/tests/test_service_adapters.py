import json
import sys
from types import SimpleNamespace
from unittest.mock import Mock

import numpy as np
import pytest

from service.detr import DetrDetector
from service.yolov5 import YOLOV5_COMMIT, YoloV5Detector
from signa_ai.detection import Box
from signa_ai.detr import DETR_CONFIG
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


def test_detr_uses_shared_loader_and_drops_empty_boxes(monkeypatch, tmp_path):
    torch = pytest.importorskip("torch")
    labels = load_label_map()

    class FakeDetr(torch.nn.Module):
        def __init__(self, config):
            super().__init__()
            self.config = config
            self.anchor = torch.nn.Parameter(torch.zeros(1))

        def forward(self, *, pixel_values, pixel_mask):
            assert pixel_values.shape == (1, 3, 640, 640)
            assert pixel_mask[0, 160:480].all()
            assert not pixel_mask[0, :160].any() and not pixel_mask[0, 480:].any()
            logits = torch.full((1, 3, len(labels) + 1), -100.0)
            logits[0, :, 1] = 2
            logits[0, :, -1] = torch.tensor([0.0, 4.0, 0.0])
            # Query thứ ba nằm trọn trong viền đệm phía trên nên bị cắt về diện tích 0.
            boxes = [[[0.5, 0.25, 0.5, 0.25], [0.5, 0.5, 0.5, 0.5], [0.5, 0.125, 0.5, 0.25]]]
            return SimpleNamespace(logits=logits, pred_boxes=torch.tensor(boxes))

    from_dict = Mock(side_effect=lambda data, **kwargs: SimpleNamespace(source=data, **kwargs))
    monkeypatch.setitem(
        sys.modules,
        "transformers",
        SimpleNamespace(
            DetrConfig=SimpleNamespace(from_dict=from_dict), DetrForObjectDetection=FakeDetr
        ),
    )
    weights = tmp_path / "best.pt"
    torch.save({"epoch": 219, "model": {"anchor": torch.zeros(1)}}, weights)

    detector = DetrDetector(weights, labels, "detr-r50-v1.0", "cpu")
    [(source,), options] = from_dict.call_args
    assert source == json.loads(DETR_CONFIG.read_text(encoding="utf-8"))
    assert options["id2label"] == labels.names and options["use_pretrained_backbone"] is False
    image = np.zeros((160, 320, 3), dtype=np.uint8)
    [result] = detector.predict(image, conf=0.5, iou=0)
    assert result.box == Box(80, 0, 160, 40)
    assert result.sign_type_code == labels.by_id(1).code
    assert result.confidence == pytest.approx(1 / (1 + np.exp(-2)))
    # Ngưỡng dự phòng 0,71 vẫn giữ query đầu (0,88).
    assert len(detector.predict(image)) == 1
    assert detector.predict(image, conf=0.9) == []


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


@pytest.mark.parametrize(
    ("repo", "commit", "names", "message"),
    [
        (None, YOLOV5_COMMIT, None, "SIGNA_YOLOV5_REPO"),
        ("set", "0" * 40, None, "commit"),
        ("set", YOLOV5_COMMIT, {0: "P.123a"}, "không khớp"),
    ],
)
def test_yolov5_rejects_wrong_setup(monkeypatch, tmp_path, repo, commit, names, message):
    labels = load_label_map()
    if repo is None:
        monkeypatch.delenv("SIGNA_YOLOV5_REPO", raising=False)
    else:
        monkeypatch.setenv("SIGNA_YOLOV5_REPO", str(tmp_path))
    monkeypatch.setattr(
        "service.yolov5.subprocess.check_output", lambda *args, **kwargs: commit + "\n"
    )
    monkeypatch.setitem(sys.modules, "torch", SimpleNamespace(device=str))
    monkeypatch.setitem(
        sys.modules,
        "models.common",
        SimpleNamespace(
            AutoShape=lambda model: SimpleNamespace(names=names or labels.names),
            DetectMultiBackend=lambda path, device: (path, device),
        ),
    )
    with pytest.raises(ValueError, match=message):
        YoloV5Detector(tmp_path / "best.pt", labels, "yolov5s-v1.0", "cpu")
