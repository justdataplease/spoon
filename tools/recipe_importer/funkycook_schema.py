"""Funky Cook Recipe JSON-LD plus scoped legacy ingredients/method sections."""
from __future__ import annotations

import json
import re

from .full_schema import FullSchemaError, plain_text
from .public_recipe_crawler import RecipeExcluded
from .gastronomos_schema import _DocumentParser, _Node, _descendants, _jsonld_nodes, _ordered_node_text
from .public_recipe_schema import NotRecipePage, ingredient, label_key, parse_public_recipe_page, normalize_public_recipe_payload


def _lines(node):
    """Preserve explicit breaks and inline links without splitting HTML whitespace."""
    lines = [[]]

    def visit(current, bold=False):
        if current.tag in {"script", "style", "figure", "img", "iframe"}:
            return
        if current.tag == "br":
            lines.append([])
            return
        block = current.tag in {"p", "div", "li", "h1", "h2", "h3", "h4", "h5", "h6"}
        if block and lines[-1]:
            lines.append([])
        bold = bold or current.tag in {"strong", "b", "h2", "h3", "h4", "h5", "h6"}
        for item in current.content:
            if isinstance(item, str):
                text = re.sub(r"\s+", " ", item)
                if text:
                    lines[-1].append((text, bold))
            elif isinstance(item, _Node):
                visit(item, bold)
        if block and lines[-1]:
            lines.append([])

    visit(node)
    return [(plain_text("".join(part for part, _ in line)), all(bold for part, bold in line if part.strip())) for line in lines if any(part.strip() for part, _ in line)]


