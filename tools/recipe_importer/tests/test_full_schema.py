"""Tests for complete API normalization and the three Firestore projections."""

import json
from pathlib import Path

import pytest

from tools.recipe_importer.full_schema import (
    MAX_FIRESTORE_DOCUMENT_BYTES,
    FullSchemaError,
    collection_hash,
    document_sizes,
    ensure_full_record,
    firestore_detail_payload,
    firestore_recipe_payload,
    firestore_source_payload,
    normalize_recipe_detail,
    parse_wait_minutes,
    sanitize_source_payload,
)


FIXTURE = Path(__file__).parent / "fixtures" / "api_recipe.json"


def payload():
    return json.loads(FIXTURE.read_text(encoding="utf-8"))


def full_record():
    return normalize_recipe_detail(
        payload(),
        source_url="https://akispetretzikis.com/recipe/123/synthetiki-syntagi",
        sitemap_last_modified="2026-02-01",
        associations={
            "diet": [{"id": "gf", "title": "gf"}],
            "meal_type": [{"id": "3", "title": "Κυρίως γεύμα"}],
            "occasion": [{"id": "75", "title": "Γρήγορα πιάτα"}],
            "method": [{"id": "108", "title": "Κατσαρόλα"}],
            "cuisine": [{"id": "117", "title": "Ελλάδα"}],
            "ingredient": [{"id": "129", "title": "Μοσχάρι"}],
        },
    )


def test_normalizes_every_android_detail_and_retains_unknown_source_fields():
    record = full_record()
    ensure_full_record(record)
    assert record["id"] == "123"
    assert record["rating"] == 9.0
    assert record["ratingCount"] == 20
    assert record["waitMinutes"] == 75
    assert record["totalMinutes"] == 130
    assert record["preparationCount"] == 1
    assert record["stepCount"] == 2
    assert record["methodSections"][0]["steps"][0] == "Ψήνουμε στους 180°C."
    ingredient = record["ingredientSections"][0]["ingredients"][0]
    assert ingredient["internalLink"] == "https://akispetretzikis.com/recipe/55/voithitiki"
    assert ingredient["externalLink"] == "https://example.com/info"
    assert ingredient["ukQuantity"] == "17.64"
    assert record["nutritionSections"][0]["saturatedFat100g"] == "8.4"
    assert record["imageUrls"] == [
        "https://akispetretzikis.com/photos/100/dish.jpg",
        "https://akispetretzikis.com/photos/101/dish-vertical.jpg",
    ]
    assert record["videoUrls"] == ["https://youtu.be/abc123"]
    assert record["cuisineLabels"] == ["Ελλάδα"]
    assert record["dietLabels"] == ["Χωρίς γλουτένη"]
    assert record["categoryLabel"] in record["tags"]
    assert not set(record["categoryKeys"]) & set(record["tags"])
    assert record["quickRecipe"] is True
    assert record["sourcePayload"]["future_api_field"] == {"nested": "retained&safe"}


def test_normalizes_publisher_label_after_video_url():
    source = payload()
    source["video_url"] = "https://youtu.be/VWaCrszdgt4 Heinz"
    record = normalize_recipe_detail(
        source,
        source_url="https://akispetretzikis.com/recipe/6162/stromboli",
        sitemap_last_modified="2026-09-03",
        associations={},
    )
    assert record["videoUrls"] == ["https://youtu.be/VWaCrszdgt4"]


def test_projects_lean_list_complete_detail_and_complete_raw_source_docs():
    record = full_record()
    summary = firestore_recipe_payload(record)
    detail = firestore_detail_payload(record)
    source = firestore_source_payload(record)
    assert "ingredientSections" not in summary
    assert "methodSections" not in summary
    assert "nutritionSections" not in summary
    assert "sourcePayload" not in summary
    assert detail["ingredientSections"] == record["ingredientSections"]
    assert "sourcePayload" not in detail
    assert source["payload"] == record["sourcePayload"]
    assert source["payload"]["future_api_field"] == {"nested": "retained&safe"}
    assert collection_hash([record], firestore_recipe_payload) != collection_hash([record], firestore_detail_payload)
    assert all(size < MAX_FIRESTORE_DOCUMENT_BYTES for size in document_sizes(record))


@pytest.mark.parametrize(
    ("value", "minutes"),
    [("2 ώρες και 5 λεπτά", 125), ("45 λεπτά", 45), ("-", 0), (30, 30)],
)
def test_wait_duration_parser(value, minutes):
    assert parse_wait_minutes(value) == minutes


def test_rejects_nonfinite_source_and_oversized_documents_without_truncation():
    with pytest.raises(FullSchemaError, match="non-finite"):
        sanitize_source_payload({"bad": float("nan")})
    record = full_record()
    record["sourcePayload"]["huge"] = "x" * MAX_FIRESTORE_DOCUMENT_BYTES
    with pytest.raises(FullSchemaError, match="exceeds"):
        ensure_full_record(record)
