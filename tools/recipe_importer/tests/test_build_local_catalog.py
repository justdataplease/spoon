import hashlib
import json
import sqlite3
import zlib
from pathlib import Path

import pytest

from tools.recipe_importer.build_local_catalog import (
    APPLICATION_ID,
    PAGE_SIZE,
    SCHEMA_VERSION,
    CatalogBuildError,
    SourceArtifact,
    build_catalog,
    normalize_search_token,
    prepare_recipe,
)


def _record(recipe_id: str, source_key: str, *, title: str) -> dict:
    return {
        "id": recipe_id,
        "title": title,
        "category": "legumes",
        "categoryKeys": ["legumes"],
        "categoryLabel": "Όσπρια",
        "rating": 8.4,
        "rating10": 8.4,
        "prepMinutes": 15,
        "cookMinutes": 25,
        "totalMinutes": 40,
        "preparationCount": 1,
        "stepCount": 4,
        "language": "el",
        "imageUrl": "https://images.example/recipe.jpg",
        "sourceUrl": "https://example.test/recipe",
        "canonicalUrl": "https://example.test/recipe",
        "sourceName": "Δοκιμαστική πηγή",
        "source": "example.test",
        "sourceKey": source_key,
        "providerRecipeId": recipe_id,
        "tags": ["Όσπρια", "Κατσαρόλα"],
        "updatedAtEpochMillis": 0,
        "active": True,
        "sourceRecipeId": 0,
        "slug": "fakes-me-laxanika",
        "description": "Φακές με πολύχρωμα λαχανικά.",
        "seoTitle": "Εύκολες φακές",
        "seoDescription": "Μια γρήγορη συνταγή.",
        "categorySourceId": 1,
        "ratingCount": 10,
        "rating1": 0,
        "rating2": 0,
        "rating3": 1,
        "rating4": 3,
        "rating5": 6,
        "waitMinutes": 0,
        "sourceDifficulty": "εύκολη",
        "servings": "4 μερίδες",
        "imageUrls": ["https://images.example/recipe.jpg"],
        "shortUrl": "",
        "dietLabels": ["Χωρίς γλουτένη", "Vegan"],
        "mealTypeLabels": ["Κυρίως γεύμα"],
        "occasionLabels": ["Καθημερινό"],
        "methodLabels": ["Κατσαρόλα"],
        "cuisineLabels": ["Ελληνική κουζίνα"],
        "ingredientLabels": ["Φακές", "Καρότο"],
        "quickRecipe": False,
        "videoUrls": ["https://www.youtube.com/watch?v=test"],
        "ingredientSections": [
            {
                "title": "Υλικά",
                "ingredients": [
                    {"title": "φακές", "quantity": "250", "unit": "γρ."},
                    {"title": "καρότο", "quantity": "1", "unit": "τεμ."},
                ],
            }
        ],
        "methodSections": [{"title": "Εκτέλεση", "steps": ["Βράζουμε."]}],
        "tips": [],
        "nutritionTips": [],
        "nutritionPer": "",
        "nutritionSections": [],
        "equipment": ["κατσαρόλα"],
        "authorName": "Μάγειρας",
        "publishedAt": "2026-01-01T00:00:00Z",
        "published": True,
        "createdAt": "2026-01-01T00:00:00Z",
        "sourceUpdatedAt": "2026-01-02T00:00:00Z",
        "shares": 0,
        "sponsorLogoUrl": "",
        "ease": "easy",
        "randomKey": 0.125,
        "detailSchemaVersion": "fixture-v1",
        "filterAssociations": {"diet": [{"id": "vegan", "title": "Vegan"}]},
        "sitemapLastModified": "2026-01-02",
        "sourcePayload": {"large": "must never enter the APK", "id": recipe_id},
    }


def _artifact(tmp_path: Path, source_key: str, records: list[dict]) -> SourceArtifact:
    catalog = tmp_path / f"{source_key}.jsonl"
    catalog.write_text(
        "".join(json.dumps(record, ensure_ascii=False) + "\n" for record in records),
        encoding="utf-8",
    )
    manifest = tmp_path / f"{source_key}.manifest.json"
    manifest.write_text(
        json.dumps(
            {
                "complete": True,
                "language": "el",
                "failedRecipeCount": 0,
                "activeRecipeCount": len(records),
                "sourceKey": source_key,
                "catalogHash": "1" * 64,
                "detailHash": "2" * 64,
                "summaryHash": "3" * 64,
                "sourcePayloadHash": "4" * 64,
            }
        ),
        encoding="utf-8",
    )
    return SourceArtifact(catalog, manifest)


