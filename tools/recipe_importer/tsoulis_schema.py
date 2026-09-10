"""Extract the complete recipe payload embedded by the official Tsoulis Nuxt app."""
from __future__ import annotations

import json
import re
from collections.abc import Mapping
from typing import Any
from urllib.parse import unquote, urlsplit

from .full_schema import FullSchemaError, plain_text
from .public_recipe_schema import ingredient, normalize_public_recipe_payload, strings


class IncompleteTsoulisRecipe(FullSchemaError):
    """A published full payload explicitly lacks the required ingredient list."""


def decode_nuxt_payload(html_text: str) -> Mapping[str, Any]:
    match = re.search(r'<script\b[^>]*\bid=["\']__NUXT_DATA__["\'][^>]*>(.*?)</script\s*>', html_text, re.S | re.I)
    if not match:
        raise FullSchemaError("Tsoulis recipe has no embedded Nuxt payload")
    table = json.loads(match.group(1))
    if not isinstance(table, list) or not table:
        raise FullSchemaError("invalid Nuxt reference table")
    memo = {}

    def decode(index, path=()):
        if not isinstance(index, int):
            return index
        if index < 0:
            return None
        if index >= len(table) or index in path or len(path) > 40:
            raise FullSchemaError("invalid or cyclic Nuxt reference")
        if index in memo:
            return memo[index]
        value = table[index]
        if isinstance(value, Mapping):
            result = {key: decode(child, (*path, index)) for key, child in value.items()}
        elif isinstance(value, list):
            if value and isinstance(value[0], str) and value[0] in {"Reactive", "ShallowReactive", "Ref", "ShallowRef", "EmptyRef", "EmptyShallowRef"}:
                result = decode(value[1], (*path, index)) if len(value) > 1 else None
            else:
                result = [decode(child, (*path, index)) for child in value]
        else:
            result = value
        memo[index] = result
        return result

    result = decode(0)
    if not isinstance(result, Mapping):
        raise FullSchemaError("Nuxt payload is not an object")
    return result


def extract_tsoulis_recipe(html_text: str, *, source_url: str) -> dict[str, Any]:
    state = decode_nuxt_payload(html_text)
    requested_slug = unquote(urlsplit(source_url).path.rstrip("/").split("/")[-1])
    recipes = []
    for response in state.get("data", {}).values():
        if not isinstance(response, Mapping):
            continue
        data = response.get("data")
        if isinstance(data, Mapping) and data.get("slug") == requested_slug and "instructions" in data and ("ingredients_raw" in data or "ingredients" in data):
            recipes.append(dict(data))
    if len(recipes) != 1:
        raise FullSchemaError("Nuxt data has no unique full recipe matching the requested slug")
    recipe = recipes[0]
    if recipe.get("is_published") != 1:
        raise FullSchemaError("Tsoulis recipe is not published")
    # Related cards are separate recipes and do not belong to the recipe archive.
    return {key: value for key, value in recipe.items() if key not in {"related", "category_related", "newest_related"}}


