from __future__ import annotations

import ast
import csv
import hashlib
import json
import math
import os
import socket
import sys
from contextlib import nullcontext
from io import StringIO
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, Mock

import numpy as np
import pytest
import yaml
from PIL import Image

from signa_ai.detection import Box, to_coco_predictions
from signa_ai.detr import (
    DETR_CONFIG,
    IMAGE_MEAN,
    IMAGE_STD,
    DetrDataset,
    detr_detections,
    letterbox,
    load_detr_checkpoint,
    load_detr_config,
    predict_detr,
)
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


@pytest.mark.parametrize(
    ("logits_shape", "boxes_shape", "bad"),
    [
        ((47,), (1, 4), None),
        ((2, 48), (2, 4), None),
        ((2, 47), (2, 3), None),
        ((2, 47), (3, 4), None),
        ((2, 47), (2, 4), "logits"),
        ((2, 47), (2, 4), "boxes"),
    ],
)
def test_detr_detections_rejects_invalid_outputs(logits_shape, boxes_shape, bad):
    labels = load_label_map()
    _, geometry = letterbox(Image.new("RGB", (80, 40)))
    logits, boxes = np.zeros(logits_shape), np.full(boxes_shape, 0.5)
    if bad == "logits":
        logits[0, 0] = np.nan
    elif bad == "boxes":
        boxes[1, 2] = np.inf
    with pytest.raises(ValueError, match="không hợp lệ"):
        detr_detections(logits, boxes, geometry, labels.names)


def install_fake_transformers(monkeypatch, model_class=None):
    from_dict = Mock(side_effect=lambda data, **kwargs: SimpleNamespace(source=data, **kwargs))
    from_pretrained = Mock(side_effect=AssertionError("không được tải cấu hình qua mạng"))
    config_class = SimpleNamespace(from_dict=from_dict, from_pretrained=from_pretrained)
    module = SimpleNamespace(DetrConfig=config_class, DetrForObjectDetection=model_class)
    monkeypatch.setitem(sys.modules, "transformers", module)
    return from_dict, from_pretrained


def test_detr_config_is_read_locally_without_network(tmp_path, monkeypatch):
    labels = load_label_map()
    from_dict, from_pretrained = install_fake_transformers(monkeypatch)
    monkeypatch.setattr(socket, "socket", Mock(side_effect=OSError("mạng bị chặn")))
    # config.json của facebook/detr-resnet-50 tại MODEL_REVISION, chép nguyên văn.
    assert hashlib.sha256(DETR_CONFIG.read_bytes()).hexdigest() == (
        "e7bcf3992363f27717a863f14b193140ad2e41d4338ee012730e58a92cae17e6"
    )

    config = load_detr_config(labels)
    from_pretrained.assert_not_called()
    assert config.source == json.loads(DETR_CONFIG.read_text(encoding="utf-8"))
    assert config.source["backbone"] == "resnet50" and config.source["num_queries"] == 100
    assert config.num_labels == len(labels) and config.id2label == labels.names
    assert {config.label2id[code] for code in labels.names.values()} == set(labels.names)
    assert config.use_pretrained_backbone is False and config.auxiliary_loss is True

    custom = tmp_path / "config.json"
    custom.write_text('{"model_type": "detr", "num_queries": 7}', encoding="utf-8")
    assert load_detr_config(labels, str(custom)).source == {"model_type": "detr", "num_queries": 7}
    assert from_dict.call_count == 2
    from_pretrained.assert_not_called()


LANDSCAPE = (80, 40), [0.25, 0.425, 0.25, 0.15], [10, 8, 20, 12]
PORTRAIT = (40, 80), [0.425, 0.25, 0.15, 0.25], [8, 10, 12, 20]


