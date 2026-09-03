"""Normalize authorized full Greek recipe data into Firestore-safe records."""

from __future__ import annotations

import html
import hashlib
import json
import math
import re
from collections.abc import Mapping, Sequence
from datetime import datetime
from html.parser import HTMLParser
from typing import Any
from urllib.parse import urljoin, urlsplit, urlunsplit

try:
    from .helpers import canonical_category, classify_category_keys, classify_ease, stable_random_key
except ImportError:  # pragma: no cover - direct script execution
    from helpers import canonical_category, classify_category_keys, classify_ease, stable_random_key


DETAIL_SCHEMA_VERSION = "akis-full-v1"
MAX_FIRESTORE_DOCUMENT_BYTES = 900 * 1024
MAX_SOURCE_DEPTH = 32
FACET_KEYS = ("diet", "meal_type", "occasion", "method", "cuisine", "ingredient")
FACET_LABEL_FIELDS = {
    "diet": "dietLabels",
    "meal_type": "mealTypeLabels",
    "occasion": "occasionLabels",
    "method": "methodLabels",
    "cuisine": "cuisineLabels",
    "ingredient": "ingredientLabels",
}
DIET_LABELS = {
    "ve": "Χορτοφαγική",
    "vg": "Αυστηρά χορτοφαγική (vegan)",
    "gf": "Χωρίς γλουτένη",
    "df": "Χωρίς γαλακτοκομικά",
    "ef": "Χωρίς αυγά",
    "sf": "Χωρίς σόγια",
    "nf": "Χωρίς ξηρούς καρπούς",
    "ls": "Χαμηλή σε ζάχαρη",
}
CATEGORY_LABELS = {
    "legumes": "Όσπρια",
    "fish": "Ψάρι",
    "meat": "Κρέας",
    "poultry": "Κοτόπουλο",
    "vegetables": "Λαχανικά",
    "street_food": "Βρώμικο",
    "pasta_rice": "Ζυμαρικά",
}
FULL_REQUIRED_FIELDS = {
    "detailSchemaVersion", "sourceRecipeId", "slug", "description", "seoTitle",
    "seoDescription", "categoryLabel", "categorySourceId", "ratingCount",
    "rating1", "rating2", "rating3", "rating4", "rating5", "waitMinutes",
    "sourceDifficulty", "servings", "imageUrls", "shortUrl", "dietLabels",
    "mealTypeLabels", "occasionLabels", "methodLabels", "cuisineLabels",
    "ingredientLabels", "quickRecipe", "videoUrls", "ingredientSections",
    "methodSections", "tips", "nutritionTips", "nutritionPer",
    "nutritionSections", "equipment", "authorName", "published", "shares",
    "sponsorLogoUrl", "createdAt", "updatedAtEpochMillis", "sourcePayload",
    "filterAssociations", "sitemapLastModified", "publishedAt",
}
FULL_OPTIONAL_FIELDS = {"retiredDetectedAt"}


class FullSchemaError(ValueError):
    """The source cannot be represented safely and completely."""


class _PlainTextParser(HTMLParser):
    BLOCKS = {
        "br", "p", "div", "li", "ul", "ol", "section", "article",
        "h1", "h2", "h3", "h4", "h5", "h6",
    }

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self.suppressed = 0

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        del attrs
        if tag.casefold() in {"script", "style"}:
            self.suppressed += 1
        elif tag.casefold() in self.BLOCKS:
            self.parts.append("\n")

    def handle_endtag(self, tag: str) -> None:
        if tag.casefold() in {"script", "style"} and self.suppressed:
            self.suppressed -= 1
        elif tag.casefold() in self.BLOCKS:
            self.parts.append("\n")

    def handle_data(self, data: str) -> None:
        if not self.suppressed:
            self.parts.append(data)


_CONTROL_RE = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
_WAIT_RE = re.compile(
    r"(?P<number>\d+(?:[.,]\d+)?)\s*"
    r"(?P<unit>[ηή]μερ\w*|day\w*|[ωώ]ρ\w*|hour\w*|λεπτ\w*|min\w*)",
    re.IGNORECASE,
)