def _open_read_only(path: Path) -> sqlite3.Connection:
    return sqlite3.connect(f"file:{path.as_posix()}?mode=ro&immutable=1", uri=True)


def _prepared_for_vegan_test(
    *,
    title: str = "Φακές",
    info: str = "",
    diet_labels: list[str] | None = None,
    category: str = "legumes",
):
    record = _record("fixture_vegan", "fixture", title="Δοκιμή")
    record["dietLabels"] = ["Vegan"] if diet_labels is None else diet_labels
    record["category"] = category
    record["ingredientSections"] = [{
        "title": "Υλικά",
        "ingredients": [{"title": title, "info": info}],
    }]
    return prepare_recipe(record)[0]


@pytest.mark.parametrize(
    "ingredient",
    [
        "κοτόπουλο",
        "κρέας",
        "σολομός",
        "ψάρι",
        "αυγό",
        "γάλα",
        "παρμεζάνα",
        "βούτυρο",
        "γιαούρτι",
        "μέλι",
        "ζελατίνη",
        "gelatine",
        "λαρδί",
        "lard",
        "ρέγγα",
        "σάλτσα Worcestershire",
        "μαγιονέζα",
        "mayo",
        "aioli",
        "ζωμός",
        "stock",
        "πέστο βασιλικού",
    ],
)
def test_strict_vegan_eligibility_rejects_animal_ingredients(ingredient):
    assert _prepared_for_vegan_test(title=ingredient).vegan_eligible == 0


@pytest.mark.parametrize(
    "ingredient",
    [
        "γάλα καρύδας",
        "γάλα σόγιας",
        "γάλα βρώμης",
        "φυτικό τυρί",
        "peanut butter",
        "βούτυρο κακάο",
        "μαγιονέζα χωρίς αυγό",
        "vegan mayonnaise",
        "φυτική μαγιονέζα",
        "ζωμός λαχανικών",
        "mushroom broth",
        "vegan pesto",
        "πέστο χωρίς τυρί",
    ],
)
def test_strict_vegan_eligibility_keeps_explicit_plant_alternatives(ingredient):
    assert _prepared_for_vegan_test(title=ingredient).vegan_eligible == 1


def test_strict_vegan_eligibility_uses_info_only_as_a_local_qualifier():
    assert (
        _prepared_for_vegan_test(title="τυρί", info="φυτικό προϊόν").vegan_eligible
        == 1
    )
    assert (
        _prepared_for_vegan_test(
            title="λαχανικά", info="σερβίρονται με κοτόπουλο"
        ).vegan_eligible
        == 0
    )
    assert _prepared_for_vegan_test(title="", info="κοτόπουλο").vegan_eligible == 0


@pytest.mark.parametrize(
    ("title", "info"),
    [
        ("γλυκόζη", "ή μέλι"),
        ("ζωμός", "κοτόπουλου"),
        ("ζωμός λαχανικών (ή κότας)", ""),
        ("λάδι", "ή βούτυρο"),
        ("γάλα", "φρέσκο ή φυτικό"),
        ("κατσικίσιο τυρί — ή νηστίσιμο", ""),
    ],
)
def test_strict_vegan_eligibility_rejects_animal_info_and_alternatives(
    title, info
):
    assert _prepared_for_vegan_test(title=title, info=info).vegan_eligible == 0


@pytest.mark.parametrize(
    ("diet_labels", "expected"),
    [
        (["Vegan"], 1),
        (["Αυστηρά χορτοφαγική (vegan)"], 1),
        (["Vegan επιλογή"], 0),
        (["όχι vegan"], 0),
        (["Αυστηρά χορτοφαγική vegan επιλογή"], 0),
    ],
)
def test_strict_vegan_eligibility_requires_an_exact_diet_alias(
    diet_labels, expected
):
    assert (
        _prepared_for_vegan_test(diet_labels=diet_labels).vegan_eligible
        == expected
    )


@pytest.mark.parametrize("category", ["meat", "poultry", "fish"])
def test_strict_vegan_eligibility_vetoes_animal_categories(category):
    assert _prepared_for_vegan_test(category=category).vegan_eligible == 0


