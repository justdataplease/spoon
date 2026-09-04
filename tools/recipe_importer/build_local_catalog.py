"""Build the immutable, bundled Android recipe catalog.

The crawler JSONL files are deliberately rich archival artifacts.  They contain
provider payloads and import bookkeeping which must not be copied into the APK.
This module projects those artifacts onto the Kotlin ``Recipe`` contract,
compresses each canonical JSON object, and adds small indexed columns for the
queries performed by the application.

The output has no timestamps and rows are inserted in canonical order.  Given
the same source artifacts and SQLite runtime, repeated builds are byte-for-byte
deterministic.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import sqlite3
import sys
import unicodedata
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence

try:
    from .helpers import classify_ease
except ImportError:  # pragma: no cover - direct script execution
    from helpers import classify_ease


SCHEMA_VERSION = 2
APPLICATION_ID = 0x53504F4E  # ``SPON``; SQLite application_id is signed 32-bit.
PAGE_SIZE = 16_384  # Avoid overflow-page waste for independently compressed recipes.
MAX_GIT_BLOB_BYTES = 100_000_000
JSON_COMPRESSION_LEVEL = 9

DEFAULT_CATALOG_NAMES = (
    "akis-greek-full",
    "argiro-greek-full",
    "gastronomos-greek-full",
)

# Keep this list aligned with data/model/Recipe.kt.  Fields absent from a source
# record retain their Kotlin defaults.  Everything else, most importantly
# sourcePayload and importer-only integrity fields, is excluded from the APK.
KOTLIN_RECIPE_FIELDS = (
    "id",
    "title",
    "category",
    "rating",
    "prepMinutes",
    "stepCount",
    "cookMinutes",
    "totalMinutes",
    "preparationCount",
    "language",
    "imageUrl",
    "sourceUrl",
    "sourceName",
    "source",
    "sourceKey",
    "providerRecipeId",
    "canonicalUrl",
    "tags",
    "updatedAtEpochMillis",
    "active",
    "sourceRecipeId",
    "slug",
    "description",
    "seoTitle",
    "seoDescription",
    "categoryLabel",
    "categorySourceId",
    "ratingCount",
    "rating1",
    "rating2",
    "rating3",
    "rating4",
    "rating5",
    "waitMinutes",
    "sourceDifficulty",
    "servings",
    "recipeYield",
    "imageUrls",
    "shortUrl",
    "dietLabels",
    "mealTypeLabels",
    "occasionLabels",
    "methodLabels",
    "cuisineLabels",
    "ingredientLabels",
    "quickRecipe",
    "videoUrls",
    "ingredientSections",
    "methodSections",
    "tips",
    "nutritionTips",
    "nutritionPer",
    "nutritionSections",
    "equipment",
    "authorName",
    "publishedAt",
    "published",
    "createdAt",
    "sourceUpdatedAt",
    "shares",
    "sponsorLogoUrl",
    "notes",
)

# Exact top-level defaults declared by Recipe.kt.  Omitting an equal value is
# lossless with Kotlin Serialization and materially improves per-row DEFLATE,
# because each recipe is compressed independently for O(1) detail reads.
KOTLIN_RECIPE_DEFAULTS: dict[str, Any] = {
    "id": "",
    "title": "",
    "category": "",
    "rating": 0.0,
    "prepMinutes": 0,
    "stepCount": 0,
    "cookMinutes": 0,
    "totalMinutes": 0,
    "preparationCount": 0,
    "language": "el",
    "imageUrl": "",
    "sourceUrl": "",
    "sourceName": "",
    "source": "",
    "sourceKey": "",
    "providerRecipeId": "",
    "canonicalUrl": "",
    "tags": [],
    "updatedAtEpochMillis": 0,
    "active": True,
    "sourceRecipeId": 0,
    "slug": "",
    "description": "",
    "seoTitle": "",
    "seoDescription": "",
    "categoryLabel": "",
    "categorySourceId": 0,
    "ratingCount": 0,
    "rating1": 0,
    "rating2": 0,
    "rating3": 0,
    "rating4": 0,
    "rating5": 0,
    "waitMinutes": 0,
    "sourceDifficulty": "",
    "servings": "",
    "recipeYield": "",
    "imageUrls": [],
    "shortUrl": "",
    "dietLabels": [],
    "mealTypeLabels": [],
    "occasionLabels": [],
    "methodLabels": [],
    "cuisineLabels": [],
    "ingredientLabels": [],
    "quickRecipe": False,
    "videoUrls": [],
    "ingredientSections": [],
    "methodSections": [],
    "tips": [],
    "nutritionTips": [],
    "nutritionPer": "",
    "nutritionSections": [],
    "equipment": [],
    "authorName": "",
    "publishedAt": "",
    "published": True,
    "createdAt": "",
    "sourceUpdatedAt": "",
    "shares": 0,
    "sponsorLogoUrl": "",
    "notes": [],
}
if set(KOTLIN_RECIPE_DEFAULTS) != set(KOTLIN_RECIPE_FIELDS):  # pragma: no cover
    raise RuntimeError("Recipe field/default contract is incomplete")

FACET_FIELDS = {
    "diet": "dietLabels",
    "meal": "mealTypeLabels",
    "occasion": "occasionLabels",
    "method": "methodLabels",
    "cuisine": "cuisineLabels",
    "ingredient": "ingredientLabels",
}

REQUIRED_FULL_FIELDS = {
    "id",
    "title",
    "category",
    "rating",
    "prepMinutes",
    "preparationCount",
    "stepCount",
    "language",
    "sourceKey",
    "randomKey",
    "ease",
    "ingredientSections",
    "methodSections",
    "sourcePayload",
}

_SPACE_RE = re.compile(r"\s+")
_HEX_64_RE = re.compile(r"[0-9a-f]{64}")


class CatalogBuildError(ValueError):
    """The local catalog cannot be built without weakening its contract."""


@dataclass(frozen=True)
class SourceArtifact:
    catalog_path: Path
    manifest_path: Path


@dataclass(frozen=True)
class PreparedRecipe:
    id: str
    title_normalized: str
    search_text: str
    category: str
    ease: str
    rating: float
    prep_minutes: int
    quick_recipe: int
    source_key: str
    random_key: float
    facet_tokens: Mapping[str, tuple[str, ...]]
    ingredient_texts: tuple[str, ...]
    recipe_json: bytes
    recipe_json_sha256: str


@dataclass(frozen=True)
class BuildResult:
    output_path: Path
    recipe_count: int
    file_size: int
    file_sha256: str
    catalog_hash: str
    source_counts: Mapping[str, int]


def canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def normalize_search_token(value: str) -> str:
    """Return the shared accent-insensitive exact/search representation."""

    folded = unicodedata.normalize("NFKD", value.casefold())
    without_marks = "".join(
        character
        for character in folded
        if unicodedata.category(character) != "Mn"
    )
    alphanumeric = "".join(
        character if character.isalnum() else " "
        for character in without_marks
    )
    return _SPACE_RE.sub(" ", alphanumeric).strip()


def _strings(value: Any, *, field: str) -> list[str]:
    if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
        raise CatalogBuildError(f"{field} must be a list of strings")
    return value


def _non_negative_int(value: Any, *, field: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise CatalogBuildError(f"{field} must be a non-negative integer")
    return value


def _finite_number(value: Any, *, field: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise CatalogBuildError(f"{field} must be numeric")
    result = float(value)
    if not math.isfinite(result):
        raise CatalogBuildError(f"{field} must be finite")
    return result


def _search_chunks(record: Mapping[str, Any]) -> Iterable[str]:
    # This is the canonical ExploreRecipeFilter search corpus.  Keep it aligned
    # with the Android predicate so local SQL and in-memory custom recipes return
    # the same results.  ingredientLabels provides the curated ingredient search
    # surface; raw ingredient info and SEO copies are deliberately not repeated.
    for field in (
        "title",
        "description",
        "category",
        "categoryLabel",
        "sourceKey",
        "source",
        "sourceName",
    ):
        value = record.get(field)
        if isinstance(value, str) and value.strip():
            yield value
    for field in ("tags", *FACET_FIELDS.values()):
        value = record.get(field, [])
        if isinstance(value, list):
            yield from (item for item in value if isinstance(item, str) and item.strip())
def _normalized_search_text(record: Mapping[str, Any]) -> str:
    # Preserve first-seen order while removing repeated normalized phrases.
    seen: set[str] = set()
    chunks: list[str] = []
    for value in _search_chunks(record):
        normalized = normalize_search_token(value)
        if normalized and normalized not in seen:
            seen.add(normalized)
            chunks.append(normalized)
    return " ".join(chunks)


def _facet_values(
    record: Mapping[str, Any],
) -> tuple[dict[str, tuple[str, ...]], dict[str, dict[str, str]]]:
    facets: dict[str, tuple[str, ...]] = {}
    labels: dict[str, dict[str, str]] = {}
    for facet_type, field in FACET_FIELDS.items():
        token_labels: dict[str, str] = {}
        for raw_label in _strings(record.get(field, []), field=field):
            display_label = _SPACE_RE.sub(" ", raw_label).strip()
            token = normalize_search_token(display_label)
            if not token:
                continue
            previous = token_labels.get(token)
            if previous is None or display_label < previous:
                token_labels[token] = display_label
        facets[facet_type] = tuple(sorted(token_labels))
        labels[facet_type] = token_labels
    return facets, labels


def _normalized_ingredient_texts(record: Mapping[str, Any]) -> tuple[str, ...]:
    """Return one searchable title/info row for each distinct raw ingredient.

    Rows intentionally remain ingredient-scoped instead of being concatenated at
    recipe level. Android can therefore apply normalized phrase containment
    (for example ``γαλα καρυδας``) without accidentally matching words that
    occur in separate ingredients. Quantity and unit are excluded because they
    are preparation data rather than ingredient identity.
    """

    sections = record.get("ingredientSections")
    if not isinstance(sections, list):
        raise CatalogBuildError("ingredientSections must be a list")
    normalized_texts: set[str] = set()
    for section in sections:
        if not isinstance(section, Mapping):
            raise CatalogBuildError("ingredientSections must contain objects")
        ingredients = section.get("ingredients")
        if not isinstance(ingredients, list):
            raise CatalogBuildError("ingredientSections ingredients must be a list")
        for ingredient in ingredients:
            if not isinstance(ingredient, Mapping):
                raise CatalogBuildError("ingredients must contain objects")
            chunks: list[str] = []
            for field in ("title", "info"):
                value = ingredient.get(field, "")
                if not isinstance(value, str):
                    raise CatalogBuildError(f"ingredient {field} must be a string")
                if value.strip():
                    chunks.append(value)
            normalized = normalize_search_token(" ".join(chunks))
            if normalized:
                normalized_texts.add(normalized)
    return tuple(sorted(normalized_texts))


def prepare_recipe(
    record: Mapping[str, Any],
) -> tuple[PreparedRecipe, dict[str, dict[str, str]]]:
    missing = sorted(REQUIRED_FULL_FIELDS - record.keys())
    if missing:
        raise CatalogBuildError(f"record is not full; missing fields: {', '.join(missing)}")
    recipe_id = record["id"]
    if not isinstance(recipe_id, str) or not recipe_id:
        raise CatalogBuildError("id must be a non-empty string")
    title = record["title"]
    if not isinstance(title, str) or not title.strip():
        raise CatalogBuildError(f"{recipe_id}: title must be a non-empty string")
    if record["language"] != "el":
        raise CatalogBuildError(f"{recipe_id}: only Greek records may be bundled")
    if record.get("active") is not True:
        raise CatalogBuildError(f"{recipe_id}: only active records may be bundled")

    prep_minutes = _non_negative_int(record["prepMinutes"], field="prepMinutes")
    preparation_count = _non_negative_int(
        record["preparationCount"], field="preparationCount"
    )
    step_count = _non_negative_int(record["stepCount"], field="stepCount")
    total_minutes = _non_negative_int(record.get("totalMinutes", 0), field="totalMinutes")
    derived_ease = classify_ease(preparation_count, step_count, total_minutes)
    if record["ease"] != derived_ease:
        raise CatalogBuildError(
            f"{recipe_id}: ease {record['ease']!r} does not match derived {derived_ease!r}"
        )

    rating = _finite_number(record["rating"], field="rating")
    if not 0 <= rating <= 10:
        raise CatalogBuildError(f"{recipe_id}: rating must be between 0 and 10")
    random_key = _finite_number(record["randomKey"], field="randomKey")
    if not 0 <= random_key < 1:
        raise CatalogBuildError(f"{recipe_id}: randomKey must be in [0, 1)")
    quick_recipe = record.get("quickRecipe")
    if not isinstance(quick_recipe, bool):
        raise CatalogBuildError(f"{recipe_id}: quickRecipe must be boolean")

    source_key = record["sourceKey"]
    category = record["category"]
    if not isinstance(source_key, str) or not source_key:
        raise CatalogBuildError(f"{recipe_id}: sourceKey must be a non-empty string")
    if not isinstance(category, str) or not category:
        raise CatalogBuildError(f"{recipe_id}: category must be a non-empty string")

    facets, labels = _facet_values(record)
    ingredient_texts = _normalized_ingredient_texts(record)
    kotlin_recipe = {
        field: record[field]
        for field in KOTLIN_RECIPE_FIELDS
        if field in record
        and record[field] != KOTLIN_RECIPE_DEFAULTS[field]
    }
    recipe_json = canonical_json_bytes(kotlin_recipe)
    compressed = zlib.compress(recipe_json, level=JSON_COMPRESSION_LEVEL)
    prepared = PreparedRecipe(
        id=recipe_id,
        title_normalized=normalize_search_token(title),
        search_text=_normalized_search_text(record),
        category=category,
        ease=derived_ease,
        rating=rating,
        prep_minutes=prep_minutes,
        quick_recipe=int(quick_recipe),
        source_key=source_key,
        random_key=random_key,
        facet_tokens=facets,
        ingredient_texts=ingredient_texts,
        recipe_json=compressed,
        recipe_json_sha256=hashlib.sha256(recipe_json).hexdigest(),
    )
    return prepared, labels


def _read_manifest(artifact: SourceArtifact) -> dict[str, Any]:
    try:
        manifest = json.loads(artifact.manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise CatalogBuildError(
            f"cannot read manifest {artifact.manifest_path}: {exc}"
        ) from exc
    if not isinstance(manifest, dict):
        raise CatalogBuildError(f"{artifact.manifest_path}: manifest must be an object")
    if manifest.get("complete") is not True:
        raise CatalogBuildError(f"{artifact.manifest_path}: catalog is not complete")
    # Older provider manifests did not publish a language field.  Every row is
    # still checked below; an explicit non-Greek manifest value fails closed.
    if manifest.get("language", "el") != "el":
        raise CatalogBuildError(f"{artifact.manifest_path}: catalog is not Greek-only")
    if manifest.get("failedRecipeCount") != 0:
        raise CatalogBuildError(f"{artifact.manifest_path}: catalog contains failures")
    for field in ("catalogHash", "detailHash", "summaryHash", "sourcePayloadHash"):
        value = manifest.get(field)
        if not isinstance(value, str) or not _HEX_64_RE.fullmatch(value):
            raise CatalogBuildError(f"{artifact.manifest_path}: invalid {field}")
    return manifest


def _load_sources(
    artifacts: Sequence[SourceArtifact],
) -> tuple[
    list[PreparedRecipe],
    dict[str, int],
    dict[str, str],
    dict[str, str],
    dict[str, str],
    dict[str, str],
    dict[str, dict[str, str]],
]:
    if not artifacts:
        raise CatalogBuildError("at least one source artifact is required")
    recipes: list[PreparedRecipe] = []
    seen_ids: set[str] = set()
    source_counts: dict[str, int] = {}
    source_catalog_hashes: dict[str, str] = {}
    source_detail_hashes: dict[str, str] = {}
    source_summary_hashes: dict[str, str] = {}
    source_payload_hashes: dict[str, str] = {}
    option_labels = {facet_type: {} for facet_type in FACET_FIELDS}

    for artifact in sorted(artifacts, key=lambda item: str(item.catalog_path)):
        manifest = _read_manifest(artifact)
        source_key = manifest.get("sourceKey")
        if not isinstance(source_key, str) or not source_key:
            raise CatalogBuildError(f"{artifact.manifest_path}: invalid sourceKey")
        if source_key in source_counts:
            raise CatalogBuildError(f"duplicate source artifact {source_key!r}")
        expected_count = manifest.get("activeRecipeCount")
        if isinstance(expected_count, bool) or not isinstance(expected_count, int):
            raise CatalogBuildError(f"{artifact.manifest_path}: invalid activeRecipeCount")

        count = 0
        try:
            stream = artifact.catalog_path.open(encoding="utf-8")
        except OSError as exc:
            raise CatalogBuildError(f"cannot read {artifact.catalog_path}: {exc}") from exc
        with stream:
            for line_number, line in enumerate(stream, start=1):
                if not line.strip():
                    continue
                try:
                    raw = json.loads(line)
                except json.JSONDecodeError as exc:
                    raise CatalogBuildError(
                        f"{artifact.catalog_path}:{line_number}: invalid JSON: {exc}"
                    ) from exc
                if not isinstance(raw, dict):
                    raise CatalogBuildError(
                        f"{artifact.catalog_path}:{line_number}: record must be an object"
                    )
                if raw.get("sourceKey") != source_key:
                    raise CatalogBuildError(
                        f"{artifact.catalog_path}:{line_number}: sourceKey does not match manifest"
                    )
                try:
                    prepared, labels = prepare_recipe(raw)
                except CatalogBuildError as exc:
                    raise CatalogBuildError(
                        f"{artifact.catalog_path}:{line_number}: {exc}"
                    ) from exc
                if prepared.id in seen_ids:
                    raise CatalogBuildError(f"duplicate recipe id {prepared.id!r}")
                seen_ids.add(prepared.id)
                recipes.append(prepared)
                count += 1
                for facet_type, token_labels in labels.items():
                    destination = option_labels[facet_type]
                    for token, label in token_labels.items():
                        previous = destination.get(token)
                        if previous is None or label < previous:
                            destination[token] = label

        if count != expected_count:
            raise CatalogBuildError(
                f"{artifact.catalog_path}: expected {expected_count} records, found {count}"
            )
        source_counts[source_key] = count
        source_catalog_hashes[source_key] = manifest["catalogHash"]
        source_detail_hashes[source_key] = manifest["detailHash"]
        source_summary_hashes[source_key] = manifest["summaryHash"]
        source_payload_hashes[source_key] = manifest["sourcePayloadHash"]

    recipes.sort(key=lambda recipe: recipe.id)
    return (
        recipes,
        source_counts,
        source_catalog_hashes,
        source_detail_hashes,
        source_summary_hashes,
        source_payload_hashes,
        option_labels,
    )


def _facet_options_json(option_labels: Mapping[str, Mapping[str, str]]) -> str:
    value = {
        facet_type: [
            {"token": token, "label": labels[token]}
            for token in sorted(labels)
        ]
        for facet_type, labels in sorted(option_labels.items())
    }
    return canonical_json_bytes(value).decode("utf-8")


def _content_hash(
    recipes: Sequence[PreparedRecipe],
    *,
    source_counts: Mapping[str, int],
    source_catalog_hashes: Mapping[str, str],
    source_detail_hashes: Mapping[str, str],
) -> str:
    digest = hashlib.sha256()
    digest.update(
        canonical_json_bytes(
            {
                "schemaVersion": SCHEMA_VERSION,
                "sourceCounts": source_counts,
                "sourceCatalogHashes": source_catalog_hashes,
                "sourceDetailHashes": source_detail_hashes,
            }
        )
    )
    digest.update(b"\n")
    for recipe in recipes:
        envelope = {
            "id": recipe.id,
            "titleNormalized": recipe.title_normalized,
            "searchText": recipe.search_text,
            "category": recipe.category,
            "ease": recipe.ease,
            "rating": recipe.rating,
            "prepMinutes": recipe.prep_minutes,
            "quickRecipe": recipe.quick_recipe,
            "sourceKey": recipe.source_key,
            "randomKey": recipe.random_key,
            "facets": recipe.facet_tokens,
            "ingredientTexts": recipe.ingredient_texts,
            "recipeJsonSha256": recipe.recipe_json_sha256,
        }
        digest.update(canonical_json_bytes(envelope))
        digest.update(b"\n")
    return digest.hexdigest()


SCHEMA_SQL = """
CREATE TABLE catalog_meta (
    key TEXT PRIMARY KEY NOT NULL,
    value TEXT NOT NULL
) WITHOUT ROWID;

