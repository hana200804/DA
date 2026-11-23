from __future__ import annotations

import json
import shutil
from pathlib import Path, PurePosixPath
from zipfile import ZipFile

import numpy as np
import pytest
from PIL import Image, ImageDraw

from scripts.download_synthetic_templates import FIELDS, allowed_license
from signa_ai.dataset import assign_splits, read_samples, read_yolo, write_bundle
from signa_ai.labels import DEFAULT_PATH, load_label_map
from signa_ai.synthetic import (
    INTAKE_FIELDS,
    background_frames,
    generate,
    label_path,
    overlaps,
    package,
    rare_classes,
    read_csv,
    render,
    sha256,
    write_csv,
)


@pytest.fixture
def inputs(tmp_path):
    source = tmp_path / "input"
    (source / "images").mkdir(parents=True)
    (source / "labels").mkdir()
    labels = load_label_map(DEFAULT_PATH)
    class_id = labels.by_code("P.123a").id
    other_id = labels.by_code("other_prohibitory").id
    rows = []
    for index, split in enumerate(["train"] * 7 + ["val", "test", "field"]):
        path = PurePosixPath("images", f"{index}.png")
        Image.new("RGB", (320, 240), (25 + index, 32, 47)).save(source / path)
        (source / label_path(path)).write_text(
            f"{class_id} 0.20 0.32 0.20 0.22\n{other_id} 0.72 0.30 0.15 0.10\n",
            encoding="utf-8",
        )
        rows.append(
            {
                "path": str(path),
                "source": "public:fixture",
                "sequence": f"route-{index}",
                "split": split,
                "tags": "",
            }
        )
    intake = source / "intake.csv"
    write_csv(intake, INTAKE_FIELDS, rows)
    samples = read_samples(source, intake, labels)
    base = tmp_path / "v1.1"
    write_bundle(source, base, samples, assign_splits(samples), labels, DEFAULT_PATH)

    templates = tmp_path / "templates"
    templates.mkdir()
    template = Image.new("RGBA", (80, 80), "white")
    draw = ImageDraw.Draw(template)
    draw.rectangle((4, 4, 28, 28), fill="red")
    draw.rectangle((51, 51, 75, 75), fill="blue")
    png = templates / "P.123a.png"
    template.save(png)
    template_csv = tmp_path / "templates.csv"
    write_csv(
        template_csv,
        FIELDS,
        [
            {
                "code": "P.123a",
                "commons_file": "File:Fixture.svg",
                "url": "",
                "license": "CC0",
                "author": "Fixture",
                "sha256": sha256(png),
            }
        ],
    )
    return base, templates, template_csv


def files(path: Path) -> dict[str, bytes]:
    return {p.relative_to(path).as_posix(): p.read_bytes() for p in path.rglob("*") if p.is_file()}


def test_generate_is_reproducible_and_preserves_train_background(inputs, tmp_path):
    base, templates, catalog = inputs
    first, second = tmp_path / "first", tmp_path / "second"
    for output in (first, second):
        generate(base, output, templates, catalog, seed=21)
    assert files(first) == files(second)
    assert "P.123a" not in rare_classes(base, 7)
    assert rare_classes(base, 8)["P.123a"] == 7
    assert "other_prohibitory" not in rare_classes(base, 8)

    labels = load_label_map(base / "labels.yaml")
    train = {
        row["sequence"]: row["path"]
        for row in read_csv(base / "manifest.csv")
        if row["split"] == "train"
    }
    rows = read_csv(first / "intake.csv")
    assert len(rows) == 7 * 3 // 7
    for row in rows:
        assert (row["source"], row["split"], row["tags"]) == (
            "synthetic",
            "train",
            "synthetic_boxes=1",
        )
        original = base / train[row["sequence"]]
        origin_label = base / label_path(PurePosixPath(train[row["sequence"]]))
        pasted_label = first / label_path(PurePosixPath(row["path"]))
        original_boxes = read_yolo(origin_label, labels)
        *retained, added = read_yolo(pasted_label, labels)
        assert tuple(retained) == original_boxes
        assert pasted_label.read_bytes().startswith(origin_label.read_bytes())

        with Image.open(first / row["path"]) as generated, Image.open(original) as image:
            width, height = generated.size
            assert image.size == generated.size
            margin = 4
            assert not any(
                overlaps(
                    box,
                    added.left - margin / width,
                    added.top - margin / height,
                    added.right + margin / width,
                    added.bottom + margin / height,
                )
                for box in retained
            )
            assert 16 - 1e-9 <= (added.bottom - added.top) * height <= 200 + 1e-9
            assert added.bottom <= 0.5
            assert added.right <= 0.35 or added.left >= 0.65
            changed = np.any(np.asarray(image) != np.asarray(generated), axis=2)
            yy, xx = np.nonzero(changed)
            assert len(xx) > 0
            assert min(xx) >= round(added.left * width)
            assert max(xx) < round(added.right * width)
            assert min(yy) >= round(added.top * height)
            assert max(yy) < round(added.bottom * height)


