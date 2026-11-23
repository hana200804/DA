"""Tải PNG từ SVG biển báo Việt Nam trên Wikimedia Commons và ghi nguồn."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import sys
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen

from PIL import Image

from signa_ai.synthetic import (
    BASE,
    TEMPLATE_CSV,
    TEMPLATES,
    THRESHOLD,
    rare_classes,
    write_csv,
)

API = "https://commons.wikimedia.org/w/api.php"
USER_AGENT = "signa-dataset-tool/0.1"
FIELDS = ("code", "commons_file", "url", "license", "author", "sha256")
CREATIVE_COMMONS = re.compile(r"CC(?:0(?: 1\.0)?| BY(?:-SA)? [1-4]\.[05])", re.IGNORECASE)


class PlainText(HTMLParser):
    def __init__(self, html: str) -> None:
        super().__init__()
        self.parts: list[str] = []
        self.feed(html)

    def handle_data(self, data: str) -> None:
        self.parts.append(data)


def download(url: str) -> bytes:
    request = Request(url, headers={"User-Agent": USER_AGENT})
    with urlopen(request, timeout=30) as response:
        return response.read()


def candidates(code: str) -> list[str]:
    # P.124a có hai hình chuẩn riêng. Chọn biến thể cấm quay đầu sang trái.
    stem = "P124a1" if code == "P.124a" else code.replace(".", "")
    names = (f"{code} (QCVN 41-2024-BGTVT)", stem, code)
    return [f"File:Vietnam road sign {name}.svg" for name in names]


def imageinfo(titles: list[str]) -> dict[str, dict]:
    parameters = {
        "action": "query",
        "format": "json",
        "formatversion": 2,
        "prop": "imageinfo|categories",
        "iiprop": "url|mime|extmetadata",
        "iiurlwidth": 512,
        "clshow": "hidden",
        "cllimit": "max",
        "titles": "|".join(titles),
    }
    response = json.loads(download(f"{API}?{urlencode(parameters)}"))
    if "error" in response or "continue" in response:
        raise ValueError(f"Commons chưa trả đủ thông tin giấy phép cho {titles}")
    return {page["title"]: page for page in response["query"]["pages"]}


def allowed_license(page: dict, meta: dict) -> str:
    # Commons có thể trả PD của tài liệu gốc trong extmetadata. Khi trang SVG
    # có thẻ CC được phép, ghi giấy phép CC của chính hình vẽ để ghi công.
    categories = []
    for category in page.get("categories", []):
        name = re.sub(r"^CC-(BY(?:-SA)?)-", r"CC \1 ", category["title"].removeprefix("Category:"))
        if CREATIVE_COMMONS.fullmatch(name):
            categories.append(name.upper())
    if categories:
        return sorted(categories)[-1]
    short = meta.get("LicenseShortName", {}).get("value", "").strip()
    if CREATIVE_COMMONS.fullmatch(short):
        return short.upper()
    if short == "Public domain" and meta.get("License", {}).get("value") == "pd":
        return "PD"
    raise ValueError(f"giấy phép không được chấp nhận: {short or 'thiếu metadata'}")


def read_source(page: dict) -> tuple[str, str, str]:
    if not page.get("imageinfo"):
        raise ValueError("thiếu tệp SVG")
    info = page["imageinfo"][0]
    thumb = info.get("thumburl", "")
    url = urlsplit(thumb)
    if (
        info.get("mime") != "image/svg+xml"
        or url.scheme != "https"
        or url.hostname not in {"upload.wikimedia.org", "thumb.wikimedia.org"}
        or not url.path.endswith(".png")
    ):
        raise ValueError("thiếu bản PNG được Commons render từ SVG")
    meta = info["extmetadata"]
    license_id = allowed_license(page, meta)
    artist = meta.get("Artist", {}).get("value", "")
    author = " ".join("".join(PlainText(artist).parts).split())
    if not author:
        raise ValueError("thiếu tên tác giả")
    return thumb, license_id, author


def refresh(base: Path, templates: Path, output: Path, threshold: int) -> None:
    rows = []
    templates.mkdir(parents=True, exist_ok=True)
    missing = []
    codes = rare_classes(base, threshold)
    titles = {code: candidates(code) for code in codes}
    # Mỗi truy vấn chỉ có tối đa 30 tên. Metadata giấy phép luôn đi cùng PNG.
    all_titles = list(dict.fromkeys(title for names in titles.values() for title in names))
    pages = {}
    for index in range(0, len(all_titles), 30):
        pages.update(imageinfo(all_titles[index : index + 30]))
    for code, names in titles.items():
        for name in dict.fromkeys(names):
            try:
                thumb, license_id, author = read_source(pages.get(name, {}))
            except ValueError as error:
                print(f"{code}: bỏ {name}: {error}", file=sys.stderr)
                continue
            png = download(thumb)
            with Image.open(io.BytesIO(png)) as image:
                if image.format != "PNG":
                    raise ValueError(f"Commons không trả PNG: {name}")
                image.verify()
            (templates / f"{code}.png").write_bytes(png)
            rows.append(
                {
                    "code": code,
                    "commons_file": name,
                    "url": thumb,
                    "license": license_id,
                    "author": author,
                    "sha256": hashlib.sha256(png).hexdigest(),
                }
            )
            break
        else:
            missing.append(code)
    output.parent.mkdir(parents=True, exist_ok=True)
    write_csv(output, FIELDS, rows)
    print(f"Đã ghi {len(rows)} hình vẽ vào {output}")
    print(f"Mã thiếu hình vẽ: {', '.join(missing) or 'không có'}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", type=Path, default=BASE)
    parser.add_argument("--templates", type=Path, default=TEMPLATES)
    parser.add_argument("--output", type=Path, default=TEMPLATE_CSV)
    parser.add_argument("--threshold", type=int, default=THRESHOLD)
    args = parser.parse_args()
    try:
        refresh(args.base, args.templates, args.output, args.threshold)
    except (KeyError, OSError, ValueError) as error:
        parser.exit(2, f"{error}\n")


if __name__ == "__main__":
    main()
