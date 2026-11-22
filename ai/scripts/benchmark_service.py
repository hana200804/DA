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
from PIL import Image, ImageOps

from signa_ai.labels import load_label_map

VAL_GT_SHA256 = "e3ccd276014eadd148030941675efeaf06db1c1ad8deddcd80747d40ec8b08e2"
RUNS = {
    "yolov5": "yolov5s-640-v1.1-main-s0",
    "yolov8": "yolov8s-640-v1.1-main-s0",
    "detr": "detr-r50-640-v1.1-main-s0",
}
EXPERIMENTS = Path(__file__).resolve().parents[1] / "experiments"
WARMUP = 10


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
    payloads: list[tuple[int, str, bytes, int, int]],
    default: bool,
    codes: set[str],
) -> tuple[dict, list[dict]]:
    def send(item, *, explicit=True):
        image_id, name, image, width, height = item
        family = model["modelName"]
        context = f"{family}, ảnh {image_id} ({name})"
        start = perf_counter()
        response = client.post(
            "/v1/detect",
            files={"image": (name, image, "image/jpeg")},
            data={"model": family} if explicit else {},
        )
        elapsed = (perf_counter() - start) * 1000
        if response.status_code != 200:
            raise ValueError(f"{context}: cần HTTP 200, nhận HTTP {response.status_code}")
        try:
            result = response.json()
        except ValueError as exc:
            raise ValueError(f"{context}: phản hồi không phải JSON hợp lệ") from exc
        if not isinstance(result, dict):
            raise ValueError(f"{context}: phản hồi phải là đối tượng JSON")
        for key in ("modelName", "modelVersion", "labelMapVersion"):
            value = result.get(key)
            if type(value) is not type(model[key]) or value != model[key]:
                raise ValueError(f"{context}: sai {key} trong phản hồi")
        inference = result.get("inferenceMs")
        if type(inference) is not int or inference < 0:
            raise ValueError(f"{context}: inferenceMs phải là số nguyên không âm")
        detections = result.get("detections")
        if not isinstance(detections, list):
            raise ValueError(f"{context}: detections phải là danh sách")
        for detection in detections:
            if not isinstance(detection, dict):
                raise ValueError(f"{context}: phát hiện phải là đối tượng JSON")
            code, confidence, box = (
                detection.get(key) for key in ("signTypeCode", "confidence", "box")
            )
            if (
                not isinstance(code, str)
                or code not in codes
                or type(confidence) not in (int, float)
                or not model["defaultConf"] <= confidence <= 1
            ):
                raise ValueError(f"{context}: mã biển hoặc confidence sai hợp đồng: {detection}")
            if not isinstance(box, dict):
                raise ValueError(f"{context}: box phải là đối tượng JSON")
            coords = [box.get(key) for key in ("x", "y", "width", "height")]
            if any(type(value) not in (int, float) for value in coords):
                raise ValueError(f"{context}: các trường box phải là số: {box}")
            x, y, w, h = coords
            if (
                not (0 <= x <= width and 0 <= y <= height)
                or not (0 <= w <= width - x + 1e-5 and 0 <= h <= height - y + 1e-5)
            ):
                raise ValueError(f"{context}: box ngoài biên ảnh: {box}")
        return {
            "model": family,
            "image_id": image_id,
            "status": response.status_code,
            "client_ms": elapsed,
            "inferenceMs": inference,
            "box_count": len(detections),
        }

    for item in payloads[:WARMUP]:
        send(item)
    if default:
        send(payloads[0], explicit=False)
    samples = [send(item) for item in payloads]
    detections = [sample["box_count"] for sample in samples]
    if not sum(detections):
        raise ValueError(f"{model['modelName']} không phát hiện biển nào trên {len(samples)} ảnh")
    report = {
        **{k: model[k] for k in ("modelName", "modelVersion", "labelMapVersion", "defaultConf")},
        "weights_sha256": model["modelCard"]["weightsSha256"],
        "http": summary([sample["client_ms"] for sample in samples]),
        "inference": summary([sample["inferenceMs"] for sample in samples]),
        "images_with_detections": sum(n > 0 for n in detections),
        "detections": sum(detections),
    }
    return report, samples


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
            width, height = ImageOps.exif_transpose(original).size
        payloads.append((image["id"], path.name, data, width, height))
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
        samples = []
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
            results[family], model_samples = assess(
                client, model, payloads, family == "yolov8", codes
            )
            samples.extend(model_samples)
            print(family, results[family]["http"], flush=True)
    samples_path = args.out.with_name(f"{args.out.stem}-samples.json")
    samples_data = (json.dumps(samples, indent=2) + "\n").encode("utf-8")
    samples_path.write_bytes(samples_data)
    args.out.write_text(
        json.dumps(
            {
                "measured_at": datetime.now(UTC).isoformat(),
                "val_gt_sha256": gt_sha,
                "warmup": WARMUP,
                "default_model_checks": 1,
                "image_ids": [image["id"] for image in images],
                "samples": {
                    "file": samples_path.name,
                    "sha256": hashlib.sha256(samples_data).hexdigest(),
                },
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
