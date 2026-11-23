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
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
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
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png"}


@dataclass(frozen=True)
class Frame:
    path: Path
    sequence: str
    sha256: str
    label: Path | None = None


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
    color = ImageEnhance.Brightness(sign.convert("RGB")).enhance(rng.uniform(0.70, 1.30))
    color = ImageEnhance.Contrast(color).enhance(rng.uniform(0.70, 1.30))
    # Cùng một giá trị nhiễu cho ba kênh để giữ sắc độ.
    noise = rng.normal(0, rng.uniform(0, 6), (height, width, 1))
    color = Image.fromarray(np.clip(np.asarray(color) + noise, 0, 255).astype(np.uint8))
    color.putalpha(sign.getchannel("A"))
    return color.filter(ImageFilter.GaussianBlur(rng.uniform(0, 0.85)))


def overlaps(box: Box, left: float, top: float, right: float, bottom: float) -> bool:
    return box.left < right and box.right > left and box.top < bottom and box.bottom > top


def place(
    frame: Image.Image,
    sign: Image.Image,
    boxes: tuple[Box, ...],
    class_id: int,
    rng: np.random.Generator,
) -> Box | None:
    width, height = frame.size
    margin = max(4, round(0.01 * min(width, height)))
    side_width = round(width * 0.35)
    xmax = side_width - sign.width - margin
    ymax = height // 2 - sign.height
    if xmax < margin or ymax < margin:
        return None
    for _ in range(16):
        x = int(rng.integers(margin, xmax + 1))
        if rng.integers(0, 2):
            x = width - x - sign.width
        y = int(rng.integers(margin, ymax + 1))
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
    background: Frame,
    template: Image.Image,
    class_id: int,
    label_map: LabelMap,
    rng: np.random.Generator,
) -> tuple[Image.Image, Box] | None:
    payload = background.path.read_bytes()
    if hashlib.sha256(payload).hexdigest() != background.sha256:
        raise ValueError(f"ảnh nền đã thay đổi: {background.path}")
    with Image.open(io.BytesIO(payload)) as original:
        image = original.convert("RGB")
    boxes = read_yolo(background.label, label_map) if background.label else ()
    max_height = min(HEIGHT_RANGE[1], image.height // 2 - max(4, image.height // 100))
    if max_height < HEIGHT_RANGE[0]:
        return None
    height = int(rng.integers(HEIGHT_RANGE[0], max_height + 1))
    sign = render(template, height, rng)
    added = place(image, sign, boxes, class_id, rng)
    return (image, added) if added else None


def generate(
    base: Path,
    output: Path,
    templates: Path,
    template_csv: Path,
    *,
    threshold: int = THRESHOLD,
    seed: int = 0,
    backgrounds: Path | None = None,
) -> None:
    if output.exists():
        raise ValueError(f"đích đã tồn tại: {output}")
    counts = rare_classes(base, threshold)
    frames = background_frames(base, backgrounds)
    label_map = load_label_map(base / "labels.yaml")
    catalog = {row["code"]: row for row in read_csv(template_csv)}
    rng = np.random.default_rng(seed)
    rows = []
    report = {}
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as tmp:
        staged = Path(tmp) / "intake"
        (staged / "images").mkdir(parents=True)
        (staged / "labels").mkdir()
        for code, real in counts.items():
            cap = real * 3 // 7
            generated = 0
            if cap and code in catalog:
                template = read_template(templates / f"{code}.png", catalog[code]["sha256"])
                class_id = label_map.by_code(code).id
                # Đổi nền sau mỗi lần thử, dùng tối đa 20 lần thử cho mỗi box.
                for attempt in range(cap * 20):
                    background = frames[int(rng.integers(len(frames)))]
                    result = compose(background, template, class_id, label_map, rng)
                    if result is None:
                        continue
                    image, box = result
                    # Cùng mã và thứ tự tạo cho cùng seed, độc lập với thư mục đích.
                    path = PurePosixPath("images", f"syn-{class_id:02d}-{attempt:05d}.png")
                    image.save(staged / path, compress_level=1)
                    original = background.label.read_bytes() if background.label else b""
                    newline = b"\n" if original and not original.endswith(b"\n") else b""
                    (staged / label_path(path)).write_bytes(
                        original + newline + (box.yolo() + "\n").encode("utf-8")
                    )
                    rows.append(
                        {
                            "path": str(path),
                            "source": "synthetic",
                            "sequence": background.sequence,
                            "split": "train",
                            "tags": SYNTHETIC_BOXES_TAG + "1",
                        }
                    )
                    generated += 1
                    if generated == cap:
                        break
            report[code] = {
                "real": real,
                "cap": cap,
                "synthetic": generated,
                "has_template": code in catalog,
            }
        if not rows:
            raise ValueError("không sinh được ảnh nào từ bộ hình vẽ và nền đã chọn")
        write_csv(staged / "intake.csv", INTAKE_FIELDS, rows)
        write_json(
            staged / "generation.json", {"seed": seed, "threshold": threshold, "classes": report}
        )
        staged.rename(output)
    print(f"Ảnh tổng hợp: {len(rows)}")
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
            "ATTRIBUTION.md",
            "source-audit.json",
            "vn82-box-fixes.csv",
            "vn82-to-signa.csv",
        )
        for name in provenance:
            if (base / name).is_file():
                shutil.copyfile(base / name, bundle / name)
        shutil.copyfile(template_csv, bundle / "templates.csv")
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