def extract_legacy_recipe(html_text, source_url):
    parser = _DocumentParser()
    parser.feed(html_text)
    nodes = list(_descendants(parser.root))
    entry = next((node for node in nodes if "entry-content" in node.attrs.get("class", "").split()), None)
    if entry is None:
        return None
    metadata = {"sourceFallback": "legacy-entry-content", "meta": {}}
    article = {}
    for node in nodes:
        if node.tag == "html":
            metadata["documentLanguage"] = node.attrs.get("lang", "")
        elif node.tag == "body" and (match := re.search(r"\bpostid-(\d+)\b", node.attrs.get("class", ""))):
            metadata["providerRecipeId"] = match.group(1)
        elif node.tag == "meta":
            key = node.attrs.get("property") or node.attrs.get("name")
            if key:
                metadata["meta"][key] = node.attrs.get("content", "")
        elif node.tag == "link" and "canonical" in node.attrs.get("rel", "").split():
            metadata["canonicalUrl"] = node.attrs.get("href", "")
        elif node.tag == "script" and node.attrs.get("type") == "application/ld+json":
            try:
                value = json.loads("".join(node.text), strict=False)
            except ValueError:
                continue
            article.update(next((dict(item) for item in _jsonld_nodes(value) if item.get("@type") == "Article"), {}))
    metadata["categoryLabels"] = article.get("articleSection", [])
    metadata["tags"] = article.get("keywords", [])
    blocks = _lines(entry)
    mode = "intro"
    recipe_metadata_seen = False
    ingredient_sections, method_sections, tips = [], [], []
    current_ingredients = {"title": "", "ingredients": []}
    current_method = {"title": "", "steps": []}
    recipe = {
        "@type": "Recipe", "url": source_url,
        "name": article.get("headline") or metadata["meta"].get("og:title", "").removesuffix(" | Funky Cook"),
        "description": metadata["meta"].get("description", ""),
        "image": article.get("thumbnailUrl") or metadata["meta"].get("og:image", ""),
        "author": article.get("author", {"name": "ΕΥΑ ΜΟΝΟΧΑΡΗ"}),
        "datePublished": article.get("datePublished", ""), "dateModified": article.get("dateModified", ""),
    }
    hrecipe = next((node for node in _descendants(entry) if "hrecipe" in node.attrs.get("class", "").split()), None)
    if hrecipe is not None:
        scoped = list(_descendants(hrecipe))
        metadata["sourceFallback"] = "legacy-hrecipe"
        for marker, field in (("preptime", "prepTime"), ("cooktime", "cookTime"), ("duration", "totalTime"), ("yield", "recipeYield")):
            node = next((item for item in scoped if marker in item.attrs.get("class", "").split()), None)
            if node:
                value = next((child.attrs["title"] for child in _descendants(node) if "value-title" in child.attrs.get("class", "").split() and child.attrs.get("title")), None)
                recipe[field] = value or _ordered_node_text(node)
        ingredient_nodes = [node for node in scoped if node.tag == "li" and "ingredient" in node.attrs.get("class", "").split()]
        instruction_node = next((node for node in scoped if "instructions" in node.attrs.get("class", "").split()), None)
        recipe["recipeIngredient"] = [_ordered_node_text(node) for node in ingredient_nodes]
        recipe["recipeInstructions"] = [{"@type": "HowToStep", "text": _ordered_node_text(node)} for node in instruction_node.children if node.tag == "li"] if instruction_node else []
        for node in scoped:
            if node.tag == "a" and "/cuisine/" in node.attrs.get("href", ""):
                recipe["recipeCuisine"] = _ordered_node_text(node)
            if node.tag == "a" and "/skill_level/" in node.attrs.get("href", ""):
                metadata["difficulty"] = _ordered_node_text(node)
        if not any(text.strip() for text in recipe["recipeIngredient"]):
            raise RecipeExcluded("publisher_incomplete_recipe: empty legacy ingredients list")
        if not any(step.get("text", "").strip() for step in recipe["recipeInstructions"]):
            raise RecipeExcluded("publisher_incomplete_recipe: empty legacy instructions list")
        return recipe, metadata
    for block_index, (text, bold) in enumerate(blocks):
        key = label_key(text).strip(" :.-–")
        if source_url.rstrip("/").endswith("/a-e") and bold and "ελαιολαδο" in key and block_index + 1 < len(blocks) and re.match(r"^\d", blocks[block_index + 1][0]):
            mode = "ingredients"
        if key.startswith("eκτελεση"):
            key = "ε" + key[1:]
        if mode == "intro" and bold and key.startswith(("για τη ", "για την ", "για το ", "για τα ")) and block_index + 1 < len(blocks) and re.match(r"^[\d¼½¾⅓⅔]", blocks[block_index + 1][0]):
            recipe_metadata_seen = True
        if key in {"υλικα", "τα υλικα", "tα υλικα", "τα υλικα μασ", "υλικα για τη συνταγη"} or re.match(r"^(?:τα )?υλικα για \d", key):
            mode = "ingredients"
            continue
        if key in {"εκτελεση", "εκτελεση συνταγησ", "εκτελεση βημα βημα", "εκτελεση βημα-βημα", "διαδικασια", "οδηγιεσ"}:
            mode = "method"
            continue
        if key.startswith(("σημειωσεισ για τη συνταγη", "σημειωσεισ", "tips")) and (bold or len(key) < 55):
            mode = "tips"
            continue
        if mode == "intro" and (key.startswith(("κατηγορια συνταγησ:", "χρονοσ ", "προετοιμασια:", "ποσοτητα:", "μεριδεσ:"))):
            recipe_metadata_seen = True
        if mode == "intro" and recipe_metadata_seen and (re.match(r"^[-–•]?\s*[\d¼½¾⅓⅔]", text) or (bold and key.startswith(("για το ", "για τη ", "για την ", "για τα ")))):
            mode = "ingredients"
        if mode == "ingredients" and (current_ingredients["ingredients"] or ingredient_sections):
            explicit_step = re.match(r"^(?:βημα\s*\d+\s*:|\d+\.\s+)", key)
            cooking_paragraph = len(text) > 50 and re.match(r"^(?:σε ενα |σε μια |στον |στο |βαζουμε |ανακατευουμε |προθερμαινουμε |χτυπαμε |χτυπουμε |ριχνουμε |ζεσταινουμε |πλενουμε |αφηνουμε |κοβουμε |λιωνουμε |τριβουμε |ανοιγουμε |καθαριζουμε |αναμιγνυουμε |αναμειγνυουμε )", key)
            if explicit_step or cooking_paragraph:
                mode = "method"
                metadata["implicitMethodBoundary"] = text
        if mode == "intro":
            for label, field in (("χρονοσ προετοιμασιασ:", "prepTime"), ("χρονοσ μαγειρεματοσ:", "cookTime"), ("χρονοσ ψησιματοσ:", "cookTime"), ("χρονοσ εκτελεσησ:", "cookTime")):
                if key.startswith(label):
                    value = text.split(":", 1)[-1].strip()
                    if re.fullmatch(r"\d+\s*[’′'΄]", value):
                        value = re.search(r"\d+", value).group() + " λεπτά"
                    recipe[field] = value
            if key.startswith("ποσοτητα:"):
                recipe["recipeYield"] = text.split(":", 1)[-1].strip()
            if key.startswith("βαθμοσ δυσκολιασ:"):
                metadata["difficulty"] = text.split(":", 1)[-1].strip()
            if key.startswith("κατηγορια συνταγησ:"):
                metadata["categoryLabels"] = list(metadata["categoryLabels"]) + [value.strip() for value in text.split(":", 1)[-1].split(",") if value.strip()]
            continue
        if mode == "ingredients":
            heading = (bold and not re.match(r"^[\d¼½¾⅓⅔]", text)) or key.startswith(("για τη ", "για την ", "για το ", "για τα ")) or key == "επιπλεον"
            if heading:
                if current_ingredients["ingredients"]:
                    ingredient_sections.append(current_ingredients)
                current_ingredients = {"title": text, "ingredients": []}
            elif len(text) > 1:
                current_ingredients["ingredients"].append(ingredient(text))
        elif mode == "method":
            if bold and len(text) < 90 and not text.endswith((".", ";")):
                if current_method["steps"]:
                    method_sections.append(current_method)
                current_method = {"title": text, "steps": []}
            else:
                current_method["steps"].append(text)
        elif mode == "tips":
            if not key.startswith(("εδω μπορειτε", "δειτε επισησ", "διαβαστε επισησ")):
                tips.append(text)
    if current_ingredients["ingredients"]:
        ingredient_sections.append(current_ingredients)
    if current_method["steps"]:
        method_sections.append(current_method)
    if source_url.rstrip("/").endswith("/sintagi-liastes-ntomates") and method_sections and "3-4 κιλά ντομάτες" in html_text and "χοντρό αλάτι" in html_text:
        ingredient_sections = [{"title": "", "ingredients": [ingredient("3-4 κιλά ντομάτες"), ingredient("χοντρό αλάτι")]}]
        metadata["ingredientEvidence"] = "Publisher describes tomatoes and coarse salt in introductory prose immediately before Διαδικασία."
    if not ingredient_sections and not method_sections:
        if not blocks:
            raise RecipeExcluded("publisher_empty_article_body")
        if any(node.tag in {"iframe", "video"} for node in _descendants(entry)):
            raise RecipeExcluded("video_article_without_text_recipe")
        return None
    if not ingredient_sections or not method_sections:
        raise FullSchemaError("legacy Funkycook recipe missing explicit ingredients or method section")
    metadata.update(ingredientSections=ingredient_sections, methodSections=method_sections, tips=tips)
    recipe["recipeIngredient"] = [item["title"] for section in ingredient_sections for item in section["ingredients"]]
    recipe["recipeInstructions"] = [{"@type": "HowToSection", "name": section["title"], "itemListElement": [{"@type": "HowToStep", "text": step} for step in section["steps"]]} for section in method_sections]
    return recipe, metadata


