"""Kiểm tra logic thuần của notebook chấm test mà không cần GPU, Drive hay mô hình."""

import ast
import hashlib
import json
import math
import os
import re
import subprocess
import sys
import time
from contextlib import contextmanager
from copy import deepcopy
from datetime import UTC, datetime
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import pytest
import yaml

AI_ROOT = Path(__file__).resolve().parents[1]
NOTEBOOK = "predict_test_v1.1.ipynb"


def code_cells(filename):
    notebook = json.loads((AI_ROOT / "notebooks" / filename).read_text(encoding="utf-8"))
    for cell in notebook["cells"]:
        if cell["cell_type"] == "code":
            source = "".join(cell["source"])
            yield "\n".join(line for line in source.splitlines() if not line.startswith("%"))


def top_level(filename):
    """Hằng literal viết hoa và các hàm ở mức đầu của mọi ô mã."""
    constants, functions = {}, {}
    for source in code_cells(filename):
        for node in ast.parse(source).body:
            if isinstance(node, ast.FunctionDef):
                functions[node.name] = node
            elif (
                isinstance(node, ast.Assign)
                and len(node.targets) == 1
                and isinstance(node.targets[0], ast.Name)
                and node.targets[0].id.isupper()
            ):
                try:
                    constants[node.targets[0].id] = ast.literal_eval(node.value)
                except ValueError:
                    pass
    return constants, functions


CONSTANTS, FUNCTIONS = top_level(NOTEBOOK)


@pytest.fixture
def nb():
    namespace = {
        **CONSTANTS,
        "LOG": None,
        # predict_val chỉ cần decorator khi định nghĩa; không chạy mô hình trong kiểm thử.
        "torch": SimpleNamespace(inference_mode=lambda: lambda function: function),
        "Path": Path,
        "PackageNotFoundError": PackageNotFoundError,
        "UTC": UTC,
        "contextmanager": contextmanager,
        "datetime": datetime,
        "hashlib": hashlib,
        "json": json,
        "math": math,
        "os": os,
        "sys": sys,
        "time": time,
        "traceback": __import__("traceback"),
        "version": version,
    }
    module = ast.Module(body=list(FUNCTIONS.values()), type_ignores=[])
    exec(compile(module, NOTEBOOK, "exec"), namespace)
    return namespace


def test_read_group_accepts_three_groups_and_rejects_others(nb):
    read_group = nb["read_group"]
    assert [read_group({"SIGNA_GROUP": name}) for name in ("yolov8", " YOLOv5 ", "detr")] == [
        "yolov8",
        "yolov5",
        "detr",
    ]
    for environ in ({}, {"SIGNA_GROUP": ""}, {"SIGNA_GROUP": "rtdetr"}):
        with pytest.raises(ValueError, match="SIGNA_GROUP"):
            read_group(environ)


def test_groups_cover_five_main_runs_once():
    runs = [run for group in CONSTANTS["GROUPS"].values() for run in group["runs"]]
    assert sorted(runs) == sorted(CONSTANTS["LOCKED"])
    assert len(runs) == len(set(runs)) == 5
    detr = CONSTANTS["GROUPS"]["detr"]["packages"]
    assert f"transformers=={CONSTANTS['TRANSFORMERS_VERSION']}" in detr
    assert "ultralytics==8.3.203" in CONSTANTS["GROUPS"]["yolov8"]["packages"]


ROW = r"\| \[[^]]+\]\(([^/]+)/model_card\.json\) \| (\d+) \| ([\d,]+) \| `(\w+)`"


def locked_table():
    text = (AI_ROOT / "experiments/model-selection-v1.1.md").read_text(encoding="utf-8")
    section = text.split("## Chốt cấu hình trước khi đo test", 1)[1].split("\n## ", 1)[0]
    rows = {}
    for line in section.splitlines():
        match = re.match(ROW, line)
        if match:
            run, size, threshold, sha = match.groups()
            rows[run] = (int(size), float(threshold.replace(",", ".")), sha)
    return rows


