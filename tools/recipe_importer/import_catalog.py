"""Validate authorized metadata and optionally replace Firestore recipe documents."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
import time
from collections.abc import Iterable, Iterator, Mapping
from pathlib import Path
from typing import Any
from urllib.parse import unquote, urlsplit, urlunsplit

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from helpers import (  # type: ignore[import-not-found]
        classify_category_keys,
        classify_ease,
        canonical_category,
        derive_total_minutes,
        stable_random_key,
    )
    from full_schema import (  # type: ignore[import-not-found]
        DETAIL_SCHEMA_VERSION,
        FULL_OPTIONAL_FIELDS,
        FULL_REQUIRED_FIELDS,
        FullSchemaError,
        collection_hash,
        document_sizes,
        ensure_full_record,
        firestore_detail_payload,
        firestore_recipe_payload,
        firestore_source_payload,
        is_full_record,
    )
    from providers import (  # type: ignore[import-not-found]
        DOCUMENT_ID_RE,
        ProviderError,
        canonical_image_url,
        canonical_recipe_url,
        normalize_provider_recipe_id,
        provider_for,
        recipe_document_id,
        record_source_key,
        stable_recipe_random_key,
    )
else:
    from .helpers import (
        classify_category_keys,
        classify_ease,
        canonical_category,
        derive_total_minutes,
        stable_random_key,
    )
    from .full_schema import (
        DETAIL_SCHEMA_VERSION,
        FULL_OPTIONAL_FIELDS,
        FULL_REQUIRED_FIELDS,
        FullSchemaError,
        collection_hash,
        document_sizes,
        ensure_full_record,
        firestore_detail_payload,
        firestore_recipe_payload,
        firestore_source_payload,
        is_full_record,
    )
    from .providers import (
        DOCUMENT_ID_RE,
        ProviderError,
        canonical_image_url,
        canonical_recipe_url,
        normalize_provider_recipe_id,
        provider_for,
        recipe_document_id,
        record_source_key,
        stable_recipe_random_key,
    )


COLLECTION_NAME = "spoon_recipes"
DETAIL_COLLECTION_NAME = "spoon_recipe_details"
PAYLOAD_COLLECTION_NAME = "spoon_recipe_payloads"
STATUS_COLLECTION_NAME = "spoon_catalog"
STATUS_DOCUMENT_ID = "status"
MAX_BATCH_SIZE = 500
DEFAULT_BATCH_SIZE = 100
BATCH_COMMIT_MAX_ATTEMPTS = 3
BATCH_COMMIT_INITIAL_BACKOFF_SECONDS = 1.0
FULL_STATUS_METADATA_FIELDS = {
    'detailSchemaVersion',
    'activeRecipeCount',
    'retiredRecipeCount',
    'retiredOnCommitCount',
    'knownRetiredRecipeCount',
    'detailRecipeCount',
    'activeDetailRecipeCount',
    'sourcePayloadCount',
    'activeSourcePayloadCount',
    'summaryHash',
    'detailHash',
    'sourcePayloadHash',
    'maximumSummaryDocumentBytes',
    'maximumDetailDocumentBytes',
    'maximumSourcePayloadDocumentBytes',
    'oversizedDocumentCount',
}
ALLOWED_FIELDS = {
    "id",
    "title",
    "language",
    "source",
    "sourceKey",
    "providerRecipeId",
    "sourceRecipeId",
    "sourceUrl",
    "canonicalUrl",
    "categoryKeys",
    "prepMinutes",
    "cookMinutes",
    "totalMinutes",
    "preparationCount",
    "stepCount",
    "rating10",
    "ease",
    "sourceUpdatedAt",
    "active",
}
FULL_DERIVED_FIELDS = {"category", "rating", "tags", "sourceName", "randomKey"}
ALLOWED_FIELDS |= FULL_REQUIRED_FIELDS | FULL_OPTIONAL_FIELDS | FULL_DERIVED_FIELDS
VALID_EASE = {"unknown", "easy", "moderate", "involved"}
LICENSED_IMAGE_FIELD = "imageUrl"


class CatalogError(ValueError):
    """Raised for an unsafe or invalid catalog before any writes occur."""


def _is_transient_firestore_error(error: Exception) -> bool:
    """Recognize only retry-safe transport failures without requiring Firebase for dry runs."""

    try:
        from google.api_core.exceptions import DeadlineExceeded, ServiceUnavailable
    except ImportError:
        return False
    return isinstance(error, (DeadlineExceeded, ServiceUnavailable))


def _commit_batch_with_retry(batch: Any) -> Any:
    """Retry idempotent Firestore set batches after bounded transient failures."""

    for attempt in range(BATCH_COMMIT_MAX_ATTEMPTS):
        try:
            return batch.commit()
        except Exception as exc:
            final_attempt = attempt + 1 >= BATCH_COMMIT_MAX_ATTEMPTS
            if final_attempt or not _is_transient_firestore_error(exc):
                raise
            time.sleep(BATCH_COMMIT_INITIAL_BACKOFF_SECONDS * (2**attempt))
    raise AssertionError("unreachable")


def _json_records(value: object) -> Iterator[Mapping[str, Any]]:
    if isinstance(value, list):
        for item in value:
            if not isinstance(item, Mapping):
                raise CatalogError("every JSON array item must be an object")
            yield item
        return
    if isinstance(value, Mapping) and isinstance(value.get("recipes"), list):
        yield from _json_records(value["recipes"])
        return
    if isinstance(value, Mapping):
        yield value
        return
    raise CatalogError("JSON input must be an object, an array, or {'recipes': [...]} ")


def load_catalog(path: Path) -> list[Mapping[str, Any]]:
    if not path.is_file():
        raise CatalogError(f"catalog file does not exist: {path}")
    if path.suffix.casefold() in {".jsonl", ".ndjson"}:
        records: list[Mapping[str, Any]] = []
        with path.open("r", encoding="utf-8-sig") as handle:
            for line_number, line in enumerate(handle, start=1):
                if not line.strip():
                    continue
                try:
                    value = json.loads(line)
                except json.JSONDecodeError as exc:
                    raise CatalogError(f"invalid JSON on line {line_number}: {exc.msg}") from exc
                if not isinstance(value, Mapping):
                    raise CatalogError(f"JSONL line {line_number} must contain one object")
                records.append(value)
        return records
    try:
        value = json.loads(path.read_text(encoding="utf-8-sig"))
    except json.JSONDecodeError as exc:
        raise CatalogError(f"invalid JSON: {exc.msg}") from exc
    return list(_json_records(value))


def _optional_int(record: Mapping[str, Any], name: str, *, maximum: int = 100_000) -> int | None:
    value = record.get(name)
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, int) or value < 0 or value > maximum:
        raise CatalogError(f"{name} must be a non-negative integer no greater than {maximum}")
    return value


def _validate_source_url(value: object, provider_recipe_id: str, provider) -> str:
    try:
        return canonical_recipe_url(provider, value, provider_recipe_id)
    except ProviderError as exc:
        raise CatalogError(str(exc)) from exc


def _validate_legacy_akis_image_url(value: object) -> str:
    if not isinstance(value, str) or not value.strip():
        raise CatalogError("imageUrl must be a non-empty HTTPS URL")
    parts = urlsplit(value.strip())
    host = (parts.hostname or "").casefold().rstrip(".")
    if parts.scheme.casefold() != "https" or host not in ALLOWED_HOSTS:
        raise CatalogError("imageUrl must use HTTPS on akispetretzikis.com")
    if parts.username or parts.password or parts.port not in (None, 443):
        raise CatalogError("imageUrl must not contain credentials or a non-standard port")
    decoded_path = unquote(parts.path)
    path_segments = decoded_path.split("/")
    if (
        not decoded_path.startswith("/photos/")
        or decoded_path.endswith("/")
        or "\\" in decoded_path
        or any(segment in {".", ".."} for segment in path_segments)
    ):
        raise CatalogError("imageUrl path must identify a file below /photos/")
    if parts.query or parts.fragment:
        raise CatalogError("imageUrl must not contain a query string or fragment")
    return urlunsplit(("https", "akispetretzikis.com", parts.path, "", ""))


def _validate_image_url(value: object, provider) -> str:
    try:
        return canonical_image_url(provider, value)
    except ProviderError as exc:
        raise CatalogError(str(exc)) from exc


def validate_record(
    raw: Mapping[str, Any],
    *,
    allow_licensed_images: bool = False,
    have_permission: bool = False,
    have_argiro_permission: bool = False,
    have_gastronomos_permission: bool = False,
) -> dict[str, Any]:
    full_content = is_full_record(raw)
    if full_content and not have_permission:
        raise CatalogError("full recipe content requires explicit --i-have-permission")
    media_allowed = allow_licensed_images or (full_content and have_permission)
    if LICENSED_IMAGE_FIELD in raw and raw.get(LICENSED_IMAGE_FIELD) and not media_allowed:
        raise CatalogError("imageUrl requires the explicit --allow-licensed-images flag")
    allowed_fields = ALLOWED_FIELDS | ({LICENSED_IMAGE_FIELD} if media_allowed else set())
    unknown = set(raw) - allowed_fields
    if unknown:
        raise CatalogError(
            "unsupported fields: "
            + ", ".join(sorted(unknown))
        )
    if full_content:
        try:
            ensure_full_record(raw)
        except FullSchemaError as exc:
            raise CatalogError(str(exc)) from exc

    try:
        provider = provider_for(
            source_key=raw.get("sourceKey"),
            source=raw.get("source"),
            source_url=raw.get("sourceUrl"),
        )
        raw_provider_recipe_id = raw.get("providerRecipeId")
        if raw_provider_recipe_id is None:
            raw_provider_recipe_id = raw.get("sourceRecipeId")
        if raw_provider_recipe_id is None and provider.legacy_numeric_document_ids:
            raw_provider_recipe_id = raw.get("id")
        provider_recipe_id = normalize_provider_recipe_id(raw_provider_recipe_id)
        expected_document_id = recipe_document_id(provider, provider_recipe_id)
    except ProviderError as exc:
        raise CatalogError(str(exc)) from exc
    if full_content and provider.key == "argiro" and not have_argiro_permission:
        raise CatalogError(
            "Argiro full content requires explicit --i-have-argiro-permission"
        )
    if (
        full_content
        and provider.key == "gastronomos"
        and not have_gastronomos_permission
    ):
        raise CatalogError(
            "Gastronomos full content requires explicit "
            "--i-have-gastronomos-permission"
        )

    source_id = str(raw.get("id") or expected_document_id).strip()
    if not DOCUMENT_ID_RE.fullmatch(source_id):
        raise CatalogError("id must contain only letters, digits, '_' or '-' (maximum 128)")
    if source_id != expected_document_id:
        raise CatalogError(
            f"id must be the canonical provider-scoped document ID {expected_document_id!r}"
        )
    title = raw.get("title")
    if not isinstance(title, str) or not title.strip() or len(title.strip()) > 300:
        raise CatalogError("title is required and must be at most 300 characters")
    language = raw.get("language", "el")
    if language != "el":
        raise CatalogError("language must be el; the production catalog is Greek-only")

    prep = _optional_int(raw, "prepMinutes", maximum=10_080)
    cook = _optional_int(raw, "cookMinutes", maximum=10_080)
    total = _optional_int(raw, "totalMinutes", maximum=20_160)
    total = derive_total_minutes(prep, cook, total)
    preparation_count = _optional_int(raw, "preparationCount", maximum=100)
    step_count = _optional_int(raw, "stepCount", maximum=1_000)
    preparation_count = preparation_count if preparation_count is not None else 0
    step_count = step_count if step_count is not None else 0

    categories = raw.get("categoryKeys")
    if categories is None:
        category_keys = classify_category_keys(title)
    elif not isinstance(categories, list) or not all(
        isinstance(item, str) and re.fullmatch(r"[a-z0-9_-]{1,40}", item) for item in categories
    ):
        raise CatalogError("categoryKeys must be a list of lowercase machine keys")
    else:
        category_keys = list(dict.fromkeys(categories)) or ["other"]

    rating = raw.get("rating10")
    if rating is not None:
        if isinstance(rating, bool) or not isinstance(rating, (int, float)):
            raise CatalogError("rating10 must be numeric or null")
        rating = float(rating)
        if not math.isfinite(rating) or not 0 <= rating <= 10:
            raise CatalogError("rating10 must be between 0 and 10")
        rating = round(rating, 2)

    computed_ease = classify_ease(preparation_count, step_count, total)
    supplied_ease = raw.get("ease", computed_ease)
    if supplied_ease not in VALID_EASE:
        raise CatalogError("ease must be easy, moderate, or involved")
    if supplied_ease != computed_ease:
        raise CatalogError("ease does not match the derived preparation/step/time band")

    source_url = _validate_source_url(raw.get("sourceUrl"), provider_recipe_id, provider)
    supplied_canonical_url = raw.get("canonicalUrl")
    if supplied_canonical_url not in (None, source_url):
        raise CatalogError("canonicalUrl must equal the normalized sourceUrl")

    legacy_source_recipe_id = (
        int(provider_recipe_id)
        if provider_recipe_id.isdigit() and int(provider_recipe_id) <= 2_147_483_647
        else 0
    )
    supplied_legacy_id = raw.get("sourceRecipeId")
    if supplied_legacy_id is not None and (
        isinstance(supplied_legacy_id, bool)
        or not isinstance(supplied_legacy_id, int)
        or supplied_legacy_id != legacy_source_recipe_id
    ):
        raise CatalogError("sourceRecipeId must be the numeric provider ID or 0")

    active = raw.get("active", True)
    if not isinstance(active, bool):
        raise CatalogError("active must be boolean")
    source_updated_at = raw.get("sourceUpdatedAt")
    if source_updated_at is not None and not isinstance(source_updated_at, str):
        raise CatalogError("sourceUpdatedAt must be an ISO timestamp string or null")

    validated = {
        "id": source_id,
        "title": title.strip(),
        "language": "el",
        "source": provider.source,
        "sourceKey": provider.key,
        "providerRecipeId": provider_recipe_id,
        "sourceRecipeId": legacy_source_recipe_id,
        "sourceUrl": source_url,
        "canonicalUrl": source_url,
        "categoryKeys": category_keys,
        "category": canonical_category(category_keys),
        "tags": category_keys,
        "prepMinutes": prep if prep is not None else 0,
        "cookMinutes": cook if cook is not None else 0,
        "totalMinutes": total if total is not None else 0,
        "preparationCount": preparation_count,
        "stepCount": step_count,
        "rating10": rating,
        "rating": rating if rating is not None else 0.0,
        "ease": computed_ease,
        "randomKey": stable_recipe_random_key(provider, provider_recipe_id),
        "sourceName": provider.display_name,
        "sourceUpdatedAt": source_updated_at,
        "active": active,
        # This field is always present so a replacement without an approved
        # image explicitly revokes any URL stored by an earlier licensed run.
        "imageUrl": "",
    }
    if raw.get(LICENSED_IMAGE_FIELD):
        validated[LICENSED_IMAGE_FIELD] = _validate_image_url(raw[LICENSED_IMAGE_FIELD], provider)
    if full_content:
        for field in ("category", "rating", "sourceName", "randomKey"):
            if raw.get(field) != validated.get(field):
                raise CatalogError(f"{field} does not match the normalized full recipe value")
        validated["tags"] = list(raw["tags"])
        for field in FULL_REQUIRED_FIELDS | FULL_OPTIONAL_FIELDS:
            if field in raw:
                validated[field] = raw[field]
        try:
            ensure_full_record(validated)
        except FullSchemaError as exc:
            raise CatalogError(str(exc)) from exc
    return validated


def validate_catalog(
    records: Iterable[Mapping[str, Any]],
    *,
    allow_licensed_images: bool = False,
    have_permission: bool = False,
    have_argiro_permission: bool = False,
    have_gastronomos_permission: bool = False,
) -> list[dict[str, Any]]:
    validated: list[dict[str, Any]] = []
    seen: set[str] = set()
    for index, raw in enumerate(records, start=1):
        try:
            record = validate_record(
                raw,
                allow_licensed_images=allow_licensed_images,
                have_permission=have_permission,
                have_argiro_permission=have_argiro_permission,
                have_gastronomos_permission=have_gastronomos_permission,
            )
        except CatalogError as exc:
            raise CatalogError(f"record {index}: {exc}") from exc
        if record["id"] in seen:
            raise CatalogError(f"record {index}: duplicate id {record['id']!r}")
        seen.add(record["id"])
        validated.append(record)
    if not validated:
        raise CatalogError("catalog contains no records")
    full_flags = {is_full_record(record) for record in validated}
    if len(full_flags) > 1:
        raise CatalogError("a catalog cannot mix metadata-only and full recipe records")
    source_keys = {record["sourceKey"] for record in validated}
    if len(source_keys) > 1:
        raise CatalogError(
            "a catalog must contain exactly one provider so completeness and retirement stay source-scoped"
        )
    return validated


def compute_catalog_hash(records: list[dict[str, Any]]) -> str:
    """Hash normalized content independently of the input record order."""

    canonical = json.dumps(
        sorted(records, key=lambda record: (0, int(record["id"])) if record["id"].isdigit() else (1, record["id"])),
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def _replace_collection(
    client: Any,
    records: list[dict[str, Any]],
    *,
    collection_name: str,
    projector,
    batch_size: int,
    server_timestamp: object,
) -> tuple[int, int]:
    if not 1 <= batch_size <= MAX_BATCH_SIZE:
        raise CatalogError(f"batch_size must be between 1 and {MAX_BATCH_SIZE}")
    collection = client.collection(collection_name)
    batch_count = 0
    for offset in range(0, len(records), batch_size):
        batch = client.batch()
        for record in records[offset : offset + batch_size]:
            payload = projector(record)
            if server_timestamp is not None:
                payload["importedAt"] = server_timestamp
            batch.set(collection.document(record["id"]), payload, merge=False)
        _commit_batch_with_retry(batch)
        batch_count += 1
    return len(records), batch_count


def replace_records(
    client: Any,
    records: list[dict[str, Any]],
    *,
    batch_size: int = DEFAULT_BATCH_SIZE,
    server_timestamp: object = None,
) -> tuple[int, int]:
    full = bool(records and is_full_record(records[0]))
    projector = firestore_recipe_payload if full else lambda record: {key: value for key, value in record.items() if key != "id"}
    return _replace_collection(
        client, records, collection_name=COLLECTION_NAME, projector=projector,
        batch_size=batch_size, server_timestamp=server_timestamp,
    )


def replace_detail_records(client: Any, records: list[dict[str, Any]], *, batch_size: int = DEFAULT_BATCH_SIZE, server_timestamp: object = None) -> tuple[int, int]:
    return _replace_collection(
        client, records, collection_name=DETAIL_COLLECTION_NAME, projector=firestore_detail_payload,
        batch_size=batch_size, server_timestamp=server_timestamp,
    )


def replace_source_payloads(client: Any, records: list[dict[str, Any]], *, batch_size: int = DEFAULT_BATCH_SIZE, server_timestamp: object = None) -> tuple[int, int]:
    return _replace_collection(
        client, records, collection_name=PAYLOAD_COLLECTION_NAME, projector=firestore_source_payload,
        batch_size=batch_size, server_timestamp=server_timestamp,
    )


def existing_active_recipe_ids(client: Any, source_key: str) -> set[str]:
    """Inventory active summaries for one provider, including legacy Akis docs."""
    query = client.collection(COLLECTION_NAME).select(
        ["active", "source", "sourceKey", "sourceUrl"]
    )
    result: set[str] = set()
    for snapshot in query.stream():
        recipe_id = str(snapshot.id)
        if not DOCUMENT_ID_RE.fullmatch(recipe_id):
            raise CatalogError(f"existing Firestore recipe has unsafe document id {recipe_id!r}")
        data = snapshot.to_dict() or {}
        try:
            belongs_to_source = record_source_key(data) == source_key
        except ProviderError:
            # Unknown/future providers must never be retired by this importer.
            belongs_to_source = False
        if belongs_to_source and data.get("active", True) is not False:
            result.add(recipe_id)
    return result


def retire_recipe_ids(
    client: Any,
    recipe_ids: Iterable[str],
    *,
    batch_size: int = DEFAULT_BATCH_SIZE,
    server_timestamp: object,
) -> tuple[int, int]:
    """Merge inactive tombstones across all three collections; never delete content."""
    if not 1 <= batch_size <= MAX_BATCH_SIZE:
        raise CatalogError(f"batch_size must be between 1 and {MAX_BATCH_SIZE}")
    ids = sorted(set(recipe_ids), key=lambda value: (0, int(value)) if value.isdigit() else (1, value))
    writes = []
    for recipe_id in ids:
        if not DOCUMENT_ID_RE.fullmatch(recipe_id):
            raise CatalogError(f"unsafe retirement document id {recipe_id!r}")
        for collection_name in (COLLECTION_NAME, DETAIL_COLLECTION_NAME, PAYLOAD_COLLECTION_NAME):
            reference = client.collection(collection_name).document(recipe_id)
            writes.append((reference, {"active": False, "retiredAt": server_timestamp}))
    batch_count = 0
    for offset in range(0, len(writes), batch_size):
        batch = client.batch()
        for reference, payload in writes[offset : offset + batch_size]:
            batch.set(reference, payload, merge=True)
        _commit_batch_with_retry(batch)
        batch_count += 1
    return len(ids), batch_count


def write_catalog_status(
    client: Any,
    *,
    recipe_count: int,
    catalog_hash: str,
    server_timestamp: object,
    source_key: str = "akis",
    full_metadata: Mapping[str, Any] | None = None,
) -> None:
    """Publish the catalog checkpoint after every recipe batch has succeeded."""

    status = {
        "language": "el",
        "recipeCount": recipe_count,
        "catalogVersion": f"sha256:{catalog_hash}",
        "catalogHash": catalog_hash,
        "lastImportedAt": server_timestamp,
        "lastImportedSource": source_key,
    }
    # Metadata-only commits must explicitly revoke a previously published
    # full-catalog checkpoint. Nulls overwrite stale values under merge=True.
    status.update({field: None for field in FULL_STATUS_METADATA_FIELDS})
    status['complete'] = False
    if full_metadata:
        status.update(full_metadata)
    source_summary = {
        "recipeCount": recipe_count,
        "catalogHash": catalog_hash,
        "catalogVersion": f"sha256:{catalog_hash}",
        "lastImportedAt": server_timestamp,
        "complete": bool(full_metadata and full_metadata.get("complete")),
    }
    # Firestore merge semantics merge nested map leaves, preserving status for
    # providers imported on earlier runs while the legacy top-level timestamp
    # remains usable by existing app versions.
    global_status = dict(status)
    global_status["sources"] = {source_key: source_summary}
    batch = client.batch()
    reference = client.collection(STATUS_COLLECTION_NAME).document(STATUS_DOCUMENT_ID)
    batch.set(reference, global_status, merge=True)
    provider_reference = client.collection(STATUS_COLLECTION_NAME).document(
        f"status_{source_key}"
    )
    batch.set(provider_reference, status, merge=True)
    _commit_batch_with_retry(batch)


def commit_catalog(
    client: Any,
    records: list[dict[str, Any]],
    *,
    batch_size: int = DEFAULT_BATCH_SIZE,
    server_timestamp: object,
) -> tuple[int, int, str]:
    """Replace recipe documents, then publish a status checkpoint separately."""

    if server_timestamp is None:
        raise CatalogError("a Firestore server timestamp is required for commit")
    full = bool(records and is_full_record(records[0]))
    source_key = records[0]["sourceKey"]
    existing_active_ids = (
        existing_active_recipe_ids(client, source_key) if full else set()
    )
    catalog_hash = compute_catalog_hash(records)
    writes, recipe_batches = replace_records(
        client,
        records,
        batch_size=batch_size,
        server_timestamp=server_timestamp,
    )
    full_metadata = None
    total_batches = recipe_batches
    if full:
        _, detail_batches = replace_detail_records(
            client, records, batch_size=batch_size, server_timestamp=server_timestamp,
        )
        _, payload_batches = replace_source_payloads(
            client, records, batch_size=batch_size, server_timestamp=server_timestamp,
        )
        total_batches += detail_batches + payload_batches
        active_count = sum(bool(record["active"]) for record in records)
        active_ids = {record["id"] for record in records if record["active"]}
        retired_on_commit_ids = existing_active_ids - active_ids
        retired_on_commit_count, retirement_batches = retire_recipe_ids(
            client,
            retired_on_commit_ids,
            batch_size=batch_size,
            server_timestamp=server_timestamp,
        )
        total_batches += retirement_batches
        carried_retired_ids = {record["id"] for record in records if not record["active"]}
        sizes = [document_sizes(record) for record in records]
        full_metadata = {
            "complete": True,
            "detailSchemaVersion": records[0]["detailSchemaVersion"],
            "activeRecipeCount": active_count,
            "retiredRecipeCount": len(records) - active_count,
            "retiredOnCommitCount": retired_on_commit_count,
            "knownRetiredRecipeCount": len(carried_retired_ids | retired_on_commit_ids),
            "detailRecipeCount": len(records),
            "activeDetailRecipeCount": active_count,
            "sourcePayloadCount": len(records),
            "activeSourcePayloadCount": active_count,
            "summaryHash": collection_hash(records, firestore_recipe_payload),
            "detailHash": collection_hash(records, firestore_detail_payload),
            "sourcePayloadHash": collection_hash(records, firestore_source_payload),
            "maximumSummaryDocumentBytes": max(size[0] for size in sizes),
            "maximumDetailDocumentBytes": max(size[1] for size in sizes),
            "maximumSourcePayloadDocumentBytes": max(size[2] for size in sizes),
            "oversizedDocumentCount": 0,
        }
    # Intentionally last: a failed/partial recipe import must not advertise a
    # new complete catalog version.
    write_catalog_status(
        client,
        recipe_count=writes,
        catalog_hash=catalog_hash,
        server_timestamp=server_timestamp,
        source_key=source_key,
        full_metadata=full_metadata,
    )
    return writes, total_batches, catalog_hash


def create_firestore_client(project_id: str | None, credentials_path: Path | None):
    try:
        import firebase_admin
        from firebase_admin import credentials, firestore
    except ImportError as exc:
        raise RuntimeError("firebase-admin is required for --commit") from exc

    try:
        app = firebase_admin.get_app()
    except ValueError:
        credential = credentials.Certificate(str(credentials_path)) if credentials_path else None
        options = {"projectId": project_id} if project_id else None
        app = firebase_admin.initialize_app(credential, options)
    return firestore.client(app=app), firestore.SERVER_TIMESTAMP


def _batch_size(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("batch size must be an integer") from exc
    if not 1 <= parsed <= MAX_BATCH_SIZE:
        raise argparse.ArgumentTypeError("batch size must be between 1 and 500")
    return parsed


def load_manifest(path: Path) -> Mapping[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, json.JSONDecodeError) as exc:
        raise CatalogError(f"cannot read completeness manifest: {exc}") from exc
    if not isinstance(value, Mapping):
        raise CatalogError("completeness manifest must be a JSON object")
    return value


def _manifest_value_hash(value: object) -> str:
    return hashlib.sha256(json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")).hexdigest()


def validate_manifest(records: list[dict[str, Any]], manifest: Mapping[str, Any]) -> None:
    if not manifest.get("complete") or manifest.get("failedRecipeCount") != 0:
        raise CatalogError("manifest does not certify a complete zero-failure crawl")
    active = [record["id"] for record in records if record["active"]]
    active_ids_hash = hashlib.sha256(json.dumps(
        sorted(active, key=lambda value: (0, int(value)) if value.isdigit() else (1, value)),
        ensure_ascii=False, sort_keys=True,
        separators=(",", ":"), allow_nan=False,
    ).encode("utf-8")).hexdigest()
    expected = {
        "sourceKey": records[0]["sourceKey"],
        "detailSchemaVersion": records[0]["detailSchemaVersion"],
        "outputRecipeCount": len(records),
        "activeRecipeCount": len(active),
        "detailRecipeCount": len(records),
        "sourcePayloadCount": len(records),
        "activeIdsHash": active_ids_hash,
        "catalogHash": compute_catalog_hash(records),
        "summaryHash": collection_hash(records, firestore_recipe_payload),
        "detailHash": collection_hash(records, firestore_detail_payload),
        "sourcePayloadHash": collection_hash(records, firestore_source_payload),
        "oversizedDocumentCount": 0,
    }
    mismatches = [key for key, value in expected.items() if manifest.get(key) != value]
    if manifest.get("discoveredActiveRecipeCount") != len(active):
        mismatches.append("discoveredActiveRecipeCount")
    if records[0]["sourceKey"] == "akis" and manifest.get("apiReportedActiveRecipeCount") != len(active):
        mismatches.append("apiReportedActiveRecipeCount")
    if records[0]["sourceKey"] == "argiro":
        # Lazy import avoids the crawler/importer module cycle while binding a
        # commit to the exact parser code and audited omission contracts that
        # produced its manifest.
        try:
            if __package__ in (None, ""):
                from crawl_argiro import (
                    AUDITED_CANONICAL_ALIASES,
                    AUDITED_EXTERNAL_REDIRECTS,
                    AUDITED_INTERNAL_STALE_REDIRECTS,
                    AUDITED_NON_GREEK_STUBS,
                    checkpoint_run_key,
                    parser_contract_hash,
                )
            else:
                from .crawl_argiro import (
                    AUDITED_CANONICAL_ALIASES,
                    AUDITED_EXTERNAL_REDIRECTS,
                    AUDITED_INTERNAL_STALE_REDIRECTS,
                    AUDITED_NON_GREEK_STUBS,
                    checkpoint_run_key,
                    parser_contract_hash,
                )
        except ImportError as exc:
            raise CatalogError(f"cannot load Argiro manifest contract: {exc}") from exc

        list_fields = (
            "canonicalAliases",
            "externalRedirectExclusions",
            "internalStaleRedirectExclusions",
            "nonGreekStubExclusions",
            "excludedRecipeUrls",
        )
        lists: dict[str, list[Any]] = {}
        for field in list_fields:
            value = manifest.get(field)
            if not isinstance(value, list) or any(
                not isinstance(item, Mapping) for item in value
            ):
                mismatches.append(field)
                lists[field] = []
            else:
                lists[field] = value

        expected_aliases = sorted([
            {"sourceUrl": source_url, **details}
            for source_url, details in AUDITED_CANONICAL_ALIASES.items()
        ], key=lambda item: item["sourceUrl"])
        expected_external = sorted([
            {
                "sourceUrl": source_url,
                **details,
                "reason": "redirected outside /recipe/{slug}/",
            }
            for source_url, details in AUDITED_EXTERNAL_REDIRECTS.items()
        ], key=lambda item: item["sourceUrl"])
        expected_stubs = sorted([
            {
                "sourceUrl": source_url,
                "providerRecipeId": provider_recipe_id,
                "finalStatus": 200,
                "reason": "recipe content is not substantively Greek",
            }
            for source_url, provider_recipe_id in AUDITED_NON_GREEK_STUBS.items()
        ], key=lambda item: item["sourceUrl"])
        expected_internal_stale = sorted([
            {
                "sourceUrl": source_url,
                **details,
                "reason": (
                    "redirected to a distinct surviving recipe; "
                    "content substitution is forbidden"
                ),
            }
            for source_url, details in AUDITED_INTERNAL_STALE_REDIRECTS.items()
        ], key=lambda item: item["sourceUrl"])
        expected_exclusions = sorted(
            [{"kind": "externalRedirect", **item} for item in expected_external]
            + [{"kind": "nonGreekStub", **item} for item in expected_stubs]
            + [
                {"kind": "internalStaleRedirect", **item}
                for item in expected_internal_stale
            ],
            key=lambda item: item["sourceUrl"],
        )
        expected_argiro_values = {
            "canonicalGreekRecipeCount": len(records),
            "canonicalAliasCount": len(expected_aliases),
            "canonicalAliases": expected_aliases,
            "canonicalAliasesHash": _manifest_value_hash(expected_aliases),
            "externalRedirectExclusionCount": len(expected_external),
            "externalRedirectExclusions": expected_external,
            "externalRedirectExclusionsHash": _manifest_value_hash(expected_external),
            "internalStaleRedirectExclusionCount": len(expected_internal_stale),
            "internalStaleRedirectExclusions": expected_internal_stale,
            "internalStaleRedirectExclusionsHash": _manifest_value_hash(expected_internal_stale),
            "nonGreekStubExclusionCount": len(expected_stubs),
            "nonGreekStubExclusions": expected_stubs,
            "nonGreekStubExclusionsHash": _manifest_value_hash(expected_stubs),
            "excludedRecipeUrlCount": len(expected_exclusions),
            "excludedRecipeUrls": expected_exclusions,
            "excludedRecipeUrlsHash": _manifest_value_hash(expected_exclusions),
            "parserContractHash": parser_contract_hash(),
            "checkpointRunKey": checkpoint_run_key(),
        }
        mismatches.extend(
            key for key, value in expected_argiro_values.items()
            if manifest.get(key) != value
        )
        discovered = manifest.get("discoveredRecipeUrlCount")
        duplicates = manifest.get("duplicateRecipeEntryCount")
        declared = manifest.get("declaredRecipeEntryCount")
        if (
            not isinstance(discovered, int)
            or discovered
            != len(records) + len(expected_aliases) + len(expected_exclusions)
        ):
            mismatches.append("discoveredRecipeUrlCount")
        if (
            not isinstance(duplicates, int)
            or duplicates < 0
            or not isinstance(declared, int)
            or declared != discovered + duplicates
        ):
            mismatches.append("declaredRecipeEntryCount")
        if len(active) != len(records):
            mismatches.append("canonicalGreekRecipeCount")
        records_by_url = {record["canonicalUrl"]: record for record in records}
        if len(records_by_url) != len(records):
            mismatches.append("canonicalAliases")
        for alias in expected_aliases:
            target = records_by_url.get(alias["canonicalUrl"])
            if (
                target is None
                or target.get("providerRecipeId") != alias["providerRecipeId"]
                or target.get("id") != f"argiro_{alias['providerRecipeId']}"
            ):
                mismatches.append("canonicalAliases")
                break
    if records[0]["sourceKey"] == "gastronomos":
        try:
            if __package__ in (None, ""):
                from crawl_gastronomos import (
                    AUDITED_CANONICAL_ALIASES,
                    AUDITED_EXTERNAL_REDIRECTS,
                    AUDITED_NON_RECIPE_SITEMAP_ENTRIES,
                    AUDITED_NON_GREEK_STUBS,
                    checkpoint_run_key,
                    parser_contract_hash,
                )
            else:
                from .crawl_gastronomos import (
                    AUDITED_CANONICAL_ALIASES,
                    AUDITED_EXTERNAL_REDIRECTS,
                    AUDITED_NON_RECIPE_SITEMAP_ENTRIES,
                    AUDITED_NON_GREEK_STUBS,
                    checkpoint_run_key,
                    parser_contract_hash,
                )
        except ImportError as exc:
            raise CatalogError(
                f"cannot load Gastronomos manifest contract: {exc}"
            ) from exc

        list_fields = (
            "canonicalAliases",
            "externalRedirectExclusions",
            "nonGreekStubExclusions",
            "excludedRecipeUrls",
        )
        for field in list_fields:
            value = manifest.get(field)
            if not isinstance(value, list) or any(
                not isinstance(item, Mapping) for item in value
            ):
                mismatches.append(field)

        non_recipe_sitemap_entries = manifest.get("nonRecipeSitemapEntries")
        if (
            not isinstance(non_recipe_sitemap_entries, list)
            or any(
                not isinstance(item, str)
                for item in non_recipe_sitemap_entries
            )
        ):
            mismatches.append("nonRecipeSitemapEntries")

        expected_aliases = sorted([
            {"sourceUrl": source_url, **details}
            for source_url, details in AUDITED_CANONICAL_ALIASES.items()
        ], key=lambda item: item["sourceUrl"])
        expected_external = sorted([
            {
                "sourceUrl": source_url,
                **details,
                "reason": (
                    "redirected outside /syntagh/{slug}/{numeric-id}/"
                ),
            }
            for source_url, details in AUDITED_EXTERNAL_REDIRECTS.items()
        ], key=lambda item: item["sourceUrl"])
        expected_stubs = sorted([
            {
                "sourceUrl": source_url,
                "providerRecipeId": provider_recipe_id,
                "finalStatus": 200,
                "reason": "recipe content is not substantively Greek",
            }
            for source_url, provider_recipe_id in AUDITED_NON_GREEK_STUBS.items()
        ], key=lambda item: item["sourceUrl"])
        expected_exclusions = sorted(
            [{"kind": "externalRedirect", **item} for item in expected_external]
            + [{"kind": "nonGreekStub", **item} for item in expected_stubs],
            key=lambda item: item["sourceUrl"],
        )
        expected_non_recipe_sitemap_entries = sorted(
            AUDITED_NON_RECIPE_SITEMAP_ENTRIES
        )
        expected_gastronomos_values = {
            "canonicalGreekRecipeCount": len(records),
            "canonicalAliasCount": len(expected_aliases),
            "canonicalAliases": expected_aliases,
            "canonicalAliasesHash": _manifest_value_hash(expected_aliases),
            "externalRedirectExclusionCount": len(expected_external),
            "externalRedirectExclusions": expected_external,
            "externalRedirectExclusionsHash": _manifest_value_hash(expected_external),
            "nonGreekStubExclusionCount": len(expected_stubs),
            "nonGreekStubExclusions": expected_stubs,
            "nonGreekStubExclusionsHash": _manifest_value_hash(expected_stubs),
            "excludedRecipeUrlCount": len(expected_exclusions),
            "excludedRecipeUrls": expected_exclusions,
            "excludedRecipeUrlsHash": _manifest_value_hash(expected_exclusions),
            "nonRecipeSitemapEntryCount": len(
                expected_non_recipe_sitemap_entries
            ),
            "nonRecipeSitemapEntries": expected_non_recipe_sitemap_entries,
            "nonRecipeSitemapEntriesHash": _manifest_value_hash(
                expected_non_recipe_sitemap_entries
            ),
            "parserContractHash": parser_contract_hash(),
            "checkpointRunKey": checkpoint_run_key(),
        }
        mismatches.extend(
            key for key, value in expected_gastronomos_values.items()
            if manifest.get(key) != value
        )
        discovered = manifest.get("discoveredRecipeUrlCount")
        duplicates = manifest.get("duplicateRecipeEntryCount")
        declared = manifest.get("declaredRecipeEntryCount")
        if (
            not isinstance(discovered, int)
            or isinstance(discovered, bool)
            or discovered
            != len(records) + len(expected_aliases) + len(expected_exclusions)
        ):
            mismatches.append("discoveredRecipeUrlCount")
        if (
            not isinstance(duplicates, int)
            or isinstance(duplicates, bool)
            or duplicates < 0
            or not isinstance(declared, int)
            or isinstance(declared, bool)
        ):
            mismatches.append("declaredRecipeEntryCount")
        elif (
            not isinstance(discovered, int)
            or isinstance(discovered, bool)
            or declared
            != discovered
            + duplicates
            + len(expected_non_recipe_sitemap_entries)
        ):
            mismatches.append("declaredRecipeEntryCount")
        if len(active) != len(records):
            mismatches.append("canonicalGreekRecipeCount")
        records_by_url = {record["canonicalUrl"]: record for record in records}
        if len(records_by_url) != len(records):
            mismatches.append("canonicalAliases")
        try:
            provider = provider_for(source_key="gastronomos")
            for alias in expected_aliases:
                target = records_by_url.get(alias["canonicalUrl"])
                if (
                    target is None
                    or target.get("providerRecipeId") != alias["providerRecipeId"]
                    or target.get("id")
                    != recipe_document_id(provider, alias["providerRecipeId"])
                ):
                    mismatches.append("canonicalAliases")
                    break
        except ProviderError:
            mismatches.append("canonicalAliases")
    if mismatches:
        raise CatalogError("manifest/catalog mismatch: " + ", ".join(sorted(set(mismatches))))


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Validate an authorized Greek catalog; Firestore writes are opt-in."
    )
    parser.add_argument("catalog", type=Path, help="Authorized .json, .jsonl, or .ndjson file")
    parser.add_argument(
        "--commit",
        action="store_true",
        help=(
            f"Actually replace normalized documents in {COLLECTION_NAME}; "
            "without this flag the command is a dry-run."
        ),
    )
    parser.add_argument("--project-id")
    parser.add_argument("--credentials", type=Path)
    parser.add_argument("--manifest", type=Path, help="Crawler completeness manifest (required for full-content --commit).")
    parser.add_argument(
        "--i-have-permission",
        action="store_true",
        help="Acknowledge publisher permission; required for full content and every commit.",
    )
    parser.add_argument(
        "--i-have-argiro-permission",
        action="store_true",
        help=(
            "Separately acknowledge written Argiro permission; required for "
            "Argiro full-content validation/import."
        ),
    )
    parser.add_argument(
        "--i-have-gastronomos-permission",
        action="store_true",
        help=(
            "Separately acknowledge written Gastronomos permission; required "
            "for Gastronomos full-content validation/import."
        ),
    )
    parser.add_argument("--batch-size", type=_batch_size, default=DEFAULT_BATCH_SIZE)
    parser.add_argument(
        "--allow-licensed-images",
        action="store_true",
        help=(
            "Allow publisher HTTPS /photos/ URLs from a catalog whose image rights "
            "are explicitly licensed; images are never downloaded by this tool."
        ),
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        records = validate_catalog(
            load_catalog(args.catalog),
            allow_licensed_images=args.allow_licensed_images,
            have_permission=args.i_have_permission,
            have_argiro_permission=args.i_have_argiro_permission,
            have_gastronomos_permission=args.i_have_gastronomos_permission,
        )
        full = is_full_record(records[0])
        if args.commit and not args.i_have_permission:
            raise CatalogError("--commit requires explicit --i-have-permission")
        if full and args.commit and args.manifest is None:
            raise CatalogError("full-content --commit requires --manifest from the completed crawl")
        if args.manifest is not None:
            if not full:
                raise CatalogError("--manifest is only valid for a full-content catalog")
            validate_manifest(records, load_manifest(args.manifest))
        catalog_hash = compute_catalog_hash(records)
        catalog_version = f"sha256:{catalog_hash}"
        if not args.commit:
            print(
                json.dumps(
                    {
                        "mode": "dry-run",
                        "collection": COLLECTION_NAME,
                        "validated": len(records),
                        "writes": 0,
                        "catalogVersion": catalog_version,
                        "catalogHash": catalog_hash,
                        "fullContent": full,
                        "summaryDocuments": len(records) if full else 0,
                        "detailDocuments": len(records) if full else 0,
                        "sourcePayloadDocuments": len(records) if full else 0,
                        "maximumDocumentBytes": max(max(document_sizes(record)) for record in records) if full else None,
                    },
                    sort_keys=True,
                )
            )
            return 0
        client, server_timestamp = create_firestore_client(args.project_id, args.credentials)
        writes, batches, catalog_hash = commit_catalog(
            client,
            records,
            batch_size=args.batch_size,
            server_timestamp=server_timestamp,
        )
        print(
            json.dumps(
                {
                    "mode": "commit",
                    "collection": COLLECTION_NAME,
                    "validated": len(records),
                    "writes": writes,
                    "documentWrites": writes * (3 if full else 1),
                    "detailWrites": writes if full else 0,
                    "sourcePayloadWrites": writes if full else 0,
                    "batches": batches,
                    "statusWrites": 2,
                    "catalogVersion": f"sha256:{catalog_hash}",
                    "catalogHash": catalog_hash,
                },
                sort_keys=True,
            )
        )
        return 0
    except (CatalogError, RuntimeError, OSError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
