"""Kiểm tra dịch vụ nhận diện và đo HTTP trên 200 ảnh val v1.1 qua loopback."""

import argparse
import hashlib
import json
from datetime import UTC, datetime
from io import BytesIO
from pathlib import Path
from time import perf_counter

import httpx
import numpy as np
from PIL import Image

from signa_ai.labels import load_label_map

VAL_GT_SHA256 = "e3ccd276014eadd148030941675efeaf06db1c1ad8deddcd80747d40ec8b08e2"
RUNS = {
    "yolov5": "yolov5s-640-v1.1-main-s0",
    "yolov8": "yolov8s-640-v1.1-main-s0",
    "detr": "detr-r50-640-v1.1-main-s0",
}
EXPERIMENTS = Path(__file__).resolve().parents[1] / "experiments"


def summary(samples: list[float]) -> dict:
    return {
        "n": len(samples),
        "mean_ms": float(np.mean(samples)),
        "p50_ms": float(np.percentile(samples, 50)),
        "p95_ms": float(np.percentile(samples, 95)),
        "max_ms": max(samples),
    }


def assess(
    client: httpx.Client,
    model: dict,
    payloads: list[tuple[str, bytes, int, int]],
    default: bool,
    codes: set[str],
) -> dict:
    def send(item, *, explicit=True):
        name, image, width, height = item
        start = perf_counter()
        response = client.post(
            "/v1/detect",
            files={"image": (name, image, "image/jpeg")},
            data={"model": model["modelName"]} if explicit else {},
        )
        elapsed = (perf_counter() - start) * 1000
        response.raise_for_status()
        result = response.json()
        for key in ("modelName", "modelVersion", "labelMapVersion"):
            if result[key] != model[key]:
                raise ValueError(f"Sai {key} trong phản hồi {name}")
        for detection in result["detections"]:
            box = detection["box"]
            if (
                detection["signTypeCode"] not in codes
                or not model["defaultConf"] <= detection["confidence"] <= 1
                or not (0 <= box["x"] <= width and 0 <= box["y"] <= height)
                or not (0 <= box["width"] <= width - box["x"] + 1e-5)
                or not (0 <= box["height"] <= height - box["y"] + 1e-5)
            ):
                raise ValueError(f"Phát hiện sai hợp đồng cho ảnh {name}: {detection}")
        return elapsed, result["inferenceMs"], len(result["detections"])

    for item in payloads[:10]:
        send(item)
    if default:
        send(payloads[0], explicit=False)
    wall, inference, detections = zip(*(send(item) for item in payloads), strict=True)
    if not sum(detections):
        raise ValueError(f"{model['modelName']} không phát hiện biển nào trên 200 ảnh")
    return {
        **{k: model[k] for k in ("modelName", "modelVersion", "labelMapVersion", "defaultConf")},
        "weights_sha256": model["modelCard"]["weightsSha256"],
        "http": summary(wall),
        "inference": summary(inference),
        "images_with_detections": sum(n > 0 for n in detections),
        "detections": sum(detections),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    gt = (args.dataset / "coco/val.json").read_bytes()
    gt_sha = hashlib.sha256(gt).hexdigest()
    if gt_sha != VAL_GT_SHA256:
        raise ValueError("GT val không khớp v1.1")
    images = json.loads(gt)["images"][:200]
    payloads = []
    for image in images:
        path = args.dataset / image["file_name"]
        data = path.read_bytes()
        with Image.open(BytesIO(data)) as original:
            width, height = original.size
        payloads.append((path.name, data, width, height))
    codes = set(load_label_map().names.values())

    with httpx.Client(base_url=f"http://127.0.0.1:{args.port}", timeout=30) as client:
        health = client.get("/healthz")
        health.raise_for_status()
        models = client.get("/v1/models").raise_for_status().json()
        if health.json() != {"status": "ok"} or models["defaultModel"] != "yolov8":
            raise ValueError("Trạng thái dịch vụ hoặc mô hình mặc định sai")
        entries = {model["modelName"]: model for model in models["models"]}
        if entries.keys() != RUNS.keys():
            raise ValueError("Dịch vụ phải nạp đủ ba họ mô hình")
        results = {}
        for family, run in RUNS.items():
            card = json.loads((EXPERIMENTS / run / "model_card.json").read_text(encoding="utf-8"))
            model = entries[family]
            if (
                model["modelVersion"] != card["modelVersion"]
                or model["labelMapVersion"] != card["labelMapVersion"]
                or model["defaultConf"] != card["operating_point"]["threshold"]
                or model["modelCard"]["weightsSha256"] != card["weightsSha256"]
            ):
                raise ValueError(f"Cấu hình phục vụ khác thẻ mô hình đã chốt: {family}")
            results[family] = assess(client, model, payloads, family == "yolov8", codes)
            print(family, results[family]["http"], flush=True)
    args.out.write_text(
        json.dumps(
            {
                "measured_at": datetime.now(UTC).isoformat(),
                "val_gt_sha256": gt_sha,
                "warmup": 10,
                "image_ids": [image["id"] for image in images],
                "healthz_http": health.status_code,
                "default_model": models["defaultModel"],
                "models": results,
            },
            indent=2,
        )
        + "\n"
    )


if __name__ == "__main__":
    main()
