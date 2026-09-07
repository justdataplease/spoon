import copy
from types import SimpleNamespace

import pytest

from tools.recipe_importer.migrate_firestore_taxonomy import (
    COLLECTION_FIELDS, INGREDIENT_TAXONOMY_VERSION, commit_plan, digest,
    publish_status, taxonomy_patch, validate_plan,
)


def plan():
    documents = []
    for collection, fields in COLLECTION_FIELDS.items():
        expected = {field: [] for field in fields}
        expected.update(category="vegetables", categoryLabel="Λαχανικά", ingredientLabels=["Μελιτζάνα"])
        before = {**expected, "ingredientLabels": ["ΜΕΛΙΤΖΑΝΑ"]}
        documents.append(dict(collection=collection, id="123", sourceKey="akis",
                              updateTime="2026-09-08T00:00:00.123456789Z", before=before,
                              expected=expected, patch={"ingredientLabels": ["Μελιτζάνα"]}))
    return dict(version=1, projectId="spoontheplanner", ingredientTaxonomyVersion=INGREDIENT_TAXONOMY_VERSION,
                sourceCounts={"akis": 1}, documents=documents)


def test_patch_is_idempotent_and_preserves_every_unrelated_field():
    target = dict(sourceKey="akis", providerRecipeId="123", canonicalUrl="https://example/123", active=True,
                  category="vegetables", categoryLabel="Λαχανικά", ingredientLabels=["Μελιτζάνα"], tags=[])
    live = {**target, "ingredientLabels": ["Μελιτζάνες"], "rating": 8, "notes": ["keep"]}
    assert taxonomy_patch(live, target, collection="spoon_recipes") == {"ingredientLabels": ["Μελιτζάνα"]}
    assert taxonomy_patch(target, target, collection="spoon_recipes") == {}
    for field in ("sourceKey", "providerRecipeId", "canonicalUrl", "active"):
        with pytest.raises(ValueError, match="identity"):
            taxonomy_patch({**live, field: None}, target, collection="spoon_recipes")


@pytest.mark.parametrize("mutation", [
    lambda p: p.update(projectId="different-project"),
    lambda p: p["documents"][0].update(collection="spoon_recipe_payloads"),
    lambda p: p["documents"][0].update(id="user/private"),
    lambda p: p["documents"][0]["patch"].update(rating=1),
    lambda p: p["documents"][0]["patch"].update(ingredientLabels=["wrong"]),
    lambda p: p["documents"].pop(),
])
def test_unsafe_plans_fail_before_any_writes(mutation):
    value = plan()
    mutation(value)
    with pytest.raises(ValueError):
        validate_plan(value, "spoontheplanner", digest(value))


class Client:
    project = "spoontheplanner"
    def __init__(self, fail=False): self.writes, self.commits, self.fail = [], 0, fail
    def collection(self, name): return SimpleNamespace(document=lambda key: f"{name}/{key}")
    def write_option(self, **kwargs): return kwargs
    def batch(self): return self
    def update(self, ref, patch, option): self.writes.append((ref, patch, option))
    def set(self, ref, payload, merge): self.writes.append((ref, payload, merge))
    def commit(self, **kwargs):
        self.commits += 1
        if self.fail: raise RuntimeError("concurrent edit")


def test_commit_uses_update_time_guards_and_only_changed_taxonomy_masks():
    value = plan(); client = Client()
    assert commit_plan(client, value, digest(value), batch_size=1) == 2
    assert client.commits == 2
    for ref, patch, option in client.writes:
        assert ref.startswith(("spoon_recipes/", "spoon_recipe_details/"))
        assert set(patch) == {"ingredientLabels"}
        assert option["last_update_time"].nanosecond == 123456789
    with pytest.raises(ValueError): commit_plan(Client(), value, "incorrect-hash")


def test_batch_failure_stops_without_publishing_catalog_status():
    client = Client(fail=True); value = plan()
    with pytest.raises(RuntimeError, match="concurrent edit"):
        commit_plan(client, value, digest(value), batch_size=1)
    assert len(client.writes) == 1
    assert client.commits == 1


def test_status_uses_existing_provider_paths_and_invalidates_stale_document_hashes():
    client = Client(); publish_status(client, plan())
    assert {row[0] for row in client.writes} == {"spoon_catalog/status_akis", "spoon_catalog/status"}
    assert client.commits == 1
    for _, payload, merge in client.writes:
        assert merge is True
        assert payload["summaryHash"] is None
        assert payload["detailHash"] is None
        assert "catalogHash" not in payload
        assert "sourcePayloadHash" not in payload
