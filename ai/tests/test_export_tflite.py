import json

import pytest
import yaml

from scripts.export_tflite import ARTIFACTS, calibration_config, complete, sha256, split_paths
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
