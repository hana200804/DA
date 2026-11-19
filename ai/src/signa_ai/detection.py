"""Hợp đồng phát hiện dùng chung cho ba mô hình (docs/04 §4 và §7).

- Mỗi mô hình có một adapter cài `Detector`. Dịch vụ nhận diện chỉ làm việc qua interface này.
- Mọi adapter xuất dự đoán ra **cùng** định dạng COCO bằng `to_coco_predictions`, để một bộ
  đánh giá `pycocotools` chấm cả ba (ADR-0003, UT-A05).
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from typing import Any, Protocol, TypedDict, runtime_checkable

from signa_ai.labels import LabelMap

# Ngưỡng tin cậy khi xuất dự đoán để đánh giá, giữ đủ đường precision–recall (docs/04 §4).
EVAL_CONFIDENCE = 0.001


@dataclass(frozen=True)
class Box:
    """Box theo pixel của ảnh gốc, gốc tọa độ ở góc trên trái (docs/07 §5)."""

    x: float
    y: float
    width: float
    height: float

    def __post_init__(self) -> None:
        if self.width < 0 or self.height < 0:
            raise ValueError(f"box có kích thước âm: {self}")

    @property
    def area(self) -> float:
        return self.width * self.height

    @classmethod
    def from_xyxy(cls, x1: float, y1: float, x2: float, y2: float) -> Box:
        return cls(x1, y1, x2 - x1, y2 - y1)


@dataclass(frozen=True)
class Detection:
    box: Box
    sign_type_code: str
    confidence: float

    def __post_init__(self) -> None:
        if not 0.0 <= self.confidence <= 1.0:
            raise ValueError(f"độ tin cậy ngoài [0, 1]: {self.confidence}")


@runtime_checkable
class Detector(Protocol):
    """Interface chung của adapter YOLOv5, YOLOv8 và DETR."""

    name: str
    """`yolov5`, `yolov8` hoặc `detr`, khớp tham số `model` của API."""
    version: str
    """Phiên bản `<kiến trúc>-<cỡ>-vMAJOR.MINOR`, ví dụ `yolov8n-v1.2` (docs/04 §8)."""
    label_map_version: int

    def predict(
        self, image: Any, *, conf: float | None = None, iou: float | None = None
    ) -> list[Detection]:
        """Nhận ảnh RGB (mảng H×W×3 `uint8`), trả box theo tọa độ ảnh gốc."""
        ...


class CocoPrediction(TypedDict):
    image_id: int
    category_id: int
    bbox: list[float]
    score: float


def to_coco_predictions(
    image_id: int, detections: Iterable[Detection], label_map: LabelMap
) -> list[CocoPrediction]:
    """Đổi dự đoán của một ảnh sang bản ghi kết quả COCO.

    `category_id` chính là `id` của lớp trong `labels.yaml`, giống nhau cho mọi mô hình.
    """
    return [
        {
            "image_id": image_id,
            "category_id": label_map.by_code(d.sign_type_code).id,
            "bbox": [d.box.x, d.box.y, d.box.width, d.box.height],
            "score": d.confidence,
        }
        for d in detections
    ]
