"""Kiểm tra logic của notebook mà không cần GPU, Drive hoặc Ultralytics."""

import ast
import csv
import json
from datetime import UTC, datetime
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock

import pytest

from signa_ai.detection import Box, Detection, to_coco_predictions
from signa_ai.labels import load_label_map

AI_ROOT = Path(__file__).resolve().parents[1]


def load_notebook_functions(filename, namespace):
    path = AI_ROOT / "notebooks" / filename
    notebook = json.loads(path.read_text())
    for cell in notebook["cells"]:
        if cell["cell_type"] != "code":
            continue
        source = "".join(cell["source"])
        source = "\n".join(line for line in source.splitlines() if not line.startswith("%"))
        tree = ast.parse(source)
        functions = [node for node in tree.body if isinstance(node, ast.FunctionDef)]
        exec(compile(ast.Module(body=functions, type_ignores=[]), str(path), "exec"), namespace)
    return namespace


@pytest.fixture
def notebook_functions():
    label_map = load_label_map()
    namespace = {
        "Box": Box,
        "Detection": Detection,
        "to_coco_predictions": to_coco_predictions,
        "label_map": label_map,
        "NAMES": label_map.names,
    }
    return load_notebook_functions("train_yolov8n_v1.1.ipynb", namespace)


def load_main_notebook(version):
    return load_notebook_functions(
        f"train_yolo{version}_main_v1.1.ipynb",
        {
            "csv": csv,
            "json": json,
            "torch": SimpleNamespace(load=Mock(), __version__="fake"),
            "datetime": datetime,
            "UTC": UTC,
            "REPO_COMMIT": "repo-sha",
            "NOTEBOOK_COMMIT": None,
            "GPU": "fake",
            "label_map": SimpleNamespace(version=1),
        },
    )


@pytest.mark.parametrize("size", ["n", "s"])
def test_yolov5_best_selection_matches_committed_results(size, tmp_path):
    functions = load_main_notebook("v5")
    run = AI_ROOT / "experiments" / f"yolov5{size}-640-v1.1-main-s0"
    (tmp_path / "results.csv").write_bytes((run / "results.csv").read_bytes())

    functions["write_best_selection"](tmp_path)

    assert (tmp_path / "best-selection.json").read_bytes() == (
        run / "best-selection.json"
    ).read_bytes()


@pytest.mark.parametrize("version", ["v5", "v8"])
@pytest.mark.parametrize(
    ("checkpoint", "expected"),
    [({"optimizer": {}}, "resume"), ({"optimizer": None}, "trained"), ({}, "trained")],
)
def test_run_status_loads_on_cpu(version, checkpoint, expected, tmp_path):
    functions = load_main_notebook(version)
    last = tmp_path / "weights" / "last.pt"
    last.parent.mkdir()
    last.touch()
    load = functions["torch"].load
    load.return_value = checkpoint

    assert functions["run_status"](tmp_path) == expected
    load.assert_called_once_with(last, map_location="cpu", weights_only=False)


@pytest.mark.parametrize("version", ["v5", "v8"])
def test_run_status_handles_new_and_done(version, tmp_path):
    functions = load_main_notebook(version)
    run_status = functions["run_status"]
    assert run_status(tmp_path) == "new"

    last = tmp_path / "weights" / "last.pt"
    last.parent.mkdir()
    last.touch()
    (tmp_path / "done.json").write_text("{}")

    assert run_status(tmp_path) == "done"
    functions["torch"].load.assert_not_called()


def test_yolov5_records_gpu_time_at_checkpoint(tmp_path):
    functions = load_main_notebook("v5")
    functions["time"] = SimpleNamespace(monotonic=Mock(side_effect=[0, 0, 3600, 7200]))
    functions["YOLOV5_COMMIT"] = "upstream-sha"
    register = Mock()
    functions["Callbacks"] = Mock(return_value=SimpleNamespace(register_action=register))
    meta_path = tmp_path / "meta.json"
    resume = str(tmp_path / "weights/last.pt")

    def train(opt, callbacks):
        assert opt.resume == resume
        assert callbacks is functions["Callbacks"].return_value
        register.call_args.kwargs["callback"](None, 5, False, 0.5, 0.5)
        session = json.loads(meta_path.read_text())["sessions"][0]
        assert session["gpu_hours"] == 1
        assert session["epoch"] == 6

    functions["yolov5_train"] = SimpleNamespace(
        parse_opt=Mock(return_value=SimpleNamespace()), main=train
    )

    functions["train_with_tracking"](tmp_path, resume=resume)

    assert register.call_args.args == ("on_model_save",)
    meta = json.loads(meta_path.read_text())
    assert meta["notebook_commit"] is None
    assert "SIGNA_NOTEBOOK_COMMIT" in meta["notebook_commit_note"]
    assert meta["sessions"][0]["gpu_hours"] == 2


