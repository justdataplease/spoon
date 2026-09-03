"""Normalize an authorized Gastronomos Greek recipe page for Spoon.

The parser is deliberately JSON-LD first.  HTML is used only for narrowly
scoped recipe-page evidence (official facet links, section labels, media,
difficulty, tips, equipment, and split time labels).  The document itself is
never copied into Firestore.  Tests for this module use synthetic markup only;
network access lives behind the explicit gate in ``crawl_gastronomos.py``.
"""

from __future__ import annotations

import hashlib
import json
import math
import re
import unicodedata
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from datetime import datetime
from html.parser import HTMLParser
from typing import Any
from urllib.parse import parse_qs, urljoin, urlsplit, urlunsplit

try:
    from .full_schema import (
        CATEGORY_LABELS,
        FACET_KEYS,
        GASTRONOMOS_DETAIL_SCHEMA_VERSION,
        FullSchemaError,
        ensure_full_record,
        plain_text,
        sanitize_source_payload,
    )
    from .helpers import canonical_category, classify_ease
    from .providers import (
        GASTRONOMOS,
        canonical_recipe_url,
        recipe_document_id,
        stable_recipe_random_key,
    )
except ImportError:  # pragma: no cover - direct script execution
    from full_schema import (  # type: ignore[no-redef]
        CATEGORY_LABELS,
        FACET_KEYS,
        GASTRONOMOS_DETAIL_SCHEMA_VERSION,
        FullSchemaError,
        ensure_full_record,
        plain_text,
        sanitize_source_payload,
    )
    from helpers import canonical_category, classify_ease  # type: ignore[no-redef]
    from providers import (  # type: ignore[no-redef]
        GASTRONOMOS,
        canonical_recipe_url,
        recipe_document_id,
        stable_recipe_random_key,
    )


SITE_ORIGIN = "https://www.gastronomos.gr"
_DURATION_RE = re.compile(
    r"^P(?:(?P<days>\d+(?:\.\d+)?)D)?(?:T"
    r"(?:(?P<hours>\d+(?:\.\d+)?)H)?"
    r"(?:(?P<minutes>\d+(?:\.\d+)?)M)?"
    r"(?:(?P<seconds>\d+(?:\.\d+)?)S)?)?$",
    re.I,
)
_TEXT_DURATION_RE = re.compile(
    r"(?P<number>\d+(?:[.,]\d+)?)\s*"
    r"(?P<unit>ημερ\w*|μέρ\w*|day\w*|ωρ\w*|ώρ\w*|hour\w*|"
    r"λεπτ\w*|min\w*|δευτερ\w*|second\w*)",
    re.I,
)
_VOID_ELEMENTS = {
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link",
    "meta", "param", "source", "track", "wbr",
}
_SCOPED_CLASS_MARKERS = (
    "recipe", "syntagh", "single-post", "post-content", "entry-content",
    "article-content",
)
_EXCLUDED_SCOPE_MARKERS = (
    "related", "recommend", "read-more", "more-recipes", "advert", "banner",
    "sidebar", "navigation", "breadcrumb", "social-share",
)
_MEDIA_SCOPE_TOKENS = frozenset({
    "entry-content",
    "post-content",
    "article-content",
    "syntagh-content",
    "recipe-content",
    "recipe__content",
})
_MEDIA_EXCLUDED_MARKERS = (
    *_EXCLUDED_SCOPE_MARKERS,
    "future", "latest", "popular", "suggest",
)
_EMBED_VIDEO_HOSTS = frozenset({
    "youtube.com",
    "m.youtube.com",
    "youtu.be",
    "youtube-nocookie.com",
    "vimeo.com",
    "player.vimeo.com",
})
_DIRECT_VIDEO_SUFFIXES = (".mp4", ".webm", ".m3u8")
_INGREDIENT_GROUP_MARKERS = (
    "ingredient-section", "ingredients-section", "ingredient-group",
    "ingredients-group", "recipe-ingredients__group", "recipe_ingredients_group",
    "recipe-ingredients", "ingredients", "ylika",
)
_AUDITED_LEGACY_ENTRY_PROFILE_IDS = frozenset({"90262"})
_LEGACY_INGREDIENT_HEADING = "υλικα"
_LEGACY_METHOD_HEADING = "διαδικασια"
_LEGACY_EXPECTED_INGREDIENT_COUNT = 4
_LEGACY_EXPECTED_METHOD_STEP_COUNTS = (5, 2, 3)
_FACET_FAMILIES = {
    "vasiko-yliko": "ingredient",
    "eidos-geumatos": "meal_type",
    "eidiki-diatrofi": "diet",
}

# ``/eidos-syntagon/`` mixes cuisines, methods, and generic recipe types.  Only
# these reviewed exact slugs are promoted to a facet; every other value remains
# a visible tag instead of being guessed into the wrong filter.
_CUISINE_SLUGS = {
    "ellinika-paradosiaka", "elliniki", "mesogeiaki", "italiki", "galliki",
    "ispaniki", "mexikaniki", "asiatiki", "indiki", "amerikaniki",
    "anatolitiki", "kritiki", "kykladitiki", "politiki", "pontiaki",
}
_METHOD_SLUGS = {
    "fourno", "fournou", "katsarola", "katsarolas", "tigani", "tiganita",
    "sxara", "schara", "grill", "barbecue", "bbq", "xoris-mageirema",
    "stovetop", "atmos", "ston-atmo", "gastra", "gastras",
}
_SCHEMA_DIETS = {
    "vegandiet": "Vegan",
    "vegetariandiet": "Χορτοφαγική",
    "glutenfreediet": "Χωρίς γλουτένη",
    "lowcaloriediet": "Χαμηλών θερμίδων",
    "lowfatdiet": "Χαμηλών λιπαρών",
    "lowlactosediet": "Χωρίς λακτόζη",
    "lowsaltdiet": "Χαμηλή σε αλάτι",
    "diabeticdiet": "Κατάλληλη για διαβητικούς",
}
_CATEGORY_LABEL_MAP = {
    "dessert": {
        "γλυκο", "γλυκα", "επιδορπιο", "επιδορπια", "dessert", "desserts",
        "glyko", "glyka", "epidorpio", "epidorpia",
    },
    "fish": {
        "ψαρι", "ψαρια", "θαλασσινα", "οστρακοειδη", "fish", "seafood",
        "psari", "psaria", "thalassina",
    },
    "legumes": {
        "οσπρια", "φακες", "φασολια", "ρεβιθια", "φάβα", "fava", "legumes",
        "ospria", "fakes", "fasolia", "revithia",
    },
    "poultry": {
        "πουλερικα", "κοτοπουλο", "γαλοπουλα", "παπια", "poultry", "chicken",
        "poulerika", "kotopoulo", "galopoula", "papia",
    },
    "meat": {
        "κρεας", "μοσχαρι", "χοιρινο", "αρνι", "κατσικι", "κουνελι", "meat",
        "kreas", "moschari", "xoirino", "arni", "katsiki", "kouneli",
    },
    "pasta_rice": {
        "ζυμαρικα", "μακαρονια", "ρυζι", "ριζοτο", "κριθαρακι", "pasta", "rice",
        "zymarika", "makaronia", "ryzi", "rizi", "risotto", "kritharaki",
    },
    "vegetables": {
        "λαχανικα", "λαδερα", "πατατες", "vegetables", "lachanika", "ladera",
        "patates",
    },
}
_TERMINAL_OTHER = {
    "ροφημα", "ροφηματα", "ποτο", "ποτα", "cocktail", "cocktails", "smoothie",
    "smoothies", "χυμοι", "μαρμελαδες", "σαλτσες", "ντιπ", "ψωμια", "ζυμες",
    "rofima", "rofimata", "poto", "pota", "chymoi", "marmelades", "saltses",
    "ntip", "psomia", "zymes",
}
_STREET_FORMATS = {
    "street food", "street-food", "streetfood", "σαντουιτς", "sandwich",
    "sandwiches", "burger", "burgers", "μπεργκερ", "finger food", "finger-food",
    "fingerfood", "santouits", "mperegker", "mpergker",
}
_CATEGORY_ORDER = (
    "fish", "legumes", "poultry", "meat", "pasta_rice", "vegetables",
)
_NUTRITION_FIELDS = {
    "calories": "kcal",
    "fatContent": "fat",
    "saturatedFatContent": "saturatedFat",
    "carbohydrateContent": "carbs",
    "sugarContent": "sugars",
    "proteinContent": "protein",
    "fiberContent": "fiber",
    "sodiumContent": "sodium",
}


