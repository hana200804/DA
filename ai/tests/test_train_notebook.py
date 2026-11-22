"""Kiểm tra logic của notebook mà không cần GPU, Drive hoặc Ultralytics."""

import ast
import csv
import hashlib
import json
import time
from datetime import UTC, datetime
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

import numpy as np
import pytest
import yaml

from signa_ai.augment import ULTRALYTICS_ARGS
from signa_ai.detection import Box, Detection, to_coco_predictions
from signa_ai.labels import load_label_map
from signa_ai.yolo_training import (
    check_resume_config,
    file_sha256,
    freeze_requirements,
    load_stop_state,
    restore_stop_state,
    save_stop_state,
    write_run_file,
)

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
            "Path": Path,
            "time": time,
            "yaml": yaml,
            "patch": patch,
            "check_resume_config": check_resume_config,
            "file_sha256": file_sha256,
            "freeze_requirements": freeze_requirements,
            "load_stop_state": load_stop_state,
            "restore_stop_state": restore_stop_state,
            "save_stop_state": save_stop_state,
            "write_run_file": write_run_file,
            "REPO_COMMIT": "repo-sha",
            "NOTEBOOK_COMMIT": None,
            "GPU": "fake",
            "IMGSZ": 640,
            "label_map": SimpleNamespace(version=1),
        },
    )


def notebook_constants(filename):
    """Hằng số gán bằng literal ở cấp cao nhất của các ô mã."""
    notebook = json.loads((AI_ROOT / "notebooks" / filename).read_text())
    constants = {}
    for cell in notebook["cells"]:
        if cell["cell_type"] != "code":
            continue
        source = "\n".join(
            line for line in "".join(cell["source"]).splitlines() if not line.startswith("%")
        )
        for node in ast.parse(source).body:
            if isinstance(node, ast.Assign) and len(node.targets) == 1:
                target = node.targets[0]
                if isinstance(target, ast.Name):
                    try:
                        constants[target.id] = ast.literal_eval(node.value)
                    except ValueError:
                        pass
    return constants


def load_yolov8_main(imgsz, **extra):
    constants = notebook_constants("train_yolov8_main_v1.1.ipynb")
    functions = load_main_notebook("v8")
    functions.update(
        IMGSZ=imgsz,
        SEED=constants["SEED"],
        MAIN_BATCH=constants["MAIN_BATCH"],
        BATCH=constants["MAIN_BATCH"],
        EPOCHS=200,
        SMOKE=False,
        config_path=Path("/content/signa-data.yaml"),
        DRIVE_ROOT=Path("/content/drive/MyDrive/Signa/experiments"),
        ULTRALYTICS_ARGS=ULTRALYTICS_ARGS,
        label_map=SimpleNamespace(version=1),
        **extra,
    )
    return functions


SETTINGS = {"IMGSZ", "SIZES", "MAIN_BATCH", "BATCH"}


def yolov8_settings(environ):
    """Chạy các dòng cấu hình đọc biến môi trường, kèm assert kiểm tra chúng."""
    notebook = json.loads((AI_ROOT / "notebooks" / "train_yolov8_main_v1.1.ipynb").read_text())
    nodes = []
    for cell in notebook["cells"]:
        if cell["cell_type"] != "code":
            continue
        for node in ast.parse("".join(cell["source"])).body:
            names = {n.id for n in ast.walk(node) if isinstance(n, ast.Name)}
            assigned = isinstance(node, ast.Assign) and {
                target.id for target in node.targets if isinstance(target, ast.Name)
            } & SETTINGS
            checked = isinstance(node, ast.Assert) and names & SETTINGS
            if assigned or checked:
                nodes.append(node)
    namespace = {"os": SimpleNamespace(environ=environ)}
    exec(compile(ast.Module(body=nodes, type_ignores=[]), "settings", "exec"), namespace)
    return {name: namespace[name] for name in SETTINGS}


def test_yolov8_defaults_keep_main_run():
    assert yolov8_settings({}) == {"IMGSZ": 640, "SIZES": ["n", "s"], "MAIN_BATCH": 16, "BATCH": 16}


