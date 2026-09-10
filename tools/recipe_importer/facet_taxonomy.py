"""Reviewed equivalent filter labels for the offline app; preserve raw source evidence."""
from __future__ import annotations
import json
from pathlib import Path
try:
    from .ingredient_taxonomy import ingredient_token
    from .timing_guard import normalize_catalog_timing
except ImportError:  # direct script entrypoints
    from ingredient_taxonomy import ingredient_token
    from timing_guard import normalize_catalog_timing

FACET_FIELDS = {"diet": "dietLabels", "meal": "mealTypeLabels", "occasion": "occasionLabels", "method": "methodLabels", "cuisine": "cuisineLabels"}
FACET_ALIASES = json.loads(Path(__file__).with_name("facet_aliases.json").read_text(encoding="utf-8"))
ALIASES = {facet: {ingredient_token(alias): group[0] for group in groups for alias in group}
           for facet, groups in FACET_ALIASES.items()}

def canonical_facet_labels(facet: str, values: list[str]) -> list[str]:
    result = {}
    for value in values:
        for part in value.split(",") if facet == "cuisine" else [value]:
            label = ALIASES.get(facet, {}).get(ingredient_token(part), " ".join(part.split()))
            if label:
                result[ingredient_token(label)] = label
    return [result[key] for key in sorted(result)]

# Exact published recipeCuisine misclassifications observed in the source archive.
# Keep their original wording searchable while correcting only the catalog projection.
_MISPLACED_CUISINE_LABELS = {"diy", "vegan", "street"}


def normalize_catalog_facets(record):
    result = dict(record)
    cuisine_labels = []
    diet_labels = list(record.get("dietLabels", []))
    meal_labels = list(record.get("mealTypeLabels", []))
    preserved_cuisine_tags = []
    for value in record.get("cuisineLabels", []):
        for part in value.split(","):
            label = " ".join(part.split())
            token = ingredient_token(label)
            if token not in _MISPLACED_CUISINE_LABELS:
                cuisine_labels.append(label)
                continue
            preserved_cuisine_tags.append(label)
            if token == "vegan":
                diet_labels.append("Vegan")
            elif token == "street":
                meal_labels.append("Street food")
    result.update(cuisineLabels=cuisine_labels, dietLabels=diet_labels, mealTypeLabels=meal_labels)
    for facet, field in FACET_FIELDS.items():
        result[field] = canonical_facet_labels(facet, list(result.get(field, [])))
    aliases = {key: value for group in ALIASES.values() for key, value in group.items()}
    result["tags"] = list(dict.fromkeys(
        tag if ingredient_token(tag) in _MISPLACED_CUISINE_LABELS
        else aliases.get(ingredient_token(tag), tag)
        for tag in [*record.get("tags", []), *preserved_cuisine_tags]
    ))
    return normalize_catalog_timing(result)