class GastronomosLanguageError(FullSchemaError):
    """Raised when a strict recipe page is explicitly or substantively non-Greek."""

    def __init__(self, provider_recipe_id: str, reason: str) -> None:
        self.provider_recipe_id = provider_recipe_id
        self.reason = reason
        super().__init__(f"Gastronomos recipe is not Greek: {reason}")


@dataclass
class _Node:
    tag: str
    attrs: dict[str, str]
    parent: "_Node | None" = None
    children: list["_Node"] = field(default_factory=list)
    text: list[str] = field(default_factory=list)
    # ``text`` and ``children`` are retained for byte-compatible existing
    # extraction. ``content`` alone preserves the position of inline text
    # around child elements for the audited legacy entry-content parser.
    content: list[object] = field(default_factory=list)


class _DocumentParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.root = _Node("document", {})
        self.current = self.root

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        name = tag.casefold()
        parent = self.current
        node = _Node(
            name,
            {key.casefold(): value or "" for key, value in attrs},
            parent,
        )
        parent.children.append(node)
        parent.content.append(node)
        if name not in _VOID_ELEMENTS:
            self.current = node

    def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        self.handle_starttag(tag, attrs)
        if tag.casefold() not in _VOID_ELEMENTS:
            self.handle_endtag(tag)

    def handle_endtag(self, tag: str) -> None:
        name = tag.casefold()
        cursor = self.current
        while cursor is not self.root and cursor.tag != name:
            cursor = cursor.parent or self.root
        if cursor is not self.root:
            self.current = cursor.parent or self.root

    def handle_data(self, data: str) -> None:
        self.current.text.append(data)
        self.current.content.append(data)


def _descendants(node: _Node) -> Iterable[_Node]:
    for child in node.children:
        yield child
        yield from _descendants(child)


def _node_text(node: _Node) -> str:
    parts: list[str] = []

    def visit(current: _Node) -> None:
        if current.tag in {"script", "style", "noscript"}:
            return
        parts.extend(current.text)
        for child in current.children:
            visit(child)

    visit(node)
    return plain_text(" ".join(parts))


def _raw_node_text(node: _Node) -> str:
    parts = list(node.text)
    for child in node.children:
        parts.append(_raw_node_text(child))
    return "".join(parts)


def _ordered_node_text(node: _Node) -> str:
    """Return visible text in source order without changing legacy callers."""
    parts: list[str] = []

    def visit(current: _Node) -> None:
        if current.tag in {"script", "style", "noscript"}:
            return
        if current.tag == "br":
            parts.append("\n")
            return
        for item in current.content:
            if isinstance(item, str):
                parts.append(item)
            elif isinstance(item, _Node):
                visit(item)

    visit(node)
    return plain_text("".join(parts))


def _tokens(node: _Node) -> set[str]:
    return {
        item.casefold()
        for key in ("class", "id")
        for item in re.split(r"\s+", node.attrs.get(key, ""))
        if item
    }


def _has_marker(node: _Node, markers: Sequence[str]) -> bool:
    joined = " ".join(_tokens(node))
    return any(marker in joined for marker in markers)


def _is_recipe_scoped(node: _Node) -> bool:
    cursor: _Node | None = node
    scoped = False
    while cursor is not None:
        if cursor.tag in {"nav", "footer"} or (
            cursor.tag != "body"
            and _has_marker(cursor, _EXCLUDED_SCOPE_MARKERS)
        ):
            return False
        if cursor.tag != "body" and (
            cursor.tag == "article"
            or _has_marker(cursor, _SCOPED_CLASS_MARKERS)
        ):
            scoped = True
        cursor = cursor.parent
    return scoped


def _safe_https_url(value: object, *, base_url: str = SITE_ORIGIN) -> str:
    text = str(value or "").strip()
    if not text:
        return ""
    candidate = urljoin(base_url, text)
    parts = urlsplit(candidate)
    if (
        parts.scheme.casefold() != "https"
        or not parts.hostname
        or parts.username
        or parts.password
        or parts.port not in (None, 443)
    ):
        return ""
    return urlunsplit(("https", parts.netloc.casefold(), parts.path, parts.query, ""))


def _strings(value: object) -> list[str]:
    if value is None:
        return []
    values = value if isinstance(value, (list, tuple)) else [value]
    result: list[str] = []
    for item in values:
        if isinstance(item, Mapping):
            item = item.get("name") or item.get("text") or item.get("url") or item.get("@id")
        text = plain_text(item)
        if text and text not in result:
            result.append(text)
    return result


def _https_urls(value: object) -> list[str]:
    candidates: list[object] = []

    def collect(item: object) -> None:
        if isinstance(item, str):
            candidates.append(item)
        elif isinstance(item, Mapping):
            for key in ("url", "contentUrl", "embedUrl", "thumbnailUrl", "@id"):
                if key in item:
                    collect(item[key])
        elif isinstance(item, Sequence) and not isinstance(item, (bytes, bytearray)):
            for child in item:
                collect(child)

    collect(value)
    result: list[str] = []
    for candidate in candidates:
        url = _safe_https_url(candidate)
        if url and url not in result:
            result.append(url)
    return result


def _jsonld_nodes(value: object) -> Iterable[Mapping[str, Any]]:
    if isinstance(value, Mapping):
        yield value
        graph = value.get("@graph")
        if isinstance(graph, Sequence) and not isinstance(graph, (str, bytes, bytearray)):
            for child in graph:
                yield from _jsonld_nodes(child)
    elif isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray)):
        for child in value:
            yield from _jsonld_nodes(child)


def _is_recipe(value: Mapping[str, Any]) -> bool:
    raw = value.get("@type")
    types = raw if isinstance(raw, list) else [raw]
    return any(str(item).casefold().rsplit("/", 1)[-1] == "recipe" for item in types)


def _canonical_candidate(value: object, provider_id: str) -> str:
    for candidate in _https_urls(value):
        try:
            return canonical_recipe_url(GASTRONOMOS, candidate, provider_id)
        except ValueError:
            continue
    return ""


def _select_recipe(values: Sequence[object], provider_id: str, source_url: str) -> Mapping[str, Any]:
    recipes = [node for value in values for node in _jsonld_nodes(value) if _is_recipe(node)]
    matching = [
        item for item in recipes
        if not item.get("url")
        or _canonical_candidate(item.get("url"), provider_id) == source_url
    ]
    candidates = matching
    if len(candidates) != 1:
        raise FullSchemaError(
            f"Gastronomos page must contain exactly one matching JSON-LD Recipe; found {len(candidates)}"
        )
    return candidates[0]


def _normalized_label(value: object) -> str:
    normalized = unicodedata.normalize("NFKD", plain_text(value).casefold())
    without_accents = "".join(char for char in normalized if not unicodedata.combining(char))
    return " ".join(re.sub(r"[^\w]+", " ", without_accents).split())


