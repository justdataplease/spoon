"""Normalize an authorized Argiro Greek recipe page into Spoon's full schema.

Only structured recipe data and narrowly scoped recipe-page supplements are
retained; the full HTML document is intentionally not copied into Firestore.
Tests use synthetic markup.  Running this against the publisher requires the
separate permission gate in ``crawl_argiro.py``.
"""

from __future__ import annotations

import hashlib
import json
import math
import re
import unicodedata
from collections.abc import Mapping, Sequence
from datetime import datetime
from html.parser import HTMLParser
from typing import Any
from urllib.parse import parse_qs, urljoin, urlsplit, urlunsplit

try:
    from .full_schema import (
        ARGIRO_DETAIL_SCHEMA_VERSION,
        CATEGORY_LABELS,
        FACET_KEYS,
        FullSchemaError,
        ensure_full_record,
        plain_text,
        sanitize_source_payload,
    )
    from .helpers import canonical_category, classify_ease
    from .providers import (
        ARGIRO,
        canonical_recipe_url,
        recipe_document_id,
        stable_recipe_random_key,
    )
except ImportError:  # pragma: no cover - direct script execution
    from full_schema import (
        ARGIRO_DETAIL_SCHEMA_VERSION,
        CATEGORY_LABELS,
        FACET_KEYS,
        FullSchemaError,
        ensure_full_record,
        plain_text,
        sanitize_source_payload,
    )
    from helpers import canonical_category, classify_ease
    from providers import (
        ARGIRO,
        canonical_recipe_url,
        recipe_document_id,
        stable_recipe_random_key,
    )


_DURATION_RE = re.compile(
    r"^P(?:(?P<days>\d+)D)?(?:T(?:(?P<hours>\d+)H)?(?:(?P<minutes>\d+)M)?(?:(?P<seconds>\d+)S)?)?$"
)
_WP_ID_RE = re.compile(r"(?:\bAM\.recipe\b|\brecipe\b).{0,1200}?\bid\s*[:=]\s*['\"]?(\d+)", re.S)
_RATING_RE = re.compile(r"\brating\s*:\s*['\"]?(\d+(?:\.\d+)?)", re.S)
_VOTES_RE = re.compile(r"\btotal_votes\s*:\s*['\"]?(\d+)", re.S)
_PERCENT_RE = re.compile(r"\brating_percentage\s*:\s*['\"]?(\d+(?:\.\d+)?)", re.S)
_AM_RECIPE_ID_RE = re.compile(
    r"\bAM\s*=\s*\{.{0,2000}?\brecipe\s*:\s*\{.{0,500}?\bid\s*:\s*(\d+)",
    re.S,
)
_TAXONOMY_FACETS = {
    "dietary-category": "diet",
    "meal-type": "meal_type",
    "occasion": "occasion",
    "cuisine": "cuisine",
    "basic-ingredient": "ingredient",
}
_CATEGORY_SLUGS = {
    "legumes": {"ospria"},
    "fish": {"psaria", "psari", "thalassina"},
    "meat": {
        "kreas", "moschari", "choirino", "xoirino", "arni", "katsiki",
        "kouneli",
    },
    "poultry": {"kotopoulo", "galopoula", "kokoras", "poulika"},
    "vegetables": {"lachanika", "laxanika", "ladera", "patata", "avokanto"},
    "pasta_rice": {
        "zymarika", "makaronia", "ryzi", "rizi", "risotto", "kritharaki",
        "kinoa", "pligouri",
    },
}
_CATEGORY_KEY_PRECEDENCE = (
    "fish", "legumes", "poultry", "meat", "pasta_rice", "vegetables",
)
_TERMINAL_DESSERT_CATEGORY_SLUGS = {
    "glyka", "glika", "gliko", "epidorpia", "keik", "tourtes",
}
_TERMINAL_DRINK_CATEGORY_SLUGS = {"rofimata-pota"}
_TERMINAL_OTHER_CATEGORY_SLUGS = {
    "ntip-saltses", "sinodeutika", "psomi-zymes",
}
_STREET_FORMAT_CATEGORY_SLUGS = {"santouits", "fingerfood", "finger-food"}
_CATEGORY_LABELS_EXACT = {
    "dessert": {"γλυκα", "γλυκο", "επιδορπια", "dessert"},
    "drinks": {"ροφηματα", "ροφημα", "ποτα", "ποτο", "κοκτειλ", "drinks", "cocktails"},
    "legumes": {"οσπρια", "legumes"},
    "fish": {"ψαρια", "ψαρι", "θαλασσινα", "fish"},
    "meat": {"κρεας", "μοσχαρι", "χοιρινο", "αρνι", "κατσικι", "meat"},
    "poultry": {"κοτοπουλο", "γαλοπουλα", "πουλερικα", "poultry"},
    "vegetables": {"λαχανικα", "λαδερα", "vegetables"},
    "pasta_rice": {"ζυμαρικα", "μακαρονια", "ρυζι", "risotto"},
}
_VOID_ELEMENTS = {
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link",
    "meta", "param", "source", "track", "wbr",
}
_TIP_TITLES = {"μυστικα", "συμβουλες", "tips"}
_DIET_SCHEMA_LABELS = {
    "vegandiet": "Vegan",
    "vegetariandiet": "Χορτοφαγική",
    "glutenfreediet": "Χωρίς γλουτένη",
    "lowcaloriediet": "Χαμηλών θερμίδων",
    "lowfatdiet": "Χαμηλών λιπαρών",
    "lowlactosediet": "Χωρίς λακτόζη",
    "lowsaltdiet": "Χαμηλή σε αλάτι",
    "diabeticdiet": "Κατάλληλη για διαβητικούς",
}
_NUTRITION_PORTION_FIELDS = {
    "calories": "kcal",
    "fatContent": "fat",
    "saturatedFatContent": "saturatedFat",
    "carbohydrateContent": "carbs",
    "sugarContent": "sugars",
    "proteinContent": "protein",
    "fiberContent": "fiber",
    "sodiumContent": "sodium",
}
_ADROTATE_METHOD_NODE_RE = re.compile(
    r"^\[adrotate\s+banner\s*=\s*[\'\u201c\u201d]?\d+[\'\u201c\u201d\u2032\u2033]?\]$",
    re.I,
)
_RECIPE_GRID_METHOD_NODE_RE = re.compile(
    r'^\[recipe_grid\s+recipe_ids\s*=\s*[\x22\x27\u201c\u201d]?'
    r'\d+(?:\s*,\s*\d+)*[\x22\x27\u201c\u201d\u2032\u2033]?\]$',
    re.I,
)
_INTERNAL_RECIPE_METHOD_URL_RE = re.compile(
    r"^https?://(?:www\.)?argiro\.gr/recipe/[^/?#\s]+/?$",
    re.I,
)
# Exact JSON-LD-only editorial copy, independently audited against the live
# DOM. Binding every string to its provider ID prevents broad prose filtering.
# The original values remain unchanged in ``sourcePayload.jsonLd``.
_AUDITED_JSONLD_EDITORIAL_INSTRUCTION_ARTIFACTS = {
    "16315": {
        "Θέλετε περισσότερες νόστιμες συνταγές με φακές; Βρείτε τες όλες εδώ!",
    },
    "16601": {
        "Δείτε εδώ και φτιάξτε τα ωραιότερα παγωτά για τους αγαπημένους σας.",
    },
    "16620": {
        "Αγαπάτε τις φράουλες; Δείτε περισσότερες εύκολες και φαντασικές "
        "φραουλένιες συνταγές εδώ!",
    },
    "16759": {
        "Θέλετε περισσότερες συνταγές για μοναδικά νηστίσιμα γλυκά; "
        "Βρείτε τες όλες εδώ!",
    },
    "20708": {
        "Τα Νούντλς (Noodles) της Kόμπρας του Άνταμ Κοντοβά, "
        "εύκολα & πεντανόστιμα!",
    },
}


_AUDITED_JSONLD_EDITORIAL_INSTRUCTION_ARTIFACTS.update({
    '15401': {
        'Σπιτικό fast food που θα σας συναρπάσει και θα σας γλιτώσει από περιττά έξοδα!',
    },
    '20035': {
        'Διαβάστε και όλα τα μυστικά μου, για να λιώσετε σωστά τη σοκολάτα.',
    },
})

