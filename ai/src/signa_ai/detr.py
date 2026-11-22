"""Tiền xử lý và suy luận DETR-R50 cho lượt so sánh của Signa."""

from __future__ import annotations

import json
from collections import defaultdict
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import TYPE_CHECKING

import numpy as np
from PIL import Image

from signa_ai.augment import detr_transforms
from signa_ai.dataset import RATIOS, relative_path
from signa_ai.dataset import Box as DatasetBox
from signa_ai.detection import EVAL_CONFIDENCE, Box, Detection
from signa_ai.labels import LabelMap

if TYPE_CHECKING:
    from torch import nn

IMAGE_SIZE = 640
IMAGE_MEAN = np.asarray((0.485, 0.456, 0.406), dtype=np.float32)
IMAGE_STD = np.asarray((0.229, 0.224, 0.225), dtype=np.float32)
MODEL = "facebook/detr-resnet-50"
MODEL_REVISION = "1d5f47bd3bdd2c4bbfa585418ffe6da5028b4c0b"


@dataclass(frozen=True)
class Letterbox:
    original: tuple[int, int]
    resized: tuple[int, int]
    padding: tuple[int, int]
    size: int

    def to_normalized(self, box: Box) -> tuple[float, float, float, float]:
        """Đổi box COCO thành (cx, cy, w, h) chuẩn hóa trên khung đã đệm."""
        width, height = self.original
        resized_w, resized_h = self.resized
        left, top = self.padding
        return (
            ((box.x + box.width / 2) * resized_w / width + left) / self.size,
            ((box.y + box.height / 2) * resized_h / height + top) / self.size,
            box.width * resized_w / width / self.size,
            box.height * resized_h / height / self.size,
        )

    def to_original(self, cx: float, cy: float, w: float, h: float) -> Box:
        """Đổi (cx, cy, w, h) chuẩn hóa về pixel ảnh gốc và cắt theo biên ảnh."""
        width, height = self.original
        resized_w, resized_h = self.resized
        left, top = self.padding
        x1, x2 = [
            min(float(width), max(0.0, (x * self.size - left) * width / resized_w))
            for x in (cx - w / 2, cx + w / 2)
        ]
        y1, y2 = [
            min(float(height), max(0.0, (y * self.size - top) * height / resized_h))
            for y in (cy - h / 2, cy + h / 2)
        ]
        return Box.from_xyxy(x1, y1, x2, y2)


