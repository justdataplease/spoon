"""Source-neutral normalization of authorized Greek public recipe pages.

Only publisher-scoped Recipe data and metadata are used. Navigation, related
recipes and title guessing never contribute ingredients or planner categories.
"""

from __future__ import annotations

import hashlib
import json
import re
import unicodedata
from collections.abc import Mapping
from typing import Any
from urllib.parse import unquote, urljoin, urlsplit

try:
    from .full_schema import (
        CATEGORY_LABELS, FACET_KEYS, FACET_LABEL_FIELDS, FullSchemaError,
        ensure_full_record, plain_text, sanitize_source_payload,
    )
    from .gastronomos_schema import (
        _DocumentParser, _descendants, _ordered_node_text, _jsonld_nodes,
        _is_recipe, _instruction_sections, _nutrition, _rating, _epoch_millis,
        parse_duration_minutes,
    )
    from .facet_taxonomy import ALIASES as COMMON_FACET_ALIASES
    from .ingredient_taxonomy import ingredient_token, canonical_ingredient_labels, is_reviewed_ingredient
    from .helpers import canonical_category, classify_category_keys, classify_ease
    from .providers import canonical_recipe_url, provider_for, recipe_document_id, stable_recipe_random_key
except ImportError:  # pragma: no cover - direct command execution
    from full_schema import (
        CATEGORY_LABELS, FACET_KEYS, FACET_LABEL_FIELDS, FullSchemaError,
        ensure_full_record, plain_text, sanitize_source_payload,
    )
    from gastronomos_schema import (
        _DocumentParser, _descendants, _ordered_node_text, _jsonld_nodes,
        _is_recipe, _instruction_sections, _nutrition, _rating, _epoch_millis,
        parse_duration_minutes,
    )
    from facet_taxonomy import ALIASES as COMMON_FACET_ALIASES
    from ingredient_taxonomy import ingredient_token, canonical_ingredient_labels, is_reviewed_ingredient
    from helpers import canonical_category, classify_category_keys, classify_ease
    from providers import canonical_recipe_url, provider_for, recipe_document_id, stable_recipe_random_key


PUBLIC_SOURCE_KEYS = frozenset({"tsoulis", "lucacos", "funkycook", "cookpad"})
INGREDIENT_FIELDS = (
    "title", "unit", "quantity", "info", "internalLink", "externalLink",
    "ukUnit", "ukQuantity", "usUnit", "usQuantity",
)


class NonGreekRecipe(FullSchemaError):
    """A published page cannot enter the Greek-only catalog."""


class NotRecipePage(FullSchemaError):
    """Discovery found a public article or listing without a recipe."""


def strings(value: object, *, split_lines: bool = False) -> list[str]:
    if isinstance(value, Mapping):
        return strings(value.get("name") or value.get("text") or value.get("title"), split_lines=split_lines)
    if isinstance(value, list):
        return [text for item in value for text in strings(item, split_lines=split_lines)]
    if isinstance(value, (str, int, float)) and not isinstance(value, bool):
        text = plain_text(value)
        return [part.strip() for part in (text.splitlines() if split_lines else [text]) if part.strip()]
    return []


def label_key(value: object) -> str:
    return " ".join("".join(c for c in unicodedata.normalize("NFD", plain_text(value).casefold()) if unicodedata.category(c) != "Mn").split())


def dedupe(values: list[str]) -> list[str]:
    seen: set[str] = set()
    return [value for value in values if (key := label_key(value)) and not (key in seen or seen.add(key))]


def safe_urls(value: object, *, base_url: str) -> list[str]:
    if isinstance(value, list):
        return list(dict.fromkeys(url for item in value for url in safe_urls(item, base_url=base_url)))
    if isinstance(value, Mapping):
        return safe_urls(value.get("contentUrl") or value.get("url") or value.get("embedUrl") or value.get("@id"), base_url=base_url)
    if not isinstance(value, str) or not value.strip() or value.strip().startswith("#"):
        return []
    result = urljoin(base_url, value.strip())
    parts = urlsplit(result)
    if parts.scheme == "http":
        parts = parts._replace(scheme="https")
    if parts.scheme != "https" or not parts.hostname or parts.username or parts.password:
        return []
    if parts.port not in (None, 443):
        return []
    return [parts._replace(fragment="").geturl()]



