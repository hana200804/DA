import json
import subprocess
import sys

import pytest

from scripts.make_model_card import tflite_card
from service.app import ModelInfo


def write_json(path, data):
    path.write_text(json.dumps(data), encoding="utf-8")
    return path


def test_make_model_card_cli(tmp_path):
    run = tmp_path / "yolov8n-640-v1.1-main-s0"
    run.mkdir()
    (run / "config.yaml").write_text(
        "dataset_version: v1.1\nlabel_map_version: 1\nimgsz: 640\nseed: 0\n",
        encoding="utf-8",
    )
    write_json(
        run / "meta.json",
        {
            "dataset_version": "v1.1",
            "label_map_version": 1,
            "repo_commit": "abc",
        },
    )
    write_json(run / "best-selection.json", {"epoch": 10})
    write_json(
        run / "metrics.json",
        {
            "label_map_version": 1,
            "map_50": 0.8,
            "map_50_95": 0.7,
            "ap_by_size": {"small": 0.5, "medium": 0.7, "large": None},
            "operating_point": {"threshold": 0.4, "precision": 0.9, "recall": 0.8},
            "prohibitory_recall": 0.85,
            "cost": {"size_mb": 9, "cpu_ms": None, "gpu_ms": None, "mobile_fps": None},
        },
    )
    export = write_json(
        tmp_path / "export-config.json",
        {
            "datasetVersion": "v1.1",
            "labelMapVersion": 1,
            "imgsz": 640,
            "weights_sha256": "a" * 64,
        },
    )
    subprocess.run(
        [
            sys.executable,
            "scripts/make_model_card.py",
            str(run),
            "--version",
            "v1.0",
            "--export-config",
            str(export),
        ],
        check=True,
    )
    card = json.loads((run / "model_card.json").read_text(encoding="utf-8"))
    assert ModelInfo.model_validate(card, strict=True).modelVersion == "yolov8n-v1.0"
    assert card["datasetVersion"] == "v1.1"
    assert card["selection"]["epoch"] == 10
    assert card["weightsSha256"] == "a" * 64
    assert card["operating_point"]["threshold"] == 0.4
    assert card["val"]["map_50_95"] == 0.7
    assert card["test"] is card["field"] is card["serverLatencyP95Ms"] is None


def test_tflite_card_keeps_tensor_contract_and_checks_provenance(tmp_path):
    summary = tmp_path / "summary.csv"
    summary.write_text(
        "config,size_mb,map_50,map_50_95,prohibitory_recall,operating_conf,cpu_ms_per_image\n"
        "640-fp16,4,0.8,0.7,0.8,0.4,130\n",
        encoding="utf-8",
    )
    tensors = {
        "input": {"shape": [1, 640, 640, 3], "dtype": "float32"},
        "output": {"shape": [1, 50, 8400], "dtype": "float32"},
    }
    meta = write_json(
        tmp_path / "export-meta.json",
        {
            "identity": {
                "source_run": "run",
                "model": "yolov8",
                "label_map_version": 1,
                "val_gt_sha256": "gt",
                "weights_sha256": "weights",
                "size": 640,
                "precision": "fp16",
                "cpu_model": "CPU",
                "cpu_threads": 2,
            },
            "evaluation_split": "val",
            "size_bytes": 4000000,
            "size_mb": 4,
            "sha256": {"predictions-val.json": "predictions", "model.tflite": "model"},
            "tensors": tensors,
        },
    )
    analysis_path = tmp_path / "analysis.json"
    analysis = {
        "sha256": {"ground_truth": "gt", "predictions": "predictions"},
        "label_map_version": 1,
        "map_50": 0.8,
        "map_50_95": 0.7,
        "ap_by_size": {},
        "prohibitory_recall": 0.8,
        "operating_point": {"threshold": 0.4},
        "prohibitory_operating_point": {"threshold": 0.2, "prohibitory_recall": 0.85},
    }
    paths = [summary, meta, write_json(analysis_path, analysis)]
    base_card = {"modelName": "yolov8", "labelMapVersion": 1, "weightsSha256": "weights"}
    tflite = tflite_card(paths, "run", base_card)
    assert tflite["tensors"] == tensors
    assert tflite["cpuMsPerImage"] == 130
    assert tflite["operating_point"]["threshold"] == 0.2
    assert tflite["deviceFps"] is None

    analysis["sha256"]["predictions"] = "different"
    write_json(analysis_path, analysis)
    with pytest.raises(ValueError, match="Nguồn TFLite không khớp"):
        tflite_card(paths, "run", base_card)
