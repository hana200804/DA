import copy

import pytest

from signa_ai.labels import LabelMapError, load_label_map, parse_label_map

VALID = {
    "version": 1,
    "categories": {"prohibitory": {"name": "Biển cấm"}, "auxiliary": {"name": "Biển phụ"}},
    "classes": [
        {"id": 0, "code": "P.102", "category": "prohibitory", "name": "Cấm đi ngược chiều"},
        {
            "id": 1,
            "code": "P.127_50",
            "category": "prohibitory",
            "name": "Tốc độ tối đa 50 km/h",
            "speed_kmh": 50,
        },
        {"id": 2, "code": "S", "category": "auxiliary", "name": "Biển phụ"},
    ],
}


def variant(**changes):
    raw = copy.deepcopy(VALID)
    for index, patch in changes.get("classes", {}).items():
        raw["classes"][index].update(patch)
    return raw


def test_repo_label_map_is_valid():
    label_map = load_label_map()
    assert len(label_map) > 0
    assert label_map.by_code("P.123a").category == "prohibitory"


def test_lookup_by_code_and_id():
    label_map = parse_label_map(VALID)
    assert label_map.by_code("P.127_50").speed_kmh == 50
    assert label_map.by_id(2).code == "S"
    assert label_map.names == {0: "P.102", 1: "P.127_50", 2: "S"}


# UT-A06
def test_duplicate_id_is_rejected():
    with pytest.raises(LabelMapError, match="id 0 bị trùng"):
        parse_label_map(variant(classes={1: {"id": 0}}))


# UT-A06
def test_duplicate_code_is_rejected():
    with pytest.raises(LabelMapError, match="mã P.102 bị trùng"):
        parse_label_map(variant(classes={1: {"code": "P.102"}}))


def test_undeclared_category_is_rejected():
    with pytest.raises(LabelMapError, match="không khai báo"):
        parse_label_map(variant(classes={0: {"category": "warning"}}))


def test_unknown_lookup_raises_key_error():
    with pytest.raises(KeyError):
        parse_label_map(VALID).by_code("P.999")
