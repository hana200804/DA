from __future__ import annotations

import json

import numpy as np
import pytest
from PIL import Image

from signa_ai.detection import Box, to_coco_predictions
from signa_ai.detr import DetrDataset, detr_detections, letterbox
from signa_ai.labels import load_label_map


@pytest.mark.parametrize("dimensions", [(201, 100), (100, 201)])
def test_letterbox_roundtrip(dimensions):
    image = Image.new("RGB", dimensions, (10, 20, 30))
    canvas, geometry = letterbox(image)
    assert canvas.size == (640, 640)
    assert max(geometry.resized) == 640
    assert canvas.getpixel((0, 0)) == (114, 114, 114)
    assert canvas.getpixel(geometry.padding) == (10, 20, 30)

    original = Box(10.5, 20, 31, 15.75)
    mapped = geometry.to_original(*geometry.to_normalized(original))
    assert (mapped.x, mapped.y, mapped.width, mapped.height) == pytest.approx(
        (original.x, original.y, original.width, original.height)
    )


def test_detr_detections_use_coco_ids_and_keep_queries():
    labels = load_label_map()
    _, geometry = letterbox(Image.new("RGB", (80, 40)))
    logits = np.full((4, len(labels) + 1), -20.0)
    logits[:, -1] = 0
    logits[:3, 17] = -1
    box = geometry.to_normalized(Box(12, 8, 25, 16))
    boxes = np.array([box, box, (0.5, 0.1, 0.1, 0.1), box])

    detections = detr_detections(logits, boxes, geometry, labels.names)
    first, duplicate, padded = to_coco_predictions(42, detections, labels)
    assert first == duplicate
    assert first["image_id"] == 42
    assert first["category_id"] == 17
    assert first["bbox"] == pytest.approx([12, 8, 25, 16])
    assert first["score"] == pytest.approx(1 / (1 + np.e))
    assert padded["bbox"][1] == 0 and 0 < padded["bbox"][3] < 0.001


@pytest.mark.parametrize("split", ["train", "val", "test"])
def test_detr_dataset_preserves_valid_boxes(tmp_path, split):
    labels = load_label_map()
    (tmp_path / "coco").mkdir()
    images = []
    for image_id in (12, 18):
        path = tmp_path / f"images/{split}/{image_id}.png"
        path.parent.mkdir(parents=True, exist_ok=True)
        Image.new("RGB", (80, 40), (50, 70, 90)).save(path)
        images.append({
            "id": image_id,
            "file_name": path.relative_to(tmp_path).as_posix(),
            "width": 80,
            "height": 40,
        })
    dataset_json = {
        "info": {"label_map_version": labels.version},
        "images": images,
        "categories": [{"id": key, "name": name} for key, name in labels.names.items()],
        "annotations": [{"id": 4, "image_id": 12, "category_id": 17, "bbox": [20, 10, 40, 20]}],
    }
    (tmp_path / f"coco/{split}.json").write_text(json.dumps(dataset_json), encoding="utf-8")
    dataset = DetrDataset(tmp_path, split, labels)
    assert len(dataset) == 2
    if split == "train":
        dataset.transform.set_random_seed(0)
    else:
        assert dataset.transform is None

    pixels, mask, target, image_id, geometry = dataset[0]
    assert image_id == 12 and pixels.shape == (3, 640, 640) and pixels.dtype == np.float32
    assert not mask[0, 0] and mask[320, 320] and mask.sum() == 640 * 320
    assert target["class_labels"].tolist() == [17]
    original = geometry.to_original(*map(float, target["boxes"][0]))
    assert 0 <= original.x < original.x + original.width <= 80
    assert 0 <= original.y < original.y + original.height <= 40
    if split != "train":
        assert original == Box(20, 10, 40, 20)
    assert dataset[1][2]["boxes"].shape == (0, 4)
