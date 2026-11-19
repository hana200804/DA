"""Đọc multipart và giải mã ảnh trong bộ nhớ (ADR-0006, NFR-09)."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import BinaryIO

import numpy as np
from fastapi import HTTPException, Request
from PIL import Image
from python_multipart.multipart import parse_options_header
from starlette.datastructures import FormData
from starlette.formparsers import MultiPartException, MultiPartParser
from starlette.requests import ClientDisconnect

MAX_IMAGE_BYTES = 10_000_000
MAX_REQUEST_BYTES = MAX_IMAGE_BYTES + 64 * 1024
MAX_IMAGE_PIXELS = 25_000_000


class InMemoryMultipart(MultiPartParser):
    # Luồng đầu vào bị giới hạn trước khi parser ghi, nên spool không thể chuyển xuống đĩa.
    spool_max_size = MAX_REQUEST_BYTES
    complete = False

    def on_end(self) -> None:
        self.complete = True


async def bounded_stream(request: Request) -> AsyncIterator[bytes]:
    received = 0
    async for chunk in request.stream():
        received += len(chunk)
        if received > MAX_REQUEST_BYTES:
            raise HTTPException(413, "Ảnh nhận diện tối đa 10 MB")
        yield chunk


@asynccontextmanager
async def image_form(request: Request) -> AsyncIterator[FormData]:
    content_type, _ = parse_options_header(request.headers.get("content-type"))
    if content_type != b"multipart/form-data":
        raise HTTPException(415, "Yêu cầu phải dùng multipart/form-data")

    parser = InMemoryMultipart(
        request.headers, bounded_stream(request), max_files=1, max_fields=3, max_part_size=1024
    )
    try:
        form = await parser.parse()
    except (MultiPartException, ClientDisconnect) as exc:
        raise HTTPException(400, "Dữ liệu multipart không hợp lệ") from exc

    try:
        if not parser.complete or len(form) != len(form.multi_items()):
            raise HTTPException(400, "Dữ liệu multipart chưa hoàn chỉnh hoặc có trường trùng")
        yield form
    finally:
        await form.close()


def decode_image(file: BinaryIO) -> np.ndarray:
    try:
        with Image.open(file, formats=("JPEG", "PNG", "WEBP")) as image:
            if image.width * image.height > MAX_IMAGE_PIXELS:
                raise HTTPException(413, "Ảnh nhận diện tối đa 25 triệu pixel")
            return np.array(image.convert("RGB"))
    except Image.DecompressionBombError as exc:
        raise HTTPException(413, "Ảnh nhận diện tối đa 25 triệu pixel") from exc
    except (OSError, ValueError, SyntaxError) as exc:
        raise HTTPException(415, "Ảnh phải là JPEG, PNG hoặc WebP hợp lệ") from exc