_QUANTITY_RE = re.compile(r"^\s*(?P<quantity>\d+\s*[¼½¾⅓⅔⅛⅜⅝⅞]|(?:\d+\s+)?\d+\s*[/\\]\s*\d+|\d+(?:[.,]\d+)?(?:\s*[-–]\s*\d+(?:[.,]\d+)?)?|[¼½¾⅓⅔⅛⅜⅝⅞])\s*(?P<rest>.*)$")
# Compound measures must match before their abbreviated or one-word prefixes.
_UNIT_RE = re.compile(
    r"^(?P<unit>"
    r"κουτ\.?\s*(?:της\s+)?(?:σούπας|σουπας|γλυκού|γλυκου|τσαγιού|τσαγιου)\b|"
    r"κουταλι(?:ά|ές|α|ες)\s+(?:της\s+)?(?:σούπας|σουπας|γλυκού|γλυκου)\b|"
    r"κουταλάκι(?:α)?\s+(?:του\s+)?(?:γλυκού|γλυκου)\b|"
    r"φλιτζάνι(?:α)?\s+(?:του\s+)?(?:τσαγιού|τσαγιου|καφέ|καφε)\b|"
    r"φλ\.?\s*(?:τσ\.?|τσαγ\.?|καφ\.?)(?=\s|$)|"
    r"κ\.\s*σ\.|κ\.\s*γ\.|κ\.σ|κ\.γ|κσ\b|κγ\b|"
    r"γρ\b\.?|γραμμ?άρια\b|γραμμ?αρια\b|κιλά\b|κιλό\b|κιλα\b|κιλο\b|"
    r"kg\b\.?|gr\b\.?|g\b\.?|ml\b\.?|λίτρα\b|λίτρο\b|λιτρα\b|λιτρο\b|lt\b\.?|l\b\.?|"
    r"φλιτζάνια\b|φλιτζάνι\b|φλιτζανια\b|φλιτζανι\b|φλ\b\.?|"
    r"κούπες\b|κούπα\b|κουπες\b|κουπα\b|πρέζες\b|πρέζα\b|σκελίδες\b|σκελίδα\b|"
    r"φέτες\b|φέτα\b|κουταλιές\b|κουταλιά\b|κουταλάκια\b|κουταλάκι\b|tsp\b\.?|tbsp\b\.?|cups?\b"
    r")\s*(?P<title>.*)$", re.I,
)


def ingredient(text: str) -> dict[str, str]:
    """Split explicit leading measures; preserve the food and all publisher notes."""
    result = {field: "" for field in INGREDIENT_FIELDS}
    result["title"] = plain_text(text)
    if match := _QUANTITY_RE.fullmatch(result["title"]):
        rest = match.group("rest").strip()
        # Packed multipliers such as 2x400g need their original sentence intact.
        if rest and not re.match(r"^[x×]\s*\d", rest, re.I):
            result["quantity"] = match.group("quantity").strip()
            if measured := _UNIT_RE.fullmatch(rest):
                result["unit"] = measured.group("unit").strip()
                rest = measured.group("title").strip()
            if rest:
                result["title"] = rest
            else:
                result["quantity"] = result["unit"] = ""
    title, separator, notes = result["title"].partition(",")
    if separator and title.strip() and notes.strip():
        result["title"], result["info"] = title.strip(), notes.strip()
    return result


# Exact publisher labels only; these extend the established shared taxonomy.
_CATEGORY_ALIASES = {
    "κρεατικα": "meat", "χοιρινο": "meat", "αρνι": "meat", "μοσχαρι": "meat",
    "κρεας": "meat", "κοτοπουλο": "poultry", "πουλερικα": "poultry",
    "ψαρι - θαλασσινα": "fish", "ψαρια & θαλασσινα": "fish", "θαλασσινα": "seafood",
    "οσπρια": "legumes", "οσπρια & λαδερα": "legumes", "λαδερα": "vegetables",
    "ζυμαρικα": "pasta", "ζυμαρικα - ρυζι": "pasta", "ρυζι": "rice",
    "λαχανικα": "vegetables", "σαλατες": "vegetables",
    "γλυκα": "dessert", "γλυκα και επιδορπια": "dessert", "σιροπιαστα γλυκα": "dessert",
    "ροφηματα & ποτα": "drinks", "ροφηματα": "drinks", "ποτα": "drinks",
    "σαντουιτς": "dirty", "σαντουιτς & σνακ": "dirty",
}
_TERMINAL_OTHER = {"βασικες συνταγες", "σαλτσες / ντιπ", "σαλτσες", "ζωμοι", "ψωμια & ζυμες", "αρωματικα βουτυρα"}
_DIET_ALIASES = {
    "vegan": "Vegan", "vegandiet": "Vegan", "vegetarian": "Χορτοφαγική",
    "vegetariandiet": "Χορτοφαγική", "χορτοφαγικη (vegetarian)": "Χορτοφαγική",
    "gluten free": "Χωρίς γλουτένη", "glutenfreediet": "Χωρίς γλουτένη",
    "dairy free": "Χωρίς γαλακτοκομικά", "lactosefreediet": "Χωρίς λακτόζη",
    "sugar free": "Χωρίς ζάχαρη", "νηστισιμο": "Νηστίσιμα", "νηστισιμα": "Νηστίσιμα",
}


