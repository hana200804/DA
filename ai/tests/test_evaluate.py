import json
import subprocess
import sys

import pytest

from signa_ai.evaluate import evaluate
from signa_ai.labels import load_label_map


@pytest.fixture
def sample():
    labels = load_label_map()
    gt = {
        "images": [
            {"id": 1, "file_name": "images/val/1.jpg", "tags": ["night"]},
            {"id": 2, "file_name": "images/val/2.jpg"},
        ],
        "categories": [{"id": i, "name": labels.by_id(i).code} for i in (0, 1)],
        "annotations": [
            {"id": 1, "image_id": 1, "category_id": 0, "bbox": [0, 0, 12, 20]},
            {"id": 2, "image_id": 2, "category_id": 1, "bbox": [8, 8, 110, 100]},
        ],
    }
    predictions = [
        {key: value for key, value in ann.items() if key != "id"} | {"score": 0.8}
        for ann in gt["annotations"]
    ]
    return gt, predictions, labels


# UT-A04
def test_perfect_prediction(sample):
    result = evaluate(*sample)
    assert result["map_50"] == pytest.approx(1)
    assert result["map_50_95"] == pytest.approx(1)
    assert result["ap_by_size"] == {
        "small": pytest.approx(1),
        "medium": None,
        "large": pytest.approx(1),
    }
    assert result["max_f1"] == {"threshold": 0.8, "precision": 1, "recall": 1, "f1": 1}
    assert result["prohibitory_recall"] == 1
    assert result["by_condition"]["night"]["map_50"] == pytest.approx(1)
    assert result["by_condition"]["day"] is None
    assert result["by_condition"]["rain"] is None
    assert result["top_confusions"] == []


@pytest.mark.parametrize("extra", [False, True], ids=["missing", "wrong-class"])
def test_missing_or_wrong_class(sample, extra):
    gt, predictions, labels = sample
    predictions[1]["category_id"] = 0
    result = evaluate(gt, predictions if extra else predictions[:1], labels)
    assert result["map_50"] == pytest.approx(0.5)
    assert result["map_50_95"] == pytest.approx(0.5)
    assert result["ap_by_size"] == {"small": pytest.approx(1), "medium": None, "large": 0}
    assert result["per_class"]["P.103a"] == {"ap_50_95": 0, "recall": 0, "support": 1}
    assert result["prohibitory_recall"] == 0.5
    assert result["max_f1"]["precision"] == (0.5 if extra else 1)
    assert result["max_f1"]["f1"] == (0.5 if extra else 2 / 3)
    matrix = result["confusion_matrix"]["counts"]
    assert matrix[1][0 if extra else -1] == 1
    assert result["top_confusions"] == (
        [{"actual": "P.103a", "predicted": "P.102", "count": 1}] if extra else []
    )


def test_threshold_sweep_and_fixed_threshold(sample):
    gt, predictions, labels = sample
    predictions.append(dict(predictions[0], score=0.2))
    predictions[1]["score"] = 0.6
    optimum = evaluate(gt, predictions, labels)["max_f1"]
    assert optimum == {"threshold": 0.6, "precision": 1, "recall": 1, "f1": 1}

    fixed = evaluate(gt, predictions, labels, threshold=0.7)
    assert fixed["max_f1"] is None
    assert fixed["operating_point"] == {
        "threshold": 0.7,
        "precision": 1,
        "recall": 0.5,
        "f1": 2 / 3,
    }


def test_empty_predictions(sample):
    gt, _, labels = sample
    result = evaluate(gt, [], labels)
    assert result["map_50"] == 0
    assert result["max_f1"]["f1"] == 0
    assert sum(row[-1] for row in result["confusion_matrix"]["counts"]) == 2


