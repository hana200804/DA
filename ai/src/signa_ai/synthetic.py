"""Sinh ảnh biển báo tổng hợp và đóng gói cùng dữ liệu Signa (AI-07)."""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import math
import shutil
import tempfile
from collections import Counter
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from urllib.parse import quote
from zipfile import ZIP_DEFLATED, ZIP_STORED, ZipFile, ZipInfo

import numpy as np
import yaml
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter

from signa_ai.dataset import (
    MANIFEST_FIELDS,
    SPLITS,
    SYNTHETIC_BOXES_TAG,
    Box,
    assign_splits,
    read_samples,
    read_yolo,
    relative_path,
    write_bundle,
    write_json,
)
from signa_ai.labels import DEFAULT_PATH, LabelMap, load_label_map

DATA = DEFAULT_PATH.parent
BASE = DATA / "processed" / "v1.1"
TEMPLATES = DATA / "processed" / "synthetic-templates"
TEMPLATE_CSV = DATA / "synthetic" / "templates.csv"
INTAKE_FIELDS = ("path", "source", "sequence", "split", "tags")
THRESHOLD = 150
HEIGHT_RANGE = (16, 200)
MAX_BACKGROUND_BOX_HEIGHT = 0.25
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png"}


@dataclass(frozen=True)
class Frame:
    path: Path
    sequence: str
    sha256: str
    label: Path | None = None


@dataclass(frozen=True)
class Background:
    frame: Frame
    boxes: tuple[Box, ...]


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream))


def write_csv(path: Path, fields: tuple[str, ...], rows: list[dict[str, str]]) -> None:
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def rare_classes(base: Path, threshold: int) -> dict[str, int]:
    if threshold <= 0:
        raise ValueError("ngưỡng lớp hiếm phải dương")
    stats = json.loads((base / "stats.json").read_text(encoding="utf-8"))
    return {
        code: count
        for code, count in sorted(stats["train"]["by_class"].items())
        if count < threshold and not code.startswith("other_")
    }


def label_path(image_path: PurePosixPath) -> PurePosixPath:
    return PurePosixPath("labels", *image_path.parts[1:]).with_suffix(".txt")


def manifest_path(row: dict[str, str]) -> PurePosixPath:
    path = relative_path(row["path"])
    split = row["split"]
    if split not in SPLITS or len(path.parts) < 3 or path.parts[:2] != ("images", split):
        raise ValueError(f"đường dẫn ảnh không khớp tập trong manifest: {path}")
    return path


def background_frames(base: Path, background_dir: Path | None) -> list[Frame]:
    rows = read_csv(base / "manifest.csv")
    protected = [row for row in rows if row["split"] != "train"]
    protected_paths = {(base / manifest_path(row)).resolve() for row in protected}
    protected_hashes = {row["sha256"] for row in protected}
    protected_sequences = {row["sequence"] for row in protected}
    frames = []
    if background_dir is None:
        for row in sorted(rows, key=lambda row: row["path"]):
            path = manifest_path(row)
            if row["split"] != "train":
                continue
            image = base / path
            if (
                not image.resolve().is_relative_to(base.resolve() / "images" / "train")
                or image.resolve() in protected_paths
                or row["sha256"] in protected_hashes
                or row["sequence"] in protected_sequences
            ):
                raise ValueError(f"ảnh hoặc chuỗi train trùng tập đánh giá: {path}")
            frames.append(Frame(image, row["sequence"], row["sha256"], base / label_path(path)))
    else:
        folder = background_dir.resolve()
        if not folder.is_dir() or folder.is_relative_to(base.resolve()):
            raise ValueError("--backgrounds phải là thư mục riêng cho video dành cho train")
        for image in sorted(folder.rglob("*")):
            if not image.is_file() or image.suffix.lower() not in IMAGE_EXTENSIONS:
                continue
            parts = image.relative_to(folder).parts
            if len(parts) < 2 or not image.resolve().is_relative_to(folder):
                raise ValueError(f"cần ảnh trong thư mục theo chuỗi của --backgrounds: {image}")
            digest = sha256(image)
            if (
                image.resolve() in protected_paths
                or digest in protected_hashes
                or parts[0] in protected_sequences
            ):
                raise ValueError(f"nền hoặc chuỗi trùng tập đánh giá: {image}")
            frames.append(Frame(image, parts[0], digest))
    if not frames:
        raise ValueError("cần ít nhất một ảnh nền train")
    return frames


