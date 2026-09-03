"""Pure helpers shared by the importer and the one-page inspector."""

from __future__ import annotations

import hashlib
import math
import re
import unicodedata
from collections.abc import Iterable, Mapping
from typing import Any


_DURATION_RE = re.compile(
    r"^P"
    r"(?:(?P<days>\d+(?:\.\d+)?)D)?"
    r"(?:T"
    r"(?:(?P<hours>\d+(?:\.\d+)?)H)?"
    r"(?:(?P<minutes>\d+(?:\.\d+)?)M)?"
    r"(?:(?P<seconds>\d+(?:\.\d+)?)S)?"
    r")?$",
    re.IGNORECASE,
)


# Coarse planner groups are derived only from publisher-owned taxonomy values.
# Recipe titles and free-form ingredient text are deliberately excluded: a
# substring classifier made "Τρουφάκια" match the old "φακ" lentil stem.
#
# Precedence is encoded in the returned list: terminal dessert/non-meal source
# categories win, followed by the publisher's exact sandwich/finger-food format,
# then the exact recipe category and official main-ingredient facets. The broad
# Snack bucket is never enough on its own: it also contains sweet bars, cereal,
# and breakfast recipes.
_CATEGORY_KEY_PRECEDENCE = (
    "dessert",
    "fish",
    "seafood",
    "legumes",
    "poultry",
    "meat",
    "pasta",
    "rice",
    "vegetables",
    "dirty",
)

_DESSERT_SOURCE_CATEGORY_SLUGS = {
    "glika",
    "ta-aghapimena-mas",
    "keik",
    "siropiasta",
    "mpiskota",
    "glyka-pshgeiou",
    "cheesecakes",
    "glikes-tartes",
    "glikes-pites",
    "tourtes",
    "paghota",
    "glika-tu-kutaliou",
    "sokolata",
}

_SOURCE_CATEGORY_KEYS = {
    # The source's nested "Κυρίως γεύμα" taxonomy.
    "kreas": "meat",
    "moskhari": "meat",
    "xirino": "meat",
    "xirino-1": "meat",
    "arni": "meat",
    "katsiki": "meat",
    "kuneli": "meat",
    "kinighi": "meat",
    "ospria": "legumes",
    "fakes": "legumes",
    "fasolia": "legumes",
    "gighantes": "legumes",
    "revythia": "legumes",
    "fava": "legumes",
    "mauromatika-fasolia": "legumes",
    "ladera": "vegetables",
    "lakhanika": "vegetables",
    "patata": "vegetables",
    "zimarika": "pasta",
    "zimarika-1": "pasta",
    "pulerika": "poultry",
    "kotopulo": "poultry",
    "kotopulo-1": "poultry",
    "galopoula": "poultry",
    "papia": "poultry",
    "thalassina": "seafood",
    "psaria": "fish",
    "ryzi": "rice",
}

# These exact source categories describe content that must never be proposed as
# one of the app's main-meal groups, even if an official ingredient facet is
# present (for example rice pudding or a vegetable-based cake).
_TERMINAL_OTHER_SOURCE_CATEGORIES = {
    "marmelades",
    "rofimata-pota",
    "smoothies",
    "ximi",
    "detox",
    "cocktails",
    "mi-alkooloukha-pota",
}

_SOURCE_FORMAT_FALLBACK_KEYS = {
    "finger-food": "dirty",
    "santuits": "dirty",
}

# IDs come from the official ingredient facet captured in the manifest.
# Unknown IDs intentionally remain unclassified until the taxonomy mapping is
# reviewed; guessing is worse than showing "Άλλο" in a meal planner.
_INGREDIENT_FACET_KEYS = {
    **{str(value): "meat" for value in range(129, 135)},
    **{str(value): "poultry" for value in range(135, 138)},
    "138": "pasta",
    **{str(value): "legumes" for value in range(139, 145)},
    **{str(value): "seafood" for value in (145, 147, 148, 149, 150, 151)},
    "155": "rice",
    "158": "rice",
    **{str(value): "fish" for value in (160, 161, 164, 165, 167, 168, 171)},
    **{
        str(value): "vegetables"
        for value in (157, 172, 173, 174, 175, 177, 178, 179, 180, 181, 182, 252)
    },
}

_MEAL_TYPE_FALLBACK_KEYS = {
    "32": "dirty",  # Σάντουιτς
    "92": "dirty",  # Finger food
}

_DESSERT_CATEGORY_ID = "34"