# Greek casefold changes final sigma; normalize both sides of exact label maps.
_CATEGORY_ALIASES = {label_key(key): value for key, value in _CATEGORY_ALIASES.items()}
_CATEGORY_ALIASES.update({label_key(key): value for key, value in {
    "ΚΟΤΟΠΟΥΛΟ & ΠΟΥΛΕΡΙΚΑ": "poultry", "ΨΑΡΙ & ΘΑΛΑΣΣΙΝΑ": "fish",
    "ΖΥΜΑΡΙΚΑ & ΡΥΖΙ": "pasta", "ΣΑΝΤΟΥΪΤΣ": "dirty",
}.items()})
_TERMINAL_OTHER = {label_key(value) for value in _TERMINAL_OTHER}
_DIET_ALIASES = {label_key(key): value for key, value in _DIET_ALIASES.items()}



# Reviewed complete publisher keywords. No recipe-title substring inference.
_CATEGORY_ALIASES.update({label_key(key): value for value, labels in {
    "legumes": ["Φασόλια", "fasolia", "beans", "Φακές", "Κόκκινες φακές", "Ρεβίθια", "Φάβα", "Γίγαντες"],
    "pasta": ["Μακαρονάδα", "Μακαρόνια", "Σπαγγέτι", "Πέννες", "Χυλοπίτες", "Λινγκουίνι", "Κριθαράκι", "Μακαρονοσαλάτα", "Παστίτσιο", "Λαζάνια", "Καρμπονάρα", "Τορτελίνια", "Φαρφάλες", "Ραβιόλια"],
    "rice": ["Ριζότο", "Πιλάφι"],
    "poultry": ["Κοτόσουπα", "Φιλετάκια κοτόπουλου μούρλια", "Κοτομπουκιές"],
    "fish": ["Τόνος", "Σολομός", "Μπακαλιάρος", "Σαρδέλα", "Λαβράκι", "Τσιπούρα"],
    "seafood": ["Γαρίδες", "Καλαμάρια", "Χταπόδι", "Μύδια"],
    "vegetables": ["Πατζαροσαλάτα", "Παντζαροσαλάτα", "Πατατοσαλάτα", "Χωριάτικη σαλάτα", "Πουρές πατάτας", "Σπανακόρυζο", "Λαχανόρυζο", "Αρακάς", "Μπριάμ", "Φασολάκια"],
    "dessert": ["Σοκολατίνα", "Σοκολατόπιτα", "Πορτοκαλόπιτα", "Σπιτικό παγωτό", "Παγωτό", "Φανουρόπιτα", "Γαλακτομπούρεκο", "Ρυζόγαλο", "Καρυδόπιτα", "Μηλόπιτα", "Μπισκοτάκι", "Μπισκότα", "Κέικ", "Brownies", "Mille Feuille", "Κουραμπιέδες", "Μελομακάρονα", "Τσουρέκι", "Σαραγλί", "Μπακλαβάς", "ΚΕΪΚ & ΜΠΙΣΚΟΤΑ", "Γλυκά Ψυγείου-Παγωτά", "Διεθνή Γλυκά"],
    "dirty": ["Πίτσα", "Σάντουιτς", "Σάντουιτς & Σνακ"],
}.items() for key in labels})
# Exact labels reviewed from unclassified publisher examples. This batch is
# fallback-only: a named dish must not displace an established mixed-dish category.
_REVIEWED_OTHER_CATEGORY_ALIASES = {
    label_key(label): key for key, labels in {
        "meat": ["Σούπα κρέας", "Οσομπούκο", "Μοσχάρι γιουβέτσι"],
        "legumes": ["Ρεβύθια"],
        "seafood": ["Καλαμάρι"],
        "dessert": [
            "Νηστίσιμο κέικ", "Κέικ λεμονιού", "Κέικ μαρμπρέ", "Κωκ",
            "Εργολάβοι", "Μαχαλεπί", "Επιδόρπιο", "Ελληνικά & Παραδοσιακά Γλυκά",
            "Εύκολα Γλυκά", "Νηστίσιμα Γλυκά", "Χειμωνιάτικα Γλυκά",
            "Χριστουγεννιάτικα Γλυκά", "Σοκολατένια γλυκά",
        ],
        "dirty": ["Corn Dogs"],
    }.items() for label in labels
}