def eligible_backgrounds(
    frames: list[Frame], label_map: LabelMap, max_box_height: float, rng: np.random.Generator
) -> tuple[dict[str, Background], int]:
    if not 0 < max_box_height <= 1:
        raise ValueError("--max-background-box-height phải thuộc khoảng (0, 1]")
    groups: dict[int, list[Background]] = {0: [], 1: []}
    rejected_closeup = 0
    for frame in frames:
        boxes = read_yolo(frame.label, label_map) if frame.label else ()
        if any(box.bottom - box.top > max_box_height + 1e-12 for box in boxes):
            rejected_closeup += 1
        elif len(boxes) <= 1:
            groups[len(boxes)].append(Background(frame, boxes))
    available = {}
    for group in groups.values():
        for index in rng.permutation(len(group)):
            candidate = group[index]
            available.setdefault(candidate.frame.sha256, candidate)
    return available, rejected_closeup


def read_template(path: Path, expected_sha: str) -> Image.Image:
    payload = path.read_bytes()
    if hashlib.sha256(payload).hexdigest() != expected_sha:
        raise ValueError(f"SHA-256 của PNG không khớp: {path}")
    with Image.open(io.BytesIO(payload)) as png:
        if png.format != "PNG":
            raise ValueError(f"hình vẽ phải là PNG: {path}")
        image = png.convert("RGBA")
    bounds = image.getbbox()
    if bounds is None:
        raise ValueError(f"hình vẽ không có điểm ảnh hiển thị: {path}")
    return image.crop(bounds)


def perspective(image: Image.Image, rng: np.random.Generator) -> Image.Image:
    width, height = image.size
    corners = np.array([(0, 0), (width, 0), (width, height), (0, height)])
    displaced = corners + rng.uniform(-0.055, 0.055, (4, 2)) * (width, height)
    # Pillow ánh xạ ngược từ tọa độ đích về tọa độ của hình vẽ.
    matrix = []
    for (x, y), (u, v) in zip(displaced, corners, strict=True):
        matrix.extend([(x, y, 1, 0, 0, 0, -x * u, -y * u), (0, 0, 0, x, y, 1, -x * v, -y * v)])
    coefficients = np.linalg.solve(matrix, corners.ravel())
    pad = math.ceil(max(width, height) * 0.06)
    padded = Image.new("RGBA", (width + 2 * pad, height + 2 * pad))
    padded.paste(image, (pad, pad))
    # Dịch hệ tọa độ vào vùng có lề để giữ các góc nhô ra khi biến đổi.
    transform = np.array([*coefficients, 1]).reshape((3, 3))
    shift = np.array([(1, 0, pad), (0, 1, pad), (0, 0, 1)])
    unshift = np.array([(1, 0, -pad), (0, 1, -pad), (0, 0, 1)])
    transform = shift @ transform @ unshift
    return padded.transform(
        padded.size,
        Image.Transform.PERSPECTIVE,
        (transform / transform[2, 2]).ravel()[:8],
        Image.Resampling.BICUBIC,
    )