def _clean(value: str) -> str:
    decoded = value
    for _ in range(5):
        next_value = html.unescape(decoded)
        if next_value == decoded:
            break
        decoded = next_value
    return (
        _CONTROL_RE.sub("", decoded)
        .replace("\r\n", "\n")
        .replace("\r", "\n")
    )


def plain_text(value: object) -> str:
    """Turn source HTML fragments into safe plain Unicode for app rendering."""
    if value is None:
        return ""
    parser = _PlainTextParser()
    parser.feed(_clean(str(value)))
    parser.close()
    lines = [" ".join(line.split()) for line in "".join(parser.parts).splitlines()]
    return "\n".join(line for line in lines if line).strip()


def sanitize_source_payload(value: object, *, _depth: int = 0) -> Any:
    """Convert to JSON/Firestore values without truncating or dropping API fields."""
    if _depth > MAX_SOURCE_DEPTH:
        raise FullSchemaError(f"sourcePayload exceeds depth {MAX_SOURCE_DEPTH}")
    if value is None or isinstance(value, (bool, int)):
        if isinstance(value, int) and not -(2**63) <= value < 2**63:
            raise FullSchemaError("sourcePayload integer is outside Firestore's 64-bit range")
        return value
    if isinstance(value, float):
        if not math.isfinite(value):
            raise FullSchemaError("sourcePayload contains a non-finite number")
        return value
    if isinstance(value, str):
        return _clean(value)
    if isinstance(value, Mapping):
        result: dict[str, Any] = {}
        for raw_key, item in value.items():
            key = _clean(str(raw_key))
            if not key or key in result:
                raise FullSchemaError("sourcePayload contains an empty or duplicate object key")
            result[key] = sanitize_source_payload(item, _depth=_depth + 1)
        return result
    if isinstance(value, Sequence) and not isinstance(value, (bytes, bytearray, memoryview)):
        return [sanitize_source_payload(item, _depth=_depth + 1) for item in value]
    raise FullSchemaError(f"unsupported sourcePayload type: {type(value).__name__}")


def _mapping(value: object) -> Mapping[str, Any]:
    return value if isinstance(value, Mapping) else {}


def _list(value: object) -> list[Any]:
    return list(value) if isinstance(value, (list, tuple)) else []


def _string(value: object) -> str:
    return plain_text(value)


def _int(value: object, default: int = 0) -> int:
    if value is None or value == "" or isinstance(value, bool):
        return default
    try:
        return max(0, int(float(str(value).replace(",", "."))))
    except (TypeError, ValueError, OverflowError):
        return default


def _float(value: object) -> float | None:
    if value is None or value == "" or isinstance(value, bool):
        return None
    try:
        result = float(str(value).replace(",", "."))
    except (TypeError, ValueError):
        return None
    return result if math.isfinite(result) else None


def _unique(values: Sequence[str]) -> list[str]:
    return list(dict.fromkeys(item for item in values if item))


def _safe_url(value: object, *, base_url: str = "") -> str:
    text = _clean(str(value or "")).strip()
    candidate = urljoin(base_url, text) if base_url else text
    parts = urlsplit(candidate)
    if (
        parts.scheme.casefold() != "https"
        or not parts.hostname
        or parts.username
        or parts.password
        or parts.port not in (None, 443)
    ):
        return ""
    return urlunsplit(("https", parts.netloc, parts.path, parts.query, parts.fragment))


def parse_wait_minutes(value: object) -> int:
    if value is None or value == "" or value == "-":
        return 0
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return max(0, math.ceil(float(value)))
    text = plain_text(value).casefold()
    matches = list(_WAIT_RE.finditer(text))
    if matches:
        total = 0.0
        for match in matches:
            amount = float(match.group("number").replace(",", "."))
            unit = match.group("unit")
            multiplier = 1440 if unit.startswith(("ημερ", "ήμερ", "day")) else 60 if unit.startswith(("ωρ", "ώρ", "hour")) else 1
            total += amount * multiplier
        return max(0, math.ceil(total))
    numbers = re.findall(r"\d+(?:[.,]\d+)?", text)
    return max(0, math.ceil(float(numbers[-1].replace(",", ".")))) if numbers else 0