def _dedupe_labels(values: Iterable[object]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        text = plain_text(value)
        identity = _normalized_label(text)
        if text and identity and identity not in seen:
            seen.add(identity)
            result.append(text)
    return result


def _machine_key(value: str) -> str:
    normalized = unicodedata.normalize("NFKD", value)
    ascii_value = normalized.encode("ascii", "ignore").decode("ascii").casefold()
    result = re.sub(r"[^a-z0-9]+", "-", ascii_value).strip("-")
    return result[:80] or hashlib.sha256(value.encode("utf-8")).hexdigest()[:16]


def _facet_links(root: _Node) -> list[dict[str, str]]:
    result: list[dict[str, str]] = []
    seen: set[tuple[str, str]] = set()
    for node in _descendants(root):
        if node.tag != "a" or not _is_recipe_scoped(node):
            continue
        href = _safe_https_url(node.attrs.get("href"))
        label = _node_text(node)
        parts = [part for part in urlsplit(href).path.split("/") if part]
        if not href or not label or len(parts) != 2:
            continue
        family, slug = parts[0].casefold(), parts[1].casefold()
        if family not in {*_FACET_FAMILIES, "eidos-syntagon", "tag", "tags"}:
            continue
        identity = (href, _normalized_label(label))
        if identity in seen:
            continue
        seen.add(identity)
        result.append({"href": href, "label": label, "family": family, "slug": slug})
    return result


def _meta_values(root: _Node) -> dict[str, str]:
    result: dict[str, str] = {}
    for node in _descendants(root):
        if node.tag != "meta":
            continue
        key = (
            node.attrs.get("property")
            or node.attrs.get("name")
            or node.attrs.get("itemprop")
            or ""
        ).strip().casefold()
        value = node.attrs.get("content", "").strip()
        if key and value and key not in result:
            result[key] = value
    return result


def _document_language(root: _Node) -> str:
    return next(
        (node.attrs.get("lang", "").strip() for node in _descendants(root) if node.tag == "html"),
        "",
    )


def _canonical_link(root: _Node) -> str:
    for node in _descendants(root):
        if node.tag == "link" and "canonical" in node.attrs.get("rel", "").casefold().split():
            return _safe_https_url(node.attrs.get("href"))
    return ""


def _is_recipe_media_scoped(node: _Node) -> bool:
    cursor: _Node | None = node
    scoped = False
    while cursor is not None:
        if cursor.tag in {"header", "nav", "footer"} or (
            cursor.tag != "body"
            and _has_marker(cursor, _MEDIA_EXCLUDED_MARKERS)
        ):
            return False
        if _tokens(cursor) & _MEDIA_SCOPE_TOKENS:
            scoped = True
        cursor = cursor.parent
    return scoped


def _approved_scoped_video(node: _Node, value: str) -> bool:
    parts = urlsplit(value)
    host = (parts.hostname or "").casefold().removeprefix("www.")
    if node.tag == "iframe":
        return host in _EMBED_VIDEO_HOSTS
    return host in _EMBED_VIDEO_HOSTS or parts.path.casefold().endswith(
        _DIRECT_VIDEO_SUFFIXES
    )


def _scoped_media(root: _Node) -> tuple[list[str], list[str]]:
    images: list[str] = []
    videos: list[str] = []
    for node in _descendants(root):
        if not _is_recipe_media_scoped(node):
            continue
        if node.tag == "img":
            value = ""
            for key in ("data-src", "data-lazy-src", "src"):
                value = _safe_https_url(node.attrs.get(key))
                if value:
                    break
            if not value:
                srcset = (
                    node.attrs.get("data-srcset")
                    or node.attrs.get("srcset")
                    or ""
                )
                candidates = [
                    _safe_https_url(tokens[0])
                    for candidate in srcset.split(",")
                    if (tokens := candidate.strip().split(maxsplit=1))
                ]
                value = next(
                    (candidate for candidate in reversed(candidates) if candidate),
                    "",
                )
            if value and value not in images:
                images.append(value)
        if node.tag in {"iframe", "video", "source"}:
            for key in ("src", "data-src"):
                value = _safe_https_url(node.attrs.get(key))
                if (
                    value
                    and _approved_scoped_video(node, value)
                    and value not in videos
                ):
                    videos.append(value)
                    break
    return images, videos


def _heading(node: _Node) -> str:
    for child in node.children:
        if child.tag in {"h2", "h3", "h4", "h5", "legend"}:
            text = _node_text(child)
            if text:
                return text
    return ""


def _ingredient_entry(node: _Node) -> dict[str, str] | None:
    text = _node_text(node)
    if not text:
        return None
    internal = ""
    external = ""
    for child in _descendants(node):
        if child.tag != "a":
            continue
        href = _safe_https_url(child.attrs.get("href"))
        if not href:
            continue
        host = (urlsplit(href).hostname or "").casefold().removeprefix("www.")
        if host == "gastronomos.gr":
            internal = href
        else:
            external = href
    return {
        "title": text,
        "unit": "",
        "quantity": "",
        "info": "",
        "internalLink": internal,
        "externalLink": external,
        "ukUnit": "",
        "ukQuantity": "",
        "usUnit": "",
        "usQuantity": "",
    }


def _html_ingredient_sections(root: _Node) -> list[dict[str, Any]]:
    groups = [
        node for node in _descendants(root)
        if node.tag in {"section", "div", "fieldset"}
        and _is_recipe_scoped(node)
        and _has_marker(node, _INGREDIENT_GROUP_MARKERS)
    ]
    # Retain only the deepest marked groups to avoid duplicating an outer wrapper.
    groups = [
        node for node in groups
        if not any(child in groups for child in _descendants(node))
    ]
    result: list[dict[str, Any]] = []
    for group in groups:
        item_nodes = [
            node for node in _descendants(group)
            if node.tag == "li" or node.attrs.get("itemprop", "").casefold() == "recipeingredient"
        ]
        # Do not emit nested list items twice.
        item_nodes = [
            node for node in item_nodes
            if not any(parent in item_nodes for parent in _ancestors(node, stop=group))
        ]
        ingredients = [item for node in item_nodes if (item := _ingredient_entry(node))]
        if ingredients:
            result.append({"title": _heading(group), "ingredients": ingredients})
    return result


def _ancestors(node: _Node, *, stop: _Node | None = None) -> Iterable[_Node]:
    cursor = node.parent
    while cursor is not None and cursor is not stop:
        yield cursor
        cursor = cursor.parent


def _legacy_ingredient(text: str) -> dict[str, str]:
    return {
        "title": text,
        "unit": "",
        "quantity": "",
        "info": "",
        "internalLink": "",
        "externalLink": "",
        "ukUnit": "",
        "ukQuantity": "",
        "usUnit": "",
        "usQuantity": "",
    }


def _legacy_entry_blocks(root: _Node) -> list[tuple[_Node, str]]:
    block_tags = {"h2", "h3", "h4", "h5", "h6", "li", "p"}
    result: list[tuple[_Node, str]] = []
    for node in _descendants(root):
        if node.tag not in block_tags or not _is_recipe_scoped(node):
            continue
        # A paragraph inside a list item is part of that item, not another
        # method step. The same rule prevents malformed nested paragraphs from
        # being emitted twice.
        if any(
            ancestor.tag in {"li", "p"}
            for ancestor in _ancestors(node, stop=root)
        ):
            continue
        text = _ordered_node_text(node)
        if text:
            result.append((node, text))
    return result


def _extract_audited_legacy_entry_content(
    root: _Node,
    *,
    provider_id: str,
) -> dict[str, Any]:
    """Extract the one audited pre-schema recipe; never guess on other IDs."""
    if provider_id not in _AUDITED_LEGACY_ENTRY_PROFILE_IDS:
        return {}

    entry_roots = [
        node for node in _descendants(root)
        if "entry-content" in _tokens(node) and _is_recipe_scoped(node)
    ]
    entry_roots = [
        node for node in entry_roots
        if not any("entry-content" in _tokens(child) for child in _descendants(node))
    ]
    if len(entry_roots) != 1:
        raise FullSchemaError(
            f"Gastronomos audited legacy entry profile {provider_id} changed entry-content roots"
        )

    blocks = _legacy_entry_blocks(entry_roots[0])
    ingredient_markers = [
        index for index, (node, text) in enumerate(blocks)
        if node.tag in {"h2", "h3", "h4", "h5", "h6"}
        and _normalized_label(text) == _LEGACY_INGREDIENT_HEADING
    ]
    method_markers = [
        index for index, (node, text) in enumerate(blocks)
        if node.tag in {"h2", "h3", "h4", "h5", "h6"}
        and _normalized_label(text) == _LEGACY_METHOD_HEADING
    ]
    if (
        len(ingredient_markers) != 1
        or len(method_markers) != 1
        or ingredient_markers[0] >= method_markers[0]
    ):
        raise FullSchemaError(
            f"Gastronomos audited legacy entry profile {provider_id} changed section markers"
        )

    ingredient_index = ingredient_markers[0]
    method_index = method_markers[0]
    ingredient_paragraphs = [
        text for node, text in blocks[ingredient_index + 1:method_index]
        if node.tag == "p" and text.lstrip().startswith("•")
    ]
    if len(ingredient_paragraphs) != 1:
        raise FullSchemaError(
            f"Gastronomos audited legacy entry profile {provider_id} changed ingredient blocks"
        )
    ingredient_texts = [
        " ".join(plain_text(part).split())
        for part in ingredient_paragraphs[0].split("•")[1:]
        if plain_text(part)
    ]
    if (
        len(ingredient_texts) != _LEGACY_EXPECTED_INGREDIENT_COUNT
        or any(not any(character.isalpha() for character in item) for item in ingredient_texts)
    ):
        raise FullSchemaError(
            f"Gastronomos audited legacy entry profile {provider_id} changed ingredient count"
        )

    heading_tags = {"h2", "h3", "h4", "h5", "h6"}
    method_sections: list[dict[str, Any]] = []
    section_title = blocks[method_index][1]
    section_steps: list[str] = []

    def flush_method_section() -> None:
        nonlocal section_steps
        if section_steps:
            method_sections.append({"title": section_title, "steps": section_steps})
        section_steps = []

    for node, text in blocks[method_index + 1:]:
        if node.tag in heading_tags:
            flush_method_section()
            section_title = text
        elif node.tag in {"li", "p"}:
            section_steps.append(text)
    flush_method_section()
    if tuple(len(section["steps"]) for section in method_sections) != (
        _LEGACY_EXPECTED_METHOD_STEP_COUNTS
    ):
        raise FullSchemaError(
            f"Gastronomos audited legacy entry profile {provider_id} changed method shape"
        )

    ingredient_title = blocks[ingredient_index][1]
    return {
        "legacyEntryProfile": "entry-content-90262-v1",
        "legacyIngredientSections": [{
            "title": ingredient_title,
            "ingredients": [_legacy_ingredient(item) for item in ingredient_texts],
        }],
        "legacyMethodSections": method_sections,
    }


def _labelled_values(root: _Node) -> list[dict[str, str]]:
    values: list[dict[str, str]] = []
    seen: set[tuple[str, str]] = set()
    for node in _descendants(root):
        if not _is_recipe_scoped(node):
            continue
        pair: tuple[str, str] | None = None
        label_attr = node.attrs.get("data-label") or node.attrs.get("aria-label")
        if label_attr:
            pair = (plain_text(label_attr), _node_text(node))
        elif node.tag == "dt" and node.parent:
            siblings = node.parent.children
            index = siblings.index(node)
            following = next((item for item in siblings[index + 1:] if item.tag == "dd"), None)
            if following:
                pair = (_node_text(node), _node_text(following))
        elif node.tag in {"tr", "li", "div"} and _has_marker(node, ("recipe-time", "recipe-meta")):
            children = [child for child in node.children if _node_text(child)]
            if len(children) >= 2:
                pair = (_node_text(children[0]), _node_text(children[-1]))
        if pair and pair[0] and pair[1]:
            identity = (_normalized_label(pair[0]), _normalized_label(pair[1]))
            if identity not in seen:
                seen.add(identity)
                values.append({"label": pair[0], "value": pair[1]})
    return values


def _scoped_text_collection(root: _Node, markers: Sequence[str]) -> list[str]:
    result: list[str] = []
    for node in _descendants(root):
        if not _is_recipe_scoped(node) or not _has_marker(node, markers):
            continue
        nested_matches = [child for child in _descendants(node) if _has_marker(child, markers)]
        if nested_matches:
            continue
        leaves = [child for child in _descendants(node) if child.tag in {"li", "p"}]
        values = [_node_text(child) for child in leaves] or [_node_text(node)]
        for value in values:
            if value and value not in result:
                result.append(value)
    return result


def parse_gastronomos_page(
    html_text: str,
    *,
    source_url: str,
) -> tuple[Mapping[str, Any], dict[str, Any]]:
    match = GASTRONOMOS.recipe_path.fullmatch(urlsplit(source_url).path)
    if not match:
        raise FullSchemaError("Gastronomos source URL is not a strict recipe URL")
    provider_id = match.group("id")
    parser = _DocumentParser()
    parser.feed(html_text)
    parser.close()

    jsonld_values: list[object] = []
    invalid_jsonld_count = 0
    for node in _descendants(parser.root):
        if node.tag != "script" or node.attrs.get("type", "").casefold().split(";", 1)[0].strip() != "application/ld+json":
            continue
        try:
            jsonld_values.append(json.loads(_raw_node_text(node)))
        except json.JSONDecodeError:
            invalid_jsonld_count += 1
    recipe = _select_recipe(jsonld_values, provider_id, source_url)
    scoped_images, scoped_videos = _scoped_media(parser.root)
    metas = _meta_values(parser.root)
    labelled = _labelled_values(parser.root)
    difficulty = next(
        (
            item["value"] for item in labelled
            if _normalized_label(item["label"]) in {"δυσκολια", "βαθμος δυσκολιας", "difficulty"}
        ),
        "",
    )
    if not difficulty:
        difficulty = next(
            (
                _node_text(node) for node in _descendants(parser.root)
                if _is_recipe_scoped(node) and _has_marker(node, ("difficulty", "dyskolia"))
            ),
            "",
        )
    metadata: dict[str, Any] = {
        "documentLanguage": _document_language(parser.root),
        "canonicalUrl": _canonical_link(parser.root),
        "meta": metas,
        "facetLinks": _facet_links(parser.root),
        "ingredientSections": _html_ingredient_sections(parser.root),
        "timeLabels": labelled,
        "difficulty": difficulty,
        "scopedImageUrls": scoped_images,
        "scopedVideoUrls": scoped_videos,
        "tips": _scoped_text_collection(parser.root, ("recipe-tip", "tips", "symvouli", "mystiko")),
        "equipment": _scoped_text_collection(parser.root, ("equipment", "exoplismos")),
        "invalidJsonLdBlockCount": invalid_jsonld_count,
    }
    legacy_details = _extract_audited_legacy_entry_content(
        parser.root,
        provider_id=provider_id,
    )
    if legacy_details:
        metadata.update(legacy_details)
    return recipe, metadata


def parse_duration_minutes(value: object) -> int:
    if value is None or isinstance(value, bool):
        return 0
    if isinstance(value, (int, float)):
        return max(0, math.ceil(float(value))) if math.isfinite(float(value)) else 0
    text = plain_text(value).strip()
    match = _DURATION_RE.fullmatch(text)
    if match:
        minutes = (
            float(match.group("days") or 0) * 1440
            + float(match.group("hours") or 0) * 60
            + float(match.group("minutes") or 0)
            + float(match.group("seconds") or 0) / 60
        )
        return max(0, math.ceil(minutes))
    total = 0.0
    for part in _TEXT_DURATION_RE.finditer(text):
        amount = float(part.group("number").replace(",", "."))
        unit = _normalized_label(part.group("unit"))
        multiplier = 1440 if unit.startswith(("ημερ", "μερ", "day")) else 60 if unit.startswith(("ωρ", "hour")) else 1 / 60 if unit.startswith(("δευτερ", "second")) else 1
        total += amount * multiplier
    return max(0, math.ceil(total)) if total else 0


def _instruction_sections(value: object) -> tuple[list[dict[str, Any]], list[str]]:
    sections: list[dict[str, Any]] = []
    tips: list[str] = []

    def walk(item: object, title: str = "") -> list[str]:
        if isinstance(item, str):
            text = plain_text(item)
            return [text] if text else []
        if isinstance(item, Sequence) and not isinstance(item, (str, bytes, bytearray)):
            return [step for child in item for step in walk(child, title)]
        if not isinstance(item, Mapping):
            return []
        raw_type = item.get("@type")
        types = raw_type if isinstance(raw_type, list) else [raw_type]
        names = {str(entry).casefold().rsplit("/", 1)[-1] for entry in types}
        item_title = plain_text(item.get("name") or title)
        children = item.get("itemListElement") or item.get("steps")
        if "howtosection" in names:
            steps = walk(children, item_title)
            if steps:
                sections.append({"title": item_title, "steps": steps})
            return []
        if "howtotip" in names:
            for text in _strings(item.get("text") or item.get("name")):
                if text not in tips:
                    tips.append(text)
            return []
        if children is not None:
            return walk(children, item_title)
        text = plain_text(item.get("text") or item.get("description") or item.get("name"))
        return [text] if text else []

    loose = walk(value)
    if loose:
        sections.insert(0, {"title": "", "steps": loose})
    return sections, tips


def _jsonld_ingredient_sections(recipe: Mapping[str, Any]) -> list[dict[str, Any]]:
    ingredients = _strings(recipe.get("recipeIngredient"))
    return [{
        "title": "",
        "ingredients": [{
            "title": item,
            "unit": "",
            "quantity": "",
            "info": "",
            "internalLink": "",
            "externalLink": "",
            "ukUnit": "",
            "ukQuantity": "",
            "usUnit": "",
            "usQuantity": "",
        } for item in ingredients],
    }] if ingredients else []


def _ingredient_texts(sections: Sequence[Mapping[str, Any]]) -> list[str]:
    return [
        plain_text(item.get("title"))
        for section in sections
        for item in section.get("ingredients", [])
        if isinstance(item, Mapping) and plain_text(item.get("title"))
    ]


def _aligned_html_ingredients(
    html_sections: object,
    jsonld_sections: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    if not isinstance(html_sections, list) or not html_sections:
        return jsonld_sections
    structured = _ingredient_texts(jsonld_sections)
    rendered = _ingredient_texts(html_sections)
    if len(structured) != len(rendered):
        return jsonld_sections
    for left, right in zip(structured, rendered, strict=True):
        left_key, right_key = _normalized_label(left), _normalized_label(right)
        if not left_key or not right_key or (left_key not in right_key and right_key not in left_key):
            return jsonld_sections
    return html_sections


def _split_keywords(value: object) -> list[str]:
    result: list[str] = []
    for item in _strings(value):
        for part in item.split(","):
            text = plain_text(part)
            if text and text not in result:
                result.append(text)
    return result


def _category_matches(values: Iterable[object]) -> list[str]:
    normalized = {_normalized_label(value) for value in values}
    return [
        key for key in _CATEGORY_ORDER
        if normalized & {_normalized_label(item) for item in _CATEGORY_LABEL_MAP[key]}
    ]


def _exact_label_keys(values: Iterable[str]) -> frozenset[str]:
    return frozenset(_normalized_label(value) for value in values)


_SCHEMA_MEAL_TYPE_KEY_ALLOWLIST = _exact_label_keys((
    "Κυρίως Γεύμα",
    "Γλυκό",
    "Ορεκτικό / Μεζές",
    "Σαλάτα",
    "Συνοδευτικά",
    "Πρωινό",
    "Σνακ",
    "Κοκτέιλ",
    "Ροφήματα",
))
_KEYWORD_FACET_KEY_ALLOWLISTS = {
    "cuisine": _exact_label_keys((
        "Ιταλική Κουζίνα",
        "Μικρασιατική Κουζίνα",
        "Πολίτικη κουζίνα",
        "Σμυρνέικη Κουζίνα",
    )),
    "diet": _exact_label_keys((
        "Light",
        "Vegan",
        "Νηστίσιμα",
        "Νηστεία",
        "Χορτοφαγικά",
        "Χωρίς γαλακτοκομικά",
        "Χωρίς γλουτένη",
        "Χωρίς ζάχαρη",
    )),
    "meal_type": _exact_label_keys(("Brunch",)),
    "occasion": _exact_label_keys((
        "25η Μαρτίου",
        "Halloween",
        "Απόκριες",
        "Καθαρά Δευτέρα",
        "Πάσχα",
        "Σαρακοστή",
        "Τσικνοπέμπτη",
        "Χριστούγεννα",
    )),
    "method": _exact_label_keys(("BBQ",)),
    # Filled only from the independently audited, exact publisher vocabulary.
    # An unknown future keyword remains a visible tag instead of being guessed
    # into an ingredient filter.
    "ingredient": _exact_label_keys((
        "Αβοκάντο",
        "Αγγούρι",
        "Αγκινάρες",
        "Αγριογούρουνο",
        "Αθερίνα / Μαρίδα",
        "Ακτινίδιο",
        "Αλεύρι (ζύμες)",
        "Αλκοόλ",
        "Αλλαντικά",
        "Αμύγδαλα",
        "Ανάμεικτος κιμάς",
        "Ανανάς",
        "Αντίδια",
        "Απάκι",
        "Αρακάς",
        "Αρνί",
        "Αρνίσιος κιμάς",
        "Αστακός",
        "Αυγά",
        "Αυγοτάραχο",
        "Αχλάδι",
        "Βατόμουρα",
        "Βερίκοκα",
        "Βλίτα",
        "Βούτυρο",
        "Βραστόψαρα",
        "Βρώμη",
        "Βότκα",
        "Βύσσινο",
        "Γάλα",
        "Γάλα Αμυγδάλου",
        "Γάλα Καρύδας",
        "Γάλα Ρυζιού",
        "Γαλακτοκομικά",
        "Γαλοπούλα",
        "Γαρίδες",
        "Γαύρος",
        "Γιαούρτι",
        "Γκρέιπφρουτ",
        "Γλυκαντικά",
        "Γλυκοπατάτα",
        "Γλώσσα",
        "Γόπες",
        "Δαμάσκηνα",
        "Δημητριακά",
        "Διάφορα χόρτα εποχής",
        "Ελάφι",
        "Ελιά",
        "Ζάχαρη",
        "Ζυμαρικά",
        "Θαλασσινά",
        "Κάσιους",
        "Κάστανα",
        "Κέφαλος",
        "Κίτρο",
        "Καβούρι",
        "Κακάο",
        "Καλαμάρι / Θράψαλο",
        "Καπόνι",
        "Καραβίδες",
        "Καρπούζι",
        "Καρότα",
        "Καρύδα",
        "Καρύδια",
        "Κατσίκι",
        "Κεράσι",
        "Κιμάς",
        "Κιμάς γαλοπούλας",
        "Κιμάς κοτόπουλου",
        "Κινόα",
        "Κοκκινόψαρο",
        "Κολιός",
        "Κολοκυθάκια",
        "Κολοκύθα",
        "Κονιάκ",
        "Κοτόπουλο",
        "Κουκιά",
        "Κουκουνάρι",
        "Κουνέλι",
        "Κουνουπίδι",
        "Κους κους",
        "Κουτσομούρες",
        "Κράνμπερι",
        "Κρέας",
        "Κρέμα Γάλακτος",
        "Κρασί",
        "Κρεμμύδι",
        "Κριθαράκι",
        "Κυδώνι",
        "Κυδώνια / Όστρακα",
        "Κόκορας",
        "Κόλιανδρος",
        "Λάιμ",
        "Λάχανο",
        "Λαβράκι",
        "Λακέρδα",
        "Λαχανικά",
        "Λεμόνι",
        "Λικέρ",
        "Λουκάνικα",
        "Μάνγκο",
        "Μάραθος",
        "Μέλι",
        "Μήλο",
        "Μαγιάτικο",
        "Μανιτάρια",
        "Μανταρίνι",
        "Μαρούλι",
        "Μαϊντανός",
        "Μελιτζάνες",
        "Μοσχάρι",
        "Μοσχαρίσιος κιμάς",
        "Μούσμουλα",
        "Μπάμιες",
        "Μπέικον",
        "Μπακαλιάρος",
        "Μπανάνα",
        "Μπαρμπούνια",
        "Μπρόκολο",
        "Μυρώνια",
        "Μύδια",
        "Μύρτιλο",
        "Νεκταρίνια",
        "Νεράντζι",
        "Νουντλς",
        "Ντομάτα",
        "Ξερά βερίκοκα",
        "Ξερά δαμάσκηνα",
        "Ξερά σύκα",
        "Ξερά φρούτα",
        "Ξηροί καρποί",
        "Ουίσκι",
        "Ούζο",
        "Πάπια",
        "Πέρκα",
        "Πέστροφα",
        "Παλαμίδα",
        "Παντζάρια",
        "Πατάτες",
        "Πεπόνι",
        "Περγαμόντο",
        "Πεσκανδρίτσα",
        "Πιπεριές",
        "Πλιγούρι",
        "Πορτοκάλι",
        "Πουλερικά",
        "Πράσα",
        "Προβατίνα",
        "Ρέγκα",
        "Ραδίκια",
        "Ρεβύθια",
        "Ροδάκινο",
        "Ροφός",
        "Ρούμι",
        "Ρόδι",
        "Ρύζι",
        "Σέσκουλα",
        "Σαγκουίνι",
        "Σαλάχι",
        "Σαρδέλα",
        "Σαφρίδια",
        "Σελινόριζα",
        "Σιμιγδάλι",
        "Σιρόπι αγαύης",
        "Σκορπίνα",
        "Σκόρδο",
        "Σμέουρα",
        "Σοκολάτα",
        "Σολομός",
        "Σουπιές",
        "Σουσάμι",
        "Σπανάκι",
        "Σπαράγγια",
        "Σταφίδες",
        "Σταφύλι",
        "Συκώτι",
        "Συναγρίδα",
        "Σύκο",
        "Ταραμάς",
        "Ταχίνι",
        "Τεκίλα",
        "Τζιν",
        "Τραχανάς",
        "Τροπικά φρούτα",
        "Τσίπουρο + Ρακή",
        "Τσιπούρα",
        "Τυρί",
        "Τόνος",
        "Φάβα",
        "Φέτα",
        "Φακές",
        "Φασιανός",
        "Φασολάκια",
        "Φασόλια",
        "Φινόκιο",
        "Φουντούκια",
        "Φράουλα",
        "Φρούτα",
        "Φρούτο του πάθους",
        "Φυστίκια",
        "Φυτικά γάλατα",
        "Χάνος",
        "Χοιρινό",
        "Χοιρινός κιμάς",
        "Χουρμάδες",
        "Χριστόψαρο",
        "Χταπόδι",
        "Χυλοπίτες",
        "Χόρτα / Μυρωδικά",
        "Ψάρι",
        "Όσπρια",
    )),
}
_AUDITED_KEYWORD_TAG_ONLY_KEYS = _exact_label_keys((
    "Leftovers",
    "Sponsored",
    "Άγιο Όρος",
    "Αγία Αικατερίνη",
    "Βασιλόπιτα κέικ",
    "Γλυκό Κουταλιού",
    "Καν' το μόνος σου",
    "Κεράσματα",
    "Κουραμπιέδες",
    "Μελομακάρονα",
    "Μοναχός Επιφάνιος",
    "Ρώμη",
    "Τούρτες",
    "τσιξ",
    "Χαλβάς",
    "Όρος Σινά",
))
_AUDITED_FACET_NAVIGATION_LABEL_KEYS = _exact_label_keys((
    "Άρθρα και Συνταγές για Κοκτέιλ",
    "Άρθρα και Συνταγές για Κυρίως Γεύμα",
    "Άρθρα και Συνταγές με Αλεύρι (ζύμες)",
    "Άρθρα και Συνταγές με Ζυμαρικά",
))


def _taxonomy(metadata: Mapping[str, Any], recipe: Mapping[str, Any]) -> tuple[list[str], dict[str, list[str]], list[str]]:
    labels: dict[str, list[str]] = {facet: [] for facet in FACET_KEYS}
    tags: list[str] = []
    ingredient_values: list[str] = []
    authoritative_values: list[str] = []
    for raw in metadata.get("facetLinks", []):
        if not isinstance(raw, Mapping):
            continue
        label = plain_text(raw.get("label"))
        family = str(raw.get("family") or "").casefold()
        slug = str(raw.get("slug") or "").casefold()
        if not label:
            continue
        # The two audited legacy pages expose four navigation links beside
        # their actual facet links.  Their slugs look authoritative, but the
        # link labels describe archive navigation rather than this recipe.
        if _normalized_label(label) in _AUDITED_FACET_NAVIGATION_LABEL_KEYS:
            continue
        tags.append(label)
        facet = _FACET_FAMILIES.get(family)
        if facet:
            labels[facet].append(label)
        elif family == "eidos-syntagon" and slug in _CUISINE_SLUGS:
            labels["cuisine"].append(label)
        elif family == "eidos-syntagon" and slug in _METHOD_SLUGS:
            labels["method"].append(label)
        if family == "vasiko-yliko":
            ingredient_values.extend((label, slug))
        elif family in {"eidos-geumatos", "eidos-syntagon"}:
            authoritative_values.extend((label, slug))

    schema_categories = _strings(recipe.get("recipeCategory"))
    # The publisher keywords field is its own exact tag list. It is valid
    # taxonomy evidence, unlike recipe titles, descriptions, or ingredient
    # prose, which can mention a food without defining the recipe category.
    keyword_values = _split_keywords(recipe.get("keywords"))
    authoritative_values.extend(schema_categories)
    tags.extend(schema_categories)
    tags.extend(keyword_values)
    for value in schema_categories:
        if _normalized_label(value) in _SCHEMA_MEAL_TYPE_KEY_ALLOWLIST:
            labels["meal_type"].append(value)
    for value in keyword_values:
        key = _normalized_label(value)
        for facet, allowlist in _KEYWORD_FACET_KEY_ALLOWLISTS.items():
            if key in allowlist:
                labels[facet].append(value)
    for value in _strings(recipe.get("recipeCuisine")):
        labels["cuisine"].append(value)
        tags.append(value)
    for raw in _strings(recipe.get("suitableForDiet")):
        key = urlsplit(raw).path.rstrip("/").rsplit("/", 1)[-1].casefold()
        labels["diet"].append(_SCHEMA_DIETS.get(key, plain_text(raw)))

    authoritative_normalized = {
        _normalized_label(value) for value in authoritative_values
    }
    keyword_normalized = {
        _normalized_label(value) for value in keyword_values
    }
    dessert_values = {_normalized_label(value) for value in _CATEGORY_LABEL_MAP["dessert"]}
    other_values = {_normalized_label(value) for value in _TERMINAL_OTHER}
    street_values = {_normalized_label(value) for value in _STREET_FORMATS}
    explicit_keys = _category_matches(authoritative_values)
    ingredient_keys = _category_matches(ingredient_values)
    keyword_keys = _category_matches(keyword_values)
    authoritative_terminal = bool(
        authoritative_normalized
        & (dessert_values | other_values | street_values)
    )

    # Structured recipe categories and page facets always outrank keywords.
    # This prevents a secondary keyword such as "Γλυκά" from turning an
    # authoritative όσπρια recipe into dessert. Publisher keywords are used
    # only when neither structured recipe nor ingredient-facet evidence can
    # classify the record.
    if authoritative_normalized & dessert_values:
        primary = "dessert"
    elif authoritative_normalized & other_values:
        primary = "other"
    elif authoritative_normalized & street_values:
        primary = "street_food"
    elif explicit_keys:
        primary = explicit_keys[0]
    elif ingredient_keys:
        primary = ingredient_keys[0]
    elif keyword_normalized & dessert_values:
        primary = "dessert"
    elif keyword_normalized & other_values:
        primary = "other"
    elif keyword_normalized & street_values:
        primary = "street_food"
    elif keyword_keys:
        primary = keyword_keys[0]
    else:
        primary = "other"
    keys = [primary]
    secondary_groups = (
        (explicit_keys, ingredient_keys)
        if authoritative_terminal or explicit_keys or ingredient_keys
        else (keyword_keys,)
    )
    for group in secondary_groups:
        for key in group:
            if key != primary and key not in keys:
                keys.append(key)
    return keys, {key: _dedupe_labels(value) for key, value in labels.items()}, _dedupe_labels(tags)


def derive_gastronomos_taxonomy(source_payload: Mapping[str, Any]) -> dict[str, Any]:
    recipe = source_payload.get("jsonLd")
    metadata = source_payload.get("htmlMetadata")
    if not isinstance(recipe, Mapping) or not isinstance(metadata, Mapping):
        raise FullSchemaError(
            "Gastronomos sourcePayload must preserve jsonLd and htmlMetadata taxonomy evidence"
        )
    category_keys, _labels, _tags = _taxonomy(metadata, recipe)
    category = canonical_category(category_keys)
    return {
        "categoryKeys": category_keys,
        "category": category,
        "categoryLabel": CATEGORY_LABELS.get(category, "Άλλο"),
    }


def _is_greek_language(value: str) -> bool:
    normalized = value.strip().casefold().replace("_", "-")
    return normalized in {"el", "el-gr", "gr", "greek"}


def _has_substantive_greek(values: Sequence[str]) -> bool:
    text = " ".join(values)
    greek = len(re.findall(r"[\u0370-\u03ff\u1f00-\u1fff]", text))
    latin = len(re.findall(r"[A-Za-z]", text))
    return greek >= 3 and greek >= latin


def _has_greek_recipe_evidence(
    *,
    title: str,
    description: str,
    ingredients: Sequence[str],
    steps: Sequence[str],
) -> bool:
    # English brand names can legitimately dominate an ingredient list. Keep
    # the aggregate guard, but also accept independently Greek title + method
    # evidence. An English recipe with a misleading site-level ``lang=el``
    # still fails because both recipe-scoped signals must be Greek.
    return _has_substantive_greek([
        title,
        description,
        *ingredients,
        *steps,
    ]) or (
        _has_substantive_greek([title])
        and any(_has_substantive_greek([step]) for step in steps)
    )


def _time_values(metadata: Mapping[str, Any]) -> dict[str, int]:
    values = {"prep": 0, "cook": 0, "bake": 0, "fry": 0, "wait": 0, "total": 0}
    label_map = {
        "προετοιμασια": "prep", "prep": "prep", "preparation": "prep",
        "μαγειρεμα": "cook", "cook": "cook", "cooking": "cook",
        "ψησιμο": "bake", "bake": "bake", "baking": "bake",
        "τηγανισμα": "fry", "fry": "fry", "frying": "fry",
        "αναμονη": "wait", "wait": "wait", "resting": "wait",
        "συνολικος χρονος": "total", "συνολο": "total", "total time": "total",
    }
    for raw in metadata.get("timeLabels", []):
        if not isinstance(raw, Mapping):
            continue
        key = label_map.get(_normalized_label(raw.get("label")))
        if key:
            values[key] = max(values[key], parse_duration_minutes(raw.get("value")))
    return values


def _rating(recipe: Mapping[str, Any]) -> tuple[float, int]:
    aggregate = recipe.get("aggregateRating")
    if not isinstance(aggregate, Mapping):
        return 0.0, 0
    try:
        value = float(str(aggregate.get("ratingValue") or "").replace(",", "."))
        best = float(str(aggregate.get("bestRating") or 5).replace(",", "."))
        count = int(float(str(aggregate.get("ratingCount") or aggregate.get("reviewCount") or 0)))
    except (TypeError, ValueError, OverflowError):
        return 0.0, 0
    if not all(math.isfinite(item) for item in (value, best)) or best <= 0 or value < 0 or value > best:
        return 0.0, 0
    return round(value / best * 10, 2), max(0, count)


def _nutrition(value: object) -> tuple[str, list[dict[str, str]]]:
    nutrition = value if isinstance(value, Mapping) else {}
    section: dict[str, str] = {"title": ""}
    for prefix in _NUTRITION_FIELDS.values():
        section.update({
            f"{prefix}Portion": "",
            f"{prefix}PortionPercent": "",
            f"{prefix}100g": "",
            f"{prefix}100gPercent": "",
        })
    has_value = False
    for field_name, prefix in _NUTRITION_FIELDS.items():
        raw = nutrition.get(field_name)
        value_text = plain_text(raw) if isinstance(raw, (str, int, float)) and not isinstance(raw, bool) else ""
        section[f"{prefix}Portion"] = value_text
        has_value = has_value or bool(value_text)
    if not has_value:
        return "", []
    serving = nutrition.get("servingSize")
    nutrition_per = plain_text(serving) if isinstance(serving, (str, int, float)) and not isinstance(serving, bool) else ""
    return nutrition_per or "τη δηλωμένη μερίδα", [section]


def _epoch_millis(value: object) -> int:
    text = str(value or "").strip()
    if not text:
        return 0
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        return 0
    return max(0, int(parsed.timestamp() * 1000)) if parsed.tzinfo else 0


def _youtube_identity(value: str) -> str:
    parts = urlsplit(value)
    host = (parts.hostname or "").casefold().removeprefix("www.")
    video_id = ""
    if host == "youtu.be":
        video_id = parts.path.strip("/").split("/", 1)[0]
    elif host in {"youtube.com", "m.youtube.com", "youtube-nocookie.com"}:
        segments = [part for part in parts.path.split("/") if part]
        if segments and segments[0] in {"embed", "shorts", "live"} and len(segments) > 1:
            video_id = segments[1]
        elif parts.path.rstrip("/") == "/watch":
            video_id = parse_qs(parts.query).get("v", [""])[0]
    return f"youtube:{video_id}" if video_id else value


def _dedupe_media(values: Iterable[str], *, videos: bool = False) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for raw in values:
        value = _safe_https_url(raw)
        identity = _youtube_identity(value) if videos else value
        if value and identity not in seen:
            seen.add(identity)
            result.append(value)
    return result


def normalize_gastronomos_payload(
    recipe: Mapping[str, Any],
    metadata: Mapping[str, Any],
    *,
    source_url: str,
    sitemap_last_modified: str = "",
    active: bool = True,
) -> dict[str, Any]:
    match = GASTRONOMOS.recipe_path.fullmatch(urlsplit(source_url).path)
    if not match:
        raise FullSchemaError("Gastronomos source URL is not a strict recipe URL")
    provider_id = match.group("id")
    canonical_url = canonical_recipe_url(GASTRONOMOS, source_url, provider_id)
    if not isinstance(recipe, Mapping) or not _is_recipe(recipe):
        raise FullSchemaError("Gastronomos sourcePayload jsonLd is not a Recipe")
    if not isinstance(metadata, Mapping):
        raise FullSchemaError("Gastronomos sourcePayload htmlMetadata is not an object")
    if recipe.get("url") and _canonical_candidate(recipe.get("url"), provider_id) != canonical_url:
        raise FullSchemaError("Gastronomos JSON-LD Recipe URL does not match the source URL")
    declared_canonical = plain_text(metadata.get("canonicalUrl"))
    if declared_canonical:
        try:
            if canonical_recipe_url(GASTRONOMOS, declared_canonical, provider_id) != canonical_url:
                raise FullSchemaError("Gastronomos canonical link does not match the sitemap recipe URL")
        except ValueError as exc:
            raise FullSchemaError("Gastronomos canonical link is not a strict matching recipe URL") from exc

    title = plain_text(recipe.get("name") or recipe.get("headline"))
    if not title:
        raise FullSchemaError("Gastronomos JSON-LD Recipe has no name")
    jsonld_ingredients = _strings(recipe.get("recipeIngredient"))
    legacy_ingredient_sections = metadata.get("legacyIngredientSections")
    if not isinstance(legacy_ingredient_sections, list):
        legacy_ingredient_sections = []
    ingredients = jsonld_ingredients or _ingredient_texts(legacy_ingredient_sections)
    method_sections, instruction_tips = _instruction_sections(recipe.get("recipeInstructions"))
    all_steps = [step for section in method_sections for step in section.get("steps", [])]
    if not all_steps:
        raw_legacy_methods = metadata.get("legacyMethodSections")
        if isinstance(raw_legacy_methods, list):
            method_sections = [
                {
                    "title": plain_text(section.get("title")),
                    "steps": [
                        text for step in section.get("steps", [])
                        if (text := plain_text(step))
                    ],
                }
                for section in raw_legacy_methods
                if isinstance(section, Mapping)
                and isinstance(section.get("steps"), list)
            ]
            method_sections = [section for section in method_sections if section["steps"]]
            all_steps = [
                step for section in method_sections for step in section["steps"]
            ]
    language = plain_text(metadata.get("documentLanguage"))
    if language and not _is_greek_language(language):
        raise GastronomosLanguageError(provider_id, f"HTML lang={language!r}")
    structured_languages = _strings(recipe.get("inLanguage"))
    if structured_languages and any(not _is_greek_language(value) for value in structured_languages):
        raise GastronomosLanguageError(provider_id, "JSON-LD inLanguage is not Greek")
    if not _has_greek_recipe_evidence(
        title=title,
        description=plain_text(recipe.get("description")),
        ingredients=ingredients,
        steps=all_steps,
    ):
        raise GastronomosLanguageError(provider_id, "recipe content is not substantively Greek")
    if not ingredients:
        raise FullSchemaError("Gastronomos JSON-LD Recipe has no ingredients")
    if not all_steps:
        raise FullSchemaError("Gastronomos JSON-LD Recipe has no instructions")

    jsonld_sections = _jsonld_ingredient_sections(recipe)
    if jsonld_ingredients:
        rendered_sections = metadata.get("ingredientSections")
        if not rendered_sections and legacy_ingredient_sections:
            rendered_sections = legacy_ingredient_sections
        ingredient_sections = _aligned_html_ingredients(rendered_sections, jsonld_sections)
    else:
        ingredient_sections = legacy_ingredient_sections
    category_keys, facet_labels, tags = _taxonomy(metadata, recipe)
    category = canonical_category(category_keys)
    times = _time_values(metadata)
    prep = parse_duration_minutes(recipe.get("prepTime")) or times["prep"]
    schema_cook = parse_duration_minutes(recipe.get("cookTime"))
    html_cook = times["cook"] + times["bake"] + times["fry"]
    cook = schema_cook or html_cook
    wait = times["wait"]
    component_total = prep + cook + wait
    total = parse_duration_minutes(recipe.get("totalTime")) or times["total"] or component_total
    total = max(total, component_total)
    rating, rating_count = _rating(recipe)
    nutrition_per, nutrition_sections = _nutrition(recipe.get("nutrition"))
    images = _dedupe_media([
        *_https_urls(recipe.get("image")),
        *metadata.get("scopedImageUrls", []),
    ])
    videos = _dedupe_media([
        *_https_urls(recipe.get("video")),
        *metadata.get("scopedVideoUrls", []),
    ], videos=True)
    associations = {
        facet: sorted(
            ({"id": _machine_key(value), "title": value} for value in values),
            key=lambda item: (item["title"].casefold(), item["id"]),
        )
        for facet, values in facet_labels.items()
    }
    source_payload = sanitize_source_payload({"jsonLd": recipe, "htmlMetadata": metadata})
    author = " · ".join(_strings(recipe.get("author")))
    published_at = str(recipe.get("datePublished") or "")
    updated_at = str(recipe.get("dateModified") or "")
    description = plain_text(recipe.get("description"))
    meta = metadata.get("meta") if isinstance(metadata.get("meta"), Mapping) else {}
    seo_title = plain_text(meta.get("og:title") or meta.get("twitter:title") or title)
    seo_description = plain_text(meta.get("description") or meta.get("og:description") or description)
    path_parts = [part for part in urlsplit(canonical_url).path.split("/") if part]

    record: dict[str, Any] = {
        "id": recipe_document_id(GASTRONOMOS, provider_id),
        "detailSchemaVersion": GASTRONOMOS_DETAIL_SCHEMA_VERSION,
        "sourceRecipeId": int(provider_id),
        "sourceKey": GASTRONOMOS.key,
        "providerRecipeId": provider_id,
        "slug": path_parts[-2],
        "title": title,
        "description": description,
        "seoTitle": seo_title,
        "seoDescription": seo_description,
        "categoryKeys": category_keys,
        "category": category,
        "categoryLabel": CATEGORY_LABELS.get(category, "Άλλο"),
        "categorySourceId": 0,
        "rating10": rating,
        "rating": rating,
        "ratingCount": rating_count,
        "rating1": 0,
        "rating2": 0,
        "rating3": 0,
        "rating4": 0,
        "rating5": 0,
        "prepMinutes": prep,
        "cookMinutes": cook,
        "waitMinutes": wait,
        "totalMinutes": total,
        "sourceDifficulty": plain_text(metadata.get("difficulty")),
        "ease": classify_ease(len(method_sections), len(all_steps), total),
        "randomKey": stable_recipe_random_key(GASTRONOMOS, provider_id),
        "stepCount": len(all_steps),
        "preparationCount": len([section for section in method_sections if section.get("steps")]),
        "servings": " · ".join(_strings(recipe.get("recipeYield"))),
        "language": "el",
        "imageUrl": images[0] if images else "",
        "imageUrls": images,
        "source": GASTRONOMOS.source,
        "sourceUrl": canonical_url,
        "canonicalUrl": canonical_url,
        "shortUrl": "",
        "sourceName": GASTRONOMOS.display_name,
        "tags": tags,
        "dietLabels": facet_labels["diet"],
        "mealTypeLabels": facet_labels["meal_type"],
        "occasionLabels": facet_labels["occasion"],
        "methodLabels": facet_labels["method"],
        "cuisineLabels": facet_labels["cuisine"],
        "ingredientLabels": facet_labels["ingredient"],
        "quickRecipe": total > 0 and total <= 30,
        "videoUrls": videos,
        "ingredientSections": ingredient_sections,
        "methodSections": method_sections,
        "tips": _dedupe_labels([*instruction_tips, *metadata.get("tips", [])]),
        "nutritionTips": [],
        "nutritionPer": nutrition_per,
        "nutritionSections": nutrition_sections,
        "equipment": _dedupe_labels(metadata.get("equipment", [])),
        "authorName": author,
        "published": True,
        "publishedAt": published_at,
        "shares": 0,
        "sponsorLogoUrl": "",
        "createdAt": published_at,
        "sourceUpdatedAt": updated_at,
        "updatedAtEpochMillis": _epoch_millis(updated_at),
        "active": bool(active),
        "sourcePayload": source_payload,
        "filterAssociations": associations,
        "sitemapLastModified": str(sitemap_last_modified or ""),
    }
    ensure_full_record(record)
    return record


def normalize_gastronomos_page(
    html_text: str,
    *,
    source_url: str,
    sitemap_last_modified: str = "",
    active: bool = True,
) -> dict[str, Any]:
    match = GASTRONOMOS.recipe_path.fullmatch(urlsplit(source_url).path)
    if not match:
        raise FullSchemaError("Gastronomos source URL is not a strict recipe URL")
    provider_id = match.group("id")
    canonical_url = canonical_recipe_url(GASTRONOMOS, source_url, provider_id)
    recipe, metadata = parse_gastronomos_page(html_text, source_url=canonical_url)
    return normalize_gastronomos_payload(
        recipe,
        metadata,
        source_url=canonical_url,
        sitemap_last_modified=sitemap_last_modified,
        active=active,
    )
