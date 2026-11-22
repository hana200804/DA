import hashlib

import pytest

from scripts import measure_cost


def test_measure_cost_counts_one_cpu_forward_and_checks_checkpoint(tmp_path, monkeypatch):
    torch = pytest.importorskip("torch")
    monkeypatch.setenv("CUDA_VISIBLE_DEVICES", "")
    model = torch.nn.Conv2d(3, 2, kernel_size=1, device="cpu")
    checkpoint = tmp_path / "best.pt"
    torch.save(model.state_dict(), checkpoint)
    digest = hashlib.sha256(checkpoint.read_bytes()).hexdigest()
    loads = []

    def load_model(family, path, repo):
        loads.append((family, path, repo))
        return model, {"torch": torch.__version__}

    monkeypatch.setattr(measure_cost, "load_model", load_model)
    with pytest.raises(ValueError, match="SHA-256 checkpoint"):
        measure_cost.measure_cost("detr", checkpoint, "a" * 64, None)
    assert loads == []

    threads = torch.get_num_threads()
    try:
        report = measure_cost.measure_cost("detr", checkpoint, digest, None)
    finally:
        torch.set_num_threads(threads)
    assert loads == [("detr", checkpoint, None)]
    assert report["parameters"] == 8
    assert report["flops"] == 2 * 3 * 2 * 640 * 640
    assert report["gflops"] == report["flops"] / 1e9
    assert report["size_bytes"] == checkpoint.stat().st_size
    assert report["size_mb"] == report["size_bytes"] / 1e6
    assert report["weights_sha256"] == digest
    assert report["device"] == "cpu"
    assert report["flop_counter"] == "torch.utils.flop_counter.FlopCounterMode"
