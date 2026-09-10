import copy

import pytest
from tools.recipe_importer.facet_taxonomy import canonical_facet_labels, normalize_catalog_facets

@pytest.mark.parametrize("facet,values,expected", [
    ("diet", ["VEGETARIAN", "Χορτοφαγικά", "Χορτοφαγική"], ["Χορτοφαγική"]),
    ("occasion", ["ΧΡΙΣΤΟΥΓΕΝΝΑ", "Christmas", "Χριστουγεννιάτικη"], ["Χριστούγεννα"]),
    ("cuisine", ["ΑΜΕΡΙΚΑΝΙΚΗ, ΕΛΛΗΝΙΚΗ", "Greek", "Ελλάδα"], ["Αμερικάνικη", "Ελληνική"]),
    ("meal", ["Κυρίως γεύματα", "Main dish"], ["Κυρίως γεύμα"]),
    ("cuisine", ["Αυστραλέζικη", "Αυστραλία", "Αυστραλιανή"], ["Αυστραλιανή"]),
    ("cuisine", ["Μεξικανινη", "Μεξικό"], ["Μεξικάνικη"]),
    ("cuisine", ["Ανατολή", "Ανατολίτικη"], ["Ανατολίτικη"]),
    ("diet", ["vegatarian", "Vegetarian"], ["Χορτοφαγική"]),
    ("occasion", ["Καλοκαιρινές συνταγές", "Καλοκαίρι"], ["Καλοκαίρι"]),
    ("occasion", ["Χειμωνιάτικες συνταγές", "Χειμώνας"], ["Χειμώνας"]),
    ("occasion", ["Παιδικά", "Για παιδιά"], ["Για παιδιά"]),
    ("occasion", ["ΠΑΙΔΙΚΑ ΠΑΡΤΙ", "Παιδικό πάρτι"], ["Παιδικό πάρτι"]),
    ("meal", ["Παιδικά", "ΣΥΝΤΑΓΕΣ ΓΙΑ ΠΑΙΔΙΑ"], ["Για παιδιά"]),
    ("meal", ["ΟΡΕΚΤΙΚΑ", "Ορεκτικό"], ["Ορεκτικά"]),
])
def test_equivalent_publisher_facets_share_one_identity(facet, values, expected):
    assert canonical_facet_labels(facet, values) == expected


def test_restrictions_with_different_meanings_remain_distinct():
    labels = canonical_facet_labels("diet", ["Dairy Free", "LactoseFreeDiet", "Sugar Free", "Χαμηλή σε ζάχαρη"])
    assert set(labels) == {"Χωρίς γαλακτοκομικά", "Χωρίς λακτόζη", "Χωρίς ζάχαρη", "Χαμηλή σε ζάχαρη"}


def test_catalog_projection_preserves_source_evidence():
    payload = {"tags": ["Christmas"], "ingredient": "2 αυγά"}
    raw = {"sourcePayload": payload, "occasionLabels": ["Christmas"], "tags": ["Christmas"]}
    normalized = normalize_catalog_facets(raw)
    assert normalized["occasionLabels"] == ["Χριστούγεννα"]
    assert normalized["tags"] == ["Χριστούγεννα"]
    assert normalized["sourcePayload"] == payload
    assert raw["occasionLabels"] == ["Christmas"]


@pytest.mark.parametrize("facet, labels", [
    ("cuisine", ["Αγγλική", "Βρετανική"]),
    ("cuisine", ["Πολίτικη", "Μικρασιατική", "Σμυρνέικη Κουζίνα"]),
    ("diet", ["Vegan", "Vegetarian", "Νηστεία"]),
    ("occasion", ["Για παιδιά", "Παιδικό πάρτι", "Πάρτι"]),
    ("occasion", ["Νηστεία", "Σαρακοστή", "Πάσχα"]),
    ("occasion", ["Καλοκαίρι", "Χειμώνας", "Για όλο τον χρόνο"]),
    ("meal", ["Ροφήματα", "Ροφήματα & ποτά"]),
    ("meal", ["Ορεκτικά", "Ορεκτικό / Μεζές"]),
    ("meal", ["Παιδικά", "Βρεφικά"]),
])
def test_related_facets_are_not_treated_as_equivalent(facet, labels):
    assert len(canonical_facet_labels(facet, labels)) == len(labels)


