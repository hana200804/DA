"""Kiểm kê ZIP VN82 và xuất dữ liệu YOLO dùng thử trên Colab."""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import shutil
import tempfile
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from zipfile import ZipFile

import yaml
from PIL import Image

from signa_ai.dataset import Box, relative_path, write_json, write_yolo

REVISION = "bd948a572cefbea92a1c523a2784c1ea9cf1dbe1"
SHA256 = "d8c8a777c691c15c15d72b1b2c9b0b2abd55090d1505dd3974f5fbab9ca2bbca"
SPLITS = ("train", "val", "test")
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png"}
DRAFT_CODES = {0: "P.102", 17: "P.123a", 39: "P.127_50", 56: "R.303"}
REVIEW_CLASSES = {29: "Xác định biến thể W.207", 31: "Tên tiếng Anh và tiếng Việt lệch nhau"}
OUTSIDE_SIGNA = {54, 55}
SOURCE_URL = (
    "https://huggingface.co/datasets/star092304/Traffic-sign-detection-VietNam/tree/" + REVISION
)


@dataclass(frozen=True)
class Frame:
    member: str
    split: str
    name: PurePosixPath
    width: int
    height: int
    boxes: tuple[Box, ...]

    @property
    def path(self) -> Path:
        return Path("images", self.split, self.name)


def read_classes(path: Path) -> dict[int, dict[str, str]]:
    with path.open(encoding="utf-8-sig", newline="") as stream:
        classes = {int(row["ID"]): row for row in csv.DictReader(stream)}
    if sorted(classes) != list(range(82)):
        raise ValueError("bảng lớp VN82 phải có ID liên tiếp từ 0 đến 81")
    return classes


def index_files(archive: ZipFile) -> tuple[dict, dict]:
    images = {}
    labels = {}
    for member in archive.infolist():
        if member.is_dir():
            continue
        parts = relative_path(member.filename).parts
        if any(part.startswith(".") or part == "__MACOSX" for part in parts):
            continue
        for index, (split, kind) in enumerate(zip(parts, parts[1:], strict=False)):
            if split not in SPLITS or kind not in {"images", "labels"}:
                continue
            name = PurePosixPath(*parts[index + 2 :])
            extension = name.suffix.lower()
            if kind == "images" and extension in IMAGE_EXTENSIONS:
                files = images
            elif kind == "labels" and extension == ".txt":
                files = labels
            else:
                break
            key = (split, name.with_suffix(""))
            if key in files:
                raise ValueError(f"tệp trùng khóa trong ZIP: {member.filename}")
            files[key] = (member.filename, name)
            break
    return images, labels


def decode_boxes(raw: bytes) -> tuple[Box, ...]:
    boxes: dict[str, Box] = {}
    for line in raw.decode("utf-8-sig").splitlines():
        if not line.strip():
            continue
        fields = line.split()
        if len(fields) != 5:
            raise ValueError("dòng nhãn YOLO phải có đúng 5 trường")
        class_id = int(fields[0])
        if not 0 <= class_id < 82:
            raise ValueError(f"ID lớp ngoài VN82: {class_id}")
        x, y, w, h = map(float, fields[1:])
        box = Box.from_edges(class_id, (x - w / 2, y - h / 2, x + w / 2, y + h / 2))
        boxes[box.yolo()] = box
    return tuple(boxes.values())


