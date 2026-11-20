import json
import sys
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