def letterbox(image: Image.Image, size: int = IMAGE_SIZE) -> tuple[Image.Image, Letterbox]:
    """Giữ tỉ lệ, đặt ảnh giữa khung vuông với viền xám 114 như YOLO."""
    if size <= 0 or min(image.size) <= 0:
        raise ValueError("kích thước ảnh và khung phải dương")
    scale = size / max(image.size)
    resized = tuple(max(1, round(edge * scale)) for edge in image.size)
    padding = tuple((size - edge) // 2 for edge in resized)
    canvas = Image.new("RGB", (size, size), (114, 114, 114))
    canvas.paste(image.resize(resized, Image.Resampling.BILINEAR), padding)
    return canvas, Letterbox(image.size, resized, padding, size)


def _prepare_image(image: Image.Image) -> tuple[np.ndarray, np.ndarray, Letterbox]:
    canvas, geometry = letterbox(image)
    pixels = (np.asarray(canvas, dtype=np.float32) / 255 - IMAGE_MEAN) / IMAGE_STD
    mask = np.zeros((geometry.size, geometry.size), dtype=bool)
    left, top = geometry.padding
    width, height = geometry.resized
    mask[top : top + height, left : left + width] = True
    return pixels.transpose(2, 0, 1), mask, geometry


def detr_detections(
    logits: np.ndarray,
    boxes: np.ndarray,
    geometry: Letterbox,
    id2label: Mapping[int, str],
    conf: float = EVAL_CONFIDENCE,
) -> list[Detection]:
    """Lấy lớp biển tốt nhất mỗi query, tính softmax gồm cả lớp nền ở cột cuối."""
    if (
        logits.ndim != 2
        or logits.shape[1] != len(id2label) + 1
        or boxes.shape != (len(logits), 4)
        or not np.isfinite(logits).all()
        or not np.isfinite(boxes).all()
    ):
        raise ValueError("logits hoặc box DETR không hợp lệ")

    weights = np.exp(logits.astype(np.float64) - logits.max(axis=-1, keepdims=True))
    class_ids = weights[:, :-1].argmax(axis=-1)
    scores = weights[np.arange(len(logits)), class_ids] / weights.sum(axis=-1)
    detections = []
    for index in np.flatnonzero(scores >= conf):
        box = geometry.to_original(*map(float, boxes[index]))
        # COCO cần diện tích dương. Query nằm trọn trong padding vẫn tính là dự đoán.
        box = Box(box.x, box.y, box.width or 1e-6, box.height or 1e-6)
        detections.append(Detection(box, id2label[class_ids[index]], float(scores[index])))
    return detections


def load_detr_checkpoint(
    checkpoint: Path, label_map: LabelMap, *, device: str = "cpu"
) -> nn.Module:
    """Nạp best.pt với danh mục đã dùng khi huấn luyện và trả mô hình ở chế độ eval."""
    import torch
    from transformers import DetrConfig, DetrForObjectDetection

    names = label_map.names
    if list(names) != list(range(len(names))):
        raise ValueError("ID lớp DETR phải liên tiếp từ 0")

    state = torch.load(checkpoint, map_location="cpu", weights_only=True)
    config = DetrConfig.from_pretrained(
        MODEL,
        revision=MODEL_REVISION,
        num_labels=len(names),
        id2label=names,
        label2id={code: class_id for class_id, code in names.items()},
        use_pretrained_backbone=False,
        auxiliary_loss=True,
    )
    model = DetrForObjectDetection(config)
    model.load_state_dict(state["model"])
    return model.to(device).eval()


def predict_detr(
    model: nn.Module, image: Image.Image, *, device: str = "cpu", conf: float = EVAL_CONFIDENCE
) -> list[Detection]:
    """Chạy mô hình đã nạp trên một ảnh và trả box theo pixel ảnh gốc."""
    import torch

    pixels, mask, geometry = _prepare_image(image.convert("RGB"))
    device_type = torch.device(device).type
    with torch.inference_mode(), torch.autocast(
        device_type, dtype=torch.float16, enabled=device_type == "cuda"
    ):
        outputs = model(
            pixel_values=torch.from_numpy(pixels).unsqueeze(0).to(device),
            pixel_mask=torch.from_numpy(mask).unsqueeze(0).to(device),
        )
    return detr_detections(
        outputs.logits[0].float().cpu().numpy(),
        outputs.pred_boxes[0].float().cpu().numpy(),
        geometry,
        model.config.id2label,
        conf,
    )


class DetrDataset:
    """Đọc COCO Signa và trả ảnh CHW, mặt nạ, nhãn cùng hình học để chiếu ngược."""

    def __init__(self, root: Path, split: str, label_map: LabelMap):
        if split not in RATIOS:
            raise ValueError(f"tập dữ liệu không hỗ trợ: {split}")
        self.root = root
        self.truth = json.loads((root / "coco" / f"{split}.json").read_text(encoding="utf-8"))
        names = label_map.names
        categories = {row["id"]: row["name"] for row in self.truth["categories"]}
        if (
            self.truth["info"]["label_map_version"] != label_map.version
            or categories != names
            or list(names) != list(range(len(names)))
        ):
            raise ValueError(f"danh mục COCO của tập {split} không khớp labels.yaml")

        self.images = {}
        self.annotations = defaultdict(list)
        for row in self.truth["images"]:
            path = relative_path(row["file_name"])
            dimensions = row["width"], row["height"]
            if (
                path.parts[:2] != ("images", split)
                or min(dimensions) <= 0
                or row["id"] in self.images
            ):
                raise ValueError(f"ảnh COCO không hợp lệ: {row}")
            self.images[row["id"]] = (path, dimensions)
        self.image_ids = list(self.images)

        for row in self.truth["annotations"]:
            image_id, class_id = row["image_id"], row["category_id"]
            if class_id not in categories or row.get("iscrowd", 0):
                raise ValueError(f"nhãn COCO không hỗ trợ: {row}")
            _, (width, height) = self.images[image_id]
            x, y, w, h = row["bbox"]
            edges = (x / width, y / height, (x + w) / width, (y + h) / height)
            box = DatasetBox.from_edges(class_id, edges).coco(width, height)
            self.annotations[image_id].append((box, class_id))

        self.transform = detr_transforms() if split == "train" else None

    def __len__(self) -> int:
        return len(self.image_ids)

    def __getitem__(self, index: int) -> tuple:
        image_id = self.image_ids[index]
        path, dimensions = self.images[image_id]
        with Image.open(self.root / path) as source:
            if source.size != dimensions:
                raise ValueError(f"kích thước ảnh không khớp COCO: {path}")
            image = source.convert("RGB")

        boxes, classes = [], []
        for box, class_id in self.annotations.get(image_id, ()):
            boxes.append(box)
            classes.append(class_id)
        if self.transform is not None:
            sample = self.transform(image=np.asarray(image), bboxes=boxes, class_labels=classes)
            image = Image.fromarray(sample["image"])
            boxes, classes = sample["bboxes"], sample["class_labels"]

        pixels, mask, geometry = _prepare_image(image)
        target = {
            "class_labels": np.asarray(classes, dtype=np.int64),
            "boxes": np.asarray(
                [geometry.to_normalized(Box(*box)) for box in boxes], dtype=np.float32
            ).reshape(-1, 4),
        }
        return pixels, mask, target, image_id, geometry
