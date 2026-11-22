"""Bổ sung SHA-256 cho thẻ bàn giao đã xác minh nguồn."""

import argparse
import hashlib
import json
from pathlib import Path
from stat import S_IMODE


def add_card_sha(weights: Path) -> str:
    weights = weights.resolve()
    if weights.suffix != ".pt" or not weights.is_file():
        raise ValueError("Cần checkpoint .pt cục bộ")
    with weights.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()

    card_path = weights.with_name("model_card.json")
    draft = card_path.with_suffix(".json.tmp")
    # Chỉ một lần di trú được sửa thẻ tại một thời điểm.
    output = draft.open("x", encoding="utf-8")
    try:
        with output:
            card = json.loads(card_path.read_text(encoding="utf-8"))
            if not isinstance(card, dict):
                raise ValueError("model_card.json phải chứa một đối tượng JSON")
            existing = card.get("weightsSha256")
            if existing is not None and existing != digest:
                raise ValueError("weightsSha256 trong thẻ không khớp SHA-256 của checkpoint")
            if existing is None:
                card["weightsSha256"] = digest
                json.dump(card, output, ensure_ascii=False, indent=2)
                output.write("\n")
        if existing == digest:
            draft.unlink()
        else:
            draft.chmod(S_IMODE(card_path.stat().st_mode))
            draft.replace(card_path)
    except BaseException:
        draft.unlink(missing_ok=True)
        raise
    return digest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("weights", type=Path, help="checkpoint .pt đã xác minh nguồn")
    args = parser.parse_args()
    try:
        digest = add_card_sha(args.weights)
    except (OSError, ValueError) as exc:
        parser.exit(1, f"Lỗi di trú thẻ: {exc}\n")
    print(f"weightsSha256: {digest}")


if __name__ == "__main__":
    main()
