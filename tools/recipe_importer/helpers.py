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


# These are deliberately broad, app-owned food groups. They are not a copy of
# the publisher's category taxonomy. Terms are normalized before matching.
_CATEGORY_TERMS: tuple[tuple[str, tuple[str, ...]], ...] = (
    (
        "fish",
        (
            "fish",
            "salmon",
            "tuna",
            "cod",
            "sardine",
            "sea bass",
            "sea bream",
            "ψαρι",
            "σολομ",
            "τονο",
            "μπακαλιαρ",
            "σαρδελ",
            "τσιπουρ",
            "λαβρακ",
        ),
    ),
    (
        "seafood",
        (
            "seafood",
            "shrimp",
            "prawn",
            "squid",
            "octopus",
            "mussel",
            "θαλασσιν",
            "γαριδ",
            "καλαμαρ",
            "χταποδ",
            "μυδι",
        ),
    ),
    (
        "legumes",
        (
            "legume",
            "lentil",
            "chickpea",
            "bean",
            "fava",
            "οσπρι",
            "φακ",
            "ρεβιθ",
            "φασολ",
            "γιγαντ",
            "φαβα",
        ),
    ),
    (
        "poultry",
        (
            "chicken",
            "turkey",
            "duck",
            "κοτοπουλ",
            "γαλοπουλ",
            "παπια",
            "πουλερικ",
        ),
    ),
    (
        "meat",
        (
            "meat",
            "beef",
            "pork",
            "lamb",
            "veal",
            "steak",
            "κρεα",
            "μοσχαρ",
            "χοιριν",
            "αρν",
            "κατσικ",
            "μπριζολ",
        ),
    ),
    (
        "pasta",
        ("pasta", "spaghetti", "linguine", "orzo", "ζυμαρ", "μακαρον", "κριθαρακ"),
    ),
    (
        "rice",
        ("rice", "risotto", "ρυζ", "ριζοτο"),
    ),
    (
        "vegetables",
        ("vegetable", "veggie", "λαχανικ", "λαδερα", "ladera"),
    ),
    (
        "dirty",
        (
            "street food",
            "burger",
            "pizza",
            "hot dog",
            "gyros",
            "kebab",
            "sandwich",
            "loaded fries",
            "μπεργκερ",
            "πιτσα",
            "χοτ ντογκ",
            "γυρο",
            "σουβλακ",
            "κεμπαπ",
            "σαντουιτς",
        ),
    ),
)

_CANONICAL_CATEGORY_ALIASES = {
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


def classify_category_keys(
    title: object,
    categories: object = None,
    keywords: object = None,
) -> list[str]:
    """Infer coarse planner groups without retaining source descriptions."""

    haystack = " ".join(
        normalize_text(part)
        for part in (*_text_values(title), *_text_values(categories), *_text_values(keywords))
    )
    matched = [key for key, terms in _CATEGORY_TERMS if any(term in haystack for term in terms)]
    return matched or ["other"]


def canonical_category(category_keys: Iterable[str]) -> str:
    """Map the first recognized metadata tag to an Android MealCategory key."""

    for key in category_keys:
        mapped = _CANONICAL_CATEGORY_ALIASES.get(key)
        if mapped is not None:
            return mapped
    # Empty is intentional: MealCategory.ANY will still show the record, while
    # a false food-group assignment would produce misleading weekly plans.
    return ""


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
