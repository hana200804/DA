"""Kiểm tra và đóng gói dữ liệu YOLO/COCO cho Signa (AI-01, AI-02)."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import shutil
import tempfile
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

import yaml
from PIL import Image

from signa_ai.labels import DEFAULT_PATH, LabelMap, load_label_map

RATIOS = {"train": 0.70, "val": 0.15, "test": 0.15}
SPLITS = (*RATIOS, "field")
MANIFEST_FIELDS = ("path", "sha256", "split", "source", "sequence", "tags", "label_map_version")
SYNTHETIC_BOXES_TAG = "synthetic_boxes="
EDGE_TOLERANCE = 1e-6


@dataclass(frozen=True)
class Box:
    class_id: int
    left: float
    top: float
    right: float
    bottom: float

    @classmethod
    def from_edges(cls, class_id: int, edges: tuple[float, float, float, float]) -> Box:
        left, top, right, bottom = edges
        if not (
            all(math.isfinite(value) for value in edges)
            and -EDGE_TOLERANCE <= left < right <= 1 + EDGE_TOLERANCE
            and -EDGE_TOLERANCE <= top < bottom <= 1 + EDGE_TOLERANCE
        ):
            raise ValueError("box nằm ngoài ảnh hoặc có kích thước không hợp lệ")
        left, top = max(0.0, left), max(0.0, top)
        right, bottom = min(1.0, right), min(1.0, bottom)
        if right <= left or bottom <= top:
            raise ValueError("box nằm ngoài ảnh")
        return cls(class_id, left, top, right, bottom)

    def yolo(self) -> str:
        width = self.right - self.left
        height = self.bottom - self.top
        values = (self.left + width / 2, self.top + height / 2, width, height)
        return f"{self.class_id} " + " ".join(format(value, ".15g") for value in values)

    def coco(self, width: int, height: int) -> list[float]:
        return [
            self.left * width,
            self.top * height,
            (self.right - self.left) * width,
            (self.bottom - self.top) * height,
        ]


@dataclass(frozen=True)
class Sample:
    path: PurePosixPath
    sha256: str
    width: int
    height: int
    source: str
    sequence: str
    tags: str
    split: str
    boxes: tuple[Box, ...]
    pasted_boxes: int | None = None


def pasted_box_count(tags: str, source: str, box_count: int) -> int | None:
    counts = [
        tag.removeprefix(SYNTHETIC_BOXES_TAG)
        for tag in tags.split(";")
        if tag.startswith(SYNTHETIC_BOXES_TAG)
    ]
    if not counts:
        return None
    if len(counts) != 1 or source != "synthetic" or not 1 <= int(counts[0]) <= box_count:
        raise ValueError("synthetic_boxes phải là số box được dán ở cuối nhãn ảnh tổng hợp")
    return int(counts[0])


def relative_path(value: str) -> PurePosixPath:
    path = PurePosixPath(value)
    if not path.parts or path.is_absolute() or ".." in path.parts or "\\" in value:
        raise ValueError(f"đường dẫn tương đối không hợp lệ: {value!r}")
    return path


def read_yolo(path: Path, label_map: LabelMap) -> tuple[Box, ...]:
    boxes = []
    for number, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        if not line.strip():
            continue
        try:
            class_text, *coords = line.split()
            class_id = int(class_text)
            label_map.by_id(class_id)
            cx, cy, width, height = map(float, coords)
            edges = (cx - width / 2, cy - height / 2, cx + width / 2, cy + height / 2)
            boxes.append(Box.from_edges(class_id, edges))
        except (KeyError, ValueError) as error:
            raise ValueError(f"{path}:{number}: {error}") from error
    return tuple(boxes)


def read_samples(root: Path, intake: Path, label_map: LabelMap) -> list[Sample]:
    samples = []
    paths: set[PurePosixPath] = set()
    labels: set[PurePosixPath] = set()
    hashes: dict[str, PurePosixPath] = {}
    with intake.open(encoding="utf-8-sig", newline="") as handle:
        rows = csv.DictReader(handle)
        if not {"path", "source", "sequence"} <= set(rows.fieldnames or []):
            raise ValueError("CSV phải có path, source và sequence")
        for row in rows:
            try:
                if None in row or any(value is None for value in row.values()):
                    raise ValueError("số giá trị khác số cột CSV")
                path = relative_path(row["path"].strip())
                if len(path.parts) < 2 or path.parts[0] != "images":
                    raise ValueError("path phải nằm trong images/")
                label = PurePosixPath("labels", *path.parts[1:]).with_suffix(".txt")
                if path in paths or label in labels:
                    raise ValueError(f"ảnh hoặc đường dẫn nhãn trùng: {path}")
                source, sequence = row["source"].strip(), row["sequence"].strip()
                if not sequence or not (
                    source in {"photo", "synthetic"}
                    or any(
                        source.startswith(prefix) and source[len(prefix) :]
                        for prefix in ("public:", "field:")
                    )
                ):
                    raise ValueError("source hoặc sequence không hợp lệ")
                split = row.get("split", "").strip()
                if split and split not in SPLITS:
                    raise ValueError(f"split không hợp lệ: {split}")
                if source == "synthetic" and split not in ("", "train"):
                    raise ValueError("ảnh tổng hợp chỉ dùng cho train")
                with (root / path).open("rb") as stream:
                    digest = hashlib.file_digest(stream, "sha256").hexdigest()
                if digest in hashes:
                    raise ValueError(f"ảnh trùng SHA-256 với {hashes[digest]}: {path}")
                with Image.open(root / path) as image:
                    image.load()
                    width, height = image.size
                boxes = read_yolo(root / label, label_map)
                tags = row.get("tags", "").strip()
                samples.append(
                    Sample(
                        path,
                        digest,
                        width,
                        height,
                        source,
                        sequence,
                        tags,
                        split,
                        boxes,
                        pasted_box_count(tags, source, len(boxes)),
                    )
                )
                paths.add(path)
                labels.add(label)
                hashes[digest] = path
            except (OSError, ValueError) as error:
                raise ValueError(f"{intake}:{rows.line_num}: {error}") from error
    if not samples:
        raise ValueError(f"{intake}: cần ít nhất một ảnh")
    return sorted(samples, key=lambda sample: sample.path)


def assign_splits(samples: list[Sample]) -> dict[str, str]:
    by_sequence: dict[str, list[Sample]] = defaultdict(list)
    for sample in samples:
        by_sequence[sample.sequence].append(sample)

    assigned = {}
    sizes = Counter()
    features = {}
    totals = Counter()
    for sequence, group in sorted(by_sequence.items()):
        fixed = {sample.split for sample in group if sample.split}
        if any(sample.source == "synthetic" for sample in group):
            fixed.add("train")
        if len(fixed) > 1:
            raise ValueError(f"chuỗi {sequence!r} xuất hiện trong nhiều tập: {sorted(fixed)}")
        if fixed:
            assigned[sequence] = fixed.pop()
        if assigned.get(sequence) == "field":
            continue
        real = [sample for sample in group if sample.source != "synthetic"]
        counts = Counter(box.class_id for sample in real for box in sample.boxes)
        counts["images"] = len(real)
        features[sequence] = counts
        totals.update(counts)
        if sequence in assigned and real:
            sizes[assigned[sequence]] += 1

    per_split = {split: Counter() for split in RATIOS}
    for sequence, counts in features.items():
        if sequence in assigned:
            per_split[assigned[sequence]].update(counts)

    targets = {split: {} for split in RATIOS}
    for key, total in totals.items():
        if not total:
            continue
        fixed = {split: per_split[split][key] for split in RATIOS}
        remaining = total - sum(fixed.values())
        deficits = {split: max(0, total * ratio - fixed[split]) for split, ratio in RATIOS.items()}
        demand = sum(deficits.values())
        for split in RATIOS:
            targets[split][key] = (
                fixed[split] + (remaining * deficits[split] / demand if demand else 0)
            ) / total

    pending = sorted(
        (sequence for sequence in features if sequence not in assigned),
        key=lambda sequence: (
            -sum(value / totals[key] for key, value in features[sequence].items() if value),
            sequence,
        ),
    )
    for index, sequence in enumerate(pending):
        counts = features[sequence]
        empty = [split for split in RATIOS if not sizes[split]]
        choices = empty if len(empty) >= len(pending) - index else RATIOS

        def cost(split: str, group_counts: Counter) -> float:
            # Chênh lệch bình phương so với đích, chuẩn hóa riêng cho từng lớp.
            return sum(
                (value / totals[key])
                * (
                    2 * per_split[split][key] / totals[key]
                    + value / totals[key]
                    - 2 * targets[split][key]
                )
                for key, value in group_counts.items()
                if value
            )

        target = min((cost(split, counts), order, split) for order, split in enumerate(choices))[2]
        assigned[sequence] = target
        per_split[target].update(counts)
        sizes[target] += 1
    return assigned


def write_yolo(path: Path, boxes: tuple[Box, ...] | list[Box]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(f"{box.yolo()}\n" for box in boxes), encoding="utf-8")


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_bundle(
    root: Path,
    output: Path,
    samples: list[Sample],
    assigned: dict[str, str],
    label_map: LabelMap,
    labels_path: Path,
) -> None:
    output = output.resolve()
    if output.exists():
        raise ValueError(f"đích đã tồn tại: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as temporary:
        staging = Path(temporary) / "bundle"
        staging.mkdir()
        coco_dir = staging / "coco"
        coco_dir.mkdir()
        shutil.copyfile(labels_path, staging / "labels.yaml")
        categories = [
            {"id": class_id, "name": code, "supercategory": label_map.by_id(class_id).category}
            for class_id, code in label_map.names.items()
        ]
        stats = {}
        with (staging / "manifest.csv").open("w", encoding="utf-8", newline="") as handle:
            manifest = csv.DictWriter(handle, fieldnames=MANIFEST_FIELDS, lineterminator="\n")
            manifest.writeheader()
            for split in SPLITS:
                group = [sample for sample in samples if assigned[sample.sequence] == split]
                stats[split] = write_split(
                    root, staging, split, group, categories, manifest, label_map
                )
        write_json(staging / "stats.json", stats)
        real_train = Counter(
            label_map.by_id(box.class_id).code
            for sample in samples
            if sample.source != "synthetic" and assigned[sample.sequence] == "train"
            for box in sample.boxes
        )
        for code, count in stats["train"]["synthetic_by_class"].items():
            if 10 * count > 3 * (real_train[code] + count):
                raise ValueError(f"{code}: box tổng hợp vượt 30% số box trong train")
        config = {split: f"images/{split}" for split in RATIOS if stats[split]["images"]}
        config = {"path": str(output), **config, "names": label_map.names}
        (staging / "data.yaml").write_text(
            yaml.safe_dump(config, sort_keys=False, allow_unicode=True),
            encoding="utf-8",
        )
        staging.rename(output)


def write_split(
    root: Path,
    staging: Path,
    split: str,
    samples: list[Sample],
    categories: list[dict],
    manifest: csv.DictWriter,
    label_map: LabelMap,
) -> dict:
    images = []
    annotations = []
    classes = Counter(dict.fromkeys(label_map.names.values(), 0))
    groups = Counter(dict.fromkeys(label_map.categories, 0))
    sources = Counter()
    tags = Counter()
    sizes = Counter(dict.fromkeys(("small", "medium", "large"), 0))
    synthetic_classes = Counter()
    for image_id, sample in enumerate(samples, 1):
        relative = PurePosixPath(split, *sample.path.parts[1:])
        image_path = PurePosixPath("images", relative)
        target = staging / image_path
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(root / sample.path, target)
        with target.open("rb") as stream:
            if hashlib.file_digest(stream, "sha256").hexdigest() != sample.sha256:
                raise ValueError(f"ảnh đã thay đổi trong lúc đóng gói: {sample.path}")
        label_path = PurePosixPath("labels", relative).with_suffix(".txt")
        write_yolo(staging / label_path, sample.boxes)
        manifest.writerow(
            {
                "path": str(image_path),
                "sha256": sample.sha256,
                "split": split,
                "source": sample.source,
                "sequence": sample.sequence,
                "tags": sample.tags,
                "label_map_version": label_map.version,
            }
        )
        images.append(
            {
                "id": image_id,
                "file_name": str(image_path),
                "width": sample.width,
                "height": sample.height,
            }
        )
        sources[sample.source] += 1
        tags.update(tag for tag in set(sample.tags.split(";")) if tag)
        pasted_start = 0 if sample.pasted_boxes is None else len(sample.boxes) - sample.pasted_boxes
        for index, box in enumerate(sample.boxes):
            bbox = box.coco(sample.width, sample.height)
            area = bbox[2] * bbox[3]
            sign = label_map.by_id(box.class_id)
            classes[sign.code] += 1
            groups[sign.category] += 1
            sizes["small" if area < 32**2 else "large" if area > 96**2 else "medium"] += 1
            if sample.source == "synthetic" and index >= pasted_start:
                synthetic_classes[sign.code] += 1
            annotations.append(
                {
                    "id": len(annotations) + 1,
                    "image_id": image_id,
                    "category_id": box.class_id,
                    "bbox": bbox,
                    "area": area,
                    "iscrowd": 0,
                }
            )
    write_json(
        staging / "coco" / f"{split}.json",
        {
            "info": {"label_map_version": label_map.version},
            "images": images,
            "annotations": annotations,
            "categories": categories,
        },
    )
    return {
        "images": len(images),
        "boxes": len(annotations),
        "sequences": len({sample.sequence for sample in samples}),
        "by_class": dict(classes),
        "by_group": dict(groups),
        "by_source": dict(sorted(sources.items())),
        "by_tag": dict(sorted(tags.items())),
        "by_size": dict(sizes),
        "synthetic_by_class": dict(sorted(synthetic_classes.items())),
    }


def coco_to_yolo(coco_path: Path, output: Path, label_map: LabelMap) -> None:
    coco = json.loads(coco_path.read_text(encoding="utf-8"))
    version = coco.get("info", {}).get("label_map_version", label_map.version)
    if version != label_map.version:
        raise ValueError(f"COCO dùng danh mục v{version}, cần v{label_map.version}")
    category_ids = set()
    for category in coco["categories"]:
        class_id = category["id"]
        if label_map.by_id(class_id).code != category["name"] or class_id in category_ids:
            raise ValueError(f"id hoặc mã lớp COCO không khớp: {category}")
        category_ids.add(class_id)
    images = {}
    paths = set()
    for image in coco["images"]:
        path = relative_path(image["file_name"])
        if path.parts[0] == "images" and len(path.parts) > 1:
            path = PurePosixPath(*path.parts[1:])
        path = path.with_suffix(".txt")
        if path in paths or image["id"] in images or min(image["width"], image["height"]) <= 0:
            raise ValueError(f"ảnh COCO trùng hoặc kích thước không hợp lệ: {image}")
        images[image["id"]] = (path, image["width"], image["height"], [])
        paths.add(path)
    for annotation in coco["annotations"]:
        path, width, height, boxes = images[annotation["image_id"]]
        class_id = annotation["category_id"]
        if class_id not in category_ids or annotation.get("iscrowd", 0):
            raise ValueError(f"annotation không hỗ trợ trong YOLO: {annotation['id']}")
        x, y, box_width, box_height = annotation["bbox"]
        edges = (x / width, y / height, (x + box_width) / width, (y + box_height) / height)
        try:
            boxes.append(Box.from_edges(class_id, edges))
        except ValueError as error:
            raise ValueError(f"{path}, annotation {annotation['id']}: {error}") from error
    if output.exists():
        raise ValueError(f"đích đã tồn tại: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as temporary:
        staging = Path(temporary) / "labels"
        staging.mkdir()
        for path, _, _, boxes in images.values():
            write_yolo(staging / path, boxes)
        staging.rename(output)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    build = commands.add_parser("build", help="Kiểm tra, chia tập và xuất dữ liệu")
    build.add_argument("--root", required=True, type=Path)
    build.add_argument("--intake", required=True, type=Path)
    reverse = commands.add_parser("coco-to-yolo", help="Đổi COCO Signa về nhãn YOLO")
    reverse.add_argument("--coco", required=True, type=Path)
    for command in (build, reverse):
        command.add_argument("--labels", default=DEFAULT_PATH, type=Path)
        command.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        label_map = load_label_map(args.labels)
        if args.command == "coco-to-yolo":
            coco_to_yolo(args.coco, args.output, label_map)
        else:
            if list(label_map.names) != list(range(len(label_map))):
                raise ValueError("để xuất YOLO, id của danh mục phải liên tiếp từ 0")
            samples = read_samples(args.root, args.intake, label_map)
            assigned = assign_splits(samples)
            write_bundle(args.root, args.output, samples, assigned, label_map, args.labels)
    except (KeyError, OSError, TypeError, ValueError, yaml.YAMLError) as error:
        parser.exit(2, f"{error}\n")


if __name__ == "__main__":
    main()
