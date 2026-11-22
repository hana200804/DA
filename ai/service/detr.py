"""Adapter cho state dict DETR-R50 của lượt so sánh v1.1.

Nạp và suy luận qua ``signa_ai.detr``, cùng đường với lúc chọn ngưỡng trên val:
cấu hình kiến trúc ghim trong ``configs/detr-r50-config.json``, autocast fp16 trên CUDA,
cùng letterbox, softmax và chiếu ngược. Khác duy nhất: API bỏ box diện tích 0.
"""

from pathlib import Path

import numpy as np
from PIL import Image

from signa_ai.detection import Detection
from signa_ai.labels import LabelMap

# Làm tròn ngưỡng F1 tối đa 0,7065 trên val v1.1. Chỉ dùng khi thẻ thiếu operating_point.
DEFAULT_CONFIDENCE = 0.71


class DetrDetector:
    name = "detr"

    def __init__(self, weights: Path, labels: LabelMap, version: str, device: str | None):
        import torch

        from signa_ai.detr import load_detr_checkpoint

        self.version = version
        self.label_map_version = labels.version
        device = device or ("cuda:0" if torch.cuda.is_available() else "cpu")
        self.model = load_detr_checkpoint(weights, labels, device=device)

    def predict(
        self, image: np.ndarray, *, conf: float | None = None, iou: float | None = None
    ) -> list[Detection]:
        """Suy luận theo từng query. DETR bỏ qua iou."""
        from signa_ai.detr import predict_detr

        return predict_detr(
            self.model,
            Image.fromarray(image),
            conf=DEFAULT_CONFIDENCE if conf is None else conf,
            drop_empty=True,
        )
