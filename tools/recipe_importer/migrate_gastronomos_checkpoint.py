"""Migrate one immutable Gastronomos checkpoint without weakening validation.

Only the exact diagnostic snapshot named below is eligible.  Each cached row
must contain complete JSON-LD ingredients and instructions, must preserve its
source payload exactly, and must still pass the current checkpoint identity
validator.  Re-normalization may refresh only explicitly derived taxonomy
fields.  Every other changed or incomplete row is deliberately omitted so the
next crawl fetches it again.
"""

from __future__ import annotations

import argparse
from collections.abc import Mapping, Sequence
import copy
import json
from pathlib import Path
import sqlite3
import sys
from typing import Any

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import gastronomos_schema  # type: ignore[import-not-found]
    from checkpoint_migration import (  # type: ignore[import-not-found]
        CheckpointMigrationError,
        CheckpointMigrationResult,
        CheckpointRecord,
        CheckpointSnapshot,
        migrate_checkpoint,
        snapshot_checkpoint,
    )
    from crawl_gastronomos import (  # type: ignore[import-not-found]
        canonical_recipe_location,
        checkpoint_run_key,
        validate_checkpoint_record,
    )
    from full_schema import FullSchemaError  # type: ignore[import-not-found]
    from providers import (  # type: ignore[import-not-found]
        GASTRONOMOS,
        ProviderError,
        canonical_recipe_url,
        recipe_document_id,
    )
else:
    from . import gastronomos_schema
    from .checkpoint_migration import (
        CheckpointMigrationError,
        CheckpointMigrationResult,
        CheckpointRecord,
        CheckpointSnapshot,
        migrate_checkpoint,
        snapshot_checkpoint,
    )
    from .crawl_gastronomos import (
        canonical_recipe_location,
        checkpoint_run_key,
        validate_checkpoint_record,
    )
    from .full_schema import FullSchemaError
    from .providers import (
        GASTRONOMOS,
        ProviderError,
        canonical_recipe_url,
        recipe_document_id,
    )


FROZEN_SOURCE_RUN_KEY = (
    "58a96fff36f4b074e68358ac4cbcf4a69eada7d604f1777547d37cd3fe921b85"
)
FROZEN_SOURCE_DIGEST = (
    "96fdabdaa2a605fbb6399b47c6001ac51a078dfcab91af2cea39a12bfc73f1f0"
)
FROZEN_SOURCE_RECORD_COUNT = 12_325
FROZEN_SOURCE_METADATA_COUNT = 1
DERIVED_TAXONOMY_FIELDS = frozenset({
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
})


