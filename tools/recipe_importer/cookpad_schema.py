"""Greek Cookpad recipes with scoped public DOM fallback for photo-less pages."""
from __future__ import annotations

import re
import math

from .full_schema import FullSchemaError, plain_text
from .ingredient_taxonomy import is_reviewed_ingredient
from .gastronomos_schema import _DocumentParser, _descendants, _ordered_node_text
from .public_recipe_schema import NotRecipePage, dedupe, parse_public_recipe_page, normalize_public_recipe_payload, label_key, ingredient


_TIME_NUMBER = r"\d+(?:,\d+)?"
_TIME_TOKEN = re.compile(rf"(?P<low>{_TIME_NUMBER})(?:\s*[-–]\s*(?P<high>{_TIME_NUMBER}))?\s*(?P<unit>λεπτ(?:α|ο|ων)|ωρ(?:α|εσ)|ημερ(?:α|εσ)|minutes?|mins?|hours?|hrs?|days?|[hλ]|[’′'΄])", re.I)


def parse_cookpad_minutes(value):
    """Accept complete duration expressions; retain ambiguous prose only as evidence."""
    text = label_key(value).strip()
    text = re.sub(r"^(?:περιπου|συνολικα)\s+|\s+(?:περιπου|συνολικα|ψησιμο|βρασιμο|μαγειρεμα)[.!]?$", "", text)
    tokens = list(_TIME_TOKEN.finditer(text))
    remainder = _TIME_TOKEN.sub("", text)
    if not tokens or re.sub(r"\s|και|[+&]", "", remainder):
        return 0
    total = 0.0
    for token in tokens:
        amount = max(float(token.group("low").replace(",", ".")), float((token.group("high") or token.group("low")).replace(",", ".")))
        unit = token.group("unit")
        multiplier = 1440 if unit.startswith(("ημερ", "day")) else 60 if unit.startswith(("ωρ", "hour", "hr")) or unit == "h" else 1
        total += amount * multiplier
    return math.ceil(total)


def ingredient_row_content(node):
    """Separate the ingredient sentence from explicit attached recipe cards."""
    parts, references = [], []
    for child in node.content:
        if isinstance(child, str):
            parts.append(child)
            continue
        descendants = list(_descendants(child))
        attachment = any("mise-icon-attachment" in item.attrs.get("class", "").split() for item in descendants)
        linked = [item for item in descendants if item.tag == "a" and re.match(r"^/gr/(?:sintages|simvoules)/\d+", item.attrs.get("href", ""))]
        if attachment and linked:
            for item in linked:
                reference_path = item.attrs["href"].split("?", 1)[0].split("#", 1)[0]
                if match := re.match(r"^/gr/sintages/(\d+)", reference_path):
                    reference_path = f"/gr/sintages/{match.group(1)}"
                references.append({"url": "https://cookpad.com" + reference_path, "title": plain_text(_ordered_node_text(item))})
        else:
            parts.append(_ordered_node_text(child))
    return re.sub(r"\s+", " ", plain_text("".join(parts))).strip(), references


def ingredient_groups(section, published_lines=None):
    sections, lines = [], []
    published_ingredients = {re.sub(r"\s+", " ", plain_text(value)).strip() for value in (published_lines or [])}
    current = {"title": "", "ingredients": []}
    had_heading = False
    for node in _descendants(section):
        if not re.fullmatch(r"ingredient_\d+", node.attrs.get("id", "")):
            continue
        text, references = ingredient_row_content(node)
        classes = node.attrs.get("class", "").split()
        # Cookpad authors sometimes mark real ingredients as headings; JSON-LD
        # then silently omits those ingredients. Prefer source content over style.
        parsed = ingredient(text)
        key = label_key(text)
        ordinal_heading = bool(re.match(r"^\d+\s*(?:η|ο|οσ)\b", key))
        component_heading = key.rstrip(": ") in {"σιροπι", "κρεμα", "μπεσαμελ", "γλασο", "γεμιση", "βαση", "σαντιγι", "σαλτσα", "σερβιρισμα", "επιπλεον"}
        reviewed_bare = is_reviewed_ingredient(parsed["title"]) and not component_heading and not text.endswith(":")
        explicit_ingredient = text in published_ingredients or (bool(parsed["quantity"]) and not ordinal_heading) or reviewed_bare
        if "font-semibold" in classes and "border-0" in classes and not explicit_ingredient:
            had_heading = True
            if current["ingredients"] or current["title"]:
                sections.append(current)
            current = {"title": text, "ingredients": []}
        else:
            lines.append(text)
            item = ingredient(text)
            if references:
                item["externalLink"] = references[0]["url"]
            current["ingredients"].append(item)
    if current["ingredients"] or current["title"]:
        sections.append(current)
    return sections, lines, had_heading


