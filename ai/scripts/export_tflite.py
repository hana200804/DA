"""Xuất TFLite và chấm COCO val trong runtime Colab do notebook chuẩn bị."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import random
import shutil
import subprocess
import sys
from importlib.metadata import distributions, version
from pathlib import Path
from tempfile import TemporaryDirectory
from time import perf_counter
from zipfile import ZipFile

import numpy as np
import yaml
from PIL import Image

from signa_ai.dataset import relative_path, write_json
from signa_ai.detection import EVAL_CONFIDENCE, to_coco_predictions
from signa_ai.labels import LabelMap, load_label_map
from signa_ai.mobile import decode_tflite, letterbox, write_labels_json

REPO = Path(__file__).resolve().parents[2]
LABELS = REPO / "ai/data/labels.yaml"
MANIFEST = REPO / "ai/data/manifests/v1.1.csv"
ARTIFACTS = ("model.tflite", "predictions-val.json", "metrics.json")
PACKAGES = (
    "ultralytics",
    "tensorflow",
    "keras",
    "numpy",
    "opencv-python",
    "pillow",
    "pyyaml",
    "torch",
    "torchvision",
    "pycocotools",
    "ultralytics-thop",
    "protobuf",
)
V8_PACKAGES = (
    "tf-keras",
    "onnx",
    "onnx2tf",
    "onnxslim",
    "onnxruntime",
    "onnx-graphsurgeon",
    "ai-edge-litert",
    "sng4onnx",
    "h5py",
    "ml-dtypes",
)
IOU = 0.7
MAX_DET = 300


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def unpack(archive: Path, dataset: Path) -> None:
    if not dataset.exists():
        with TemporaryDirectory(dir=dataset.parent) as temp, ZipFile(archive) as bundle:
            for name in bundle.namelist():
                if relative_path(name).parts[0] != dataset.name:
                    raise ValueError(f"ZIP sai thư mục gốc: {name}")
            bundle.extractall(temp)
            (Path(temp) / dataset.name).rename(dataset)
    if (dataset / "manifest.csv").read_bytes() != MANIFEST.read_bytes():
        raise ValueError("manifest.csv không khớp dữ liệu v1.1 đã ghim")


def split_paths(dataset: Path, manifest: Path, split: str) -> list[str]:
    with manifest.open(encoding="utf-8", newline="") as stream:
        paths = sorted(row["path"] for row in csv.DictReader(stream) if row["split"] == split)
    root = (dataset / "images" / split).resolve()
    for name in paths:
        relative = relative_path(name)
        if relative.parts[:2] != ("images", split) or not (
            dataset / relative
        ).resolve().is_relative_to(root):
            raise ValueError(f"Ảnh nằm ngoài split {split}: {name}")
    return paths


def calibration_config(
    work: Path, dataset: Path, labels: LabelMap, train: list[str], model: str
) -> tuple[Path, int]:
    # YOLOv5 dừng ở chỉ số 100, YOLOv8 gom toàn bộ mẫu vào RAM.
    count = min(101 if model == "yolov5" else 300, len(train))
    if not count:
        raise ValueError("Thiếu ảnh train để hiệu chỉnh INT8")
    paths = [dataset / name for name in random.Random(0).sample(train, count)]
    if missing := next((path for path in paths if not path.is_file()), None):
        raise FileNotFoundError(missing)
    listing = work / "calibration-train.txt"
    listing.write_text("".join(f"{path}\n" for path in paths), encoding="utf-8")
    config = work / "calibration.yaml"
    # Trường val bắt buộc trong schema upstream. Cả hai khóa đều trỏ tới danh sách train.
    config.write_text(
        yaml.safe_dump(
            {
                "path": str(dataset),
                "train": str(listing),
                "val": str(listing),
                "nc": len(labels),
                "names": labels.names,
            }
        ),
        encoding="utf-8",
    )
    return config, count


def export_model(
    weights: Path,
    labels: LabelMap,
    model: str,
    size: int,
    int8: bool,
    data: Path,
    yolo5: Path | None,
) -> Path:
    # Hai độ phân giải dùng chung tên tệp trong thư mục tạm.
    for previous in weights.parent.glob("*.tflite"):
        previous.unlink()

    if model == "yolov8":
        from ultralytics import YOLO

        detector = YOLO(str(weights))
        if detector.task != "detect" or detector.names != labels.names:
            raise ValueError("Checkpoint YOLOv8 lệch danh mục nhãn hoặc tác vụ detect")
        exported = Path(
            detector.export(
                format="tflite",
                imgsz=size,
                batch=1,
                device="cpu",
                # Với Torch 2.8 và ONNX 1.17, opset mặc định 22 vượt giới hạn của ONNX Runtime 1.18.
                opset=19,
                nms=False,
                int8=int8,
                half=not int8,
                data=str(data),
                split="train",
            )
        )
        # API 8.3.203 trả dynamic-range INT8. Lấy bản có I/O 8 bit và dùng dữ liệu hiệu chỉnh.
        return exported.with_name(f"{weights.stem}_full_integer_quant.tflite") if int8 else exported

    sys.path.insert(0, str(yolo5))
    import torch
    from export import run
    from models.experimental import attempt_load

    detector = attempt_load(str(weights), device=torch.device("cpu"), fuse=False)
    if detector.names != labels.names:
        raise ValueError("Checkpoint YOLOv5 lệch danh mục nhãn")
    files = run(
        weights=str(weights),
        data=str(data),
        imgsz=(size, size),
        batch_size=1,
        device="cpu",
        include=("tflite",),
        int8=int8,
        nms=False,
    )
    artifacts = [Path(name) for name in files if Path(name).suffix == ".tflite"]
    if len(artifacts) != 1 or not artifacts[0].is_file():
        precision = "INT8" if int8 else "FP16"
        raise RuntimeError(
            f"Xuất YOLOv5 {size}px {precision} thất bại: cần đúng một tệp TFLite "
            f"tồn tại trong kết quả exporter, nhận {files!r}. Xem log exporter phía trên."
        )
    return artifacts[0]


def tensor_meta(tensor: dict) -> dict:
    scale, zero_point = tensor["quantization"]
    return {
        "shape": tensor["shape"].tolist(),
        "dtype": np.dtype(tensor["dtype"]).name,
        "scale": float(scale),
        "zero_point": int(zero_point),
    }


def predict_val(
    path: Path,
    dataset: Path,
    images: list[dict],
    labels: LabelMap,
    model: str,
    size: int,
    int8: bool,
    *,
    num_threads: int,
) -> tuple[list[dict], float, dict]:
    import tensorflow as tf

    interpreter = tf.lite.Interpreter(model_path=str(path), num_threads=num_threads)
    interpreter.allocate_tensors()
    (inp,) = interpreter.get_input_details()
    (out,) = interpreter.get_output_details()
    anchors = sum((size // stride) ** 2 for stride in (8, 16, 32))
    expected = (
        [1, anchors * 3, 5 + len(labels)] if model == "yolov5" else [1, 4 + len(labels), anchors]
    )
    tensors = {"input": tensor_meta(inp), "output": tensor_meta(out)}
    if tensors["input"]["shape"] != [1, size, size, 3] or tensors["output"]["shape"] != expected:
        raise ValueError(f"Shape TFLite sai hợp đồng {model} ở {size}: {tensors}")
    for tensor in tensors.values():
        valid = (
            tensor["dtype"] in ("int8", "uint8") and tensor["scale"] > 0
            if int8
            else tensor["dtype"] in ("float16", "float32")
        )
        if not valid:
            raise ValueError(f"Kiểu tensor hoặc tham số lượng tử hóa sai: {tensor}")

    dtype = inp["dtype"]
    scale, zero_point = inp["quantization"]
    if int8:
        bounds = np.iinfo(dtype)
    predictions = []
    elapsed = 0.0
    for index, image in enumerate(images):
        with Image.open(dataset / image["file_name"]) as source:
            if source.size != (image["width"], image["height"]):
                raise ValueError(f"Kích thước ảnh không khớp COCO: {image['file_name']}")
            padded, transform = letterbox(np.asarray(source.convert("RGB")), size)
        tensor = padded[None].astype(np.float32) / 255
        if int8:
            tensor = np.clip(np.rint(tensor / scale + zero_point), bounds.min, bounds.max)
        interpreter.set_tensor(inp["index"], tensor.astype(dtype, copy=False))
        if index == 0:
            for _ in range(5):
                interpreter.invoke()
        start = perf_counter()
        interpreter.invoke()
        elapsed += perf_counter() - start
        detections = decode_tflite(
            interpreter.get_tensor(out["index"]),
            transform,
            labels,
            model=model,
            quantization=out["quantization"],
            conf=EVAL_CONFIDENCE,
            iou=IOU,
            max_det=MAX_DET,
        )
        predictions.extend(to_coco_predictions(image["id"], detections, labels))
    return predictions, 1000 * elapsed / len(images), tensors


def complete(directory: Path, identity: dict) -> bool:
    try:
        meta = json.loads((directory / "export-meta.json").read_text(encoding="utf-8"))
        return meta["identity"] == identity and all(
            sha256(directory / name) == meta["sha256"][name] for name in ARTIFACTS
        )
    except (OSError, ValueError, KeyError, TypeError):
        return False


def publish(source: Path, directory: Path) -> None:
    partial = directory / f".{source.name}.partial"
    shutil.copyfile(source, partial)
    partial.replace(directory / source.name)


def write_summary(root: Path, rows: list[dict]) -> None:
    partial = root / ".summary.csv.partial"
    with partial.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    partial.replace(root / "summary.csv")


def cpu_model() -> str | None:
    try:
        with Path("/proc/cpuinfo").open(encoding="utf-8") as stream:
            for line in stream:
                key, _, value = line.partition(":")
                if key.strip() == "model name" and value.strip():
                    return value.strip()
    except OSError:
        return None
    return None


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", choices=("yolov5", "yolov8"), required=True)
    parser.add_argument("--run", required=True)
    parser.add_argument("--drive", type=Path, required=True)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--yolov5-repo", type=Path)
    args = parser.parse_args()
    if relative_path(args.run).parts != (args.run,):
        parser.error("--run phải là tên một thư mục")
    if args.model == "yolov5" and args.yolov5_repo is None:
        parser.error("YOLOv5 cần --yolov5-repo")

    weights = args.drive / "Signa/experiments" / args.run / "weights/best.pt"
    root = args.drive / "Signa/exports" / args.run
    checkpoint_sha = sha256(weights)
    unpack(args.drive / "signa-v1.1.zip", args.dataset)
    manifest = args.dataset / "manifest.csv"
    train = split_paths(args.dataset, manifest, "train")
    val = split_paths(args.dataset, manifest, "val")
    gt_path = args.dataset / "coco/val.json"
    truth = json.loads(gt_path.read_text(encoding="utf-8"))
    images = truth["images"]
    labels = load_label_map(LABELS)
    if (
        not images
        or sorted(image["file_name"] for image in images) != val
        or truth["info"]["label_map_version"] != labels.version
        or {cat["id"]: cat["name"] for cat in truth["categories"]} != labels.names
    ):
        raise ValueError("COCO val không khớp manifest hoặc danh mục v1.1")

    packages = PACKAGES + (V8_PACKAGES if args.model == "yolov8" else ())
    cpu_count = os.cpu_count()
    cpu_threads = min(4, cpu_count or 1)
    cpu = {"cpu_count": cpu_count, "cpu_model": cpu_model(), "cpu_threads": cpu_threads}
    identity = {
        "repo_commit": subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=REPO, text=True
        ).strip(),
        "weights_sha256": checkpoint_sha,
        "manifest_sha256": sha256(manifest),
        "val_gt_sha256": sha256(gt_path),
        "source_run": args.run,
        "model": args.model,
        "python": sys.version.split()[0],
        "versions": {name: version(name) for name in packages},
        "label_map_version": labels.version,
        "conf": EVAL_CONFIDENCE,
        "iou": IOU,
        "max_det": MAX_DET,
        **cpu,
        "cpu_warmup": 5,
    }
    if args.yolov5_repo:
        identity["yolov5_commit"] = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=args.yolov5_repo, text=True
        ).strip()

    root.mkdir(parents=True, exist_ok=True)
    rows = []
    with TemporaryDirectory() as temp:
        work = Path(temp)
        requirements = work / "requirements.txt"
        installed = {(dist.metadata["Name"], dist.version) for dist in distributions()}
        requirements.write_text(
            "\n".join(sorted(f"{name}=={ver}" for name, ver in installed if name)) + "\n",
            encoding="utf-8",
        )
        identity["requirements_sha256"] = sha256(requirements)
        publish(requirements, root)
        write_labels_json(work / "labels.json", LABELS)
        publish(work / "labels.json", root)
        config, sample_count = calibration_config(work, args.dataset, labels, train, args.model)
        local_weights = work / "best.pt"
        for size in (640, 480):
            for precision in ("fp16", "int8"):
                name = f"{size}-{precision}"
                directory = root / name
                settings = dict(identity, size=size, precision=precision)
                if complete(directory, settings):
                    print(f"Bỏ qua {name}: đã có đủ kết quả", flush=True)
                else:
                    if not local_weights.exists():
                        shutil.copyfile(weights, local_weights)
                        if sha256(local_weights) != checkpoint_sha:
                            raise ValueError("Checkpoint đã đổi khi đang sao chép")
                    int8 = precision == "int8"
                    artifact = export_model(
                        local_weights, labels, args.model, size, int8, config, args.yolov5_repo
                    )
                    model_path = work / "model.tflite"
                    shutil.copyfile(artifact, model_path)
                    predictions, cpu_ms, tensors = predict_val(
                        model_path,
                        args.dataset,
                        images,
                        labels,
                        args.model,
                        size,
                        int8,
                        num_threads=cpu_threads,
                    )
                    pred_path, metrics_path = (work / filename for filename in ARTIFACTS[1:])
                    write_json(pred_path, predictions)
                    subprocess.run(
                        [
                            sys.executable,
                            "-m",
                            "signa_ai.evaluate",
                            "--gt",
                            str(gt_path),
                            "--pred",
                            str(pred_path),
                            "--manifest",
                            str(manifest),
                            "--labels",
                            str(LABELS),
                            "--out",
                            str(metrics_path),
                        ],
                        check=True,
                    )
                    size_bytes = model_path.stat().st_size
                    size_mb = size_bytes / 1_000_000
                    metrics = json.loads(metrics_path.read_text(encoding="utf-8"))
                    metrics["cost"].update(size_mb=size_mb, cpu_ms=cpu_ms)
                    write_json(metrics_path, metrics)
                    meta_path = work / "export-meta.json"
                    write_json(
                        meta_path,
                        {
                            "identity": settings,
                            "size_bytes": size_bytes,
                            "size_mb": size_mb,
                            "calibration": {"split": "train", "images": sample_count, "seed": 0}
                            if int8
                            else None,
                            "evaluation_split": "val",
                            "tensors": tensors,
                            "sha256": {filename: sha256(work / filename) for filename in ARTIFACTS},
                        },
                    )
                    directory.mkdir(exist_ok=True)
                    for filename in (*ARTIFACTS, "export-meta.json"):
                        publish(work / filename, directory)
                    del predictions
                metrics = json.loads((directory / "metrics.json").read_text(encoding="utf-8"))
                rows.append(
                    {
                        "config": name,
                        "size_mb": metrics["cost"]["size_mb"],
                        "cpu_ms_per_image": metrics["cost"]["cpu_ms"],
                        **cpu,
                        "map_50": metrics["map_50"],
                        "map_50_95": metrics["map_50_95"],
                        "prohibitory_recall": metrics["prohibitory_recall"],
                        "operating_conf": metrics["operating_point"]["threshold"],
                    }
                )
                write_summary(root, rows)
                print(rows[-1], flush=True)


if __name__ == "__main__":
    os.environ["CUDA_VISIBLE_DEVICES"] = "-1"
    main()
