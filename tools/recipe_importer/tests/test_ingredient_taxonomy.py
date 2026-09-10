import copy
import json
import zlib

import pytest

from tools.recipe_importer.ingredient_taxonomy import (
    INGREDIENT_GROUPS, canonical_ingredient_label, canonical_ingredient_labels,
    ingredient_token, is_reviewed_ingredient, normalize_ingredient_facets,
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
    (["Τομάτες", "ΝΤΟΜΑΤΑΣ"], ["Ντομάτα"]),
    (["ΚΡΕΜΜΥΔΙΑ ΞΕΡΑ", "Ξερό κρεμμύδι"], ["Ξερό κρεμμύδι"]),
    (["Cottage cheese", "Τυρί cottage"], ["Τυρί κότατζ"]),
    (["ΖΑΧΑΡΗ ΚΡΥΣΤΑΛΛΙΚΗ", "Κρυσταλλική ζάχαρη"], ["Κρυσταλλική ζάχαρη"]),
    (["Αραβοσιτέλαιο", "Καλαμποκέλαιο"], ["Καλαμποκέλαιο"]),
    (["Μπίρα", "Μπύρα"], ["Μπύρα"]),
    (["ΜΠΕΪΚΙΝ ΣΟΔΑ (baking soda)", "Μπέικιν σόδα"], ["Μαγειρική σόδα"]),
    (["Ασπράδια", "Ασπράδι αυγού"], ["Ασπράδι αυγού"]),
    (["Κρόκοι αυγών", "Κρόκο αυγού"], ["Κρόκος αυγού"]),
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
    ["Γάλα", "Γάλα εβαπορέ", "Ζαχαρούχο γάλα", "Γάλα σόγιας"],
    ["Αλεύρι", "Αλεύρι βρώμης", "Αλεύρι ολικής άλεσης", "Αλεύρι αμυγδάλου"],
    ["Τυρί", "Τυρί κότατζ", "Τυρί κρέμα", "Μυζήθρα", "Κεφαλοτύρι"],
    ["Αυγό", "Ασπράδι αυγού", "Κρόκος αυγού", "Σαφράν"],
    ["Μπέικιν πάουντερ", "Μαγειρική σόδα"],
    ["Ζάχαρη", "Κρυσταλλική ζάχαρη", "Ζάχαρη καρύδας"],
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


@pytest.mark.parametrize("source", ["akis", "argiro", "gastronomos", "tsoulis", "cookpad", "lucacos", "funkycook"])
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


@pytest.mark.parametrize("fragment", [
    "κόκκινο", "μοσχαρίσιο", "κρέμα", "μπέικιν", "κρόκο", "κρόκος",
    "σόγια", "γάλακτος", "ολικής", "κότας", "κονσέρβα", "Βιτάμ", "Philadelphia",
])
def test_ambiguous_source_fragments_are_not_reviewed_food_identities(fragment):
    assert not is_reviewed_ingredient(fragment)


@pytest.mark.parametrize("recipe_id, raw_line, facet, canonical", [
    ("12203377", "Έναν κρόκο αυγού", "Κρόκο αυγού", "Κρόκος αυγού"),
    ("24981111", "3 στήμονες κρόκο Κοζάνης", "Κρόκος Κοζάνης", "Κρόκος Κοζάνης"),
])
def test_observed_cookpad_yolk_and_saffron_lines_preserve_the_original_words(
    recipe_id, raw_line, facet, canonical,
):
    # These distinct published ingredient lines share the ambiguous search fragment κρόκο.
    record = _record(recipe_id, "cookpad", title="Δοκιμή ακριβούς ταυτότητας υλικού")
    record["ingredientSections"] = [{"title": "Υλικά", "ingredients": [{"title": raw_line}]}]
    record["ingredientLabels"] = [facet]
    record["sourcePayload"] = {"ingredientLines": [raw_line], "ingredientSearchKeywords": ["κρόκο"]}
    original = copy.deepcopy(record)
    detail = firestore_detail_payload(record)
    prepared, _ = prepare_recipe(record)
    bundled = json.loads(zlib.decompress(prepared.recipe_json))
    assert detail["ingredientLabels"] == [canonical]
    assert bundled["ingredientLabels"] == [canonical]
    assert detail["ingredientSections"] == original["ingredientSections"]
    assert bundled["ingredientSections"] == original["ingredientSections"]
    assert firestore_source_payload(record) == firestore_source_payload(original)
    assert record == original