def render(template: Image.Image, height: int, rng: np.random.Generator) -> Image.Image:
    width = max(1, round(template.width * height / template.height))
    sign = template.resize((2 * width, 2 * height), Image.Resampling.LANCZOS)
    sign = perspective(sign, rng).rotate(
        rng.uniform(-10, 10), resample=Image.Resampling.BICUBIC, expand=True
    )
    sign = sign.crop(sign.getbbox())
    width = max(1, round(sign.width * height / sign.height))
    sign = sign.resize((width, height), Image.Resampling.LANCZOS)
    # Giảm độ nét của ảnh render trước khi thêm nhiễu để gần với ảnh nền.
    scale = rng.uniform(0.45, 0.75)
    reduced = (max(1, round(width * scale)), max(1, round(height * scale)))
    sign = sign.resize(reduced, Image.Resampling.LANCZOS).resize(
        (width, height), Image.Resampling.BILINEAR
    )
    color = ImageEnhance.Brightness(sign.convert("RGB")).enhance(rng.uniform(0.70, 1.30))
    color = ImageEnhance.Contrast(color).enhance(rng.uniform(0.70, 1.30))
    # Cùng một giá trị nhiễu cho ba kênh để giữ sắc độ.
    noise = rng.normal(0, rng.uniform(0, 6), (height, width, 1))
    color = Image.fromarray(np.clip(np.asarray(color) + noise, 0, 255).astype(np.uint8))
    color.putalpha(sign.getchannel("A"))
    return color.filter(ImageFilter.GaussianBlur(rng.uniform(0, 0.85)))


def overlaps(box: Box, left: float, top: float, right: float, bottom: float) -> bool:
    return box.left < right and box.right > left and box.top < bottom and box.bottom > top


