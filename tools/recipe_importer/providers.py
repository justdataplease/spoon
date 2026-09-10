"""Provider-neutral recipe identity and provenance helpers.

The Firestore document ID is part of the mobile app's public data contract: it is
copied into favorites, plans, history, notes, and shopping-list entries.  Existing
Akis documents therefore keep their historical numeric IDs.  Every other provider
uses a source-prefixed ID so equal native IDs cannot collide.
"""

from __future__ import annotations

import base64
import hashlib
import re
from collections.abc import Mapping
from dataclasses import dataclass
from urllib.parse import parse_qsl, unquote, urlsplit, urlunsplit


DOCUMENT_ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,128}$")
SOURCE_KEY_RE = re.compile(r"^[a-z][a-z0-9_]{1,31}$")
MAX_PROVIDER_RECIPE_ID_BYTES = 80


class ProviderError(ValueError):
    """Raised when a record's provider identity or URL is inconsistent."""


@dataclass(frozen=True)
class RecipeProvider:
    key: str
    source: str
    display_name: str
    hosts: frozenset[str]
    canonical_host: str
    recipe_path: re.Pattern[str]
    url_id_must_match: bool = True
    legacy_numeric_document_ids: bool = False
    image_path_prefixes: tuple[str, ...] = ()
    image_hosts: frozenset[str] = frozenset()
    image_query_keys: frozenset[str] = frozenset()


AKIS = RecipeProvider(
    key="akis",
    source="akispetretzikis.com",
    display_name="Άκης Πετρετζίκης",
    hosts=frozenset({"akispetretzikis.com", "www.akispetretzikis.com"}),
    canonical_host="akispetretzikis.com",
    recipe_path=re.compile(r"^/(?:el/)?recipe/(?P<id>\d+)(?:/[^/?#]+)?/?$"),
    legacy_numeric_document_ids=True,
    image_path_prefixes=("/photos/",),
)

ARGIRO = RecipeProvider(
    key="argiro",
    source="argiro.gr",
    display_name="Αργυρώ Μπαρμπαρίγου",
    hosts=frozenset({"argiro.gr", "www.argiro.gr"}),
    canonical_host="www.argiro.gr",
    recipe_path=re.compile(r"^/recipe/(?P<id>[^/?#]+)/?$"),
    url_id_must_match=False,
    image_path_prefixes=("/wp-content/", "/images/", "/uploads/"),
)

GASTRONOMOS = RecipeProvider(
    key="gastronomos",
    source="gastronomos.gr",
    display_name="Γαστρονόμος",
    hosts=frozenset({"gastronomos.gr", "www.gastronomos.gr"}),
    canonical_host="www.gastronomos.gr",
    recipe_path=re.compile(r"^/syntagh/(?P<slug>[^/?#]+)/(?P<id>\d+)/?$"),
    image_path_prefixes=("/wp-content/", "/uploads/", "/images/"),
)

TSOULIS = RecipeProvider(
    key="tsoulis",
    source="giorgostsoulis.com",
    display_name="Γιώργος Τσούλης",
    hosts=frozenset({"giorgostsoulis.com", "www.giorgostsoulis.com", "app.giorgostsoulis.com"}),
    canonical_host="www.giorgostsoulis.com",
    recipe_path=re.compile(r"^/syntages/[^/?#]+/(?P<id>[^/?#]+)/?$"),
    url_id_must_match=False,
    image_path_prefixes=("/storage/", "/images/", "/media/"),
    image_hosts=frozenset({"api.giorgostsoulis.com"}),
)

LUCACOS = RecipeProvider(
    key="lucacos",
    source="yiannislucacos.gr",
    display_name="Γιάννης Λουκάκος",
    hosts=frozenset({"yiannislucacos.gr", "www.yiannislucacos.gr"}),
    canonical_host="www.yiannislucacos.gr",
    recipe_path=re.compile(r"^/recipe/(?:[^/?#]+/)?(?P<id>\d+)/[^/?#]+/?$"),
    image_path_prefixes=("/sites/",),
    image_query_keys=frozenset({"itok"}),
)

FUNKYCOOK = RecipeProvider(
    key="funkycook",
    source="funkycook.gr",
    display_name="Funky Cook",
    hosts=frozenset({"funkycook.gr", "www.funkycook.gr"}),
    canonical_host="funkycook.gr",
    recipe_path=re.compile(r"^/(?P<id>[^/?#]+)/?$"),
    url_id_must_match=False,
    image_path_prefixes=("/wp-content/",),
)

COOKPAD = RecipeProvider(
    key="cookpad",
    source="cookpad.com",
    display_name="Cookpad",
    hosts=frozenset({"cookpad.com", "www.cookpad.com"}),
    canonical_host="cookpad.com",
    recipe_path=re.compile(r"^/gr/sintages/(?P<id>\d+)(?:-[^/?#]+)?/?$"),
    image_path_prefixes=("/recipes/",),
    image_hosts=frozenset({"img-global.cpcdn.com"}),
)

PROVIDERS = {
    provider.key: provider
    for provider in (AKIS, ARGIRO, GASTRONOMOS, TSOULIS, LUCACOS, FUNKYCOOK, COOKPAD)
}
_ALIASES = {
    alias: provider.key
    for provider in PROVIDERS.values()
    for alias in {provider.key, provider.source, provider.canonical_host, *provider.hosts}
}


