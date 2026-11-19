"""Đánh giá dự đoán COCO của YOLOv5, YOLOv8 và DETR (AI-03, docs/04 §4)."""

from __future__ import annotations

import argparse
import csv
import json
import math
from collections import Counter
from collections.abc import Iterable
from contextlib import redirect_stdout
from io import StringIO
from itertools import groupby
from operator import itemgetter
from pathlib import Path

import numpy as np
import yaml
from pycocotools.coco import COCO
from pycocotools.cocoeval import COCOeval
from pycocotools.mask import iou

from signa_ai.detection import EVAL_CONFIDENCE
from signa_ai.labels import DEFAULT_PATH, LabelMap, load_label_map

MATCH_IOU = 0.5
CONDITIONS = {
    "day": {"day", "ngày"},
    "night": {"night", "đêm"},
    "rain": {"rain", "mưa"},
}
COST_FIELDS = (
    "parameters",
    "gflops",
    "size_mb",
    "cpu_ms",
    "gpu_ms",
    "mobile_fps",
    "epochs_to_convergence",
    "gpu_hours",
)


def number(value: object) -> bool:
    return type(value) in (int, float) and math.isfinite(value)


def indexed(rows: list[dict], name: str, *, minimum: int = 0) -> dict[int, dict]:
    if not isinstance(rows, list):
        raise ValueError(f"GT.{name} phải là danh sách")
    result = {}
    for index, row in enumerate(rows):
        value = row.get("id") if isinstance(row, dict) else None
        if type(value) is not int or value < minimum or value in result:
            raise ValueError(f"GT.{name}[{index}].id không hợp lệ hoặc bị trùng: {value!r}")
        result[value] = row
    return result


def validate_box(row: dict, where: str, images: dict, categories: dict) -> float:
    for key, allowed in (("image_id", images), ("category_id", categories)):
        value = row.get(key)
        if type(value) is not int or value not in allowed:
            raise ValueError(f"{where}.{key} không có trong GT: {value!r}")
    bbox = row.get("bbox")
    if (
        not isinstance(bbox, list)
        or len(bbox) != 4
        or any(not number(value) or value < 0 for value in bbox)
        or min(bbox[2:]) <= 0
    ):
        raise ValueError(f"{where}.bbox cần [x, y, w, h] hữu hạn, x/y ≥ 0 và w/h > 0")
    area = bbox[2] * bbox[3]
    if not math.isfinite(area) or area <= 0:
        raise ValueError(f"{where}.bbox có diện tích ngoài phạm vi")
    return area


def coco_inputs(gt: dict, predictions: list[dict], labels: LabelMap) -> tuple[COCO, COCO]:
    if not isinstance(gt, dict) or not isinstance(predictions, list):
        raise ValueError("GT phải là đối tượng COCO và predictions phải là danh sách")
    info = gt.get("info", {})
    if (
        not isinstance(info, dict)
        or info.get("label_map_version", labels.version) != labels.version
    ):
        raise ValueError(f"GT.info.label_map_version phải khớp danh mục v{labels.version}")

    images = indexed(gt["images"], "images")
    categories = indexed(gt["categories"], "categories")
    names = labels.names
    for class_id, category in categories.items():
        if class_id not in names or category.get("name") != names[class_id]:
            raise ValueError(
                f"GT.categories: id/mã lớp không khớp danh mục v{labels.version}: "
                f"{class_id}/{category.get('name')!r}"
            )
    annotations = []
    for ann_id, row in indexed(gt["annotations"], "annotations", minimum=1).items():
        where = f"GT.annotations[id={ann_id}]"
        area = validate_box(row, where, images, categories)
        crowd = row.get("iscrowd", 0)
        if type(crowd) is not int or crowd not in (0, 1):
            raise ValueError(f"{where}.iscrowd phải là 0 hoặc 1")
        annotations.append({**row, "area": area, "iscrowd": crowd})

    detections = []
    for index, row in enumerate(predictions):
        where = f"predictions[{index}]"
        if not isinstance(row, dict):
            raise ValueError(f"{where} phải là đối tượng COCO")
        area = validate_box(row, where, images, categories)
        score = row.get("score")
        if not number(score) or not 0 <= score <= 1:
            raise ValueError(f"{where}.score phải thuộc [0, 1]: {score!r}")
        if score < EVAL_CONFIDENCE:
            raise ValueError(f"{where}.score dưới ngưỡng xuất {EVAL_CONFIDENCE}: {score!r}")
        detections.append({**row, "id": index + 1, "area": area, "iscrowd": 0})

    truth, results = COCO(), COCO()
    for coco, rows in ((truth, annotations), (results, detections)):
        coco.dataset = {"images": gt["images"], "categories": gt["categories"], "annotations": rows}
        coco.createIndex()
    return truth, results