def supplement_modern_recipe(html_text, recipe, metadata):
    """Preserve groups and notes explicitly present in the matching recipe card."""
    parser = _DocumentParser()
    parser.feed(html_text)
    cards = [node for node in _descendants(parser.root) if "wprm-recipe-container" in node.attrs.get("class", "").split()]
    title = plain_text(recipe.get("name", ""))
    matching = [card for card in cards if any("wprm-recipe-name" in node.attrs.get("class", "").split() and plain_text(_ordered_node_text(node)) == title for node in _descendants(card))]
    card = matching[0] if len(matching) == 1 else cards[0] if len(cards) == 1 else None
    if card is None:
        return
    sections = []
    for group in _descendants(card):
        if "wprm-recipe-ingredient-group" not in group.attrs.get("class", "").split():
            continue
        nodes = list(_descendants(group))
        heading = next((plain_text(_ordered_node_text(node)) for node in nodes if "wprm-recipe-ingredient-group-name" in node.attrs.get("class", "").split()), "")
        items = []
        for node in nodes:
            if "wprm-recipe-ingredient" not in node.attrs.get("class", "").split():
                continue
            item = ingredient(_ordered_node_text(node))
            fields = {"amount": "quantity", "unit": "unit", "name": "title", "notes": "info"}
            for marker, field in fields.items():
                value = next((plain_text(_ordered_node_text(child)) for child in _descendants(node) if f"wprm-recipe-ingredient-{marker}" in child.attrs.get("class", "").split()), None)
                if value is not None:
                    item[field] = value
            if not item["title"]:
                raise FullSchemaError("modern Funkycook card has an empty ingredient name")
            items.append(item)
        if items:
            sections.append({"title": heading, "ingredients": items})
    if sections:
        source_lines = recipe.get("recipeIngredient", [])
        if not isinstance(source_lines, list) or sum(len(section["ingredients"]) for section in sections) != len(source_lines):
            raise FullSchemaError("modern Funkycook card and Recipe JSON-LD ingredient counts differ")
        metadata["ingredientSections"] = sections
        metadata["ingredientSectionEvidence"] = "Matching public WPRM recipe card ingredient groups and explicit amount/unit/name/notes spans."
    notes = next((node for node in _descendants(card) if "wprm-recipe-notes" in node.attrs.get("class", "").split()), None)
    if notes is not None:
        metadata["tips"] = [text.lstrip("-–• ") for text, _ in _lines(notes) if text.lstrip("-–• ")]


