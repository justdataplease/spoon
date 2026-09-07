"""Reviewed cross-provider ingredient facets; raw recipe ingredients stay verbatim.

ingredient_aliases.json is authoritative for the Firestore summary/detail projections.
Source payloads and source-derived archival records retain publisher spelling.
"""
from __future__ import annotations

import json
import hashlib
from collections.abc import Mapping
from typing import Any
from pathlib import Path
import unicodedata

VOCABULARY_PATH = Path(__file__).with_name("ingredient_aliases.json")


def ingredient_token(value: str) -> str:
    folded = unicodedata.normalize("NFKD", value.casefold())
    return " ".join("".join(
        c if c.isalnum() else " " for c in folded
        if unicodedata.category(c) != "Mn"
    ).split())


INGREDIENT_GROUPS: list[list[str]] = json.loads(VOCABULARY_PATH.read_text(encoding="utf-8"))
_GROUP_BY_TOKEN: dict[str, list[str]] = {}
for _group in INGREDIENT_GROUPS:
    if not _group or not all(isinstance(label, str) and ingredient_token(label) for label in _group):
        raise ValueError("Ingredient groups require a display label and non-empty aliases")
    for _alias in _group:
        _token = ingredient_token(_alias)
        if _token in _GROUP_BY_TOKEN:
            raise ValueError(f"Ingredient alias belongs to multiple groups: {_alias}")
        _GROUP_BY_TOKEN[_token] = _group


def is_reviewed_ingredient(value: str) -> bool:
    return ingredient_token(value) in _GROUP_BY_TOKEN


def canonical_ingredient_label(value: str) -> str:
    cleaned = unicodedata.normalize("NFC", " ".join(value.split()))
    group = _GROUP_BY_TOKEN.get(ingredient_token(cleaned))
    return group[0] if group else cleaned


def canonical_ingredient_labels(values: list[str]) -> list[str]:
    labels = {ingredient_token(value): value for raw in values
              if (value := canonical_ingredient_label(raw))}
    return [labels[token] for token in sorted(labels)]


def expanded_ingredient_tokens(value: str) -> list[str]:
    token = ingredient_token(value)
    if not token:
        return []
    return [ingredient_token(alias) for alias in _GROUP_BY_TOKEN.get(token, [value])]



def normalize_ingredient_facets(record: Mapping[str, Any]) -> dict[str, Any]:
    """Canonicalize public ingredient facets and their tags without mutating evidence."""
    result = dict(record)
    if "ingredientLabels" not in record:
        return result
    values = record["ingredientLabels"]
    if not isinstance(values, list) or not all(isinstance(value, str) for value in values):
        raise ValueError("ingredientLabels must be a string list")
    result["ingredientLabels"] = canonical_ingredient_labels(values)
    if "tags" in record:
        ingredient_tokens = {ingredient_token(value) for value in values}
        result["tags"] = list(dict.fromkeys(
            canonical_ingredient_label(tag) if ingredient_token(tag) in ingredient_tokens else tag
            for tag in record["tags"]
        ))
    return result


INGREDIENT_TAXONOMY_VERSION = "sha256:" + hashlib.sha256(VOCABULARY_PATH.read_bytes()).hexdigest()