_EXTRA_FACET_LABELS = {
    "occasion": {"Χριστουγεννιάτικες": ["Χριστούγεννα"], "Καλοκαίρι": ["Καλοκαίρι"], "Καλοκαιριού": ["Καλοκαίρι"], "Χειμώνας": ["Χειμώνας"], "Halloween": ["Halloween"], "Για παιδιά": ["Για παιδιά"], "Παιδικά": ["Για παιδιά"], "Παιδικό party": ["Παιδικό πάρτι"], "Για όλο τον χρόνο": ["Για όλο τον χρόνο"]},
    "meal_type": {"Ορεκτικά": ["Ορεκτικά"], "ΟΡΕΚΤΙΚΑ & ΝΤΙΠ": ["Ορεκτικά"], "Σούπες": ["Σούπες"], "Πρωινό-Brunch": ["Πρωινό", "Μπραντς"], "Παιδικά": ["Παιδικά"], "Βρεφικά": ["Βρεφικά"]},
    "method": {"Ψητά φούρνου": ["Φούρνος"], "Κατσαρόλα": ["Κατσαρόλα"], "Τηγανητά": ["Τηγάνι"], "Ψητά σχάρας": ["Σχάρα"]},
    "diet": {"Χωρίς Γλουτένη (Gluten Free)": ["Χωρίς γλουτένη"], "Eggless": ["Χωρίς αυγά"]},
}
_EXTRA_FACET_LABELS = {facet: {label_key(key): value for key, value in aliases.items()} for facet, aliases in _EXTRA_FACET_LABELS.items()}

def derive_public_recipe_taxonomy(source_payload: Mapping[str, Any]) -> dict[str, Any]:
    recipe = source_payload.get("jsonLd", {})
    metadata = source_payload.get("htmlMetadata", {})
    if not isinstance(recipe, Mapping) or not isinstance(metadata, Mapping):
        raise FullSchemaError("public recipe taxonomy evidence must be objects")
    categories = dedupe(strings(recipe.get("recipeCategory")) + strings(metadata.get("categoryLabels")))
    raw_keywords = metadata.get("scopedKeywords", recipe.get("keywords"))
    keywords = [part.strip() for part in raw_keywords.split(",") if part.strip()] if isinstance(raw_keywords, str) else strings(raw_keywords)
    tags = dedupe(categories + keywords + strings(metadata.get("tags")))
    keys = [mapped for value in categories if (mapped := _CATEGORY_ALIASES.get(label_key(value)))]
    if not keys:
        keys = classify_category_keys("", categories)
    if keys == ["other"]:
        keys = [mapped for value in tags if (mapped := _CATEGORY_ALIASES.get(label_key(value)))] or classify_category_keys("", [], tags)
    if any(label_key(value) in _TERMINAL_OTHER for value in categories) and not any(key in {"dessert", "drinks"} for key in keys):
        keys = ["other"]
    # Dessert/drink evidence has precedence over incidental ingredient tags.
    priority = {"dessert": 0, "drinks": 1}
    keys = sorted(dict.fromkeys(key for key in keys if key != "other"), key=lambda key: priority.get(key, 2)) or ["other"]
    if keys == ["other"] and not any(label_key(value) in _TERMINAL_OTHER for value in categories):
        fallback_keys = [mapped for value in tags if (mapped := _REVIEWED_OTHER_CATEGORY_ALIASES.get(label_key(value)))]
        keys = sorted(dict.fromkeys(fallback_keys), key=lambda key: priority.get(key, 2)) or ["other"]
    category = canonical_category(keys)
    facet_labels = {facet: dedupe(strings(metadata.get(FACET_LABEL_FIELDS[facet]))) for facet in FACET_KEYS}
    facet_labels["cuisine"] = dedupe(facet_labels["cuisine"] + strings(recipe.get("recipeCuisine")))
    for value in strings(recipe.get("suitableForDiet")):
        label = _DIET_ALIASES.get(label_key(value.rsplit("/", 1)[-1]))
        if label:
            facet_labels["diet"].append(label)
    for value in tags:
        for facet, aliases in COMMON_FACET_ALIASES.items():
            if mapped := aliases.get(ingredient_token(value)):
                facet_labels["meal_type" if facet == "meal" else facet].append(mapped)
        for facet, aliases in _EXTRA_FACET_LABELS.items():
            if mapped := aliases.get(label_key(value)):
                facet_labels[facet].extend(mapped)
        if mapped := _DIET_ALIASES.get(label_key(value)):
            facet_labels["diet"].append(mapped)
    source_ingredients = strings(recipe.get("recipeIngredient"), split_lines=True)
    scoped_sections = metadata.get("ingredientSections") or []
    scoped_titles = [str(item.get("title", "")) for section in scoped_sections for item in section.get("ingredients", [])]
    reviewed_titles = [parsed for text in source_ingredients if is_reviewed_ingredient(parsed := ingredient(text)["title"])]
    reviewed_titles += [text for text in scoped_titles if is_reviewed_ingredient(text)]
    # A source can expose search fragments ("red", "canned") alongside actual
    # foods. Only reviewed complete identities enter the shared ingredient facet.
    # Raw titles, tags and source metadata remain untouched for detail/audit use.
    canonical_ingredients = canonical_ingredient_labels(facet_labels["ingredient"] + reviewed_titles)
    facet_labels["ingredient"] = [label for label in canonical_ingredients if is_reviewed_ingredient(label)]
    facet_labels = {facet: dedupe(values) for facet, values in facet_labels.items()}
    return {"categoryKeys": keys, "category": category, "categoryLabel": CATEGORY_LABELS.get(category, "Άλλο"), "facetLabels": facet_labels, "tags": tags}


