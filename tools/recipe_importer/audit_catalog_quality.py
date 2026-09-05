"""Audit the bundled catalog's category and normalized facet/index contracts.

The report is intentionally read-only.  It compares every compressed recipe payload with
the SQLite columns and indexes consumed by Android, then summarizes provider-specific
taxonomy coverage and suspicious category evidence for manual review.
"""

from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from collections.abc import Iterable, Mapping, Sequence
import json
from pathlib import Path
import re
import sqlite3
from typing import Any
import zlib

try:
    from .build_local_catalog import (
        FACET_FIELDS,
        VEGAN_VETO_CATEGORIES,
        _is_vegan_eligible,
        normalize_search_token,
    )
    from .helpers import canonical_category, classify_ease
except ImportError:  # pragma: no cover - direct script execution
    from build_local_catalog import (
        FACET_FIELDS,
        VEGAN_VETO_CATEGORIES,
        _is_vegan_eligible,
        normalize_search_token,
    )
    from helpers import canonical_category, classify_ease


KNOWN_SOURCES = {"akis", "argiro", "gastronomos"}
KNOWN_CATEGORIES = {
    "legumes",
    "poultry",
    "vegetables",
    "meat",
    "fish",
    "street_food",
    "pasta_rice",
    "dessert",
    "other",
}

_SPACE_RE = re.compile(r"\s+")
_HTML_RE = re.compile(r"<[^>]+>")
_LEADING_QUANTITY_RE = re.compile(
    r"^\s*(?:\d+(?:[.,/]\d+)?|[½¼¾⅓⅔])(?:\s*[-–]\s*\d+(?:[.,/]\d+)?)?\b"
)

# These are review signals, not automatic reclassification rules.  A dish can validly
# combine groups (for example lentils with sausage), so the audit reports evidence rather
# than declaring the publisher taxonomy wrong.
_CATEGORY_EVIDENCE = {
    "poultry": (
        "κοτοπουλ",
        "κοτα",
        "γαλοπουλ",
        "παπια",
        "chicken",
        "turkey",
        "duck",
        "poultry",
    ),
    "meat": (
        "μοσχαρ",
        "χοιριν",
        "αρνι",
        "κατσικ",
        "κιμα",
        "μπεικον",
        "ζαμπον",
        "λουκανικ",
        "beef",
        "pork",
        "lamb",
        "bacon",
        "ham",
        "sausage",
    ),
    "fish": (
        "ψαρ",
        "σολομ",
        "τονο",
        "μπακαλιαρ",
        "γαριδ",
        "χταποδ",
        "καλαμαρ",
        "θαλασσιν",
        "fish",
        "salmon",
        "tuna",
        "shrimp",
        "seafood",
    ),
    "legumes": (
        "οσπρ",
        "φακ",
        "φασολ",
        "ρεβιθ",
        "γιγαντ",
        "φαβα",
        "lentil",
        "bean",
        "chickpea",
        "legume",
    ),
}


def _strings(value: object) -> list[str]:
    if not isinstance(value, list):
        return []
    return [item for item in value if isinstance(item, str)]


def _recipe_ingredients(recipe: Mapping[str, Any]) -> Iterable[tuple[str, str, str]]:
    sections = recipe.get("ingredientSections")
    if not isinstance(sections, list):
        return
    for section in sections:
        if not isinstance(section, Mapping):
            continue
        ingredients = section.get("ingredients")
        if not isinstance(ingredients, list):
            continue
        for ingredient in ingredients:
            if not isinstance(ingredient, Mapping):
                continue
            title = ingredient.get("title")
            info = ingredient.get("info")
            quantity = ingredient.get("quantity")
            yield (
                title if isinstance(title, str) else "",
                info if isinstance(info, str) else "",
                quantity if isinstance(quantity, str) else "",
            )


def _expected_ingredient_texts(recipe: Mapping[str, Any]) -> set[str]:
    return {
        normalized
        for title, info, _ in _recipe_ingredients(recipe)
        if (normalized := normalize_search_token(" ".join((title, info))))
    }


def _evidence_groups(recipe: Mapping[str, Any]) -> set[str]:
    evidence = [str(recipe.get("title") or "")]
    evidence.extend(_strings(recipe.get("tags")))
    evidence.extend(_strings(recipe.get("ingredientLabels")))
    evidence.extend(title for title, _, _ in _recipe_ingredients(recipe))
    words = [
        word
        for value in evidence
        for word in normalize_search_token(value).split()
    ]
    return {
        group
        for group, stems in _CATEGORY_EVIDENCE.items()
        if any(word.startswith(stem) for word in words for stem in stems)
    }


