"""Tiền xử lý và giải mã TFLite tham chiếu cho ứng dụng Android (docs/04 §5)."""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Literal

import numpy as np

from signa_ai.detection import EVAL_CONFIDENCE, Box, Detection
from signa_ai.labels import DEFAULT_PATH, LabelMap, load_label_map


@dataclass(frozen=True)
class Letterbox:
    size: int
    width: int
    height: int
    resized_width: int
    resized_height: int
    left: int
    top: int

    def to_original(self, boxes: np.ndarray) -> np.ndarray:
        """Đổi một mảng box xyxy từ pixel đầu vào sang pixel ảnh gốc, có cắt biên."""
        offset = np.array([self.left, self.top] * 2)
        scale = np.array([self.width / self.resized_width, self.height / self.resized_height] * 2)
        return np.clip((boxes - offset) * scale, 0, [self.width, self.height] * 2)


def _resize_indices(length: int, resized: int) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    # Nội suy bilinear theo tâm pixel, lặp lại pixel ở mép ảnh.
    positions = np.clip(
        (np.arange(resized, dtype=np.float32) + 0.5) * (length / resized) - 0.5,
        0,
        length - 1,
    )
    low = positions.astype(np.intp)
    return low, np.minimum(low + 1, length - 1), positions - low.astype(np.float32)


def letterbox(image: np.ndarray, size: int) -> tuple[np.ndarray, Letterbox]:
    """Resize RGB uint8 giữ tỷ lệ và đệm màu 114, phần lẻ đặt bên phải hoặc dưới."""
    if image.ndim != 3 or image.shape[2] != 3 or image.dtype != np.uint8:
        raise ValueError("ảnh phải là RGB H×W×3 uint8")
    height, width = image.shape[:2]
    if min(height, width, size) <= 0:
        raise ValueError("kích thước ảnh và đầu vào phải dương")
    scale = size / max(height, width)
    rw, rh = max(1, round(width * scale)), max(1, round(height * scale))
    left, top = (size - rw) // 2, (size - rh) // 2

    x0, x1, xw = _resize_indices(width, rw)
    y0, y1, yw = _resize_indices(height, rh)
    xw, yw = xw[None, :, None], yw[:, None, None]
    upper = image[y0[:, None], x0] * (1 - xw) + image[y0[:, None], x1] * xw
    lower = image[y1[:, None], x0] * (1 - xw) + image[y1[:, None], x1] * xw
    padded = np.full((size, size, 3), 114, dtype=np.uint8)
    padded[top : top + rh, left : left + rw] = np.rint(upper * (1 - yw) + lower * yw)
    return padded, Letterbox(size, width, height, rw, rh, left, top)


def _nms(
    boxes: np.ndarray, scores: np.ndarray, classes: np.ndarray, iou: float, max_det: int
) -> list[int]:
    order = np.argsort(-scores, kind="stable")
    areas = np.prod(boxes[:, 2:] - boxes[:, :2], axis=1)
    kept = []
    while order.size and len(kept) < max_det:
        best = order[0]
        kept.append(int(best))
        rest = order[1:]
        same_class = classes[rest] == classes[best]
        candidates = rest[same_class]
        overlap = np.minimum(boxes[best, 2:], boxes[candidates, 2:]) - np.maximum(
            boxes[best, :2], boxes[candidates, :2]
        )
        intersection = np.prod(np.maximum(0, overlap), axis=1)
        union = areas[best] + areas[candidates] - intersection
        same_class[same_class] = intersection > iou * union
        order = rest[~same_class]
    return kept


def decode_tflite(
    output: np.ndarray,
    transform: Letterbox,
    labels: LabelMap,
    *,
    model: Literal["yolov5", "yolov8"],
    quantization: tuple[float, int] = (0.0, 0),
    conf: float = EVAL_CONFIDENCE,
    iou: float = 0.7,
    max_det: int = 300,
) -> list[Detection]:
    """Giải mã tensor thô xywh chuẩn hóa của batch 1 và giữ lớp tốt nhất mỗi box."""
    if model not in ("yolov5", "yolov8"):
        raise ValueError(f"kiến trúc không hỗ trợ: {model}")
    if not 0 <= conf <= 1 or not 0 <= iou <= 1 or max_det < 1:
        raise ValueError("conf và iou phải thuộc [0, 1], max_det phải dương")
    if tuple(labels.names) != tuple(range(len(labels))):
        raise ValueError("chỉ số lớp YOLO phải liên tiếp từ 0")

    v5 = model == "yolov5"
    class_start = 5 if v5 else 4
    if (
        output.ndim != 3
        or output.shape[0] != 1
        or output.shape[2 if v5 else 1] != (class_start + len(labels))
    ):
        raise ValueError(f"tensor đầu ra {model} sai kích thước: {output.shape}")
    if output.dtype in (np.int8, np.uint8):
        scale, zero_point = quantization
        if not np.isfinite(scale) or scale <= 0:
            raise ValueError("tensor lượng tử hóa cần scale dương")
        values = (output.astype(np.float32) - zero_point) * scale
    elif np.issubdtype(output.dtype, np.floating):
        values = output.astype(np.float32, copy=False)
    else:
        raise ValueError(f"kiểu tensor đầu ra không hỗ trợ: {output.dtype}")

    rows = values[0] if v5 else values[0].T
    probabilities = rows[:, class_start:]
    classes = np.argmax(probabilities, axis=1)
    scores = np.clip(probabilities[np.arange(len(rows)), classes], 0, 1)
    if v5:
        scores *= np.clip(rows[:, 4], 0, 1)
    valid = np.isfinite(rows).all(axis=1) & (scores >= conf) & (rows[:, 2:4] > 0).all(axis=1)
    xywh, scores, classes = rows[valid, :4] * transform.size, scores[valid], classes[valid]
    half = xywh[:, 2:] / 2
    boxes = np.concatenate((xywh[:, :2] - half, xywh[:, :2] + half), axis=1)

    original = transform.to_original(boxes)
    visible = (original[:, 2:] > original[:, :2]).all(axis=1)
    original, scores, classes = original[visible], scores[visible], classes[visible]
    kept = _nms(boxes[visible], scores, classes, iou, max_det)
    return [
        Detection(
            Box.from_xyxy(*original[i].tolist()),
            labels.by_id(int(classes[i])).code,
            float(scores[i]),
        )
        for i in kept
    ]


def write_labels_json(output: Path, labels_path: Path = DEFAULT_PATH) -> None:
    """Sinh danh mục JSON cho ứng dụng từ labels.yaml."""
    labels = load_label_map(labels_path)
    value = {
        "version": labels.version,
        "categories": {key: {"name": name} for key, name in labels.categories.items()},
        "classes": [asdict(labels.by_id(class_id)) for class_id in labels.names],
    }
    output.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
