"""Tests for permission-gated three-collection Firestore imports."""

import json
import hashlib
from pathlib import Path

import pytest

import tools.recipe_importer.import_catalog as importer
from tools.recipe_importer.crawl_argiro import (
    AUDITED_CANONICAL_ALIASES,
    AUDITED_EXTERNAL_REDIRECTS,
    AUDITED_INTERNAL_STALE_REDIRECTS,
    AUDITED_NON_GREEK_STUBS,
    checkpoint_run_key,
    parser_contract_hash,
)
from tools.recipe_importer.full_schema import ARGIRO_DETAIL_SCHEMA_VERSION
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


def test_preflight_rejects_nested_arrays_before_any_firestore_access():
    record = validate_record(full_record(), have_permission=True)
    record["sourcePayload"]["future_api_field"] = {
        "outer": [["forbidden nested array"]],
    }
    client = FakeClient()

    with pytest.raises(
        CatalogError,
        match=(
            r"spoon_recipe_payloads/123\.payload\.future_api_field"
            r"\.outer\[0\]"
        ),
    ):
        commit_catalog(client, [record], server_timestamp="SERVER")

    assert client.collections == []
    assert client.commits == []


def test_preflight_rejects_firestore_nesting_over_twenty_before_access():
    record = validate_record(full_record(), have_permission=True)
    nested = "leaf"
    for _ in range(20):
        nested = {"child": nested}
    record["sourcePayload"]["future_api_field"] = nested
    client = FakeClient()

    with pytest.raises(CatalogError, match="nesting exceeds 20"):
        commit_catalog(client, [record], server_timestamp="SERVER")

    assert client.collections == []
    assert client.commits == []


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


def test_argiro_manifest_requires_exact_allowlists_hashes_and_discovery_algebra(
    monkeypatch,
):
    aliases = sorted(
        [
            {"sourceUrl": source_url, **details}
            for source_url, details in AUDITED_CANONICAL_ALIASES.items()
        ],
        key=lambda item: item["sourceUrl"],
    )
    external = sorted(
        [
            {
                "sourceUrl": source_url,
                **details,
                "reason": "redirected outside /recipe/{slug}/",
            }
            for source_url, details in AUDITED_EXTERNAL_REDIRECTS.items()
        ],
        key=lambda item: item["sourceUrl"],
    )
    stubs = sorted(
        [
            {
                "sourceUrl": source_url,
                "providerRecipeId": provider_recipe_id,
                "finalStatus": 200,
                "reason": "recipe content is not substantively Greek",
            }
            for source_url, provider_recipe_id in AUDITED_NON_GREEK_STUBS.items()
        ],
        key=lambda item: item["sourceUrl"],
    )
    internal_stale = sorted(
        [
            {
                "sourceUrl": source_url,
                **details,
                "reason": (
                    "redirected to a distinct surviving recipe; "
                    "content substitution is forbidden"
                ),
            }
            for source_url, details in AUDITED_INTERNAL_STALE_REDIRECTS.items()
        ],
        key=lambda item: item["sourceUrl"],
    )
    exclusions = sorted(
        [{"kind": "externalRedirect", **item} for item in external]
        + [{"kind": "nonGreekStub", **item} for item in stubs]
        + [{"kind": "internalStaleRedirect", **item} for item in internal_stale],
        key=lambda item: item["sourceUrl"],
    )
    records = [
        {
            "id": f"argiro_{alias['providerRecipeId']}",
            "providerRecipeId": alias["providerRecipeId"],
            "canonicalUrl": alias["canonicalUrl"],
            "sourceKey": "argiro",
            "detailSchemaVersion": ARGIRO_DETAIL_SCHEMA_VERSION,
            "active": True,
        }
        for alias in aliases
    ]
    monkeypatch.setattr(importer, "compute_catalog_hash", lambda values: "a" * 64)
    monkeypatch.setattr(
        importer,
        "collection_hash",
        lambda values, projector: "b" * 64,
    )
    active_ids = sorted(record["id"] for record in records)
    active_ids_hash = hashlib.sha256(
        json.dumps(
            active_ids,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ).encode("utf-8")
    ).hexdigest()
    manifest = {
        "complete": True,
        "failedRecipeCount": 0,
        "sourceKey": "argiro",
        "detailSchemaVersion": ARGIRO_DETAIL_SCHEMA_VERSION,
        "outputRecipeCount": len(records),
        "activeRecipeCount": len(records),
        "detailRecipeCount": len(records),
        "sourcePayloadCount": len(records),
        "discoveredActiveRecipeCount": len(records),
        "activeIdsHash": active_ids_hash,
        "catalogHash": "a" * 64,
        "summaryHash": "b" * 64,
        "detailHash": "b" * 64,
        "sourcePayloadHash": "b" * 64,
        "oversizedDocumentCount": 0,
        "canonicalGreekRecipeCount": len(records),
        "canonicalAliasCount": len(aliases),
        "canonicalAliases": aliases,
        "canonicalAliasesHash": importer._manifest_value_hash(aliases),
        "externalRedirectExclusionCount": len(external),
        "externalRedirectExclusions": external,
        "externalRedirectExclusionsHash": importer._manifest_value_hash(external),
        "internalStaleRedirectExclusionCount": len(internal_stale),
        "internalStaleRedirectExclusions": internal_stale,
        "internalStaleRedirectExclusionsHash": importer._manifest_value_hash(internal_stale),
        "nonGreekStubExclusionCount": len(stubs),
        "nonGreekStubExclusions": stubs,
        "nonGreekStubExclusionsHash": importer._manifest_value_hash(stubs),
        "excludedRecipeUrlCount": len(exclusions),
        "excludedRecipeUrls": exclusions,
        "excludedRecipeUrlsHash": importer._manifest_value_hash(exclusions),
        "parserContractHash": parser_contract_hash(),
        "checkpointRunKey": checkpoint_run_key(),
        "discoveredRecipeUrlCount": len(records) + len(aliases) + len(exclusions),
        "duplicateRecipeEntryCount": 0,
        "declaredRecipeEntryCount": len(records) + len(aliases) + len(exclusions),
    }
    importer.validate_manifest(records, manifest)

    for field, invalid in (
        ("canonicalAliasesHash", "0" * 64),
        ("excludedRecipeUrlCount", len(exclusions) - 1),
        ("parserContractHash", "0" * 64),
        ("declaredRecipeEntryCount", manifest["declaredRecipeEntryCount"] + 1),
    ):
        with pytest.raises(CatalogError, match="manifest/catalog mismatch"):
            importer.validate_manifest(records, manifest | {field: invalid})


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
