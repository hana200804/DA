"""Dựng bộ dữ liệu Signa v1 từ VN82: đổi sang mã QCVN và chia lại theo nhóm ảnh gần trùng."""

from __future__ import annotations

import argparse
import csv
import json
import shutil
import tempfile
from collections import Counter
from dataclasses import replace
from pathlib import Path

import numpy as np
import yaml
from PIL import Image
from prepare_vn82 import REVISION, SHA256, SPLITS, decode_boxes

from signa_ai.dataset import assign_splits, read_samples, write_bundle, write_json, write_yolo
from signa_ai.labels import DEFAULT_PATH, load_label_map

DATA_DIR = Path(__file__).resolve().parents[1] / "data"
# Khoảng cách Hamming của dHash 256 bit. Hai ảnh ngẫu nhiên trong VN82 có trung vị 126
# và phân vị 0,1% khoảng 78. Từ 60 trở xuống, các cặp lấy mẫu đều là cùng một cảnh.
NEAR_DUPLICATE = 60
# Từ 61 đến 80, dHash lẫn cặp cùng cảnh với cặp khác cảnh. Cần thêm tương quan của ảnh
# xám 32×24 từ 0,88 trở lên để coi là cùng cảnh.
CANDIDATE = 80
CORRELATION = 0.88
# Hai ảnh liền số thường là hai khung kế tiếp của cùng một video.
CONSECUTIVE = 70
DARK = 12
BLOCK = 1024


def describe(path: Path) -> tuple[np.ndarray, np.ndarray]:
    """dHash 16×16 và ảnh xám 32×24 chuẩn hoá, sau khi cắt viền đen của ảnh."""
    with Image.open(path) as image:
        gray = np.asarray(image.convert("L"), dtype=np.float32)
    rows = np.flatnonzero(gray.mean(axis=1) > DARK)
    cols = np.flatnonzero(gray.mean(axis=0) > DARK)
    if len(rows) > 8 and len(cols) > 8:
        gray = gray[rows[0] : rows[-1] + 1, cols[0] : cols[-1] + 1]
    cropped = Image.fromarray(gray.astype(np.uint8))
    pixels = np.asarray(cropped.resize((17, 16), Image.BILINEAR), dtype=np.int16)
    thumb = np.asarray(cropped.resize((32, 24), Image.BILINEAR), dtype=np.float32).ravel()
    thumb = (thumb - thumb.mean()) / (thumb.std() + 1e-6) / np.sqrt(thumb.size)
    return (pixels[:, 1:] > pixels[:, :-1]).ravel(), thumb


def same_scene_pairs(hashes: np.ndarray, thumbs: np.ndarray) -> list[tuple[int, int]]:
    """Mọi cặp ảnh cùng cảnh theo dHash, hoặc theo dHash gần kèm tương quan cao."""
    bits = hashes.astype(np.float32)
    ones = bits.sum(axis=1)
    pairs = []
    for start in range(0, len(bits), BLOCK):
        block = slice(start, start + BLOCK)
        distance = ones[block, None] + ones[None, :] - 2 * bits[block] @ bits.T
        correlation = thumbs[block] @ thumbs.T
        same = (distance <= NEAR_DUPLICATE) | (
            (distance <= CANDIDATE) & (correlation >= CORRELATION)
        )
        rows, columns = np.nonzero(same)
        rows = rows + start
        keep = rows < columns
        pairs.extend(zip(rows[keep].tolist(), columns[keep].tolist(), strict=True))
    return pairs


def group_images(
    hashes: np.ndarray, thumbs: np.ndarray, order: list[int]
) -> tuple[list[int], list[tuple[int, int]]]:
    """Gom ảnh cùng cảnh ở bất kỳ đâu và ảnh liền số khá giống nhau vào một nhóm."""
    parent = list(range(len(hashes)))

    def find(item: int) -> int:
        while parent[item] != item:
            parent[item] = parent[parent[item]]
            item = parent[item]
        return item

    pairs = same_scene_pairs(hashes, thumbs)
    for first, second in zip(order, order[1:], strict=False):
        if np.count_nonzero(hashes[first] != hashes[second]) <= CONSECUTIVE:
            pairs.append((first, second))
    for first, second in pairs:
        parent[find(first)] = find(second)
    return [find(item) for item in range(len(hashes))], pairs


def read_mapping(path: Path, codes: set[str]) -> dict[int, str]:
    with path.open(encoding="utf-8", newline="") as stream:
        mapping = {int(row["source_id"]): row["signa_code"] for row in csv.DictReader(stream)}
    if sorted(mapping) != list(range(82)):
        raise ValueError("bảng ánh xạ phải có đủ ID VN82 từ 0 đến 81")
    unknown = {code for code in mapping.values() if code and code not in codes}
    if unknown:
        raise ValueError(f"mã không có trong labels.yaml: {sorted(unknown)}")
    return mapping


def read_fixes(path: Path, codes: set[str]) -> dict[tuple[str, int], tuple[int, str]]:
    """Mã đã rà tay cho từng box, theo `images/<tập nguồn>/<tên ảnh>` và số thứ tự box."""
    fixes = {}
    with path.open(encoding="utf-8", newline="") as stream:
        for row in csv.DictReader(stream):
            key = (row["image"], int(row["box_index"]))
            if key in fixes or row["signa_code"] not in codes:
                raise ValueError(f"dòng sửa nhãn trùng hoặc sai mã: {key}")
            fixes[key] = (int(row["source_id"]), row["signa_code"])
    return fixes


