"""Đọc và kiểm tra danh mục nhãn `ai/data/labels.yaml` (AI-01, docs/05 §2).

`labels.yaml` là nguồn duy nhất của danh mục. Mọi nơi cần ánh xạ mã biển ↔ chỉ số lớp
(huấn luyện, bộ đánh giá, dịch vụ nhận diện, xuất `labels.json` cho ứng dụng) dùng `LabelMap`.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

CATEGORIES = ("prohibitory", "mandatory", "warning", "auxiliary", "other")

DEFAULT_PATH = Path(__file__).resolve().parents[2] / "data" / "labels.yaml"


class LabelMapError(ValueError):
    """Danh mục nhãn không hợp lệ."""


@dataclass(frozen=True)
class SignClass:
    id: int
    code: str
    category: str
    name: str
    speed_kmh: int | None = None


@dataclass(frozen=True)
class LabelMap:
    version: int
    categories: Mapping[str, str]
    classes: tuple[SignClass, ...]
    _by_code: Mapping[str, SignClass] = field(repr=False, compare=False)
    _by_id: Mapping[int, SignClass] = field(repr=False, compare=False)

    def by_code(self, code: str) -> SignClass:
        try:
            return self._by_code[code]
        except KeyError:
            raise KeyError(f"mã biển không có trong danh mục v{self.version}: {code}") from None

    def by_id(self, class_id: int) -> SignClass:
        try:
            return self._by_id[class_id]
        except KeyError:
            raise KeyError(f"id lớp không có trong danh mục v{self.version}: {class_id}") from None

    @property
    def names(self) -> dict[int, str]:
        """`{id: mã biển}` theo thứ tự `id`, dùng làm `names` khi huấn luyện."""
        return {c.id: c.code for c in sorted(self.classes, key=lambda c: c.id)}

    def __len__(self) -> int:
        return len(self.classes)


def parse_label_map(raw: Any) -> LabelMap:
    """Kiểm tra cấu trúc và ràng buộc của danh mục (UT-A06)."""
    if not isinstance(raw, Mapping):
        raise LabelMapError("labels.yaml phải là một mapping")

    version = raw.get("version")
    if not isinstance(version, int) or isinstance(version, bool) or version < 0:
        raise LabelMapError("`version` phải là số nguyên không âm")

    raw_categories = raw.get("categories")
    if not isinstance(raw_categories, Mapping) or not raw_categories:
        raise LabelMapError("`categories` phải là mapping không rỗng")
    unknown = set(raw_categories) - set(CATEGORIES)
    if unknown:
        raise LabelMapError(f"nhóm không hợp lệ: {sorted(unknown)}; chỉ dùng {list(CATEGORIES)}")
    categories = {key: str(value["name"]) for key, value in raw_categories.items()}

    raw_classes = raw.get("classes")
    if not isinstance(raw_classes, list) or not raw_classes:
        raise LabelMapError("`classes` phải là danh sách không rỗng")

    classes: list[SignClass] = []
    by_code: dict[str, SignClass] = {}
    by_id: dict[int, SignClass] = {}
    for index, item in enumerate(raw_classes):
        where = f"classes[{index}]"
        if not isinstance(item, Mapping):
            raise LabelMapError(f"{where} phải là mapping")
        missing = {"id", "code", "category", "name"} - set(item)
        if missing:
            raise LabelMapError(f"{where} thiếu trường {sorted(missing)}")

        class_id = item["id"]
        if not isinstance(class_id, int) or isinstance(class_id, bool) or class_id < 0:
            raise LabelMapError(f"{where}: `id` phải là số nguyên không âm")
        code = str(item["code"]).strip()
        if not code:
            raise LabelMapError(f"{where}: `code` rỗng")
        category = item["category"]
        if category not in categories:
            raise LabelMapError(f"{where} ({code}): nhóm `{category}` không khai báo")
        speed = item.get("speed_kmh")
        if speed is not None and (not isinstance(speed, int) or speed <= 0):
            raise LabelMapError(f"{where} ({code}): `speed_kmh` phải là số nguyên dương")

        if class_id in by_id:
            raise LabelMapError(f"id {class_id} bị trùng: {by_id[class_id].code} và {code}")
        if code in by_code:
            raise LabelMapError(f"mã {code} bị trùng")

        sign = SignClass(class_id, code, category, str(item["name"]), speed)
        classes.append(sign)
        by_id[class_id] = sign
        by_code[code] = sign

    return LabelMap(version, categories, tuple(classes), by_code, by_id)


def load_label_map(path: str | Path = DEFAULT_PATH) -> LabelMap:
    with Path(path).open(encoding="utf-8") as handle:
        return parse_label_map(yaml.safe_load(handle))