_AUDITED_JSONLD_INSTRUCTION_REWRITES = {
    '20035': {
        (
            'Μπορείτε να φτιάξετε τη συνταγή με μαρόν γλασέ.\n'
            'Διαβάστε και όλα τα μυστικά μου, για να λιώσετε σωστά τη σοκολάτα.'
        ): 'Μπορείτε να φτιάξετε τη συνταγή με μαρόν γλασέ.',
    },
}


class ArgiroLanguageError(FullSchemaError):
    """Raised when a strict recipe page is explicitly or substantively non-Greek."""

    def __init__(self, provider_recipe_id: str, reason: str) -> None:
        self.provider_recipe_id = provider_recipe_id
        self.reason = reason
        super().__init__(f"Argiro recipe is not Greek: {reason}")


def _strings(value: object) -> list[str]:
    if value is None:
        return []
    values = value if isinstance(value, (list, tuple)) else [value]
    result: list[str] = []
    for item in values:
        if isinstance(item, Mapping):
            item = item.get("name") or item.get("url") or item.get("@id")
        text = plain_text(item)
        if text and text not in result:
            result.append(text)
    return result


def _https_urls(value: object) -> list[str]:
    candidates: list[str] = []

    def collect(item: object) -> None:
        if isinstance(item, str):
            candidates.append(item)
        elif isinstance(item, Mapping):
            for key in ("url", "contentUrl", "embedUrl", "@id"):
                if key in item:
                    collect(item[key])
        elif isinstance(item, Sequence) and not isinstance(item, (bytes, bytearray)):
            for child in item:
                collect(child)

    collect(value)
    result = []
    for candidate in candidates:
        parts = urlsplit(candidate)
        if parts.scheme.casefold() == "https" and parts.hostname and not parts.username and not parts.password:
            clean = parts._replace(fragment="").geturl()
            if clean not in result:
                result.append(clean)
    return result


def _youtube_identity(value: str) -> str:
    parts = urlsplit(value)
    host = (parts.hostname or "").casefold().removeprefix("www.")
    video_id = ""
    if host == "youtu.be":
        video_id = parts.path.strip("/").split("/", 1)[0]
    elif host in {"youtube.com", "m.youtube.com", "youtube-nocookie.com"}:
        segments = [segment for segment in parts.path.split("/") if segment]
        if segments and segments[0] in {"embed", "shorts", "live"} and len(segments) > 1:
            video_id = segments[1]
        elif parts.path.rstrip("/") == "/watch":
            video_id = parse_qs(parts.query).get("v", [""])[0]
    return f"youtube:{video_id}" if video_id else value


