"""Adapter YOLOv8 cho checkpoint YOLOv8n tạm."""

from pathlib import Path
from threading import Lock

import numpy as np
from PIL import Image

from signa_ai.detection import Box, Detection
from signa_ai.labels import LabelMap

# Làm tròn ngưỡng F1 tối đa 0,401663 trên val v1.1 (PR #9).
DEFAULT_CONFIDENCE = 0.40


class YoloV8Detector:
    name = "yolov8"

    def __init__(self, weights: Path, labels: LabelMap, version: str, device: str | None):
        import torch
        from ultralytics import YOLO

        self.version = version
        self.label_map_version = labels.version
        self.labels = labels
        self.device = device or ("cuda:0" if torch.cuda.is_available() else "cpu")
        self.model = YOLO(str(weights)).to(self.device)
        if self.model.task != "detect" or self.model.names != labels.names:
            raise ValueError("Tác vụ hoặc lớp trong checkpoint không khớp danh mục nhãn")
        self.lock = Lock()

    def predict(
        self, image: np.ndarray, *, conf: float | None = None, iou: float | None = None
    ) -> list[Detection]:
        source = Image.fromarray(image)
        # Một predictor dùng chung cho các request và cập nhật tham số cho từng lần gọi.
        with self.lock:
            result = self.model.predict(
                source,
                device=self.device,
                imgsz=640,
                conf=DEFAULT_CONFIDENCE if conf is None else conf,
                iou=0.7 if iou is None else iou,
                save=False,
                verbose=False,
            )[0]
            boxes = result.boxes.cpu()

        return [
            Detection(Box.from_xyxy(*coords), self.labels.by_id(int(class_id)).code, score)
            for coords, class_id, score in zip(
                boxes.xyxy.tolist(), boxes.cls.tolist(), boxes.conf.tolist(), strict=True
            )
        ]
