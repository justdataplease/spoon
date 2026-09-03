"""Tests for permission-gated three-collection Firestore imports."""

import json
from pathlib import Path

import pytest

from tools.recipe_importer.full_schema import normalize_recipe_detail
from tools.recipe_importer.import_catalog import (
    COLLECTION_NAME,
    DETAIL_COLLECTION_NAME,
    PAYLOAD_COLLECTION_NAME,
    STATUS_COLLECTION_NAME,
    CatalogError,
    commit_catalog,
    validate_catalog,
    validate_manifest,
    validate_record,
)


FIXTURE = Path(__file__).parent / "fixtures" / "api_recipe.json"


def full_record():
    payload = json.loads(FIXTURE.read_text(encoding="utf-8"))
    return normalize_recipe_detail(
        payload,
        source_url="https://akispetretzikis.com/recipe/123/synthetiki-syntagi",
        sitemap_last_modified="2026-02-01",
    )


class FakeDocument:
    def __init__(self, name):
        self.name = name


class FakeCollection:
    def __init__(self, client, name):
        self.client, self.name = client, name

    def document(self, document_id):
        return FakeDocument(f"{self.name}/{document_id}")

    def select(self, fields):
        assert fields == ["active", "source", "sourceKey", "sourceUrl"]
        return self

    def stream(self):
        return [FakeSnapshot(recipe_id, data) for recipe_id, data in self.client.existing.items()]


class FakeSnapshot:
    def __init__(self, recipe_id, data):
        self.id, self.data = recipe_id, data

    def to_dict(self):
        return self.data if isinstance(self.data, dict) else {"active": self.data}


class FakeBatch:
    def __init__(self, client):
        self.client, self.writes = client, []

    def set(self, reference, payload, merge):
        self.writes.append((reference, payload, merge))

    def commit(self):
        self.client.attempts += 1
        if self.client.fail_at == self.client.attempts:
            raise RuntimeError("synthetic failure")
        self.client.commits.append(self.writes)


class FakeClient:
    def __init__(self, fail_at=None, existing=None):
        self.commits, self.collections, self.attempts = [], [], 0
        self.fail_at = fail_at
        self.existing = existing or {}

    def collection(self, name):
        self.collections.append(name)
        return FakeCollection(self, name)

    def batch(self):
        return FakeBatch(self)


def test_full_record_requires_permission_but_legacy_library_path_is_preserved():
    with pytest.raises(CatalogError, match="--i-have-permission"):
        validate_record(full_record())
    validated = validate_record(full_record(), have_permission=True)
    assert validated["sourcePayload"]["future_api_field"] == {"nested": "retained&safe"}


def test_commit_replaces_summary_detail_payload_then_publishes_status():
    record = validate_record(full_record(), have_permission=True)
    client = FakeClient()
    writes, batches, _ = commit_catalog(client, [record], server_timestamp="SERVER")
    assert (writes, batches) == (1, 3)
    assert client.collections == [
        COLLECTION_NAME,
        COLLECTION_NAME,
        DETAIL_COLLECTION_NAME,
        PAYLOAD_COLLECTION_NAME,
        STATUS_COLLECTION_NAME,
        STATUS_COLLECTION_NAME,
    ]
    _, summary, summary_merge = client.commits[0][0]
    _, detail, detail_merge = client.commits[1][0]
    _, source, source_merge = client.commits[2][0]
    assert summary_merge is detail_merge is source_merge is False
    assert "ingredientSections" not in summary and "sourcePayload" not in summary
    assert detail["ingredientSections"] == record["ingredientSections"]
    assert "sourcePayload" not in detail
    assert source["payload"] == record["sourcePayload"]
    status = client.commits[3][0][1]
    assert len(client.commits[3]) == 2
    assert status["complete"] is True
    assert status["recipeCount"] == status["detailRecipeCount"] == status["sourcePayloadCount"] == 1
    assert status["activeRecipeCount"] == status["activeDetailRecipeCount"] == status["activeSourcePayloadCount"] == 1
    assert len(status["summaryHash"]) == len(status["detailHash"]) == len(status["sourcePayloadHash"]) == 64


def test_payload_failure_never_publishes_catalog_status():
    record = validate_record(full_record(), have_permission=True)
    client = FakeClient(fail_at=3)
    with pytest.raises(RuntimeError, match="synthetic failure"):
        commit_catalog(client, [record], server_timestamp="SERVER")
    assert client.collections == [COLLECTION_NAME, COLLECTION_NAME, DETAIL_COLLECTION_NAME, PAYLOAD_COLLECTION_NAME]
    assert len(client.commits) == 2


def test_manifest_mismatch_is_rejected_before_firestore():
    records = validate_catalog([full_record()], have_permission=True)
    with pytest.raises(CatalogError, match="manifest/catalog mismatch"):
        validate_manifest(records, {
            "complete": True,
            "failedRecipeCount": 0,
            "outputRecipeCount": 999,
        })


def test_full_commit_retires_existing_active_ids_missing_from_new_active_catalog():
    record = validate_record(full_record(), have_permission=True)
    client = FakeClient(existing={
        "123": True,
        "999": True,
        "already-inactive": False,
        "argiro_17265": {
            "active": True,
            "sourceKey": "argiro",
            "source": "argiro.gr",
            "sourceUrl": "https://argiro.gr/recipe/synthetic/",
        },
    })
    _, batches, _ = commit_catalog(client, [record], server_timestamp="SERVER")
    assert batches == 4
    retirement = client.commits[-2]
    assert len(retirement) == 3
    assert {write[0].name for write in retirement} == {
        "spoon_recipes/999",
        "spoon_recipe_details/999",
        "spoon_recipe_payloads/999",
    }
    assert all(write[1] == {"active": False, "retiredAt": "SERVER"} and write[2] is True for write in retirement)
    status = client.commits[-1][0][1]
    assert status["activeRecipeCount"] == 1
    assert status["retiredOnCommitCount"] == 1
    assert status["knownRetiredRecipeCount"] == 1
    assert all("argiro_17265" not in write[0].name for batch in client.commits for write in batch)