def test_yolov8_settings_from_environment():
    settings = yolov8_settings({"SIGNA_IMGSZ": "960", "SIGNA_SIZES": "s"})
    assert settings == {"IMGSZ": 960, "SIZES": ["s"], "MAIN_BATCH": 16, "BATCH": 16}
    assert yolov8_settings({"SIGNA_SIZES": " n , s "})["SIZES"] == ["n", "s"]
    assert yolov8_settings({"SIGNA_BATCH": "8"})["BATCH"] == 8


@pytest.mark.parametrize("environ", [{"SIGNA_IMGSZ": "1000"}, {"SIGNA_SIZES": "m"}])
def test_yolov8_settings_reject_invalid(environ):
    with pytest.raises(AssertionError):
        yolov8_settings(environ)


@pytest.mark.parametrize("size", ["n", "s"])
def test_yolov8_640_reproduces_main_config(size):
    functions = load_yolov8_main(640)
    name = functions["run_name"](size)
    assert name == f"yolov8{size}-640-v1.1-main-s0"
    committed = yaml.safe_load((AI_ROOT / "experiments" / name / "config.yaml").read_text())

    config = functions["run_config"](functions["train_args"](size))

    assert config == committed
    assert "batch_note" not in config


def test_yolov8_960_names_run_and_records_imgsz():
    functions = load_yolov8_main(960)
    assert functions["run_name"]("s") == "yolov8s-960-v1.1-main-s0"
    assert (AI_ROOT / "experiments" / "yolov8s-960-v1.1-main-s0" / "README.md").is_file()

    config = functions["run_config"](functions["train_args"]("s"))

    assert config["imgsz"] == 960
    assert config["name"] == "yolov8s-960-v1.1-main-s0"
    assert config["batch"] == 16 and "batch_note" not in config


def test_yolov8_smaller_batch_is_noted():
    functions = load_yolov8_main(960)
    functions["BATCH"] = 8

    config = functions["run_config"](functions["train_args"]("s"))

    assert config["batch"] == 8
    assert "16" in config["batch_note"] and "8" in config["batch_note"]


def test_yolov8_meta_and_export_config_record_imgsz(tmp_path):
    def fake_evaluate(*args):
        out = Path(args[args.index("--out") + 1])
        out.write_text(json.dumps({"mAP50": 0.5}))

    functions = load_yolov8_main(
        960,
        hashlib=hashlib,
        sh=Mock(side_effect=fake_evaluate),
        sys=SimpleNamespace(executable="python"),
        DATASET=tmp_path,
        REPO=tmp_path,
        EVAL_CONFIDENCE=0.001,
        GROUND_TRUTH={"val": {"images": []}},
        time=SimpleNamespace(monotonic=Mock(return_value=0)),
    )
    functions["start_session"](tmp_path, {})
    best = tmp_path / "best.pt"
    best.write_bytes(b"weights")

    functions["write_predictions_and_evaluate"](tmp_path, best, lambda truth: [])

    assert json.loads((tmp_path / "meta.json").read_text())["imgsz"] == 960
    export = json.loads((tmp_path / "evaluation" / "export-config.json").read_text())
    assert export["imgsz"] == 960
    assert export["weights_sha256"] == hashlib.sha256(b"weights").hexdigest()


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
    functions["ultralytics"] = SimpleNamespace(__version__="8.4.215")
    registered = {}

    def register(name, callback):
        registered[name] = callback

    functions["Callbacks"] = Mock(return_value=SimpleNamespace(register_action=register))
    meta_path = tmp_path / "meta.json"
    resume = str(tmp_path / "weights/last.pt")

    def train(opt, callbacks):
        assert opt.resume == resume
        assert callbacks is functions["Callbacks"].return_value
        registered["on_model_save"](None, 5, False, 0.5, 0.5)
        session = json.loads(meta_path.read_text())["sessions"][0]
        assert session["gpu_hours"] == 1
        assert session["epoch"] == 6

    functions["yolov5_train"] = SimpleNamespace(
        parse_opt=Mock(return_value=SimpleNamespace()), main=train,
        EarlyStopping=Mock(),
    )

    functions["train_with_tracking"](tmp_path, {"patience": 30}, {}, stop_state={})

    assert "on_model_save" in registered
    meta = json.loads(meta_path.read_text())
    assert meta["notebook_commit"] is None
    assert "SIGNA_NOTEBOOK_COMMIT" in meta["notebook_commit_note"]
    assert meta["sessions"][0]["gpu_hours"] == 2