# Exact aliases are retained only for metadata-only JSON-LD inspection. They
# are compared as complete values (or comma-separated keyword values), never as
# substrings and never against the recipe title.
_EXACT_METADATA_ALIASES = {
    "dessert": "dessert",
    "desserts": "dessert",
    "γλυκο": "dessert",
    "γλυκα": "dessert",
    "fish": "fish",
    "ψαρι": "fish",
    "ψαρια": "fish",
    "salmon": "fish",
    "σολομος": "fish",
    "seafood": "seafood",
    "θαλασσινα": "seafood",
    "legume": "legumes",
    "legumes": "legumes",
    "οσπρια": "legumes",
    "lentil": "legumes",
    "lentils": "legumes",
    "φακες": "legumes",
    "chickpea": "legumes",
    "chickpeas": "legumes",
    "ρεβιθια": "legumes",
    "chicken": "poultry",
    "κοτοπουλο": "poultry",
    "turkey": "poultry",
    "γαλοπουλα": "poultry",
    "meat": "meat",
    "κρεας": "meat",
    "beef": "meat",
    "μοσχαρι": "meat",
    "pork": "meat",
    "χοιρινο": "meat",
    "pasta": "pasta",
    "ζυμαρικα": "pasta",
    "rice": "rice",
    "ρυζι": "rice",
    "vegetables": "vegetables",
    "λαχανικα": "vegetables",
    "street food": "dirty",
    "street-food": "dirty",
    "sandwich": "dirty",
    "σαντουιτς": "dirty",
}

_CANONICAL_CATEGORY_ALIASES = {
    "dessert": "dessert",
    "other": "other",
    "legumes": "legumes",
    "poultry": "poultry",
    "vegetables": "vegetables",
    "meat": "meat",
    "fish": "fish",
    "seafood": "fish",
    "dirty": "street_food",
    "street_food": "street_food",
    "pasta": "pasta_rice",
    "rice": "pasta_rice",
    "pasta_rice": "pasta_rice",
}


def normalize_text(value: object) -> str:
    """Case-fold text and remove accents so Greek/English matching is stable."""

    normalized = unicodedata.normalize("NFKD", str(value or "")).casefold()
    without_accents = "".join(
        char for char in normalized if not unicodedata.combining(char)
    )
    return " ".join(re.sub(r"[^\w]+", " ", without_accents).split())


def _text_values(value: object) -> Iterable[str]:
    if value is None:
        return ()
    if isinstance(value, str):
        return (value,)
    if isinstance(value, Mapping):
        return tuple(str(item) for item in value.values())
    if isinstance(value, Iterable):
        return tuple(str(item) for item in value)
    return (str(value),)


def _ordered_category_keys(values: Iterable[str]) -> list[str]:
    found = set(values)
    return [key for key in _CATEGORY_KEY_PRECEDENCE if key in found]


def _association_ids(associations: object, facet: str) -> tuple[str, ...]:
    if not isinstance(associations, Mapping):
        return ()
    values = associations.get(facet)
    if not isinstance(values, Iterable) or isinstance(values, (str, bytes, Mapping)):
        return ()
    result = []
    for value in values:
        if isinstance(value, Mapping):
            identifier = str(value.get("id") or "").strip()
            if identifier:
                result.append(identifier)
    return tuple(result)


def classify_official_category_keys(
    source_category: object,
    associations: object = None,
) -> list[str]:
    """Classify using exact official recipe/facet taxonomy metadata only.

    The source recipe category has the strongest authority. Explicit dessert,
    drink, bread, fruit, and condiment categories are terminal other values so
    an ingredient facet cannot turn them into main meals. For a generic source
    category, an official sandwich/finger-food value is authoritative for the
    Street Food planner group. Exact recipe and main-ingredient categories are
    retained as secondary keys. The source's generic Snack value is deliberately
    not enough: it also contains clearly sweet snacks and breakfast recipes, so
    it fails closed to ``other``.
    """

    category = source_category if isinstance(source_category, Mapping) else {}
    slug = str(category.get("slug") or "").strip().casefold()
    category_id = str(category.get("id") or "").strip()
    parent_id = str(category.get("parent_id") or "").strip()
    meal_type_ids = set(_association_ids(associations, "meal_type"))
    if (
        category_id == _DESSERT_CATEGORY_ID
        or parent_id == _DESSERT_CATEGORY_ID
        or slug in _DESSERT_SOURCE_CATEGORY_SLUGS
        or _DESSERT_CATEGORY_ID in meal_type_ids
    ):
        return ["dessert"]
    if slug in _TERMINAL_OTHER_SOURCE_CATEGORIES:
        return ["other"]

    ingredient_keys = _ordered_category_keys(
        _INGREDIENT_FACET_KEYS[identifier]
        for identifier in _association_ids(associations, "ingredient")
        if identifier in _INGREDIENT_FACET_KEYS
    )
    source_key = _SOURCE_CATEGORY_KEYS.get(slug)
    format_key = _SOURCE_FORMAT_FALLBACK_KEYS.get(slug)
    if not format_key:
        format_key = next(
            (
                key
                for identifier, key in _MEAL_TYPE_FALLBACK_KEYS.items()
                if identifier in meal_type_ids
            ),
            "",
        )
    secondary_keys = [
        *([source_key] if source_key else []),
        *(key for key in ingredient_keys if key != source_key),
    ]
    if format_key:
        return [format_key, *(key for key in secondary_keys if key != format_key)]
    if secondary_keys:
        return secondary_keys
    return ["other"]