def mean_ap(values: np.ndarray) -> float | None:
    valid = values[values >= 0]
    return float(valid.mean()) if valid.size else None


def coco_ap(truth: COCO, results: COCO, image_ids: list[int]) -> tuple[dict, dict]:
    evaluator = COCOeval(truth, results, "bbox")
    evaluator.params.imgIds = image_ids
    # COCO dùng hai đầu mút đóng. Thiết kế Signa xếp hai giá trị biên vào nhóm vừa.
    evaluator.params.areaRng[1][1] = math.nextafter(32**2, -math.inf)
    evaluator.params.areaRng[3][0] = math.nextafter(96**2, math.inf)
    evaluator.evaluate()
    evaluator.accumulate()
    precision = evaluator.eval["precision"][..., -1]
    return (
        {
            "map_50": mean_ap(precision[0, :, :, 0]),
            "map_50_95": mean_ap(precision[:, :, :, 0]),
            "ap_by_size": {
                label: mean_ap(precision[:, :, :, index])
                for index, label in enumerate(evaluator.params.areaRngLbl)
                if label != "all"
            },
        },
        {
            class_id: mean_ap(precision[:, :, index, 0])
            for index, class_id in enumerate(evaluator.params.catIds)
        },
    )


def tags(value: list[str] | str | None) -> set[str]:
    if value is None:
        return set()
    if isinstance(value, str):
        value = value.split(";")
    if not isinstance(value, list) or any(not isinstance(tag, str) for tag in value):
        raise ValueError("tags phải là danh sách chuỗi hoặc chuỗi phân tách bằng dấu ';'")
    return {tag.strip().casefold() for tag in value}


def condition_images(truth: COCO, manifest: Path | None) -> dict[str, list[int]]:
    by_path = {}
    if manifest is not None:
        with manifest.open(encoding="utf-8-sig", newline="") as handle:
            rows = csv.DictReader(handle)
            if not {"path", "tags"}.issubset(rows.fieldnames or []):
                raise ValueError("manifest cần có cột path và tags")
            by_path = {row["path"]: tags(row["tags"]) for row in rows}

    groups = {condition: [] for condition in CONDITIONS}
    for image_id, image in truth.imgs.items():
        assigned = tags(image.get("tags")) | by_path.get(image.get("file_name"), set())
        for condition, aliases in CONDITIONS.items():
            if assigned & aliases:
                groups[condition].append(image_id)
    return groups


def match(
    annotations: list[dict], detections: list[dict], *, same_class: bool
) -> Iterable[tuple[dict | None, dict | None]]:
    """Ghép theo score giảm dần với GT còn trống có IoU lớn nhất."""
    if not annotations:
        yield from ((None, detection) for detection in detections)
        return
    if not detections:
        yield from ((ann, None) for ann in annotations if not ann["iscrowd"])
        return

    overlaps = iou(
        [dt["bbox"] for dt in detections],
        [ann["bbox"] for ann in annotations],
        [ann["iscrowd"] for ann in annotations],
    )
    matched = set()
    for dt, row in zip(detections, overlaps, strict=True):
        candidates = [
            (index, overlap)
            for index, overlap in enumerate(row)
            if overlap >= MATCH_IOU
            and (not same_class or dt["category_id"] == annotations[index]["category_id"])
        ]
        available = [
            (index, overlap)
            for index, overlap in candidates
            if index not in matched and not annotations[index]["iscrowd"]
        ]
        if available:
            index, _ = max(available, key=itemgetter(1))
            matched.add(index)
            yield annotations[index], dt
        elif not any(annotations[index]["iscrowd"] for index, _ in candidates):
            yield None, dt

    for index, ann in enumerate(annotations):
        if index not in matched and not ann["iscrowd"]:
            yield ann, None


def operating_metrics(tp: int, predicted: int, total: int, threshold: float) -> dict:
    return {
        "threshold": threshold,
        "precision": tp / predicted if predicted else 0.0,
        "recall": tp / total if total else None,
        "f1": 2 * tp / (predicted + total) if predicted + total else 0.0,
    }


def best_f1(ranked: list[tuple[float, bool, int]], total: int) -> dict:
    if not ranked:
        return operating_metrics(0, 0, total, 1.0)
    tp = predicted = 0
    best = None
    for score, group in groupby(ranked, key=itemgetter(0)):
        for _, hit, _ in group:
            predicted += 1
            tp += hit
        current = operating_metrics(tp, predicted, total, score)
        if best is None or current["f1"] > best["f1"]:
            best = current
    return best