def test_yolov8_train_end_preserves_best_selection(tmp_path):
    functions = load_main_notebook("v8")
    functions["time"] = SimpleNamespace(monotonic=Mock(side_effect=[0, 3600, 7200]))
    functions["ultralytics"] = SimpleNamespace(__version__="8.3.203")
    functions["NOTEBOOK_COMMIT"] = "notebook-sha"
    callbacks = {}
    functions["track"](SimpleNamespace(add_callback=callbacks.__setitem__), tmp_path)
    trainer = SimpleNamespace(
        epoch=6,
        fitness=0.6,
        best_fitness=0.6,
        metrics={"metrics/mAP50-95(B)": 0.6},
        plot_metrics=Mock(),
    )
    callbacks["on_model_save"](trainer)
    trainer.metrics = {"metrics/mAP50-95(B)": 0.59}

    callbacks["on_train_end"](trainer)

    selection = json.loads((tmp_path / "best-selection.json").read_text())
    assert selection == {"epoch": 7, "fitness": 0.6, "val": {"metrics/mAP50-95(B)": 0.6}}
    meta = json.loads((tmp_path / "meta.json").read_text())
    assert meta["notebook_commit"] == "notebook-sha"
    assert meta["sessions"][0]["gpu_hours"] == 2


def test_qc_wrong_class_missing_low_conf_and_one_to_one(notebook_functions):
    suspect = notebook_functions["suspicious_boxes"]
    names = {0: "P.102", 1: "P.103a"}
    source = [{"category_id": 0, "bbox": [0, 0, 10, 10]}]
    pred = {"category_id": 1, "bbox": [1, 1, 10, 10], "score": 0.9}
    assert suspect(source, [pred], names) == [
        {
            "box_index": 1,
            "original_code": "P.102",
            "predicted_code": "P.103a",
            "confidence": 0.9,
            "reason": "different_class",
        }
    ]
    assert suspect(source, [{**pred, "score": 0.7}], names) == []
    assert suspect(source, [{**pred, "category_id": 0}], names) == []
    assert suspect([], [pred], names) == []
    assert suspect(source, [], names)[0]["reason"] == "not_detected"
    distant = {**pred, "bbox": [20, 20, 10, 10]}
    assert suspect(source, [distant], names)[0]["predicted_code"] == ""
    duplicate_sources = source + [{"category_id": 0, "bbox": [2, 2, 8, 8]}]
    rows = suspect(duplicate_sources, [{**pred, "bbox": [0, 0, 10, 10]}], names)
    assert [row["reason"] for row in rows] == ["different_class", "not_detected"]
    assert [row["box_index"] for row in rows] == [1, 2]


class FakeTensor:
    def __init__(self, values):
        self.values = values

    def cpu(self):
        return self

    def tolist(self):
        return self.values


def test_coco_export_preserves_image_id_zero_category_and_pixel_box(notebook_functions):
    convert = notebook_functions["result_predictions"]
    result = SimpleNamespace(
        boxes=SimpleNamespace(
            xyxy=FakeTensor([[10.0, 20.0, 40.0, 60.0]]),
            cls=FakeTensor([0.0]),
            conf=FakeTensor([0.001]),
        )
    )
    assert convert(result, 817) == [
        {
            "image_id": 817,
            "category_id": 0,
            "bbox": [10.0, 20.0, 30.0, 40.0],
            "score": 0.001,
        }
    ]
    empty = SimpleNamespace(
        boxes=SimpleNamespace(
            xyxy=FakeTensor([]),
            cls=FakeTensor([]),
            conf=FakeTensor([]),
        )
    )
    assert convert(empty, 817) == []
