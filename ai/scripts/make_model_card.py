"""Sinh thẻ mô hình từ hồ sơ huấn luyện và đánh giá trên val."""

import argparse
import csv
import json
import re
from math import isclose
from pathlib import Path

import yaml

VAL_FIELDS = ("map_50", "map_50_95", "ap_by_size", "prohibitory_recall")
NO_TEST = "Chưa đo trên tập test và tập thực địa."
NO_FIELD = "Chưa đo trên tập thực địa."


def read_json(path: Path) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"{path} phải chứa một đối tượng JSON")
    return data


def test_result(folder: Path, run: Path, weights_sha: str | None, labels: int, threshold: float):
    """Đọc kết quả test đã chấm nếu có; kiểm trọng số, nhãn và ngưỡng đã chốt trên val."""
    metrics_path, config_path = folder / "metrics-test.json", folder / "test-config.json"
    if not metrics_path.is_file():
        return None
    if weights_sha is None:
        raise ValueError(f"Kết quả test ở {folder} cần SHA-256 trọng số từ --export-config")
    metrics, config = read_json(metrics_path), read_json(config_path)
    used_sha = config.get("weights_sha256", config.get("model_sha256"))
    used_threshold = config.get("operating_threshold", config.get("config", {}).get("threshold"))
    if (
        metrics["label_map_version"] != labels
        or used_sha != weights_sha
        or used_threshold is None
        or not isclose(used_threshold, threshold)
        or not isclose(metrics["operating_point"]["threshold"], threshold)
    ):
        raise ValueError(f"Kết quả test ở {folder} không khớp trọng số, nhãn hoặc ngưỡng đã chốt")
    return {
        "map_50": metrics["map_50"],
        "map_50_95": metrics["map_50_95"],
        "ap_by_size": metrics["ap_by_size"],
        "operating_point": metrics["operating_point"],
        "prohibitory_recall": metrics["prohibitory_recall"],
        "metricsPath": metrics_path.relative_to(run).as_posix(),
    }


def tflite_card(paths: list[Path], run_dir: Path, card: dict) -> dict:
    run = run_dir.name
    summary_path, meta_path, analysis_path = paths
    meta, analysis = read_json(meta_path), read_json(analysis_path)
    identity = meta["identity"]
    if (
        identity["source_run"] != run
        or identity["model"] != card["modelName"]
        or identity["label_map_version"] != card["labelMapVersion"]
        or analysis["label_map_version"] != card["labelMapVersion"]
        or meta["evaluation_split"] != "val"
        or analysis["sha256"]["ground_truth"] != identity["val_gt_sha256"]
        or analysis["sha256"]["predictions"] != meta["sha256"]["predictions-val.json"]
        or (
            card["weightsSha256"] is not None
            and identity["weights_sha256"] != card["weightsSha256"]
        )
    ):
        raise ValueError("Nguồn TFLite không khớp lượt chạy, danh mục nhãn hoặc dữ liệu val")

    variant = f"{identity['size']}-{identity['precision']}"
    with summary_path.open(encoding="utf-8-sig", newline="") as handle:
        rows = [row for row in csv.DictReader(handle) if row["config"] == variant]
    expected = {
        "size_mb": meta["size_mb"],
        "map_50": analysis["map_50"],
        "map_50_95": analysis["map_50_95"],
        "prohibitory_recall": analysis["prohibitory_recall"],
        "operating_conf": analysis["operating_point"]["threshold"],
    }
    if len(rows) != 1 or any(
        not isclose(float(rows[0][key]), value) for key, value in expected.items()
    ):
        raise ValueError(f"Summary TFLite lệch metadata hoặc kết quả val của {variant}")

    return {
        "inputSize": identity["size"],
        "precision": identity["precision"],
        "sourceWeightsSha256": identity["weights_sha256"],
        "modelSha256": meta["sha256"]["model.tflite"],
        "valGroundTruthSha256": identity["val_gt_sha256"],
        "valPredictionsSha256": meta["sha256"]["predictions-val.json"],
        "sizeBytes": meta["size_bytes"],
        "sizeMb": meta["size_mb"],
        "cpuMsPerImage": float(rows[0]["cpu_ms_per_image"]),
        "cpuEnvironment": "Colab",
        "cpuModel": identity["cpu_model"],
        "cpuThreads": identity["cpu_threads"],
        "deviceFps": None,
        "tensors": meta["tensors"],
        "val": {
            **{key: analysis[key] for key in VAL_FIELDS},
            "maxF1": analysis["operating_point"],
        },
        "test": test_result(
            run_dir / f"tflite-{identity['size']}-{identity['precision']}-evaluation",
            run_dir,
            meta["sha256"]["model.tflite"],
            card["labelMapVersion"],
            analysis["prohibitory_operating_point"]["threshold"],
        ),
        "field": None,
        "operating_point": analysis["prohibitory_operating_point"],
        "limitations": [
            "Chưa đo FPS của chuỗi nhận diện trên thiết bị tham chiếu.",
            "Điểm vận hành trên val tính theo từng ảnh, chưa đo với tracker.",
        ],
    }