def inspect(archive: ZipFile) -> tuple[dict, list[Frame], list[tuple[str, str]]]:
    images, labels = index_files(archive)
    by_hash: dict[str, list[Frame]] = defaultdict(list)
    issues = [(labels[key][0], "nhãn thiếu ảnh") for key in sorted(labels.keys() - images.keys())]
    for (split, stem), (member, name) in sorted(images.items()):
        label = labels.get((split, stem))
        if label is None:
            issues.append((member, "ảnh thiếu tệp nhãn"))
            continue
        try:
            boxes = decode_boxes(archive.read(label[0]))
            binary = archive.read(member)
            with Image.open(io.BytesIO(binary)) as image:
                image.load()
                width, height = image.size
        except (OSError, ValueError) as error:
            issues.append((member, str(error)))
            continue
        digest = hashlib.sha256(binary).hexdigest()
        by_hash[digest].append(Frame(member, split, name, width, height, boxes))

    kept = []
    for group in by_hash.values():
        if len(group) == 1:
            kept.extend(group)
            continue
        annotations = {frozenset(box.yolo() for box in frame.boxes) for frame in group}
        if len(annotations) > 1:
            issues.extend((frame.member, "ảnh trùng SHA-256 có nhãn khác nhau") for frame in group)
            continue
        # Ưu tiên giữ mẫu đánh giá khi các bản sao có nhãn giống hệt.
        group.sort(key=lambda frame: (-SPLITS.index(frame.split), frame.member))
        kept.append(group[0])
        issues.extend((frame.member, "ảnh trùng SHA-256") for frame in group[1:])

    raw = dict(Counter(split for split, _ in images))
    return raw, sorted(kept, key=lambda frame: (frame.split, frame.name)), issues


def write_reports(
    output: Path,
    classes: dict[int, dict[str, str]],
    frames: list[Frame],
    issues: list[tuple[str, str]],
    raw: dict,
) -> None:
    counts: dict[str, Counter] = {split: Counter() for split in SPLITS}
    images = Counter(frame.split for frame in frames)
    empty = Counter(frame.split for frame in frames if not frame.boxes)
    for frame in frames:
        counts[frame.split].update(box.class_id for box in frame.boxes)

    with (output / "class-counts.csv").open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(["id", "name_en", "name_vi", *SPLITS, "signa_code_draft", "note"])
        for class_id, row in sorted(classes.items()):
            writer.writerow(
                [
                    class_id,
                    row["classes-en"].strip(),
                    row["classes-vn"].strip(),
                    *(counts[split][class_id] for split in SPLITS),
                    DRAFT_CODES.get(class_id, ""),
                    "Đèn tín hiệu, ngoài danh mục biển Signa"
                    if class_id in OUTSIDE_SIGNA
                    else REVIEW_CLASSES.get(class_id, ""),
                ]
            )
    with (output / "issues.csv").open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(["member", "reason"])
        writer.writerows(issues)
    write_review(output, frames)

    write_json(
        output / "audit.json",
        {
            "source_revision": REVISION,
            "source_sha256": SHA256,
            "source_images": raw,
            "images": dict(images),
            "negative_images": dict(empty),
            "boxes": {split: counts[split].total() for split in SPLITS},
            "classes_present": {split: len(counts[split]) for split in SPLITS},
            "issues": len(issues),
        },
    )


def write_review(output: Path, frames: list[Frame]) -> None:
    with (output / "review-boxes-train.csv").open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(
            ["image", "preview", "box_index", "source_id", "x", "y", "w", "h", "signa_code", "note"]
        )
        for frame in frames:
            if frame.split != "train":
                continue
            candidates = [
                (index, box)
                for index, box in enumerate(frame.boxes, 1)
                if box.class_id in REVIEW_CLASSES
            ]
            if not candidates:
                continue
            with Image.open(output / frame.path) as image:
                for index, box in candidates:
                    x, y, width, height = box.coco(frame.width, frame.height)
                    crop = image.crop(
                        (
                            max(0, x - 0.15 * width),
                            max(0, y - 0.15 * height),
                            min(frame.width, x + 1.15 * width),
                            min(frame.height, y + 1.15 * height),
                        )
                    ).convert("RGB")
                    crop.thumbnail((384, 384))
                    preview = Path(
                        "review-crops", frame.name.parent, f"{frame.name.stem}-box-{index}.jpg"
                    )
                    (output / preview).parent.mkdir(parents=True, exist_ok=True)
                    crop.save(output / preview, quality=90)
                    writer.writerow(
                        [
                            frame.path.as_posix(),
                            preview.as_posix(),
                            index,
                            box.class_id,
                            *(round(value, 2) for value in (x, y, width, height)),
                            "",
                            REVIEW_CLASSES[box.class_id],
                        ]
                    )


