"""Synthetic tests for the frozen Gastronomos checkpoint migration policy."""

from __future__ import annotations

import copy
from pathlib import Path

import pytest

from tools.recipe_importer import migrate_gastronomos_checkpoint as migration
from tools.recipe_importer.checkpoint_migration import (
    CheckpointMigrationError,
    CheckpointRecord,
    CheckpointSnapshot,
)


URL = "https://www.gastronomos.gr/syntagh/synthetic/203399/"
LASTMOD = "2026-01-03"


def _payload() -> dict:
    return {
        "id": "gastronomos_203399",
        "sourceRecipeId": 203399,
        "sourceKey": "gastronomos",
        "source": "gastronomos.gr",
        "providerRecipeId": "203399",
        "sourceUrl": URL,
        "canonicalUrl": URL,
        "sitemapLastModified": LASTMOD,
        "language": "el",
        "active": True,
        "title": "Συνθετική συνταγή",
        "sourcePayload": {
            "jsonLd": {
                "@type": "Recipe",
                "name": "Συνθετική συνταγή",
                "recipeIngredient": ["1 συνθετικό υλικό"],
                "recipeInstructions": [
                    {"@type": "HowToStep", "text": "Ανακατεύουμε."}
                ],
            },
            "htmlMetadata": {"documentLanguage": "el"},
        },
    }


def _record(payload: dict | None = None) -> CheckpointRecord:
    return CheckpointRecord(
        url=URL,
        lastmod=LASTMOD,
        payload=payload if payload is not None else _payload(),
    )


def _install_identity_validator(monkeypatch) -> None:
    def validate(value, sitemap_url, sitemap_last_modified):
        assert sitemap_url == URL
        assert sitemap_last_modified == LASTMOD
        return dict(value)

    monkeypatch.setattr(migration, "validate_checkpoint_record", validate)


def test_identical_re_normalization_is_reused(monkeypatch):
    stored = _payload()
    _install_identity_validator(monkeypatch)

    def normalize(recipe, metadata, **identity):
        assert recipe == stored["sourcePayload"]["jsonLd"]
        assert metadata == stored["sourcePayload"]["htmlMetadata"]
        assert recipe is not stored["sourcePayload"]["jsonLd"]
        assert metadata is not stored["sourcePayload"]["htmlMetadata"]
        assert identity == {
            "source_url": URL,
            "sitemap_last_modified": LASTMOD,
            "active": True,
        }
        # Reverse top-level insertion order to prove comparison is canonical.
        return dict(reversed(list(stored.items())))

    monkeypatch.setattr(
        migration.gastronomos_schema,
        "normalize_gastronomos_payload",
        normalize,
        raising=False,
    )

    assert migration.validate_gastronomos_migration_record(_record(stored)) == stored


def test_derived_taxonomy_allowlist_is_exact():
    assert migration.DERIVED_TAXONOMY_FIELDS == {
        "categoryKeys",
        "category",
        "categoryLabel",
        "dietLabels",
        "mealTypeLabels",
        "occasionLabels",
        "methodLabels",
        "cuisineLabels",
        "ingredientLabels",
        "filterAssociations",
    }