def test_builds_exact_rows_metadata_query_columns_and_omits_import_payload(tmp_path):
    artifact = _artifact(
        tmp_path,
        "fixture",
        [
            _record("fixture_2", "fixture", title="Γίγαντες πλακί"),
            _record("fixture_1", "fixture", title="Φακές σαλάτα"),
        ],
    )
    output = tmp_path / "recipe_catalog.db"

    result = build_catalog([artifact], output)

    assert result.recipe_count == 2
    assert result.file_size == output.stat().st_size
    assert result.file_sha256 == hashlib.sha256(output.read_bytes()).hexdigest()
    with _open_read_only(output) as database:
        assert SCHEMA_VERSION == 3
        assert database.execute("PRAGMA user_version").fetchone()[0] == SCHEMA_VERSION
        assert database.execute("PRAGMA application_id").fetchone()[0] == APPLICATION_ID
        assert database.execute("PRAGMA page_size").fetchone()[0] == PAGE_SIZE
        assert database.execute("SELECT count(*) FROM recipes").fetchone()[0] == 2
        metadata = dict(database.execute("SELECT key, value FROM catalog_meta"))
        assert metadata["catalog_count"] == "2"
        assert metadata["catalog_hash"] == result.catalog_hash
        assert metadata["catalog_version"] == f"sha256:{result.catalog_hash}"
        assert json.loads(metadata["source_counts"]) == {"fixture": 2}
        assert json.loads(metadata["source_catalog_hashes"]) == {"fixture": "1" * 64}
        assert metadata["json_compression"] == "zlib"

        row = database.execute(
            """
            SELECT title_normalized, search_text, category, ease, rating,
                   prep_minutes, quick_recipe, vegan_eligible, source_key, random_key,
                   recipe_json
            FROM recipes WHERE id = 'fixture_1'
            """
        ).fetchone()
        assert row[:10] == (
            "φακεσ σαλατα",
            row[1],
            "legumes",
            "easy",
            8.4,
            15,
            0,
            1,
            "fixture",
            0.125,
        )
        assert "φακεσ" in row[1]
        assert "καροτο" in row[1]
        assert "πολυχρωμα λαχανικα" in row[1]
        assert "δοκιμαστικη πηγη" in row[1]
        assert "example test" in row[1]
        assert "fixture" in row[1]
        recipe = json.loads(zlib.decompress(row[10]).decode("utf-8"))
        assert recipe["id"] == "fixture_1"
        assert recipe["ingredientSections"][0]["ingredients"][0]["title"] == "φακές"
        assert "sourcePayload" not in recipe
        assert "detailSchemaVersion" not in recipe
        assert "filterAssociations" not in recipe
        assert "sitemapLastModified" not in recipe
        assert "randomKey" not in recipe
        assert "ease" not in recipe
        assert "rating10" not in recipe
        assert "categoryKeys" not in recipe
        assert "language" not in recipe  # Recipe.kt restores its "el" default.
        assert "active" not in recipe
        assert "quickRecipe" not in recipe

        facets = database.execute(
            """
            SELECT facet_type, token FROM recipe_facets
            WHERE recipe_id = 'fixture_1' ORDER BY facet_type, token
            """
        ).fetchall()
        assert ("diet", "vegan") in facets
        assert ("diet", "χωρισ γλουτενη") in facets
        assert ("cuisine", "ελληνικη κουζινα") in facets
        options = json.loads(metadata["facet_options_json"])
        assert {option["token"] for option in options["ingredient"]} == {
            "καροτο",
            "φακεσ",
        }
        index_columns = database.execute(
            "PRAGMA index_info(recipes_vegan_plan_idx)"
        ).fetchall()
        assert [column[2] for column in index_columns] == [
            "vegan_eligible",
            "category",
            "random_key",
            "id",
        ]