def _lucacos_article_sections(article: Any, *, field: str, source_url: str) -> tuple[list[dict[str, Any]], list[str]]:
    """Preserve scoped bold section headings, ordered content, links and tips."""
    content_key = "ingredients" if field == "ingredientSections" else "steps"
    sections: list[dict[str, Any]] = []
    tips: list[str] = []
    current: dict[str, Any] = {"title": "", content_key: []}
    in_tips = False

    def visit(node: Any) -> None:
        nonlocal current, in_tips
        if node.tag in {"script", "style", "iframe", "noscript"}:
            return
        text = plain_text(_ordered_node_text(node))
        if content_key == "steps" and node.tag == "p":
            # These are publisher-labelled tips, including unbolded "Tip: ..."
            # paragraphs. Keep their prose outside the numbered method.
            inline_tip = re.fullmatch(r"\*?\s*Tips?\s*[:.]\s*(.+)", text, re.I | re.S)
            if inline_tip:
                tips.append(inline_tip.group(1).strip())
                return
            visible = [item for item in node.content if not isinstance(item, str) or item.strip()]
            first = visible[0] if visible else None
            if first is not None and not isinstance(first, str) and first.tag in {"strong", "b"}:
                heading = plain_text(_ordered_node_text(first))
                remainder = plain_text("".join(item if isinstance(item, str) else _ordered_node_text(item) for item in visible[1:]))
                if remainder and re.match(r"^(?:για\s+(?:το|τη|την|τον|τις|τα|τους)\b|for\b)", heading, re.I):
                    if current[content_key]:
                        sections.append(current)
                    current = {"title": heading, content_key: [remainder]}
                    in_tips = False
                    return
        is_heading = node.tag in {"h2", "h3", "h4", "h5"}
        if node.tag == "p" and text:
            # Publisher rich-text editors nest emphasis inside spans and use
            # unbolded "Για ..." ingredient headings on several older pages.
            bold_text = plain_text("".join(_ordered_node_text(child) for child in _descendants(node) if child.tag in {"strong", "b"}))
            is_heading = text == bold_text and (
                content_key == "ingredients"
                or bool(re.match(r"^(?:για\b|for\b|tips?\b|συμβουλ)", text, re.I))
            )
        if content_key == "ingredients" and node.tag in {"p", "li"} and re.match(r"^(?:για\s+(?:το|τη|την|τον|τις|τα|τους)\b|for\b)", text, re.I):
            is_heading = True
        if content_key == "steps" and node.tag in {"p", "li"} and re.fullmatch(r"Tips?\s*:?", text, re.I):
            is_heading = True
        if is_heading and text:
            if label_key(text) in {"video directions", "εκτελεση", "υλικα"}:
                return
            if current[content_key] or (current["title"] and not in_tips):
                sections.append(current)
            in_tips = content_key == "steps" and bool(re.match(r"^(?:tips?\b|συμβουλ)", text, re.I))
            current = {"title": text, content_key: []}
            return
        is_content = node.tag in {"p", "li"}
        if is_content and text and not any(child.tag == "li" for child in _descendants(node)):
            if in_tips:
                tips.append(text)
            elif content_key == "ingredients":
                # Scoped ingredient paragraphs and explicit br-separated rows
                # are as authoritative as list items. Footnotes remain tips,
                # rather than masquerading as an ingredient.
                for line in strings(text, split_lines=True):
                    if line.startswith("*"):
                        tips.append(line)
                        continue
                    item = ingredient(line)
                    links = [url for child in _descendants(node) if child.tag == "a"
                             and plain_text(_ordered_node_text(child)) in line
                             for url in safe_urls(child.attrs.get("href"), base_url=source_url)]
                    if links:
                        item["externalLink"] = links[0]
                    current[content_key].append(item)
            else:
                current[content_key].append(text)
            return
        for child in node.children:
            visit(child)

    for child in article.children:
        visit(child)
    if current[content_key] or (current["title"] and not in_tips):
        sections.append(current)
    return sections, tips