def evaluate(
    gt: dict,
    predictions: list[dict],
    labels: LabelMap,
    *,
    manifest: Path | None = None,
    threshold: float | None = None,
) -> dict:
    if threshold is not None and not EVAL_CONFIDENCE <= threshold <= 1:
        raise ValueError(f"ngưỡng vận hành phải thuộc [{EVAL_CONFIDENCE}, 1]")
    with redirect_stdout(StringIO()):
        truth, results = coco_inputs(gt, predictions, labels)
        summary, per_class_ap = coco_ap(truth, results, list(truth.imgs))
        by_condition = {}
        for condition, images in condition_images(truth, manifest).items():
            by_condition[condition] = None
            if images:
                metrics, _ = coco_ap(truth, results, images)
                by_condition[condition] = {"images": len(images), **metrics}

    support = Counter(ann["category_id"] for ann in truth.anns.values() if not ann["iscrowd"])
    ordered = {
        image_id: sorted(rows, key=itemgetter("score"), reverse=True)
        for image_id, rows in results.imgToAnns.items()
    }
    ranked = sorted(
        (
            (dt["score"], ann is not None, dt["category_id"])
            for image_id in truth.imgs
            for ann, dt in match(
                truth.imgToAnns[image_id], ordered.get(image_id, []), same_class=True
            )
            if dt is not None
        ),
        key=itemgetter(0),
        reverse=True,
    )
    total = support.total()
    optimum = best_f1(ranked, total) if threshold is None else None
    if optimum is not None:
        threshold = optimum["threshold"]
    selected = [hit for hit in ranked if hit[0] >= threshold]
    hits = Counter(class_id for _, hit, class_id in selected if hit)

    names = labels.names
    prohibitory = [sign.id for sign in labels.classes if sign.category == "prohibitory"]
    group_support = sum(support[class_id] for class_id in prohibitory)
    background = len(names)
    positions = {class_id: index for index, class_id in enumerate(names)}
    matrix = [[0] * (background + 1) for _ in range(background + 1)]
    for image_id in truth.imgs:
        retained = [dt for dt in ordered.get(image_id, []) if dt["score"] >= threshold]
        for ann, dt in match(truth.imgToAnns[image_id], retained, same_class=False):
            row = positions[ann["category_id"]] if ann else background
            col = positions[dt["category_id"]] if dt else background
            matrix[row][col] += 1
    top_confusions = sorted(
        (
            {"actual": actual, "predicted": predicted, "count": matrix[row][col]}
            for row, actual in enumerate(names.values())
            for col, predicted in enumerate(names.values())
            if row != col and matrix[row][col]
        ),
        key=lambda pair: (-pair["count"], pair["actual"], pair["predicted"]),
    )[:10]
    return {
        **summary,
        "label_map_version": labels.version,
        "images": len(truth.imgs),
        "ground_truth_boxes": total,
        "predictions": len(predictions),
        "operating_point": operating_metrics(hits.total(), len(selected), total, threshold),
        "max_f1": optimum,
        "per_class": {
            code: {
                "ap_50_95": per_class_ap.get(class_id),
                "recall": hits[class_id] / support[class_id] if support[class_id] else None,
                "support": support[class_id],
            }
            for class_id, code in names.items()
        },
        "prohibitory_recall": (
            sum(hits[class_id] for class_id in prohibitory) / group_support
            if group_support
            else None
        ),
        "by_condition": by_condition,
        "confusion_matrix": {"labels": [*names.values(), "background"], "counts": matrix},
        "top_confusions": top_confusions,
        "cost": dict.fromkeys(COST_FIELDS),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gt", required=True, type=Path)
    parser.add_argument("--pred", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--labels", type=Path, default=DEFAULT_PATH)
    parser.add_argument("--manifest", type=Path, help="Manifest CSV có cột path và tags")
    parser.add_argument("--threshold", type=float, help="Ngưỡng vận hành đã chọn trên val")
    args = parser.parse_args()
    try:
        metrics = evaluate(
            json.loads(args.gt.read_text(encoding="utf-8")),
            json.loads(args.pred.read_text(encoding="utf-8")),
            load_label_map(args.labels),
            manifest=args.manifest,
            threshold=args.threshold,
        )
        args.out.write_text(
            json.dumps(metrics, ensure_ascii=False, allow_nan=False, indent=2) + "\n",
            encoding="utf-8",
        )
    except (KeyError, OSError, OverflowError, ValueError, yaml.YAMLError) as error:
        parser.exit(2, f"Đánh giá thất bại: {error}\n")


if __name__ == "__main__":
    main()