def test_derived_taxonomy_refresh_is_reused(monkeypatch):
    stored = _payload()
    stored.update({
        "categoryKeys": ["other"],
        "category": "other",
        "categoryLabel": "Άλλο",
        "dietLabels": [],
        "mealTypeLabels": [],
        "occasionLabels": [],
        "methodLabels": [],
        "cuisineLabels": [],
        "ingredientLabels": [],
        "filterAssociations": {
            "diet": [],
            "meal_type": [],
            "occasion": [],
            "method": [],
            "cuisine": [],
            "ingredient": [],
        },
    })
    refreshed = copy.deepcopy(stored)
    refreshed.update({
        "categoryKeys": ["pasta_rice", "vegetables"],
        "category": "pasta_rice",
        "categoryLabel": "Ζυμαρικά / Ρύζι",
        "dietLabels": ["Vegan"],
        "mealTypeLabels": ["Κυρίως Γεύμα"],
        "occasionLabels": ["Σαρακοστή"],
        "methodLabels": ["BBQ"],
        "cuisineLabels": ["Ιταλική Κουζίνα"],
        "ingredientLabels": ["Ζυμαρικά", "Ντομάτα"],
        "filterAssociations": {
            "diet": [{"id": "vegan", "title": "Vegan"}],
            "meal_type": [
                {"id": "κυριωσ-γευμα", "title": "Κυρίως Γεύμα"}
            ],
            "occasion": [{"id": "σαρακοστη", "title": "Σαρακοστή"}],
            "method": [{"id": "bbq", "title": "BBQ"}],
            "cuisine": [
                {"id": "ιταλικη-κουζινα", "title": "Ιταλική Κουζίνα"}
            ],
            "ingredient": [
                {"id": "ζυμαρικα", "title": "Ζυμαρικά"},
                {"id": "ντοματα", "title": "Ντομάτα"},
            ],
        },
    })
    _install_identity_validator(monkeypatch)
    monkeypatch.setattr(
        migration.gastronomos_schema,
        "normalize_gastronomos_payload",
        lambda recipe, metadata, **identity: refreshed,
        raising=False,
    )

    assert (
        migration.validate_gastronomos_migration_record(_record(stored))
        == refreshed
    )


@pytest.mark.parametrize(
    "mutation",
    [
        lambda value: value.update(title="changed"),
        lambda value: value.update(tags=["changed"]),
        lambda value: value.update(categorySourceId=999),
        lambda value: value.update(
            imageUrl="https://www.gastronomos.gr/changed.jpg"
        ),
        lambda value: value.update(unexpectedField="changed"),
    ],
)
def test_non_derived_re_normalization_change_is_skipped(
    monkeypatch,
    mutation,
):
    stored = _payload()
    altered = copy.deepcopy(stored)
    mutation(altered)
    _install_identity_validator(monkeypatch)
    monkeypatch.setattr(
        migration.gastronomos_schema,
        "normalize_gastronomos_payload",
        lambda recipe, metadata, **identity: altered,
        raising=False,
    )

    assert migration.validate_gastronomos_migration_record(_record(stored)) is None


def test_source_payload_change_is_skipped_and_cannot_mutate_stored_evidence(
    monkeypatch,
):
    stored = _payload()
    original = copy.deepcopy(stored)
    _install_identity_validator(monkeypatch)

    def normalize(recipe, metadata, **identity):
        recipe["keywords"] = "changed"
        altered = copy.deepcopy(stored)
        altered["sourcePayload"] = {
            "jsonLd": recipe,
            "htmlMetadata": metadata,
        }
        return altered

    monkeypatch.setattr(
        migration.gastronomos_schema,
        "normalize_gastronomos_payload",
        normalize,
        raising=False,
    )

    assert migration.validate_gastronomos_migration_record(_record(stored)) is None
    assert stored == original


@pytest.mark.parametrize(
    "mutation",
    [
        lambda value: value.pop("sourcePayload"),
        lambda value: value["sourcePayload"].pop("jsonLd"),
        lambda value: value["sourcePayload"].pop("htmlMetadata"),
        lambda value: value["sourcePayload"]["jsonLd"].update(
            recipeIngredient=[]
        ),
        lambda value: value["sourcePayload"]["jsonLd"].update(
            recipeInstructions=[]
        ),
    ],
)
def test_incomplete_primary_evidence_is_skipped_without_normalizing(
    monkeypatch,
    mutation,
):
    stored = _payload()
    mutation(stored)
    monkeypatch.setattr(
        migration.gastronomos_schema,
        "normalize_gastronomos_payload",
        lambda *args, **kwargs: pytest.fail("incomplete row must be refetched"),
        raising=False,
    )

    assert migration.validate_gastronomos_migration_record(_record(stored)) is None


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("id", "gastronomos_999"),
        ("sourceRecipeId", 999),
        ("sourceKey", "akis"),
        ("source", "example.test"),
        ("providerRecipeId", "999"),
        ("sourceUrl", "https://www.gastronomos.gr/syntagh/other/203399/"),
        ("canonicalUrl", "https://www.gastronomos.gr/syntagh/other/203399/"),
        ("sitemapLastModified", "2026-01-04"),
        ("language", "en"),
        ("active", False),
    ],
)
def test_stored_identity_mismatch_fails_closed(field, value):
    stored = _payload()
    stored[field] = value

    with pytest.raises(CheckpointMigrationError, match="identity"):
        migration.validate_gastronomos_migration_record(_record(stored))


