"""Đối chiếu cấu hình và lưu trạng thái dừng sớm cho notebook YOLO."""

import hashlib
import json
from importlib.metadata import distributions
from math import isfinite

import yaml


def write_run_file(path, content):
    pending = path.with_suffix(path.suffix + ".tmp")
    pending.write_text(content, encoding="utf-8")
    pending.replace(path)


def file_sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def freeze_requirements(run_dir):
    packages = {(d.metadata["Name"], d.version) for d in distributions()}
    lines = sorted(f"{name}=={version}" for name, version in packages if name)
    contents = "\n".join(lines) + "\n"
    digest = hashlib.sha256(contents.encode()).hexdigest()
    filename = f"requirements-{digest}.txt"
    write_run_file(run_dir / filename, contents)
    write_run_file(run_dir / "requirements.txt", contents)
    return filename


def check_resume_config(run_dir, current, checkpoint_args, batch_key, allow_batch_change):
    path = run_dir / "config.yaml"
    recorded = yaml.safe_load(path.read_text()) if path.is_file() else {}
    saved = {**recorded, **checkpoint_args}
    mismatches = [
        f"{key}: đã lưu {saved.get(key)!r}, hiện tại {value!r}"
        for key, value in current.items()
        if key != "batch_note" and saved.get(key) != value
        and not (key == batch_key and key in saved and allow_batch_change)
    ]
    if mismatches:
        raise ValueError("Cấu hình resume khác lượt đang chạy:\n" + "\n".join(mismatches))
    previous_batch = saved[batch_key]
    batch = current[batch_key] if allow_batch_change else previous_batch
    return batch, previous_batch


def save_stop_state(run_dir, stopper, checkpoint_epoch, *, epoch_offset=0):
    path = run_dir / "early-stopping.json"
    states = json.loads(path.read_text()) if path.is_file() else {}
    previous = str(checkpoint_epoch - 1)
    # Giữ trạng thái của last.pt cũ cho đến khi bộ công cụ lưu checkpoint mới.
    states = {previous: states[previous]} if previous in states else {}
    fitness = stopper.best_fitness
    if hasattr(fitness, "item"):
        fitness = fitness.item()  # YOLOv5 trả về một mảng NumPy có một phần tử.
    states[str(checkpoint_epoch)] = {
        "best_fitness": float(fitness),
        "best_epoch": stopper.best_epoch,
        "epochs_without_improvement": checkpoint_epoch + epoch_offset - stopper.best_epoch,
    }
    write_run_file(path, json.dumps(states, indent=2, allow_nan=False))


def load_stop_state(run_dir, checkpoint_epoch, *, epoch_offset=0):
    path = run_dir / "early-stopping.json"
    states = json.loads(path.read_text()) if path.is_file() else {}
    state = states.get(str(checkpoint_epoch))
    if (
        state is None
        or not isfinite(state["best_fitness"])
        or not 0 <= state["best_epoch"] <= checkpoint_epoch + epoch_offset
        or state["epochs_without_improvement"]
        != checkpoint_epoch + epoch_offset - state["best_epoch"]
    ):
        raise ValueError(
            "Thiếu trạng thái dừng sớm khớp last.pt. Dùng cặp tệp cùng epoch hoặc tạo lượt mới."
        )
    return state


def restore_stop_state(stopper, state):
    stopper.best_fitness = state["best_fitness"]
    stopper.best_epoch = state["best_epoch"]
    stopper.possible_stop = state["epochs_without_improvement"] >= stopper.patience - 1
