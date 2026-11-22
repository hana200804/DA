"""Adapter cho state dict DETR-R50 của lượt so sánh v1.1."""

from collections.abc import Mapping
from pathlib import Path

import numpy as np
from PIL import Image

from signa_ai.detection import Box, Detection
from signa_ai.labels import LabelMap

IMAGE_SIZE = 640
IMAGE_MEAN = np.asarray((0.485, 0.456, 0.406), dtype=np.float32)
IMAGE_STD = np.asarray((0.229, 0.224, 0.225), dtype=np.float32)
DEFAULT_CONFIDENCE = 0.71


def detr_detections(
    logits: np.ndarray,
    boxes: np.ndarray,
    original: tuple[int, int],
    resized: tuple[int, int],
    padding: tuple[int, int],
    names: Mapping[int, str],
    conf: float,
) -> list[Detection]:
    if (
        logits.ndim != 2
        or logits.shape[1] != len(names) + 1
        or boxes.shape != (len(logits), 4)
        or not np.isfinite(logits).all()
        or not np.isfinite(boxes).all()
    ):
        raise ValueError("Logits hoặc box DETR không hợp lệ")

    # Theo lượt đánh giá v1.1, lớp nền chỉ tham gia mẫu số softmax.
    weights = np.exp(logits.astype(np.float64) - logits.max(axis=-1, keepdims=True))
    class_ids = weights[:, :-1].argmax(axis=-1)
    scores = weights[np.arange(len(logits)), class_ids] / weights.sum(axis=-1)
    ratio = np.asarray(original) / resized
    detections = []
    for index in np.flatnonzero(scores >= conf):
        center = boxes[index, :2]
        half_size = boxes[index, 2:] / 2
        start = np.clip(((center - half_size) * IMAGE_SIZE - padding) * ratio, 0, original)
        end = np.clip(((center + half_size) * IMAGE_SIZE - padding) * ratio, 0, original)
        box = Box.from_xyxy(*start.tolist(), *end.tolist())
        if box.area > 0:
            detections.append(Detection(box, names[class_ids[index]], float(scores[index])))
    return detections


class DetrDetector:
    name = "detr"

    def __init__(self, weights: Path, labels: LabelMap, version: str, device: str | None):
        import torch
        from transformers import DetrConfig, DetrForObjectDetection

        self.version = version
        self.label_map_version = labels.version
        self.names = labels.names
        self.torch = torch
        self.device = device or ("cuda:0" if torch.cuda.is_available() else "cpu")
        # Các giá trị mặc định của DetrConfig khớp kiến trúc facebook/detr-resnet-50.
        config = DetrConfig(
            id2label=self.names,
            label2id={code: class_id for class_id, code in self.names.items()},
            use_pretrained_backbone=False,
            auxiliary_loss=True,
        )
        model = DetrForObjectDetection(config)
        model.load_state_dict(torch.load(weights, map_location="cpu", weights_only=True)["model"])
        self.model = model.to(self.device).eval()

    def predict(
        self, image: np.ndarray, *, conf: float | None = None, iou: float | None = None
    ) -> list[Detection]:
        """Suy luận theo từng query. DETR bỏ qua iou."""
        source = Image.fromarray(image)
        width, height = source.size
        scale = IMAGE_SIZE / max(width, height)
        resized = max(1, round(width * scale)), max(1, round(height * scale))
        left, top = padding = (IMAGE_SIZE - resized[0]) // 2, (IMAGE_SIZE - resized[1]) // 2
        canvas = Image.new("RGB", (IMAGE_SIZE, IMAGE_SIZE), (114, 114, 114))
        canvas.paste(source.resize(resized, Image.Resampling.BILINEAR), padding)
        pixels = (np.asarray(canvas, dtype=np.float32) / 255 - IMAGE_MEAN) / IMAGE_STD
        mask = np.zeros((IMAGE_SIZE, IMAGE_SIZE), dtype=bool)
        mask[top : top + resized[1], left : left + resized[0]] = True

        with self.torch.inference_mode():
            outputs = self.model(
                pixel_values=self.torch.from_numpy(pixels.transpose(2, 0, 1)[None]).to(self.device),
                pixel_mask=self.torch.from_numpy(mask[None]).to(self.device),
            )
            logits = outputs.logits[0].float().cpu().numpy()
            boxes = outputs.pred_boxes[0].float().cpu().numpy()

        return detr_detections(
            logits,
            boxes,
            source.size,
            resized,
            padding,
            self.names,
            DEFAULT_CONFIDENCE if conf is None else conf,
        )