def test_locked_config_matches_selection_doc_and_val_metrics():
    table = locked_table()
    assert set(table) == set(CONSTANTS["LOCKED"])
    for run, spec in CONSTANTS["LOCKED"].items():
        assert table[run] == (spec["imgsz"], spec["threshold"], spec["weights_sha256"])
        metrics = json.loads((AI_ROOT / f"experiments/{run}/metrics.json").read_text())
        assert metrics["operating_point"]["threshold"] == spec["threshold"]
        card = json.loads((AI_ROOT / f"experiments/{run}/model_card.json").read_text())
        assert card["weightsSha256"] == spec["weights_sha256"]
        assert spec["weights"] == ("best.pt" if run.startswith("detr") else "weights/best.pt")


def find_key(data, key):
    if isinstance(data, dict):
        for name, value in data.items():
            if name == key:
                yield value
            yield from find_key(value, key)
    elif isinstance(data, list):
        for value in data:
            yield from find_key(value, key)


def test_val_gt_sha_matches_tflite_model_cards():
    shas = {
        sha
        for path in AI_ROOT.glob("experiments/*/model_card.json")
        for sha in find_key(json.loads(path.read_text()), "valGroundTruthSha256")
    }
    assert shas == {CONSTANTS["VAL_GT_SHA256"]}


def test_detr_batch_matches_training_config():
    config = yaml.safe_load(
        (AI_ROOT / "experiments/detr-r50-640-v1.1-main-s0/config.yaml").read_text()
    )
    assert CONSTANTS["DETR_BATCH"] == config["batch"]


def test_repo_commit_has_detr_inference_and_evaluate_threshold():
    commit = CONSTANTS["REPO_COMMIT"]
    assert re.fullmatch(r"[0-9a-f]{40}", commit)
    try:
        detr = subprocess.run(
            ["git", "show", f"{commit}:ai/src/signa_ai/detr.py"],
            cwd=AI_ROOT,
            capture_output=True,
            text=True,
            check=True,
        ).stdout
        evaluate = subprocess.run(
            ["git", "show", f"{commit}:ai/src/signa_ai/evaluate.py"],
            cwd=AI_ROOT,
            capture_output=True,
            text=True,
            check=True,
        ).stdout
    except (OSError, subprocess.CalledProcessError):
        pytest.skip("không đọc được REPO_COMMIT từ git cục bộ")
    assert "def load_detr_checkpoint" in detr and "def predict_detr" in detr
    assert '"--threshold"' in evaluate


@pytest.mark.parametrize(
    ("train_notebook", "train_name", "name"),
    [
        ("train_yolov8_main_v1.1.ipynb", "predict_with", "predict_with_yolov8"),
        ("train_yolov5_main_v1.1.ipynb", "predict_with", "predict_with_yolov5"),
        ("train_detr_main_v1.1.ipynb", "collate", "collate"),
        ("train_detr_main_v1.1.ipynb", "predict_val", "predict_val"),
    ],
)
def test_prediction_code_is_verbatim_from_train_notebook(train_notebook, train_name, name):
    original = deepcopy(top_level(train_notebook)[1][train_name])
    copy = deepcopy(FUNCTIONS[name])
    for node in (original, copy):
        node.name = "f"
    assert ast.dump(copy) == ast.dump(original)


def test_check_weights_returns_sha_and_stops_on_mismatch(nb, tmp_path):
    weights = tmp_path / "best.pt"
    weights.write_bytes(b"weights")
    digest = hashlib.sha256(b"weights").hexdigest()
    assert nb["check_weights"](weights, digest) == digest
    with pytest.raises(RuntimeError, match="khác bảng chốt"):
        nb["check_weights"](weights, "0" * 64)
    with pytest.raises(FileNotFoundError):
        nb["check_weights"](tmp_path / "missing.pt", digest)


def test_reproduction_check_uses_two_tenths_of_a_point(nb):
    check = nb["reproduction_check"]
    reference = {"map_50_95": 0.6956, "map_50": 0.8403, "predictions": 10}
    within = check({"map_50_95": 0.6976, "map_50": 0.8383, "predictions": 11}, reference)
    assert within["passed"]
    assert within["delta_points"]["map_50_95"] == pytest.approx(0.2)
    assert within["delta_points"]["map_50"] == pytest.approx(-0.2)
    assert within["predictions"] == {"reference": 10, "reproduced": 11}
    assert not check({"map_50_95": 0.6956, "map_50": 0.8424}, reference)["passed"]
    assert not check({"map_50_95": 0.6935, "map_50": 0.8403}, reference)["passed"]
    with pytest.raises(ValueError, match="map_50"):
        check({"map_50_95": 0.6956, "map_50": None}, reference)


