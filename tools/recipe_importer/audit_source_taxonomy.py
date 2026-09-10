"""Read-only comparison of every catalog tag family with preserved publisher evidence.

This checks reproducibility, not culinary correctness of every publisher assertion.
Category changes are reported separately from missing or extra source-derived tags.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path

from . import argiro_schema, gastronomos_schema
from .build_local_catalog import normalize_search_token
from .full_schema import FACET_LABEL_FIELDS, normalize_recipe_detail
from .helpers import canonical_category
from .public_recipe_schema import PUBLIC_SOURCE_KEYS, normalize_public_recipe_payload


def source_taxonomy(record: dict) -> dict:
    payload = record["sourcePayload"]
    if record["sourceKey"] == "akis":
        normalized = normalize_recipe_detail(
            payload, source_url=record["sourceUrl"],
            sitemap_last_modified=record.get("sitemapLastModified", ""),
            associations=record["filterAssociations"], active=record["active"],
        )
        return {field: normalized[field] for field in (
            "categoryKeys", "category", "categoryLabel", "tags", *FACET_LABEL_FIELDS.values(),
        )}
    if record["sourceKey"] in PUBLIC_SOURCE_KEYS:
        normalized = normalize_public_recipe_payload(
            payload["jsonLd"], payload["htmlMetadata"],
            source_key=record["sourceKey"], source_url=record["sourceUrl"],
            provider_recipe_id=record["providerRecipeId"],
            sitemap_last_modified=record.get("sitemapLastModified", ""),
            active=record["active"],
        )
        return {field: normalized[field] for field in (
            "categoryKeys", "category", "categoryLabel", "tags", *FACET_LABEL_FIELDS.values(),
        )}
    provider = {"argiro": argiro_schema, "gastronomos": gastronomos_schema}[record["sourceKey"]]
    keys, facets, tags = provider._taxonomy(payload["htmlMetadata"], payload["jsonLd"])
    category = canonical_category(keys)
    return {
        "categoryKeys": keys, "category": category,
        "categoryLabel": provider.CATEGORY_LABELS[category], "tags": tags,
        **{field: facets[facet] for facet, field in FACET_LABEL_FIELDS.items()},
    }


def semantic_labels(values: list[str]) -> set[str]:
    return {normalize_search_token(value) for value in values}


def audit_sources(paths: list[Path], sample_limit: int = 20) -> dict:
    counts = Counter()
    category_changes = []
    tag_mismatches = []
    source_errors = []
    before = defaultdict(Counter)
    after = defaultdict(Counter)
    coverage = defaultdict(Counter)
    ingredients = defaultdict(Counter)
    tag_fields = ("tags", *FACET_LABEL_FIELDS.values())
    for path in paths:
        with path.open(encoding="utf-8") as stream:
            for line in stream:
                if not line.strip():
                    continue
                record = json.loads(line)
                source = record["sourceKey"]
                counts[source] += 1
                before[source][record["category"]] += 1
                ingredients[source].update(record.get("ingredientLabels", []))
                for field in tag_fields:
                    if not record.get(field):
                        coverage[source][field] += 1
                try:
                    expected = source_taxonomy(record)
                except (ValueError, KeyError, TypeError) as exc:
                    source_errors.append({"id": record["id"], "error": str(exc)})
                    continue
                after[source][expected["category"]] += 1
                if any(record[field] != expected[field] for field in ("categoryKeys", "category", "categoryLabel")):
                    category_changes.append({
                        "id": record["id"], "source": source, "title": record["title"],
                        "before": record["category"], "after": expected["category"],
                        "ingredientLabels": record.get("ingredientLabels", []),
                    })
                differences = {
                    field: {"stored": record.get(field, []), "derived": expected[field]}
                    for field in tag_fields
                    if semantic_labels(record.get(field, [])) != semantic_labels(expected[field])
                }
                if differences:
                    tag_mismatches.append({"id": record["id"], "source": source, "fields": differences})
    return {
        "recordCount": sum(counts.values()), "sourceCounts": dict(counts),
        "categoryCountsBefore": dict(before), "categoryCountsDerived": dict(after),
        "categoryChangeCount": len(category_changes),
        "primaryCategoryChangeCount": sum(item["before"] != item["after"] for item in category_changes),
        "categoryChanges": category_changes,
        "tagMismatchCount": len(tag_mismatches), "tagMismatchSamples": tag_mismatches[:sample_limit],
        "sourceErrorCount": len(source_errors), "sourceErrorSamples": source_errors[:sample_limit],
        "missingTagFamiliesBySource": dict(coverage),
        "ingredientLabelInventory": dict(ingredients),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("catalogs", nargs="*", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    paths = args.catalogs or [
        Path(__file__).parent / "output" / f"{source}-greek-full.jsonl"
        for source in ("akis", "argiro", "gastronomos", "tsoulis", "lucacos", "funkycook", "cookpad")
    ]
    report = audit_sources(paths)
    serialized = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(serialized, encoding="utf-8")
        print(json.dumps({key: report[key] for key in (
            "recordCount", "categoryChangeCount", "tagMismatchCount", "sourceErrorCount",
        )}))
    else:
        print(serialized)
    return int(bool(report["categoryChangeCount"] or report["tagMismatchCount"] or report["sourceErrorCount"]))


if __name__ == "__main__":
    raise SystemExit(main())
