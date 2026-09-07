import copy
import json
import zlib

import pytest

from tools.recipe_importer.ingredient_taxonomy import (
    INGREDIENT_GROUPS, canonical_ingredient_label, canonical_ingredient_labels,
    ingredient_token, normalize_ingredient_facets,
)
from tools.recipe_importer.full_schema import (
    firestore_recipe_payload, firestore_detail_payload, firestore_source_payload,
)
from tools.recipe_importer.build_local_catalog import normalize_search_token, prepare_recipe
from tools.recipe_importer.tests.test_build_local_catalog import _record


@pytest.mark.parametrize("labels, expected", [
    (["ΚΑΣΤΑΝΟ", "Κάστανα"], ["Κάστανο"]),
    (["Μελιτζάνα", "ΜΕΛΙΤΖΑΝΕΣ"], ["Μελιτζάνα"]),
    (["Κολοκυθάκια", "Κολοκύθι"], ["Κολοκύθι"]),
    (["BLUEBERRY", "Μύρτιλο"], ["Μύρτιλο"]),
    (["ΓΑΛΑ ΑΜΥΓΔΑΛΟΥ", "Γάλα Αμυγδάλου"], ["Γάλα αμυγδάλου"]),
])
def test_cross_provider_equivalences(labels, expected):
    assert canonical_ingredient_labels(labels) == expected


@pytest.mark.parametrize("labels", [
    ["Αλεύρι", "Αλεύρι (ζύμες)", "Αλεύρι αμυγδάλου"],
    ["Γάλα", "Γάλα αμυγδάλου", "Γάλα καρύδας"],
    ["Βατόμουρο", "Μύρτιλο", "Σμέουρα"],
    ["Κολοκύθα", "Κολοκύθι"],
    ["Μοσχάρι", "Μοσχαρίσιος κιμάς", "Κιμάς κοτόπουλου"],
    ["Καλαμάρι", "Καλαμάρι / θράψαλο"],
])
def test_distinct_foods_remain_distinct(labels):
    assert len(canonical_ingredient_labels(labels)) == len(labels)


def test_vocabulary_matches_catalog_token_contract_and_is_idempotent():
    for group in INGREDIENT_GROUPS:
        for alias in group:
            for value in (alias, alias.upper(), "  " + alias + "  "):
                assert ingredient_token(value) == normalize_search_token(value)
                assert canonical_ingredient_label(value) == group[0]
        assert canonical_ingredient_labels(group) == [group[0]]
    assert canonical_ingredient_label("  Νέο   υλικό ") == "Νέο υλικό"


@pytest.mark.parametrize("source", ["akis", "argiro", "gastronomos"])
def test_firestore_and_apk_receive_identical_normalized_fields_without_mutating_source(source):
    record = _record("123", source, title="Δοκιμή")
    record["ingredientLabels"] = ["ΚΑΣΤΑΝΟ", "Κάστανα", "BLUEBERRY"]
    record["tags"] = ["Κάστανα", "ΚΑΣΤΑΝΟ", "BLUEBERRY", "Χριστούγεννα"]
    original = copy.deepcopy(record)
    source_before = firestore_source_payload(record)
    summary = firestore_recipe_payload(record)
    detail = firestore_detail_payload(record)
    prepared, _ = prepare_recipe(record)
    bundled = json.loads(zlib.decompress(prepared.recipe_json))
    for payload in (summary, detail, bundled):
        assert payload["ingredientLabels"] == ["Κάστανο", "Μύρτιλο"]
        assert payload["tags"] == ["Κάστανο", "Μύρτιλο", "Χριστούγεννα"]
        assert payload["category"] == record["category"]
    assert detail["ingredientSections"] == original["ingredientSections"]
    assert bundled["ingredientSections"] == original["ingredientSections"]
    assert firestore_source_payload(record) == source_before
    assert record == original
    assert normalize_ingredient_facets(detail) == detail