class StopperProbe:
    def __init__(self, patience):
        self.patience = patience
        self.best_fitness = 0.0
        self.best_epoch = 0
        self.possible_stop = False

    def __call__(self, epoch, fitness):
        if fitness > self.best_fitness:
            self.best_fitness = fitness
            self.best_epoch = epoch
        elapsed = epoch - self.best_epoch
        self.possible_stop = elapsed >= self.patience - 1
        return elapsed >= self.patience


def main_training_namespace(version, run_dir):
    functions = load_main_notebook(version)
    functions.update(
        BATCH=16,
        SEED=0,
        EPOCHS=200,
        SMOKE=False,
        MAIN_BATCH=16,
        IMGSZ=960,
        DRIVE_ROOT=run_dir.parent,
        config_path=run_dir.parent / "data.yaml",
        data_path=run_dir.parent / "data.yaml",
        HYP=AI_ROOT / "configs/yolov5-hyp.yaml",
        ULTRALYTICS_ARGS=ULTRALYTICS_ARGS,
        NOTEBOOK_COMMIT="notebook-sha",
        YOLOV5_COMMIT="yolov5-sha",
        ALLOW_BATCH_CHANGE=False,
        ultralytics=SimpleNamespace(__version__="test"),
    )
    return functions


@pytest.mark.parametrize("version", ["v5", "v8"])
@pytest.mark.parametrize(
    ("batch", "allow", "invalid"),
    [(2, False, None), (16, False, "batch"), (8, True, None), (8, True, "seed")],
)
def test_main_resume_checks_checkpoint_and_restores_patience(
    tmp_path, version, batch, allow, invalid
):
    run_dir = tmp_path / f"yolo{version}s-{960 if version == 'v8' else 640}-v1.1-main-s0"
    run_dir.mkdir()
    functions = main_training_namespace(version, run_dir)
    args = functions["train_args"]("s")
    config_file = run_dir / "config.yaml"
    original_config = yaml.safe_dump({"dataset_version": "v1.1", "label_map_version": 1, **args})
    config_file.write_text(original_config)  # Cấu hình có thể mới hơn checkpoint.
    batch_key = "batch" if version == "v8" else "batch_size"
    saved = {**args, batch_key: 2}
    if invalid == "seed":
        saved["seed"] = 1
    epoch_offset = int(version == "v8")
    saved_stopper = StopperProbe(args["patience"])
    peak = np.array([0.75]) if version == "v5" else 0.75
    assert not saved_stopper(10, peak)
    for epoch in range(11, 36):
        assert not saved_stopper(epoch, 0.5)
    save_stop_state(run_dir, saved_stopper, 35 - epoch_offset, epoch_offset=epoch_offset)
    # Phiên mất ngay trước khi last.pt được ghi lại vẫn giữ trạng thái epoch cũ.
    saved_stopper(36, 0.8)
    save_stop_state(run_dir, saved_stopper, 36 - epoch_offset, epoch_offset=epoch_offset)
    functions.update(BATCH=batch, ALLOW_BATCH_CHANGE=allow)
    events = {}
    observed_batch = []

    def finish(stopper):
        assert stopper.best_epoch == 10 and stopper.best_fitness == 0.75
        for epoch in range(36, 40):
            assert not stopper(epoch, 0.5)
        assert stopper.possible_stop
        assert stopper(40, 0.5)

    if version == "v8":
        saved.update(device="0", fraction=1.0)
        checkpoint = {"train_args": saved, "epoch": 34}
        trainer = SimpleNamespace(
            stopper=StopperProbe(args["patience"]), epoch=39, save_model=Mock(),
        )

        def train(**kwargs):
            assert kwargs["resume"] is True
            observed_batch.append(kwargs["batch"])
            events["on_train_start"](trainer)
            finish(trainer.stopper)
            trainer.save_model()

        functions["YOLO"] = Mock(return_value=SimpleNamespace(
            ckpt=checkpoint, add_callback=events.__setitem__, train=train
        ))
    else:
        saved["hyp"] = yaml.safe_load(Path(args["hyp"]).read_text())
        saved["optimizer"] = "SGD"
        checkpoint = {"opt": saved, "epoch": 35}
        functions["torch"].load.return_value = checkpoint
        opt_yaml = run_dir / "opt.yaml"
        stale_opts = yaml.safe_dump({**saved, batch_key: 16, "optimizer": "Adam"})
        opt_yaml.write_text(stale_opts)

        def main(opt, callbacks):
            assert opt.resume == str(run_dir / "weights/last.pt")
            native_opt = yaml.safe_load(opt_yaml.read_text())
            observed_batch.append(native_opt["batch_size"])
            assert native_opt["optimizer"] == "SGD"
            stopper = yolov5.EarlyStopping(native_opt["patience"])
            events["on_train_start"]()
            finish(stopper)
            events["on_fit_epoch_end"]([], 40, 0.75, 0.5)

        def register(name, callback):
            events[name] = callback

        yolov5 = SimpleNamespace(
            parse_opt=Mock(return_value=SimpleNamespace()), main=main,
            EarlyStopping=StopperProbe,
        )
        functions["yolov5_train"] = yolov5
        functions["Callbacks"] = Mock(return_value=SimpleNamespace(register_action=register))

    if invalid:
        with pytest.raises(ValueError, match=invalid):
            functions["train_run"](run_dir, "s", "resume")
        assert config_file.read_text() == original_config
        if version == "v5":
            assert opt_yaml.read_text() == stale_opts
        assert not (run_dir / "meta.json").exists()
        assert not observed_batch
        return

    original_session = {"batch": 2, "epoch": 35}
    meta_file = run_dir / "meta.json"
    meta_file.write_text(json.dumps({
        "coco_weights_sha256": "original-coco-sha",
        "sessions": [original_session],
    }))
    functions["train_run"](run_dir, "s", "resume")

    assert observed_batch == [batch]
    assert yaml.safe_load(config_file.read_text())[batch_key] == batch
    meta = json.loads(meta_file.read_text())
    assert meta["coco_weights_sha256"] == "original-coco-sha"
    previous_session, session = meta["sessions"]
    assert previous_session == original_session
    assert session["batch"] == batch and session["checkpoint_batch"] == 2
    requirements = (run_dir / "requirements.txt").read_bytes()
    assert requirements == (run_dir / session["requirements_file"]).read_bytes()
    assert b"pytest==" in requirements
    state = load_stop_state(run_dir, 40 - epoch_offset, epoch_offset=epoch_offset)
    assert state == {"best_epoch": 10, "best_fitness": 0.75, "epochs_without_improvement": 30}


