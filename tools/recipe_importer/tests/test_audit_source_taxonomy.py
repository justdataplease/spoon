import json

from tools.recipe_importer.audit_source_taxonomy import audit_sources, source_taxonomy


def test_source_audit_distinguishes_category_changes_from_missing_and_extra_tags(tmp_path):
    record = {
        "id": "gastronomos_fixture", "sourceKey": "gastronomos", "title": "Fixture",
        "sourcePayload": {
            "htmlMetadata": {"facetLinks": [
                {"family": "vasiko-yliko", "slug": "chicken", "label": "chicken"},
            ]}, "jsonLd": {"recipeCategory": ["Main dish"]},
        },
    }
    record.update(source_taxonomy(record))
    path = tmp_path / "fixture.jsonl"
    path.write_text(json.dumps(record), encoding="utf-8")
    clean = audit_sources([path])
    assert clean["recordCount"] == 1
    assert clean["tagMismatchCount"] == clean["categoryChangeCount"] == 0
    record["ingredientLabels"] = ["unrelated"]
    record["category"] = "other"
    path.write_text(json.dumps(record), encoding="utf-8")
    changed = audit_sources([path])
    assert changed["tagMismatchCount"] == changed["categoryChangeCount"] == 1
    assert "ingredientLabels" in changed["tagMismatchSamples"][0]["fields"]