@pytest.mark.parametrize(
    ("mode", "color", "rgb", "case"),
    [
        ("RGB", (50, 70, 90), (50, 70, 90), LANDSCAPE),
        ("RGB", (50, 70, 90), (50, 70, 90), PORTRAIT),
        ("L", 50, (50, 50, 50), LANDSCAPE),
        # Alpha bị bỏ như DetrDataset, không trộn lên nền 114.
        ("RGBA", (50, 70, 90, 0), (50, 70, 90), PORTRAIT),
    ],
)
def test_checkpoint_inference_returns_coco_prediction_on_cpu(
    tmp_path, monkeypatch, mode, color, rgb, case
):
    torch = pytest.importorskip("torch")
    labels = load_label_map()
    dimensions, pred_box, expected_bbox = case
    calls = []

    class FakeDetr(torch.nn.Module):
        def __init__(self, config):
            super().__init__()
            self.config = config
            self.anchor = torch.nn.Parameter(torch.zeros(1))
            self.register_buffer("logits", torch.full((1, 1, len(labels) + 1), -20.0))

        def forward(self, *, pixel_values, pixel_mask):
            assert not self.training and not torch.is_grad_enabled()
            assert pixel_values.device == pixel_mask.device == self.anchor.device
            calls.append((pixel_values, pixel_mask))
            return SimpleNamespace(logits=self.logits, pred_boxes=torch.tensor([[pred_box]]))

    from_dict, from_pretrained = install_fake_transformers(monkeypatch, FakeDetr)
    logits = torch.full((1, 1, len(labels) + 1), -20.0)
    logits[0, 0, 17] = 1
    logits[0, 0, -1] = 0
    checkpoint = tmp_path / "best.pt"
    torch.save({"epoch": 219, "model": {"anchor": torch.zeros(1), "logits": logits}}, checkpoint)

    model = load_detr_checkpoint(checkpoint, labels)
    from_pretrained.assert_not_called()
    assert from_dict.call_args.kwargs["use_pretrained_backbone"] is False
    image = Image.new(mode, dimensions, color)
    [prediction] = to_coco_predictions(42, predict_detr(model, image), labels)
    assert prediction["image_id"] == 42
    assert prediction["category_id"] == 17
    assert prediction["bbox"] == pytest.approx(expected_bbox)
    assert prediction["score"] == pytest.approx(1 / (1 + np.exp(-1)))
    assert predict_detr(model, image, conf=0.8) == []

    pixel_values, pixel_mask = calls[0]
    assert pixel_values.shape == (1, 3, 640, 640) and pixel_values.dtype == torch.float32
    assert pixel_mask.dtype == torch.bool and pixel_mask.sum() == 640 * 320
    mask = pixel_mask[0] if dimensions[0] > dimensions[1] else pixel_mask[0].T
    assert not mask[159, 0] and mask[160, 0] and mask[479, 0] and not mask[480, 0]
    inside = (160, 0) if dimensions[0] > dimensions[1] else (0, 160)
    expected = (np.asarray(rgb, dtype=np.float32) / 255 - IMAGE_MEAN) / IMAGE_STD
    assert pixel_values[0, :, inside[0], inside[1]].numpy() == pytest.approx(expected)
    assert pixel_values[0, :, 0, 0].numpy() == pytest.approx((114 / 255 - IMAGE_MEAN) / IMAGE_STD)


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


def load_notebook_functions(functions, namespace):
    notebook = Path(__file__).resolve().parents[1] / "notebooks/train_detr_main_v1.1.ipynb"
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


def test_notebook_recovers_best_and_logs_from_last(tmp_path):
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
    load_notebook_functions(functions, namespace)

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


@pytest.mark.parametrize(
    ("losses", "expected", "steps", "attempts"),
    [
        ([math.nan, *([2.0] * 99)], (2.0, 1), 99, 100),
        ([math.nan, 2.0, math.inf], (None, 2), 1, 3),
    ],
)
def test_train_epoch_skips_nonfinite_loss_until_limit(losses, expected, steps, attempts):
    torch = MagicMock()
    torch.autocast.side_effect = lambda *_args, **_kwargs: nullcontext()
    namespace = {
        "torch": torch,
        "math": math,
        "DEVICE": "cpu",
        "EPOCHS": 1,
        "CLIP": 0.1,
        "MAX_NONFINITE_LOSS_FRACTION": 0.01,
        "tqdm": lambda loader, **_: loader,
    }
    load_notebook_functions({"train_epoch"}, namespace)
    outputs = []
    for value in losses:
        loss = Mock()
        loss.detach.return_value.item.return_value = value
        outputs.append(SimpleNamespace(loss=loss))
    model, optimizer, scaler = Mock(side_effect=outputs), Mock(), Mock()
    tensor = MagicMock()
    tensor.to.return_value = tensor
    tensor.__len__.return_value = 1
    loader = [(tensor, tensor, [], None, None)] * 100

    assert namespace["train_epoch"](model, loader, optimizer, scaler, 1) == expected
    assert model.call_count == attempts
    assert scaler.scale.call_count == scaler.step.call_count == scaler.update.call_count == steps
    assert optimizer.zero_grad.call_count >= attempts