def write_coco(output: Path, names: dict[int, str], frames: list[Frame]) -> None:
    output.mkdir()
    categories = [{"id": class_id, "name": name} for class_id, name in names.items()]
    for split in SPLITS:
        images = []
        annotations = []
        for frame in frames:
            if frame.split != split:
                continue
            image_id = len(images) + 1
            images.append(
                {
                    "id": image_id,
                    "file_name": frame.path.as_posix(),
                    "width": frame.width,
                    "height": frame.height,
                }
            )
            for box in frame.boxes:
                bbox = box.coco(frame.width, frame.height)
                annotations.append(
                    {
                        "id": len(annotations) + 1,
                        "image_id": image_id,
                        "category_id": box.class_id,
                        "bbox": bbox,
                        "area": bbox[2] * bbox[3],
                        "iscrowd": 0,
                    }
                )
        write_json(
            output / f"{split}.json",
            {
                "info": {"source": SOURCE_URL},
                "images": images,
                "annotations": annotations,
                "categories": categories,
            },
        )


def prepare(archive_path: Path, classes_path: Path, output: Path) -> None:
    if output.exists():
        raise ValueError(f"đích đã tồn tại: {output}")
    with archive_path.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != SHA256:
            raise ValueError("SHA-256 của ZIP khác bản VN82 đã công bố")
    classes = read_classes(classes_path)
    output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(archive_path) as archive, tempfile.TemporaryDirectory(dir=output.parent) as temp:
        raw, frames, issues = inspect(archive)
        if set(raw) != set(SPLITS) or {frame.split for frame in frames} != set(SPLITS):
            raise ValueError("cần dữ liệu hợp lệ trong cả train, val và test")
        staging = Path(temp) / "vn82"
        staging.mkdir()
        for frame in frames:
            target = staging / frame.path
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(frame.member) as source, target.open("wb") as dest:
                shutil.copyfileobj(source, dest)
            label = staging / "labels" / frame.split / frame.name.with_suffix(".txt")
            write_yolo(label, frame.boxes)
        names = {class_id: row["classes-en"].strip() for class_id, row in sorted(classes.items())}
        config = {**{split: f"images/{split}" for split in SPLITS}, "names": names}
        (staging / "data.yaml").write_text(
            yaml.safe_dump(config, sort_keys=False), encoding="utf-8"
        )
        write_coco(staging / "coco", names, frames)
        write_reports(staging, classes, frames, issues, raw)
        shutil.copyfile(classes_path, staging / "source-classid.csv")
        (staging / "ATTRIBUTION.md").write_text(
            "# Ghi công VN82\n\n"
            "Ảnh và nhãn từ Vietnam Traffic Sign Detection Dataset của star092304. "
            f"[Bản công bố]({SOURCE_URL})\n\n"
            "Metadata ghi CC BY 4.0 và liên kết giấy phép trên README dẫn đến CC BY-NC 4.0. "
            "Bản này phục vụ huấn luyện phi thương mại trong đồ án. "
            "Xác nhận điều khoản với tác giả trước khi phát hành lại.\n\n"
            "Signa kiểm tra định dạng, loại ảnh trùng hoặc có lỗi nhãn, giữ ID lớp nguồn "
            "và xuất lại ở định dạng YOLO và COCO.\n",
            encoding="utf-8",
        )
        staging.rename(output)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", required=True, type=Path)
    parser.add_argument("--classes", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        prepare(args.archive, args.classes, args.output)
    except (OSError, ValueError) as error:
        parser.exit(2, f"{error}\n")