@pytest.mark.parametrize("version", ["v5", "v8"])
def test_main_new_run_records_notebook_and_initial_coco_sha(tmp_path, version):
    functions = main_training_namespace(version, tmp_path)
    weights = tmp_path / f"yolo{version}s.pt"
    weights.write_bytes(b"initial-coco-weights")
    train = Mock()
    if version == "v8":
        functions["YOLO"] = Mock(return_value=SimpleNamespace(
            ckpt_path=str(weights), add_callback=Mock(), train=train
        ))
    else:
        functions["attempt_download"] = Mock(return_value=str(weights))
        functions["yolov5_train"] = SimpleNamespace(
            parse_opt=Mock(return_value=SimpleNamespace()),
            main=train, EarlyStopping=StopperProbe,
        )
        functions["Callbacks"] = Mock()

    functions["train_run"](tmp_path, "s", "new")

    train.assert_called_once()
    meta = json.loads((tmp_path / "meta.json").read_text())
    assert meta["notebook_commit"] == "notebook-sha"
    assert meta["coco_weights_sha256"] == hashlib.sha256(weights.read_bytes()).hexdigest()
    assert meta["sessions"][0]["batch"] == 16


def test_resume_rejects_missing_epoch_state(tmp_path):
    with pytest.raises(ValueError, match="Thiếu trạng thái dừng sớm"):
        load_stop_state(tmp_path, 18)


def test_yolov8_train_end_preserves_best_selection(tmp_path):
    functions = load_main_notebook("v8")
    functions["time"] = SimpleNamespace(monotonic=Mock(side_effect=[0, 3600, 7200]))
    functions["ultralytics"] = SimpleNamespace(__version__="8.3.203")
    functions["NOTEBOOK_COMMIT"] = "notebook-sha"
    callbacks = {}
    functions["track"](SimpleNamespace(add_callback=callbacks.__setitem__), tmp_path, {})
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