def normalize_cookpad_page(html_text, *, source_key="cookpad", source_url, sitemap_last_modified="", active=True):
    parser = _DocumentParser()
    parser.feed(html_text)
    nodes = list(_descendants(parser.root))
    ingredients = next((node for node in nodes if node.attrs.get("id") == "ingredients"), None)
    steps = next((node for node in nodes if node.attrs.get("id") == "steps"), None)
    try:
        recipe, metadata = parse_public_recipe_page(html_text, source_key=source_key, source_url=source_url)
    except NotRecipePage:
        if ingredients is None or steps is None:
            raise FullSchemaError("Cookpad page has no public recipe ingredients/method")
        meta = {node.attrs.get("property") or node.attrs.get("name"): node.attrs.get("content", "") for node in nodes if node.tag == "meta"}
        metadata = {"sourceFallback": "public-recipe-dom", "meta": meta, "documentLanguage": next((node.attrs.get("lang", "") for node in nodes if node.tag == "html"), "")}
        title = next((_ordered_node_text(node) for node in nodes if node.tag == "h1"), "")
        recipe = {
            "@type": "Recipe", "name": title, "url": source_url,
            "description": meta.get("description", ""),
            "author": {"name": meta.get("og:title", "").split("Συνταγή από τον/την ")[-1]},
            "recipeIngredient": ingredient_groups(ingredients)[1],
            "recipeInstructions": [{"@type": "HowToStep", "text": "\n".join(_ordered_node_text(child) for child in _descendants(node) if child.tag == "p")} for node in _descendants(steps) if re.fullmatch(r"step_\d+", node.attrs.get("id", ""))],
        }
        # Photo-less legacy pages advertise Cookpad's generic logo in og:image.
        # Keep image empty instead of presenting that brand image as the dish.
        image = meta.get("og:image", "")
        if "img-global.cpcdn.com/recipes/" in image:
            recipe["image"] = image
        serving = next((node for node in _descendants(ingredients) if node.attrs.get("id", "").startswith("serving_recipe_")), None)
        if serving:
            recipe["recipeYield"] = _ordered_node_text(serving)
    native_id = re.search(r"/gr/sintages/(\d+)", source_url).group(1)
    time_node = next((node for node in nodes if node.attrs.get("id") == f"cooking_time_recipe_{native_id}"), None)
    if time_node is not None:
        time_text = plain_text(_ordered_node_text(time_node))
        minutes = parse_cookpad_minutes(time_text)
        metadata["cookingTimeEvidence"] = {"elementId": time_node.attrs["id"], "sourceField": "cooking_time", "text": time_text, "parsedMinutes": minutes}
        if time_text and not minutes and not recipe.get("totalTime"):
            metadata["totalDurationUncertain"] = True
        if minutes and not recipe.get("cookTime"):
            recipe["cookTime"] = f"PT{minutes}M"
    keywords = next((node for node in nodes if node.attrs.get("data-recipe-section-show-logger-section-name-value") == "related_keywords"), None)
    if keywords is not None:
        scoped_keywords = dedupe([plain_text(_ordered_node_text(node)) for node in _descendants(keywords) if node.tag == "a" and node.attrs.get("href", "").startswith("/gr/anazitisi/")])
        metadata["scopedKeywords"] = scoped_keywords
        metadata["categoryLabels"] = scoped_keywords
        metadata["tags"] = scoped_keywords
    if ingredients is not None:
        sections, lines, had_heading = ingredient_groups(ingredients, recipe.get("recipeIngredient", []))
        if sections:
            published = [re.sub(r"\s+", " ", plain_text(value)).strip() for value in recipe.get("recipeIngredient", [])]
            all_rows = {ingredient_row_content(node)[0] for node in _descendants(ingredients) if re.fullmatch(r"ingredient_\d+", node.attrs.get("id", ""))}
            if len(published) > len(lines):
                raise FullSchemaError("Cookpad public ingredient row count is lower than published JSON-LD")
            metadata["ingredientSections"] = sections
            metadata["ingredientSectionEvidence"] = "Current recipe #ingredients rows, explicit group headings, amounts and reviewed ingredient identities; publisher bold style alone cannot remove an ingredient."
            metadata["ingredientReconciliation"] = {"jsonLdIngredientCount": len(published), "domIngredientCount": len(lines), "additionalDomIngredientLines": [line for line in lines if line not in published], "jsonLdTextDifferences": [value for value in published if value not in all_rows]}
            metadata["ingredientReferences"] = [{"ingredientElementId": node.attrs["id"], **reference} for node in _descendants(ingredients) if re.fullmatch(r"ingredient_\d+", node.attrs.get("id", "")) for reference in ingredient_row_content(node)[1]]
        # Cookpad links individual search words within a complete ingredient.
        # Those tokens are evidence, not safe ingredient identities: e.g. a milk
        # link can be part of almond milk, and a yolk word can also mean saffron.
        metadata["ingredientSearchKeywords"] = dedupe([plain_text(_ordered_node_text(node)) for node in _descendants(ingredients) if node.tag == "a" and node.attrs.get("href", "").startswith("/gr/anazitisi/")])
    return normalize_public_recipe_payload(recipe, metadata, source_key=source_key, source_url=source_url, sitemap_last_modified=sitemap_last_modified, active=active)