def normalize_tsoulis_page(html_text: str, *, source_url: str, sitemap_last_modified: str = "", active: bool = True, **_kwargs) -> dict[str, Any]:
    raw = extract_tsoulis_recipe(html_text, source_url=source_url)
    if not any(raw.get(key) for key in ("ingredients_raw", "ingredients", "ingredients_formated", "ingredient")):
        raise IncompleteTsoulisRecipe(f"native_id={raw['id']}; published payload has no ingredient list")
    metadata: dict[str, Any] = {"providerRecipeId": str(raw["id"]), "documentLanguage": "el", "publisherRecipe": raw}
    categories = raw.get("categories") or [raw.get("category", {})]
    category_labels = [str(category.get("title", "")) for category in categories if isinstance(category, Mapping)]
    primary = raw.get("category") or {}
    if primary.get("title"):
        category_labels = [primary["title"], *category_labels]
    metadata["categoryLabels"] = category_labels
    metadata["dietLabels"] = [str(flag.get("title") or flag.get("name") or "") for flag in raw.get("flags", []) if isinstance(flag, Mapping)]
    metadata["tags"] = strings(raw.get("tags"))
    metadata["equipment"] = [str(item.get("readable") or item.get("singular") or "") for item in raw.get("utensils", [])]
    metadata["difficulty"] = (raw.get("difficulty") or {}).get("name", "")
    metadata["tips"] = strings(raw.get("tip"), split_lines=True)
    times = raw.get("time") or {}
    metadata["waitMinutes"] = times.get("waiting") or 0
    metadata["videoUrls"] = re.findall(r'<iframe\b[^>]*\bsrc=["\'](https://[^"\']+)', raw.get("video") or "", re.I)
    metadata["imageUrls"] = [item.get("large") or item.get("original") for item in raw.get("gallery", []) if isinstance(item, Mapping)]
    media = raw.get("media") or {}
    cuisine = raw.get("cuisine") or {}
    portions = raw.get("portions") or {}
    portion_type = portions.get("type") or {}
    rating = raw.get("rating") or {}
    recipe = {
        "@type": "Recipe", "inLanguage": "el", "url": source_url, "name": raw.get("title"),
        "description": raw.get("description"), "recipeCategory": [primary.get("title", "")],
        "recipeIngredient": strings(raw.get("ingredients_raw"), split_lines=True),
        "recipeInstructions": [{"@type": "HowToStep", "text": item.get("description", "")} for item in raw.get("instructions", [])],
        "prepTime": times.get("preparation") or 0, "cookTime": times.get("execution") or 0,
        "totalTime": times.get("total") or 0,
        "recipeYield": " ".join(str(value) for value in [portions.get("amount"), portion_type.get("plural")] if value),
        "image": media.get("large") or media.get("original"),
        "author": "Γιώργος Τσούλης",
        "recipeCuisine": cuisine.get("title", "") if cuisine.get("slug") != "no-cuisine" else "",
        "aggregateRating": {"ratingValue": rating.get("rounded") or 0, "ratingCount": rating.get("count") or 0, "bestRating": 5},
    }
    parts = raw.get("instruction_parts") or []
    if parts:
        method_sections = []
        used = set()
        for part in parts:
            if not isinstance(part, Mapping):
                continue
            part_id = part.get("id")
            instructions = part.get("items") or part.get("instructions") or [step for step in raw.get("instructions", []) if step.get("recipe_part_id") == part_id]
            steps = [plain_text(item.get("description")) for item in instructions if isinstance(item, Mapping) and plain_text(item.get("description"))]
            if steps:
                method_sections.append({"title": plain_text(part.get("title") or part.get("name")), "steps": steps})
                used.update(item.get("id") for item in instructions)
        loose = [plain_text(item.get("description")) for item in raw.get("instructions", []) if item.get("id") not in used and plain_text(item.get("description"))]
        if loose:
            method_sections.insert(0, {"title": "", "steps": loose})
        if method_sections:
            metadata["methodSections"] = method_sections
    # Only explicit group headings become section names; bold ingredient words
    # remain inline and never split an ingredient into several shopping entries.
    raw_ingredients = raw.get("ingredients_raw") or ""
    markers = {}

    def mark_heading(match):
        title = plain_text(match.group(0))
        part_titles = [str(part.get("part") or "") for part in raw.get("ingredients_formated", [])]
        if not (re.match(r"^(?:Για|For)\s", title, re.I) or title in part_titles or re.match(r"<h[2-5]\b", match.group(0), re.I)):
            return match.group(0)
        token = f"__PELTES_INGREDIENT_SECTION_{len(markers)}__"
        markers[token] = title
        return f"<div>{token}</div>"

    marked = re.sub(r"<(?:strong|b|h[2-5])\b[^>]*>.*?</(?:strong|b|h[2-5])\s*>", mark_heading, raw_ingredients, flags=re.S | re.I)
    sections = []
    current = {"title": "", "ingredients": []}
    for text in strings(marked, split_lines=True):
        if text in markers:
            if current["ingredients"]:
                sections.append(current)
            current = {"title": markers[text], "ingredients": []}
        else:
            current["ingredients"].append(ingredient(text))
    if current["ingredients"]:
        sections.append(current)
    if sections:
        metadata["ingredientSections"] = sections
    nutrition_map = {"ΘΕΡΜΙΔΕΣ": "calories", "ΠΡΩΤΕΪΝΕΣ": "proteinContent", "ΥΔΑΤΑΝΘΡΑΚΕΣ": "carbohydrateContent", "ΣΑΚΧΑΡΑ": "sugarContent", "ΛΙΠΟΣ": "fatContent", "ΚΟΡΕΣΜΕΝΑ": "saturatedFatContent", "ΦΥΤΙΚΕΣ ΙΝΕΣ": "fiberContent"}
    recipe["nutrition"] = {field: f"{item.get('value', '')} {(item.get('element') or {}).get('unit', '')}".strip() for item in raw.get("nutritionals", []) if (field := nutrition_map.get((item.get("element") or {}).get("name")))}
    metadata["meta"] = {"og:title": raw.get("meta_title"), "description": raw.get("meta_description")}
    return normalize_public_recipe_payload(recipe, metadata, source_key="tsoulis", source_url=source_url, sitemap_last_modified=sitemap_last_modified, active=active, provider_recipe_id=str(raw["id"]))