def test_current_identity_validator_failure_is_not_silently_skipped(monkeypatch):
    stored = _payload()
    monkeypatch.setattr(
        migration.gastronomos_schema,
        "normalize_gastronomos_payload",
        lambda recipe, metadata, **identity: stored,
        raising=False,
    )

    def reject(*args, **kwargs):
        raise migration.FullSchemaError("normalized identity mismatch")

    monkeypatch.setattr(migration, "validate_checkpoint_record", reject)
    with pytest.raises(migration.FullSchemaError, match="identity mismatch"):
        migration.validate_gastronomos_migration_record(_record(stored))


def test_wrapper_binds_exact_frozen_snapshot_and_current_run_key(
    monkeypatch,
    tmp_path,
):
    source = tmp_path / "source.sqlite3"
    destination = tmp_path / "destination.sqlite3"
    snapshot = CheckpointSnapshot(
        run_key=migration.FROZEN_SOURCE_RUN_KEY,
        digest=migration.FROZEN_SOURCE_DIGEST,
        record_count=migration.FROZEN_SOURCE_RECORD_COUNT,
        metadata_count=migration.FROZEN_SOURCE_METADATA_COUNT,
    )
    destination_key = "d" * 64
    sentinel = object()
    observed = {}
    monkeypatch.setattr(migration, "snapshot_checkpoint", lambda path: snapshot)
    monkeypatch.setattr(migration, "checkpoint_run_key", lambda: destination_key)
    monkeypatch.setattr(
        migration.gastronomos_schema,
        "normalize_gastronomos_payload",
        lambda *args, **kwargs: {},
        raising=False,
    )

    def migrate(*args, **kwargs):
        observed["args"] = args
        observed["kwargs"] = kwargs
        return sentinel

    monkeypatch.setattr(migration, "migrate_checkpoint", migrate)

    assert (
        migration.migrate_frozen_gastronomos_checkpoint(source, destination)
        is sentinel
    )
    assert observed["args"] == (source, destination)
    assert observed["kwargs"] == {
        "expected_source_run_key": migration.FROZEN_SOURCE_RUN_KEY,
        "expected_source_digest": migration.FROZEN_SOURCE_DIGEST,
        "destination_run_key": destination_key,
        "validate_record": migration.validate_gastronomos_migration_record,
    }


@pytest.mark.parametrize(
    "changed",
    [
        {"run_key": "0" * 64},
        {"digest": "0" * 64},
        {"record_count": 12_324},
        {"metadata_count": 2},
    ],
)
def test_wrapper_rejects_any_frozen_snapshot_drift(monkeypatch, tmp_path, changed):
    fields = {
        "run_key": migration.FROZEN_SOURCE_RUN_KEY,
        "digest": migration.FROZEN_SOURCE_DIGEST,
        "record_count": migration.FROZEN_SOURCE_RECORD_COUNT,
        "metadata_count": migration.FROZEN_SOURCE_METADATA_COUNT,
    }
    fields.update(changed)
    monkeypatch.setattr(
        migration,
        "snapshot_checkpoint",
        lambda path: CheckpointSnapshot(**fields),
    )
    monkeypatch.setattr(
        migration,
        "migrate_checkpoint",
        lambda *args, **kwargs: pytest.fail("drift must fail before migration"),
    )

    with pytest.raises(CheckpointMigrationError, match="exact frozen"):
        migration.migrate_frozen_gastronomos_checkpoint(
            tmp_path / "source.sqlite3",
            tmp_path / "destination.sqlite3",
        )


def test_cli_requires_explicit_source_and_destination():
    with pytest.raises(SystemExit):
        migration.build_parser().parse_args([])
