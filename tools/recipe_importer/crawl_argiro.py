"""Permission-gated, resumable Argiro sitemap/JSON-LD catalog crawler.

This module intentionally performs no work on import.  A real crawl requires the
explicit Argiro-specific acknowledgement because robots permission is not content
licensing.  Unit tests use synthetic responses only.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sqlite3
import sys
import time
import xml.etree.ElementTree as ET
from collections.abc import Callable, Iterable, Mapping
from dataclasses import dataclass
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path
from urllib.parse import urljoin, urlsplit, urlunsplit
from urllib.robotparser import RobotFileParser

import requests

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from argiro_schema import (  # type: ignore[import-not-found]
        ArgiroLanguageError,
        normalize_argiro_page,
    )
    from full_schema import (  # type: ignore[import-not-found]
        ARGIRO_DETAIL_SCHEMA_VERSION,
        FullSchemaError,
        collection_hash,
        document_sizes,
        ensure_full_record,
        firestore_detail_payload,
        firestore_recipe_payload,
        firestore_source_payload,
    )
    from providers import ARGIRO, canonical_recipe_url, recipe_document_id  # type: ignore[import-not-found]
else:
    from .argiro_schema import ArgiroLanguageError, normalize_argiro_page
    from .full_schema import (
        ARGIRO_DETAIL_SCHEMA_VERSION,
        FullSchemaError,
        collection_hash,
        document_sizes,
        ensure_full_record,
        firestore_detail_payload,
        firestore_recipe_payload,
        firestore_source_payload,
    )
    from .providers import ARGIRO, canonical_recipe_url, recipe_document_id


SITE_ORIGIN = "https://www.argiro.gr"
ROBOTS_URL = f"{SITE_ORIGIN}/robots.txt"
SITEMAP_URL = f"{SITE_ORIGIN}/sitemap_index.xml"
USER_AGENT = "PeltesSpoonRecipeImporter/1.0 (+mailto:hey@spoon.gr)"
ALLOWED_HOSTS = {"argiro.gr", "www.argiro.gr"}
RECIPE_PATH_RE = re.compile(r"^/recipe/(?P<slug>[^/?#]+)/?$")
RECIPE_SITEMAP_RE = re.compile(r"^recipe-sitemap(?:\d+)?\.xml$", re.I)
RETRY_STATUSES = {408, 425, 429, 500, 502, 503, 504}
MINIMUM_DELAY_SECONDS = 1.0
MAX_RESPONSE_BYTES = 2 * 1024 * 1024

AUDITED_EXTERNAL_REDIRECTS = {
    "https://www.argiro.gr/recipe/bides-me-kima-fakes-kai-giaourti/": {
        "finalUrl": "https://www.argiro.gr/recipe-category/almira/zymarika/",
        "finalStatus": 200,
    },
    "https://www.argiro.gr/recipe/brioche-gemista-me-sokolata/": {
        "finalUrl": "https://www.argiro.gr/basic-ingredients/",
        "finalStatus": 200,
    },
    "https://www.argiro.gr/recipe/galopoula-rolo/": {
        "finalUrl": "https://www.argiro.gr/basic-ingredients/",
        "finalStatus": 200,
    },
    "https://www.argiro.gr/recipe/koulouria-rodou/": {
        "finalUrl": "https://www.argiro.gr/basic-ingredients/",
        "finalStatus": 200,
    },
    "https://www.argiro.gr/recipe/mydia-sto-fourno-tragani-krousta/": {
        "finalUrl": "https://www.argiro.gr/",
        "finalStatus": 200,
    },
}

AUDITED_NON_GREEK_STUBS = {
    "https://www.argiro.gr/recipe/greek-rice-pudding/": "20817",
    "https://www.argiro.gr/recipe/lemon-cake/": "20869",
    "https://www.argiro.gr/recipe/milk-pie-without-phyllo/": "20816",
}

AUDITED_VIDEO_TIPS_ONLY = {
    "https://www.argiro.gr/recipe/giaourtoglyko-apo-tin-argiro/": "20573",
}

AUDITED_CANONICAL_ALIASES = {
    "https://www.argiro.gr/recipe/cheesecake-me-giaourti/": {
        "canonicalUrl": "https://www.argiro.gr/recipe/cheesecake-me-zaxarouxo/",
        "providerRecipeId": "17290",
    },
    "https://www.argiro.gr/recipe/afrati-tyropita-xoris-fyllo/": {
        "canonicalUrl": "https://www.argiro.gr/recipe/efkoli-tyropita-xoris-fyllo/",
        "providerRecipeId": "17572",
    },
    "https://www.argiro.gr/recipe/kidonia-psita-me-kanelogarifala-kai-koniak/": {
        "canonicalUrl": "https://www.argiro.gr/recipe/kudonia-psita/",
        "providerRecipeId": "17679",
    },
    "https://www.argiro.gr/recipe/tragana-loukoumadakia-siropiasta/": {
        "canonicalUrl": "https://www.argiro.gr/recipe/loukoumades-tis-tempelas/",
        "providerRecipeId": "17477",
    },
    "https://www.argiro.gr/recipe/mprizolakia-choirina-sto-tigani/": {
        "canonicalUrl": "https://www.argiro.gr/recipe/mprizolakia-me-balsamiko-xidi-kai-karamelomena-laxanika/",
        "providerRecipeId": "16201",
    },
    "https://www.argiro.gr/recipe/ogkraten/": {
        "canonicalUrl": "https://www.argiro.gr/recipe/ogkraten-lachanikon/",
        "providerRecipeId": "21027",
    },
    "https://www.argiro.gr/recipe/efkolo-psomi-choris-magia-choris-alevri/": {
        "canonicalUrl": "https://www.argiro.gr/recipe/psomi-choris-glouteni/",
        "providerRecipeId": "21124",
    },
    "https://www.argiro.gr/recipe/keik-tiramisou/": {
        "canonicalUrl": "https://www.argiro.gr/recipe/tiramisou-me-pantespani/",
        "providerRecipeId": "17801",
    },
}

# This stale sitemap URL redirects to a different surviving recipe.  The live
# provider category cards prove that the source and target have different
# titles, images, facets, and video state, so coalescing it as an alias would
# silently substitute unrelated content.  Keep it as its own exact exclusion.
AUDITED_INTERNAL_STALE_REDIRECTS = {
    "https://www.argiro.gr/recipe/pitsa-special-ton-paidion/": {
        "finalUrl": "https://www.argiro.gr/recipe/zimi-gia-pitsa-eukoli/",
        "finalStatus": 200,
    },
}

PARSER_CONTRACT_FILENAMES = (
    "argiro_schema.py",
    "full_schema.py",
    "helpers.py",
    "providers.py",
)


class ArgiroCrawlError(RuntimeError):
    pass


class IncompleteArgiroCrawl(ArgiroCrawlError):
    def __init__(self, failures: list[dict[str, str]]) -> None:
        self.failures = failures
        super().__init__(f"Argiro catalog is incomplete ({len(failures)} failures)")


@dataclass(frozen=True)
class DiscoveredRecipe:
    source_url: str
    last_modified: str


@dataclass(frozen=True)
class DiscoveryResult:
    recipes: dict[str, DiscoveredRecipe]
    recipe_sitemaps: int
    declared_entries: int
    duplicate_entries: int


def canonical_site_url(value: str, *, upgrade_http: bool = False) -> str:
    parts = urlsplit(value.strip())
    host = (parts.hostname or "").casefold().rstrip(".")
    valid_scheme = parts.scheme.casefold() == "https" or (
        upgrade_http and parts.scheme.casefold() == "http"
    )
    if (
        not valid_scheme
        or host not in ALLOWED_HOSTS
        or parts.username
        or parts.password
        or parts.port not in (None, 80, 443)
    ):
        raise ArgiroCrawlError(f"refusing non-approved URL: {value}")
    # RFC 3986 percent escapes are case-insensitive.  ``requests`` normalizes
    # them to uppercase in ``response.url`` while the Argiro sitemap currently
    # contains at least one lowercase escape.  Normalize only the escape hex
    # digits so a byte-identical recipe path cannot be mistaken for an alias.
    path = re.sub(
        r"%[0-9a-fA-F]{2}",
        lambda match: match.group(0).upper(),
        parts.path,
    )
    return urlunsplit(("https", "www.argiro.gr", path, parts.query, ""))


def canonical_recipe_location(value: str) -> str | None:
    try:
        canonical = canonical_site_url(value, upgrade_http=True)
    except ArgiroCrawlError:
        return None
    parts = urlsplit(canonical)
    if parts.query or not RECIPE_PATH_RE.fullmatch(parts.path):
        return None
    return urlunsplit(("https", "www.argiro.gr", parts.path.rstrip("/") + "/", "", ""))


class RateLimiter:
    def __init__(self, interval_seconds: float = MINIMUM_DELAY_SECONDS) -> None:
        self.interval_seconds = max(MINIMUM_DELAY_SECONDS, float(interval_seconds))
        self.last_request_at: float | None = None

    def wait(self) -> None:
        if self.last_request_at is not None:
            remaining = self.interval_seconds - (time.monotonic() - self.last_request_at)
            if remaining > 0:
                time.sleep(remaining)
        self.last_request_at = time.monotonic()


class ArgiroHttpClient:
    def __init__(
        self,
        *,
        session: requests.Session | None = None,
        min_delay_seconds: float = MINIMUM_DELAY_SECONDS,
        timeout_seconds: float = 30.0,
        max_retries: int = 5,
    ) -> None:
        self.session = session or requests.Session()
        self.session.headers.update({"User-Agent": USER_AGENT, "Accept": "text/html, application/xml, text/plain"})
        self.rate_limiter = RateLimiter(min_delay_seconds)
        self.timeout_seconds = timeout_seconds
        self.max_retries = max(1, max_retries)

    @staticmethod
    def _retry_after(value: str | None, fallback: float) -> float:
        if not value:
            return fallback
        try:
            return max(0.0, float(value))
        except ValueError:
            try:
                return max(0.0, (parsedate_to_datetime(value) - datetime.now(timezone.utc)).total_seconds())
            except (TypeError, ValueError):
                return fallback

    def get(
        self,
        url: str,
        *,
        robots: RobotFileParser | None = None,
        allowed_statuses: set[int] | None = None,
    ) -> requests.Response:
        current = canonical_site_url(url)
        allowed = allowed_statuses or {200}
        for _redirect in range(6):
            if robots is not None and not robots.can_fetch(USER_AGENT, current):
                raise ArgiroCrawlError(f"robots.txt does not allow {current}")
            response = None
            for attempt in range(self.max_retries):
                self.rate_limiter.wait()
                try:
                    response = self.session.get(current, allow_redirects=False, timeout=self.timeout_seconds)
                except requests.RequestException:
                    if attempt + 1 >= self.max_retries:
                        raise
                    time.sleep(min(30.0, 2**attempt))
                    continue
                if len(response.content) > MAX_RESPONSE_BYTES:
                    raise ArgiroCrawlError(f"response exceeds {MAX_RESPONSE_BYTES} bytes: {current}")
                if response.status_code in RETRY_STATUSES and attempt + 1 < self.max_retries:
                    time.sleep(self._retry_after(response.headers.get("Retry-After"), min(60.0, 2**attempt)))
                    continue
                break
            assert response is not None
            if response.is_redirect or response.is_permanent_redirect:
                location = response.headers.get("Location")
                if not location:
                    raise ArgiroCrawlError("redirect response has no Location")
                current = canonical_site_url(urljoin(current, location), upgrade_http=True)
                continue
            if response.status_code not in allowed:
                raise ArgiroCrawlError(f"HTTP {response.status_code} for {current}")
            return response
        raise ArgiroCrawlError(f"too many redirects for {url}")


def load_robots(client: ArgiroHttpClient) -> RobotFileParser:
    response = client.get(ROBOTS_URL, allowed_statuses={200, 404})
    robots = RobotFileParser()
    robots.set_url(ROBOTS_URL)
    robots.parse(response.text.splitlines() if response.status_code == 200 else [])
    return robots


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _child_text(node: ET.Element, name: str) -> str:
    return next(((child.text or "").strip() for child in node if _local_name(child.tag) == name), "")


def parse_sitemap(xml_text: str) -> tuple[list[DiscoveredRecipe], list[str], int]:
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as exc:
        raise ArgiroCrawlError(f"invalid sitemap XML: {exc}") from exc
    recipes: list[DiscoveredRecipe] = []
    children: list[str] = []
    declared = 0
    if _local_name(root.tag) == "sitemapindex":
        for node in root:
            if _local_name(node.tag) != "sitemap":
                continue
            raw = _child_text(node, "loc")
            try:
                location = canonical_site_url(raw, upgrade_http=True)
            except ArgiroCrawlError:
                continue
            if RECIPE_SITEMAP_RE.fullmatch(Path(urlsplit(location).path).name):
                children.append(location)
    elif _local_name(root.tag) == "urlset":
        for node in root:
            if _local_name(node.tag) != "url":
                continue
            declared += 1
            location = canonical_recipe_location(_child_text(node, "loc"))
            if location:
                recipes.append(DiscoveredRecipe(location, _child_text(node, "lastmod")))
    else:
        raise ArgiroCrawlError("unexpected sitemap root element")
    return recipes, children, declared


def discover_recipes(
    client: ArgiroHttpClient,
    robots: RobotFileParser,
    sitemap_url: str = SITEMAP_URL,
) -> DiscoveryResult:
    index_response = client.get(canonical_site_url(sitemap_url), robots=robots)
    index_recipes, children, index_declared = parse_sitemap(index_response.text)
    if index_recipes or index_declared or not children:
        raise ArgiroCrawlError("Argiro sitemap index contains no recipe sitemaps")
    recipes: dict[str, DiscoveredRecipe] = {}
    duplicates = 0
    declared = 0
    for child in sorted(set(children)):
        response = client.get(child, robots=robots)
        parsed, nested, child_declared = parse_sitemap(response.text)
        if nested:
            raise ArgiroCrawlError("nested recipe sitemap indexes are not supported")
        declared += child_declared
        for candidate in parsed:
            if candidate.source_url in recipes:
                duplicates += 1
                old = recipes[candidate.source_url]
                recipes[candidate.source_url] = DiscoveredRecipe(
                    candidate.source_url,
                    max(old.last_modified, candidate.last_modified),
                )
            else:
                recipes[candidate.source_url] = candidate
    if not recipes or len(recipes) + duplicates != declared:
        raise ArgiroCrawlError("recipe sitemap contains invalid or non-canonical entries")
    return DiscoveryResult(recipes, len(set(children)), declared, duplicates)


class CheckpointStore:
    def __init__(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        self.connection = sqlite3.connect(path)
        self.connection.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        self.connection.execute(
            "CREATE TABLE IF NOT EXISTS records (url TEXT PRIMARY KEY, lastmod TEXT NOT NULL, payload TEXT NOT NULL)"
        )
        self.connection.commit()

    def prepare(self, run_key: str, *, resume: bool) -> None:
        row = self.connection.execute("SELECT value FROM meta WHERE key='runKey'").fetchone()
        if not resume or not row or row[0] != run_key:
            self.connection.execute("DELETE FROM records")
            self.connection.execute(
                "INSERT INTO meta(key,value) VALUES('runKey',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                (run_key,),
            )
            self.connection.commit()

    def get(self, url: str, lastmod: str) -> Mapping[str, object] | None:
        # Sitemap rows without lastmod cannot prove that cached HTML is still
        # current, so they must be fetched on every run.
        if not lastmod.strip():
            return None
        row = self.connection.execute(
            "SELECT payload FROM records WHERE url=? AND lastmod=?", (url, lastmod)
        ).fetchone()
        value = json.loads(row[0]) if row else None
        return value if isinstance(value, Mapping) else None

    def put(self, url: str, lastmod: str, record: Mapping[str, object]) -> None:
        payload = json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)
        self.connection.execute(
            "INSERT INTO records(url,lastmod,payload) VALUES(?,?,?) ON CONFLICT(url) DO UPDATE SET lastmod=excluded.lastmod,payload=excluded.payload",
            (url, lastmod, payload),
        )
        self.connection.commit()

    def close(self) -> None:
        self.connection.close()


def _hash(value: object) -> str:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def parser_contract_hash() -> str:
    """Hash every source module that can change Argiro normalized output."""
    digest = hashlib.sha256()
    directory = Path(__file__).resolve().parent
    for filename in PARSER_CONTRACT_FILENAMES:
        payload = (directory / filename).read_bytes()
        digest.update(filename.encode("utf-8"))
        digest.update(b"\0")
        digest.update(len(payload).to_bytes(8, "big"))
        digest.update(payload)
    return digest.hexdigest()


def checkpoint_run_key() -> str:
    """Bind checkpoints/manifests to parser code and every audited exception."""
    return _hash({
        "schema": ARGIRO_DETAIL_SCHEMA_VERSION,
        "parserContractHash": parser_contract_hash(),
        "externalRedirectAllowlist": AUDITED_EXTERNAL_REDIRECTS,
        "nonGreekStubAllowlist": AUDITED_NON_GREEK_STUBS,
        "canonicalAliasAllowlist": AUDITED_CANONICAL_ALIASES,
        "internalStaleRedirectAllowlist": AUDITED_INTERNAL_STALE_REDIRECTS,
        "videoTipsOnlyAllowlist": AUDITED_VIDEO_TIPS_ONLY,
    })


def validate_checkpoint_record(
    record: Mapping[str, object],
    sitemap_url: str,
    sitemap_last_modified: str,
) -> dict[str, object]:
    """Fail closed before any cached provider payload is reused."""
    value = dict(record)
    ensure_full_record(value)
    expected_url = canonical_recipe_location(sitemap_url)
    provider_recipe_id = str(value.get("providerRecipeId") or "")
    if (
        expected_url is None
        or value.get("sourceKey") != ARGIRO.key
        or value.get("active") is not True
        or canonical_recipe_location(str(value.get("sourceUrl") or "")) != expected_url
        or canonical_recipe_location(str(value.get("canonicalUrl") or "")) != expected_url
        or value.get("sourceUrl") != expected_url
        or value.get("canonicalUrl") != expected_url
        or value.get("sitemapLastModified") != sitemap_last_modified
        or value.get("language") != "el"
        or not provider_recipe_id.isdigit()
        or value.get("id") != recipe_document_id(ARGIRO, provider_recipe_id)
        or canonical_recipe_url(ARGIRO, expected_url, provider_recipe_id) != expected_url
    ):
        raise FullSchemaError(
            "Argiro checkpoint record identity does not match its sitemap URL"
        )
    return value


def _atomic_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2), encoding="utf-8")
    temporary.replace(path)


def _atomic_jsonl(path: Path, records: Iterable[Mapping[str, object]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n")
    temporary.replace(path)


def run_argiro_crawl(
    *,
    client: ArgiroHttpClient,
    robots: RobotFileParser,
    output_path: Path,
    manifest_path: Path,
    failures_path: Path,
    checkpoint_path: Path,
    expected_active_count: int | None = None,
    expected_discovered_count: int | None = None,
    resume: bool = True,
    progress: Callable[[int, int, str], None] | None = None,
    on_failure: Callable[[str, str], None] | None = None,
) -> dict[str, object]:
    discovery = discover_recipes(client, robots)
    if (
        expected_discovered_count is not None
        and len(discovery.recipes) != expected_discovered_count
    ):
        raise ArgiroCrawlError(
            f"discovered sitemap URL count {len(discovery.recipes)} "
            f"!= expected {expected_discovered_count}"
        )
    # Inventory changes are expected quarterly. Individual URL + lastmod keys
    # decide reuse; any parser/schema/taxonomy/provider code change changes this
    # content-derived contract and invalidates all normalized cached records.
    contract_hash = parser_contract_hash()
    run_key = checkpoint_run_key()
    store = CheckpointStore(checkpoint_path)
    records: list[dict[str, object]] = []
    failures: list[dict[str, str]] = []
    external_redirects: list[dict[str, object]] = []
    non_greek_stubs: list[dict[str, object]] = []
    canonical_aliases: list[dict[str, str]] = []
    internal_stale_redirects: list[dict[str, object]] = []
    resumed = 0
    invalidated_checkpoint_records = 0
    try:
        store.prepare(run_key, resume=resume)
        total = len(discovery.recipes)
        for index, item in enumerate(sorted(discovery.recipes.values(), key=lambda value: value.source_url), start=1):
            record: Mapping[str, object] | None = None
            if (
                item.source_url not in AUDITED_EXTERNAL_REDIRECTS
                and item.source_url not in AUDITED_NON_GREEK_STUBS
                and item.source_url not in AUDITED_CANONICAL_ALIASES
                and item.source_url not in AUDITED_INTERNAL_STALE_REDIRECTS
            ):
                cached = store.get(item.source_url, item.last_modified)
                if cached is not None:
                    try:
                        record = validate_checkpoint_record(
                            cached,
                            item.source_url,
                            item.last_modified,
                        )
                    except (FullSchemaError, ValueError):
                        invalidated_checkpoint_records += 1
            try:
                if record is None:
                    response = client.get(item.source_url, robots=robots)
                    final_recipe_url = canonical_recipe_location(response.url)
                    if final_recipe_url is None:
                        observed = {
                            "finalUrl": canonical_site_url(
                                response.url, upgrade_http=True
                            ),
                            "finalStatus": response.status_code,
                        }
                        expected = AUDITED_EXTERNAL_REDIRECTS.get(item.source_url)
                        if expected != observed:
                            raise ArgiroCrawlError(
                                "unapproved non-recipe redirect: "
                                f"{item.source_url} -> {observed}"
                            )
                        external_redirects.append({
                            "sourceUrl": item.source_url,
                            **observed,
                            "reason": "redirected outside /recipe/{slug}/",
                        })
                        if progress:
                            progress(index, total, item.source_url)
                        continue
                    if final_recipe_url != item.source_url:
                        observed_internal_stale = {
                            "finalUrl": final_recipe_url,
                            "finalStatus": response.status_code,
                        }
                        expected_internal_stale = (
                            AUDITED_INTERNAL_STALE_REDIRECTS.get(item.source_url)
                        )
                        if expected_internal_stale == observed_internal_stale:
                            internal_stale_redirects.append({
                                "sourceUrl": item.source_url,
                                **observed_internal_stale,
                                "reason": (
                                    "redirected to a distinct surviving recipe; "
                                    "content substitution is forbidden"
                                ),
                            })
                            if progress:
                                progress(index, total, item.source_url)
                            continue
                        expected_alias = AUDITED_CANONICAL_ALIASES.get(
                            item.source_url
                        )
                        if (
                            expected_alias is None
                            or expected_alias["canonicalUrl"] != final_recipe_url
                            or final_recipe_url not in discovery.recipes
                        ):
                            raise ArgiroCrawlError(
                                "unapproved canonical recipe alias: "
                                f"{item.source_url} -> {final_recipe_url}"
                            )
                        canonical_aliases.append({
                            "sourceUrl": item.source_url,
                            "canonicalUrl": final_recipe_url,
                            "providerRecipeId": expected_alias["providerRecipeId"],
                        })
                        if progress:
                            progress(index, total, item.source_url)
                        continue
                    if item.source_url in AUDITED_EXTERNAL_REDIRECTS:
                        raise ArgiroCrawlError(
                            "audited external redirect unexpectedly became a recipe"
                        )
                    if item.source_url in AUDITED_CANONICAL_ALIASES:
                        raise ArgiroCrawlError(
                            "audited canonical alias unexpectedly stopped redirecting"
                        )
                    if item.source_url in AUDITED_INTERNAL_STALE_REDIRECTS:
                        raise ArgiroCrawlError(
                            "audited internal stale redirect unexpectedly became a recipe"
                        )
                    last_schema_error: FullSchemaError | None = None
                    language_excluded = False
                    for schema_attempt in range(3):
                        try:
                            record = normalize_argiro_page(
                                response.text,
                                source_url=item.source_url,
                                sitemap_last_modified=item.last_modified,
                            )
                            break
                        except ArgiroLanguageError as exc:
                            expected_stub_id = AUDITED_NON_GREEK_STUBS.get(
                                item.source_url
                            )
                            if expected_stub_id != exc.provider_recipe_id:
                                raise
                            non_greek_stubs.append({
                                "sourceUrl": item.source_url,
                                "providerRecipeId": exc.provider_recipe_id,
                                "finalStatus": response.status_code,
                                "reason": exc.reason,
                            })
                            language_excluded = True
                            break
                        except FullSchemaError as exc:
                            last_schema_error = exc
                            if schema_attempt == 2:
                                raise
                            response = client.get(item.source_url, robots=robots)
                            retried_url = canonical_recipe_location(response.url)
                            if retried_url != item.source_url:
                                raise ArgiroCrawlError(
                                    "recipe URL changed during schema retry: "
                                    f"{item.source_url} -> {response.url}"
                                )
                    if language_excluded:
                        if progress:
                            progress(index, total, item.source_url)
                        continue
                    if item.source_url in AUDITED_NON_GREEK_STUBS:
                        raise ArgiroCrawlError(
                            "audited non-Greek stub unexpectedly normalized as Greek"
                        )
                    if record is None and last_schema_error is not None:
                        raise last_schema_error
                    if record is None:
                        raise FullSchemaError("Argiro normalization returned no record")
                    record = validate_checkpoint_record(
                        record,
                        item.source_url,
                        item.last_modified,
                    )
                    store.put(item.source_url, item.last_modified, record)
                else:
                    resumed += 1
                records.append(dict(record))
            except (ArgiroCrawlError, FullSchemaError, requests.RequestException, ValueError) as exc:
                failures.append({"sourceUrl": item.source_url, "error": str(exc)})
                _atomic_json(failures_path, {
                    "complete": False,
                    "failedRecipeCount": len(failures),
                    "failures": failures,
                })
                if on_failure:
                    on_failure(item.source_url, str(exc))
            if progress:
                progress(index, total, item.source_url)
    finally:
        store.close()
    if failures:
        _atomic_json(failures_path, {
            "complete": False,
            "failedRecipeCount": len(failures),
            "failures": failures,
        })
        raise IncompleteArgiroCrawl(failures)
    external_redirects.sort(key=lambda item: str(item["sourceUrl"]))
    non_greek_stubs.sort(key=lambda item: str(item["sourceUrl"]))
    canonical_aliases.sort(key=lambda item: item["sourceUrl"])
    internal_stale_redirects.sort(key=lambda item: str(item["sourceUrl"]))
    if {item["sourceUrl"] for item in external_redirects} != set(
        AUDITED_EXTERNAL_REDIRECTS
    ):
        raise ArgiroCrawlError(
            "observed external redirects do not match the audited allowlist"
        )
    if {item["sourceUrl"] for item in non_greek_stubs} != set(
        AUDITED_NON_GREEK_STUBS
    ):
        raise ArgiroCrawlError(
            "observed non-Greek stubs do not match the audited allowlist"
        )
    if {item["sourceUrl"] for item in canonical_aliases} != set(
        AUDITED_CANONICAL_ALIASES
    ):
        raise ArgiroCrawlError(
            "observed canonical aliases do not match the audited allowlist"
        )
    if {item["sourceUrl"] for item in internal_stale_redirects} != set(
        AUDITED_INTERNAL_STALE_REDIRECTS
    ):
        raise ArgiroCrawlError(
            "observed internal stale redirects do not match the audited allowlist"
        )

    document_ids = [str(record["id"]) for record in records]
    canonical_urls = [str(record["canonicalUrl"]) for record in records]
    accounted = (
        len(records)
        + len(canonical_aliases)
        + len(external_redirects)
        + len(non_greek_stubs)
        + len(internal_stale_redirects)
    )
    if (
        accounted != len(discovery.recipes)
        or len(set(document_ids)) != len(document_ids)
        or len(set(canonical_urls)) != len(canonical_urls)
    ):
        raise ArgiroCrawlError(
            "discovery is not exactly accounted by unique canonical Greek "
            "recipes, aliases, external redirects, and non-Greek stubs"
        )
    records_by_url = {
        str(record["canonicalUrl"]): record for record in records
    }
    for alias in canonical_aliases:
        target = records_by_url.get(alias["canonicalUrl"])
        if (
            target is None
            or str(target.get("providerRecipeId")) != alias["providerRecipeId"]
            or str(target.get("id"))
            != recipe_document_id(ARGIRO, alias["providerRecipeId"])
        ):
            raise ArgiroCrawlError(
                f"canonical alias target identity is missing: {alias['sourceUrl']}"
            )
    for record in records:
        ingredient_count = sum(
            len(section.get("ingredients", []))
            for section in record.get("ingredientSections", [])
            if isinstance(section, Mapping)
        )
        method_count = sum(
            len(section.get("steps", []))
            for section in record.get("methodSections", [])
            if isinstance(section, Mapping)
        )
        if ingredient_count < 1:
            raise ArgiroCrawlError(
                f"canonical Greek recipe has no ingredients: {record['canonicalUrl']}"
            )
        if method_count < 1:
            expected_video_only_id = AUDITED_VIDEO_TIPS_ONLY.get(
                str(record["canonicalUrl"])
            )
            if not (
                expected_video_only_id == str(record.get("providerRecipeId"))
                and record.get("videoUrls")
                and record.get("tips")
            ):
                raise ArgiroCrawlError(
                    "canonical Greek recipe has no method and is not the "
                    f"audited video/tips-only recipe: {record['canonicalUrl']}"
                )
    if expected_active_count is not None and len(records) != expected_active_count:
        raise ArgiroCrawlError(
            f"canonical Greek recipe count {len(records)} "
            f"!= expected {expected_active_count}"
        )
    exclusions: list[dict[str, object]] = [
        {"kind": "externalRedirect", **item} for item in external_redirects
    ] + [
        {"kind": "nonGreekStub", **item} for item in non_greek_stubs
    ] + [
        {"kind": "internalStaleRedirect", **item}
        for item in internal_stale_redirects
    ]
    exclusions.sort(key=lambda item: str(item["sourceUrl"]))
    sizes = [document_sizes(record) for record in records]
    now = datetime.now(timezone.utc).isoformat()
    manifest: dict[str, object] = {
        "complete": True,
        "sourceKey": "argiro",
        "detailSchemaVersion": ARGIRO_DETAIL_SCHEMA_VERSION,
        "parserContractHash": contract_hash,
        "checkpointRunKey": run_key,
        "failedRecipeCount": 0,
        "recipeSitemapCount": discovery.recipe_sitemaps,
        "declaredRecipeEntryCount": discovery.declared_entries,
        "duplicateRecipeEntryCount": discovery.duplicate_entries,
        "discoveredRecipeUrlCount": len(discovery.recipes),
        "canonicalGreekRecipeCount": len(records),
        "canonicalAliasCount": len(canonical_aliases),
        "canonicalAliases": canonical_aliases,
        "canonicalAliasesHash": _hash(canonical_aliases),
        "internalStaleRedirectExclusionCount": len(internal_stale_redirects),
        "internalStaleRedirectExclusions": internal_stale_redirects,
        "internalStaleRedirectExclusionsHash": _hash(internal_stale_redirects),
        "externalRedirectExclusionCount": len(external_redirects),
        "externalRedirectExclusions": external_redirects,
        "externalRedirectExclusionsHash": _hash(external_redirects),
        "nonGreekStubExclusionCount": len(non_greek_stubs),
        "nonGreekStubExclusions": non_greek_stubs,
        "nonGreekStubExclusionsHash": _hash(non_greek_stubs),
        "excludedRecipeUrlCount": len(exclusions),
        "excludedRecipeUrls": exclusions,
        "excludedRecipeUrlsHash": _hash(exclusions),
        "discoveredActiveRecipeCount": len(records),
        "outputRecipeCount": len(records),
        "activeRecipeCount": len(records),
        "detailRecipeCount": len(records),
        "sourcePayloadCount": len(records),
        "activeIdsHash": _hash(sorted(document_ids)),
        "catalogHash": _hash(sorted(records, key=lambda record: str(record["id"]))),
        "summaryHash": collection_hash(records, firestore_recipe_payload),
        "detailHash": collection_hash(records, firestore_detail_payload),
        "sourcePayloadHash": collection_hash(records, firestore_source_payload),
        "maximumSummaryDocumentBytes": max(size[0] for size in sizes),
        "maximumDetailDocumentBytes": max(size[1] for size in sizes),
        "maximumSourcePayloadDocumentBytes": max(size[2] for size in sizes),
        "oversizedDocumentCount": 0,
        "checkpointResumedRecipeCount": resumed,
        "checkpointInvalidatedRecipeCount": invalidated_checkpoint_records,
        "generatedAt": now,
        "catalogFile": output_path.name,
    }
    # Importer hash is authoritative and includes its final normalization.  The
    # crawler's records already satisfy that schema, so import lazily to avoid a
    # module cycle at startup.
    if __package__ in (None, ""):
        from import_catalog import compute_catalog_hash  # type: ignore[import-not-found]
    else:
        from .import_catalog import compute_catalog_hash
    manifest["catalogHash"] = compute_catalog_hash(records)  # type: ignore[arg-type]
    _atomic_jsonl(output_path, records)
    _atomic_json(manifest_path, manifest)
    _atomic_json(failures_path, {
        "complete": True,
        "failedRecipeCount": 0,
        "failures": [],
        "canonicalAliasCount": len(canonical_aliases),
        "canonicalAliases": canonical_aliases,
        "excludedRecipeUrlCount": len(exclusions),
        "excludedRecipeUrls": exclusions,
    })
    return manifest


def _positive_int(value: str) -> int:
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be positive")
    return parsed


def build_parser() -> argparse.ArgumentParser:
    output_dir = Path(__file__).resolve().parent / "output"
    parser = argparse.ArgumentParser(description="Crawl authorized Argiro Greek recipe JSON-LD")
    parser.add_argument("--i-have-argiro-permission", action="store_true")
    parser.add_argument("--output", type=Path, default=output_dir / "argiro-greek-full.jsonl")
    parser.add_argument("--manifest", type=Path, default=output_dir / "argiro-greek-full.manifest.json")
    parser.add_argument("--failures", type=Path, default=output_dir / "argiro-greek-full.failures.json")
    parser.add_argument("--checkpoint", type=Path, default=output_dir / ".argiro-greek-full.checkpoint.sqlite3")
    parser.add_argument(
        "--expected-discovered-count",
        type=_positive_int,
        help="Optional exact count of unique strict sitemap recipe URLs.",
    )
    parser.add_argument(
        "--expected-active-count",
        type=_positive_int,
        help="Optional exact count of canonical Greek output recipes after audited aliases/exclusions.",
    )
    parser.add_argument("--no-resume", action="store_true")
    parser.add_argument("--min-delay-seconds", type=float, default=MINIMUM_DELAY_SECONDS)
    parser.add_argument("--timeout-seconds", type=float, default=30.0)
    parser.add_argument("--max-retries", type=_positive_int, default=5)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if not args.i_have_argiro_permission:
        print(
            "error: written Argiro authorization is required; pass --i-have-argiro-permission only after obtaining it",
            file=sys.stderr,
        )
        return 2
    if args.timeout_seconds <= 0 or args.min_delay_seconds < MINIMUM_DELAY_SECONDS:
        print("error: timeout must be positive and delay must be at least 1 second", file=sys.stderr)
        return 2
    client = ArgiroHttpClient(
        min_delay_seconds=args.min_delay_seconds,
        timeout_seconds=args.timeout_seconds,
        max_retries=args.max_retries,
    )

    def progress(done: int, total: int, source: str) -> None:
        if done == 1 or done == total or done % 100 == 0:
            print(
                json.dumps({"progress": done, "total": total, "source": source}),
                file=sys.stderr,
                flush=True,
            )

    def report_failure(source: str, error: str) -> None:
        print(
            json.dumps({"failure": source, "error": error}, ensure_ascii=False),
            file=sys.stderr,
            flush=True,
        )

    try:
        robots = load_robots(client)
        manifest = run_argiro_crawl(
            client=client,
            robots=robots,
            output_path=args.output,
            manifest_path=args.manifest,
            failures_path=args.failures,
            checkpoint_path=args.checkpoint,
            expected_active_count=args.expected_active_count,
            expected_discovered_count=args.expected_discovered_count,
            resume=not args.no_resume,
            progress=progress,
            on_failure=report_failure,
        )
    except (ArgiroCrawlError, FullSchemaError, requests.RequestException, OSError, sqlite3.Error) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(manifest, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