def test_test_done_skips_only_matching_sha(nb, tmp_path):
    test_done = nb["test_done"]
    assert not test_done(tmp_path, "w", "g")
    (tmp_path / "metrics-test.json").write_text("{}")
    assert not test_done(tmp_path, "w", "g")
    config = tmp_path / "test-config.json"
    config.write_text(json.dumps({"weights_sha256": "w", "test_gt_sha256": "g"}))
    assert test_done(tmp_path, "w", "g")
    with pytest.raises(RuntimeError, match="Kiểm tra tay"):
        test_done(tmp_path, "other", "g")
    with pytest.raises(RuntimeError):
        test_done(tmp_path, "w", "other")


def test_latency_done_requires_same_weights(nb, tmp_path):
    assert not nb["latency_done"](tmp_path, "w")
    (tmp_path / "latency.json").write_text(json.dumps({"weights_sha256": "w"}))
    assert nb["latency_done"](tmp_path, "w")
    assert not nb["latency_done"](tmp_path, "other")


@pytest.mark.parametrize("values", [[5.0], [3.0, 1.0], list(np.random.default_rng(0).random(200))])
def test_latency_summary_matches_numpy(nb, values):
    summary = nb["latency_summary"](values)
    assert summary["n"] == len(values)
    assert summary["p50_ms"] == pytest.approx(np.percentile(values, 50))
    assert summary["p95_ms"] == pytest.approx(np.percentile(values, 95))
    assert summary["mean_ms"] == pytest.approx(np.mean(values))
    assert (summary["min_ms"], summary["max_ms"]) == (min(values), max(values))
    with pytest.raises(ValueError):
        nb["latency_summary"]([])


def test_time_pipeline_excludes_warmup_and_syncs(nb):
    calls, syncs = [], []
    ticks = iter([0.0, 0.010, 1.0, 1.025])
    samples = nb["time_pipeline"](
        calls.append, [b"w"], [b"a", b"b"], sync=lambda: syncs.append(1), clock=lambda: next(ticks)
    )
    assert calls == [b"w", b"a", b"b"]
    assert samples == pytest.approx([10.0, 25.0])
    assert len(syncs) == 3


def test_logged_writes_header_traceback_and_footer(nb, tmp_path):
    path = tmp_path / "logs/predict.log"
    with nb["logged"](path, "yolov8"):
        nb["log"]("dòng 1")
    with pytest.raises(RuntimeError), nb["logged"](path, "yolov8"):
        raise RuntimeError("hỏng")
    text = path.read_text(encoding="utf-8")
    assert text.count("· yolov8 ---") == 2
    assert "dòng 1" in text and "· xong ---" in text
    assert "RuntimeError: hỏng" in text and text.rstrip().endswith("· lỗi ---")
    assert nb["LOG"] is None


def test_write_json_is_atomic_and_strict(nb, tmp_path):
    path = tmp_path / "out.json"
    nb["write_json"](path, {"mã": "P.102"})
    assert json.loads(path.read_text(encoding="utf-8")) == {"mã": "P.102"}
    with pytest.raises(ValueError):
        nb["write_json"](path, {"x": float("nan")})
    assert json.loads(path.read_text(encoding="utf-8")) == {"mã": "P.102"}


def test_package_versions_reports_missing_as_none(nb):
    versions = nb["package_versions"](("pytest", "signa-ai-không-có"))
    assert versions["pytest"] == version("pytest")
    assert versions["signa-ai-không-có"] is None


def test_notebook_has_no_outputs_and_t4_metadata():
    notebook = json.loads((AI_ROOT / "notebooks" / NOTEBOOK).read_text(encoding="utf-8"))
    assert notebook["metadata"]["colab"]["gpuType"] == "T4"
    for cell in notebook["cells"]:
        if cell["cell_type"] == "code":
            assert cell["outputs"] == [] and cell["execution_count"] is None