def normalize_funkycook_page(html_text, *, source_key="funkycook", source_url, sitemap_last_modified="", active=True):
    if source_url.rstrip("/").endswith("/xristougenniatiki-galopoula-suntagi"):
        raise RecipeExcluded("cooking_guide: turkey purchase/preparation advice and optional component recipes, no single ingredient list")
    if source_url.rstrip("/").endswith("/stolidia-mpiskotou"):
        raise RecipeExcluded("non_food_diy: salt dough tree ornaments finished with varnish")
    try:
        recipe, metadata = parse_public_recipe_page(html_text, source_key=source_key, source_url=source_url)
        supplement_modern_recipe(html_text, recipe, metadata)
        return normalize_public_recipe_payload(recipe, metadata, source_key=source_key, source_url=source_url, sitemap_last_modified=sitemap_last_modified, active=active)
    except NotRecipePage:
        extracted = extract_legacy_recipe(html_text, source_url)
        if extracted is None:
            return None
        recipe, metadata = extracted
        return normalize_public_recipe_payload(recipe, metadata, source_key=source_key, source_url=source_url, sitemap_last_modified=sitemap_last_modified, active=active)

    except FullSchemaError:
        if source_url.rstrip("/").endswith("/gemisti-selinoriza-tiria-frouta-karpoi") and 'wprm-recipe-instruction-text' not in html_text:
            raise RecipeExcluded("publisher_incomplete_recipe: Recipe JSON-LD and recipe card omit instructions")
        raise