def _recover_lucacos_jsonld(raw: str) -> dict[str, Any] | None:
    """Read the publisher's fixed field template when inner quotes are unescaped.

    This recovery is restricted to Lucacos's pretty-printed Recipe template.
    Ingredient and method HTML is parsed separately and remains authoritative.
    """
    # Requests may retain repeated CR characters; gzip text readers normalize
    # them. Parse both transports consistently; the caller keeps raw verbatim.
    raw = raw.replace("\r\n", "\n").replace("\r", "\n")
    if not re.search(r'(?m)^\s*"@type"\s*:\s*"Recipe"\s*,?\s*$', raw):
        return None
    fields = (
        "@context", "@type", "url", "inLanguage", "author", "description",
        "image", "recipeIngredient", "recipeInstructions", "name", "keywords",
        "cookTime", "prepTime", "totalTime", "recipeCategory", "recipeYield", "video",
    )
    result: dict[str, Any] = {}
    for key in fields:
        match = re.search(r'(?m)^\s*"' + re.escape(key) + r'"\s*:\s*"(.*?)"\s*,?[ \t]*(?:\r?\n|$)', raw, re.S)
        if match:
            result[key] = match.group(1)
    rating = {}
    for key in ("ratingValue", "reviewCount", "ratingCount", "bestRating", "worstRating"):
        match = re.search(r'"' + key + r'"\s*:\s*"([^"]*)"', raw)
        if match:
            rating[key] = match.group(1)
    if rating:
        result["aggregateRating"] = rating
    return result if result.get("name") and result.get("url") else None


def parse_public_recipe_page(html_text: str, *, source_key: str, source_url: str) -> tuple[dict[str, Any], dict[str, Any]]:
    parser = _DocumentParser()
    parser.feed(html_text)
    nodes = list(_descendants(parser.root))
    metadata: dict[str, Any] = {"meta": {}}
    recipes = []
    articles = []
    for node in nodes:
        if node.tag == "html":
            metadata["documentLanguage"] = node.attrs.get("lang", "")
        elif source_key == "funkycook" and node.tag in {"body", "article"}:
            marker = re.search(r"(?:^|\s)postid-(\d+)(?:\s|$)", node.attrs.get("class", "")) or re.fullmatch(r"post-(\d+)", node.attrs.get("id", ""))
            if marker:
                metadata["providerRecipeId"] = marker.group(1)
        elif node.tag == "meta":
            key = node.attrs.get("property") or node.attrs.get("name")
            if key:
                metadata["meta"][key] = node.attrs.get("content", "")
        elif node.tag == "link" and "canonical" in node.attrs.get("rel", "").split():
            metadata["canonicalUrl"] = urljoin(source_url, node.attrs.get("href", ""))
        elif node.tag == "script" and node.attrs.get("type", "").split(";", 1)[0].strip() == "application/ld+json":
            try:
                # Lucacos embeds literal line breaks in JSON-LD strings.
                value = json.loads("".join(node.text), strict=False)
            except (ValueError, TypeError):
                value = _recover_lucacos_jsonld("".join(node.text)) if source_key == "lucacos" else None
                if value is None:
                    continue
                metadata["recoveredJsonLdTemplate"] = "".join(node.text)
            recipes.extend(dict(item) for item in _jsonld_nodes(value) if _is_recipe(item))
            for item in _jsonld_nodes(value):
                types = item.get("@type")
                types = types if isinstance(types, list) else [types]
                if any(str(kind).rsplit("/", 1)[-1] in {"Article", "BlogPosting"} for kind in types):
                    articles.append(dict(item))
    if not recipes:
        raise NotRecipePage(f"{source_key}: page contains no Recipe JSON-LD")
    path = unquote(urlsplit(source_url).path).rstrip("/")
    matched = [recipe for recipe in recipes if any(unquote(urlsplit(url).path).rstrip("/") == path for url in safe_urls(recipe.get("url") or recipe.get("mainEntityOfPage") or recipe.get("@id"), base_url=source_url))]
    if len(matched) == 1:
        recipe = matched[0]
    elif len(recipes) == 1:
        recipe = recipes[0]
    else:
        raise FullSchemaError("multiple Recipe objects without an unambiguous canonical match")
    if source_key == "funkycook":
        for article in articles:
            metadata.setdefault("categoryLabels", []).extend(strings(article.get("articleSection")))
            metadata.setdefault("tags", []).extend(strings(article.get("keywords")))
            for field in ("datePublished", "dateModified"):
                if article.get(field) and not recipe.get(field):
                    recipe[field] = article[field]
    if source_key == "lucacos":
        # Desktop article columns preserve publisher-defined section headings.
        for field, marker in (("ingredientSections", "ingredients"), ("methodSections", "directions")):
            container = next((node for node in nodes if marker in node.attrs.get("class", "").split() and "columns" in node.attrs.get("class", "").split()), None)
            if container is None:
                continue
            article = next((node for node in _descendants(container) if node.tag == "article"), None)
            if article is None:
                continue
            sections, tips = _lucacos_article_sections(article, field=field, source_url=source_url)
            if (field == "ingredientSections" and not sections
                    and not plain_text(_ordered_node_text(article))
                    and not strings(recipe.get("recipeIngredient"), split_lines=True)):
                metadata["publisherIngredientListAbsent"] = True
            if sections:
                metadata[field] = sections
            if tips:
                metadata.setdefault("tips", []).extend(tips)
        recipe_tags = next((node for node in nodes if node.tag == "section" and "recipe-tags" in node.attrs.get("class", "").split()), None)
        if recipe_tags is not None:
            metadata["scopedKeywords"] = dedupe([plain_text(_ordered_node_text(node)) for node in _descendants(recipe_tags) if node.tag == "a" and re.match(r"/(?:main-free-tags|free-tags)/", node.attrs.get("href", ""))])
            metadata["tags"] = list(metadata["scopedKeywords"])
        ingredient_scope = next((node for node in nodes if "ingredients" in node.attrs.get("class", "").split() and "columns" in node.attrs.get("class", "").split()), None)
        metadata["ingredientLabels"] = dedupe([plain_text(_ordered_node_text(node)) for node in _descendants(ingredient_scope) if node.tag == "a" and re.match(r"/ingredient/\d+/", node.attrs.get("href", ""))]) if ingredient_scope is not None else []
    return recipe, metadata