def classify_category_keys(
    title: object,
    categories: object = None,
    keywords: object = None,
) -> list[str]:
    """Classify metadata-only records using exact publisher metadata values.

    title remains in the signature for compatibility, but is intentionally
    ignored. Full API records use classify_official_category_keys.
    """

    del title
    matched = []
    for raw in (*_text_values(categories), *_text_values(keywords)):
        for part in re.split(r"[,;|]", raw):
            key = _EXACT_METADATA_ALIASES.get(normalize_text(part))
            if key:
                matched.append(key)
    return _ordered_category_keys(matched) or ["other"]


def canonical_category(category_keys: Iterable[str]) -> str:
    """Map the first recognized metadata tag to an Android MealCategory key."""

    for key in category_keys:
        mapped = _CANONICAL_CATEGORY_ALIASES.get(key)
        if mapped is not None:
            return mapped
    # Fail closed into an explicit value that can be audited and filtered.
    return "other"


def parse_duration_minutes(value: object) -> int | None:
    """Convert an ISO-8601 day/time duration to whole minutes, rounded up."""

    if value is None or value == "":
        return None
    if isinstance(value, bool):
        raise ValueError("boolean is not a duration")
    if isinstance(value, (int, float)):
        if not math.isfinite(float(value)) or float(value) < 0:
            raise ValueError(f"invalid duration: {value!r}")
        return math.ceil(float(value))

    match = _DURATION_RE.fullmatch(str(value).strip())
    if not match or not any(match.groupdict().values()):
        raise ValueError(f"unsupported ISO-8601 duration: {value!r}")
    parts = {name: float(number or 0) for name, number in match.groupdict().items()}
    total_seconds = (
        parts["days"] * 86_400
        + parts["hours"] * 3_600
        + parts["minutes"] * 60
        + parts["seconds"]
    )
    return math.ceil(total_seconds / 60)


def derive_total_minutes(
    prep_minutes: int | None,
    cook_minutes: int | None,
    explicit_total_minutes: int | None = None,
) -> int | None:
    if explicit_total_minutes is not None:
        return explicit_total_minutes
    known = [value for value in (prep_minutes, cook_minutes) if value is not None]
    return sum(known) if known else None


def _has_type(value: object, expected: str) -> bool:
    types = value if isinstance(value, list) else [value]
    return any(str(item).casefold() == expected.casefold() for item in types)


def count_recipe_steps(instructions: object) -> int:
    """Count HowToStep/string leaves, including steps nested in sections."""

    if instructions is None:
        return 0
    if isinstance(instructions, str):
        return int(bool(instructions.strip()))
    if isinstance(instructions, list):
        return sum(count_recipe_steps(item) for item in instructions)
    if not isinstance(instructions, Mapping):
        return 0
    if _has_type(instructions.get("@type"), "HowToStep"):
        return 1
    for key in ("itemListElement", "steps", "recipeInstructions"):
        if key in instructions:
            return count_recipe_steps(instructions[key])
    return 0


def count_preparation_sections(instructions: object) -> int:
    """Count named HowToSection components; a flat non-empty method is one."""

    def section_count(value: object) -> int:
        if isinstance(value, list):
            return sum(section_count(item) for item in value)
        if isinstance(value, Mapping):
            own = int(_has_type(value.get("@type"), "HowToSection"))
            nested = section_count(value.get("itemListElement"))
            return own + nested
        return 0

    sections = section_count(instructions)
    if sections:
        return sections
    return int(count_recipe_steps(instructions) > 0)


def classify_ease(
    preparation_count: int,
    step_count: int,
    total_minutes: int | None = None,
) -> str:
    """Return the app's structural ease band; duration is intentionally separate."""

    del total_minutes
    if preparation_count <= 0 and step_count <= 0:
        return "unknown"
    if preparation_count >= 3 or step_count >= 10:
        return "involved"
    if preparation_count <= 1 and 1 <= step_count <= 5:
        return "easy"
    return "moderate"


def rating_to_ten(aggregate_rating: object) -> float | None:
    """Normalize Schema.org AggregateRating onto a 0–10 scale."""

    if not isinstance(aggregate_rating, Mapping):
        return None
    try:
        value = float(aggregate_rating.get("ratingValue"))
        best = float(aggregate_rating.get("bestRating", 5))
    except (TypeError, ValueError):
        return None
    if not math.isfinite(value) or not math.isfinite(best) or best <= 0:
        return None
    return round(max(0.0, min(10.0, value / best * 10.0)), 2)


def stable_random_key(recipe_id: object) -> float:
    """Return a stable [0, 1) key suitable for wrap-around random queries."""

    digest = hashlib.sha256(f"spoon-recipe:{recipe_id}".encode("utf-8")).digest()
    return int.from_bytes(digest[:8], "big") / 2**64
