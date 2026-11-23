import copy
import json
import subprocess
import sys
from pathlib import Path

import pytest

from scripts.compare_synthetic import TEMPLATES, compare, read_rare_classes
from signa_ai.labels import load_label_map

AI_ROOT = Path(__file__).resolve().parents[1]
FIXTURES = AI_ROOT / "tests/fixtures/compare_synthetic"
BASELINE = FIXTURES / "metrics-val-v1.1.json"
SYNTHETIC = FIXTURES / "metrics-val-v1.1-syn.json"
RARE = ["I.409", "P.104", "W.208"]
LABELS = load_label_map()
CODES = set(LABELS.names.values())


def fixtures():
    return json.loads(BASELINE.read_text()), json.loads(SYNTHETIC.read_text())


def run(baseline, synthetic, rare=RARE):
    return compare(baseline, synthetic, rare, LABELS.version, CODES)


def test_cli_prints_rare_class_and_overall_tables():
    result = subprocess.run(
        [
            sys.executable, "scripts/compare_synthetic.py", str(BASELINE), str(SYNTHETIC),
            "--rare-classes", ",".join(RARE),
        ],
        cwd=AI_ROOT, capture_output=True, text=True, check=True,
    )
    lines = result.stdout.splitlines()

    assert "| `I.409` | 10 | 50,00% | 55,00% | +5,00 | 60,00% | 70,00% | +10,00 |" in lines
    assert "| `P.104` | 5 | 80,00% | 78,00% | −2,00 | 80,00% | 80,00% | ±0,00 |" in lines
    assert "| `W.208` | 4 | 25,00% | 25,00% | ±0,00 | 50,00% | 75,00% | +25,00 |" in lines
    assert (
        "| **Trung bình 3 lớp hiếm** | 19 | 51,67% | 52,67% | +1,00 | 63,33% | 75,00% | +11,67 |"
        in lines
    )
    assert "| mAP@0.5 | 80,00% | 81,00% | +1,00 |" in lines
    assert "| mAP@0.5:0.95 | 60,00% | 59,00% | −1,00 |" in lines
    assert "`P.102`" not in result.stdout
    assert "gốc 0,4500, v1.1-syn 0,4000" in result.stdout
    assert "seed 0" in result.stdout and "nhiễu" in result.stdout
    assert "lớp ít nhất có 4 box" in result.stdout
    assert "422 box thật" in result.stdout
    assert "Không dùng test" in result.stdout


def test_cli_writes_file_and_reads_rare_classes_from_templates(tmp_path):
    templates = tmp_path / "templates.csv"
    templates.write_text("code,license\nI.409,CC BY-SA 4.0\nW.208,CC BY-SA 4.0\n")
    out = tmp_path / "report.md"
    subprocess.run(
        [
            sys.executable, "scripts/compare_synthetic.py", str(BASELINE), str(SYNTHETIC),
            "--templates", str(templates), "--out", str(out),
        ],
        cwd=AI_ROOT, check=True,
    )
    report = out.read_text()
    assert "`I.409`" in report and "`W.208`" in report and "`P.104`" not in report
    assert "Trung bình 2 lớp hiếm" in report


def test_default_rare_classes_are_the_twelve_template_codes():
    rare = read_rare_classes(TEMPLATES)
    assert len(rare) == 12 == len(set(rare))
    assert set(rare) <= CODES
    assert rare[0] == "I.409" and "W.239b" in rare


def test_cli_rejects_label_map_mismatch(tmp_path):
    synthetic = json.loads(SYNTHETIC.read_text())
    synthetic["label_map_version"] = 2
    path = tmp_path / "metrics.json"
    path.write_text(json.dumps(synthetic))
    result = subprocess.run(
        [sys.executable, "scripts/compare_synthetic.py", str(BASELINE), str(path)],
        cwd=AI_ROOT, capture_output=True, text=True,
    )
    assert result.returncode == 2
    assert "label_map_version khác nhau" in result.stderr


def test_rejects_label_map_other_than_labels_yaml():
    baseline, synthetic = fixtures()
    baseline["label_map_version"] = synthetic["label_map_version"] = 0
    with pytest.raises(ValueError, match="labels.yaml"):
        run(baseline, synthetic)


def test_scoring_mode_must_match():
    baseline, synthetic = fixtures()
    synthetic["max_f1"] = None
    with pytest.raises(ValueError, match="cùng chế độ chấm"):
        run(baseline, synthetic)

    baseline["max_f1"] = None
    with pytest.raises(ValueError, match="cùng chế độ chấm"):
        run(baseline, synthetic)

    synthetic["operating_point"]["threshold"] = baseline["operating_point"]["threshold"]
    assert "ngưỡng khóa 0,4500 cho cả hai lượt" in run(baseline, synthetic)


@pytest.mark.parametrize(
    ("change", "message"),
    [
        (lambda metrics: metrics.update(images=21), "cùng val"),
        (lambda metrics: metrics.update(ground_truth_boxes=30), "cùng val"),
        (lambda metrics: metrics["per_class"]["I.409"].update(support=9), "I.409"),
        (lambda metrics: metrics["per_class"].pop("P.102"), "danh sách lớp"),
        (lambda metrics: metrics.update(dataset_version="v1.1"), "dataset_version"),
    ],
)
def test_rejects_runs_not_scored_on_same_val(change, message):
    baseline, synthetic = fixtures()
    change(synthetic)
    with pytest.raises(ValueError, match=message):
        run(baseline, synthetic)


def test_baseline_without_dataset_version_is_accepted():
    """metrics.json của lượt gốc đã commit chưa có dataset_version."""
    baseline, synthetic = fixtures()
    del baseline["dataset_version"]
    assert "| `I.409` |" in run(baseline, synthetic)


@pytest.mark.parametrize(
    ("rare", "message"),
    [([], "khác rỗng"), (["I.409", "I.409"], "không trùng"), (["X.1"], "labels.yaml"),
     (["P.130"], "thiếu lớp hiếm")],
)
def test_rejects_bad_rare_classes(rare, message):
    with pytest.raises(ValueError, match=message):
        run(*fixtures(), rare)


def test_missing_values_are_dashes():
    baseline, synthetic = fixtures()
    for metrics in (baseline, synthetic):
        metrics["per_class"]["W.208"] = {"ap_50_95": None, "recall": None, "support": 0}
    synthetic_copy = copy.deepcopy(synthetic)

    report = run(baseline, synthetic)

    assert "| `W.208` | 0 | — | — | — | — | — | — |" in report.splitlines()
    # Trung bình bỏ lớp không có GT: (0,5 + 0,8) / 2 và (0,55 + 0,78) / 2.
    assert "| **Trung bình 3 lớp hiếm** | 15 | 65,00% | 66,50% | +1,50 |" in report
    assert synthetic == synthetic_copy