def test_observed_misspellings_and_kids_labels_preserve_original_source_evidence():
    source_payload = {
        "jsonLd": {"recipeCuisine": "Μεξικανινη"},
        "htmlMetadata": {"categoryLabels": ["ΣΥΝΤΑΓΕΣ ΓΙΑ ΠΑΙΔΙΑ", "ΠΑΙΔΙΚΑ ΠΑΡΤΙ"]},
    }
    raw = {
        "sourcePayload": source_payload,
        "cuisineLabels": ["Μεξικανινη"],
        "mealTypeLabels": ["ΣΥΝΤΑΓΕΣ ΓΙΑ ΠΑΙΔΙΑ"],
        "occasionLabels": ["Παιδικά", "ΠΑΙΔΙΚΑ ΠΑΡΤΙ"],
        "tags": ["Παιδικά", "ΣΥΝΤΑΓΕΣ ΓΙΑ ΠΑΙΔΙΑ", "ΠΑΙΔΙΚΑ ΠΑΡΤΙ"],
    }
    normalized = normalize_catalog_facets(raw)
    assert normalized["cuisineLabels"] == ["Μεξικάνικη"]
    assert normalized["mealTypeLabels"] == ["Για παιδιά"]
    assert normalized["occasionLabels"] == ["Για παιδιά", "Παιδικό πάρτι"]
    assert normalized["tags"] == ["Για παιδιά", "Παιδικό πάρτι"]
    assert normalized["sourcePayload"] == source_payload
    assert raw["cuisineLabels"] == ["Μεξικανινη"]
    assert raw["occasionLabels"] == ["Παιδικά", "ΠΑΙΔΙΚΑ ΠΑΡΤΙ"]


@pytest.mark.parametrize("label, diet_labels, meal_labels", [
    ("DIY", [], []),
    ("Vegan", ["Vegan"], []),
    ("VEGAN", ["Vegan"], []),
    ("Street", [], ["Street food"]),
])
def test_exact_misplaced_cuisine_labels_move_to_the_correct_facet_without_losing_evidence(
    label, diet_labels, meal_labels,
):
    raw = {
        "sourcePayload": {"jsonLd": {"recipeCuisine": label}},
        "cuisineLabels": [label],
        "tags": ["Ετικέτα πηγής"],
        "ingredientSections": [{"title": "Υλικά", "ingredients": [{"title": "2 αυγά"}]}],
    }
    original = copy.deepcopy(raw)
    normalized = normalize_catalog_facets(raw)
    assert normalized["cuisineLabels"] == []  # Never invent a geographic cuisine.
    assert normalized["dietLabels"] == diet_labels
    assert normalized["mealTypeLabels"] == meal_labels
    assert normalized["tags"] == ["Ετικέτα πηγής", label]
    assert normalized["sourcePayload"] == original["sourcePayload"]
    assert normalized["ingredientSections"] == original["ingredientSections"]
    assert raw == original
    assert normalize_catalog_facets(normalized) == normalized


def test_cuisine_corrections_keep_real_cuisines_and_existing_facet_choices():
    raw = {
        "title": "Vegan street food DIY",
        "cuisineLabels": ["Αυστραλία, Vegan", "Street", "DIY", "Νέα τοπική κουζίνα"],
        "dietLabels": ["Vegan", "Χωρίς γλουτένη"],
        "mealTypeLabels": ["Street food", "Main dish"],
        "tags": ["DIY", "Street"],
    }
    normalized = normalize_catalog_facets(raw)
    assert normalized["cuisineLabels"] == ["Αυστραλιανή", "Νέα τοπική κουζίνα"]
    assert normalized["dietLabels"] == ["Vegan", "Χωρίς γλουτένη"]
    assert normalized["mealTypeLabels"] == ["Street food", "Κυρίως γεύμα"]
    assert normalized["tags"] == ["DIY", "Street", "Vegan"]
    assert normalize_catalog_facets(normalized) == normalized


def test_titles_and_untyped_tags_do_not_infer_cuisine_or_diet_classification():
    normalized = normalize_catalog_facets({
        "title": "Vegan street food DIY",
        "tags": ["Vegan", "Street", "DIY", "Greek"],
    })
    assert normalized["cuisineLabels"] == []
    assert normalized["dietLabels"] == []
    assert normalized["mealTypeLabels"] == []