def _text_list(value: object) -> list[str]:
    if value is None or value == "":
        return []
    if isinstance(value, str):
        text = plain_text(value)
        return [text] if text else []
    if isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray)):
        return _unique([text for item in value for text in _text_list(item)])
    if isinstance(value, Mapping):
        preferred = [value[key] for key in ("title", "text", "tip", "description", "content") if key in value]
        return _unique([text for item in (preferred or list(value.values())) for text in _text_list(item)])
    text = plain_text(value)
    return [text] if text else []


def _normalize_associations(value: object) -> dict[str, list[dict[str, str]]]:
    source = _mapping(value)
    result: dict[str, list[dict[str, str]]] = {}
    for facet in FACET_KEYS:
        options = []
        for raw in _list(source.get(facet)):
            option = _mapping(raw)
            option_id, title = _string(option.get("id")), _string(option.get("title"))
            if option_id:
                options.append({"id": option_id, "title": title or option_id})
        result[facet] = sorted(options, key=lambda item: (item["title"].casefold(), item["id"]))
    return result


def _ingredient_sections(value: object, source_url: str) -> list[dict[str, Any]]:
    sections: list[dict[str, Any]] = []
    for raw_section in _list(value):
        section = _mapping(raw_section)
        ingredients: list[dict[str, str]] = []
        for raw_ingredient in _list(section.get("ingredients")):
            ingredient = _mapping(raw_ingredient)
            conversions = _mapping(ingredient.get("conversions"))
            uk, us = _mapping(conversions.get("uk")), _mapping(conversions.get("us"))
            ingredients.append(
                {
                    "title": _string(ingredient.get("title")),
                    "unit": _string(ingredient.get("unit")),
                    "quantity": _string(ingredient.get("quantity")),
                    "info": _string(ingredient.get("info")),
                    "internalLink": _safe_url(ingredient.get("internal_link"), base_url=source_url),
                    "externalLink": _safe_url(ingredient.get("external_link")),
                    "ukUnit": _string(uk.get("unit")),
                    "ukQuantity": _string(uk.get("quantity")),
                    "usUnit": _string(us.get("unit")),
                    "usQuantity": _string(us.get("quantity")),
                }
            )
        sections.append({"title": _string(section.get("title")), "ingredients": ingredients})
    return sections


def _method_sections(value: object) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for raw_section in _list(value):
        section = _mapping(raw_section)
        steps = []
        for raw_step in _list(section.get("steps")):
            text = _string(raw_step.get("step") or raw_step.get("text")) if isinstance(raw_step, Mapping) else _string(raw_step)
            if text:
                steps.append(text)
        result.append({"title": _string(section.get("section") or section.get("title")), "steps": steps})
    return result


_NUTRITION_FIELDS = {
    "kcalPortion": "kcal_portion_abs",
    "kcalPortionPercent": "kcal_portion_per",
    "kcal100g": "kcal_grams_abs",
    "kcal100gPercent": "kcal_grams_per",
    "fatPortion": "fat_portion_abs",
    "fatPortionPercent": "fat_portion_per",
    "fat100g": "fat_grams_abs",
    "fat100gPercent": "fat_grams_per",
    "saturatedFatPortion": "satfat_portion_abs",
    "saturatedFatPortionPercent": "satfat_portion_per",
    "saturatedFat100g": "satfat_grams_abs",
    "saturatedFat100gPercent": "satfat_grams_per",
    "carbsPortion": "carbs_portion_abs",
    "carbsPortionPercent": "carbs_portion_per",
    "carbs100g": "carbs_grams_abs",
    "carbs100gPercent": "carbs_grams_per",
    "sugarsPortion": "sugars_portion_abs",
    "sugarsPortionPercent": "sugars_portion_per",
    "sugars100g": "sugars_grams_abs",
    "sugars100gPercent": "sugars_grams_per",
    "proteinPortion": "protein_portion_abs",
    "proteinPortionPercent": "protein_portion_per",
    "protein100g": "protein_grams_abs",
    "protein100gPercent": "protein_grams_per",
    "fiberPortion": "fiber_portion_abs",
    "fiberPortionPercent": "fiber_portion_per",
    "fiber100g": "fiber_grams_abs",
    "fiber100gPercent": "fiber_grams_per",
    "sodiumPortion": "sodium_portion_abs",
    "sodiumPortionPercent": "sodium_portion_per",
    "sodium100g": "sodium_grams_abs",
    "sodium100gPercent": "sodium_grams_per",
}


