import json
import os
import subprocess
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest
import yaml

from scripts.export_tflite import (
    ARTIFACTS,
    calibration_config,
    complete,
    export_model,
    sha256,
    split_paths,
)
from signa_ai.labels import load_label_map


@pytest.mark.parametrize(
    "filename", ("export_tflite_v1.1.ipynb", "export_tflite_yolov5_v1.1.ipynb")
)
def test_notebook_export_overrides_inline_matplotlib_backend(tmp_path, monkeypatch, filename):
    path = Path(__file__).resolve().parents[1] / "notebooks" / filename
    notebook = json.loads(path.read_text(encoding="utf-8"))
    (cell,) = [cell for cell in notebook["cells"] if cell["id"] == "xuat-va-cham-val"]

    script = tmp_path / "ai/scripts/export_tflite.py"
    script.parent.mkdir(parents=True)
    script.write_text('import os\nprint(os.environ["MPLBACKEND"])\n', encoding="utf-8")
    output = tmp_path / "Signa/exports/test-run"
    output.mkdir(parents=True)
    (output / "summary.csv").touch()
    inline_backend = "module://matplotlib_inline.backend_inline"
    monkeypatch.setenv("MPLBACKEND", inline_backend)

    def popen(command, *, cwd, **kwargs):
        assert cwd == "/content"
        return subprocess.Popen(command, cwd=tmp_path, **kwargs)

    namespace = {
        "DATASET": tmp_path,
        "DRIVE": tmp_path,
        "Path": Path,
        "REPO": tmp_path,
        "REPO_COMMIT": "test-sha",
        "SOURCE_RUN": "test-run",
        "YOLOV5_REPO": tmp_path,
        "os": os,
        "python": sys.executable,
        "subprocess": SimpleNamespace(
            Popen=popen, PIPE=subprocess.PIPE, STDOUT=subprocess.STDOUT,
            CalledProcessError=subprocess.CalledProcessError,
        ),
    }
    exec(compile("".join(cell["source"]), str(path), "exec"), namespace)

    assert "Agg" in (output / "export.log").read_text(encoding="utf-8").splitlines()
    assert os.environ["MPLBACKEND"] == inline_backend


def test_calibration_yaml_only_lists_train_images(tmp_path):
    paths = [f"images/{split}/image.jpg" for split in ("train", "val", "test")]
    for name in paths:
        path = tmp_path / name
        path.parent.mkdir(parents=True)
        path.touch()
    manifest = tmp_path / "manifest.csv"
    manifest.write_text(
        f"path,split\n{paths[0]},train\n{paths[1]},val\n{paths[2]},test\n", encoding="utf-8"
    )
    train = split_paths(tmp_path, manifest, "train")
    config_path, count = calibration_config(tmp_path, tmp_path, load_label_map(), train, "yolov8")
    config = yaml.safe_load(config_path.read_text(encoding="utf-8"))
    assert count == 1
    assert config["train"] == config["val"]
    assert (tmp_path / "calibration-train.txt").read_text().splitlines() == [
        str(tmp_path / paths[0])
    ]
    with manifest.open("a", encoding="utf-8") as stream:
        stream.write(f"{paths[1]},train\n")
    with pytest.raises(ValueError, match="ngoài split train"):
        split_paths(tmp_path, manifest, "train")


def test_resume_requires_matching_settings_and_intact_artifacts(tmp_path):
    assert not complete(tmp_path, {"size": 480})
    for filename in ARTIFACTS:
        (tmp_path / filename).write_text(filename, encoding="utf-8")
    meta = {
        "identity": {"size": 480},
        "sha256": {filename: sha256(tmp_path / filename) for filename in ARTIFACTS},
    }
    (tmp_path / "export-meta.json").write_text(json.dumps(meta), encoding="utf-8")
    assert complete(tmp_path, {"size": 480})
    assert not complete(tmp_path, {"size": 640})
    (tmp_path / "model.tflite").write_bytes(b"")
    assert not complete(tmp_path, {"size": 480})


@pytest.mark.parametrize(("int8", "suffix"), [(False, "float16"), (True, "full_integer_quant")])
def test_yolov8_uses_compatible_opset_and_preserves_precision(tmp_path, monkeypatch, int8, suffix):
    weights = tmp_path / "best.pt"
    labels = load_label_map()
    data = tmp_path / "calibration.yaml"
    saved_model = tmp_path / "best_saved_model"

    def export(**kwargs):
        assert kwargs["opset"] == 19
        assert kwargs["nms"] is False
        assert kwargs["int8"] is int8
        assert kwargs["half"] is not int8
        assert kwargs["split"] == "train"
        assert kwargs["data"] == str(data)
        return str(saved_model / ("best_int8.tflite" if int8 else "best_float16.tflite"))

    detector = SimpleNamespace(task="detect", names=labels.names, export=export)
    monkeypatch.setitem(sys.modules, "ultralytics", SimpleNamespace(YOLO=lambda path: detector))
    assert export_model(weights, labels, "yolov8", 640, int8, data, None) == (
        saved_model / f"best_{suffix}.tflite"
    )


@pytest.mark.parametrize(
    ("returned", "created"),
    [(True, True), (True, False), (False, True)],
    ids=["reported-file", "missing-file", "unreported-file"],
)
def test_yolov5_export_requires_new_reported_file(tmp_path, monkeypatch, returned, created):
    weights = tmp_path / "best.pt"
    target = tmp_path / "exporter-output.tflite"
    staging = tmp_path / "model.tflite"
    target.write_bytes(b"640")
    staging.write_bytes(b"640")
    labels = load_label_map()

    def run(**kwargs):
        assert kwargs["imgsz"] == (480, 480)
        assert not target.exists()
        assert not staging.exists()
        if created:
            target.write_bytes(b"480")
        files = [str(tmp_path / "best_saved_model")]
        return [*files, str(target)] if returned else files

    monkeypatch.setattr(sys, "path", sys.path.copy())
    monkeypatch.setitem(sys.modules, "torch", SimpleNamespace(device=str))
    monkeypatch.setitem(sys.modules, "export", SimpleNamespace(run=run))
    monkeypatch.setitem(
        sys.modules,
        "models.experimental",
        SimpleNamespace(attempt_load=lambda *args, **kwargs: SimpleNamespace(names=labels.names)),
    )
    args = (weights, labels, "yolov5", 480, True, tmp_path / "data.yaml", tmp_path)
    if returned and created:
        assert export_model(*args) == target
        assert target.read_bytes() == b"480"
    else:
        with pytest.raises(RuntimeError, match="YOLOv5 480px INT8 thất bại"):
            export_model(*args)
