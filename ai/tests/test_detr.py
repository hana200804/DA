from __future__ import annotations

import ast
import csv
import json
import os
from io import StringIO
from pathlib import Path
from types import SimpleNamespace

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
        images.append(
            {
                "id": image_id,
                "file_name": path.relative_to(tmp_path).as_posix(),
                "width": 80,
                "height": 40,
            }
        )
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


def test_notebook_recovers_best_and_logs_from_last(tmp_path):
    notebook = Path(__file__).resolve().parents[1] / "notebooks/train_detr_main_v1.1.ipynb"
    functions = {
        "atomic_write",
        "write_text",
        "write_json",
        "save_checkpoint",
        "run_status",
        "sync_epoch_artifacts",
    }
    namespace = {
        "torch": SimpleNamespace(
            save=lambda value, stream: stream.write(json.dumps(value).encode("utf-8")),
            load=lambda path, **_: json.loads(path.read_text(encoding="utf-8")),
        ),
        "csv": csv,
        "json": json,
        "os": os,
        "StringIO": StringIO,
        "EPOCHS": 300,
        "PATIENCE": 30,
    }
    for cell in json.loads(notebook.read_text(encoding="utf-8"))["cells"]:
        if cell["cell_type"] == "code":
            tree = ast.parse("".join(cell["source"]))
            definitions = [
                node
                for node in tree.body
                if isinstance(node, ast.FunctionDef) and node.name in functions
            ]
            exec(
                compile(ast.Module(body=definitions, type_ignores=[]), str(notebook), "exec"),
                namespace,
            )

    status = namespace["run_status"]
    save = namespace["save_checkpoint"]
    assert status(tmp_path) == ("new", None)
    latest = {
        "epoch": 2,
        "best": {"epoch": 2, "fitness": 0.4},
        "model": {"linear": [1.0, 2.0]},
        "patience_count": 0,
        "history": [{"epoch": 1}, {"epoch": 2}],
    }
    save(tmp_path / "best.pt", {"epoch": 1})
    save(tmp_path / "last.pt", latest)
    assert status(tmp_path) == ("resume", latest)
    namespace["sync_epoch_artifacts"](tmp_path, latest)
    assert namespace["torch"].load(tmp_path / "best.pt") == {"epoch": 2, "model": latest["model"]}
    assert namespace["torch"].load(tmp_path / "best-selection.json") == latest["best"]
    assert (tmp_path / "results.csv").read_text(encoding="utf-8") == "epoch\n1\n2\n"

    save(tmp_path / "last.pt", {**latest, "patience_count": 30})
    assert status(tmp_path)[0] == "trained"
    namespace["write_json"](tmp_path / "done.json", {})
    assert status(tmp_path) == ("done", None)
