import json

import pytest

import tools.recipe_importer.import_catalog as importer
from tools.recipe_importer.import_catalog import (
    COLLECTION_NAME,
    STATUS_COLLECTION_NAME,
    CatalogError,
    commit_catalog,
    compute_catalog_hash,
    load_catalog,
    replace_records,
    validate_catalog,
    validate_record,
)


def record(recipe_id: int) -> dict:
    return {
        "id": str(recipe_id),
        "title": f"Synthetic recipe {recipe_id}",
        "sourceUrl": f"https://akispetretzikis.com/recipe/{recipe_id}/synthetic",
        "categoryKeys": ["other"],
        "prepMinutes": 10,
        "cookMinutes": 20,
        "preparationCount": 1,
        "stepCount": 4,
        "rating10": 8.0,
    }


def test_loads_jsonl_and_derives_safe_fields(tmp_path):
    path = tmp_path / "catalog.jsonl"
    path.write_text("\n".join(json.dumps(record(i)) for i in (1, 2)), encoding="utf-8")
    validated = validate_catalog(load_catalog(path))
    assert len(validated) == 2
    assert validated[0]["totalMinutes"] == 30
    assert validated[0]["language"] == "el"
    assert validated[0]["ease"] == "easy"
    assert validated[0]["active"] is True


def test_cli_is_a_firestore_free_dry_run_by_default(tmp_path, monkeypatch, capsys):
    path = tmp_path / "catalog.json"
    path.write_text(json.dumps([record(1)]), encoding="utf-8")

    def unexpected_connection(*args, **kwargs):
        raise AssertionError("dry-run must not initialize Firebase")

    monkeypatch.setattr(importer, "create_firestore_client", unexpected_connection)
    assert importer.main([str(path)]) == 0
    summary = json.loads(capsys.readouterr().out)
    assert summary["collection"] == "spoon_recipes"
    assert summary["mode"] == "dry-run"
    assert summary["validated"] == 1
    assert summary["writes"] == 0
    assert summary["catalogVersion"] == f"sha256:{summary['catalogHash']}"
    assert len(summary["catalogHash"]) == 64


def test_rejects_full_recipe_content_and_duplicate_ids():
    unsafe = record(1) | {"recipeInstructions": ["must not be stored"]}
    with pytest.raises(CatalogError, match="unsupported fields"):
        validate_record(unsafe)
    with pytest.raises(CatalogError, match="duplicate id"):
        validate_catalog([record(1), record(1)])


def test_canonicalizes_source_url_and_rejects_inconsistent_ease():
    value = record(7) | {
        "sourceUrl": "https://akispetretzikis.com/recipe/7/test?tracking=no#fragment",
    }
    assert validate_record(value)["sourceUrl"] == "https://akispetretzikis.com/recipe/7/test"
    with pytest.raises(CatalogError, match="does not match"):
        validate_record(record(7) | {"ease": "involved"})


def test_accepts_el_alias_but_rejects_english_url_or_language():
    value = record(9) | {
        "sourceUrl": "https://akispetretzikis.com/el/recipe/9/test",
        "language": "el",
    }
    assert validate_record(value)["sourceUrl"] == "https://akispetretzikis.com/recipe/9/test"
    with pytest.raises(CatalogError, match="English URLs are rejected"):
        validate_record(
            record(9)
            | {"sourceUrl": "https://akispetretzikis.com/en/recipe/9/test"}
        )
    with pytest.raises(CatalogError, match="Greek-only"):
        validate_record(record(9) | {"language": "en"})


def test_writes_android_canonical_fields_and_keeps_richer_metadata():
    value = record(8) | {"categoryKeys": ["dirty", "poultry"], "rating10": None}
    validated = validate_record(value)

    assert validated["category"] == "street_food"
    assert validated["rating"] == 0.0
    assert validated["sourceName"] == "Άκης Πετρετζίκης"
    assert validated["tags"] == ["dirty", "poultry"]
    assert validated["categoryKeys"] == ["dirty", "poultry"]
    assert validated["rating10"] is None
    assert validated["source"] == "akispetretzikis.com"


def test_missing_structural_counts_are_explicitly_unknown():
    value = record(12)
    value.pop("preparationCount")
    value.pop("stepCount")
    validated = validate_record(value)
    assert validated["preparationCount"] == 0
    assert validated["stepCount"] == 0
    assert validated["ease"] == "unknown"


def test_missing_or_null_durations_are_android_safe_and_total_is_derived():
    prep_only = record(13)
    prep_only.pop("cookMinutes")
    prep_only["totalMinutes"] = None
    validated_prep_only = validate_record(prep_only)
    assert validated_prep_only["prepMinutes"] == 10
    assert validated_prep_only["cookMinutes"] == 0
    assert validated_prep_only["totalMinutes"] == 10

    unknown = record(14) | {
        "prepMinutes": None,
        "cookMinutes": None,
        "totalMinutes": None,
    }
    validated_unknown = validate_record(unknown)
    assert validated_unknown["prepMinutes"] == 0
    assert validated_unknown["cookMinutes"] == 0
    assert validated_unknown["totalMinutes"] == 0


def test_licensed_image_requires_flag_and_is_only_persisted_as_validated_url():
    value = record(10) | {
        "imageUrl": "https://www.akispetretzikis.com/photos/123/synthetic%20dish.jpg"
    }
    with pytest.raises(CatalogError, match="--allow-licensed-images"):
        validate_record(value)

    validated = validate_record(value, allow_licensed_images=True)
    assert validated["imageUrl"] == (
        "https://akispetretzikis.com/photos/123/synthetic%20dish.jpg"
    )