def trim_paired_bars(dark_lines: np.ndarray) -> tuple[int, int]:
    size = len(dark_lines)
    content = np.flatnonzero(~dark_lines)
    if content.size:
        start, stop = int(content[0]), int(content[-1]) + 1
        end_bar = size - stop
        if min(start, end_bar) >= 4 and abs(start - end_bar) <= max(3, min(start, end_bar) // 10):
            return start, stop
    return 0, size


def placement_region(frame: Image.Image) -> tuple[int, int, int, int]:
    # Ảnh VN82 thường có hai dải đen do giữ tỷ lệ khung hình.
    dark = np.asarray(frame.convert("L")) <= 12
    left, right = trim_paired_bars(dark.mean(axis=0) >= 0.995)
    top, bottom = trim_paired_bars(dark.mean(axis=1) >= 0.995)
    return left, top, right, min(frame.height // 2, (top + bottom) // 2)


def place(
    frame: Image.Image,
    sign: Image.Image,
    boxes: tuple[Box, ...],
    class_id: int,
    rng: np.random.Generator,
    region: tuple[int, int, int, int],
) -> Box | None:
    width, height = frame.size
    margin = max(4, round(0.01 * min(width, height)))
    region_left, region_top, region_right, region_bottom = region
    side_width = round((region_right - region_left) * 0.35)
    xmax = side_width - sign.width - margin
    ymax = region_bottom - sign.height
    ymin = region_top + margin
    if xmax < margin or ymax < ymin:
        return None
    for _ in range(16):
        offset = int(rng.integers(margin, xmax + 1))
        x = region_right - offset - sign.width if rng.integers(0, 2) else region_left + offset
        y = int(rng.integers(ymin, ymax + 1))
        right, bottom = x + sign.width, y + sign.height
        if any(
            overlaps(
                box,
                (x - margin) / width,
                (y - margin) / height,
                (right + margin) / width,
                (bottom + margin) / height,
            )
            for box in boxes
        ):
            continue
        if rng.random() < 0.22:
            # Một phần góc bị nền che. Box vẫn bao quanh toàn bộ biển.
            alpha = sign.getchannel("A")
            cover_x = max(1, round(sign.width * rng.uniform(0.14, 0.30)))
            cover_y = max(1, round(sign.height * rng.uniform(0.14, 0.30)))
            left = (sign.width - cover_x) if rng.integers(0, 2) else 0
            top = (sign.height - cover_y) if rng.integers(0, 2) else 0
            ImageDraw.Draw(alpha).rectangle(
                (left, top, left + cover_x - 1, top + cover_y - 1), fill=0
            )
            sign.putalpha(alpha)
        frame.paste(sign, (x, y), sign)
        return Box.from_edges(class_id, (x / width, y / height, right / width, bottom / height))
    return None


def compose(
    background: Background,
    template: Image.Image,
    class_id: int,
    rng: np.random.Generator,
) -> tuple[Image.Image, Box] | None:
    frame = background.frame
    payload = frame.path.read_bytes()
    if hashlib.sha256(payload).hexdigest() != frame.sha256:
        raise ValueError(f"ảnh nền đã thay đổi: {frame.path}")
    with Image.open(io.BytesIO(payload)) as original:
        image = original.convert("RGB")
    region = placement_region(image)
    _, top, _, bottom = region
    max_height = min(HEIGHT_RANGE[1], bottom - top - max(4, image.height // 100))
    if max_height < HEIGHT_RANGE[0]:
        return None
    height = int(rng.integers(HEIGHT_RANGE[0], max_height + 1))
    # Thử biển nhỏ hơn trước khi bỏ qua một nền ít box.
    for size in dict.fromkeys((height, max(HEIGHT_RANGE[0], height // 2), HEIGHT_RANGE[0])):
        sign = render(template, size, rng)
        added = place(image, sign, background.boxes, class_id, rng, region)
        if added is not None:
            return image, added
    return None


def take_background(
    available: dict[str, Background],
    template: Image.Image,
    class_id: int,
    rng: np.random.Generator,
) -> tuple[Background, Image.Image, Box] | None:
    for background in available.values():
        result = compose(background, template, class_id, rng)
        if result is not None:
            break
    else:
        return None
    # Một SHA-256 chỉ có thể xuất hiện trong một ảnh đã sinh.
    del available[background.frame.sha256]
    return background, *result


def generate(
    base: Path,
    output: Path,
    templates: Path,
    template_csv: Path,
    *,
    threshold: int = THRESHOLD,
    seed: int = 0,
    backgrounds: Path | None = None,
    max_background_box_height: float = MAX_BACKGROUND_BOX_HEIGHT,
) -> None:
    if output.exists():
        raise ValueError(f"đích đã tồn tại: {output}")
    counts = rare_classes(base, threshold)
    frames = background_frames(base, backgrounds)
    label_map = load_label_map(base / "labels.yaml")
    catalog = {row["code"]: row for row in read_csv(template_csv)}
    rng = np.random.default_rng(seed)
    available, rejected_closeup = eligible_backgrounds(
        frames, label_map, max_background_box_height, rng
    )
    eligible_counts = Counter(len(background.boxes) for background in available.values())
    used_counts: Counter[int] = Counter()
    copied: Counter[str] = Counter()
    rows = []
    report = {
        code: {"real": real, "cap": real * 3 // 7, "synthetic": 0, "has_template": code in catalog}
        for code, real in counts.items()
    }
    artwork = {
        code: read_template(templates / f"{code}.png", catalog[code]["sha256"])
        for code, row in report.items()
        if row["cap"] and row["has_template"]
    }
    targets = [code for code in artwork for _ in range(report[code]["cap"])]
    # Trộn thứ tự lớp để các nền 0 box được trải qua nhiều lớp.
    rng.shuffle(targets)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as tmp:
        staged = Path(tmp) / "intake"
        (staged / "images").mkdir(parents=True)
        (staged / "labels").mkdir()
        for code in targets:
            if not available:
                break
            class_id = label_map.by_code(code).id
            result = take_background(available, artwork[code], class_id, rng)
            if result is None:
                continue
            background, image, box = result
            frame = background.frame
            generated = report[code]["synthetic"]
            path = PurePosixPath("images", f"syn-{class_id:02d}-{generated:05d}.png")
            image.save(staged / path, compress_level=1)
            original = frame.label.read_bytes() if frame.label else b""
            newline = b"\n" if original and not original.endswith(b"\n") else b""
            (staged / label_path(path)).write_bytes(
                original + newline + (box.yolo() + "\n").encode("utf-8")
            )
            rows.append(
                {
                    "path": str(path),
                    "source": "synthetic",
                    "sequence": frame.sequence,
                    "split": "train",
                    "tags": SYNTHETIC_BOXES_TAG + "1",
                }
            )
            report[code]["synthetic"] += 1
            used_counts[len(background.boxes)] += 1
            copied.update(
                label_map.by_id(original_box.class_id).code for original_box in background.boxes
            )
        if not rows:
            raise ValueError("không sinh được ảnh nào từ bộ hình vẽ và nền đã chọn")
        write_csv(staged / "intake.csv", INTAKE_FIELDS, sorted(rows, key=lambda row: row["path"]))
        write_json(
            staged / "generation.json",
            {
                "seed": seed,
                "threshold": threshold,
                "classes": report,
                "copied_real_boxes": copied.total(),
                "copied_real_by_class": dict(sorted(copied.items())),
                "backgrounds": {
                    "max_box_height": max_background_box_height,
                    "rejected_closeup": rejected_closeup,
                    "eligible_by_box_count": {count: eligible_counts[count] for count in (0, 1)},
                    "used_by_box_count": {count: used_counts[count] for count in (0, 1)},
                },
            },
        )
        staged.rename(output)
    print(f"Ảnh tổng hợp: {len(rows)}")
    print(f"Nền bị loại vì box cao hơn {max_background_box_height:.1%}: {rejected_closeup}")
    print(f"Nền đã dùng: {used_counts[0]} ảnh 0 box, {used_counts[1]} ảnh 1 box")
    print(f"Box thật chép từ nền: {copied.total()}")
    for code, count in sorted(copied.items()):
        print(f"  {code}: {count}")
    for code, row in report.items():
        synthetic, real = row["synthetic"], row["real"]
        ratio = 100 * synthetic / (real + synthetic) if real else 0
        note = " (thiếu hình vẽ)" if not row["has_template"] else ""
        print(f"{code}: {synthetic}/{row['cap']} box, {ratio:.2f}% ({real} box thật){note}")


def train_inputs(
    base: Path,
    synthetic: Path,
    root: Path,
    manifest: list[dict[str, str]],
    held_sequences: set[str],
) -> tuple[list[dict[str, str]], dict[PurePosixPath, str]]:
    rows = []
    original_hashes = {}
    for original, entries in ((True, manifest), (False, read_csv(synthetic / "intake.csv"))):
        for row in entries:
            if original and row["split"] != "train":
                continue
            if row["split"] != "train" or (row["source"] == "synthetic") == original:
                raise ValueError(f"nguồn hoặc tập không hợp lệ cho train: {row['path']}")
            if row["sequence"] in held_sequences:
                raise ValueError(f"chuỗi train trùng tập đánh giá: {row['sequence']}")
            if original:
                source = manifest_path(row)
                path = PurePosixPath("images", *source.parts[2:])
                original_hashes[path] = row["sha256"]
            else:
                source = path = relative_path(row["path"])
                if path.parts[0] != "images" or len(path.parts) < 2:
                    raise ValueError(f"đường dẫn ảnh tổng hợp không hợp lệ: {path}")
            folder = base if original else synthetic
            for relative, target in ((source, path), (label_path(source), label_path(path))):
                (root / target).parent.mkdir(parents=True, exist_ok=True)
                (root / target).symlink_to((folder / relative).resolve(strict=True))
            rows.append({**{field: row[field] for field in INTAKE_FIELDS}, "path": str(path)})
    return rows, original_hashes


def create_preview(
    bundle: Path,
    labels: LabelMap,
    count: int,
    synthesized: list[dict[str, str]],
) -> None:
    if not count:
        return
    preview = bundle / "preview"
    preview.mkdir()
    examples = {}
    for row in synthesized:
        path = PurePosixPath(row["path"])
        boxes = read_yolo(bundle / label_path(path), labels)
        examples.setdefault(boxes[-1].class_id, (path, boxes))
    for path, boxes in list(examples.values())[:count]:
        with Image.open(bundle / path) as original:
            image = original.convert("RGB")
        draw = ImageDraw.Draw(image)
        for index, box in enumerate(boxes):
            x, y, w, h = box.coco(*image.size)
            color = "lime" if index == len(boxes) - 1 else "yellow"
            draw.rectangle((x, y, x + w, y + h), outline=color, width=3)
            draw.text(
                (x, max(0, y - 13)),
                labels.by_id(box.class_id).code,
                fill=color,
                stroke_width=1,
                stroke_fill="black",
            )
        image.save(preview / path.name)


def write_zip(bundle: Path, name: str, archive_path: Path) -> None:
    with ZipFile(archive_path, "w") as archive:
        for path in sorted(bundle.rglob("*")):
            if path.is_file():
                info = ZipInfo((Path(name) / path.relative_to(bundle)).as_posix())
                info.compress_type = ZIP_STORED if path.suffix in IMAGE_EXTENSIONS else ZIP_DEFLATED
                with path.open("rb") as source, archive.open(info, "w") as target:
                    shutil.copyfileobj(source, target)


def write_attribution(base: Path, bundle: Path, template_csv: Path, used_classes: set[str]) -> None:
    attribution = ["# Ghi công dữ liệu tổng hợp"]
    original = base / "ATTRIBUTION.md"
    if original.is_file():
        text = original.read_text(encoding="utf-8").strip()
        attribution.append("## " + text.removeprefix("# ") if text.startswith("# ") else text)
    templates = [row for row in read_csv(template_csv) if row["code"] in used_classes]
    if templates:
        attribution.extend(
            [
                "## Hình vẽ từ Wikimedia Commons",
                f"Signa dùng {len(templates)} hình vẽ và đã biến đổi, dán chúng lên ảnh nền. "
                "Bảng dưới đây lấy tên tác giả và giấy phép từ [templates.csv](templates.csv).",
            ]
        )
        credits = []
        for row in sorted(templates, key=lambda row: row["code"]):
            title = quote(row["commons_file"].replace(" ", "_"), safe=":")
            source = f"https://commons.wikimedia.org/wiki/{title}"
            credits.append(
                f"- `{row['code']}` — {row['author']}, {row['license']}. "
                f"[Tệp trên Wikimedia Commons]({source})"
            )
        attribution.append("\n".join(credits))
        if any(row["license"] == "CC BY-SA 4.0" for row in templates):
            attribution.append(
                "Ảnh tổng hợp sử dụng hình vẽ CC BY-SA 4.0 là tác phẩm phái sinh và phải "
                "được chia sẻ theo CC BY-SA 4.0. "
                "[Điều khoản CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)"
            )
    (bundle / "ATTRIBUTION.md").write_text("\n\n".join(attribution) + "\n", encoding="utf-8")


def package(
    base: Path,
    synthetic: Path,
    output: Path,
    template_csv: Path,
    *,
    preview_count: int = 6,
) -> None:
    output = output.absolute()
    zip_path = output.with_name(f"signa-{output.name}.zip")
    checksum = output.with_name(f"signa-{output.name}.sha256")
    for path in (output, zip_path, checksum):
        if path.exists():
            raise ValueError(f"đích đã tồn tại: {path}")
    if preview_count < 0:
        raise ValueError("số ảnh xem mẫu không thể âm")
    output.parent.mkdir(parents=True, exist_ok=True)
    labels_path = base / "labels.yaml"
    labels = load_label_map(labels_path)
    manifest = read_csv(base / "manifest.csv")
    held = [row for row in manifest if row["split"] != "train"]
    held_hashes = {row["sha256"] for row in held}
    held_sequences = {row["sequence"] for row in held}
    with tempfile.TemporaryDirectory(dir=output.parent) as tmp:
        staging = Path(tmp)
        root = staging / "input"
        root.mkdir()
        rows, original_hashes = train_inputs(base, synthetic, root, manifest, held_sequences)
        intake = root / "intake.csv"
        write_csv(intake, INTAKE_FIELDS, rows)
        samples = read_samples(root, intake, labels)
        for sample in samples:
            if sample.path in original_hashes and original_hashes[sample.path] != sample.sha256:
                raise ValueError(f"ảnh train gốc khác manifest: {sample.path}")
            if sample.sha256 in held_hashes:
                raise ValueError(f"ảnh train trùng tập đánh giá: {sample.path}")
        bundle = staging / "bundle"
        write_bundle(root, bundle, samples, assign_splits(samples), labels, labels_path)
        train = read_csv(bundle / "manifest.csv")
        for row in train:
            path = manifest_path(row)
            key = label_path(PurePosixPath("images", *path.parts[2:]))
            shutil.copyfile(root / key, bundle / label_path(path))
        write_csv(bundle / "manifest.csv", MANIFEST_FIELDS, train + held)
        base_stats = json.loads((base / "stats.json").read_text(encoding="utf-8"))
        stats = json.loads((bundle / "stats.json").read_text(encoding="utf-8"))
        config_path = bundle / "data.yaml"
        config = yaml.safe_load(config_path.read_text(encoding="utf-8"))
        for split in SPLITS[1:]:
            for kind in ("images", "labels"):
                folder = base / kind / split
                if folder.exists():
                    shutil.copytree(folder, bundle / kind / split)
            shutil.copyfile(base / "coco" / f"{split}.json", bundle / "coco" / f"{split}.json")
            stats[split] = base_stats[split]
            if split in ("val", "test") and stats[split]["images"]:
                config[split] = f"images/{split}"
        write_json(bundle / "stats.json", stats)
        config["path"] = str(output)
        config_path.write_text(
            yaml.safe_dump(config, sort_keys=False, allow_unicode=True), encoding="utf-8"
        )
        provenance = (
            "source-audit.json",
            "vn82-box-fixes.csv",
            "vn82-to-signa.csv",
        )
        for name in provenance:
            if (base / name).is_file():
                shutil.copyfile(base / name, bundle / name)
        shutil.copyfile(template_csv, bundle / "templates.csv")
        write_attribution(base, bundle, template_csv, set(stats["train"]["synthetic_by_class"]))
        shutil.copyfile(synthetic / "generation.json", bundle / "generation.json")
        synthesized = [row for row in train if row["source"] == "synthetic"]
        write_csv(
            bundle / "intake.csv",
            INTAKE_FIELDS,
            [{field: row[field] for field in INTAKE_FIELDS} for row in synthesized],
        )
        archive = staging / zip_path.name
        write_zip(bundle, output.name, archive)
        archive_hash = sha256(archive)
        manifest_hash = sha256(bundle / "manifest.csv")
        checksums = staging / checksum.name
        checksums.write_text(
            f"{archive_hash}  {zip_path.name}\n{manifest_hash}  {output.name}/manifest.csv\n",
            encoding="utf-8",
        )
        create_preview(bundle, labels, preview_count, synthesized)
        bundle.rename(output)
        archive.rename(zip_path)
        checksums.rename(checksum)
    print(f"Train: {stats['train']['images']} ảnh, {stats['train']['boxes']} box")
    print(checksum.read_text(encoding="utf-8"), end="")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    gen = commands.add_parser("generate", help="Sinh ảnh, nhãn YOLO và CSV intake")
    gen.add_argument("--templates", type=Path, default=TEMPLATES)
    gen.add_argument("--backgrounds", type=Path)
    gen.add_argument("--seed", type=int, default=0)
    gen.add_argument("--threshold", type=int, default=THRESHOLD)
    gen.add_argument("--max-background-box-height", type=float, default=MAX_BACKGROUND_BOX_HEIGHT)
    gen.add_argument("--output", type=Path, default=BASE.with_name("v1.1-syn-intake"))
    pack = commands.add_parser("package", help="Ghép train và giữ nguyên các tập đánh giá")
    pack.add_argument("--synthetic", type=Path, default=BASE.with_name("v1.1-syn-intake"))
    pack.add_argument("--preview-count", type=int, default=6)
    pack.add_argument("--output", type=Path, default=BASE.with_name("v1.1-syn"))
    for command in (gen, pack):
        command.add_argument("--base", type=Path, default=BASE)
        command.add_argument("--template-csv", type=Path, default=TEMPLATE_CSV)
    args = parser.parse_args()
    try:
        if args.command == "generate":
            generate(
                args.base.resolve(),
                args.output,
                args.templates,
                args.template_csv,
                threshold=args.threshold,
                seed=args.seed,
                backgrounds=args.backgrounds,
                max_background_box_height=args.max_background_box_height,
            )
        else:
            package(
                args.base.resolve(),
                args.synthetic,
                args.output,
                args.template_csv,
                preview_count=args.preview_count,
            )
    except (KeyError, OSError, ValueError) as error:
        parser.exit(2, f"{error}\n")


if __name__ == "__main__":
    main()