def _sample(counter: Counter[str], limit: int) -> list[dict[str, object]]:
    return [
        {"value": value, "count": count}
        for value, count in counter.most_common(max(0, limit))
    ]


def audit_catalog(database_path: Path, *, sample_limit: int = 20) -> dict[str, Any]:
    connection = sqlite3.connect(f"file:{database_path.as_posix()}?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    try:
        meta = dict(connection.execute("SELECT key, value FROM catalog_meta"))
        indexed_facets: dict[str, dict[str, set[str]]] = defaultdict(
            lambda: defaultdict(set)
        )
        for facet_type, token, recipe_id in connection.execute(
            "SELECT facet_type, token, recipe_id FROM recipe_facets"
        ):
            indexed_facets[recipe_id][facet_type].add(token)
        indexed_ingredients: dict[str, set[str]] = defaultdict(set)
        for recipe_id, normalized_text in connection.execute(
            "SELECT recipe_id, normalized_text FROM recipe_ingredient_texts"
        ):
            indexed_ingredients[recipe_id].add(normalized_text)

        source_categories: dict[str, Counter[str]] = defaultdict(Counter)
        source_tags: dict[str, Counter[str]] = defaultdict(Counter)
        source_ingredient_facets: dict[str, Counter[str]] = defaultdict(Counter)
        source_ingredient_titles: dict[str, Counter[str]] = defaultdict(Counter)
        normalized_tag_variants: dict[str, Counter[str]] = defaultdict(Counter)
        normalized_ingredient_variants: dict[str, Counter[str]] = defaultdict(Counter)
        records_without_ingredient_facets: Counter[str] = Counter()
        ingredient_item_counts: Counter[str] = Counter()
        leading_quantity_titles: Counter[str] = Counter()
        html_ingredient_titles: Counter[str] = Counter()
        empty_ingredient_titles: Counter[str] = Counter()
        category_key_mismatches: list[dict[str, object]] = []
        category_key_check_count = 0
        category_key_unavailable_count = 0
        suspicious_categories: list[dict[str, object]] = []
        index_mismatches: list[dict[str, object]] = []
        chicken_pie_records: list[dict[str, object]] = []
        vegan_eligible_by_source: Counter[str] = Counter()
        vegan_eligible_by_category: Counter[str] = Counter()
        stored_vegan_eligible_by_source: Counter[str] = Counter()
        stored_vegan_eligible_by_category: Counter[str] = Counter()
        vegan_recomputation_mismatches: list[dict[str, object]] = []
        animal_category_vegan_records: list[dict[str, object]] = []
        unknown_sources: Counter[str] = Counter()
        unknown_categories: Counter[str] = Counter()
        record_count = 0

        rows = connection.execute(
            """
            SELECT id, source_key, category, title_normalized, vegan_eligible,
                   ease, rating, prep_minutes, quick_recipe, recipe_json
            FROM recipes
            ORDER BY source_key, id
            """
        )
        for row in rows:
            record_count += 1
            recipe_id = row["id"]
            source = row["source_key"]
            category = row["category"]
            recipe = json.loads(zlib.decompress(row["recipe_json"]).decode("utf-8"))
            title = str(recipe.get("title") or "")
            actual_facets = indexed_facets.get(recipe_id, {})
            facet_tokens = {
                facet_type: tuple(sorted(actual_facets.get(facet_type, set())))
                for facet_type in FACET_FIELDS
            }
            recomputed_vegan_eligible = _is_vegan_eligible(
                recipe,
                category=category,
                facet_tokens=facet_tokens,
            )
            stored_vegan_eligible = bool(row["vegan_eligible"])
            if recomputed_vegan_eligible:
                vegan_eligible_by_source[source] += 1
                vegan_eligible_by_category[category] += 1
                if normalize_search_token(category) in VEGAN_VETO_CATEGORIES:
                    animal_category_vegan_records.append(
                        {
                            "id": recipe_id,
                            "source": source,
                            "title": title,
                            "category": category,
                        }
                    )
            if stored_vegan_eligible:
                stored_vegan_eligible_by_source[source] += 1
                stored_vegan_eligible_by_category[category] += 1
            if stored_vegan_eligible != recomputed_vegan_eligible:
                vegan_recomputation_mismatches.append(
                    {
                        "id": recipe_id,
                        "source": source,
                        "title": title,
                        "category": category,
                        "stored": stored_vegan_eligible,
                        "recomputed": recomputed_vegan_eligible,
                    }
                )

            source_categories[source][category] += 1
            if source not in KNOWN_SOURCES:
                unknown_sources[source] += 1
            if category not in KNOWN_CATEGORIES:
                unknown_categories[category] += 1

            tags = _strings(recipe.get("tags"))
            ingredient_facets = _strings(recipe.get("ingredientLabels"))
            if not ingredient_facets:
                records_without_ingredient_facets[source] += 1
            for label in tags:
                cleaned = _SPACE_RE.sub(" ", label).strip()
                if not cleaned:
                    continue
                source_tags[source][cleaned] += 1
                normalized_tag_variants[normalize_search_token(cleaned)][cleaned] += 1
            for label in ingredient_facets:
                cleaned = _SPACE_RE.sub(" ", label).strip()
                if not cleaned:
                    continue
                source_ingredient_facets[source][cleaned] += 1
                normalized_ingredient_variants[normalize_search_token(cleaned)][cleaned] += 1

            for ingredient_title, _, quantity in _recipe_ingredients(recipe):
                ingredient_item_counts[source] += 1
                cleaned = _SPACE_RE.sub(" ", ingredient_title).strip()
                if not cleaned:
                    empty_ingredient_titles[source] += 1
                    continue
                source_ingredient_titles[source][cleaned] += 1
                if not quantity.strip() and _LEADING_QUANTITY_RE.search(cleaned):
                    leading_quantity_titles[source] += 1
                if _HTML_RE.search(cleaned):
                    html_ingredient_titles[source] += 1

            category_keys = _strings(recipe.get("categoryKeys"))
            if "categoryKeys" in recipe:
                category_key_check_count += 1
                derived_category = canonical_category(category_keys)
                if derived_category != category:
                    category_key_mismatches.append(
                        {
                            "id": recipe_id,
                            "source": source,
                            "title": title,
                            "category": category,
                            "categoryKeys": category_keys,
                            "derivedCategory": derived_category,
                        }
                    )
            else:
                # The Android projection intentionally excludes importer-only
                # categoryKeys. Absence therefore means "not auditable here",
                # not an implicit ["other"] value or a category mismatch.
                category_key_unavailable_count += 1

            evidence_groups = _evidence_groups(recipe)
            if evidence_groups and category not in evidence_groups and category in {
                "legumes",
                "poultry",
                "meat",
                "fish",
                "other",
            }:
                suspicious_categories.append(
                    {
                        "id": recipe_id,
                        "source": source,
                        "title": title,
                        "category": category,
                        "evidenceGroups": sorted(evidence_groups),
                        "ingredientLabels": ingredient_facets,
                    }
                )

            normalized_title = normalize_search_token(title)
            if "κοτοπιτ" in normalized_title or "kotopit" in normalized_title:
                chicken_pie_records.append(
                    {
                        "id": recipe_id,
                        "source": source,
                        "title": title,
                        "category": category,
                        "categoryKeys": category_keys,
                        "ingredientLabels": ingredient_facets,
                        "tags": tags,
                    }
                )

            expected_facets = {
                facet_type: {
                    normalize_search_token(label)
                    for label in _strings(recipe.get(field))
                    if normalize_search_token(label)
                }
                for facet_type, field in FACET_FIELDS.items()
            }
            facet_differences = {
                facet_type: {
                    "expected": sorted(expected),
                    "actual": sorted(actual_facets.get(facet_type, set())),
                }
                for facet_type, expected in expected_facets.items()
                if expected != actual_facets.get(facet_type, set())
            }
            expected_planner_values = {
                "ease": classify_ease(
                    recipe.get("preparationCount", 0), recipe.get("stepCount", 0),
                    recipe.get("totalMinutes", 0),
                ),
                "rating": recipe.get("rating", 0.0),
                "prep_minutes": recipe.get("prepMinutes", 0),
                "quick_recipe": int(recipe.get("quickRecipe", False)),
            }
            planner_differences = {
                field: {"expected": expected, "actual": row[field]}
                for field, expected in expected_planner_values.items()
                if expected != row[field]
            }
            expected_ingredients = _expected_ingredient_texts(recipe)
            actual_ingredients = indexed_ingredients.get(recipe_id, set())
            if (
                facet_differences
                or planner_differences
                or expected_ingredients != actual_ingredients
                or normalized_title != row["title_normalized"]
                or recipe.get("category") != category
                or recipe.get("sourceKey") != source
            ):
                index_mismatches.append(
                    {
                        "id": recipe_id,
                        "source": source,
                        "facetDifferences": facet_differences,
                        "plannerDifferences": planner_differences,
                        "ingredientTextMismatch": expected_ingredients != actual_ingredients,
                        "titleMismatch": normalized_title != row["title_normalized"],
                        "categoryMismatch": recipe.get("category") != category,
                        "sourceMismatch": recipe.get("sourceKey") != source,
                    }
                )

        def collisions(values: Mapping[str, Counter[str]]) -> list[dict[str, object]]:
            result = []
            for normalized, variants in values.items():
                if normalized and len(variants) > 1:
                    result.append(
                        {
                            "normalized": normalized,
                            "variants": _sample(variants, sample_limit),
                            "occurrences": sum(variants.values()),
                        }
                    )
            return sorted(
                result,
                key=lambda item: (-int(item["occurrences"]), str(item["normalized"])),
            )[:sample_limit]

        return {
            "database": str(database_path),
            "catalogMeta": {
                key: meta.get(key)
                for key in (
                    "catalog_count",
                    "catalog_hash",
                    "catalog_version",
                    "source_counts",
                    "ingredient_text_normalization",
                    "ingredient_text_index",
                )
            },
            "recordCount": record_count,
            "categoryCountsBySource": {
                source: dict(sorted(counts.items()))
                for source, counts in sorted(source_categories.items())
            },
            "ingredientCoverageBySource": {
                source: {
                    "recipeCount": sum(source_categories[source].values()),
                    "recipesWithoutIngredientFacet": records_without_ingredient_facets[source],
                    "ingredientItems": ingredient_item_counts[source],
                    "uniqueRawIngredientTitles": len(source_ingredient_titles[source]),
                    "emptyIngredientTitles": empty_ingredient_titles[source],
                    "quantityStillEmbeddedInTitle": leading_quantity_titles[source],
                    "htmlStillEmbeddedInTitle": html_ingredient_titles[source],
                    "uniqueIngredientFacetLabels": len(source_ingredient_facets[source]),
                }
                for source in sorted(source_categories)
            },
            "topTagsBySource": {
                source: _sample(values, sample_limit)
                for source, values in sorted(source_tags.items())
            },
            "topIngredientFacetsBySource": {
                source: _sample(values, sample_limit)
                for source, values in sorted(source_ingredient_facets.items())
            },
            "normalizedTagCollisions": collisions(normalized_tag_variants),
            "normalizedIngredientFacetCollisions": collisions(
                normalized_ingredient_variants
            ),
            "categoryKeyCheckCount": category_key_check_count,
            "categoryKeyUnavailableCount": category_key_unavailable_count,
            "categoryKeyMismatchCount": len(category_key_mismatches),
            "categoryKeyMismatchSamples": category_key_mismatches[:sample_limit],
            "suspiciousCategoryEvidenceCount": len(suspicious_categories),
            "suspiciousCategoryEvidenceSamples": suspicious_categories[:sample_limit],
            "indexMismatchCount": len(index_mismatches),
            "indexMismatchSamples": index_mismatches[:sample_limit],
            "unknownSources": dict(unknown_sources),
            "unknownCategories": dict(unknown_categories),
            "veganEligibility": {
                "eligibleTotal": sum(vegan_eligible_by_source.values()),
                "eligibleBySource": dict(sorted(vegan_eligible_by_source.items())),
                "eligibleByCategory": dict(sorted(vegan_eligible_by_category.items())),
                "storedEligibleTotal": sum(stored_vegan_eligible_by_source.values()),
                "storedEligibleBySource": dict(
                    sorted(stored_vegan_eligible_by_source.items())
                ),
                "storedEligibleByCategory": dict(
                    sorted(stored_vegan_eligible_by_category.items())
                ),
                "animalCategoryEligibleCount": len(animal_category_vegan_records),
                "animalCategoryEligibleSamples": animal_category_vegan_records[
                    :sample_limit
                ],
                "recomputationMismatchCount": len(vegan_recomputation_mismatches),
                "recomputationMismatchSamples": vegan_recomputation_mismatches[
                    :sample_limit
                ],
            },
            "chickenPieRecordCount": len(chicken_pie_records),
            "chickenPieRecords": chicken_pie_records[:sample_limit],
        }
    finally:
        connection.close()


def _repository_root() -> Path:
    return Path(__file__).resolve().parents[2]


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "database",
        nargs="?",
        type=Path,
        default=_repository_root() / "app" / "src" / "main" / "assets" / "recipe_catalog.db",
    )
    parser.add_argument("--sample-limit", type=int, default=20)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    if args.sample_limit < 0:
        raise SystemExit("--sample-limit must be non-negative")
    report = audit_catalog(args.database, sample_limit=args.sample_limit)
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    vegan_audit = report["veganEligibility"]
    return (
        1
        if report["indexMismatchCount"]
        or vegan_audit["recomputationMismatchCount"]
        or vegan_audit["animalCategoryEligibleCount"]
        else 0
    )


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
