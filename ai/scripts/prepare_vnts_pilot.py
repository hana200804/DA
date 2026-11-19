"""Trích ảnh VNTS đã lọc trùng và tạo đầu vào chạy thử với danh mục Signa v0."""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import re
import shutil
import tempfile
from collections import Counter
from pathlib import Path

from PIL import Image
from pyarrow.parquet import ParquetFile

from signa_ai.dataset import Box, write_json, write_yolo
from signa_ai.labels import DEFAULT_PATH, load_label_map

LABELS_V0 = DEFAULT_PATH.with_name("labels-v0.yaml")

SOURCE = "public:vnts-dedup-1k"
REVISION = "04a6b91bef3f92c729f0ea7c383a1e3dbfece82d"
FILES = {
    "vnts-dedup-objects.jsonl": "49a586e411f053e2f2926ba0fb8f15f7293ea061e40025d6ba9ef671cb489eea",
    "vnts-dedup-train.parquet": "b3f374c3438e9ace5b743d6fdd55b1b19eb184066715427205af02be25befc64",
    "vnts-dedup-test.parquet": "48e46d7939ec95bf8f510e6909aeab09a56955a7a01ee721d7b7d2f72d40fb6f",
}
PILOT_CODES = {
    "P.102": "P.102",
    "P.123a": "P.123a",
    "P.12750": "P.127_50",
    "R.303": "R.303",
    "S.505aXe máy": "S",
    "S.505aXe tải": "S",
}
OTHER_PROHIBITORY = frozenset(
    {
        "P.103a",
        "P.104",
        "P.106aXe tải",
        "P.107",
        "P.111",
        "P.117",
        "P.123b",
        "P.124a",
        "P.124c",
        "P.124d",
        "P.12740",
        "P.12760",
        "P.12780",
        "P.130",
        "P.131a",
        "P.137",
        "P.139",
    }
)


def pilot_code(source_code: str) -> str:
    if source_code in OTHER_PROHIBITORY:
        return "other_prohibitory"
    return PILOT_CODES.get(source_code, "")


def export_review_coco(review: Path, metadata: dict) -> int:
    images = []
    annotations = []
    categories = {}
    invalid = 0
    for key, item in sorted(metadata.items()):
        width, height = item["width"], item["height"]
        image_id = len(images) + 1
        images.append(
            {
                "id": image_id,
                "file_name": f"{item['split']}/{key}.jpg",
                "width": width,
                "height": height,
            }
        )
        for obj in item["objects"]:
            code = obj["class_original"]
            category_id = categories.setdefault(code, len(categories) + 1)
            x, y, w, h = obj["bbox_xywh"]
            try:
                box = Box.from_edges(
                    category_id, (x / width, y / height, (x + w) / width, (y + h) / height)
                )
            except ValueError:
                invalid += 1
                continue
            bbox = box.coco(width, height)
            annotations.append(
                {
                    "id": len(annotations) + 1,
                    "image_id": image_id,
                    "category_id": category_id,
                    "bbox": bbox,
                    "area": bbox[2] * bbox[3],
                    "iscrowd": 0,
                }
            )
    write_json(
        review / "annotations.json",
        {
            "info": {"source": SOURCE, "revision": REVISION},
            "images": images,
            "annotations": annotations,
            "categories": [
                {"id": category_id, "name": code} for code, category_id in categories.items()
            ],
        },
    )
    return invalid