def test_indexes_normalized_raw_ingredient_phrases_without_crossing_rows(tmp_path):
    coconut_milk = _record("fixture_1", "fixture", title="Κάρυ με λαχανικά")
    coconut_milk["ingredientSections"] = [{
        "title": "Υλικά",
        "ingredients": [
            {
                "title": "Γάλα καρύδας",
                "info": "ΠΛΗΡΕΣ, χωρίς ζάχαρη",
                "quantity": "400",
                "unit": "ml",
            },
            # Repeated normalized text is deliberately stored only once.
            {
                "title": "γάλα καρύδας",
                "info": "πλήρες χωρίς ζάχαρη",
                "quantity": "1",
                "unit": "κονσέρβα",
            },
        ],
    }]
    separate_words = _record("fixture_2", "fixture", title="Δοκιμή")
    separate_words["ingredientSections"] = [{
        "title": "Υλικά",
        "ingredients": [
            {"title": "Γάλα", "quantity": "1", "unit": "καρύδα"},
            {"title": "Καρύδας", "quantity": "2", "unit": "κ.σ."},
        ],
    }]
    artifact = _artifact(tmp_path, "fixture", [coconut_milk, separate_words])
    output = tmp_path / "recipe_catalog.db"

    build_catalog([artifact], output)

    with _open_read_only(output) as database:
        assert database.execute(
            "SELECT recipe_id, normalized_text FROM recipe_ingredient_texts "
            "ORDER BY recipe_id, normalized_text"
        ).fetchall() == [
            ("fixture_1", "γαλα καρυδασ πληρεσ χωρισ ζαχαρη"),
            ("fixture_2", "γαλα"),
            ("fixture_2", "καρυδασ"),
        ]
        phrase = normalize_search_token("Γάλα καρύδας")
        matching = database.execute(
            """
            SELECT r.id
            FROM recipes r
            WHERE EXISTS (
                SELECT 1 FROM recipe_ingredient_texts i
                WHERE i.recipe_id = r.id
                  AND instr(i.normalized_text, ?) > 0
            )
            ORDER BY r.id
            """,
            (phrase,),
        ).fetchall()
        assert matching == [("fixture_1",)]
        allowed = database.execute(
            """
            SELECT r.id
            FROM recipes r
            WHERE NOT EXISTS (
                SELECT 1 FROM recipe_ingredient_texts i
                WHERE i.recipe_id = r.id
                  AND instr(i.normalized_text, ?) > 0
            )
            ORDER BY r.id
            """,
            (phrase,),
        ).fetchall()
        assert allowed == [("fixture_2",)]
        metadata = dict(database.execute("SELECT key, value FROM catalog_meta"))
        assert (
            metadata["ingredient_text_index"]
            == "recipe_ingredient_texts-title-info-instr-v1"
        )
        assert metadata["ingredient_text_normalization"] == "nfkd-casefold-alnum-v1"


def test_build_is_byte_deterministic_and_source_order_independent(tmp_path):
    first = _artifact(
        tmp_path,
        "one",
        [_record("one_1", "one", title="Σούπα")],
    )
    second = _artifact(
        tmp_path,
        "two",
        [_record("two_1", "two", title="Πίτα")],
    )
    output_a = tmp_path / "a.db"
    output_b = tmp_path / "b.db"

    result_a = build_catalog([first, second], output_a)
    result_b = build_catalog([second, first], output_b)

    assert result_a.catalog_hash == result_b.catalog_hash
    assert result_a.file_sha256 == result_b.file_sha256
    assert output_a.read_bytes() == output_b.read_bytes()


def test_rejects_incomplete_manifest_and_non_derived_ease(tmp_path):
    record = _record("fixture_1", "fixture", title="Φακές")
    record["ease"] = "involved"
    artifact = _artifact(tmp_path, "fixture", [record])
    with pytest.raises(CatalogBuildError, match="does not match derived"):
        build_catalog([artifact], tmp_path / "bad.db")

    record["ease"] = "easy"
    artifact = _artifact(tmp_path, "fixture", [record])
    manifest = json.loads(artifact.manifest_path.read_text(encoding="utf-8"))
    manifest["complete"] = False
    artifact.manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(CatalogBuildError, match="not complete"):
        build_catalog([artifact], tmp_path / "incomplete.db")


@pytest.mark.parametrize(
    ("value", "expected"),
    [
        ("Όσπρια", "οσπρια"),
        ("ΧΩΡΊΣ  γλουτένη!", "χωρισ γλουτενη"),
        ("Crème brûlée", "creme brulee"),
    ],
)
def test_search_normalization_is_accent_and_case_insensitive(value, expected):
    assert normalize_search_token(value) == expected


@pytest.mark.parametrize("column, bad_value", [
    ("ease", "involved"), ("rating", 1.0), ("prep_minutes", 999), ("quick_recipe", 1),
])
def test_catalog_audit_detects_planner_predicate_drift(tmp_path, column, bad_value):
    from tools.recipe_importer.audit_catalog_quality import audit_catalog

    artifact = _artifact(tmp_path, "fixture", [_record("fixture_1", "fixture", title="Test")])
    output = tmp_path / "catalog.db"
    build_catalog([artifact], output)
    assert audit_catalog(output)["indexMismatchCount"] == 0
    with sqlite3.connect(output) as database:
        database.execute(f"UPDATE recipes SET {column} = ?", (bad_value,))
    report = audit_catalog(output)
    assert report["indexMismatchCount"] == 1
    assert column in report["indexMismatchSamples"][0]["plannerDifferences"]
