"""So ảnh 640 và 960 trên val bằng cùng checkpoint YOLOv8s đã chốt."""

import argparse
import hashlib
import json
import subprocess
import sys
from datetime import UTC, datetime
from importlib.metadata import version
from pathlib import Path
from time import perf_counter

import torch
from ultralytics import YOLO

from signa_ai.detection import EVAL_CONFIDENCE, Box, Detection, to_coco_predictions
from signa_ai.labels import load_label_map

ROOT = Path(__file__).resolve().parents[1]
WEIGHTS_SHA = "49012dc0aa68865114f3a09545cab371978e5a52567cced52ebb050f49bf219d"
VAL_SHA = "e3ccd276014eadd148030941675efeaf06db1c1ad8deddcd80747d40ec8b08e2"
THRESHOLD = 0.5279324054718018


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--weights", type=Path, required=True)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    gt = args.dataset / "coco/val.json"
    manifest = args.dataset / "manifest.csv"
    labels_path = ROOT / "data/labels.yaml"
    source = {
        "weights_sha256": sha256(args.weights),
        "gt_sha256": sha256(gt),
        "manifest_sha256": sha256(manifest),
        "label_map_sha256": sha256(labels_path),
    }
    tracked_manifest = labels_path.parent / "manifests/v1.1.csv"
    if (
        source["weights_sha256"] != WEIGHTS_SHA
        or source["gt_sha256"] != VAL_SHA
        or source["manifest_sha256"] != sha256(tracked_manifest)
    ):
        raise ValueError("Checkpoint, GT hoặc manifest val khác lượt chính v1.1")
    if version("ultralytics") != "8.3.203" or not torch.cuda.is_available():
        raise RuntimeError("Cần CUDA và ultralytics==8.3.203 như lượt chính")
    torch.set_num_threads(4)
    images = json.loads(gt.read_text())["images"]
    labels = load_label_map(labels_path)
    model = YOLO(str(args.weights))
    if model.names != labels.names:
        raise ValueError("Danh mục nhãn trong checkpoint khác labels.yaml")
    args.out.mkdir(parents=True, exist_ok=True)
    report = {
        **source,
        "split": "val",
        "started_at": datetime.now(UTC).isoformat(),
        "gpu": torch.cuda.get_device_name(0),
        "cuda": torch.version.cuda,
        "versions": {p: version(p) for p in ("ultralytics", "torch", "torchvision", "pycocotools")},
        "checkpoint": "yolov8s-640-v1.1-main-s0",
        "conf_export": EVAL_CONFIDENCE,
        "threshold": THRESHOLD,
        "nms_iou": 0.7,
        "max_det": 300,
        "input_sizes": {},
    }
    for size in (640, 960):
        rows = []
        start = perf_counter()
        for image in images:
            result = model.predict(
                str(args.dataset / image["file_name"]),
                imgsz=size,
                conf=EVAL_CONFIDENCE,
                iou=0.7,
                max_det=300,
                device=0,
                verbose=False,
            )[0]
            if result.orig_shape != (image["height"], image["width"]):
                raise ValueError(f"Kích thước ảnh khác COCO: {image['file_name']}")
            boxes = result.boxes.cpu()
            detections = [
                Detection(Box.from_xyxy(*xyxy), labels.names[int(cls)], float(score))
                for xyxy, cls, score in zip(
                    boxes.xyxy.tolist(), boxes.cls.tolist(), boxes.conf.tolist(), strict=True
                )
            ]
            rows.extend(to_coco_predictions(image["id"], detections, labels))
        torch.cuda.synchronize()
        elapsed = perf_counter() - start
        pred_path = args.out / f"predictions-val-{size}.json"
        metrics_path = args.out / f"metrics-val-{size}.json"
        pred_path.write_text(json.dumps(rows), encoding="utf-8")
        subprocess.run(
            [
                sys.executable,
                "-m",
                "signa_ai.evaluate",
                "--gt",
                str(gt),
                "--pred",
                str(pred_path),
                "--manifest",
                str(manifest),
                "--labels",
                str(labels_path),
                "--threshold",
                repr(THRESHOLD),
                "--out",
                str(metrics_path),
            ],
            check=True,
        )
        report["input_sizes"][size] = {
            "prediction_wall_s": elapsed,
            "predictions_sha256": sha256(pred_path),
            "metrics_sha256": sha256(metrics_path),
        }
    (args.out / "comparison-config.json").write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
