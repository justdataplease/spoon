"""Permission-gated, resumable Gastronomos sitemap/JSON-LD crawler.

Importing this module never touches the network.  A real run requires the
source-specific acknowledgement flag, honours robots.txt for the transparent
Peltes Spoon user agent, applies a minimum delay, and publishes output only
when every discovered URL is accounted for without failures.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sqlite3
import sys
import threading
import time
import xml.etree.ElementTree as ET
from collections.abc import Callable, Iterable, Mapping
from concurrent.futures import Future, ThreadPoolExecutor
from dataclasses import dataclass
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path
from urllib.parse import urljoin, urlsplit, urlunsplit
from urllib.robotparser import RobotFileParser

import requests

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from full_schema import (  # type: ignore[import-not-found]
        GASTRONOMOS_DETAIL_SCHEMA_VERSION,
        FullSchemaError,
        collection_hash,
        document_sizes,
        ensure_full_record,
        firestore_detail_payload,
        firestore_recipe_payload,
        firestore_source_payload,
    )
    from gastronomos_schema import (  # type: ignore[import-not-found]
        GastronomosLanguageError,
        normalize_gastronomos_page,
    )
    from providers import (  # type: ignore[import-not-found]
        GASTRONOMOS,
        canonical_recipe_url,
        recipe_document_id,
    )
else:
    from .full_schema import (
        GASTRONOMOS_DETAIL_SCHEMA_VERSION,
        FullSchemaError,
        collection_hash,
        document_sizes,
        ensure_full_record,
        firestore_detail_payload,
        firestore_recipe_payload,
        firestore_source_payload,
    )
    from .gastronomos_schema import (
        GastronomosLanguageError,
        normalize_gastronomos_page,
    )
    from .providers import GASTRONOMOS, canonical_recipe_url, recipe_document_id


SITE_ORIGIN = "https://www.gastronomos.gr"
ROBOTS_URL = f"{SITE_ORIGIN}/robots.txt"
SITEMAP_URL = f"{SITE_ORIGIN}/sitemap_index.xml"
USER_AGENT = "PeltesSpoonRecipeImporter/1.0 (+mailto:hey@spoon.gr)"
ALLOWED_HOSTS = {"gastronomos.gr", "www.gastronomos.gr"}
RECIPE_PATH_RE = re.compile(r"^/syntagh/(?P<slug>[^/?#]+)/(?P<id>\d+)/?$")
RECIPE_SITEMAP_RE = re.compile(r"^recipe-sitemap(?:\d+)?\.xml$", re.I)
RETRY_STATUSES = {408, 425, 429, 500, 502, 503, 504}
MINIMUM_DELAY_SECONDS = 1.0
MAX_RESPONSE_BYTES = 4 * 1024 * 1024

# Exact, independently audited exceptions may be added after a diagnostic
# inventory.  Generic redirect, language, or missing-content exclusions are
# intentionally impossible: an unknown drift fails the entire run closed.
AUDITED_EXTERNAL_REDIRECTS: dict[str, dict[str, object]] = {
    "https://www.gastronomos.gr/syntagh/nero-gia-apotoxinosi/51467/": {
        "finalUrl": (
            "https://www.gastronomos.gr/syntages/symvoules/"
            "nero-gia-apotoxinosi/95913/"
        ),
        "finalStatus": 200,
    },
    "https://www.gastronomos.gr/syntagh/soypa-aygokommeni/52638/": {
        "finalUrl": "https://www.gastronomos.gr/",
        "finalStatus": 200,
    },
    "https://www.gastronomos.gr/syntagh/ta-kokteil-toy-mellontos/51777/": {
        "finalUrl": (
            "https://www.gastronomos.gr/oinos-pota/pota/"
            "ta-kokteil-toy-mellontos/95401/"
        ),
        "finalStatus": 200,
    },
}
AUDITED_NON_GREEK_STUBS: dict[str, str] = {
    (
        "https://www.gastronomos.gr/syntagh/"
        "moussaka-prepared-asia-minor-style-with-tomato-and-kasseri-cheese-"
        "and-no-bechamel/164705/"
    ): "164705",
}
AUDITED_CANONICAL_ALIASES: dict[str, dict[str, str]] = {
    "https://www.gastronomos.gr/syntagh/chaloymo-pitakia/50895/": {
        "canonicalUrl": (
            "https://www.gastronomos.gr/syntagh/"
            "kypriaka-chaloymopitakia-me-dyosmo-kai-portokalenia-zymi/249974/"
        ),
        "providerRecipeId": "249974",
    },
    (
        "https://www.gastronomos.gr/syntagh/"
        "kotopoylo-foyrnoy-klasiko-kai-kalokairino-2/270507/"
    ): {
        "canonicalUrl": (
            "https://www.gastronomos.gr/syntagh/"
            "kotopoylo-foyrnoy-klasiko-kai-kalokairino/269126/"
        ),
        "providerRecipeId": "269126",
    },
    (
        "https://www.gastronomos.gr/syntagh/"
        "krya-pantzarosoypa-se-sfinaki-me-giaoyrti/51607/"
    ): {
        "canonicalUrl": (
            "https://www.gastronomos.gr/syntagh/"
            "kry-a-soy-pa-sto-poti-ri-me-pantza-ria-kai-giaoy-rti/131465/"
        ),
        "providerRecipeId": "131465",
    },
    (
        "https://www.gastronomos.gr/syntagh/"
        "melitzanes-gioyvetsi-me-mozzarella-burrata-kai-saltsa-ntomatas-"
        "piperias-florinis/209330/"
    ): {
        "canonicalUrl": (
            "https://www.gastronomos.gr/syntagh/"
            "gioyvetsi-me-melitzanes-mozzarella-burrata-kai-saltsa-ntomatas-"
            "piperias-florinis/207162/"
        ),
        "providerRecipeId": "207162",
    },
    "https://www.gastronomos.gr/syntagh/melopita/52805/": {
        "canonicalUrl": (
            "https://www.gastronomos.gr/syntagh/melopita-mykonoy/124447/"
        ),
        "providerRecipeId": "124447",
    },
    (
        "https://www.gastronomos.gr/syntagh/"
        "moscharisio-rolo-me-agria-manitaria-thymari-kai-dentrolivano/231261/"
    ): {
        "canonicalUrl": (
            "https://www.gastronomos.gr/syntagh/"
            "moscharisio-rolo-me-agria-manitaria/160128/"
        ),
        "providerRecipeId": "160128",
    },
    "https://www.gastronomos.gr/syntagh/pasta-froytoy-flora/51935/": {
        "canonicalUrl": (
            "https://www.gastronomos.gr/syntagh/pa-sta-froy-toy-flo-ra/98166/"
        ),
        "providerRecipeId": "98166",
    },
    (
        "https://www.gastronomos.gr/syntagh/"
        "soysame-nio-saragli-nistisimo-apo-ton-evro/99603/"
    ): {
        "canonicalUrl": (
            "https://www.gastronomos.gr/syntagh/"
            "soysamenio-saragli-apo-ton-evro/189302/"
        ),
        "providerRecipeId": "189302",
    },
    "https://www.gastronomos.gr/syntagh/tom-yum-taylandeziki-soypa/52219/": {
        "canonicalUrl": (
            "https://www.gastronomos.gr/syntagh/soypa-tom-yum/106238/"
        ),
        "providerRecipeId": "106238",
    },
    "https://www.gastronomos.gr/syntagh/tom-yum/83875/": {
        "canonicalUrl": (
            "https://www.gastronomos.gr/syntagh/soypa-tom-yum/106238/"
        ),
        "providerRecipeId": "106238",
    },
}
AUDITED_SOURCE_INCOMPLETE_PAGES: dict[str, dict[str, object]] = {
    (
        "https://www.gastronomos.gr/syntagh/"
        "6-grigores-saltses-gia-fileto-moscharioy/50725/"
    ): {
        "providerRecipeId": "50725",
        "expectedError": "Gastronomos JSON-LD Recipe has no ingredients",
        "finalStatus": 200,
        "reason": (
            "multi-recipe editorial has no safely separable canonical recipe identity"
        ),
    },
    "https://www.gastronomos.gr/syntagh/cheesecake/50752/": {
        "providerRecipeId": "50752",
        "expectedError": "Gastronomos JSON-LD Recipe has no ingredients",
        "finalStatus": 200,
        "reason": "source omits every ingredient",
    },
    (
        "https://www.gastronomos.gr/syntagh/"
        "dynamotiko-proino-me-proionta-giotis/52432/"
    ): {
        "providerRecipeId": "52432",
        "expectedError": "Gastronomos JSON-LD Recipe has no ingredients",
        "finalStatus": 200,
        "reason": "editorial page has no extractable single recipe",
    },
    (
        "https://www.gastronomos.gr/syntagh/"
        "i-mageiriki-ginetai-apli-ypothesi/52428/"
    ): {
        "providerRecipeId": "52428",
        "expectedError": "Gastronomos JSON-LD Recipe has no ingredients",
        "finalStatus": 200,
        "reason": "editorial page has no extractable single recipe",
    },
    "https://www.gastronomos.gr/syntagh/mezedes-gia-mpira/50963/": {
        "providerRecipeId": "50963",
        "expectedError": "Gastronomos JSON-LD Recipe has no ingredients",
        "finalStatus": 200,
        "reason": "editorial page has no extractable single recipe",
    },
    "https://www.gastronomos.gr/syntagh/mpoyfes-me-ta-ola-toy/52355/": {
        "providerRecipeId": "52355",
        "expectedError": "Gastronomos JSON-LD Recipe has no ingredients",
        "finalStatus": 200,
        "reason": "editorial page has no extractable single recipe",
    },
    (
        "https://www.gastronomos.gr/syntagh/"
        "negroni-apo-ti-amp-8230-samo/51850/"
    ): {
        "providerRecipeId": "51850",
        "expectedError": "Gastronomos JSON-LD Recipe has no ingredients",
        "finalStatus": 200,
        "reason": (
            "multi-recipe editorial has no safely separable canonical recipe identity"
        ),
    },
    (
        "https://www.gastronomos.gr/syntagh/"
        "paprika-aloifi-tis-marias-se-5/51074/"
    ): {
        "providerRecipeId": "51074",
        "expectedError": "Gastronomos JSON-LD Recipe has no instructions",
        "finalStatus": 200,
        "reason": "source omits every preparation instruction",
    },
    "https://www.gastronomos.gr/syntagh/pascha-en-tachei/51570/": {
        "providerRecipeId": "51570",
        "expectedError": "Gastronomos JSON-LD Recipe has no ingredients",
        "finalStatus": 200,
        "reason": "editorial page has no extractable single recipe",
    },
    "https://www.gastronomos.gr/syntagh/sto-trapezi-tis-lampris/53124/": {
        "providerRecipeId": "53124",
        "expectedError": "Gastronomos JSON-LD Recipe has no ingredients",
        "finalStatus": 200,
        "reason": "editorial page has no extractable single recipe",
    },
    (
        "https://www.gastronomos.gr/syntagh/"
        "ti-na-pioyme-otan-den-pinoyme/50137/"
    ): {
        "providerRecipeId": "50137",
        "expectedError": "Gastronomos JSON-LD Recipe has no ingredients",
        "finalStatus": 200,
        "reason": "editorial page has no extractable single recipe",
    },
    (
        "https://www.gastronomos.gr/syntagh/"
        "to-kalokairi-erchetai-mazi-me-lachtarista-glyka-choris-zachari/"
        "51275/"
    ): {
        "providerRecipeId": "51275",
        "expectedError": "Gastronomos JSON-LD Recipe has no ingredients",
        "finalStatus": 200,
        "reason": "editorial page has no extractable single recipe",
    },
    "https://www.gastronomos.gr/syntagh/to-psito-tis-kyriakis/50275/": {
        "providerRecipeId": "50275",
        "expectedError": "Gastronomos JSON-LD Recipe has no ingredients",
        "finalStatus": 200,
        "reason": "editorial page has no extractable single recipe",
    },
    (
        "https://www.gastronomos.gr/syntagh/"
        "ygieini-diatrofi-me-yperocha-glyka-me-geysi-alla-choris-zachari/"
        "51278/"
    ): {
        "providerRecipeId": "51278",
        "expectedError": "Gastronomos JSON-LD Recipe has no ingredients",
        "finalStatus": 200,
        "reason": "editorial page has no extractable single recipe",
    },
    "https://www.gastronomos.gr/syntagh/zymarika-sto-foyrno/52338/": {
        "providerRecipeId": "52338",
        "expectedError": "Gastronomos JSON-LD Recipe has no ingredients",
        "finalStatus": 200,
        "reason": "editorial page has no extractable single recipe",
    },
}
AUDITED_NON_RECIPE_SITEMAP_ENTRIES = (
    "https://www.gastronomos.gr/oles-oi-syntages/",
)

PARSER_CONTRACT_FILENAMES = (
    "gastronomos_schema.py",
    "full_schema.py",
    "ingredient_taxonomy.py",
    "ingredient_aliases.json",
    "helpers.py",
    "providers.py",
)


class GastronomosCrawlError(RuntimeError):
    pass


class IncompleteGastronomosCrawl(GastronomosCrawlError):
    def __init__(self, failures: list[dict[str, str]]) -> None:
        self.failures = failures
        super().__init__(
            f"Gastronomos catalog is incomplete ({len(failures)} failures)"
        )


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
    non_recipe_entries: tuple[str, ...]


def canonical_site_url(value: str, *, upgrade_http: bool = False) -> str:
    parts = urlsplit(value.strip())
    host = (parts.hostname or "").casefold().rstrip(".")
    scheme = parts.scheme.casefold()
    if (
        (scheme != "https" and not (upgrade_http and scheme == "http"))
        or host not in ALLOWED_HOSTS
        or parts.username
        or parts.password
        or parts.port not in (None, 80, 443)
    ):
        raise GastronomosCrawlError(f"refusing non-approved URL: {value}")
    path = re.sub(
        r"%[0-9a-fA-F]{2}",
        lambda match: match.group(0).upper(),
        parts.path,
    )
    return urlunsplit(("https", "www.gastronomos.gr", path, parts.query, ""))


def canonical_recipe_location(value: str) -> str | None:
    try:
        canonical = canonical_site_url(value, upgrade_http=True)
    except GastronomosCrawlError:
        return None
    parts = urlsplit(canonical)
    if parts.query or not RECIPE_PATH_RE.fullmatch(parts.path):
        return None
    return urlunsplit(
        ("https", "www.gastronomos.gr", parts.path.rstrip("/") + "/", "", "")
    )


class RateLimiter:
    def __init__(self, interval_seconds: float = MINIMUM_DELAY_SECONDS) -> None:
        self.interval_seconds = max(MINIMUM_DELAY_SECONDS, float(interval_seconds))
        self._next_request_at = 0.0
        self._lock = threading.Lock()

    def wait(self) -> None:
        # Reserve one aggregate start slot while holding the lock, then sleep
        # outside it. Concurrent workers can overlap response latency, while
        # requests from all workers still begin at least one interval apart.
        with self._lock:
            now = time.monotonic()
            scheduled = max(now, self._next_request_at)
            self._next_request_at = scheduled + self.interval_seconds
        remaining = scheduled - now
        if remaining > 0:
            time.sleep(remaining)


class GastronomosHttpClient:
    def __init__(
        self,
        *,
        session: requests.Session | None = None,
        min_delay_seconds: float = MINIMUM_DELAY_SECONDS,
        timeout_seconds: float = 30.0,
        max_retries: int = 5,
    ) -> None:
        self._provided_session = session
        self._thread_local = threading.local()
        self._headers = {
            "User-Agent": USER_AGENT,
            "Accept": "text/html, application/ld+json, application/xml, text/plain",
        }
        if self._provided_session is not None:
            self._provided_session.headers.update(self._headers)
        self.rate_limiter = RateLimiter(min_delay_seconds)
        self.timeout_seconds = timeout_seconds
        self.max_retries = max(1, max_retries)

    def _session(self) -> requests.Session:
        if self._provided_session is not None:
            return self._provided_session
        session = getattr(self._thread_local, "session", None)
        if session is None:
            session = requests.Session()
            session.headers.update(self._headers)
            self._thread_local.session = session
        return session

    @staticmethod
    def _retry_after(value: str | None, fallback: float) -> float:
        if not value:
            return fallback
        try:
            return max(0.0, float(value))
        except ValueError:
            try:
                parsed = parsedate_to_datetime(value)
                return max(
                    0.0,
                    (parsed - datetime.now(timezone.utc)).total_seconds(),
                )
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
                raise GastronomosCrawlError(
                    f"robots.txt does not allow {current} for {USER_AGENT}"
                )
            response: requests.Response | None = None
            for attempt in range(self.max_retries):
                self.rate_limiter.wait()
                try:
                    response = self._session().get(
                        current,
                        allow_redirects=False,
                        timeout=self.timeout_seconds,
                    )
                except requests.RequestException:
                    if attempt + 1 >= self.max_retries:
                        raise
                    time.sleep(min(30.0, 2**attempt))
                    continue
                if len(response.content) > MAX_RESPONSE_BYTES:
                    raise GastronomosCrawlError(
                        f"response exceeds {MAX_RESPONSE_BYTES} bytes: {current}"
                    )
                if (
                    response.status_code in RETRY_STATUSES
                    and attempt + 1 < self.max_retries
                ):
                    time.sleep(self._retry_after(
                        response.headers.get("Retry-After"),
                        min(60.0, 2**attempt),
                    ))
                    continue
                break
            assert response is not None
            if response.is_redirect or response.is_permanent_redirect:
                location = response.headers.get("Location")
                if not location:
                    raise GastronomosCrawlError("redirect response has no Location")
                current = canonical_site_url(
                    urljoin(current, location),
                    upgrade_http=True,
                )
                continue
            if response.status_code not in allowed:
                raise GastronomosCrawlError(
                    f"HTTP {response.status_code} for {current}"
                )
            return response
        raise GastronomosCrawlError(f"too many redirects for {url}")


def load_robots(client: GastronomosHttpClient) -> RobotFileParser:
    response = client.get(ROBOTS_URL, allowed_statuses={200, 404})
    robots = RobotFileParser()
    robots.set_url(ROBOTS_URL)
    robots.parse(response.text.splitlines() if response.status_code == 200 else [])
    return robots


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _child_text(node: ET.Element, name: str) -> str:
    return next(
        ((child.text or "").strip() for child in node if _local_name(child.tag) == name),
        "",
    )


def parse_sitemap(
    xml_text: str,
) -> tuple[list[DiscoveredRecipe], list[str], int, list[str]]:
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as exc:
        raise GastronomosCrawlError(f"invalid sitemap XML: {exc}") from exc
    recipes: list[DiscoveredRecipe] = []
    children: list[str] = []
    non_recipe_entries: list[str] = []
    declared = 0
    if _local_name(root.tag) == "sitemapindex":
        for node in root:
            if _local_name(node.tag) != "sitemap":
                continue
            raw = _child_text(node, "loc")
            try:
                location = canonical_site_url(raw, upgrade_http=True)
            except GastronomosCrawlError:
                continue
            if RECIPE_SITEMAP_RE.fullmatch(Path(urlsplit(location).path).name):
                children.append(location)
    elif _local_name(root.tag) == "urlset":
        for node in root:
            if _local_name(node.tag) != "url":
                continue
            declared += 1
            raw_location = _child_text(node, "loc")
            location = canonical_recipe_location(raw_location)
            if location:
                recipes.append(
                    DiscoveredRecipe(location, _child_text(node, "lastmod"))
                )
            else:
                # Preserve every declared non-recipe location so discovery can
                # compare the raw, exact inventory with the audited list.
                # Scheme, host, path, query, and malformed/foreign drift all
                # remain visible and therefore fail closed.
                non_recipe_entries.append(raw_location.strip())
    else:
        raise GastronomosCrawlError("unexpected sitemap root element")
    return recipes, children, declared, non_recipe_entries


def discover_recipes(
    client: GastronomosHttpClient,
    robots: RobotFileParser,
    sitemap_url: str = SITEMAP_URL,
) -> DiscoveryResult:
    index_response = client.get(canonical_site_url(sitemap_url), robots=robots)
    index_recipes, children, index_declared, index_non_recipe = parse_sitemap(
        index_response.text
    )
    if index_recipes or index_declared or index_non_recipe or not children:
        raise GastronomosCrawlError(
            "Gastronomos sitemap index contains no recipe sitemaps"
        )
    recipes: dict[str, DiscoveredRecipe] = {}
    duplicates = 0
    declared = 0
    non_recipe_entries: list[str] = []
    for child in sorted(set(children)):
        response = client.get(child, robots=robots)
        parsed, nested, child_declared, child_non_recipe = parse_sitemap(
            response.text
        )
        if nested:
            raise GastronomosCrawlError(
                "nested recipe sitemap indexes are not supported"
            )
        declared += child_declared
        non_recipe_entries.extend(child_non_recipe)
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
    observed_non_recipe_entries = tuple(sorted(non_recipe_entries))
    expected_non_recipe_entries = tuple(
        sorted(AUDITED_NON_RECIPE_SITEMAP_ENTRIES)
    )
    if observed_non_recipe_entries != expected_non_recipe_entries:
        raise GastronomosCrawlError(
            "non-recipe sitemap entries do not match the exact audited allowlist"
        )
    if (
        not recipes
        or len(recipes) + duplicates + len(observed_non_recipe_entries)
        != declared
    ):
        raise GastronomosCrawlError(
            "recipe sitemap entry accounting is inconsistent"
        )
    return DiscoveryResult(
        recipes,
        len(set(children)),
        declared,
        duplicates,
        observed_non_recipe_entries,
    )


class CheckpointStore:
    def __init__(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        self.connection = sqlite3.connect(path, timeout=60.0)
        self.connection.execute(
            "CREATE TABLE IF NOT EXISTS meta "
            "(key TEXT PRIMARY KEY, value TEXT NOT NULL)"
        )
        self.connection.execute(
            "CREATE TABLE IF NOT EXISTS records "
            "(url TEXT PRIMARY KEY, lastmod TEXT NOT NULL, payload TEXT NOT NULL)"
        )
        self.connection.commit()

    def prepare(self, run_key: str, *, resume: bool) -> None:
        row = self.connection.execute(
            "SELECT value FROM meta WHERE key='runKey'"
        ).fetchone()
        if not resume or not row or row[0] != run_key:
            self.connection.execute("DELETE FROM records")
            self.connection.execute(
                "INSERT INTO meta(key,value) VALUES('runKey',?) "
                "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                (run_key,),
            )
            self.connection.commit()

    def get(self, url: str, lastmod: str) -> Mapping[str, object] | None:
        if not lastmod.strip():
            return None
        row = self.connection.execute(
            "SELECT payload FROM records WHERE url=? AND lastmod=?",
            (url, lastmod),
        ).fetchone()
        value = json.loads(row[0]) if row else None
        return value if isinstance(value, Mapping) else None

    def put(
        self,
        url: str,
        lastmod: str,
        record: Mapping[str, object],
    ) -> None:
        payload = json.dumps(
            record,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        )
        self.connection.execute(
            "INSERT INTO records(url,lastmod,payload) VALUES(?,?,?) "
            "ON CONFLICT(url) DO UPDATE SET "
            "lastmod=excluded.lastmod,payload=excluded.payload",
            (url, lastmod, payload),
        )
        self.connection.commit()

    def close(self) -> None:
        self.connection.close()


def _hash(value: object) -> str:
    encoded = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def parser_contract_hash() -> str:
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
    return _hash({
        "schema": GASTRONOMOS_DETAIL_SCHEMA_VERSION,
        "parserContractHash": parser_contract_hash(),
        "externalRedirectAllowlist": AUDITED_EXTERNAL_REDIRECTS,
        "nonGreekStubAllowlist": AUDITED_NON_GREEK_STUBS,
        "canonicalAliasAllowlist": AUDITED_CANONICAL_ALIASES,
        "sourceIncompletePageAllowlist": AUDITED_SOURCE_INCOMPLETE_PAGES,
        "nonRecipeSitemapEntryAllowlist": AUDITED_NON_RECIPE_SITEMAP_ENTRIES,
    })


def validate_checkpoint_record(
    record: Mapping[str, object],
    sitemap_url: str,
    sitemap_last_modified: str,
) -> dict[str, object]:
    value = dict(record)
    ensure_full_record(value)
    expected_url = canonical_recipe_location(sitemap_url)
    provider_id = str(value.get("providerRecipeId") or "")
    if (
        expected_url is None
        or value.get("sourceKey") != GASTRONOMOS.key
        or value.get("active") is not True
        or value.get("language") != "el"
        or value.get("sourceUrl") != expected_url
        or value.get("canonicalUrl") != expected_url
        or value.get("sitemapLastModified") != sitemap_last_modified
        or not provider_id.isdigit()
        or value.get("sourceRecipeId") != int(provider_id)
        or value.get("id") != recipe_document_id(GASTRONOMOS, provider_id)
        or canonical_recipe_url(GASTRONOMOS, expected_url, provider_id)
        != expected_url
    ):
        raise FullSchemaError(
            "Gastronomos checkpoint identity does not match its sitemap URL"
        )
    return value


def _atomic_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(
        json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2),
        encoding="utf-8",
    )
    temporary.replace(path)


def _atomic_jsonl(
    path: Path,
    records: Iterable[Mapping[str, object]],
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as handle:
        for record in records:
            handle.write(json.dumps(
                record,
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            ) + "\n")
    temporary.replace(path)


def run_gastronomos_crawl(
    *,
    client: GastronomosHttpClient,
    robots: RobotFileParser,
    output_path: Path,
    manifest_path: Path,
    failures_path: Path,
    checkpoint_path: Path,
    expected_active_count: int | None = None,
    expected_discovered_count: int | None = None,
    resume: bool = True,
    workers: int = 4,
    progress: Callable[[int, int, str], None] | None = None,
    on_failure: Callable[[str, str], None] | None = None,
) -> dict[str, object]:
    if isinstance(workers, bool) or not isinstance(workers, int) or not 1 <= workers <= 8:
        raise GastronomosCrawlError("workers must be between 1 and 8")
    discovery = discover_recipes(client, robots)
    if (
        expected_discovered_count is not None
        and len(discovery.recipes) != expected_discovered_count
    ):
        raise GastronomosCrawlError(
            f"discovered sitemap URL count {len(discovery.recipes)} "
            f"!= expected {expected_discovered_count}"
        )
    contract_hash = parser_contract_hash()
    run_key = checkpoint_run_key()
    store = CheckpointStore(checkpoint_path)
    records: list[dict[str, object]] = []
    failures: list[dict[str, str]] = []
    external_redirects: list[dict[str, object]] = []
    non_greek_stubs: list[dict[str, object]] = []
    source_incomplete_pages: list[dict[str, object]] = []
    aliases: list[dict[str, str]] = []
    resumed = 0
    invalidated = 0
    executor: ThreadPoolExecutor | None = None
    try:
        store.prepare(run_key, resume=resume)
        items = sorted(
            discovery.recipes.values(),
            key=lambda value: value.source_url,
        )
        total = len(items)
        cached_records: dict[str, Mapping[str, object]] = {}
        to_fetch: list[DiscoveredRecipe] = []
        # SQLite remains on this controlling thread. Every reusable record is
        # validated before deciding whether the corresponding URL needs work.
        for item in items:
            cached: Mapping[str, object] | None = None
            if (
                item.source_url not in AUDITED_EXTERNAL_REDIRECTS
                and item.source_url not in AUDITED_NON_GREEK_STUBS
                and item.source_url not in AUDITED_CANONICAL_ALIASES
                and item.source_url not in AUDITED_SOURCE_INCOMPLETE_PAGES
            ):
                cached = store.get(item.source_url, item.last_modified)
                if cached is not None:
                    try:
                        cached = validate_checkpoint_record(
                            cached,
                            item.source_url,
                            item.last_modified,
                        )
                    except (FullSchemaError, ValueError):
                        cached = None
                        invalidated += 1
            if cached is None:
                to_fetch.append(item)
            else:
                cached_records[item.source_url] = cached

        executor = ThreadPoolExecutor(
            max_workers=workers,
            thread_name_prefix="gastronomos-detail",
        )
        # Futures may finish out of order, but they are consumed below in the
        # canonical URL order. Normalization, checkpoint writes, progress, and
        # final JSONL order therefore remain byte-for-byte deterministic.
        fetch_futures: dict[str, Future[requests.Response]] = {
            item.source_url: executor.submit(
                client.get,
                item.source_url,
                robots=robots,
            )
            for item in to_fetch
        }
        for index, item in enumerate(
            items,
            start=1,
        ):
            record = cached_records.get(item.source_url)
            try:
                if record is None:
                    # Drop the completed Future immediately. Otherwise every
                    # Future retains its full HTTP response body until all
                    # 12k+ recipes finish, causing multi-gigabyte growth.
                    response = fetch_futures.pop(item.source_url).result()
                    final_recipe_url = canonical_recipe_location(response.url)
                    if final_recipe_url is None:
                        observed = {
                            "finalUrl": canonical_site_url(
                                response.url,
                                upgrade_http=True,
                            ),
                            "finalStatus": response.status_code,
                        }
                        expected = AUDITED_EXTERNAL_REDIRECTS.get(item.source_url)
                        if expected != observed:
                            raise GastronomosCrawlError(
                                "unapproved non-recipe redirect: "
                                f"{item.source_url} -> {observed}"
                            )
                        external_redirects.append({
                            "sourceUrl": item.source_url,
                            **observed,
                            "reason": "redirected outside /syntagh/{slug}/{numeric-id}/",
                        })
                        continue
                    if final_recipe_url != item.source_url:
                        expected = AUDITED_CANONICAL_ALIASES.get(item.source_url)
                        if (
                            expected is None
                            or expected.get("canonicalUrl") != final_recipe_url
                            or final_recipe_url not in discovery.recipes
                        ):
                            raise GastronomosCrawlError(
                                "unapproved canonical recipe alias: "
                                f"{item.source_url} -> {final_recipe_url}"
                            )
                        aliases.append({
                            "sourceUrl": item.source_url,
                            "canonicalUrl": final_recipe_url,
                            "providerRecipeId": expected["providerRecipeId"],
                        })
                        continue
                    if item.source_url in AUDITED_EXTERNAL_REDIRECTS:
                        raise GastronomosCrawlError(
                            "audited external redirect unexpectedly became a recipe"
                        )
                    if item.source_url in AUDITED_CANONICAL_ALIASES:
                        raise GastronomosCrawlError(
                            "audited canonical alias unexpectedly stopped redirecting"
                        )

                    language_excluded = False
                    source_incomplete_excluded = False
                    last_schema_error: FullSchemaError | None = None
                    for schema_attempt in range(3):
                        try:
                            record = normalize_gastronomos_page(
                                response.text,
                                source_url=item.source_url,
                                sitemap_last_modified=item.last_modified,
                            )
                            break
                        except GastronomosLanguageError as exc:
                            expected_id = AUDITED_NON_GREEK_STUBS.get(item.source_url)
                            if expected_id != exc.provider_recipe_id:
                                raise
                            non_greek_stubs.append({
                                "sourceUrl": item.source_url,
                                "providerRecipeId": exc.provider_recipe_id,
                                "finalStatus": response.status_code,
                                "reason": "recipe content is not substantively Greek",
                            })
                            language_excluded = True
                            break
                        except FullSchemaError as exc:
                            last_schema_error = exc
                            if schema_attempt == 2:
                                expected = AUDITED_SOURCE_INCOMPLETE_PAGES.get(
                                    item.source_url
                                )
                                source_match = RECIPE_PATH_RE.fullmatch(
                                    urlsplit(item.source_url).path
                                )
                                observed = {
                                    "providerRecipeId": (
                                        source_match.group("id")
                                        if source_match is not None
                                        else ""
                                    ),
                                    "expectedError": str(exc),
                                    "finalStatus": response.status_code,
                                }
                                if (
                                    expected is None
                                    or any(
                                        expected.get(key) != value
                                        for key, value in observed.items()
                                    )
                                ):
                                    raise
                                source_incomplete_pages.append({
                                    "sourceUrl": item.source_url,
                                    "providerRecipeId": observed["providerRecipeId"],
                                    "finalStatus": observed["finalStatus"],
                                    "sourceError": observed["expectedError"],
                                    "reason": str(expected["reason"]),
                                })
                                source_incomplete_excluded = True
                                break
                            response = client.get(item.source_url, robots=robots)
                            if canonical_recipe_location(response.url) != item.source_url:
                                raise GastronomosCrawlError(
                                    "recipe URL changed during schema retry: "
                                    f"{item.source_url} -> {response.url}"
                                )
                    if language_excluded or source_incomplete_excluded:
                        continue
                    if item.source_url in AUDITED_NON_GREEK_STUBS:
                        raise GastronomosCrawlError(
                            "audited non-Greek stub unexpectedly normalized as Greek"
                        )
                    if item.source_url in AUDITED_SOURCE_INCOMPLETE_PAGES:
                        raise GastronomosCrawlError(
                            "audited source-incomplete page unexpectedly normalized"
                        )
                    if record is None and last_schema_error is not None:
                        raise last_schema_error
                    if record is None:
                        raise FullSchemaError(
                            "Gastronomos normalization returned no record"
                        )
                    record = validate_checkpoint_record(
                        record,
                        item.source_url,
                        item.last_modified,
                    )
                    store.put(item.source_url, item.last_modified, record)
                else:
                    resumed += 1
                records.append(dict(record))
            except (
                GastronomosCrawlError,
                FullSchemaError,
                requests.RequestException,
                ValueError,
            ) as exc:
                failures.append({"sourceUrl": item.source_url, "error": str(exc)})
                _atomic_json(failures_path, {
                    "complete": False,
                    "failedRecipeCount": len(failures),
                    "failures": failures,
                })
                if on_failure:
                    on_failure(item.source_url, str(exc))
            finally:
                if progress:
                    progress(index, total, item.source_url)
    finally:
        if executor is not None:
            # A parser/programming error must not leave thousands of queued
            # network requests running before the original exception can be
            # reported. At most the currently running workers are awaited.
            executor.shutdown(wait=True, cancel_futures=True)
        store.close()

    if failures:
        _atomic_json(failures_path, {
            "complete": False,
            "failedRecipeCount": len(failures),
            "failures": failures,
        })
        raise IncompleteGastronomosCrawl(failures)

    external_redirects.sort(key=lambda item: str(item["sourceUrl"]))
    non_greek_stubs.sort(key=lambda item: str(item["sourceUrl"]))
    source_incomplete_pages.sort(key=lambda item: str(item["sourceUrl"]))
    aliases.sort(key=lambda item: item["sourceUrl"])
    if {str(item["sourceUrl"]) for item in external_redirects} != set(AUDITED_EXTERNAL_REDIRECTS):
        raise GastronomosCrawlError(
            "observed external redirects do not match the audited allowlist"
        )
    if {str(item["sourceUrl"]) for item in non_greek_stubs} != set(AUDITED_NON_GREEK_STUBS):
        raise GastronomosCrawlError(
            "observed non-Greek stubs do not match the audited allowlist"
        )
    if {
        str(item["sourceUrl"]) for item in source_incomplete_pages
    } != set(AUDITED_SOURCE_INCOMPLETE_PAGES):
        raise GastronomosCrawlError(
            "observed source-incomplete pages do not match the audited allowlist"
        )
    if {item["sourceUrl"] for item in aliases} != set(AUDITED_CANONICAL_ALIASES):
        raise GastronomosCrawlError(
            "observed canonical aliases do not match the audited allowlist"
        )

    document_ids = [str(record["id"]) for record in records]
    canonical_urls = [str(record["canonicalUrl"]) for record in records]
    accounted = (
        len(records)
        + len(aliases)
        + len(external_redirects)
        + len(non_greek_stubs)
        + len(source_incomplete_pages)
    )
    if (
        accounted != len(discovery.recipes)
        or len(set(document_ids)) != len(document_ids)
        or len(set(canonical_urls)) != len(canonical_urls)
    ):
        raise GastronomosCrawlError(
            "discovery is not exactly accounted by unique canonical Greek "
            "recipes, aliases, redirects, audited non-Greek stubs, and "
            "source-incomplete pages"
        )
    records_by_url = {
        str(record["canonicalUrl"]): record for record in records
    }
    for alias in aliases:
        target = records_by_url.get(alias["canonicalUrl"])
        if (
            target is None
            or str(target.get("providerRecipeId")) != alias["providerRecipeId"]
            or str(target.get("id"))
            != recipe_document_id(GASTRONOMOS, alias["providerRecipeId"])
        ):
            raise GastronomosCrawlError(
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
        if ingredient_count < 1 or method_count < 1:
            raise GastronomosCrawlError(
                "canonical Greek recipe lacks complete ingredients or method: "
                f"{record['canonicalUrl']}"
            )
    if expected_active_count is not None and len(records) != expected_active_count:
        raise GastronomosCrawlError(
            f"canonical Greek recipe count {len(records)} "
            f"!= expected {expected_active_count}"
        )
    if not records:
        raise GastronomosCrawlError("Gastronomos crawl produced no recipes")

    exclusions: list[dict[str, object]] = [
        {"kind": "externalRedirect", **item} for item in external_redirects
    ] + [
        {"kind": "nonGreekStub", **item} for item in non_greek_stubs
    ] + [
        {"kind": "sourceIncompletePage", **item}
        for item in source_incomplete_pages
    ]
    exclusions.sort(key=lambda item: str(item["sourceUrl"]))
    sizes = [document_sizes(record) for record in records]
    now = datetime.now(timezone.utc).isoformat()
    manifest: dict[str, object] = {
        "complete": True,
        "sourceKey": GASTRONOMOS.key,
        "detailSchemaVersion": GASTRONOMOS_DETAIL_SCHEMA_VERSION,
        "parserContractHash": contract_hash,
        "checkpointRunKey": run_key,
        "failedRecipeCount": 0,
        "recipeSitemapCount": discovery.recipe_sitemaps,
        "declaredRecipeEntryCount": discovery.declared_entries,
        "duplicateRecipeEntryCount": discovery.duplicate_entries,
        "discoveredRecipeUrlCount": len(discovery.recipes),
        "nonRecipeSitemapEntryCount": len(discovery.non_recipe_entries),
        "nonRecipeSitemapEntries": list(discovery.non_recipe_entries),
        "nonRecipeSitemapEntriesHash": _hash(discovery.non_recipe_entries),
        "canonicalGreekRecipeCount": len(records),
        "canonicalAliasCount": len(aliases),
        "canonicalAliases": aliases,
        "canonicalAliasesHash": _hash(aliases),
        "externalRedirectExclusionCount": len(external_redirects),
        "externalRedirectExclusions": external_redirects,
        "externalRedirectExclusionsHash": _hash(external_redirects),
        "nonGreekStubExclusionCount": len(non_greek_stubs),
        "nonGreekStubExclusions": non_greek_stubs,
        "nonGreekStubExclusionsHash": _hash(non_greek_stubs),
        "sourceIncompletePageExclusionCount": len(source_incomplete_pages),
        "sourceIncompletePageExclusions": source_incomplete_pages,
        "sourceIncompletePageExclusionsHash": _hash(source_incomplete_pages),
        "excludedRecipeUrlCount": len(exclusions),
        "excludedRecipeUrls": exclusions,
        "excludedRecipeUrlsHash": _hash(exclusions),
        "discoveredActiveRecipeCount": len(records),
        "outputRecipeCount": len(records),
        "activeRecipeCount": len(records),
        "detailRecipeCount": len(records),
        "sourcePayloadCount": len(records),
        "activeIdsHash": _hash(sorted(document_ids)),
        "catalogHash": _hash(sorted(records, key=lambda item: str(item["id"]))),
        "summaryHash": collection_hash(records, firestore_recipe_payload),
        "detailHash": collection_hash(records, firestore_detail_payload),
        "sourcePayloadHash": collection_hash(records, firestore_source_payload),
        "maximumSummaryDocumentBytes": max(size[0] for size in sizes),
        "maximumDetailDocumentBytes": max(size[1] for size in sizes),
        "maximumSourcePayloadDocumentBytes": max(size[2] for size in sizes),
        "oversizedDocumentCount": 0,
        "checkpointResumedRecipeCount": resumed,
        "checkpointInvalidatedRecipeCount": invalidated,
        "generatedAt": now,
        "catalogFile": output_path.name,
    }
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
        "canonicalAliasCount": len(aliases),
        "canonicalAliases": aliases,
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
    parser = argparse.ArgumentParser(
        description="Crawl authorized Gastronomos Greek recipe JSON-LD"
    )
    parser.add_argument("--i-have-gastronomos-permission", action="store_true")
    parser.add_argument(
        "--output",
        type=Path,
        default=output_dir / "gastronomos-greek-full.jsonl",
    )
    parser.add_argument(
        "--manifest",
        type=Path,
        default=output_dir / "gastronomos-greek-full.manifest.json",
    )
    parser.add_argument(
        "--failures",
        type=Path,
        default=output_dir / "gastronomos-greek-full.failures.json",
    )
    parser.add_argument(
        "--checkpoint",
        type=Path,
        default=output_dir / ".gastronomos-greek-full.checkpoint.sqlite3",
    )
    parser.add_argument("--expected-discovered-count", type=_positive_int)
    parser.add_argument("--expected-active-count", type=_positive_int)
    parser.add_argument("--no-resume", action="store_true")
    parser.add_argument("--workers", type=_positive_int, default=4)
    parser.add_argument(
        "--min-delay-seconds",
        type=float,
        default=MINIMUM_DELAY_SECONDS,
    )
    parser.add_argument("--timeout-seconds", type=float, default=30.0)
    parser.add_argument("--max-retries", type=_positive_int, default=5)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if not args.i_have_gastronomos_permission:
        print(
            "error: written Gastronomos authorization is required; pass "
            "--i-have-gastronomos-permission only after obtaining it",
            file=sys.stderr,
        )
        return 2
    if (
        args.timeout_seconds <= 0
        or args.min_delay_seconds < MINIMUM_DELAY_SECONDS
        or args.workers > 8
    ):
        print(
            "error: timeout must be positive, delay at least 1 second, and workers 1-8",
            file=sys.stderr,
        )
        return 2
    client = GastronomosHttpClient(
        min_delay_seconds=args.min_delay_seconds,
        timeout_seconds=args.timeout_seconds,
        max_retries=args.max_retries,
    )

    def progress(done: int, total: int, source: str) -> None:
        if done == 1 or done == total or done % 100 == 0:
            print(json.dumps({
                "progress": done,
                "total": total,
                "source": source,
            }), file=sys.stderr, flush=True)

    def report_failure(source: str, error: str) -> None:
        print(
            json.dumps({"failure": source, "error": error}, ensure_ascii=False),
            file=sys.stderr,
            flush=True,
        )

    try:
        robots = load_robots(client)
        manifest = run_gastronomos_crawl(
            client=client,
            robots=robots,
            output_path=args.output,
            manifest_path=args.manifest,
            failures_path=args.failures,
            checkpoint_path=args.checkpoint,
            expected_active_count=args.expected_active_count,
            expected_discovered_count=args.expected_discovered_count,
            resume=not args.no_resume,
            workers=args.workers,
            progress=progress,
            on_failure=report_failure,
        )
    except (
        GastronomosCrawlError,
        FullSchemaError,
        requests.RequestException,
        OSError,
        sqlite3.Error,
    ) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(manifest, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
