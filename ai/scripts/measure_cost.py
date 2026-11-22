"""Đo số tham số, FLOPs và kích thước của checkpoint đã đối chiếu SHA-256."""

import argparse
import hashlib
import json
import os
import platform
import re
import subprocess
import sys
from importlib.metadata import version
from pathlib import Path
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from torch import nn

REPO = Path(__file__).resolve().parents[2]
INPUT_SHAPE = (1, 3, 640, 640)
FLOP_COUNTER = "torch.utils.flop_counter.FlopCounterMode"


def git_commit(repo: Path) -> str:
    return subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip()


def load_model(family: str, checkpoint: Path, yolov5_repo: Path | None) -> tuple["nn.Module", dict]:
    versions = {
        "python": platform.python_version(),
        "torch": version("torch"),
        "torchvision": version("torchvision"),
    }
    if family == "detr":
        from signa_ai.detr import load_detr_checkpoint
        from signa_ai.labels import load_label_map

        versions.update(transformers=version("transformers"), timm=version("timm"))
        return load_detr_checkpoint(checkpoint, load_label_map(), device="cpu"), versions

    versions.update(ultralytics=version("ultralytics"))
    versions["ultralytics-thop"] = version("ultralytics-thop")
    if family == "yolov5":
        if yolov5_repo is None or not (yolov5_repo / "models" / "experimental.py").is_file():
            raise ValueError("Cần --yolov5-repo trỏ tới repo YOLOv5 gốc")
        versions["yolov5_commit"] = git_commit(yolov5_repo)
        sys.path.insert(0, str(yolov5_repo.resolve()))
        from models.experimental import attempt_load

        return attempt_load(str(checkpoint), device="cpu", fuse=False), versions

    from ultralytics import YOLO

    return YOLO(str(checkpoint)).model, versions


def measure_forward(model: "nn.Module") -> tuple[int, int]:
    """Đếm toàn bộ tham số và FLOPs của một lượt forward FP32 trên CPU."""
    import torch
    from torch.utils.flop_counter import FlopCounterMode

    model.to(device="cpu", dtype=torch.float32).eval()
    parameters = sum(parameter.numel() for parameter in model.parameters())
    image = torch.zeros(INPUT_SHAPE, device="cpu", dtype=torch.float32)
    with torch.no_grad(), FlopCounterMode(display=False) as counter:
        model(image)
    flops = counter.get_total_flops()
    if flops <= 0:
        raise ValueError("FlopCounterMode không đếm được phép toán nào")
    return parameters, flops


def measure_cost(
    family: str, checkpoint: Path, expected_sha256: str, yolov5_repo: Path | None
) -> dict:
    import torch

    with checkpoint.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
        size = os.fstat(stream.fileno()).st_size
    if digest != expected_sha256:
        raise ValueError(f"SHA-256 checkpoint không khớp SHA đã chốt: {digest}")

    torch.set_num_threads(2)
    model, versions = load_model(family, checkpoint, yolov5_repo)
    parameters, flops = measure_forward(model)
    report = {
        "model_family": family,
        "weights_sha256": digest,
        "size_bytes": size,
        "size_mb": size / 1_000_000,
        "parameters": parameters,
        "flops": flops,
        "gflops": flops / 1_000_000_000,
        "flop_counter": FLOP_COUNTER,
        "input_shape": list(INPUT_SHAPE),
        "dtype": "float32",
        "device": "cpu",
        "versions": versions,
        "repo_commit": git_commit(REPO),
    }
    if family.startswith("yolo"):
        from ultralytics.utils.torch_utils import get_flops

        with torch.no_grad():
            reference = get_flops(model, imgsz=INPUT_SHAPE[-1])
        if reference <= 0:
            raise ValueError("Ultralytics không đo được GFLOPs tham khảo")
        report["ultralytics_gflops_reference"] = reference
    return report


def main() -> None:
    os.environ["CUDA_VISIBLE_DEVICES"] = ""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkpoint", type=Path, help="Tệp best.pt cục bộ")
    parser.add_argument("--family", required=True, choices=("yolov5", "yolov8", "detr"))
    parser.add_argument("--expected-sha256", required=True, help="SHA-256 trọng số đã chốt")
    parser.add_argument("--yolov5-repo", type=Path, help="Repo YOLOv5 gốc đúng commit huấn luyện")
    parser.add_argument("--out", required=True, type=Path, help="Tệp cost.json đầu ra")
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-f]{64}", args.expected_sha256):
        parser.error("--expected-sha256 cần 64 ký tự hex chữ thường")
    try:
        if args.out.resolve() == args.checkpoint.resolve() or (
            args.out.exists() and args.out.samefile(args.checkpoint)
        ):
            parser.error("Tệp đầu ra phải khác checkpoint")
        report = measure_cost(
            args.family, args.checkpoint.resolve(), args.expected_sha256, args.yolov5_repo
        )
        args.out.write_text(
            json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + "\n",
            encoding="utf-8",
        )
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        parser.exit(2, f"Đo chi phí thất bại: {error}\n")


if __name__ == "__main__":
    main()