def _nutrition(value: object) -> tuple[str, list[dict[str, str]]]:
    nutrition = _mapping(value)
    sections = []
    for raw in _list(nutrition.get("sections")):
        section = _mapping(raw)
        normalized = {"title": _string(section.get("title"))}
        normalized.update({target: _string(section.get(source)) for target, source in _NUTRITION_FIELDS.items()})
        sections.append(normalized)
    return _string(nutrition.get("nutrition_per")), sections


def _epoch_millis(value: object) -> int:
    text = str(value or "").strip()
    if not text:
        return 0
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        return 0
    return max(0, int(parsed.timestamp() * 1000)) if parsed.tzinfo else 0


def normalize_recipe_detail(
    payload: Mapping[str, Any],
    *,
    source_url: str,
    sitemap_last_modified: str | None,
    associations: Mapping[str, object] | None = None,
    active: bool = True,
) -> dict[str, Any]:
    """Normalize one official Greek API detail object for Android and Firestore."""
    source_payload = sanitize_source_payload(payload)
    if not isinstance(source_payload, dict):
        raise FullSchemaError("recipe detail payload must be an object")
    source_recipe_id = _int(source_payload.get("id"), default=-1)
    if source_recipe_id < 0:
        raise FullSchemaError("recipe detail payload has no numeric id")
    title = _string(source_payload.get("title"))
    if not title:
        raise FullSchemaError(f"recipe {source_recipe_id} has no title")

    normalized_associations = _normalize_associations(associations or {})
    label_fields: dict[str, list[str]] = {}
    for facet, target in FACET_LABEL_FIELDS.items():
        options = normalized_associations[facet]
        labels = [DIET_LABELS.get(item["id"], item["title"]) for item in options] if facet == "diet" else [item["title"] for item in options]
        label_fields[target] = _unique(labels)

    raw_category = _mapping(source_payload.get("category"))
    category_slug = _string(raw_category.get("slug"))
    category_keys = classify_category_keys(
        title,
        [category_slug, *label_fields["ingredientLabels"]],
        [*label_fields["mealTypeLabels"], *label_fields["occasionLabels"], *label_fields["cuisineLabels"]],
    )
    category = canonical_category(category_keys)
    category_label = CATEGORY_LABELS.get(category, "Άλλο")
    prep_minutes = _int(source_payload.get("make_time"))
    cook_minutes = _int(source_payload.get("bake_time"))
    wait_minutes = parse_wait_minutes(source_payload.get("localized_wait_time"))
    method_sections = _method_sections(source_payload.get("method"))
    ingredient_sections = _ingredient_sections(source_payload.get("ingredient_sections"), source_url)
    step_count = sum(len(section["steps"]) for section in method_sections)
    preparation_count = sum(bool(section["steps"]) for section in method_sections)
    total_minutes = prep_minutes + cook_minutes + wait_minutes
    ease = classify_ease(preparation_count, step_count, total_minutes)

    average = _float(source_payload.get("average_score"))
    rating = 0.0 if average is None else max(0.0, min(10.0, average * 2 if average <= 5 else average))
    rating = round(rating, 2)
    assets = [_mapping(item) for item in _list(source_payload.get("assets"))]
    image_urls = _unique([_safe_url(asset.get("url")) for asset in assets])
    video_urls = []
    for field in ("video_url", "sl_video_url", "fb_video"):
        raw_value = source_payload.get(field)
        for candidate in raw_value if isinstance(raw_value, list) else [raw_value]:
            if isinstance(candidate, Mapping):
                candidate = candidate.get("url")
            video_urls.append(_safe_url(candidate))
    video_urls = _unique(video_urls)
    sponsor = source_payload.get("sponsor_logo")
    if isinstance(sponsor, Mapping):
        sponsor = sponsor.get("url") or sponsor.get("src")
    nutrition_per, nutrition_sections = _nutrition(source_payload.get("nutrition"))
    equipment = _unique([
        _string(item.get("title") if isinstance(item, Mapping) else item)
        for item in _list(source_payload.get("equipment_used"))
    ])
    author_name = " ".join(item for item in (
        _string(source_payload.get("user_first_name")),
        _string(source_payload.get("user_last_name")),
    ) if item)
    source_updated_at = _clean(str(source_payload.get("updated_at") or "")).strip()
    published = bool(_int(source_payload.get("published")))
    quick_recipe = any(
        item["id"] == "75" or "γρήγορ" in item["title"].casefold()
        for item in normalized_associations["occasion"]
    )
    labels = [label for group in label_fields.values() for label in group]
    # Tags are rendered in the Greek app, so keep internal category keys out.
    tags = _unique([category_label, *labels])

    record: dict[str, Any] = {
        "id": str(source_recipe_id),
        "detailSchemaVersion": DETAIL_SCHEMA_VERSION,
        "sourceRecipeId": source_recipe_id,
        "slug": _string(source_payload.get("slug")),
        "title": title,
        "description": _string(source_payload.get("extra_description")),
        "seoTitle": _string(source_payload.get("seo_title")),
        "seoDescription": _string(source_payload.get("seo_description")),
        "categoryKeys": category_keys,
        "category": category,
        "categoryLabel": category_label,
        "categorySourceId": _int(raw_category.get("id") or source_payload.get("recipe_category_id")),
        "rating10": rating,
        "rating": rating,
        "ratingCount": _int(source_payload.get("rates_sum")),
        "rating1": _int(source_payload.get("one")),
        "rating2": _int(source_payload.get("two")),
        "rating3": _int(source_payload.get("three")),
        "rating4": _int(source_payload.get("four")),
        "rating5": _int(source_payload.get("five")),
        "prepMinutes": prep_minutes,
        "cookMinutes": cook_minutes,
        "waitMinutes": wait_minutes,
        "totalMinutes": total_minutes,
        "sourceDifficulty": _string(source_payload.get("difficulty")),
        "ease": ease,
        "randomKey": stable_random_key(source_recipe_id),
        "stepCount": step_count,
        "preparationCount": preparation_count,
        "servings": _string(source_payload.get("shares")),
        "language": "el",
        "imageUrl": image_urls[0] if image_urls else "",
        "imageUrls": image_urls,
        "source": "akispetretzikis.com",
        "sourceUrl": source_url,
        "shortUrl": _safe_url(source_payload.get("short_url")),
        "sourceName": "Άκης Πετρετζίκης",
        "tags": tags,
        **label_fields,
        "quickRecipe": quick_recipe,
        "videoUrls": video_urls,
        "ingredientSections": ingredient_sections,
        "methodSections": method_sections,
        "tips": _unique([
            *_text_list(source_payload.get("tip")),
            *_text_list(source_payload.get("extra_asterisk")),
        ]),
        "nutritionTips": _text_list(source_payload.get("nutrition_tips")),
        "nutritionPer": nutrition_per,
        "nutritionSections": nutrition_sections,
        "equipment": equipment,
        "authorName": author_name,
        "published": published,
        "publishedAt": _clean(str(source_payload.get("created_at") or "")).strip(),
        "shares": _int(source_payload.get("shares")),
        "sponsorLogoUrl": _safe_url(sponsor),
        "createdAt": _clean(str(source_payload.get("created_at") or "")).strip(),
        "sourceUpdatedAt": source_updated_at,
        "updatedAtEpochMillis": _epoch_millis(source_updated_at),
        "active": bool(active and published),
        "sourcePayload": source_payload,
        "filterAssociations": normalized_associations,
        "sitemapLastModified": str(sitemap_last_modified or ""),
    }
    ensure_full_record(record)
    return record


