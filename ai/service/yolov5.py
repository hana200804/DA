"""Adapter cho checkpoint YOLOv5 gốc có anchor."""

import os
import subprocess
import sys
from pathlib import Path
from threading import Lock

import numpy as np

from signa_ai.detection import Box, Detection
from signa_ai.labels import LabelMap

YOLOV5_COMMIT = "1e7cf74cb64d719b34f7315aa13eab9e32cde672"
DEFAULT_CONFIDENCE = 0.42


class YoloV5Detector:
    name = "yolov5"

    def __init__(self, weights: Path, labels: LabelMap, version: str, device: str | None):
        repo_path = os.getenv("SIGNA_YOLOV5_REPO")
        if not repo_path:
            raise ValueError("Cần SIGNA_YOLOV5_REPO trỏ đến checkout repo YOLOv5 gốc")
        repo = Path(repo_path).resolve()
        commit = subprocess.check_output(
            ["git", "-C", str(repo), "rev-parse", "HEAD"], text=True, timeout=5
        ).strip()
        if commit != YOLOV5_COMMIT:
            raise ValueError(f"Checkout YOLOv5 phải ở commit {YOLOV5_COMMIT}")

        import torch

        # Checkpoint gốc tham chiếu các module models.* và utils.* của checkout.
        sys.path.insert(0, str(repo))
        try:
            from models.common import AutoShape, DetectMultiBackend

            device = device or ("cuda:0" if torch.cuda.is_available() else "cpu")
            self.model = AutoShape(DetectMultiBackend(str(weights), device=torch.device(device)))
        finally:
            sys.path.remove(str(repo))

        self.version = version
        self.label_map_version = labels.version
        self.labels = labels
        if self.model.names != labels.names:
            raise ValueError("Lớp trong checkpoint YOLOv5 không khớp danh mục nhãn")
        self.model.max_det = 300
        self.lock = Lock()

    def predict(
        self, image: np.ndarray, *, conf: float | None = None, iou: float | None = None
    ) -> list[Detection]:
        # AutoShape dùng chung ngưỡng NMS và trả xyxy đã chiếu về ảnh gốc.
        with self.lock:
            self.model.conf = DEFAULT_CONFIDENCE if conf is None else conf
            self.model.iou = 0.7 if iou is None else iou
            rows = self.model(image, size=640).xyxy[0].cpu().tolist()

        return [
            Detection(Box.from_xyxy(x1, y1, x2, y2), self.labels.by_id(int(class_id)).code, score)
            for x1, y1, x2, y2, score, class_id in rows
        ]
