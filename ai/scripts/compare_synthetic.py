"""So lượt gốc với lượt v1.1-syn trên val (AI-07, docs/05 §7).

Đọc hai `metrics.json` do `signa_ai.evaluate` chấm trên cùng GT val và xuất bảng Markdown
về AP@0.5:0.95, recall của các lớp hiếm cùng mAP toàn bộ.
"""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path

from signa_ai.labels import DEFAULT_PATH, load_label_map

ROOT = Path(__file__).resolve().parents[1]
TEMPLATES = ROOT / "data/synthetic/templates.csv"
EXPECTED_VERSIONS = {"baseline": "v1.1", "synthetic": "v1.1-syn"}
NOTES = (
    "Mỗi cấu hình chỉ có một lượt huấn luyện với seed 0, nên chưa đo được độ dao động "
    "giữa các seed.",
    "Val có ít box cho mỗi lớp hiếm, lớp ít nhất có {smallest} box, nên chênh lệch nhỏ có thể "
    "là nhiễu.",
    "v1.1-syn chép thêm 422 box thật từ ảnh nền, ngoài 618 box dán. Một phần chênh lệch có thể "
    "do lặp lại nhãn thật. Số box chép theo lớp nằm ở mục \"Bộ dữ liệu tổng hợp v1.1-syn\" "
    "của `ai/data/README.md`.",
    "So sánh dùng val. Không dùng test để quyết định có dùng dữ liệu tổng hợp hay không.",
)


def read_rare_classes(path: Path) -> list[str]:
    with path.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        if "code" not in (reader.fieldnames or []):
            raise ValueError(f"{path} thiếu cột code")
        return [row["code"].strip() for row in reader]


def load_metrics(path: Path) -> dict:
    metrics = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(metrics, dict):
        raise ValueError(f"{path} phải chứa một đối tượng JSON")
    for key in (
        "label_map_version", "map_50", "map_50_95", "images", "ground_truth_boxes",
        "operating_point", "per_class",
    ):
        if key not in metrics:
            raise ValueError(f"{path} thiếu trường {key} của signa_ai.evaluate")
    return metrics


def scoring_mode(metrics: dict) -> str | float:
    """`max_f1` khi ngưỡng chọn theo F1 cao nhất, ngược lại là ngưỡng khóa."""
    if metrics.get("max_f1") is not None:
        return "max_f1"
    return metrics["operating_point"]["threshold"]


def check_comparable(baseline: dict, synthetic: dict, label_version: int) -> None:
    versions = (baseline["label_map_version"], synthetic["label_map_version"])
    if versions[0] != versions[1]:
        raise ValueError(f"label_map_version khác nhau: {versions[0]} và {versions[1]}")
    if versions[0] != label_version:
        raise ValueError(f"label_map_version {versions[0]} khác labels.yaml v{label_version}")
    for role, metrics in (("baseline", baseline), ("synthetic", synthetic)):
        dataset = metrics.get("dataset_version")
        if dataset is not None and dataset != EXPECTED_VERSIONS[role]:
            raise ValueError(
                f"Lượt {role} có dataset_version {dataset}, cần {EXPECTED_VERSIONS[role]}"
            )
    for key in ("images", "ground_truth_boxes"):
        if baseline[key] != synthetic[key]:
            raise ValueError(
                f"Hai lượt không chấm cùng val: {key} {baseline[key]} ≠ {synthetic[key]}"
            )
    if baseline["per_class"].keys() != synthetic["per_class"].keys():
        raise ValueError("Hai lượt có danh sách lớp khác nhau")
    for code, row in baseline["per_class"].items():
        if row["support"] != synthetic["per_class"][code]["support"]:
            raise ValueError(f"Hai lượt không chấm cùng val: số box GT của {code} khác nhau")
    modes = scoring_mode(baseline), scoring_mode(synthetic)
    if modes[0] != modes[1]:
        raise ValueError(
            "Hai lượt phải cùng chế độ chấm: cùng chọn ngưỡng F1 cao nhất hoặc cùng ngưỡng khóa "
            f"(gốc {modes[0]}, v1.1-syn {modes[1]})"
        )


def decimal(value: float, digits: int) -> str:
    return f"{value:.{digits}f}".replace(".", ",")


def percent(value: float | None) -> str:
    return "—" if value is None else f"{decimal(value * 100, 2)}%"


def delta(before: float | None, after: float | None) -> str:
    if before is None or after is None:
        return "—"
    points = round((after - before) * 100, 2)
    sign = "+" if points > 0 else "−" if points < 0 else "±"
    return f"{sign}{decimal(abs(points), 2)}"


def count(value: int) -> str:
    return f"{value:,}".replace(",", ".")


def mean(values: list[float | None]) -> float | None:
    present = [value for value in values if value is not None]
    return sum(present) / len(present) if present else None


