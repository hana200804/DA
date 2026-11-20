from pathlib import Path

import numpy as np
import pytest
import yaml

from signa_ai import augment

CONFIGS = Path(__file__).resolve().parents[1] / "configs"


def test_yolov5_hyp_matches_ultralytics_args():
    hyp = yaml.safe_load((CONFIGS / "yolov5-hyp.yaml").read_text())
    for key in augment.YOLOV5_HYP_KEYS:
        assert hyp[key] == augment.ULTRALYTICS_ARGS[key], key


def test_forbidden_augmentations_are_off():
    args = augment.ULTRALYTICS_ARGS
    for key in ("fliplr", "flipud", "hsv_h", "mosaic"):
        assert args[key] == 0.0
    assert args["degrees"] <= 10


def test_unknown_toolkit_rejected():
    with pytest.raises(ValueError, match="toolkit"):
        augment.use_signa_photometric(type("X", (), {}), "detectron")


A = pytest.importorskip("albumentations")


def _image():
    return np.random.default_rng(0).integers(0, 255, (64, 64, 3), dtype=np.uint8)


def test_ultralytics_patch_builds_image_only_pipeline():
    cls = type("Albumentations", (), {})
    augment.use_signa_photometric(cls, "ultralytics")
    obj = cls(p=1.0)
    assert obj.p == 1.0 and obj.contains_spatial is False
    assert obj.transform(image=_image())["image"].shape == (64, 64, 3)


def test_yolov5_patch_keeps_boxes():
    cls = type("Albumentations", (), {})
    augment.use_signa_photometric(cls, "yolov5")
    obj = cls(size=640)
    boxes = [[0.5, 0.5, 0.2, 0.2]]
    out = obj.transform(image=_image(), bboxes=boxes, class_labels=[3])
    np.testing.assert_allclose(out["bboxes"], boxes, atol=1e-6)
    assert list(out["class_labels"]) == [3]


def test_photometric_values_follow_docs():
    names = [type(t).__name__ for t in augment.photometric_transforms()]
    assert names == [
        "RandomBrightnessContrast", "MotionBlur", "OneOf", "GaussNoise", "ImageCompression",
    ]
    jpeg = augment.photometric_transforms()[-1]
    assert tuple(jpeg.quality_range) == (40, 90)


def test_detr_pipeline_keeps_valid_boxes():
    transform = augment.detr_transforms()
    out = transform(image=_image(), bboxes=[[20, 20, 16, 16]], class_labels=[1])
    for x, y, w, h in out["bboxes"]:
        assert 0 <= x and 0 <= y and x + w <= 64 + 1e-3 and y + h <= 64 + 1e-3


def test_detr_affine_keeps_square_and_uses_gray_border(monkeypatch):
    monkeypatch.setattr(augment, "photometric_transforms", lambda: [])
    transform = augment.detr_transforms()
    transform.set_random_seed(0)
    image = np.full((64, 64, 3), 200, dtype=np.uint8)
    result = transform(image=image, bboxes=[[24, 24, 16, 16]], class_labels=[1])
    ((_, _, width, height),) = result["bboxes"]
    assert width / height == pytest.approx(1.0, abs=1e-6)
    assert result["image"][0, 0].tolist() == [114, 114, 114]