@pytest.mark.parametrize("split", ["val", "test", "field"])
def test_background_override_rejects_evaluation_photos(inputs, tmp_path, split):
    base, _, _ = inputs
    row = next(row for row in read_csv(base / "manifest.csv") if row["split"] == split)
    backgrounds = tmp_path / "backgrounds"
    sequence = backgrounds / "unrelated-sequence"
    sequence.mkdir(parents=True)
    shutil.copyfile(base / row["path"], sequence / "copied.png")
    with pytest.raises(ValueError, match="trùng tập đánh giá"):
        background_frames(base, backgrounds)
    (sequence / "copied.png").unlink()
    sequence.rename(backgrounds / row["sequence"])
    Image.new("RGB", (320, 240), "gray").save(backgrounds / row["sequence"] / "other.png")
    with pytest.raises(ValueError, match="trùng tập đánh giá"):
        background_frames(base, backgrounds)


def test_background_override_keeps_the_external_sequence(inputs, tmp_path):
    base, templates, catalog = inputs
    backgrounds = tmp_path / "backgrounds"
    trip = backgrounds / "train-trip-A"
    trip.mkdir(parents=True)
    Image.new("RGB", (320, 240), "green").save(trip / "001.png")
    output = tmp_path / "synthetic"
    generate(base, output, templates, catalog, backgrounds=backgrounds, seed=17)
    labels = load_label_map(base / "labels.yaml")
    for row in read_csv(output / "intake.csv"):
        assert row["sequence"] == trip.name
        assert len(read_yolo(output / label_path(PurePosixPath(row["path"])), labels)) == 1


@pytest.mark.parametrize("padding", [(0, 40), (44, 0)])
def test_generate_avoids_black_bars(inputs, tmp_path, padding):
    base, templates, catalog = inputs
    pad_x, pad_y = padding
    backgrounds = tmp_path / "backgrounds"
    trip = backgrounds / "trip"
    trip.mkdir(parents=True)
    frame = Image.new("RGB", (320, 240), "black")
    frame.paste("gray", (pad_x, pad_y, 320 - pad_x, 240 - pad_y))
    frame.save(trip / "001.png")
    output = tmp_path / "synthetic"
    generate(base, output, templates, catalog, backgrounds=backgrounds, seed=18)
    labels = load_label_map(base / "labels.yaml")
    for row in read_csv(output / "intake.csv"):
        (box,) = read_yolo(output / label_path(PurePosixPath(row["path"])), labels)
        assert pad_x < box.left * frame.width < box.right * frame.width < frame.width - pad_x
        assert pad_y < box.top * frame.height < box.bottom * frame.height <= frame.height / 2


def test_render_preserves_horizontal_and_vertical_orientation(inputs):
    _, templates, _ = inputs
    rng = np.random.default_rng(2026)
    with Image.open(templates / "P.123a.png") as template:
        for height in (16, 61, 151, 200):
            rendered = render(template, height, rng)
            assert rendered.height == height
            red, green, blue, alpha = np.asarray(rendered, dtype=np.int16).transpose(2, 0, 1)
            red_yx = np.argwhere((red > blue + 80) & (red > green + 80) & (alpha > 140))
            blue_yx = np.argwhere((blue > red + 80) & (blue > green + 80) & (alpha > 140))
            assert red_yx.size and blue_yx.size
            assert np.all(red_yx.mean(axis=0) + height / 5 < blue_yx.mean(axis=0))


def test_package_keeps_evaluation_bytes_and_rejects_more_than_cap(inputs, tmp_path):
    base, templates, catalog = inputs
    generated = tmp_path / "synthetic"
    bundle = tmp_path / "v1.1-syn"
    generate(base, generated, templates, catalog, seed=23)
    package(base, generated, bundle, catalog)
    for split in ("val", "test", "field"):
        for kind in ("images", "labels"):
            assert files(bundle / kind / split) == files(base / kind / split)
        path = Path("coco") / f"{split}.json"
        assert (bundle / path).read_bytes() == (base / path).read_bytes()

    intake = read_csv(bundle / "intake.csv")
    assert len(intake) == 3 and all((bundle / row["path"]).is_file() for row in intake)
    stats = json.loads((bundle / "stats.json").read_bytes())
    assert stats["train"]["images"] == 10
    assert stats["train"]["boxes"] == 23
    assert stats["train"]["synthetic_by_class"] == {"P.123a": 3}
    assert (bundle / "preview").is_dir()
    archive = tmp_path / "signa-v1.1-syn.zip"
    checksum = (tmp_path / "signa-v1.1-syn.sha256").read_text(encoding="utf-8")
    assert checksum.splitlines() == [
        f"{sha256(archive)}  {archive.name}",
        f"{sha256(bundle / 'manifest.csv')}  {bundle.name}/manifest.csv",
    ]
    with ZipFile(archive) as zipped:
        assert zipped.testzip() is None
        assert zipped.read(f"{bundle.name}/coco/val.json") == (base / "coco/val.json").read_bytes()

    rows = read_csv(generated / "intake.csv")
    extra = rows[0] | {"path": "images/extra.png"}
    Image.new("RGB", (320, 240), "black").save(generated / extra["path"])
    shutil.copyfile(
        generated / label_path(PurePosixPath(rows[0]["path"])),
        generated / label_path(PurePosixPath(extra["path"])),
    )
    write_csv(generated / "intake.csv", INTAKE_FIELDS, rows + [extra])
    with pytest.raises(ValueError, match="vượt 30%"):
        package(base, generated, tmp_path / "over-cap", catalog)


@pytest.mark.parametrize("license_name", ["CC BY-NC 4.0", "CC BY-ND 4.0", "Unknown"])
def test_commons_license_allowlist_rejects_restricted_svg(license_name):
    with pytest.raises(ValueError, match="giấy phép không được chấp nhận"):
        allowed_license({}, {"LicenseShortName": {"value": license_name}})