def compare(
    baseline: dict,
    synthetic: dict,
    rare: list[str],
    label_version: int,
    known_codes: set[str],
    *,
    names: tuple[str, str] = ("metrics gốc", "metrics v1.1-syn"),
) -> str:
    check_comparable(baseline, synthetic, label_version)
    if not rare or len(set(rare)) != len(rare):
        raise ValueError("Danh sách lớp hiếm phải khác rỗng và không trùng")
    unknown = [code for code in rare if code not in known_codes]
    if unknown:
        raise ValueError(f"Lớp hiếm không có trong labels.yaml v{label_version}: {unknown}")
    missing = [code for code in rare if code not in baseline["per_class"]]
    if missing:
        raise ValueError(f"metrics thiếu lớp hiếm: {missing}")

    mode = scoring_mode(baseline)
    if mode == "max_f1":
        point = (
            "ngưỡng F1 cao nhất trên val của từng lượt "
            f"(gốc {decimal(baseline['operating_point']['threshold'], 4)}, "
            f"v1.1-syn {decimal(synthetic['operating_point']['threshold'], 4)})"
        )
    else:
        point = f"ngưỡng khóa {decimal(mode, 4)} cho cả hai lượt"

    lines = [
        "# So sánh có và không có dữ liệu tổng hợp trên val",
        "",
        f"- Gốc: `{names[0]}`. Có dữ liệu tổng hợp: `{names[1]}`.",
        f"- Danh mục nhãn v{label_version}, {count(baseline['images'])} ảnh val, "
        f"{count(baseline['ground_truth_boxes'])} box GT.",
        f"- Recall tính tại {point}.",
        "- Δ tính bằng điểm phần trăm, bằng v1.1-syn trừ gốc.",
        "",
        "## Lớp hiếm",
        "",
        "| Mã lớp | Box GT val | AP@0.5:0.95 gốc | AP@0.5:0.95 v1.1-syn | Δ AP | "
        "Recall gốc | Recall v1.1-syn | Δ recall |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    columns = {"ap_50_95": ([], []), "recall": ([], [])}
    support = 0
    for code in rare:
        before, after = baseline["per_class"][code], synthetic["per_class"][code]
        support += before["support"]
        cells = []
        for key, (old, new) in columns.items():
            old.append(before[key])
            new.append(after[key])
            cells += [percent(before[key]), percent(after[key]), delta(before[key], after[key])]
        lines.append(f"| `{code}` | {count(before['support'])} | " + " | ".join(cells) + " |")
    cells = []
    for old, new in columns.values():
        cells += [percent(mean(old)), percent(mean(new)), delta(mean(old), mean(new))]
    lines.append(
        f"| **Trung bình {len(rare)} lớp hiếm** | {count(support)} | " + " | ".join(cells) + " |"
    )
    lines += [
        "",
        "Trung bình là trung bình cộng các lớp có giá trị, mỗi lớp cùng trọng số.",
        "",
        "## Toàn bộ val",
        "",
        "| Chỉ số | Gốc | v1.1-syn | Δ |",
        "| --- | ---: | ---: | ---: |",
    ]
    for key, label in (("map_50", "mAP@0.5"), ("map_50_95", "mAP@0.5:0.95")):
        lines.append(
            f"| {label} | {percent(baseline[key])} | {percent(synthetic[key])} | "
            f"{delta(baseline[key], synthetic[key])} |"
        )
    smallest = min(baseline["per_class"][code]["support"] for code in rare)
    lines += ["", "## Ghi chú", ""]
    lines += [f"- {note.format(smallest=count(smallest))}" for note in NOTES]
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path, help="metrics val của lượt v1.1")
    parser.add_argument("synthetic", type=Path, help="metrics val của lượt v1.1-syn")
    parser.add_argument("--labels", type=Path, default=DEFAULT_PATH)
    rare = parser.add_mutually_exclusive_group()
    rare.add_argument(
        "--templates", type=Path, default=TEMPLATES,
        help="CSV có cột code, mặc định data/synthetic/templates.csv",
    )
    rare.add_argument("--rare-classes", help="Mã lớp hiếm, phân tách bằng dấu phẩy")
    parser.add_argument("--out", type=Path, help="Ghi Markdown ra tệp thay vì stdout")
    args = parser.parse_args()
    try:
        labels = load_label_map(args.labels)
        rare_classes = (
            [code.strip() for code in args.rare_classes.split(",")]
            if args.rare_classes
            else read_rare_classes(args.templates)
        )
        report = compare(
            load_metrics(args.baseline),
            load_metrics(args.synthetic),
            rare_classes,
            labels.version,
            set(labels.names.values()),
            names=(str(args.baseline), str(args.synthetic)),
        )
    except (KeyError, OSError, TypeError, ValueError) as error:
        parser.exit(2, f"So sánh thất bại: {error}\n")
    if args.out:
        args.out.write_text(report, encoding="utf-8")
    else:
        print(report, end="")


if __name__ == "__main__":
    main()