def prepare(source: Path, mapping_path: Path, fixes_path: Path, output: Path) -> None:
    output = output.resolve()
    if output.exists():
        raise ValueError(f"đích đã tồn tại: {output}")
    audit = json.loads((source / "audit.json").read_text(encoding="utf-8"))
    if (audit["source_revision"], audit["source_sha256"]) != (REVISION, SHA256):
        raise ValueError("bảng ánh xạ chỉ khớp bản VN82 đã ghim")
    label_map = load_label_map()
    mapping = read_mapping(mapping_path, set(label_map.names.values()))
    fixes = read_fixes(fixes_path, set(label_map.names.values()))

    paths = sorted(path for split in SPLITS for path in (source / "images" / split).iterdir())
    names = [path.name for path in paths]
    if len(set(names)) != len(names):
        raise ValueError("tên ảnh VN82 phải duy nhất giữa các tập nguồn")
    described = [describe(path) for path in paths]
    hashes = np.array([item[0] for item in described], dtype=np.uint8)
    thumbs = np.array([item[1] for item in described], dtype=np.float32)
    order = sorted(range(len(paths)), key=lambda index: int(paths[index].stem))
    groups, pairs = group_images(hashes, thumbs, order)

    dropped = Counter()
    mapped = Counter()
    fixed = Counter()
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as temp:
        root = Path(temp)
        intake = root / "intake.csv"
        with intake.open("w", encoding="utf-8", newline="") as stream:
            writer = csv.writer(stream, lineterminator="\n")
            writer.writerow(["path", "source", "sequence"])
            for path, group in zip(paths, groups, strict=True):
                label = source / "labels" / path.parent.name / path.with_suffix(".txt").name
                key = f"images/{path.parent.name}/{path.name}"
                boxes = []
                for index, box in enumerate(decode_boxes(label.read_bytes()), 1):
                    code = mapping[box.class_id]
                    if (key, index) in fixes:
                        source_id, code = fixes.pop((key, index))
                        if source_id != box.class_id:
                            raise ValueError(f"box sửa nhãn không khớp lớp nguồn: {key} #{index}")
                        fixed[code] += 1
                    if not code:
                        dropped[box.class_id] += 1
                        continue
                    boxes.append(replace(box, class_id=label_map.by_code(code).id))
                    mapped[code] += 1
                target = root / "images" / path.name
                target.parent.mkdir(exist_ok=True)
                shutil.copyfile(path, target)
                write_yolo(root / "labels" / path.with_suffix(".txt").name, boxes)
                writer.writerow([f"images/{path.name}", "public:vn82", f"vn82-g{group:05}"])
        if fixes:
            raise ValueError(f"bảng sửa nhãn có box không tồn tại: {next(iter(fixes))}")

        samples = read_samples(root, intake, label_map)
        assigned = assign_splits(samples)
        staged = root / "bundle"
        write_bundle(root, staged, samples, assigned, label_map, DEFAULT_PATH)
        config_path = staged / "data.yaml"
        config = yaml.safe_load(config_path.read_text(encoding="utf-8"))
        config["path"] = str(output)
        config_path.write_text(
            yaml.safe_dump(config, sort_keys=False, allow_unicode=True), encoding="utf-8"
        )
        by_name = {sample.path.name: assigned[sample.sequence] for sample in samples}
        splits = [by_name[name] for name in names]
        write_json(
            staged / "source-audit.json",
            {
                "source_revision": REVISION,
                "images": len(paths),
                "groups": len(set(groups)),
                "largest_group": max(Counter(groups).values()),
                "grouping": {
                    "hash": "dhash-16x16-cropped",
                    "near_duplicate_max": NEAR_DUPLICATE,
                    "candidate_max": CANDIDATE,
                    "thumbnail_correlation_min": CORRELATION,
                    "consecutive_max": CONSECUTIVE,
                },
                "same_scene_pairs": len(pairs),
                "cross_split_same_scene_pairs": sum(
                    splits[first] != splits[second] for first, second in pairs
                ),
                "dropped_boxes": {str(key): value for key, value in sorted(dropped.items())},
                "mapped_boxes": dict(sorted(mapped.items())),
                "fixed_boxes": dict(sorted(fixed.items())),
            },
        )
        shutil.copyfile(source / "ATTRIBUTION.md", staged / "ATTRIBUTION.md")
        shutil.copyfile(mapping_path, staged / "vn82-to-signa.csv")
        shutil.copyfile(fixes_path, staged / "vn82-box-fixes.csv")
        staged.rename(output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--mapping", type=Path, default=DATA_DIR / "sources" / "vn82-to-signa.csv")
    parser.add_argument("--fixes", type=Path, default=DATA_DIR / "reviews" / "vn82-box-fixes.csv")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        prepare(args.source, args.mapping, args.fixes, args.output)
    except (KeyError, OSError, ValueError) as error:
        parser.exit(2, f"{error}\n")