CREATE TABLE recipes (
    id TEXT PRIMARY KEY NOT NULL,
    title_normalized TEXT NOT NULL,
    search_text TEXT NOT NULL,
    category TEXT NOT NULL,
    ease TEXT NOT NULL CHECK (ease IN ('easy', 'moderate', 'involved', 'unknown')),
    rating REAL NOT NULL CHECK (rating >= 0 AND rating <= 10),
    prep_minutes INTEGER NOT NULL CHECK (prep_minutes >= 0),
    quick_recipe INTEGER NOT NULL CHECK (quick_recipe IN (0, 1)),
    source_key TEXT NOT NULL,
    random_key REAL NOT NULL CHECK (random_key >= 0 AND random_key < 1),
    recipe_json BLOB NOT NULL
) WITHOUT ROWID;

CREATE TABLE recipe_facets (
    facet_type TEXT NOT NULL CHECK (
        facet_type IN ('diet', 'meal', 'occasion', 'method', 'cuisine', 'ingredient')
    ),
    token TEXT NOT NULL,
    recipe_id TEXT NOT NULL,
    PRIMARY KEY (facet_type, token, recipe_id),
    FOREIGN KEY (recipe_id) REFERENCES recipes(id)
) WITHOUT ROWID;

CREATE TABLE recipe_ingredient_texts (
    recipe_id TEXT NOT NULL,
    normalized_text TEXT NOT NULL,
    PRIMARY KEY (recipe_id, normalized_text),
    FOREIGN KEY (recipe_id) REFERENCES recipes(id)
) WITHOUT ROWID;