def provider_for(
    *,
    source_key: object = None,
    source: object = None,
    source_url: object = None,
    default_key: str = "akis",
) -> RecipeProvider:
    """Resolve a provider and reject conflicting provenance signals."""
    candidates: set[str] = set()
    for value in (source_key, source):
        if value is None or not str(value).strip():
            continue
        normalized = str(value).strip().casefold().rstrip(".")
        key = _ALIASES.get(normalized)
        if key is None:
            raise ProviderError(f"unsupported recipe source {value!r}")
        candidates.add(key)

    if source_url is not None and str(source_url).strip():
        host = (urlsplit(str(source_url).strip()).hostname or "").casefold().rstrip(".")
        key = _ALIASES.get(host)
        if key is None:
            raise ProviderError(f"unsupported recipe source host {host!r}")
        candidates.add(key)

    if len(candidates) > 1:
        raise ProviderError("sourceKey, source, and sourceUrl identify different providers")
    key = next(iter(candidates), default_key)
    try:
        return PROVIDERS[key]
    except KeyError as exc:  # defensive if a caller supplies a bad default
        raise ProviderError(f"unsupported recipe source key {key!r}") from exc


def normalize_provider_recipe_id(value: object) -> str:
    if isinstance(value, bool) or value is None:
        raise ProviderError("providerRecipeId is required")
    identifier = str(value).strip()
    encoded = identifier.encode("utf-8")
    if not identifier or len(encoded) > MAX_PROVIDER_RECIPE_ID_BYTES:
        raise ProviderError(
            f"providerRecipeId must be 1-{MAX_PROVIDER_RECIPE_ID_BYTES} UTF-8 bytes"
        )
    if any(ord(character) < 32 or character in "/?#\\" for character in identifier):
        raise ProviderError("providerRecipeId contains unsafe path or control characters")
    return identifier


def recipe_document_id(provider: RecipeProvider, provider_recipe_id: object) -> str:
    """Return a stable ID while retaining all already-published Akis references."""
    native_id = normalize_provider_recipe_id(provider_recipe_id)
    if provider.legacy_numeric_document_ids and native_id.isdigit():
        document_id = native_id
    elif re.fullmatch(r"[A-Za-z0-9_-]{1,80}", native_id):
        document_id = f"{provider.key}_{native_id}"
    else:
        digest = hashlib.sha256(native_id.encode("utf-8")).hexdigest()
        document_id = f"{provider.key}_h_{digest}"
    if not DOCUMENT_ID_RE.fullmatch(document_id):
        raise ProviderError("derived recipe document ID is not Firestore-safe")
    return document_id


def canonical_recipe_url(
    provider: RecipeProvider,
    value: object,
    provider_recipe_id: object,
) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ProviderError("sourceUrl is required")
    parts = urlsplit(value.strip())
    host = (parts.hostname or "").casefold().rstrip(".")
    if parts.scheme.casefold() != "https" or host not in provider.hosts:
        raise ProviderError(f"sourceUrl must be an HTTPS {provider.source} URL")
    if parts.username or parts.password or parts.port not in (None, 443):
        raise ProviderError("sourceUrl must not contain credentials or a non-standard port")
    if provider is AKIS and unquote(parts.path).startswith("/en/"):
        raise ProviderError("English URLs are rejected; sourceUrl must identify the Greek recipe")
    match = provider.recipe_path.fullmatch(unquote(parts.path))
    native_id = normalize_provider_recipe_id(provider_recipe_id)
    if not match or (provider.url_id_must_match and match.group("id") != native_id):
        raise ProviderError(
            f"sourceUrl must be a canonical Greek {provider.source} recipe URL matching providerRecipeId"
        )
    path = parts.path
    if provider is AKIS and path.startswith("/el/recipe/"):
        path = path[3:]
    if provider in {ARGIRO, GASTRONOMOS}:
        path = path.rstrip("/") + "/"
    return urlunsplit(("https", provider.canonical_host, path, "", ""))


def canonical_image_url(provider: RecipeProvider, value: object) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ProviderError("imageUrl must be a non-empty HTTPS URL")
    parts = urlsplit(value.strip())
    host = (parts.hostname or "").casefold().rstrip(".")
    if parts.scheme.casefold() != "https" or host not in provider.hosts | provider.image_hosts:
        raise ProviderError(f"imageUrl must use HTTPS on an approved {provider.source} image host")
    if parts.username or parts.password or parts.port not in (None, 443):
        raise ProviderError("imageUrl must not contain credentials or a non-standard port")
    decoded_path = unquote(parts.path)
    segments = decoded_path.split("/")
    if (
        not any(decoded_path.startswith(prefix) for prefix in provider.image_path_prefixes)
        or decoded_path.endswith("/")
        or "\\" in decoded_path
        or any(segment in {".", ".."} for segment in segments)
        or any(key not in provider.image_query_keys for key, _ in parse_qsl(parts.query, keep_blank_values=True))
        or (bool(parts.query) and not provider.image_query_keys)
        or parts.fragment
    ):
        raise ProviderError("imageUrl path must identify an approved provider-hosted file")
    image_host = host if host in provider.image_hosts else provider.canonical_host
    return urlunsplit(("https", image_host, parts.path, parts.query, ""))


def stable_recipe_random_key(provider: RecipeProvider, provider_recipe_id: object) -> float:
    """Stable [0,1) value namespaced by provider, preserving legacy Akis ordering."""
    native_id = normalize_provider_recipe_id(provider_recipe_id)
    seed = native_id if provider is AKIS else f"{provider.key}:{native_id}"
    digest = hashlib.sha256(f"spoon-recipe:{seed}".encode("utf-8")).digest()
    return int.from_bytes(digest[:8], "big") / 2**64


def record_source_key(record: Mapping[str, object]) -> str:
    """Read both new records and legacy domain-only Akis documents."""
    return provider_for(
        source_key=record.get("sourceKey"),
        source=record.get("source"),
        source_url=record.get("sourceUrl"),
    ).key
