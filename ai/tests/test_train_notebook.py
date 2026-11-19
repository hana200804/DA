"""Kiểm tra logic xuất của notebook mà không cần GPU, Drive hoặc Ultralytics."""

import ast
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from signa_ai.detection import Box, Detection, to_coco_predictions
from signa_ai.labels import load_label_map

NOTEBOOK = Path(__file__).resolve().parents[1] / "notebooks/train_yolov8n_v1.1.ipynb"


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
    notebook = json.loads(NOTEBOOK.read_text())
    for cell in notebook["cells"]:
        if cell["cell_type"] != "code":
            continue
        source = "".join(cell["source"])
        source = "\n".join(line for line in source.splitlines() if not line.startswith("%"))
        tree = ast.parse(source)
        functions = [node for node in tree.body if isinstance(node, ast.FunctionDef)]
        exec(compile(ast.Module(body=functions, type_ignores=[]), str(NOTEBOOK), "exec"), namespace)
    return namespace


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