def make_card(run: Path, version: str, export_config: Path | None) -> dict:
    model = re.match(r"(yolov[58][ns]|detr-r50)-[0-9]+-", run.name)
    if model is None or not re.fullmatch(r"v[0-9]+\.[0-9]+", version):
        raise ValueError("Cần mã lượt YOLOv5n/s, YOLOv8n/s hoặc DETR-R50 và phiên bản vMAJOR.MINOR")

    variant = model[1]
    config = yaml.safe_load((run / "config.yaml").read_text(encoding="utf-8"))
    if not isinstance(config, dict):
        raise ValueError("config.yaml phải chứa một ánh xạ")
    meta = read_json(run / "meta.json")
    selected = read_json(run / "best-selection.json")
    metrics = read_json(run / "metrics.json")
    dataset, labels = config["dataset_version"], config["label_map_version"]
    if (dataset, labels, labels) != (
        meta["dataset_version"],
        meta["label_map_version"],
        metrics["label_map_version"],
    ):
        raise ValueError("Phiên bản dữ liệu hoặc nhãn trong hồ sơ lượt chạy không khớp")

    image_size = config.get("imgsz", config.get("image_size"))
    weights_sha = None
    if export_config is not None:
        export = read_json(export_config)
        if (dataset, labels, image_size) != (
            export.get("datasetVersion", export.get("dataset_version")),
            export.get("labelMapVersion", export.get("label_map_version")),
            export["imgsz"],
        ):
            raise ValueError("Cấu hình xuất dự đoán val không khớp hồ sơ lượt chạy")
        weights_sha = export.get("weights_sha256")
        if not isinstance(weights_sha, str) or not re.fullmatch(r"[0-9a-f]{64}", weights_sha):
            raise ValueError("--export-config cần weights_sha256 hợp lệ")

    costs = metrics["cost"]
    test = test_result(
        run / "evaluation", run, weights_sha, labels, metrics["operating_point"]["threshold"]
    )
    return {
        "modelName": variant[:-1] if variant.startswith("yolo") else "detr",
        "modelVersion": f"{variant}-{version}",
        "status": "candidate",
        "description": "Mô hình nhận diện biển báo giao thông theo mã của Signa.",
        "datasetVersion": dataset,
        "labelMapVersion": labels,
        "repoCommit": meta["repo_commit"],
        "trainingConfig": config,
        "augmentation": meta.get("augmentation"),
        "selection": {
            "split": "val",
            "metric": "mAP@0.5:0.95",
            "epoch": selected["epoch"],
        },
        "val": {key: metrics[key] for key in VAL_FIELDS},
        "test": test,
        "field": None,
        "inputSize": image_size,
        "weightsSha256": weights_sha,
        "sizeMb": costs["size_mb"],
        "cpuMsPerImage": costs["cpu_ms"],
        "gpuMsPerImage": costs["gpu_ms"],
        "serverLatencyP95Ms": None,
        "deviceFps": costs["mobile_fps"],
        "operating_point": metrics["operating_point"],
        "limitations": [
            "Lượt so sánh chính dùng một seed.",
            NO_FIELD if test else NO_TEST,
            "Chưa đo thời gian xử lý đầu cuối trên server tham chiếu.",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path, help="Thư mục chứa hồ sơ lượt chạy")
    parser.add_argument("--version", required=True, help="Phiên bản phát hành dạng vMAJOR.MINOR")
    parser.add_argument("--out", type=Path, help="Mặc định ghi model_card.json trong lượt chạy")
    parser.add_argument("--export-config", type=Path, help="Cấu hình xuất dự đoán val PyTorch")
    parser.add_argument(
        "--tflite",
        type=Path,
        nargs=3,
        metavar=("SUMMARY", "META", "ANALYSIS"),
        help="Summary CSV, export-meta.json và phân tích val TFLite có chỉ tiêu recall biển cấm",
    )
    args = parser.parse_args()
    try:
        card = make_card(args.run, args.version, args.export_config)
        if args.tflite:
            card["tflite"] = tflite_card(args.tflite, args.run, card)
        output = args.out or args.run / "model_card.json"
        output.write_text(
            json.dumps(card, ensure_ascii=False, indent=2, allow_nan=False) + "\n",
            encoding="utf-8",
        )
    except (KeyError, OSError, TypeError, ValueError, yaml.YAMLError) as error:
        parser.exit(2, f"Sinh thẻ mô hình thất bại: {error}\n")


if __name__ == "__main__":
    main()