CREATE INDEX recipes_title_idx ON recipes(title_normalized, id);
CREATE INDEX recipes_plan_idx ON recipes(
    category, ease, quick_recipe, prep_minutes, rating, random_key, id
);
CREATE INDEX recipes_source_idx ON recipes(source_key, id);
CREATE INDEX recipes_random_idx ON recipes(random_key, id);
"""


def _write_database(
    output_path: Path,
    recipes: Sequence[PreparedRecipe],
    metadata: Mapping[str, str],
) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = output_path.with_name(output_path.name + ".building")
    if temporary.exists():
        temporary.unlink()
    connection = sqlite3.connect(temporary)
    try:
        connection.execute(f"PRAGMA page_size={PAGE_SIZE}")
        connection.execute("PRAGMA auto_vacuum=NONE")
        connection.execute("PRAGMA journal_mode=OFF")
        connection.execute("PRAGMA synchronous=OFF")
        connection.execute("PRAGMA temp_store=MEMORY")
        connection.execute("PRAGMA locking_mode=EXCLUSIVE")
        connection.execute("PRAGMA foreign_keys=ON")
        connection.execute(f"PRAGMA application_id={APPLICATION_ID}")
        connection.execute(f"PRAGMA user_version={SCHEMA_VERSION}")
        connection.executescript(SCHEMA_SQL)

        recipe_rows = []
        facet_rows = []
        ingredient_text_rows = []
        for recipe in recipes:
            recipe_rows.append(
                (
                    recipe.id,
                    recipe.title_normalized,
                    recipe.search_text,
                    recipe.category,
                    recipe.ease,
                    recipe.rating,
                    recipe.prep_minutes,
                    recipe.quick_recipe,
                    recipe.source_key,
                    recipe.random_key,
                    sqlite3.Binary(recipe.recipe_json),
                )
            )
            for facet_type in FACET_FIELDS:
                facet_rows.extend(
                    (facet_type, token, recipe.id)
                    for token in recipe.facet_tokens[facet_type]
                )
            ingredient_text_rows.extend(
                (recipe.id, normalized_text)
                for normalized_text in recipe.ingredient_texts
            )

        connection.executemany(
            """
            INSERT INTO recipes (
                id, title_normalized, search_text, category, ease, rating,
                prep_minutes, quick_recipe, source_key, random_key, recipe_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            recipe_rows,
        )
        connection.executemany(
            "INSERT INTO recipe_facets(facet_type, token, recipe_id) VALUES (?, ?, ?)",
            sorted(facet_rows),
        )
        connection.executemany(
            """
            INSERT INTO recipe_ingredient_texts(recipe_id, normalized_text)
            VALUES (?, ?)
            """,
            sorted(ingredient_text_rows),
        )
        connection.executemany(
            "INSERT INTO catalog_meta(key, value) VALUES (?, ?)",
            sorted(metadata.items()),
        )
        connection.commit()
        # Rebuild all b-trees in key order and remove free pages.  No ANALYZE is
        # stored because query plans should not depend on a build-machine SQLite.
        connection.execute("VACUUM")
    except Exception:
        connection.close()
        temporary.unlink(missing_ok=True)
        raise
    finally:
        if connection:
            connection.close()

    size = temporary.stat().st_size
    if size >= MAX_GIT_BLOB_BYTES:
        temporary.unlink()
        raise CatalogBuildError(
            f"generated database is {size} bytes; Git blobs must stay below "
            f"{MAX_GIT_BLOB_BYTES} bytes"
        )
    os.replace(temporary, output_path)


