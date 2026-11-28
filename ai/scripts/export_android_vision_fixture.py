"""Sinh fixture ảnh, tensor và phát hiện Python cho kiểm thử Android."""

import argparse
import gzip
import hashlib
import json
from dataclasses import asdict
from pathlib import Path

import numpy as np
from ai_edge_litert.interpreter import Interpreter
from PIL import Image, ImageOps

from signa_ai.labels import load_label_map
from signa_ai.mobile import decode_tflite, letterbox

REPO = Path(__file__).resolve().parents[2]
CONFIG = REPO / "mobile/app/src/main/assets/models/model.json"
FIXTURES = REPO / "mobile/app/src/test/resources/vision"


def make_case(interpreter, source, output, labels, config, max_edge):
    image_name = f"{source.stem}.png"
    with Image.open(source) as original:
        image = ImageOps.exif_transpose(original).convert("RGB")
        image.thumbnail((max_edge, max_edge), Image.Resampling.LANCZOS)
        # Tạo PNG RGB mới để bỏ EXIF và profile màu của ảnh nguồn.
        image = Image.fromarray(np.asarray(image))
        image.save(output / image_name, optimize=True)
    padded, transform = letterbox(np.asarray(image), config["imgsz"])
    tensor = (padded.astype(np.float32) / np.float32(255))[None]
    input_index = interpreter.get_input_details()[0]["index"]
    output_index = interpreter.get_output_details()[0]["index"]
    interpreter.set_tensor(input_index, tensor)
    interpreter.invoke()
    raw = interpreter.get_tensor(output_index)

    def decode(values):
        return decode_tflite(
            values,
            transform,
            labels,
            model="yolov8",
            conf=config["threshold"],
            iou=config["nmsIou"],
            max_det=config["maxDet"],
        )

    detections = decode(raw)
    # Giữ nguyên giá trị và thứ tự anchor. Giữ cả điểm dưới ngưỡng để thử bước lọc.
    scores = raw[0, 4:].max(axis=0)
    compact = raw[:, :, scores >= config["threshold"] / 4]
    assert compact.shape[2] and decode(compact) == detections
    raw_name = f"{source.stem}.f32.gz"
    compact_bytes = compact.astype("<f4").tobytes()
    (output / raw_name).write_bytes(gzip.compress(compact_bytes, mtime=0))
    return {
        "image": image_name,
        "raw": raw_name,
        "anchors": compact.shape[2],
        "letterbox": asdict(transform),
        "inputSha256": hashlib.sha256(tensor.astype("<f4").tobytes()).hexdigest(),
        "detections": [
            {
                "code": det.sign_type_code,
                "confidence": det.confidence,
                "box": [
                    det.box.x, det.box.y, det.box.x + det.box.width, det.box.y + det.box.height
                ],
            }
            for det in detections
        ],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("images", type=Path, nargs="+")
    parser.add_argument("--config", type=Path, default=CONFIG)
    parser.add_argument("--output", type=Path, default=FIXTURES)
    parser.add_argument("--max-edge", type=int, default=511)
    args = parser.parse_args()
    names = {image.stem for image in args.images}
    if not 3 <= len(args.images) <= 5 or len(names) != len(args.images):
        parser.error("Chọn từ 3 đến 5 ảnh có tên riêng biệt.")

    config = json.loads(args.config.read_text(encoding="utf-8"))
    model_path = args.config.parent / config["file"]
    if hashlib.sha256(model_path.read_bytes()).hexdigest() != config["sha256"]:
        parser.error("SHA-256 mô hình không khớp model.json.")
    labels = load_label_map()
    if labels.version != config["labelMapVersion"]:
        parser.error("Phiên bản danh mục không khớp model.json.")
    interpreter = Interpreter(model_path=str(model_path), num_threads=2)
    interpreter.allocate_tensors()
    args.output.mkdir(parents=True, exist_ok=True)
    manifest = {
        "modelSha256": config["sha256"],
        "size": config["imgsz"],
        "threshold": config["threshold"],
        "nmsIou": config["nmsIou"],
        "maxDet": config["maxDet"],
        "labels": [
            {"id": label.id, "code": label.code, "name": label.name}
            for label in (labels.by_id(index) for index in labels.names)
        ],
        "cases": [
            make_case(interpreter, image, args.output, labels, config, args.max_edge)
            for image in args.images
        ],
    }
    (args.output / "fixture.json").write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )


if __name__ == "__main__":
    main()