def firestore_detail_payload(record: Mapping[str, Any]) -> dict[str, Any]:
    """Return complete normalized details, with raw API data stored separately."""
    payload = dict(record)
    payload.pop("id", None)
    payload.pop("sourcePayload", None)
    return payload


_LEAN_OMIT_FIELDS = {
    "ingredientSections",
    "methodSections",
    "tips",
    "nutritionTips",
    "nutritionPer",
    "nutritionSections",
    "equipment",
    "filterAssociations",
    "sourcePayload",
}


def firestore_recipe_payload(record: Mapping[str, Any]) -> dict[str, Any]:
    """Return the lean summary streamed by Explore and weekly planning."""
    payload = dict(record)
    payload.pop("id", None)
    for field in _LEAN_OMIT_FIELDS:
        payload.pop(field, None)
    return payload


def firestore_source_payload(record: Mapping[str, Any]) -> dict[str, Any]:
    """Return the complete source envelope for ``spoon_recipe_payloads/{id}``."""
    return {
        "language": "el",
        "source": "akispetretzikis.com",
        "sourceRecipeId": record["sourceRecipeId"],
        "detailSchemaVersion": DETAIL_SCHEMA_VERSION,
        "sourceUpdatedAt": record.get("sourceUpdatedAt"),
        "active": bool(record.get("active")),
        "payload": record["sourcePayload"],
    }


