import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

from prepare_vn82_v1 import group_images  # noqa: E402


def thumbs_for(count, seed):
    values = np.random.default_rng(seed).normal(size=(count, 768)).astype(np.float32)
    return values / np.linalg.norm(values, axis=1, keepdims=True)


def test_near_duplicates_join_even_when_far_apart_in_numbering():
    rng = np.random.default_rng(0)
    base = rng.integers(0, 2, size=256, dtype=np.uint8)
    other = 1 - base
    copy = base.copy()
    copy[:10] ^= 1
    hashes = np.stack([base, other, copy])
    groups, _ = group_images(hashes, thumbs_for(3, 1), order=[0, 1, 2])
    assert groups[0] == groups[2]
    assert groups[0] != groups[1]


def test_mid_distance_pair_needs_high_thumbnail_correlation():
    rng = np.random.default_rng(2)
    base = rng.integers(0, 2, size=256, dtype=np.uint8)
    variant = base.copy()
    variant[:70] ^= 1
    hashes = np.stack([base, 1 - base, variant])
    thumbs = thumbs_for(3, 3)
    order = [0, 1, 2]
    groups, _ = group_images(hashes, thumbs, order)
    assert groups[0] != groups[2]

    thumbs[2] = thumbs[0]
    groups, _ = group_images(hashes, thumbs, order)
    assert groups[0] == groups[2]


def test_consecutive_frames_join_with_looser_threshold():
    rng = np.random.default_rng(4)
    base = rng.integers(0, 2, size=256, dtype=np.uint8)
    moved = base.copy()
    moved[:65] ^= 1
    hashes = np.stack([base, moved])
    thumbs = thumbs_for(2, 5)
    assert len(set(group_images(hashes, thumbs, order=[0, 1])[0])) == 1
    assert len(set(group_images(hashes[[0, 1]], thumbs, order=[0])[0])) == 2
