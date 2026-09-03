import pytest

from tools.recipe_importer.helpers import (
    classify_category_keys,
    classify_ease,
    classify_official_category_keys,
    canonical_category,
    count_preparation_sections,
    count_recipe_steps,
    parse_duration_minutes,
    rating_to_ten,
    stable_random_key,
)


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        ("PT15M", 15),
        ("PT1H30M", 90),
        ("P1DT2H3M4S", 1_564),
        ("PT30S", 1),
        (75, 75),
        (None, None),
    ],
)
def test_parse_duration_minutes(value, expected):
    assert parse_duration_minutes(value) == expected


def test_parse_duration_rejects_non_duration():
    with pytest.raises(ValueError):
        parse_duration_minutes("45 minutes")


def test_metadata_classifier_uses_exact_metadata_but_never_the_title():
    assert classify_category_keys(
        "Τρουφάκια με αβοκάντο και καρύδα"
    ) == ["other"]
    assert classify_category_keys(
        "ignored title",
        ["Fish"],
        "street food, salmon",
    ) == ["fish", "dirty"]
    assert classify_category_keys(
        "ignored",
        ["Τρουφάκια"],
        "beanbag, chickenpox",
    ) == ["other"]


def test_official_dessert_ancestry_is_terminal_over_ingredients():
    associations = {
        "ingredient": [{"id": "139", "title": "Φακές"}],
        "meal_type": [],
    }
    assert classify_official_category_keys(
        {"id": 34, "slug": "glika", "parent_id": 1},
        associations,
    ) == ["dessert"]
    assert classify_official_category_keys(
        {"id": 35, "slug": "ta-aghapimena-mas", "parent_id": 34},
        associations,
    ) == ["dessert"]
    assert classify_official_category_keys(
        {"id": 999, "slug": "future-sweet", "parent_id": 34},
        associations,
    ) == ["dessert"]
    assert classify_official_category_keys(
        {"id": 999, "slug": "generic", "parent_id": 1},
        {"ingredient": associations["ingredient"], "meal_type": [{"id": "34"}]},
    ) == ["dessert"]


def test_official_recipe_category_wins_then_facets_have_stable_precedence():
    associations = {
        "ingredient": [
            {"id": "135", "title": "Κοτόπουλο"},
            {"id": "145", "title": "Γαρίδες"},
            {"id": "139", "title": "Φακές"},
        ],
        "meal_type": [],
    }
    assert classify_official_category_keys(
        {"id": 19, "slug": "zimarika"},
        associations,
    ) == ["pasta", "seafood", "legumes", "poultry"]
    assert classify_official_category_keys(
        {"id": 53, "slug": "salates"},
        associations,
    ) == ["seafood", "legumes", "poultry"]
    assert classify_official_category_keys(
        {"id": 53, "slug": "salates"},
        {**associations, "ingredient": list(reversed(associations["ingredient"]))},
    ) == ["seafood", "legumes", "poultry"]


def test_unknown_taxonomy_fails_closed_and_exact_format_wins_after_terminal_guards():
    assert classify_official_category_keys(
        {"id": 999, "slug": "unknown"},
        {"ingredient": [{"id": "999", "title": "Looks like beans"}]},
    ) == ["other"]
    assert classify_official_category_keys(
        {"id": 33, "slug": "snak"},
        {"ingredient": [{"id": "129", "title": "Μοσχάρι"}]},
    ) == ["meat"]
    assert classify_official_category_keys(
        {"id": 33, "slug": "snak"},
        {},
    ) == ["other"]
    assert classify_official_category_keys(
        {"id": 33, "slug": "snak"},
        {"meal_type": [{"id": "32", "title": "Σάντουιτς"}]},
    ) == ["dirty"]
    assert classify_official_category_keys(
        {"id": 33, "slug": "snak"},
        {"meal_type": [{"id": "92", "title": "Finger food"}]},
    ) == ["dirty"]
    assert classify_official_category_keys(
        {"id": 33, "slug": "snak"},
        {
            "ingredient": [{"id": "129", "title": "Μοσχάρι"}],
            "meal_type": [{"id": "92", "title": "Finger food"}],
        },
    ) == ["dirty", "meat"]
    assert classify_official_category_keys(
        {"id": 19, "slug": "kotopulo"},
        {
            "ingredient": [{"id": "135", "title": "Κοτόπουλο"}],
            "meal_type": [{"id": "92", "title": "Finger food"}],
        },
    ) == ["dirty", "poultry"]
    assert classify_official_category_keys(
        {"id": 999, "slug": "santuits"},
        {
            "ingredient": [
                {"id": "129", "title": "Μοσχάρι"},
                {"id": "139", "title": "Φακές"},
            ],
        },
    ) == ["dirty", "legumes", "meat"]
    assert classify_official_category_keys(
        {"id": 33, "slug": "snak"},
        {
            "ingredient": [{"id": "154", "title": "Σοκολάτα"}],
            "meal_type": [{"id": "33", "title": "Σνακ"}],
        },
    ) == ["other"]
    assert classify_official_category_keys(
        {"id": 47, "slug": "smoothies"},
        {
            "ingredient": [{"id": "139", "title": "Φακές"}],
            "meal_type": [{"id": "92", "title": "Finger food"}],
        },
    ) == ["other"]
    assert classify_official_category_keys(
        {"id": 34, "slug": "glika"},
        {"meal_type": [{"id": "92", "title": "Finger food"}]},
    ) == ["dessert"]


def test_canonical_category_uses_first_supported_alias():
    assert canonical_category(["other", "dirty", "poultry"]) == "other"
    assert canonical_category(["dessert"]) == "dessert"
    assert canonical_category(["seafood"]) == "fish"
    assert canonical_category(["pasta", "rice"]) == "pasta_rice"
    assert canonical_category(["other"]) == "other"
    assert canonical_category(["future-value"]) == "other"


def test_step_and_preparation_counts_are_structural_only():
    instructions = [
        {
            "@type": "HowToSection",
            "itemListElement": [
                {"@type": "HowToStep", "text": "not exported"},
                {"@type": "HowToStep", "text": "not exported"},
            ],
        },
        {"@type": "HowToSection", "itemListElement": ["not exported"]},
    ]
    assert count_recipe_steps(instructions) == 3
    assert count_preparation_sections(instructions) == 2
    assert classify_ease(2, 3, 40) == "moderate"


@pytest.mark.parametrize(
    ("preparations", "steps", "minutes", "expected"),
    [
        (0, 0, 30, "unknown"),
        (3, 1, 10, "involved"),
        (1, 10, 10, "involved"),
        (1, 5, 1_000, "easy"),
        (1, 6, 10, "moderate"),
        (2, 1, 10, "moderate"),
    ],
)
def test_ease_uses_structure_not_duration(preparations, steps, minutes, expected):
    assert classify_ease(preparations, steps, minutes) == expected


def test_rating_is_normalized_to_ten():
    assert rating_to_ten({"ratingValue": 4.25, "bestRating": 5}) == 8.5
    assert rating_to_ten({"ratingValue": "bad"}) is None


def test_random_key_is_stable_and_bounded():
    assert stable_random_key("13") == stable_random_key("13")
    assert stable_random_key("13") != stable_random_key("14")
    assert 0 <= stable_random_key("13") < 1