def normalize_public_recipe_payload(recipe: Mapping[str, Any], metadata: Mapping[str, Any], *, source_key: str, source_url: str, sitemap_last_modified: str = "", active: bool = True, provider_recipe_id: str | None = None) -> dict[str, Any]:
    if source_key not in PUBLIC_SOURCE_KEYS:
        raise FullSchemaError(f"unsupported public recipe source: {source_key}")
    provider = provider_for(source_key=source_key, source_url=source_url)
    match = provider.recipe_path.fullmatch(unquote(urlsplit(source_url).path))
    if not match:
        raise FullSchemaError("source URL is not an approved recipe path")
    native_id = provider_recipe_id or plain_text(metadata.get("providerRecipeId")) or match.group("id")
    if len(native_id.encode("utf-8")) > 80:
        native_id = "h_" + hashlib.sha256(native_id.encode("utf-8")).hexdigest()
    canonical = canonical_recipe_url(provider, source_url, native_id)
    for declared in [metadata.get("canonicalUrl"), recipe.get("url")]:
        if not declared:
            continue
        declared_url = urljoin(canonical, str(declared))
        if unquote(canonical_recipe_url(provider, declared_url, native_id)).rstrip("/") != unquote(canonical).rstrip("/"):
            raise FullSchemaError("publisher canonical recipe URL does not match requested recipe")
    title = plain_text(recipe.get("name") or recipe.get("headline"))
    if not title:
        raise FullSchemaError("Recipe has no title")
    ingredient_sections = metadata.get("ingredientSections") or [{"title": "", "ingredients": [ingredient(text) for text in strings(recipe.get("recipeIngredient"), split_lines=True)]}]
    instructions = recipe.get("recipeInstructions")
    if isinstance(instructions, str):
        instructions = strings(instructions, split_lines=True)
    method_sections, instruction_tips = _instruction_sections(instructions)
    method_sections = metadata.get("methodSections") or method_sections
    ingredient_texts = [str(item.get("title", "")) for section in ingredient_sections for item in section.get("ingredients", [])]
    steps = [step for section in method_sections for step in section.get("steps", [])]
    if not ingredient_texts and source_key == "lucacos" and metadata.get("publisherIngredientListAbsent") is True:
        # The source's empty ingredient article plus empty Recipe data is an
        # explicit omission, not a parser failure or permission to infer foods.
        try:
            from .public_recipe_crawler import RecipeExcluded
        except ImportError:  # pragma: no cover - direct command execution
            from public_recipe_crawler import RecipeExcluded
        raise RecipeExcluded(f"publisher_incomplete_recipe: native_id={native_id}; empty ingredient column and Recipe ingredient list")
    if not ingredient_texts or not steps:
        raise FullSchemaError("Recipe must contain ingredients and instructions")
    languages = strings(recipe.get("inLanguage")) + strings(metadata.get("documentLanguage"))
    if any(not value.casefold().replace("_", "-").startswith(("el", "gr")) for value in languages):
        raise NonGreekRecipe("publisher declares a non-Greek recipe")
    if not re.search(r"[Α-Ωα-ωάέήίόύώϊϋΐΰ]", " ".join([title, *ingredient_texts, *steps])):
        raise NonGreekRecipe("recipe has no Greek content")
    payload = sanitize_source_payload({"jsonLd": dict(recipe), "htmlMetadata": dict(metadata)})
    taxonomy = derive_public_recipe_taxonomy(payload)
    facet_labels = taxonomy["facetLabels"]
    associations = {facet: sorted([{"id": hashlib.sha256(label_key(label).encode()).hexdigest()[:20], "title": label} for label in labels], key=lambda item: (item["title"].casefold(), item["id"])) for facet, labels in facet_labels.items()}
    prep = parse_duration_minutes(recipe.get("prepTime"))
    cook = parse_duration_minutes(recipe.get("cookTime"))
    wait = parse_duration_minutes(metadata.get("waitMinutes"))
    total = max(parse_duration_minutes(recipe.get("totalTime")), prep + cook + wait)
    if metadata.get("totalDurationUncertain") is True:
        total = 0
    rating, rating_count = _rating(recipe)
    images = list(dict.fromkeys(safe_urls(recipe.get("image"), base_url=canonical) + safe_urls(metadata.get("imageUrls"), base_url=canonical)))
    videos = list(dict.fromkeys(safe_urls(recipe.get("video"), base_url=canonical) + safe_urls(metadata.get("videoUrls"), base_url=canonical)))
    nutrition_per, nutrition = _nutrition(recipe.get("nutrition"))
    nutrition = metadata.get("nutritionSections") or nutrition
    meta = metadata.get("meta", {})
    published_at = str(recipe.get("datePublished") or "")
    updated_at = str(recipe.get("dateModified") or "")
    record = {
        "id": recipe_document_id(provider, native_id), "detailSchemaVersion": f"{source_key}-full-v1",
        "sourceKey": source_key, "providerRecipeId": native_id, "sourceRecipeId": int(native_id) if native_id.isdigit() else 0,
        "source": provider.source, "sourceName": provider.display_name, "sourceUrl": canonical, "canonicalUrl": canonical,
        "slug": unquote(urlsplit(canonical).path.rstrip("/").split("/")[-1]), "title": title,
        "description": plain_text(recipe.get("description")), "seoTitle": plain_text(meta.get("og:title") or title),
        "seoDescription": plain_text(meta.get("description") or meta.get("og:description") or recipe.get("description")),
        "categoryKeys": taxonomy["categoryKeys"], "category": taxonomy["category"], "categoryLabel": taxonomy["categoryLabel"], "categorySourceId": 0,
        "rating": rating, "rating10": rating, "ratingCount": rating_count, "rating1": 0, "rating2": 0, "rating3": 0, "rating4": 0, "rating5": 0,
        "prepMinutes": prep, "cookMinutes": cook, "waitMinutes": wait, "totalMinutes": total,
        "sourceDifficulty": plain_text(metadata.get("difficulty")), "ease": classify_ease(len(method_sections), len(steps), total),
        "randomKey": stable_recipe_random_key(provider, native_id), "stepCount": len(steps), "preparationCount": len(method_sections),
        "servings": " · ".join(strings(recipe.get("recipeYield"))), "recipeYield": " · ".join(strings(recipe.get("recipeYield"))),
        "language": "el", "imageUrl": images[0] if images else "", "imageUrls": images, "shortUrl": "",
        "tags": taxonomy["tags"], "quickRecipe": 0 < total < 30, "videoUrls": videos,
        "ingredientSections": ingredient_sections, "methodSections": method_sections,
        "tips": dedupe(instruction_tips + strings(metadata.get("tips"))), "nutritionTips": [], "nutritionPer": nutrition_per,
        "nutritionSections": nutrition, "equipment": dedupe(strings(metadata.get("equipment"))),
        "authorName": " · ".join(strings(recipe.get("author"))) or provider.display_name,
        "published": True, "publishedAt": published_at, "shares": 0, "sponsorLogoUrl": "", "createdAt": published_at,
        "sourceUpdatedAt": updated_at, "updatedAtEpochMillis": _epoch_millis(updated_at), "active": bool(active),
        "sourcePayload": payload, "filterAssociations": associations, "sitemapLastModified": str(sitemap_last_modified or ""),
        **{FACET_LABEL_FIELDS[facet]: labels for facet, labels in facet_labels.items()},
    }
    ensure_full_record(record)
    return record


def normalize_public_recipe_page(html_text: str, *, source_key: str, source_url: str, sitemap_last_modified: str = "", active: bool = True) -> dict[str, Any]:
    recipe, metadata = parse_public_recipe_page(html_text, source_key=source_key, source_url=source_url)
    return normalize_public_recipe_payload(recipe, metadata, source_key=source_key, source_url=source_url, sitemap_last_modified=sitemap_last_modified, active=active)