@pytest.mark.parametrize(
    "image_url",
    [
        "http://akispetretzikis.com/photos/123/dish.jpg",
        "https://example.com/photos/123/dish.jpg",
        "https://akispetretzikis.com/assets/dish.jpg",
        "https://akispetretzikis.com/photos/../private/dish.jpg",
        "https://akispetretzikis.com/photos/123/dish.jpg?token=secret",
    ],
)
def test_licensed_image_rejects_unsafe_host_or_path(image_url):
    with pytest.raises(CatalogError, match="imageUrl"):
        validate_record(
            record(10) | {"imageUrl": image_url},
            allow_licensed_images=True,
        )


def test_cli_licensed_image_switch_is_explicit_and_still_dry_run(
    tmp_path, monkeypatch, capsys
):
    path = tmp_path / "licensed-catalog.json"
    path.write_text(
        json.dumps(
            [
                record(11)
                | {"imageUrl": "https://akispetretzikis.com/photos/123/dish.jpg"}
            ]
        ),
        encoding="utf-8",
    )

    monkeypatch.setattr(
        importer,
        "create_firestore_client",
        lambda *args, **kwargs: (_ for _ in ()).throw(
            AssertionError("dry-run must not initialize Firebase")
        ),
    )
    assert importer.main([str(path)]) == 2
    assert "--allow-licensed-images" in capsys.readouterr().err
    assert importer.main([str(path), "--allow-licensed-images"]) == 0
    assert json.loads(capsys.readouterr().out)["writes"] == 0


class FakeDocument:
    def __init__(self, name):
        self.name = name


class FakeCollection:
    def __init__(self, client, name):
        self.client = client
        self.name = name

    def document(self, document_id):
        return FakeDocument(f"{self.name}/{document_id}")


class FakeBatch:
    def __init__(self, client):
        self.client = client
        self.writes = []

    def set(self, reference, payload, merge):
        self.writes.append((reference, payload, merge))

    def commit(self):
        self.client.commit_attempts += 1
        if self.client.fail_commit_number == self.client.commit_attempts:
            raise RuntimeError("synthetic commit failure")
        self.client.commits.append(self.writes)


class FakeClient:
    def __init__(self, fail_commit_number=None):
        self.commits = []
        self.collection_names = []
        self.commit_attempts = 0
        self.fail_commit_number = fail_commit_number

    def collection(self, name):
        self.collection_names.append(name)
        return FakeCollection(self, name)

    def batch(self):
        return FakeBatch(self)


def test_firestore_replacements_never_exceed_500_writes_per_batch():
    client = FakeClient()
    records = [validate_record(record(i)) for i in range(1, 502)]
    writes, batches = replace_records(client, records, server_timestamp="SERVER_TIME")

    assert (writes, batches) == (501, 2)
    assert [len(batch) for batch in client.commits] == [500, 1]
    assert client.collection_names == [COLLECTION_NAME]
    assert client.commits[0][0][2] is False
    reference, payload, _ = client.commits[0][0]
    assert reference.name == "spoon_recipes/1"
    assert "id" not in payload
    assert payload["importedAt"] == "SERVER_TIME"


def test_full_replacement_removes_stale_id_and_clears_stale_image_url():
    client = FakeClient()
    validated = validate_record(record(15))

    assert validated["imageUrl"] == ""
    replace_records(client, [validated])
    _, payload, merge = client.commits[0][0]
    assert merge is False
    assert "id" not in payload
    assert set(payload) == set(validated) - {"id"}
    assert payload["imageUrl"] == ""


def test_catalog_hash_is_order_independent_and_content_sensitive():
    first = validate_record(record(1))
    second = validate_record(record(2))
    assert compute_catalog_hash([first, second]) == compute_catalog_hash([second, first])
    changed = validate_record(record(2) | {"title": "Changed synthetic title"})
    assert compute_catalog_hash([first, second]) != compute_catalog_hash([first, changed])


def test_successful_commit_writes_status_after_all_recipe_batches():
    client = FakeClient()
    records = [validate_record(record(i)) for i in range(1, 502)]
    writes, batches, catalog_hash = commit_catalog(
        client,
        records,
        server_timestamp="SERVER_TIME",
    )

    assert (writes, batches) == (501, 2)
    assert [len(batch) for batch in client.commits] == [500, 1, 1]
    assert client.collection_names == [COLLECTION_NAME, STATUS_COLLECTION_NAME]
    reference, status, merge = client.commits[-1][0]
    assert reference.name == "spoon_catalog/status"
    assert merge is True
    assert status == {
        "language": "el",
        "recipeCount": 501,
        "catalogVersion": f"sha256:{catalog_hash}",
        "catalogHash": catalog_hash,
        "lastImportedAt": "SERVER_TIME",
    }


def test_failed_recipe_batch_never_publishes_new_status():
    client = FakeClient(fail_commit_number=2)
    records = [validate_record(record(i)) for i in range(1, 502)]

    with pytest.raises(RuntimeError, match="synthetic commit failure"):
        commit_catalog(client, records, server_timestamp="SERVER_TIME")
    assert client.collection_names == [COLLECTION_NAME]
    assert [len(batch) for batch in client.commits] == [500]
