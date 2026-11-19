import csv
import json
from pathlib import Path

import pytest
from PIL import Image

from signa_ai.dataset import assign_splits, coco_to_yolo, read_samples, read_yolo, write_bundle
from signa_ai.labels import DEFAULT_PATH, load_label_map


def make_intake(root, rows):
    intake = root / "intake.csv"
    with intake.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(["path", "source", "sequence", "split", "tags"])
        for sequence, name, split, source, label, color in rows:
            image = root / "images" / f"{name}.png"
            target = root / "labels" / f"{name}.txt"
            image.parent.mkdir(exist_ok=True)
            target.parent.mkdir(exist_ok=True)
            Image.new("RGB", (1920, 1080), color).save(image)
            target.write_text(label, encoding="utf-8")
            writer.writerow([f"images/{name}.png", source, sequence, split, ""])
    return intake


def build(root, intake, destination, label_map):
    samples = read_samples(root, intake, label_map)
    write_bundle(root, destination, samples, assign_splits(samples), label_map, DEFAULT_PATH)


def test_round_trip_and_background_frame(tmp_path):
    label_map = load_label_map()
    label = "0 0.199987 0.735556 0.112345 0.092345\n"
    intake = make_intake(
        tmp_path,
        [
            ("route-1", "positive", "test", "photo", label, "blue"),
            ("route-1", "negative", "", "photo", "", "red"),
        ],
    )
    output = tmp_path / "bundle"
    build(tmp_path, intake, output, label_map)
    coco = output / "coco" / "test.json"
    objects = json.loads(coco.read_text())
    assert len(objects["images"]) == 2
    assert len(objects["annotations"]) == 1
    assert objects["annotations"][0]["category_id"] == 0
    assert objects["images"][1]["file_name"] == "images/test/positive.png"

    restored = tmp_path / "restored"
    coco_to_yolo(coco, restored, label_map)
    [before] = read_yolo(tmp_path / "labels" / "positive.txt", label_map)
    [after] = read_yolo(restored / "test" / "positive.txt", label_map)
    assert after.coco(1920, 1080) == pytest.approx(before.coco(1920, 1080), abs=0.5)
    assert (restored / "test" / "negative.txt").read_text() == ""


def test_sequences_and_manifest_hashes(tmp_path):
    label_map = load_label_map()
    intake = make_intake(
        tmp_path,
        [
            ("route-a", "a", "", "photo", "", "black"),
            ("route-a", "b", "", "public:sample", "", "blue"),
            ("route-c", "c", "", "photo", "", "cyan"),
            ("route-d", "d", "", "photo", "", "red"),
            ("route-f", "field", "field", "field:route-f", "", "yellow"),
        ],
    )
    results = []
    for name in ("first", "second"):
        if name == "second":
            Image.new("RGB", (1920, 1080), "green").save(tmp_path / "images" / "a.png")
        output = tmp_path / name
        build(tmp_path, intake, output, label_map)
        with (output / "manifest.csv").open() as stream:
            results.append({Path(row["path"]).stem: row for row in csv.DictReader(stream)})

    first, second = results
    assert first["a"]["split"] == first["b"]["split"]
    assert first["field"]["split"] == "field"
    assert {row["split"] for row in first.values()} == {"train", "val", "test", "field"}
    assert first["a"]["sha256"] != second["a"]["sha256"]
    first["a"]["sha256"] = second["a"]["sha256"]
    assert first == second


def test_conflicting_presplit_sequence_is_rejected(tmp_path):
    intake = make_intake(
        tmp_path,
        [
            ("route-a", "a", "train", "photo", "", "red"),
            ("route-a", "b", "test", "photo", "", "blue"),
        ],
    )
    samples = read_samples(tmp_path, intake, load_label_map())
    with pytest.raises(ValueError, match="xuất hiện trong nhiều tập"):
        assign_splits(samples)


def test_conflicting_image_hash_is_rejected(tmp_path):
    intake = make_intake(
        tmp_path,
        [
            ("route-a", "a", "train", "photo", "", "red"),
            ("route-b", "b", "test", "photo", "", "red"),
        ],
    )
    with pytest.raises(ValueError, match="trùng SHA-256"):
        read_samples(tmp_path, intake, load_label_map())


def test_synthetic_source_stays_in_train_and_is_limited(tmp_path):
    label_map = load_label_map()
    label = "0 0.5 0.5 0.2 0.2\n"
    intake = make_intake(
        tmp_path,
        [
            ("route-a", "a", "train", "photo", label, "red"),
            ("synthetic-b", "b", "", "synthetic", label, "blue"),
        ],
    )
    assert assign_splits(read_samples(tmp_path, intake, label_map))["synthetic-b"] == "train"
    with pytest.raises(ValueError, match="vượt 30%"):
        build(tmp_path, intake, tmp_path / "bundle", label_map)
    assert not (tmp_path / "bundle").exists()
