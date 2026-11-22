"""Phân tích lỗi từ GT và dự đoán COCO ở ngưỡng F1 tối đa trên val."""

import argparse
import json
from hashlib import sha256
from pathlib import Path

import yaml

from signa_ai.evaluate import MATCH_IOU, evaluate
from signa_ai.labels import DEFAULT_PATH, load_label_map


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gt", required=True, type=Path)
    parser.add_argument("--pred", required=True, type=Path)
    parser.add_argument("--labels", type=Path, default=DEFAULT_PATH)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--target-prohibitory-recall", type=float)
    args = parser.parse_args()
    try:
        gt, predictions = args.gt.read_bytes(), args.pred.read_bytes()
        report = evaluate(
            json.loads(gt),
            json.loads(predictions),
            load_label_map(args.labels),
            target_prohibitory_recall=args.target_prohibitory_recall,
        )
        matrix = report["confusion_matrix"]["counts"]
        report["errors"] = {
            "match_iou": MATCH_IOU,
            "missed_fn": sum(row[-1] for row in matrix[:-1]),
            "background_fp": sum(matrix[-1][:-1]),
        }
        report["sha256"] = {
            "ground_truth": sha256(gt).hexdigest(),
            "predictions": sha256(predictions).hexdigest(),
        }
        for key in ("max_f1", "by_condition", "cost"):
            del report[key]
        args.out.write_text(
            json.dumps(report, ensure_ascii=False, allow_nan=False, separators=(",", ":")) + "\n",
            encoding="utf-8",
        )
    except (KeyError, OSError, OverflowError, ValueError, yaml.YAMLError) as error:
        parser.exit(2, f"Phân tích lỗi thất bại: {error}\n")


if __name__ == "__main__":
    main()
