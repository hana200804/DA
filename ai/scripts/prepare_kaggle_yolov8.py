"""Tạo gói kernel Kaggle private từ bản notebook đã commit."""

import argparse
import json
import re
import subprocess
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
NOTEBOOK = Path("ai/notebooks/train_yolov8_main_v1.1.ipynb")
KERNEL = "yolov8n-640-v1-1-syn-main-s0"


def dataset_ref(value):
    if not re.fullmatch(r"[a-z0-9_-]+/[a-z0-9_-]+", value):
        raise argparse.ArgumentTypeError("Dùng slug dạng owner/dataset.")
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="Thư mục gửi bằng kaggle kernels push")
    parser.add_argument("dataset", type=dataset_ref, help="Slug dataset v1.1-syn private")
    parser.add_argument(
        "--resume-dataset", type=dataset_ref, help="Slug dataset checkpoint private"
    )
    parser.add_argument("--commit", default="HEAD", help="Commit có notebook, mặc định HEAD")
    args = parser.parse_args()

    git = ["git", "-C", str(REPO)]
    commit = subprocess.check_output(
        [*git, "rev-parse", "--verify", "--end-of-options", f"{args.commit}^{{commit}}"],
        text=True,
    ).strip()
    notebook = json.loads(subprocess.check_output([*git, "show", f"{commit}:{NOTEBOOK}"]))
    notebook["cells"].insert(0, {
        "cell_type": "code",
        "execution_count": None,
        "metadata": {},
        "outputs": [],
        "source": [
            "import os\n",
            f'os.environ["SIGNA_NOTEBOOK_COMMIT"] = {commit!r}\n',
            f'os.environ["SIGNA_KAGGLE_DATASET"] = {args.dataset!r}\n',
        ],
    })
    owner = args.dataset.split("/")[0]
    metadata = {
        "id": f"{owner}/{KERNEL}",
        "title": "YOLOv8n 640 v1-1 syn main s0",
        "code_file": NOTEBOOK.name,
        "language": "python",
        "kernel_type": "notebook",
        "is_private": True,
        "enable_gpu": True,
        "enable_internet": True,
        "dataset_sources": [args.dataset],
    }
    if args.resume_dataset:
        metadata["dataset_sources"].append(args.resume_dataset)

    args.output.mkdir(parents=True, exist_ok=True)
    for name, content in ((NOTEBOOK.name, notebook), ("kernel-metadata.json", metadata)):
        (args.output / name).write_text(json.dumps(content, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()