def test_error_analysis_cli(sample, tmp_path):
    gt, predictions, _ = sample
    gt["annotations"].append(dict(gt["annotations"][1], id=3, bbox=[200, 200, 20, 20]))
    predictions[1].update(category_id=0, score=0.9)
    predictions.extend(
        [
            dict(predictions[0], score=0.8, bbox=[300, 300, 20, 20]),
            dict(predictions[0], score=0.2, bbox=[330, 330, 20, 20]),
            dict(predictions[1], category_id=1, score=0.2),
        ]
    )
    gt_path, pred_path, out = (tmp_path / name for name in ("gt.json", "pred.json", "out.json"))
    gt_path.write_text(json.dumps(gt), encoding="utf-8")
    pred_path.write_text(json.dumps(predictions), encoding="utf-8")
    subprocess.run(
        [
            sys.executable,
            "scripts/analyze_errors.py",
            "--gt",
            str(gt_path),
            "--pred",
            str(pred_path),
            "--out",
            str(out),
            "--labels",
            "data/labels.yaml",
            "--target-prohibitory-recall",
            "0.85",
        ],
        check=True,
    )
    result = json.loads(out.read_text(encoding="utf-8"))
    assert result["operating_point"]["threshold"] == 0.2
    assert result["errors"] == {"match_iou": 0.5, "missed_fn": 1, "background_fp": 3}
    assert result["top_confusions"] == [{"actual": "P.103a", "predicted": "P.102", "count": 1}]
    assert result["per_class"]["P.103a"]["recall"] == 0.5
    assert result["prohibitory_operating_point"]["prohibitory_recall"] == 2 / 3
    assert result["prohibitory_operating_point"]["target_met"] is False


def test_prohibitory_threshold_counts_score_ties(sample):
    gt, predictions, labels = sample
    predictions[1]["score"] = 0.2
    predictions.extend(dict(predictions[0], score=0.2) for _ in range(3))
    result = evaluate(gt, predictions, labels, target_prohibitory_recall=0.85)
    assert result["operating_point"]["threshold"] == 0.8
    assert result["prohibitory_operating_point"] == {
        "threshold": 0.2,
        "precision": 0.4,
        "recall": 1,
        "f1": 4 / 7,
        "prohibitory_recall": 1,
        "target_recall": 0.85,
        "target_met": True,
    }


@pytest.mark.parametrize(
    ("key", "value"),
    [
        ("image_id", 999),
        ("image_id", True),
        ("category_id", 999),
        ("bbox", [-1, 0, 10, 10]),
        ("bbox", [0, 0, -1, 10]),
        ("bbox", [0, 0, float("inf"), 10]),
        ("score", float("nan")),
        ("score", 1.1),
        ("score", 0.0009),
    ],
)
def test_invalid_predictions(sample, key, value):
    gt, predictions, labels = sample
    predictions[1][key] = value
    with pytest.raises(ValueError, match=rf"predictions\[1\]\.{key}"):
        evaluate(gt, predictions, labels)


def test_cli_with_manifest_and_size_boundaries(sample, tmp_path):
    gt, predictions, _ = sample
    for side, annotation, prediction in zip((32, 96), gt["annotations"], predictions, strict=True):
        annotation["bbox"] = prediction["bbox"] = [0, 0, side, side]

    ground_truth = tmp_path / "val.json"
    results = tmp_path / "predictions-val.json"
    manifest = tmp_path / "manifest.csv"
    output = tmp_path / "metrics.json"
    ground_truth.write_text(json.dumps(gt), encoding="utf-8")
    results.write_text(json.dumps(predictions), encoding="utf-8")
    manifest.write_text("path,tags\nimages/val/2.jpg,ngày;mưa\n", encoding="utf-8")
    subprocess.run(
        [
            sys.executable,
            "-m",
            "signa_ai.evaluate",
            "--gt",
            str(ground_truth),
            "--pred",
            str(results),
            "--out",
            str(output),
            "--manifest",
            str(manifest),
        ],
        check=True,
    )
    report = json.loads(output.read_text(encoding="utf-8"))
    assert report["ap_by_size"] == {"small": None, "medium": pytest.approx(1), "large": None}
    assert report["by_condition"]["day"]["map_50"] == pytest.approx(1)
    assert report["by_condition"]["rain"]["images"] == 1
    assert report["by_condition"]["night"]["images"] == 1