def build_catalog(
    artifacts: Sequence[SourceArtifact],
    output_path: Path,
) -> BuildResult:
    (
        recipes,
        source_counts,
        source_catalog_hashes,
        source_detail_hashes,
        source_summary_hashes,
        source_payload_hashes,
        option_labels,
    ) = _load_sources(artifacts)
    catalog_hash = _content_hash(
        recipes,
        source_counts=source_counts,
        source_catalog_hashes=source_catalog_hashes,
        source_detail_hashes=source_detail_hashes,
    )
    metadata = {
        "schema_version": str(SCHEMA_VERSION),
        "catalog_count": str(len(recipes)),
        "catalog_hash": catalog_hash,
        "catalog_version": f"sha256:{catalog_hash}",
        "source_counts": canonical_json_bytes(source_counts).decode("utf-8"),
        "source_catalog_hashes": canonical_json_bytes(source_catalog_hashes).decode("utf-8"),
        "source_detail_hashes": canonical_json_bytes(source_detail_hashes).decode("utf-8"),
        "source_summary_hashes": canonical_json_bytes(source_summary_hashes).decode("utf-8"),
        "source_payload_hashes": canonical_json_bytes(source_payload_hashes).decode("utf-8"),
        "facet_options_json": _facet_options_json(option_labels),
        "json_compression": "zlib",
        "token_normalization": "nfkd-casefold-alnum-v1",
        "ingredient_text_index": "recipe_ingredient_texts-title-info-instr-v1",
        "ingredient_text_normalization": "nfkd-casefold-alnum-v1",
    }
    _write_database(output_path, recipes, metadata)
    file_bytes = output_path.read_bytes()
    return BuildResult(
        output_path=output_path,
        recipe_count=len(recipes),
        file_size=len(file_bytes),
        file_sha256=hashlib.sha256(file_bytes).hexdigest(),
        catalog_hash=catalog_hash,
        source_counts=dict(sorted(source_counts.items())),
    )


