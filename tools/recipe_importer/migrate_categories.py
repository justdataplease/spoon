"""Audit and safely migrate only derived recipe category fields.

The command is Firestore-free by default. A commit uses merge writes for the
summary and detail collections, never touches raw source payload documents, and
publishes corrected catalog hashes only after every category batch succeeds.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from collections import Counter
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Any

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from full_schema import (  # type: ignore[import-not-found]
        DETAIL_SCHEMA_VERSION,
        FACET_KEYS,
        collection_hash,
        ensure_full_record,
        firestore_detail_payload,
        firestore_recipe_payload,
        firestore_source_payload,
        normalize_recipe_detail,
    )
    from import_catalog import (  # type: ignore[import-not-found]
        COLLECTION_NAME,
        DETAIL_COLLECTION_NAME,
        MAX_BATCH_SIZE,
        STATUS_COLLECTION_NAME,
        STATUS_DOCUMENT_ID,
        compute_catalog_hash,
        create_firestore_client,
        load_catalog,
        load_manifest,
    )
else:
    from .full_schema import (
        DETAIL_SCHEMA_VERSION,
        FACET_KEYS,
        collection_hash,
        ensure_full_record,
        firestore_detail_payload,
        firestore_recipe_payload,
        firestore_source_payload,
        normalize_recipe_detail,
    )
    from .import_catalog import (
        COLLECTION_NAME,
        DETAIL_COLLECTION_NAME,
        MAX_BATCH_SIZE,
        STATUS_COLLECTION_NAME,
        STATUS_DOCUMENT_ID,
        compute_catalog_hash,
        create_firestore_client,
        load_catalog,
        load_manifest,
    )


CATEGORY_FIELDS = ("categoryKeys", "category", "categoryLabel", "tags")


class CategoryMigrationError(ValueError):
    """The baseline catalog cannot be safely audited or patched."""


def _canonical_hash(value: object) -> str:
    encoded = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _record_id(record: Mapping[str, Any]) -> str:
    recipe_id = str(record.get("id") or "")
    if not recipe_id.isdigit():
        raise CategoryMigrationError(f"invalid numeric recipe id: {recipe_id!r}")
    return recipe_id


def _validate_manifest_baseline(
    records: Sequence[Mapping[str, Any]],
    manifest: Mapping[str, Any],
) -> dict[str, int]:
    """Validate the exact old catalog and every captured taxonomy association."""

    if not manifest.get("complete") or manifest.get("failedRecipeCount") != 0:
        raise CategoryMigrationError(
            "manifest does not certify a complete zero-failure crawl"
        )
    if manifest.get("detailSchemaVersion") != DETAIL_SCHEMA_VERSION:
        raise CategoryMigrationError("manifest detail schema version is unsupported")

    ids = [_record_id(record) for record in records]
    if len(ids) != len(set(ids)):
        raise CategoryMigrationError("catalog contains duplicate recipe ids")
    active_ids = sorted(
        (
            _record_id(record)
            for record in records
            if record.get("active") is True
        ),
        key=int,
    )
    active_ids_hash = _canonical_hash(active_ids)
    raw_records = [dict(record) for record in records]
    expected_manifest = {
        "outputRecipeCount": len(records),
        "activeRecipeCount": len(active_ids),
        "discoveredActiveRecipeCount": len(active_ids),
        "apiReportedActiveRecipeCount": len(active_ids),
        "detailRecipeCount": len(records),
        "sourcePayloadCount": len(records),
        "activeIdsHash": active_ids_hash,
        "catalogHash": compute_catalog_hash(raw_records),
        "summaryHash": collection_hash(raw_records, firestore_recipe_payload),
        "detailHash": collection_hash(raw_records, firestore_detail_payload),
        "sourcePayloadHash": collection_hash(raw_records, firestore_source_payload),
    }
    mismatches = [
        key for key, expected in expected_manifest.items()
        if manifest.get(key) != expected
    ]
    if mismatches:
        raise CategoryMigrationError(
            "manifest/catalog baseline mismatch: " + ", ".join(sorted(mismatches))
        )

    taxonomy = manifest.get("taxonomy")
    if not isinstance(taxonomy, Mapping):
        raise CategoryMigrationError("manifest taxonomy must be an object")
    taxonomy_options: dict[str, dict[str, str]] = {}
    option_count = 0
    for facet in FACET_KEYS:
        raw_options = taxonomy.get(facet)
        if not isinstance(raw_options, list):
            raise CategoryMigrationError(f"manifest taxonomy.{facet} must be a list")
        options: dict[str, str] = {}
        for raw_option in raw_options:
            if not isinstance(raw_option, Mapping):
                raise CategoryMigrationError(
                    f"manifest taxonomy.{facet} contains a non-object"
                )
            option_id = str(raw_option.get("id") or "").strip()
            title = str(raw_option.get("title") or "").strip()
            if not option_id or not title or option_id in options:
                raise CategoryMigrationError(
                    f"manifest taxonomy.{facet} has an invalid/duplicate option"
                )
            options[option_id] = title
        taxonomy_options[facet] = options
        option_count += len(options)
    if manifest.get("taxonomyOptionCount") != option_count:
        raise CategoryMigrationError("manifest taxonomyOptionCount is inconsistent")
    if manifest.get("taxonomyHash") != _canonical_hash(taxonomy):
        raise CategoryMigrationError("manifest taxonomyHash is inconsistent")

    membership_count = 0
    source_id_to_slug: dict[str, str] = {}
    source_slug_to_id: dict[str, str] = {}
    for record in records:
        recipe_id = _record_id(record)
        payload = record.get("sourcePayload")
        associations = record.get("filterAssociations")
        if not isinstance(payload, Mapping) or not isinstance(associations, Mapping):
            raise CategoryMigrationError(
                f"recipe {recipe_id} has no canonical source payload/associations"
            )
        source_category = payload.get("category")
        if not isinstance(source_category, Mapping):
            raise CategoryMigrationError(
                f"recipe {recipe_id} has no official source category"
            )
        source_id = str(source_category.get("id") or "").strip()
        source_slug = str(source_category.get("slug") or "").strip()
        if not source_id.isdigit() or not source_slug:
            raise CategoryMigrationError(
                f"recipe {recipe_id} has invalid official category id/slug"
            )
        if record.get("categorySourceId") != int(source_id):
            raise CategoryMigrationError(
                f"recipe {recipe_id} categorySourceId differs from sourcePayload"
            )
        if source_id in source_id_to_slug and source_id_to_slug[source_id] != source_slug:
            raise CategoryMigrationError(
                f"official category id {source_id} has conflicting slugs"
            )
        if source_slug in source_slug_to_id and source_slug_to_id[source_slug] != source_id:
            raise CategoryMigrationError(
                f"official category slug {source_slug!r} has conflicting ids"
            )
        source_id_to_slug[source_id] = source_slug
        source_slug_to_id[source_slug] = source_id

        for facet in FACET_KEYS:
            values = associations.get(facet)
            if not isinstance(values, list):
                raise CategoryMigrationError(
                    f"recipe {recipe_id} associations.{facet} must be a list"
                )
            seen_options: set[str] = set()
            for raw_option in values:
                if not isinstance(raw_option, Mapping):
                    raise CategoryMigrationError(
                        f"recipe {recipe_id} associations.{facet} has a non-object"
                    )
                option_id = str(raw_option.get("id") or "").strip()
                title = str(raw_option.get("title") or "").strip()
                if option_id in seen_options:
                    raise CategoryMigrationError(
                        f"recipe {recipe_id} repeats {facet}={option_id}"
                    )
                seen_options.add(option_id)
                if taxonomy_options[facet].get(option_id) != title:
                    raise CategoryMigrationError(
                        f"recipe {recipe_id} has unknown/mismatched "
                        f"{facet}={option_id}:{title!r}"
                    )
                membership_count += 1
    if manifest.get("facetAssociationMembershipCount") != membership_count:
        raise CategoryMigrationError(
            "manifest facetAssociationMembershipCount is inconsistent"
        )
    return {
        "activeRecipeCount": len(active_ids),
        "facetAssociationMembershipCount": membership_count,
        "observedSourceCategoryCount": len(source_id_to_slug),
        "taxonomyOptionCount": option_count,
    }


def _corrected_record(record: Mapping[str, Any]) -> dict[str, Any]:
    recipe_id = _record_id(record)
    payload = record.get("sourcePayload")
    associations = record.get("filterAssociations")
    if not isinstance(payload, Mapping) or not isinstance(associations, Mapping):
        raise CategoryMigrationError(
            f"recipe {recipe_id} has no source payload/associations"
        )
    try:
        normalized = normalize_recipe_detail(
            payload,
            source_url=str(record.get("sourceUrl") or ""),
            sitemap_last_modified=str(record.get("sitemapLastModified") or ""),
            associations=associations,
            active=record.get("active") is True,
        )
    except (TypeError, ValueError) as exc:
        raise CategoryMigrationError(
            f"recipe {recipe_id} cannot be reclassified: {exc}"
        ) from exc
    corrected = dict(record)
    corrected.update({field: normalized[field] for field in CATEGORY_FIELDS})
    try:
        ensure_full_record(corrected)
    except ValueError as exc:
        raise CategoryMigrationError(
            f"recipe {recipe_id} corrected record is invalid: {exc}"
        ) from exc
    return corrected


def analyze_category_migration(
    records: Sequence[Mapping[str, Any]],
    manifest: Mapping[str, Any],
    *,
    sample_limit: int = 20,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], dict[str, Any]]:
    baseline = _validate_manifest_baseline(records, manifest)
    corrected_records: list[dict[str, Any]] = []
    changes: list[dict[str, Any]] = []
    before_counts: Counter[str] = Counter()
    after_counts: Counter[str] = Counter()
    transitions: Counter[str] = Counter()
    changed_fields: Counter[str] = Counter()

    for record in records:
        corrected = _corrected_record(record)
        corrected_records.append(corrected)
        before = str(record.get("category") or "unclassified")
        after = str(corrected.get("category") or "unclassified")
        before_counts[before] += 1
        after_counts[after] += 1
        patch = {
            field: corrected[field]
            for field in CATEGORY_FIELDS
            if record.get(field) != corrected[field]
        }
        if not patch:
            continue
        for field in patch:
            changed_fields[field] += 1
        transitions[f"{before}->{after}"] += 1
        source_category = corrected["sourcePayload"]["category"]
        associations = corrected["filterAssociations"]
        changes.append(
            {
                "id": corrected["id"],
                "title": corrected["title"],
                "sourceCategoryId": str(source_category.get("id") or ""),
                "sourceCategoryParentId": str(
                    source_category.get("parent_id") or ""
                ),
                "sourceCategorySlug": str(source_category.get("slug") or ""),
                "ingredientFacetIds": [
                    item["id"] for item in associations["ingredient"]
                ],
                "mealTypeFacetIds": [
                    item["id"] for item in associations["meal_type"]
                ],
                "before": {
                    field: record.get(field) for field in CATEGORY_FIELDS
                },
                "after": {
                    field: corrected[field] for field in CATEGORY_FIELDS
                },
                "patch": patch,
            }
        )

    source_hash_before = manifest["sourcePayloadHash"]
    source_hash_after = collection_hash(
        corrected_records,
        firestore_source_payload,
    )
    if source_hash_after != source_hash_before:
        raise CategoryMigrationError(
            "category migration would unexpectedly change source payload hashes"
        )
    corrected_hashes = {
        "catalogHash": compute_catalog_hash(corrected_records),
        "summaryHash": collection_hash(
            corrected_records,
            firestore_recipe_payload,
        ),
        "detailHash": collection_hash(
            corrected_records,
            firestore_detail_payload,
        ),
        "sourcePayloadHash": source_hash_after,
    }
    reported = next(
        (
            {
                key: value
                for key, value in change.items()
                if key != "patch"
            }
            for change in changes
            if change["id"] == "3485"
        ),
        None,
    )
    report = {
        "catalogRecords": len(records),
        **baseline,
        "changedRecipeCount": len(changes),
        "unchangedRecipeCount": len(records) - len(changes),
        "summaryWrites": len(changes),
        "detailWrites": len(changes),
        "sourcePayloadWrites": 0,
        "categoryCountsBefore": dict(sorted(before_counts.items())),
        "categoryCountsAfter": dict(sorted(after_counts.items())),
        "categoryTransitions": dict(sorted(transitions.items())),
        "changedFieldCounts": dict(sorted(changed_fields.items())),
        "correctedHashes": corrected_hashes,
        "reportedRecipe3485": reported,
        "samples": [
            {key: value for key, value in change.items() if key != "patch"}
            for change in changes[: max(0, sample_limit)]
        ],
    }
    return corrected_records, changes, report


def commit_category_migration(
    client: Any,
    changes: Sequence[Mapping[str, Any]],
    corrected_hashes: Mapping[str, str],
    *,
    batch_size: int,
    server_timestamp: object,
) -> tuple[int, int]:
    """Merge category fields into summary/details; raw payloads are untouched."""

    if not 1 <= batch_size <= MAX_BATCH_SIZE:
        raise CategoryMigrationError("batch size must be between 1 and 500")
    if server_timestamp is None:
        raise CategoryMigrationError("a Firestore server timestamp is required")
    writes: list[tuple[Any, Mapping[str, Any]]] = []
    for change in changes:
        recipe_id = str(change["id"])
        patch = change["patch"]
        if (
            not recipe_id.isdigit()
            or not isinstance(patch, Mapping)
            or set(patch) - set(CATEGORY_FIELDS)
        ):
            raise CategoryMigrationError("unsafe category patch")
        for collection_name in (COLLECTION_NAME, DETAIL_COLLECTION_NAME):
            reference = client.collection(collection_name).document(recipe_id)
            writes.append((reference, dict(patch)))

    batch_count = 0
    for offset in range(0, len(writes), batch_size):
        batch = client.batch()
        for reference, patch in writes[offset : offset + batch_size]:
            batch.set(reference, patch, merge=True)
        batch.commit()
        batch_count += 1

    if changes:
        status = {
            "catalogHash": corrected_hashes["catalogHash"],
            "catalogVersion": f"sha256:{corrected_hashes['catalogHash']}",
            "summaryHash": corrected_hashes["summaryHash"],
            "detailHash": corrected_hashes["detailHash"],
            "sourcePayloadHash": corrected_hashes["sourcePayloadHash"],
            "categoryMigrationCount": len(changes),
            "lastCategoryMigrationAt": server_timestamp,
        }
        batch = client.batch()
        reference = client.collection(STATUS_COLLECTION_NAME).document(
            STATUS_DOCUMENT_ID
        )
        batch.set(reference, status, merge=True)
        batch.commit()
        batch_count += 1
    return len(writes), batch_count


def _batch_size(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("batch size must be an integer") from exc
    if not 1 <= parsed <= MAX_BATCH_SIZE:
        raise argparse.ArgumentTypeError("batch size must be between 1 and 500")
    return parsed


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Audit official Akis taxonomy and optionally merge only corrected "
            "category fields into recipe summaries/details."
        )
    )
    parser.add_argument("catalog", type=Path)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--sample-limit", type=int, default=20)
    parser.add_argument(
        "--commit",
        action="store_true",
        help="Opt in to summary/detail category merge writes.",
    )
    parser.add_argument("--project-id")
    parser.add_argument("--credentials", type=Path)
    parser.add_argument("--batch-size", type=_batch_size, default=MAX_BATCH_SIZE)
    parser.add_argument(
        "--i-have-permission",
        action="store_true",
        help="Required because the command processes the authorized full catalog.",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if not args.i_have_permission:
            raise CategoryMigrationError(
                "full catalog audit requires --i-have-permission"
            )
        if args.sample_limit < 0:
            raise CategoryMigrationError("--sample-limit cannot be negative")
        records = load_catalog(args.catalog)
        _, changes, report = analyze_category_migration(
            records,
            load_manifest(args.manifest),
            sample_limit=args.sample_limit,
        )
        report["mode"] = "dry-run"
        report["documentWrites"] = 0
        report["statusWrites"] = 0
        if args.commit:
            if not args.project_id:
                raise CategoryMigrationError("--commit requires --project-id")
            client, server_timestamp = create_firestore_client(
                args.project_id,
                args.credentials,
            )
            writes, batches = commit_category_migration(
                client,
                changes,
                report["correctedHashes"],
                batch_size=args.batch_size,
                server_timestamp=server_timestamp,
            )
            report["mode"] = "commit"
            report["documentWrites"] = writes
            report["statusWrites"] = int(bool(changes))
            report["batches"] = batches
        print(json.dumps(report, ensure_ascii=False, sort_keys=True))
        return 0
    except (CategoryMigrationError, OSError, RuntimeError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