def test_linear_scheduler_rates_and_resume_on_cpu(tmp_path):
    torch = pytest.importorskip("torch")
    namespace = {"torch": torch, "EPOCHS": 300, "LRF": 0.01}
    load_notebook_functions({"make_scheduler"}, namespace)

    def new_optimizer():
        return torch.optim.AdamW(
            [
                {"params": [torch.zeros(1, device="cpu")], "lr": 1e-4},
                {"params": [torch.zeros(1, device="cpu")], "lr": 1e-5},
            ]
        )

    def advance_and_check(optimizer, scheduler, epoch, factor):
        for _ in range(scheduler.last_epoch, epoch):
            optimizer.step()
            scheduler.step()
        expected = pytest.approx((1e-4 * factor, 1e-5 * factor), abs=1e-14)
        assert scheduler.last_epoch == epoch
        assert scheduler.get_last_lr() == expected
        assert [group["lr"] for group in optimizer.param_groups] == expected

    optimizer = new_optimizer()
    scheduler = namespace["make_scheduler"](optimizer)
    advance_and_check(optimizer, scheduler, 0, 1.0)
    advance_and_check(optimizer, scheduler, 150, 0.505)
    checkpoint = tmp_path / "last.pt"
    torch.save(
        {"optimizer": optimizer.state_dict(), "scheduler": scheduler.state_dict()}, checkpoint
    )

    optimizer = new_optimizer()
    scheduler = namespace["make_scheduler"](optimizer)
    state = torch.load(checkpoint, map_location="cpu", weights_only=True)
    optimizer.load_state_dict(state["optimizer"])
    scheduler.load_state_dict(state["scheduler"])
    assert scheduler.last_epoch == 150
    advance_and_check(optimizer, scheduler, 150, 0.505)
    advance_and_check(optimizer, scheduler, 151, 0.5017)
    advance_and_check(optimizer, scheduler, 299, 0.0133)
    advance_and_check(optimizer, scheduler, 300, 0.01)


def test_resume_keeps_batch_and_records_current_workers(tmp_path):
    config_file = tmp_path / "config.yaml"
    config_file.write_text("workers: 4\nbatch: 8\n", encoding="utf-8")
    (tmp_path / "requirements.txt").touch()
    state = {"config": {"workers": 2, "batch": 8}}
    choose_batch = Mock()
    namespace = {
        "yaml": yaml,
        "WORKERS": 8,
        "BASE_CONFIG": {"workers": 8},
        "choose_batch": choose_batch,
        "write_text": lambda path, value: path.write_text(value, encoding="utf-8"),
    }
    load_notebook_functions({"load_run_config"}, namespace)
    load_run_config = namespace["load_run_config"]
    model = object()

    expected = {"workers": 8, "batch": 8}
    assert load_run_config(tmp_path, model, state, needs_training=True) == expected
    assert yaml.safe_load(config_file.read_text(encoding="utf-8")) == expected
    choose_batch.assert_called_once_with(model, saved_batch=8)

    choose_batch.reset_mock()
    assert load_run_config(tmp_path, model, state, needs_training=False) == expected
    choose_batch.assert_not_called()
    incompatible = {"config": {"workers": 2, "batch": 4}}
    with pytest.raises(ValueError, match="checkpoint last.pt"):
        load_run_config(tmp_path, model, incompatible, needs_training=True)


def test_saved_batch_oom_does_not_try_smaller_batch():
    cuda = Mock(OutOfMemoryError=MemoryError)
    cuda.mem_get_info.return_value = (1024, 1024)
    cuda.memory_reserved.return_value = 0
    probe_memory = Mock(side_effect=MemoryError)
    model = Mock()
    model.parameters.return_value = []
    namespace = {
        "torch": SimpleNamespace(cuda=cuda),
        "GPU": "NVIDIA L4",
        "BATCH_CANDIDATES": (16, 8, 4, 2, 1),
        "train_set": SimpleNamespace(annotations={1: [[0, 0, 8, 8]]}),
        "probe_memory": probe_memory,
        "checkpoint_rng": Mock(),
        "restore_rng": Mock(),
    }
    load_notebook_functions({"choose_batch"}, namespace)

    with pytest.raises(RuntimeError, match="NVIDIA L4.*batch 16 đã lưu"):
        namespace["choose_batch"](model, saved_batch=16)
    probe_memory.assert_called_once_with(model, 16, 1)