def encoded_size(value: Mapping[str, Any]) -> int:
    return len(json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False,
    ).encode("utf-8"))


def collection_hash(records: Sequence[Mapping[str, Any]], projector) -> str:
    """Hash deterministic document IDs and payloads exactly as sent to Firestore."""
    rows = [{"id": str(record["id"]), "data": projector(record)} for record in records]
    rows.sort(key=lambda item: int(item["id"]))
    encoded = json.dumps(
        rows, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def document_sizes(record: Mapping[str, Any]) -> tuple[int, int, int]:
    return (
        encoded_size(firestore_recipe_payload(record)),
        encoded_size(firestore_detail_payload(record)),
        encoded_size(firestore_source_payload(record)),
    )


def ensure_full_record(record: Mapping[str, Any]) -> None:
    missing = FULL_REQUIRED_FIELDS - set(record)
    if missing:
        raise FullSchemaError("full recipe is missing fields: " + ", ".join(sorted(missing)))
    if record.get("detailSchemaVersion") != DETAIL_SCHEMA_VERSION:
        raise FullSchemaError(f"detailSchemaVersion must be {DETAIL_SCHEMA_VERSION}")
    source_id = str(record.get("id") or "")
    if not source_id.isdigit() or int(source_id) != record.get("sourceRecipeId"):
        raise FullSchemaError("sourceRecipeId must be numeric and match id")
    payload = record.get("sourcePayload")
    if not isinstance(payload, Mapping):
        raise FullSchemaError("sourcePayload must be an object")
    if sanitize_source_payload(payload) != payload:
        raise FullSchemaError("sourcePayload is not sanitized")
    if str(payload.get("id") or "") != source_id:
        raise FullSchemaError("sourcePayload.id must match id")
    associations = record.get("filterAssociations")
    if _normalize_associations(associations) != associations:
        raise FullSchemaError("filterAssociations must be canonical sorted facet objects")

    string_fields = {
        "detailSchemaVersion", "slug", "title", "description", "seoTitle",
        "seoDescription", "category", "categoryLabel", "sourceDifficulty",
        "servings", "language", "imageUrl", "source", "sourceUrl", "shortUrl",
        "sourceName", "nutritionPer", "authorName", "sponsorLogoUrl", "createdAt",
        "publishedAt", "sourceUpdatedAt", "ease", "sitemapLastModified",
    }
    if "retiredDetectedAt" in record:
        string_fields.add("retiredDetectedAt")
    for field in string_fields:
        if not isinstance(record.get(field), str):
            raise FullSchemaError(f"{field} must be a non-null string")
    integer_fields = {
        "sourceRecipeId", "categorySourceId", "ratingCount", "rating1", "rating2",
        "rating3", "rating4", "rating5", "prepMinutes", "cookMinutes",
        "waitMinutes", "totalMinutes", "stepCount", "preparationCount", "shares",
        "updatedAtEpochMillis",
    }
    for field in integer_fields:
        if isinstance(record.get(field), bool) or not isinstance(record.get(field), int):
            raise FullSchemaError(f"{field} must be an integer")
    for field in ("rating", "rating10", "randomKey"):
        value = record.get(field)
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(float(value)):
            raise FullSchemaError(f"{field} must be a finite number")

    string_lists = {
        "categoryKeys", "tags", "imageUrls", "dietLabels", "mealTypeLabels",
        "occasionLabels", "methodLabels", "cuisineLabels", "ingredientLabels",
        "videoUrls", "tips", "nutritionTips", "equipment",
    }
    for field in string_lists:
        value = record.get(field)
        if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
            raise FullSchemaError(f"{field} must be a list of strings")
    for field in ("imageUrls", "videoUrls"):
        if any(not _safe_url(item) for item in record[field]):
            raise FullSchemaError(f"{field} may contain only HTTPS URLs")
    if record.get("imageUrl") and not _safe_url(record["imageUrl"]):
        raise FullSchemaError("imageUrl must use HTTPS")
    for field in ("ingredientSections", "methodSections", "nutritionSections"):
        if not isinstance(record.get(field), list):
            raise FullSchemaError(f"{field} must be a list")
    ingredient_fields = {
        "title", "unit", "quantity", "info", "internalLink", "externalLink",
        "ukUnit", "ukQuantity", "usUnit", "usQuantity",
    }
    for section in record["ingredientSections"]:
        if not isinstance(section, Mapping) or not isinstance(section.get("title"), str) or not isinstance(section.get("ingredients"), list):
            raise FullSchemaError("ingredientSections contain an invalid section")
        for ingredient in section["ingredients"]:
            if not isinstance(ingredient, Mapping) or any(not isinstance(ingredient.get(field), str) for field in ingredient_fields):
                raise FullSchemaError("ingredientSections contain an invalid ingredient")
    for section in record["methodSections"]:
        if (not isinstance(section, Mapping) or not isinstance(section.get("title"), str)
                or not isinstance(section.get("steps"), list)
                or any(not isinstance(step, str) for step in section["steps"])):
            raise FullSchemaError("methodSections contain an invalid section")
    for section in record["nutritionSections"]:
        if not isinstance(section, Mapping) or any(not isinstance(value, str) for value in section.values()):
            raise FullSchemaError("nutritionSections contain an invalid value")
    for field in ("quickRecipe", "published", "active"):
        if not isinstance(record.get(field), bool):
            raise FullSchemaError(f"{field} must be boolean")

    recipe_size, detail_size, source_size = document_sizes(record)
    oversized = []
    if recipe_size > MAX_FIRESTORE_DOCUMENT_BYTES:
        oversized.append(f"summary={recipe_size}")
    if detail_size > MAX_FIRESTORE_DOCUMENT_BYTES:
        oversized.append(f"detail={detail_size}")
    if source_size > MAX_FIRESTORE_DOCUMENT_BYTES:
        oversized.append(f"sourcePayload={source_size}")
    if oversized:
        raise FullSchemaError(
            f"recipe {source_id} exceeds {MAX_FIRESTORE_DOCUMENT_BYTES} bytes: "
            + ", ".join(oversized)
        )


def is_full_record(record: Mapping[str, Any]) -> bool:
    return bool(FULL_REQUIRED_FIELDS & set(record))