def _dedupe_video_urls(values: Sequence[str]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        identity = _youtube_identity(value)
        if value and identity not in seen:
            seen.add(identity)
            result.append(value)
    return result


def _parse_am_object(script_text: str) -> Mapping[str, Any]:
    """Extract a strict quoted JSON object assigned to AM, without eval."""
    for match in re.finditer(r"\b(?:var\s+)?AM\s*=\s*", script_text):
        start = match.end()
        if start >= len(script_text) or script_text[start] != "{":
            continue
        depth = 0
        quote = ""
        escaped = False
        for index in range(start, len(script_text)):
            character = script_text[index]
            if quote:
                if escaped:
                    escaped = False
                elif ord(character) == 92:
                    escaped = True
                elif character == quote:
                    quote = ""
                continue
            if character == chr(34) or character == chr(39):
                quote = character
            elif character == "{":
                depth += 1
            elif character == "}":
                depth -= 1
                if depth == 0:
                    try:
                        value = json.loads(script_text[start:index + 1])
                    except json.JSONDecodeError:
                        break
                    if isinstance(value, Mapping):
                        return value
                    break
    return {}


def _number(value: object) -> float | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        result = float(value)
    except (TypeError, ValueError):
        return None
    return result if math.isfinite(result) else None


def parse_iso8601_minutes(value: object) -> int:
    match = _DURATION_RE.fullmatch(str(value or "").strip().upper())
    if not match:
        return 0
    minutes = int(match.group("days") or 0) * 1440
    minutes += int(match.group("hours") or 0) * 60
    minutes += int(match.group("minutes") or 0)
    minutes += math.ceil(int(match.group("seconds") or 0) / 60)
    return minutes


def _epoch_millis(value: object) -> int:
    try:
        parsed = datetime.fromisoformat(str(value or "").replace("Z", "+00:00"))
        return max(0, int(parsed.timestamp() * 1000)) if parsed.tzinfo else 0
    except ValueError:
        return 0


def _jsonld_nodes(value: object):
    if isinstance(value, list):
        for item in value:
            yield from _jsonld_nodes(item)
    elif isinstance(value, Mapping):
        graph = value.get("@graph")
        if isinstance(graph, list):
            yield from _jsonld_nodes(graph)
        yield value


def _is_recipe(node: Mapping[str, Any]) -> bool:
    kinds = node.get("@type")
    kinds = kinds if isinstance(kinds, list) else [kinds]
    return any(str(kind).casefold() == "recipe" for kind in kinds)


class _PageParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.jsonld_scripts: list[str] = []
        self.inline_scripts: list[str] = []
        self.shortlinks: list[str] = []
        self.document_language = ""
        self.tag_links: list[tuple[str, str]] = []
        self.difficulty_parts: list[str] = []
        self._stack: list[tuple[str, frozenset[str]]] = []
        self._script_kind = ""
        self._script_parts: list[str] = []
        self._tag_href = ""
        self._tag_parts: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        lowered = tag.casefold()
        values = {key.casefold(): value or "" for key, value in attrs}
        classes = frozenset(values.get("class", "").split())
        if lowered == "html" and not self.document_language:
            self.document_language = plain_text(values.get("lang", ""))
        in_tag_item = "tag_item" in classes or any(
            "tag_item" in item[1] for item in self._stack
        )
        if lowered not in _VOID_ELEMENTS:
            self._stack.append((lowered, classes))
        if lowered == "script":
            self._script_kind = values.get("type", "").casefold()
            self._script_parts = []
        if lowered == "link" and "shortlink" in values.get("rel", "").casefold().split():
            self.shortlinks.append(values.get("href", ""))
        if lowered == "a" and in_tag_item:
            self._tag_href = values.get("href", "")
            self._tag_parts = []

    def handle_endtag(self, tag: str) -> None:
        lowered = tag.casefold()
        if lowered == "script" and self._script_parts:
            text = "".join(self._script_parts)
            if self._script_kind == "application/ld+json":
                self.jsonld_scripts.append(text)
            else:
                self.inline_scripts.append(text)
            self._script_kind, self._script_parts = "", []
        if lowered == "a" and self._tag_href:
            label = plain_text("".join(self._tag_parts))
            if label:
                self.tag_links.append((self._tag_href, label))
            self._tag_href, self._tag_parts = "", []
        for index in range(len(self._stack) - 1, -1, -1):
            if self._stack[index][0] == lowered:
                del self._stack[index:]
                break

    def handle_data(self, data: str) -> None:
        if self._stack and self._stack[-1][0] == "script":
            self._script_parts.append(data)
        if self._tag_href:
            self._tag_parts.append(data)
        if any("difficulty_level" in item[1] for item in self._stack):
            self.difficulty_parts.append(data)


class _HtmlNode:
    __slots__ = ("tag", "attrs", "classes", "children")

    def __init__(
        self,
        tag: str,
        attrs: Mapping[str, str] | None = None,
    ) -> None:
        self.tag = tag
        self.attrs = dict(attrs or {})
        self.classes = frozenset(self.attrs.get("class", "").split())
        self.children: list[_HtmlNode | str] = []


class _RecipeDomParser(HTMLParser):
    """Small tolerant DOM used only for explicitly recipe-scoped HTML fields."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.root = _HtmlNode("document")
        self.stack = [self.root]

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        lowered = tag.casefold()
        node = _HtmlNode(
            lowered,
            {key.casefold(): value or "" for key, value in attrs},
        )
        self.stack[-1].children.append(node)
        if lowered not in _VOID_ELEMENTS:
            self.stack.append(node)

    def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        lowered = tag.casefold()
        self.stack[-1].children.append(
            _HtmlNode(
                lowered,
                {key.casefold(): value or "" for key, value in attrs},
            )
        )

    def handle_endtag(self, tag: str) -> None:
        lowered = tag.casefold()
        for index in range(len(self.stack) - 1, 0, -1):
            if self.stack[index].tag == lowered:
                del self.stack[index:]
                break

    def handle_data(self, data: str) -> None:
        if self.stack[-1].tag not in {"script", "style"}:
            self.stack[-1].children.append(data)


def _descendants(node: _HtmlNode):
    for child in node.children:
        if isinstance(child, _HtmlNode):
            yield child
            yield from _descendants(child)


def _node_text(node: _HtmlNode, *, leaf_scope: bool = False) -> str:
    parts: list[str] = []
    block_tags = {
        "address", "article", "aside", "blockquote", "div", "dl", "fieldset",
        "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header",
        "li", "main", "nav", "ol", "p", "section", "table", "ul",
    }

    def collect(value: _HtmlNode) -> None:
        for child in value.children:
            if isinstance(child, str):
                parts.append(child)
            elif child.tag not in {"script", "style"}:
                if leaf_scope and (
                    child.tag in {"h1", "h2", "h3", "h4", "h5", "h6"}
                    or any(
                        marker in class_name.casefold()
                        for class_name in child.classes
                        for marker in (
                            "read_also", "related", "advert", "banner", "share",
                        )
                    )
                ):
                    continue
                if child.tag in block_tags:
                    parts.append("\n")
                collect(child)
                if child.tag in block_tags:
                    parts.append("\n")

    collect(node)
    return plain_text("".join(parts))


def _with_class(node: _HtmlNode, class_name: str) -> list[_HtmlNode]:
    return [item for item in _descendants(node) if class_name in item.classes]


def _dedupe_text(values: Sequence[str]) -> list[str]:
    return list(dict.fromkeys(value for value in values if value))


def _element_children(node: _HtmlNode) -> list[_HtmlNode]:
    return [child for child in node.children if isinstance(child, _HtmlNode)]


def _including_self_with_class(node: _HtmlNode, class_name: str) -> list[_HtmlNode]:
    return ([node] if class_name in node.classes else []) + _with_class(node, class_name)


def _leaf_nodes(
    node: _HtmlNode,
    tags: frozenset[str],
    *,
    blocked: bool = False,
) -> list[_HtmlNode]:
    """Collect leaf semantic nodes while ignoring embedded non-recipe modules."""
    blocked_here = blocked or any(
        marker in class_name.casefold()
        for class_name in node.classes
        for marker in ("read_also", "related", "advert", "banner", "share")
    )
    if blocked_here:
        return []
    children = _element_children(node)
    nested_targets = any(
        descendant.tag in tags
        for child in children
        for descendant in ([child] + list(_descendants(child)))
    )
    if node.tag in tags and not nested_targets:
        return [node]
    result: list[_HtmlNode] = []
    for child in children:
        result.extend(_leaf_nodes(child, tags))
    return result


def _leaf_texts(
    node: _HtmlNode,
    tags: frozenset[str],
    *,
    blocked: bool = False,
) -> list[str]:
    """Collect leaf semantic text, ignoring unrelated embedded modules."""
    result: list[str] = []
    for leaf in _leaf_nodes(node, tags, blocked=blocked):
        # Malformed legacy paragraphs can contain recommendation headings and
        # read-also cards. Keep legitimate inline spans/links, but exclude those
        # nested modules before exact JSON-LD alignment.
        text = _node_text(leaf, leaf_scope=True)
        if text:
            result.append(text)
    return result


def _method_step_entries(
    node: _HtmlNode,
    tags: frozenset[str],
) -> list[tuple[str, str]]:
    """Return clean steps and exact structured-data anchors from scoped DOM."""
    entries: list[tuple[str, str]] = []
    for leaf in _leaf_nodes(node, tags):
        clean = _node_text(leaf, leaf_scope=True)
        if not clean or _is_non_recipe_instruction(clean):
            continue
        # JSON-LD on a legacy page serializes the whole ``li``, including a
        # nested related-recipe card. Preserve that full text only when the DOM
        # proves the exact publisher-owned read-also structure is present.
        has_read_also = any(
            "read_also__container" in descendant.classes
            for descendant in _descendants(leaf)
        )
        source = _node_text(leaf) if has_read_also else clean
        entries.append((clean, source))
    return entries


def _comparison_text(value: str) -> str:
    normalized = " ".join(
        "".join(
            character
            for character in unicodedata.normalize("NFD", plain_text(value).casefold())
            if unicodedata.category(character) != "Mn"
        ).split()
    )
    normalized = re.sub(r"\s+([.,;:!?%])", r"\1", normalized)
    normalized = re.sub(r"(?<=\d)\s*(?:ο|o|°|º)\s*(?=c\b)", "°", normalized)
    # One legacy Argiro method contains an escaped, truncated closing tag as
    # visible text (``&lt;/span``). Ignore only this terminal markup artifact
    # for comparison; the clean canonical JSON-LD text is written to output.
    normalized = re.sub(r"\s*</span>?$", "", normalized)
    return normalized


def _is_tip_title(value: str) -> bool:
    normalized = _comparison_text(value)
    return any(token in normalized for token in _TIP_TITLES)


def _is_non_recipe_instruction(
    value: str,
    *,
    provider_recipe_id: str = "",
) -> bool:
    text = plain_text(value)
    normalized = _comparison_text(text)
    return (
        not normalized
        or normalized.startswith("[visual-link-preview ")
        or bool(_ADROTATE_METHOD_NODE_RE.fullmatch(text))
        or bool(_RECIPE_GRID_METHOD_NODE_RE.fullmatch(text))
        or bool(_INTERNAL_RECIPE_METHOD_URL_RE.fullmatch(text))
        or text == "RELATED ARTICLE"
        or (
            text.startswith("ΜΑΓΕΙΡΕΨΕ ΚΑΙ\n")
            and text.count("\n") == 1
            and bool(text.partition("\n")[2].strip())
        )
        or text in _AUDITED_JSONLD_EDITORIAL_INSTRUCTION_ARTIFACTS.get(
            provider_recipe_id,
            set(),
        )
        or normalized in {"καλη επιτυχια!", "καλη επιτυχια"}
        or "social media" in normalized
    )


def _recipe_instruction_text(
    value: object,
    *,
    provider_recipe_id: str = '',
) -> str:
    text = plain_text(value)
    if _is_non_recipe_instruction(
        text,
        provider_recipe_id=provider_recipe_id,
    ):
        return ''
    return _AUDITED_JSONLD_INSTRUCTION_REWRITES.get(
        provider_recipe_id,
        {},
    ).get(text, text)


def _ingredient_link(item: _HtmlNode, *, internal: bool) -> str:
    for node in _descendants(item):
        if node.tag != "a" or not node.attrs.get("href"):
            continue
        href = node.attrs["href"].strip()
        parsed = urlsplit(href)
        if parsed.scheme.casefold() != "https" or not parsed.hostname:
            continue
        is_internal = parsed.hostname.casefold().removeprefix("www.") == "argiro.gr"
        if is_internal == internal:
            return href
    return ""


def _ingredient_item(item: _HtmlNode) -> dict[str, str] | None:
    label_node = next(iter(_including_self_with_class(item, "ingredient-label")), None)
    quantity_node = next(
        (
            node for node in _descendants(item)
            if any(
                class_name.casefold() in {"quantity", "ingredients__quantity"}
                or class_name.casefold().endswith("__quantity")
                for class_name in node.classes
            )
        ),
        None,
    )
    without_quantity = bool(
        label_node and "without_quantity" in label_node.classes
    )
    quantity = "" if without_quantity or quantity_node is None else _node_text(quantity_node)
    paragraph = next(
        (
            node for node in _descendants(label_node)
            if node.tag == "p" and _node_text(node)
        ),
        None,
    ) if label_node else None
    title = _node_text(paragraph) if paragraph else (
        _node_text(label_node) if label_node else _node_text(item)
    )
    if quantity and not paragraph:
        title = plain_text(title.replace(quantity, " ", 1))
    if not title:
        return None
    whole = _node_text(item)
    info = whole
    for consumed in (quantity, title):
        if consumed:
            info = info.replace(consumed, " ", 1)
    info = plain_text(info)
    return {
        "title": title,
        "unit": "",
        "quantity": quantity,
        "info": info,
        "internalLink": _ingredient_link(item, internal=True),
        "externalLink": _ingredient_link(item, internal=False),
        "ukUnit": "",
        "ukQuantity": "",
        "usUnit": "",
        "usQuantity": "",
    }


def _extract_ingredient_sections(root: _HtmlNode) -> list[dict[str, Any]]:
    left_columns = _with_class(root, "single_recipe__left_column")
    candidates = [
        node
        for left in left_columns
        for node in _descendants(left)
        if ({"ingredient", "ingredients"} & node.classes) and "tab" not in node.classes
    ]
    if not candidates:
        return []
    ingredients_root = max(
        candidates,
        key=lambda node: (
            sum("ingredients__item" in item.classes for item in _descendants(node)),
            int("ingredients" in node.classes),
        ),
    )
    sections: list[dict[str, Any]] = []
    title = ""
    ingredients: list[dict[str, str]] = []

    def flush() -> None:
        nonlocal ingredients
        if ingredients:
            sections.append({"title": title, "ingredients": ingredients})
        ingredients = []

    def visit(parent: _HtmlNode) -> None:
        nonlocal title
        for child in _element_children(parent):
            if "ingredients__title" in child.classes:
                next_title = _node_text(child)
                if next_title:
                    flush()
                    title = next_title
                continue
            if "ingredients__item" in child.classes or child.tag == "li":
                parsed = _ingredient_item(child)
                if parsed:
                    ingredients.append(parsed)
                continue
            visit(child)

    visit(ingredients_root)
    flush()
    return sections


def _extract_method_details(root: _HtmlNode) -> dict[str, Any]:
    sections: list[dict[str, Any]] = []
    sequence: list[str] = []
    tips: list[str] = []
    groups: list[dict[str, Any]] = []
    containers = _with_class(root, "single_recipe__method_container")
    if containers:
        for container in containers:
            title_node = next(
                iter(_with_class(container, "single_recipe__method_steps__title")),
                None,
            )
            title = _node_text(title_node) if title_node else ""
            step_roots = _with_class(container, "single_recipe__method_steps")
            step_root = step_roots[0] if step_roots else container
            is_tip = _is_tip_title(title)
            step_entries = _method_step_entries(step_root, frozenset({"li"}))
            # Some legacy recipes render every method step as a paragraph.
            # Use paragraphs only when the container has no list steps, which
            # avoids treating explanatory paragraphs as extra steps on modern
            # list-based pages while preserving the legacy section boundaries.
            if not step_entries:
                step_entries = _method_step_entries(step_root, frozenset({"p"}))
            steps = [clean for clean, _source in step_entries]
            source_steps = [source for _clean, source in step_entries]
            sequence.extend(steps)
            if steps:
                groups.append({
                    "title": title,
                    "steps": steps,
                    "sourceSteps": source_steps,
                    "isTip": is_tip,
                })
            if is_tip:
                tips.extend(steps)
            elif steps:
                sections.append({"title": title, "steps": steps})
    else:
        method_roots = _with_class(root, "single_recipe__method_steps")
        if method_roots:
            method_root = max(
                method_roots,
                key=lambda node: len(_leaf_texts(node, frozenset({"li"}))),
            )
            title_node = next(
                (
                    node for node in _leaf_nodes(
                        method_root,
                        frozenset({"h2", "h3", "h4", "h5"}),
                    )
                    if node.tag in {"h2", "h3", "h4", "h5"}
                    or "single_recipe__method_steps__title" in node.classes
                ),
                None,
            )
            title = _node_text(title_node) if title_node else ""
            step_entries = _method_step_entries(method_root, frozenset({"li"}))
            steps = [clean for clean, _source in step_entries]
            source_steps = [source for _clean, source in step_entries]
            sequence.extend(steps)
            if steps:
                groups.append({
                    "title": title,
                    "steps": steps,
                    "sourceSteps": source_steps,
                    "isTip": _is_tip_title(title),
                })
            if _is_tip_title(title):
                tips.extend(steps)
            elif steps:
                sections.append({"title": title, "steps": steps})
    return {
        "methodSections": sections,
        "methodSequence": sequence,
        "methodTips": tips,
        "methodGroups": groups,
    }


def _safe_argiro_upload_url(value: str) -> str:
    candidate = urljoin("https://www.argiro.gr/", value.strip())
    parsed = urlsplit(candidate)
    if (
        parsed.scheme.casefold() != "https"
        or (parsed.hostname or "").casefold().removeprefix("www.") != "argiro.gr"
        or parsed.username
        or parsed.password
        or not parsed.path.casefold().startswith("/wp-content/uploads/")
        or parsed.path.casefold().endswith(".svg")
    ):
        return ""
    return urlunsplit(("https", "www.argiro.gr", parsed.path, "", ""))


def _extract_scoped_images(root: _HtmlNode) -> list[str]:
    scope_names = {
        "single_recipe__method_container", "single_recipe__method_steps",
        "single_recipe__content", "single_recipe__description", "recipe_content",
    }
    scopes = [
        node for node in _descendants(root)
        if node.classes & scope_names
    ]
    images: list[str] = []

    def visit(node: _HtmlNode, blocked: bool = False) -> None:
        blocked_here = blocked or any(
            marker in class_name.casefold()
            for class_name in node.classes
            for marker in ("equipment", "video", "read_also", "related", "advert", "banner")
        )
        if not blocked_here and node.tag == "img":
            value = node.attrs.get("data-src") or node.attrs.get("src") or ""
            safe = _safe_argiro_upload_url(value)
            if safe and safe not in images:
                images.append(safe)
        for child in _element_children(node):
            visit(child, blocked_here)

    for scope in scopes:
        visit(scope)
    return images


def _extract_scoped_iframes(root: _HtmlNode) -> list[str]:
    """Keep only HTTPS video embeds inside the recipe's own video block."""
    scopes = _with_class(root, "single_recipe__video")
    result: list[str] = []
    allowed_hosts = {
        "youtube.com", "m.youtube.com", "youtube-nocookie.com", "youtu.be",
        "vimeo.com", "player.vimeo.com",
    }
    for scope in scopes:
        for node in _descendants(scope):
            if node.tag != "iframe":
                continue
            for value in _https_urls(node.attrs.get("src")):
                host = (urlsplit(value).hostname or "").casefold().removeprefix("www.")
                if host in allowed_hosts and value not in result:
                    result.append(value)
    return result


def _extract_expert_advice(root: _HtmlNode) -> list[dict[str, str]]:
    result: list[dict[str, str]] = []
    for advice in _with_class(root, "expert_advice"):
        name_node = next(iter(_with_class(advice, "expert_advice__name")), None)
        info_node = next(iter(_with_class(advice, "expert_advice__info")), None)
        content_node = next(iter(_with_class(advice, "expert_advice__content")), None)
        name = _node_text(name_node) if name_node else ""
        info = _node_text(info_node) if info_node else ""
        title = plain_text(info.replace(name, " ", 1)) if name else info
        content = _node_text(content_node) if content_node else ""
        image_url = ""
        for node in _descendants(advice):
            if node.tag == "img":
                image_url = _safe_argiro_upload_url(
                    node.attrs.get("data-src") or node.attrs.get("src") or ""
                )
                if image_url:
                    break
        if content:
            result.append({
                "name": name,
                "title": title,
                "content": content,
                "imageUrl": image_url,
            })
    return result


def _extract_scoped_details(html_text: str) -> dict[str, Any]:
    parser = _RecipeDomParser()
    parser.feed(html_text)
    parser.close()
    equipment = _dedupe_text(
        [_node_text(node) for node in _with_class(parser.root, "equipment__item")]
    )
    tips: list[str] = []
    for tips_root in _with_class(parser.root, "single_recipe__tips"):
        items = _leaf_texts(tips_root, frozenset({"li", "p"}))
        tips.extend(items or [_node_text(tips_root)])
    standalone_tips = _dedupe_text(tips)
    method_details = _extract_method_details(parser.root)
    tips.extend(method_details["methodTips"])
    expert_advice = _extract_expert_advice(parser.root)
    return {
        "ingredientSections": _extract_ingredient_sections(parser.root),
        **method_details,
        "equipment": equipment,
        "tips": _dedupe_text(tips),
        "standaloneTips": standalone_tips,
        "expertAdvice": expert_advice,
        "scopedImageUrls": _extract_scoped_images(parser.root),
        "iframeUrls": _extract_scoped_iframes(parser.root),
    }


def parse_argiro_page(html_text: str) -> tuple[Mapping[str, Any], dict[str, Any]]:
    parser = _PageParser()
    parser.feed(html_text)
    parser.close()
    recipes: list[Mapping[str, Any]] = []
    for script in parser.jsonld_scripts:
        try:
            value = json.loads(script)
        except json.JSONDecodeError:
            continue
        recipes.extend(node for node in _jsonld_nodes(value) if _is_recipe(node))
    if len(recipes) != 1:
        raise FullSchemaError(f"expected exactly one JSON-LD Recipe, found {len(recipes)}")

    script_text = "\n".join(parser.inline_scripts)
    shortlink_ids = set()
    for shortlink in parser.shortlinks:
        parsed = urlsplit(shortlink)
        if (
            (parsed.hostname or "").casefold().removeprefix("www.") == "argiro.gr"
            and parsed.path in {"", "/"}
        ):
            candidate = parse_qs(parsed.query).get("p", [""])[0]
            if candidate.isdigit():
                shortlink_ids.add(candidate)
    if len(shortlink_ids) > 1:
        raise FullSchemaError("conflicting Argiro shortlink recipe IDs")
    am_payload = _parse_am_object(script_text)
    am_recipe = am_payload.get("recipe")
    am_recipe = am_recipe if isinstance(am_recipe, Mapping) else {}
    am_stats = am_recipe.get("stats")
    am_stats = am_stats if isinstance(am_stats, Mapping) else {}
    structured_id = str(am_recipe.get("id") or "").strip()
    if structured_id and not structured_id.isdigit():
        raise FullSchemaError("Argiro AM.recipe.id is not numeric")
    script_match = _AM_RECIPE_ID_RE.search(script_text)
    script_id = structured_id or (script_match.group(1) if script_match else "")
    shortlink_id = next(iter(shortlink_ids), "")
    if shortlink_id and script_id and shortlink_id != script_id:
        raise FullSchemaError("Argiro shortlink and AM.recipe IDs disagree")
    rating_match = _RATING_RE.search(script_text)
    votes_match = _VOTES_RE.search(script_text)
    percent_match = _PERCENT_RE.search(script_text)
    rating_value = _number(am_stats.get("rating"))
    rating_votes = _number(am_stats.get("total_votes"))
    rating_percentage = _number(am_stats.get("rating_percentage"))
    metadata = {
        "wordpressId": shortlink_id or script_id,
        "documentLanguage": parser.document_language,
        "rating": rating_value if rating_value is not None else (
            float(rating_match.group(1)) if rating_match else None
        ),
        "ratingVotes": int(rating_votes) if rating_votes is not None else (
            int(votes_match.group(1)) if votes_match else 0
        ),
        "ratingPercentage": rating_percentage if rating_percentage is not None else (
            float(percent_match.group(1)) if percent_match else None
        ),
        "difficulty": plain_text(" ".join(parser.difficulty_parts)),
        # Firestore permits arrays of maps but rejects an array directly inside
        # another array. Preserve both values exactly while giving each
        # publisher tag link an explicit, self-describing shape.
        "tagLinks": [
            {"href": href, "label": label}
            for href, label in dict.fromkeys(parser.tag_links)
        ],
        **_extract_scoped_details(html_text),
    }
    return recipes[0], metadata


def _fallback_id(canonical_url: str) -> str:
    slug = urlsplit(canonical_url).path.rstrip("/").rsplit("/", 1)[-1]
    digest = hashlib.sha256(slug.encode("utf-8")).hexdigest()[:24]
    return f"slug_{digest}"


def _instructions(
    value: object,
    *,
    provider_recipe_id: str = "",
) -> tuple[list[dict[str, Any]], list[str]]:
    sections: list[dict[str, Any]] = []

    def walk(items: object, heading: str = "") -> None:
        values = items if isinstance(items, list) else [items]
        direct: list[str] = []
        for item in values:
            if isinstance(item, str):
                text = plain_text(item)
                if text and not _is_non_recipe_instruction(
                    text,
                    provider_recipe_id=provider_recipe_id,
                ):
                    direct.append(text)
            elif isinstance(item, Mapping):
                kind = str(item.get("@type") or "").casefold()
                if kind == "howtosection":
                    walk(item.get("itemListElement") or item.get("steps"), plain_text(item.get("name")))
                else:
                    text = plain_text(item.get("text") or item.get("name"))
                    if text and not _is_non_recipe_instruction(
                        text,
                        provider_recipe_id=provider_recipe_id,
                    ):
                        direct.append(text)
        if direct:
            sections.append({"title": heading, "steps": direct})

    walk(value)
    for section in sections:
        section['steps'] = [
            cleaned
            for step in section['steps']
            if (
                cleaned := _recipe_instruction_text(
                    step,
                    provider_recipe_id=provider_recipe_id,
                )
            )
        ]
    return sections, [step for section in sections for step in section["steps"]]


def _split_keywords(value: object) -> list[str]:
    result: list[str] = []
    for raw in _strings(value):
        for item in re.split(r"[,;]", raw):
            text = plain_text(item)
            _append_display_label(result, text)
    return result


def _is_greek_language(value: str) -> bool:
    normalized = plain_text(value).casefold().replace("_", "-")
    return (
        normalized == "el"
        or normalized.startswith("el-")
        or normalized in {"ell", "greek", "ελληνικά", "ελληνικα"}
    )


def _has_substantive_greek(values: Sequence[str]) -> bool:
    """Require Greek to dominate the recipe's core editorial content.

    A live English stub contains the typo ``Μake`` (Greek capital mu followed
    by Latin letters), so checking for one Greek code point is not sufficient.
    Legitimate catalog pages are overwhelmingly Greek; a 50% threshold is a
    deliberately conservative fail-closed boundary for title, description,
    ingredients, and method text only.
    """
    greek_letters = 0
    latin_letters = 0
    for value in values:
        for character in value:
            if not character.isalpha():
                continue
            script_name = unicodedata.name(character, "")
            greek_letters += "GREEK" in script_name
            latin_letters += "LATIN" in script_name
    return greek_letters > 0 and greek_letters >= latin_letters


def _display_label_key(value: str) -> str:
    return " ".join(_normalized_label(plain_text(value)).split())


def _append_display_label(values: list[str], value: object) -> None:
    """Dedupe display labels without losing the first official spelling."""
    text = plain_text(value)
    key = _display_label_key(text)
    if text and key and all(_display_label_key(item) != key for item in values):
        values.append(text)


def _taxonomy(metadata: Mapping[str, Any], recipe: Mapping[str, Any]):
    labels: dict[str, list[str]] = {facet: [] for facet in FACET_KEYS}
    tags: list[str] = []
    category_slugs: set[str] = set()
    ingredient_paths: list[tuple[str, ...]] = []
    for tag_link in metadata.get("tagLinks", []):
        if not isinstance(tag_link, Mapping):
            raise FullSchemaError("Argiro tagLinks must contain objects")
        href = tag_link.get("href")
        label = tag_link.get("label")
        if not isinstance(href, str) or not isinstance(label, str):
            raise FullSchemaError("Argiro tagLinks require string href and label")
        parsed = urlsplit(href)
        if (parsed.hostname or "").casefold().removeprefix("www.") != "argiro.gr":
            continue
        parts = [part for part in urlsplit(href).path.split("/") if part]
        if len(parts) < 2:
            continue
        family, slug = parts[0].casefold(), parts[-1].casefold()
        facet = _TAXONOMY_FACETS.get(family)
        if facet:
            _append_display_label(labels[facet], label)
        if family == "recipe-category":
            category_slugs.update(part.casefold() for part in parts[1:])
        elif family == "basic-ingredient":
            # Preserve the complete official hierarchy.  The deepest mapped
            # node is the most specific signal: ``kreas/kotopoulo`` must be
            # poultry, not generic meat.
            ingredient_paths.append(tuple(part.casefold() for part in parts[1:]))
        _append_display_label(tags, label)
    schema_categories = _strings(recipe.get("recipeCategory"))
    keywords = _split_keywords(recipe.get("keywords"))
    for label in schema_categories + keywords:
        _append_display_label(tags, label)
    for cuisine in _strings(recipe.get("recipeCuisine")):
        _append_display_label(labels["cuisine"], cuisine)
    for raw_diet in _strings(recipe.get("suitableForDiet")):
        key = urlsplit(raw_diet).path.rstrip("/").rsplit("/", 1)[-1].casefold()
        label = _DIET_SCHEMA_LABELS.get(key, plain_text(raw_diet))
        _append_display_label(labels["diet"], label)
    exact_categories = {_normalized_label(value) for value in schema_categories}

    recipe_keys = [
        key for key in _CATEGORY_KEY_PRECEDENCE
        if category_slugs & _CATEGORY_SLUGS[key]
        or exact_categories & _CATEGORY_LABELS_EXACT[key]
    ]

    # Rank official ingredient evidence by hierarchy depth, then by a stable
    # category order.  Keep secondary evidence for Explore filters while the
    # first key remains the single planner category.
    ingredient_specificity: dict[str, int] = {}
    for path in ingredient_paths:
        for depth, slug in enumerate(path, start=1):
            for key in _CATEGORY_KEY_PRECEDENCE:
                if slug in _CATEGORY_SLUGS[key]:
                    ingredient_specificity[key] = max(
                        depth, ingredient_specificity.get(key, 0)
                    )
    ingredient_keys = sorted(
        ingredient_specificity,
        key=lambda key: (
            -ingredient_specificity[key],
            _CATEGORY_KEY_PRECEDENCE.index(key),
        ),
    )

    def ordered_unique(primary: str, *groups: Sequence[str]) -> list[str]:
        result = [primary]
        for group in groups:
            for key in group:
                if key != primary and key not in result:
                    result.append(key)
        return result

    # Fail-closed precedence mirrors the Akis taxonomy contract: sweets and
    # explicit non-meal families are terminal; only the publisher's exact
    # sandwich/finger-food paths become Βρώμικο; then the most-specific
    # ingredient hierarchy wins over a generic recipe family.  Generic Snack,
    # pizza, titles, and free-form keywords never imply street food.
    dessert = (
        bool(category_slugs & _TERMINAL_DESSERT_CATEGORY_SLUGS)
        or bool(exact_categories & _CATEGORY_LABELS_EXACT["dessert"])
    )
    drinks = (
        bool(category_slugs & _TERMINAL_DRINK_CATEGORY_SLUGS)
        or bool(exact_categories & _CATEGORY_LABELS_EXACT["drinks"])
    )
    non_meal = bool(category_slugs & _TERMINAL_OTHER_CATEGORY_SLUGS)
    street_format = bool(category_slugs & _STREET_FORMAT_CATEGORY_SLUGS)
    nested_poultry = any(
        len(path) >= 2
        and path[0] == "kreas"
        and path[-1] in {"kotopoulo", "galopoula", "kokoras"}
        for path in ingredient_paths
    )
    if dessert:
        keys = ordered_unique("dessert", recipe_keys, ingredient_keys)
    elif drinks:
        keys = ordered_unique("drinks", recipe_keys, ingredient_keys)
    elif non_meal:
        keys = ordered_unique("other", recipe_keys, ingredient_keys)
    elif street_format:
        keys = ordered_unique("street_food", recipe_keys, ingredient_keys)
    elif recipe_keys:
        # Explicit mapped recipe families outrank cross-family side
        # ingredients (chicken with broccoli stays poultry; pasta with tomato
        # stays pasta).  The one strict refinement is the publisher's nested
        # kreas/kotopoulo|galopoula hierarchy, which is more specific than its
        # broad Kreas recipe family.
        primary = (
            "poultry"
            if recipe_keys[0] == "meat" and nested_poultry
            else recipe_keys[0]
        )
        keys = ordered_unique(primary, recipe_keys, ingredient_keys)
    elif ingredient_keys:
        keys = ordered_unique(ingredient_keys[0], ingredient_keys[1:])
    else:
        keys = ["other"]
    return keys, labels, tags


def derive_argiro_taxonomy(source_payload: Mapping[str, Any]) -> dict[str, Any]:
    """Rebuild planner taxonomy only from preserved provider evidence."""
    recipe = source_payload.get("jsonLd")
    metadata = source_payload.get("htmlMetadata")
    if not isinstance(recipe, Mapping) or not isinstance(metadata, Mapping):
        raise FullSchemaError(
            "Argiro sourcePayload must preserve jsonLd and htmlMetadata taxonomy evidence"
        )
    category_keys, _labels, _tags = _taxonomy(metadata, recipe)
    category = canonical_category(category_keys)
    return {
        "categoryKeys": category_keys,
        "category": category,
        "categoryLabel": CATEGORY_LABELS.get(category, "Άλλο"),
    }


def _validated_rating(metadata: Mapping[str, Any]) -> tuple[float, int]:
    value, votes, percentage = metadata.get("rating"), metadata.get("ratingVotes", 0), metadata.get("ratingPercentage")
    if not isinstance(value, (int, float)) or not math.isfinite(float(value)) or not 0 <= float(value) <= 5:
        return 0.0, 0
    if percentage is not None and (
        not isinstance(percentage, (int, float))
        or not math.isfinite(float(percentage))
        or abs(float(percentage) - float(value) * 20) > 1.0
    ):
        return 0.0, 0
    return round(float(value) * 2, 2), max(0, int(votes))


def _nutrition_information(value: object) -> tuple[str, list[dict[str, str]]]:
    """Map schema.org NutritionInformation without changing values or units."""
    nutrition = value if isinstance(value, Mapping) else {}
    section: dict[str, str] = {"title": ""}
    for prefix in _NUTRITION_PORTION_FIELDS.values():
        section.update({
            f"{prefix}Portion": "",
            f"{prefix}PortionPercent": "",
            f"{prefix}100g": "",
            f"{prefix}100gPercent": "",
        })
    has_value = False
    for source, prefix in _NUTRITION_PORTION_FIELDS.items():
        raw = nutrition.get(source)
        normalized = (
            plain_text(raw)
            if isinstance(raw, (str, int, float)) and not isinstance(raw, bool)
            else ""
        )
        section[f"{prefix}Portion"] = normalized
        has_value = has_value or bool(normalized)
    if not has_value:
        return "", []
    serving_size = nutrition.get("servingSize")
    nutrition_per = (
        plain_text(serving_size)
        if isinstance(serving_size, (str, int, float))
        and not isinstance(serving_size, bool)
        else ""
    )
    return nutrition_per or "τη δηλωμένη μερίδα", [section]


def _ingredient_texts(sections: Sequence[Mapping[str, Any]]) -> list[str]:
    result: list[str] = []
    for section in sections:
        for ingredient in section.get("ingredients", []):
            if isinstance(ingredient, Mapping):
                result.append(plain_text(" ".join(
                    str(ingredient.get(field) or "")
                    for field in ("quantity", "unit", "title", "info")
                )))
    return result


def _merge_jsonld_ingredient_prefixes(
    sections: Sequence[Mapping[str, Any]],
    structured_values: Sequence[str],
) -> None:
    """Restore a JSON-LD unit prefix omitted by an HTML ``without_quantity`` row."""
    ingredients = [
        ingredient
        for section in sections
        for ingredient in section.get("ingredients", [])
        if isinstance(ingredient, dict)
    ]
    if len(ingredients) != len(structured_values):
        return
    for ingredient, structured in zip(ingredients, structured_values):
        if _comparison_text(_ingredient_texts([{"ingredients": [ingredient]}])[0]) == _comparison_text(structured):
            continue
        title = plain_text(ingredient.get("title"))
        source = plain_text(structured)
        if (
            not title
            or ingredient.get("unit")
            or ingredient.get("info")
            or not source.casefold().endswith(title.casefold())
        ):
            continue
        prefix = plain_text(source[: len(source) - len(title)])
        current_quantity = plain_text(ingredient.get("quantity"))
        if prefix and (
            not current_quantity
            or _comparison_text(current_quantity) in _comparison_text(prefix)
        ):
            ingredient["quantity"] = prefix


def _require_matching_sequence(
    html_values: Sequence[str],
    structured_values: Sequence[str],
    *,
    label: str,
    allow_html_superset: bool = False,
) -> None:
    html_normalized = [_comparison_text(value) for value in html_values]
    structured_normalized = [_comparison_text(value) for value in structured_values]
    if allow_html_superset and len(html_normalized) > len(structured_normalized):
        cursor = 0
        for value in structured_normalized:
            try:
                cursor = html_normalized.index(value, cursor) + 1
            except ValueError:
                break
        else:
            return
    if html_normalized != structured_normalized:
        mismatch = next(
            (
                index for index, (left, right) in enumerate(
                    zip(html_normalized, structured_normalized), start=1
                ) if left != right
            ),
            min(len(html_normalized), len(structured_normalized)) + 1,
        )
        raise FullSchemaError(
            f"Argiro {label} HTML/JSON-LD mismatch at item {mismatch} "
            f"({len(html_normalized)} != {len(structured_normalized)} total)"
        )


def _filter_method_groups(
    groups: Sequence[Mapping[str, Any]],
    *,
    provider_recipe_id: str,
) -> list[dict[str, Any]]:
    filtered: list[dict[str, Any]] = []
    for group in groups:
        steps = list(group.get('steps', []))
        sources = list(group.get('sourceSteps', []))
        if len(steps) != len(sources):
            raise FullSchemaError('Argiro method source-step cardinality mismatch')
        keep = [
            index
            for index, step in enumerate(steps)
            if not _is_non_recipe_instruction(
                plain_text(step),
                provider_recipe_id=provider_recipe_id,
            )
        ]
        if not keep:
            continue
        item = dict(group)
        item['steps'] = [steps[index] for index in keep]
        item['sourceSteps'] = [sources[index] for index in keep]
        filtered.append(item)
    return filtered


def _align_method_groups(
    groups: Sequence[Mapping[str, Any]],
    jsonld_steps: Sequence[str],
) -> tuple[list[dict[str, Any]], list[str]]:
    """Use DOM headings/boundaries while retaining canonical JSON-LD step text.

    A malformed tip block can hide prose nodes from the tolerant DOM.  Ordered
    leaf nodes are therefore ordered anchors.  JSON-LD text between anchors is
    retained in the same DOM group, which recovers malformed ``p`` nodes without
    allowing reordered or unrelated content to pass validation.
    """
    canonical = list(jsonld_steps)
    normalized = [_comparison_text(step) for step in canonical]
    cursor = 0
    sections: list[dict[str, Any]] = []
    tips: list[str] = []
    for group_index, group in enumerate(groups):
        anchors = [
            plain_text(value) for value in group.get("steps", [])
            if plain_text(value)
        ]
        if not anchors:
            raise FullSchemaError(f"Argiro method group {group_index + 1} has no anchors")
        positions: list[int] = []
        search_from = cursor
        anchor_index = 0
        while anchor_index < len(anchors):
            target = _comparison_text(anchors[anchor_index])
            try:
                position = normalized.index(target, search_from)
                consumed = 1
            except ValueError:
                position = -1
                consumed = 0
                source_steps = group.get("sourceSteps")
                if (
                    isinstance(source_steps, list)
                    and len(source_steps) == len(anchors)
                ):
                    source_target = _comparison_text(source_steps[anchor_index])
                    if source_target != target:
                        try:
                            position = normalized.index(source_target, search_from)
                        except ValueError:
                            pass
                        else:
                            # Exact full-DOM/JSON-LD conservation proves that
                            # only the structurally scoped read-also subtree was
                            # removed. Emit the clean visible method sentence.
                            canonical[position] = anchors[anchor_index]
                            normalized[position] = target
                            consumed = 1
                # Some Argiro JSON-LD nodes concatenate adjacent rendered
                # ``li`` steps. Accept that structural difference only when
                # the complete normalized text is an exact match; no fuzzy or
                # partial content mismatch crosses this fail-closed boundary.
                if position < 0:
                    for end in range(
                        anchor_index + 2,
                        min(len(anchors), anchor_index + 4) + 1,
                    ):
                        combined = _comparison_text(
                            " ".join(anchors[anchor_index:end])
                        )
                        try:
                            position = normalized.index(combined, search_from)
                        except ValueError:
                            continue
                        consumed = end - anchor_index
                        break
                if position < 0:
                    raise FullSchemaError(
                        "Argiro method steps HTML/JSON-LD mismatch: "
                        f"group {group_index + 1} anchor not found"
                    )
            positions.append(position)
            search_from = position + 1
            anchor_index += consumed
        is_tip = bool(group.get("isTip"))
        # A few legacy pages have a malformed/missing normal-method container
        # and expose only the final Tips group in the DOM.  JSON-LD still has
        # the complete ordered method.  Everything before the first exact tip
        # anchor is therefore a recovered method section; the anchored suffix
        # remains tips.  If the tip anchor starts at zero (the audited 20573
        # video/tips-only recipe), no synthetic method section is created.
        if group_index == 0 and is_tip and cursor == 0 and positions[0] > 0:
            sections.append({
                "title": "",
                "steps": canonical[:positions[0]],
            })
            cursor = positions[0]
        end = (
            len(canonical)
            if group_index + 1 == len(groups)
            else positions[-1] + 1
        )
        assigned = canonical[cursor:end]
        if not assigned:
            raise FullSchemaError(f"Argiro method group {group_index + 1} is empty")
        if is_tip:
            tips.extend(assigned)
        else:
            sections.append({
                "title": plain_text(group.get("title")),
                "steps": assigned,
            })
        cursor = end
    if cursor != len(canonical):
        raise FullSchemaError(
            f"Argiro method steps HTML/JSON-LD mismatch: DOM accounts for "
            f"({cursor} != {len(canonical)})"
        )
    return sections, tips


def _image_identity(value: str) -> str:
    parsed = urlsplit(value)
    path = re.sub(r"-\d+x\d+(?=\.[a-z0-9]+$)", "", parsed.path, flags=re.I)
    return f"{(parsed.hostname or '').casefold().removeprefix('www.')}{path.casefold()}"


def _dedupe_images(values: Sequence[str]) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for value in values:
        identity = _image_identity(value)
        if value and identity not in seen:
            seen.add(identity)
            result.append(value)
    return result


def normalize_argiro_page(
    html_text: str,
    *,
    source_url: str,
    sitemap_last_modified: str = "",
    active: bool = True,
) -> dict[str, Any]:
    recipe, metadata = parse_argiro_page(html_text)
    path_slug = urlsplit(source_url).path.rstrip("/").rsplit("/", 1)[-1]
    provisional_id = str(metadata.get("wordpressId") or _fallback_id(source_url))
    canonical_url = canonical_recipe_url(ARGIRO, source_url, provisional_id)
    provider_recipe_id = str(metadata.get("wordpressId") or _fallback_id(canonical_url))
    document_id = recipe_document_id(ARGIRO, provider_recipe_id)
    title = plain_text(recipe.get("name"))
    if not title:
        raise FullSchemaError("Argiro JSON-LD Recipe has no name")

    prep = parse_iso8601_minutes(recipe.get("prepTime"))
    cook = parse_iso8601_minutes(recipe.get("cookTime"))
    total = parse_iso8601_minutes(recipe.get("totalTime")) or prep + cook
    jsonld_method_sections, jsonld_steps = _instructions(
        recipe.get("recipeInstructions"),
        provider_recipe_id=provider_recipe_id,
    )
    # A small number of provider pages expose orphan numeric list nodes (for
    # example a bare "1") in JSON-LD between otherwise matching ingredients.
    # They have no ingredient name and are not rendered in the recipe HTML.
    ingredients = [
        item for item in _strings(recipe.get("recipeIngredient"))
        if any(character.isalpha() for character in item)
    ]
    document_language = plain_text(metadata.get("documentLanguage"))
    if document_language and not _is_greek_language(document_language):
        raise ArgiroLanguageError(
            provider_recipe_id,
            f"HTML lang={document_language!r}",
        )
    structured_languages = _strings(recipe.get("inLanguage"))
    if structured_languages and any(
        not _is_greek_language(value) for value in structured_languages
    ):
        raise ArgiroLanguageError(
            provider_recipe_id,
            "JSON-LD inLanguage is not Greek",
        )
    if not _has_substantive_greek([
        title,
        plain_text(recipe.get("description")),
        *ingredients,
        *jsonld_steps,
    ]):
        raise ArgiroLanguageError(
            provider_recipe_id,
            "recipe content is not substantively Greek",
        )
    jsonld_ingredient_sections = [{
        "title": "",
        "ingredients": [{
            "title": item, "unit": "", "quantity": "", "info": "",
            "internalLink": "", "externalLink": "", "ukUnit": "",
            "ukQuantity": "", "usUnit": "", "usQuantity": "",
        } for item in ingredients],
    }]
    html_ingredient_sections = metadata.get("ingredientSections")
    html_ingredient_count = sum(
        len(section.get("ingredients", []))
        for section in html_ingredient_sections
    ) if isinstance(html_ingredient_sections, list) else 0
    if html_ingredient_count and ingredients:
        _merge_jsonld_ingredient_prefixes(html_ingredient_sections, ingredients)
        _require_matching_sequence(
            _ingredient_texts(html_ingredient_sections),
            ingredients,
            label="ingredients",
            allow_html_superset=True,
        )
    ingredient_sections = (
        html_ingredient_sections if html_ingredient_count else jsonld_ingredient_sections
    )
    html_method_sections = metadata.get("methodSections")
    html_method_sequence = metadata.get("methodSequence")
    if not isinstance(html_method_sequence, list):
        html_method_sequence = []
    html_method_groups = metadata.get("methodGroups")
    if isinstance(html_method_groups, list):
        html_method_groups = _filter_method_groups(
            html_method_groups,
            provider_recipe_id=provider_recipe_id,
        )
    method_tips: list[str] = []
    if (
        jsonld_steps
        and isinstance(html_method_groups, list)
        and html_method_groups
    ):
        method_sections, method_tips = _align_method_groups(
            html_method_groups,
            jsonld_steps,
        )
    elif html_method_sequence and isinstance(html_method_sections, list):
        method_sections = html_method_sections
        method_tips = list(metadata.get("methodTips", []))
    else:
        method_sections = jsonld_method_sections
    all_steps = [
        step for section in method_sections for step in section.get("steps", [])
    ]
    category_keys, facet_labels, tags = _taxonomy(metadata, recipe)
    category = canonical_category(category_keys)
    rating, rating_count = _validated_rating(metadata)
    nutrition_per, nutrition_sections = _nutrition_information(
        recipe.get("nutrition")
    )
    images = _dedupe_images([
        safe
        for raw in _https_urls(recipe.get("image"))
        if (safe := _safe_argiro_upload_url(raw))
    ] + list(metadata.get("scopedImageUrls", [])))
    video_urls: list[str] = []
    for video in recipe.get("video", []) if isinstance(recipe.get("video"), list) else [recipe.get("video")]:
        if isinstance(video, Mapping):
            for key in ("contentUrl", "embedUrl", "url"):
                video_urls.extend(_https_urls(video.get(key)))
        else:
            video_urls.extend(_https_urls(video))
    video_urls.extend(metadata.get("iframeUrls", []))
    video_urls = _dedupe_video_urls(video_urls)
    author = " · ".join(_strings(recipe.get("author")))
    published_at = str(recipe.get("datePublished") or "")
    updated_at = str(recipe.get("dateModified") or "")
    source_payload = sanitize_source_payload({"jsonLd": recipe, "htmlMetadata": metadata})
    associations = {facet: [] for facet in FACET_KEYS}
    for facet, values in facet_labels.items():
        associations[facet] = sorted([
            {"id": _machine_key(value), "title": value} for value in values
        ], key=lambda item: (item["title"].casefold(), item["id"]))
    expert_advice = list(metadata.get("expertAdvice", []))
    nutrition_tips = []
    for advice in expert_advice:
        if not isinstance(advice, Mapping):
            continue
        heading = " — ".join(
            value for value in (
                plain_text(advice.get("title")),
                plain_text(advice.get("name")),
            ) if value
        )
        content = plain_text(advice.get("content"))
        combined = f"{heading}: {content}" if heading else content
        if combined and combined not in nutrition_tips:
            nutrition_tips.append(combined)

    record: dict[str, Any] = {
        "id": document_id,
        "detailSchemaVersion": ARGIRO_DETAIL_SCHEMA_VERSION,
        "sourceRecipeId": int(provider_recipe_id) if provider_recipe_id.isdigit() and int(provider_recipe_id) <= 2_147_483_647 else 0,
        "sourceKey": ARGIRO.key,
        "providerRecipeId": provider_recipe_id,
        "slug": path_slug,
        "title": title,
        "description": plain_text(recipe.get("description")),
        "seoTitle": title,
        "seoDescription": plain_text(recipe.get("description")),
        "categoryKeys": category_keys,
        "category": category,
        "categoryLabel": CATEGORY_LABELS.get(category, "Άλλο"),
        "categorySourceId": 0,
        "rating10": rating,
        "rating": rating,
        "ratingCount": rating_count,
        "rating1": 0, "rating2": 0, "rating3": 0, "rating4": 0, "rating5": 0,
        "prepMinutes": prep,
        "cookMinutes": cook,
        "waitMinutes": 0,
        "totalMinutes": total,
        "sourceDifficulty": str(metadata.get("difficulty") or ""),
        "ease": classify_ease(len(method_sections), len(all_steps), total),
        "randomKey": stable_recipe_random_key(ARGIRO, provider_recipe_id),
        "stepCount": len(all_steps),
        "preparationCount": len(method_sections),
        "servings": " · ".join(_strings(recipe.get("recipeYield"))),
        "language": "el",
        "imageUrl": images[0] if images else "",
        "imageUrls": images,
        "source": ARGIRO.source,
        "sourceUrl": canonical_url,
        "canonicalUrl": canonical_url,
        "shortUrl": "",
        "sourceName": ARGIRO.display_name,
        "tags": tags,
        "dietLabels": facet_labels["diet"],
        "mealTypeLabels": facet_labels["meal_type"],
        "occasionLabels": facet_labels["occasion"],
        "methodLabels": facet_labels["method"],
        "cuisineLabels": facet_labels["cuisine"],
        "ingredientLabels": facet_labels["ingredient"],
        "quickRecipe": total > 0 and total <= 30,
        "videoUrls": video_urls,
        "ingredientSections": ingredient_sections,
        "methodSections": method_sections,
        "tips": _dedupe_text([
            *list(metadata.get("standaloneTips", [])),
            *method_tips,
        ]),
        "nutritionTips": nutrition_tips,
        "nutritionPer": nutrition_per,
        "nutritionSections": nutrition_sections,
        "equipment": list(metadata.get("equipment", [])),
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


def _machine_key(value: str) -> str:
    normalized = unicodedata.normalize("NFKD", value)
    ascii_value = normalized.encode("ascii", "ignore").decode("ascii").casefold()
    result = re.sub(r"[^a-z0-9]+", "-", ascii_value).strip("-")
    return result[:80] or hashlib.sha256(value.encode("utf-8")).hexdigest()[:16]


def _normalized_label(value: str) -> str:
    return "".join(
        character for character in unicodedata.normalize("NFD", value.casefold())
        if unicodedata.category(character) != "Mn"
    ).strip()