def prepare(raw: Path, output: Path) -> None:
    if output.exists():
        raise ValueError(f"đích đã tồn tại: {output}")
    for name, expected in FILES.items():
        with (raw / name).open("rb") as handle:
            if hashlib.file_digest(handle, "sha256").hexdigest() != expected:
                raise ValueError(f"SHA-256 khác bản VNTS đã chọn: {name}")

    metadata_path = raw / "vnts-dedup-objects.jsonl"
    metadata = {}
    for line in metadata_path.read_text(encoding="utf-8").splitlines():
        item = json.loads(line)
        key = item["image_id"]
        if not re.fullmatch(r"vts_[0-9]+", key) or key in metadata:
            raise ValueError(f"image_id sai hoặc trùng: {key}")
        metadata[key] = item

    label_map = load_label_map(LABELS_V0)
    if label_map.version != 0:
        raise ValueError("bộ chạy thử này dùng danh mục v0")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as temporary:
        staging = Path(temporary) / "vnts"
        review = staging / "review"
        review.mkdir(parents=True)
        shutil.copyfile(metadata_path, review / "objects.jsonl")
        intake = staging / "intake"
        intake.mkdir()
        seen: dict[str, str] = {}
        used_hashes = set()
        selected = Counter()
        excluded = Counter()
        raw_box_counts = {"train": Counter(), "test": Counter()}
        with (intake / "intake.csv").open("w", encoding="utf-8", newline="") as handle:
            writer = csv.writer(handle, lineterminator="\n")
            writer.writerow(["path", "source", "sequence", "split", "tags"])
            for split in ("test", "train"):
                archive = ParquetFile(raw / f"vnts-dedup-{split}.parquet")
                for batch in archive.iter_batches(columns=["image_id", "image"], batch_size=24):
                    for row in batch.to_pylist():
                        image_id = row["image_id"]
                        if image_id in seen:
                            if seen[image_id] != split:
                                raise ValueError(f"ảnh xuất hiện trong cả hai tập: {image_id}")
                            continue
                        seen[image_id] = split
                        item = metadata[image_id]
                        if item["split"] != split or item["orig_detection_split"] != split:
                            raise ValueError(f"sai tập nguồn: {image_id}")
                        original = item["original_image"]
                        if not re.fullmatch(r"[0-9]+\.jpg", original):
                            raise ValueError(f"tên ảnh nguồn không hợp lệ: {original}")
                        binary = row["image"]["bytes"]
                        with Image.open(io.BytesIO(binary)) as image:
                            width, height = image.size
                            if image.format != "JPEG" or (width, height) != (
                                item["width"],
                                item["height"],
                            ):
                                raise ValueError(f"định dạng hoặc kích thước sai: {image_id}")
                        review_image = review / "images" / split / f"{image_id}.jpg"
                        review_image.parent.mkdir(parents=True, exist_ok=True)
                        review_image.write_bytes(binary)

                        objects = item["objects"]
                        raw_box_counts[split].update(
                            (box["class_original"], box["class_vi"]) for box in objects
                        )
                        codes = [pilot_code(box["class_original"]) for box in objects]
                        if not codes or not all(codes):
                            excluded["unmapped_images"] += 1
                            continue
                        try:
                            boxes = []
                            for sign, code in zip(objects, codes, strict=True):
                                x, y, w, h = sign["bbox_xywh"]
                                box = Box.from_edges(
                                    label_map.by_code(code).id,
                                    (x / width, y / height, (x + w) / width, (y + h) / height),
                                )
                                if h >= 10:
                                    boxes.append(box)
                        except ValueError:
                            excluded["invalid_bbox_images"] += 1
                            continue
                        if not boxes:
                            excluded["only_small_boxes_images"] += 1
                            continue
                        digest = hashlib.sha256(binary).hexdigest()
                        if digest in used_hashes:
                            excluded["duplicate_images"] += 1
                            continue
                        used_hashes.add(digest)
                        name = f"{image_id}.jpg"
                        path = intake / "images" / name
                        path.parent.mkdir(parents=True, exist_ok=True)
                        path.write_bytes(binary)
                        write_yolo(intake / "labels" / f"{image_id}.txt", boxes)
                        # VNTS không công bố mã tuyến. Nhóm theo dải tên ảnh để hạn chế
                        # tách khung lân cận trong phần train nguồn khi tạo validation.
                        block = int(Path(original).stem) // 100
                        sequence = f"vnts-{split}-block-{block:02}"
                        writer.writerow(
                            [
                                f"images/{name}",
                                SOURCE,
                                sequence,
                                "test" if split == "test" else "",
                                "",
                            ]
                        )
                        selected[split] += 1
        if set(seen) != set(metadata):
            raise ValueError("tập ảnh trong Parquet và danh mục box không khớp")
        invalid_review_boxes = export_review_coco(review, metadata)
        with (review / "source-counts.csv").open("w", encoding="utf-8", newline="") as handle:
            writer = csv.writer(handle, lineterminator="\n")
            writer.writerow(
                [
                    "source_code",
                    "source_name_vi",
                    "source_train_boxes",
                    "source_test_boxes",
                    "signa_v0",
                ]
            )
            train, test = raw_box_counts["train"], raw_box_counts["test"]
            for code, name in sorted(train.keys() | test.keys()):
                writer.writerow([code, name, train[code, name], test[code, name], pilot_code(code)])
        write_json(
            staging / "source-audit.json",
            {
                "source": SOURCE,
                "revision": REVISION,
                "images": len(seen),
                "boxes": sum(sum(counts.values()) for counts in raw_box_counts.values()),
                "invalid_review_boxes": invalid_review_boxes,
                "selected_source_split": dict(selected),
                "excluded": dict(excluded),
                "sha256": FILES,
            },
        )
        staging.rename(output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        prepare(args.raw, args.output)
    except (OSError, ValueError) as error:
        parser.exit(2, f"{error}\n")