def _canonical_record_bytes(value: Mapping[str, object]) -> bytes:
    return json.dumps(
        dict(value),
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def _without_derived_taxonomy_fields(
    value: Mapping[str, object],
) -> dict[str, object]:
    return {
        key: item
        for key, item in value.items()
        if key not in DERIVED_TAXONOMY_FIELDS
    }


def _require_frozen_snapshot(snapshot: CheckpointSnapshot) -> None:
    if (
        snapshot.run_key != FROZEN_SOURCE_RUN_KEY
        or snapshot.digest != FROZEN_SOURCE_DIGEST
        or snapshot.record_count != FROZEN_SOURCE_RECORD_COUNT
        or snapshot.metadata_count != FROZEN_SOURCE_METADATA_COUNT
    ):
        raise CheckpointMigrationError(
            "source checkpoint is not the exact frozen Gastronomos snapshot"
        )


def _require_stored_identity(record: CheckpointRecord) -> None:
    value = record.payload
    provider_id = value.get("providerRecipeId")
    source_recipe_id = value.get("sourceRecipeId")
    expected_url = canonical_recipe_location(record.url)
    try:
        canonical_url = canonical_recipe_url(
            GASTRONOMOS,
            record.url,
            provider_id,
        )
        expected_document_id = recipe_document_id(GASTRONOMOS, provider_id)
    except ProviderError as exc:
        raise CheckpointMigrationError(
            "stored Gastronomos checkpoint identity is invalid"
        ) from exc
    if (
        expected_url is None
        or expected_url != record.url
        or canonical_url != record.url
        or not isinstance(provider_id, str)
        or not provider_id.isdigit()
        or isinstance(source_recipe_id, bool)
        or not isinstance(source_recipe_id, int)
        or source_recipe_id != int(provider_id)
        or value.get("id") != expected_document_id
        or value.get("sourceKey") != GASTRONOMOS.key
        or value.get("source") != GASTRONOMOS.source
        or value.get("sourceUrl") != record.url
        or value.get("canonicalUrl") != record.url
        or value.get("sitemapLastModified") != record.lastmod
        or value.get("language") != "el"
        or value.get("active") is not True
    ):
        raise CheckpointMigrationError(
            "stored Gastronomos checkpoint identity does not match its row"
        )


def _complete_primary_jsonld(recipe: Mapping[str, Any]) -> bool:
    ingredients = gastronomos_schema._strings(recipe.get("recipeIngredient"))
    method_sections, _tips = gastronomos_schema._instruction_sections(
        recipe.get("recipeInstructions")
    )
    steps = [
        step
        for section in method_sections
        if isinstance(section, Mapping)
        for step in section.get("steps", [])
        if isinstance(step, str) and step.strip()
    ]
    return bool(ingredients and steps)


def validate_gastronomos_migration_record(
    record: CheckpointRecord,
) -> Mapping[str, object] | None:
    """Return a safely refreshed current record, or ``None`` to force refetch."""

    _require_stored_identity(record)
    source_payload = record.payload.get("sourcePayload")
    if not isinstance(source_payload, Mapping):
        return None
    recipe = source_payload.get("jsonLd")
    metadata = source_payload.get("htmlMetadata")
    if not isinstance(recipe, Mapping) or not isinstance(metadata, Mapping):
        return None
    if not _complete_primary_jsonld(recipe):
        return None

    # Freeze both comparisons before invoking parser code and pass defensive
    # copies to it. This prevents accidental input mutation from weakening
    # either the sourcePayload gate or the non-derived record comparison.
    stored_source_payload_bytes = _canonical_record_bytes(source_payload)
    stored_non_derived_bytes = _canonical_record_bytes(
        _without_derived_taxonomy_fields(record.payload)
    )
    normalizer_source_payload = copy.deepcopy(dict(source_payload))
    normalizer_recipe = normalizer_source_payload.get("jsonLd")
    normalizer_metadata = normalizer_source_payload.get("htmlMetadata")
    if not isinstance(normalizer_recipe, Mapping) or not isinstance(
        normalizer_metadata,
        Mapping,
    ):
        return None

    normalizer = getattr(
        gastronomos_schema,
        "normalize_gastronomos_payload",
        None,
    )
    if not callable(normalizer):
        raise CheckpointMigrationError(
            "current Gastronomos payload normalizer is unavailable"
        )
    try:
        normalized = normalizer(
            normalizer_recipe,
            normalizer_metadata,
            source_url=record.url,
            sitemap_last_modified=record.lastmod,
            active=True,
        )
    except FullSchemaError:
        return None
    if not isinstance(normalized, Mapping):
        raise CheckpointMigrationError(
            "Gastronomos payload normalizer did not return an object"
        )

    normalized_source_payload = normalized.get("sourcePayload")
    if (
        not isinstance(normalized_source_payload, Mapping)
        or _canonical_record_bytes(normalized_source_payload)
        != stored_source_payload_bytes
    ):
        return None

    # This performs the complete current schema validation as well as the
    # source/sitemap/document identity checks.  A mismatch here indicates a
    # broken normalizer contract, not a row that is safe to silently reuse.
    validated = validate_checkpoint_record(
        normalized,
        record.url,
        record.lastmod,
    )
    if _canonical_record_bytes(
        _without_derived_taxonomy_fields(validated)
    ) != stored_non_derived_bytes:
        return None
    return validated


def migrate_frozen_gastronomos_checkpoint(
    source_path: Path,
    destination_path: Path,
) -> CheckpointMigrationResult:
    """Create a new checkpoint containing only provably reusable rows."""

    source_path = Path(source_path)
    destination_path = Path(destination_path)
    snapshot = snapshot_checkpoint(source_path)
    _require_frozen_snapshot(snapshot)
    if not callable(
        getattr(gastronomos_schema, "normalize_gastronomos_payload", None)
    ):
        raise CheckpointMigrationError(
            "current Gastronomos payload normalizer is unavailable"
        )
    destination_run_key = checkpoint_run_key()
    return migrate_checkpoint(
        source_path,
        destination_path,
        expected_source_run_key=FROZEN_SOURCE_RUN_KEY,
        expected_source_digest=FROZEN_SOURCE_DIGEST,
        destination_run_key=destination_run_key,
        validate_record=validate_gastronomos_migration_record,
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Migrate the one frozen Gastronomos diagnostic checkpoint into "
            "a new current-parser checkpoint."
        )
    )
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--destination", type=Path, required=True)
    return parser


def _result_summary(result: CheckpointMigrationResult) -> dict[str, object]:
    return {
        "sourceDigest": result.source.digest,
        "sourceRecordCount": result.source.record_count,
        "sourceMetadataCount": result.source.metadata_count,
        "destinationDigest": result.destination.digest,
        "destinationRecordCount": result.destination.record_count,
        "destinationMetadataCount": result.destination.metadata_count,
        "migratedRecordCount": result.migrated_record_count,
        "skippedRecordCount": result.skipped_record_count,
    }


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        result = migrate_frozen_gastronomos_checkpoint(
            args.source,
            args.destination,
        )
    except (CheckpointMigrationError, OSError, sqlite3.Error) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(_result_summary(result), sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