def default_artifacts(repository_root: Path) -> list[SourceArtifact]:
    output = repository_root / "tools" / "recipe_importer" / "output"
    return [
        SourceArtifact(
            catalog_path=output / f"{name}.jsonl",
            manifest_path=output / f"{name}.manifest.json",
        )
        for name in DEFAULT_CATALOG_NAMES
    ]


def _repository_root() -> Path:
    return Path(__file__).resolve().parents[2]


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--artifact",
        action="append",
        nargs=2,
        metavar=("JSONL", "MANIFEST"),
        help="catalog/manifest pair; repeat for each provider (defaults to all three)",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=_repository_root() / "app" / "src" / "main" / "assets" / "recipe_catalog.db",
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    artifacts = (
        [SourceArtifact(Path(pair[0]), Path(pair[1])) for pair in args.artifact]
        if args.artifact
        else default_artifacts(_repository_root())
    )
    try:
        result = build_catalog(artifacts, args.output)
    except CatalogBuildError as exc:
        print(str(exc), file=sys.stderr)
        return 2
    print(
        canonical_json_bytes(
            {
                "catalogHash": result.catalog_hash,
                "fileSha256": result.file_sha256,
                "fileSize": result.file_size,
                "output": str(result.output_path),
                "recipeCount": result.recipe_count,
                "sourceCounts": result.source_counts,
            }
        ).decode("utf-8")
    )
    return 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
