"""Permission-gated full Greek catalog crawler with resumable checkpoints."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sqlite3
import sys
import time
import xml.etree.ElementTree as ET
from collections import defaultdict
from collections.abc import Callable, Iterable, Mapping
from dataclasses import dataclass
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path
from typing import Any
from urllib.parse import urlencode, urljoin, urlsplit, urlunsplit
from urllib.robotparser import RobotFileParser

import requests

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from full_schema import (  # type: ignore[import-not-found]
        DETAIL_SCHEMA_VERSION, FACET_KEYS, FullSchemaError, collection_hash,
        document_sizes, ensure_full_record, firestore_detail_payload,
        firestore_recipe_payload, firestore_source_payload,
        normalize_recipe_detail, sanitize_source_payload,
    )
else:
    from .full_schema import (
        DETAIL_SCHEMA_VERSION, FACET_KEYS, FullSchemaError, collection_hash,
        document_sizes, ensure_full_record, firestore_detail_payload,
        firestore_recipe_payload, firestore_source_payload,
        normalize_recipe_detail, sanitize_source_payload,
    )


SITE_ORIGIN = "https://akispetretzikis.com"
SITEMAP_URL = f"{SITE_ORIGIN}/sitemap.xml"
ROBOTS_URL = f"{SITE_ORIGIN}/robots.txt"
API_ROOT = f"{SITE_ORIGIN}/api/v1"
USER_AGENT = "SpoonCatalogTool/1.0 (+authorized personal catalog; respectful crawler)"
MINIMUM_DELAY_SECONDS = 1.0
DEFAULT_DELAY_SECONDS = 1.0
DEFAULT_TIMEOUT_SECONDS = 30.0
DEFAULT_RETRIES = 5
MAX_RESPONSE_BYTES = 10 * 1024 * 1024
RETRY_STATUSES = {408, 425, 429, 500, 502, 503, 504}
ALLOWED_HOSTS = {"akispetretzikis.com", "www.akispetretzikis.com"}
RECIPE_PATH_RE = re.compile(r"^/recipe/(?P<id>\d+)(?:/[^/?#]+)?/?$")


class CrawlError(RuntimeError):
    """A catalog run cannot prove completeness."""


class IncompleteCrawlError(CrawlError):
    def __init__(self, failures: list[dict[str, str]]) -> None:
        self.failures = failures
        super().__init__(f"catalog is incomplete ({len(failures)} failed recipe request(s))")


@dataclass(frozen=True)
class DiscoveredRecipe:
    recipe_id: str
    source_url: str
    last_modified: str


@dataclass(frozen=True)
class DiscoveryResult:
    recipes: dict[str, DiscoveredRecipe]
    sitemap_documents: int
    duplicate_recipe_entries: int


class RateLimiter:
    def __init__(self, interval_seconds: float = DEFAULT_DELAY_SECONDS) -> None:
        self.interval_seconds = max(MINIMUM_DELAY_SECONDS, float(interval_seconds))
        self.last_request_at: float | None = None

    def wait(self) -> None:
        if self.last_request_at is not None:
            remaining = self.interval_seconds - (time.monotonic() - self.last_request_at)
            if remaining > 0:
                time.sleep(remaining)
        self.last_request_at = time.monotonic()


def validate_site_url(url: str) -> str:
    parts = urlsplit(url)
    host = (parts.hostname or "").casefold().rstrip(".")
    if (parts.scheme.casefold() != "https" or host not in ALLOWED_HOSTS
            or parts.username or parts.password or parts.port not in (None, 443)):
        raise CrawlError(f"refusing non-approved URL: {url}")
    return urlunsplit(("https", "akispetretzikis.com", parts.path, parts.query, ""))


class AuthorizedHttpClient:
    """Same-site HTTP client with retries, redirect checks, and a global rate limit."""

    def __init__(
        self,
        *,
        session: requests.Session | None = None,
        min_delay_seconds: float = DEFAULT_DELAY_SECONDS,
        timeout_seconds: float = DEFAULT_TIMEOUT_SECONDS,
        max_retries: int = DEFAULT_RETRIES,
    ) -> None:
        self.session = session or requests.Session()
        self.session.headers.update({"User-Agent": USER_AGENT, "Accept": "application/json, application/xml, text/plain"})
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
                target = parsedate_to_datetime(value)
                return max(0.0, (target - datetime.now(timezone.utc)).total_seconds())
            except (TypeError, ValueError):
                return fallback

    def get(self, url: str, *, robots: RobotFileParser | None = None, allowed_statuses: set[int] | None = None) -> requests.Response:
        current = validate_site_url(url)
        allowed = allowed_statuses or {200}
        redirects = 0
        while redirects <= 5:
            if robots is not None and not robots.can_fetch(USER_AGENT, current):
                raise CrawlError(f"robots.txt does not allow {current}")
            response: requests.Response | None = None
            for attempt in range(self.max_retries):
                self.rate_limiter.wait()
                try:
                    response = self.session.get(current, allow_redirects=False, timeout=self.timeout_seconds)
                except requests.RequestException:
                    if attempt + 1 == self.max_retries:
                        raise
                    time.sleep(min(30.0, 2**attempt))
                    continue
                if len(response.content) > MAX_RESPONSE_BYTES:
                    raise CrawlError(f"response exceeds {MAX_RESPONSE_BYTES} bytes: {current}")
                if response.status_code in RETRY_STATUSES and attempt + 1 < self.max_retries:
                    delay = self._retry_after(response.headers.get("Retry-After"), min(60.0, 2**attempt))
                    time.sleep(delay)
                    continue
                break
            assert response is not None
            if response.is_redirect or response.is_permanent_redirect:
                location = response.headers.get("Location")
                if not location:
                    raise CrawlError("redirect response has no Location header")
                current = validate_site_url(urljoin(current, location))
                redirects += 1
                continue
            if response.status_code not in allowed:
                raise CrawlError(f"HTTP {response.status_code} for {current}")
            return response
        raise CrawlError(f"too many redirects for {url}")

    def json(self, url: str, *, robots: RobotFileParser) -> Any:
        response = self.get(url, robots=robots)
        try:
            return response.json()
        except (json.JSONDecodeError, ValueError) as exc:
            raise CrawlError(f"invalid JSON from {url}") from exc


def load_robots(client: AuthorizedHttpClient) -> RobotFileParser:
    response = client.get(ROBOTS_URL, allowed_statuses={200, 404})
    robots = RobotFileParser()
    robots.set_url(ROBOTS_URL)
    if response.status_code == 200:
        robots.parse(response.text.splitlines())
    else:
        robots.parse([])
    return robots


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _node_text(node: ET.Element, child_name: str) -> str:
    for child in node:
        if _local_name(child.tag) == child_name:
            return (child.text or "").strip()
    return ""


def canonical_recipe_location(location: str) -> tuple[str, str] | None:
    parts = urlsplit(location.strip())
    host = (parts.hostname or "").casefold().rstrip(".")
    if (parts.scheme.casefold() != "https" or host not in ALLOWED_HOSTS
            or parts.username or parts.password or parts.port not in (None, 443)
            or parts.query or parts.fragment):
        return None
    match = RECIPE_PATH_RE.fullmatch(parts.path)
    if not match:
        return None
    return match.group("id"), urlunsplit(("https", "akispetretzikis.com", parts.path, "", ""))


def parse_sitemap(xml_text: str) -> tuple[list[DiscoveredRecipe], list[str]]:
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as exc:
        raise CrawlError(f"invalid sitemap XML: {exc}") from exc
    root_type = _local_name(root.tag)
    recipes: list[DiscoveredRecipe] = []
    children: list[str] = []
    if root_type == "urlset":
        for node in root:
            if _local_name(node.tag) != "url":
                continue
            location = canonical_recipe_location(_node_text(node, "loc"))
            if location:
                recipe_id, source_url = location
                recipes.append(DiscoveredRecipe(recipe_id, source_url, _node_text(node, "lastmod")))
    elif root_type == "sitemapindex":
        for node in root:
            if _local_name(node.tag) == "sitemap":
                location = _node_text(node, "loc")
                if location:
                    children.append(validate_site_url(location))
    else:
        raise CrawlError(f"unexpected sitemap root element: {root_type}")
    return recipes, children


def discover_recipes(client: AuthorizedHttpClient, robots: RobotFileParser, sitemap_url: str = SITEMAP_URL) -> DiscoveryResult:
    pending = [validate_site_url(sitemap_url)]
    visited: set[str] = set()
    recipes: dict[str, DiscoveredRecipe] = {}
    duplicates = 0
    while pending:
        current = pending.pop(0)
        if current in visited:
            continue
        if len(visited) >= 100:
            raise CrawlError("sitemap index exceeds the 100-document safety limit")
        visited.add(current)
        response = client.get(current, robots=robots)
        parsed, children = parse_sitemap(response.text)
        pending.extend(child for child in children if child not in visited)
        for candidate in parsed:
            existing = recipes.get(candidate.recipe_id)
            if existing is None:
                recipes[candidate.recipe_id] = candidate
                continue
            duplicates += 1
            # Numeric ID is canonical. Prefer the deterministic URL and newest
            # lexical ISO lastmod without fetching the same recipe twice.
            recipes[candidate.recipe_id] = DiscoveredRecipe(
                candidate.recipe_id,
                min(existing.source_url, candidate.source_url),
                max(existing.last_modified, candidate.last_modified),
            )
    if not recipes:
        raise CrawlError("no canonical Greek /recipe/{numeric-id}/ URLs were discovered")
    return DiscoveryResult(recipes, len(visited), duplicates)


def _object(value: object, label: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise CrawlError(f"{label} must be a JSON object")
    return value


def api_reported_recipe_count(client: AuthorizedHttpClient, robots: RobotFileParser) -> int:
    query = urlencode({"lang": "el", "per_page": 1, "page": 1})
    response = _object(client.json(f"{API_ROOT}/recipe?{query}", robots=robots), "recipe list")
    meta = _object(response.get("meta"), "recipe list meta")
    total = meta.get("total")
    if isinstance(total, bool) or not isinstance(total, int) or total <= 0:
        raise CrawlError("recipe list meta.total is not a positive integer")
    return total


def fetch_taxonomy(client: AuthorizedHttpClient, robots: RobotFileParser) -> dict[str, list[dict[str, str]]]:
    raw = _object(client.json(f"{API_ROOT}/recipe_filters?lang=el", robots=robots), "recipe filters")
    taxonomy: dict[str, list[dict[str, str]]] = {}
    for facet in FACET_KEYS:
        values = raw.get(facet)
        if not isinstance(values, list) or not values:
            raise CrawlError(f"recipe filter {facet!r} is missing or empty")
        options = []
        seen = set()
        for index, value in enumerate(values):
            option = _object(value, f"{facet}[{index}]")
            option_id = str(option.get("id") or "").strip()
            title = str(option.get("title") or "").strip()
            if not option_id or not title or option_id in seen:
                raise CrawlError(f"invalid or duplicate {facet} option at index {index}")
            seen.add(option_id)
            options.append({"id": option_id, "title": title})
        taxonomy[facet] = options
    return taxonomy


class CheckpointStore:
    """Transactional per-recipe and per-facet resume state."""

    def __init__(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        self.connection = sqlite3.connect(path)
        self.connection.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        self.connection.execute(
            "CREATE TABLE IF NOT EXISTS records (id TEXT PRIMARY KEY, lastmod TEXT NOT NULL, payload TEXT NOT NULL)"
        )
        self.connection.execute(
            "CREATE TABLE IF NOT EXISTS facets (facet TEXT NOT NULL, option_id TEXT NOT NULL, title TEXT NOT NULL, ids TEXT NOT NULL, PRIMARY KEY (facet, option_id))"
        )
        self.connection.commit()

    def close(self) -> None:
        self.connection.close()

    def _meta(self, key: str) -> str | None:
        row = self.connection.execute("SELECT value FROM meta WHERE key = ?", (key,)).fetchone()
        return str(row[0]) if row else None

    def _set_meta(self, key: str, value: str) -> None:
        self.connection.execute(
            "INSERT INTO meta(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            (key, value),
        )

    def prepare(self, run_key: str, taxonomy_hash: str, *, resume: bool) -> None:
        run_changed = not resume or self._meta("runKey") != run_key
        if run_changed:
            self.connection.execute("DELETE FROM records")
            self.connection.execute("DELETE FROM facets")
            self._set_meta("runKey", run_key)
        if run_changed or self._meta("taxonomyHash") != taxonomy_hash:
            self.connection.execute("DELETE FROM facets")
            self._set_meta("taxonomyHash", taxonomy_hash)
        self.connection.commit()

    def get_payload(self, recipe_id: str, lastmod: str) -> Mapping[str, Any] | None:
        row = self.connection.execute(
            "SELECT payload FROM records WHERE id = ? AND lastmod = ?", (recipe_id, lastmod)
        ).fetchone()
        if not row:
            return None
        value = json.loads(row[0])
        return _object(value, f"checkpoint recipe {recipe_id}")

    def put_payload(self, recipe_id: str, lastmod: str, payload: Mapping[str, Any]) -> None:
        serialized = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)
        self.connection.execute(
            "INSERT INTO records(id, lastmod, payload) VALUES (?, ?, ?) ON CONFLICT(id) DO UPDATE SET lastmod=excluded.lastmod, payload=excluded.payload",
            (recipe_id, lastmod, serialized),
        )
        self.connection.commit()

    def get_facet_ids(self, facet: str, option_id: str, title: str) -> list[str] | None:
        row = self.connection.execute(
            "SELECT ids, title FROM facets WHERE facet = ? AND option_id = ?", (facet, option_id)
        ).fetchone()
        if not row or row[1] != title:
            return None
        value = json.loads(row[0])
        return [str(item) for item in value] if isinstance(value, list) else None

    def put_facet_ids(self, facet: str, option_id: str, title: str, recipe_ids: list[str]) -> None:
        self.connection.execute(
            "INSERT INTO facets(facet, option_id, title, ids) VALUES (?, ?, ?, ?) "
            "ON CONFLICT(facet, option_id) DO UPDATE SET title=excluded.title, ids=excluded.ids",
            (facet, option_id, title, json.dumps(recipe_ids, separators=(",", ":"))),
        )
        self.connection.commit()


def _canonical_hash(value: object) -> str:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def discovery_run_key(discovery: DiscoveryResult) -> str:
    rows = [[item.recipe_id, item.source_url, item.last_modified] for item in sorted(discovery.recipes.values(), key=lambda item: int(item.recipe_id))]
    return _canonical_hash({"schema": DETAIL_SCHEMA_VERSION, "recipes": rows})


def _fetch_facet_page(
    client: AuthorizedHttpClient,
    robots: RobotFileParser,
    facet: str,
    option_id: str,
    page: int,
    *,
    per_page: int = 500,
) -> Mapping[str, Any]:
    parameters = {
        "foryou": "false", "filter": "false", "type": "recipes",
        "per_page": per_page, "page": page, facet: option_id, "lang": "el",
    }
    return _object(client.json(f"{API_ROOT}/search?{urlencode(parameters)}", robots=robots), "facet search")


def fetch_facet_ids(
    client: AuthorizedHttpClient,
    robots: RobotFileParser,
    facet: str,
    option_id: str,
) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    page = 1
    last_page = 1
    total = 0
    while page <= last_page:
        envelope = _fetch_facet_page(client, robots, facet, option_id, page)
        data, meta = envelope.get("data"), _object(envelope.get("meta"), "facet search meta")
        if not isinstance(data, list):
            raise CrawlError(f"facet {facet}={option_id} data is not a list")
        if page == 1:
            last_page = meta.get("last_page")
            total = meta.get("total")
            if isinstance(last_page, bool) or not isinstance(last_page, int) or last_page < 1:
                raise CrawlError(f"facet {facet}={option_id} has invalid last_page")
            if isinstance(total, bool) or not isinstance(total, int) or total < 0:
                raise CrawlError(f"facet {facet}={option_id} has invalid total")
        if meta.get("current_page") != page:
            raise CrawlError(f"facet {facet}={option_id} returned the wrong page")
        for item in data:
            recipe_id = str(_object(item, "facet recipe").get("id") or "")
            if not recipe_id.isdigit() or recipe_id in seen:
                raise CrawlError(f"facet {facet}={option_id} has invalid/duplicate recipe id")
            seen.add(recipe_id)
            result.append(recipe_id)
        page += 1
    if len(result) != total:
        raise CrawlError(f"facet {facet}={option_id} reported {total} recipes but returned {len(result)}")
    return result


def build_associations(
    client: AuthorizedHttpClient,
    robots: RobotFileParser,
    taxonomy: Mapping[str, list[dict[str, str]]],
    active_ids: set[str],
    checkpoint: CheckpointStore,
) -> tuple[dict[str, dict[str, list[dict[str, str]]]], int, int]:
    associations: dict[str, dict[str, list[dict[str, str]]]] = {
        recipe_id: {facet: [] for facet in FACET_KEYS} for recipe_id in active_ids
    }
    memberships = 0
    resumed = 0
    for facet in FACET_KEYS:
        for option in taxonomy[facet]:
            recipe_ids = checkpoint.get_facet_ids(facet, option["id"], option["title"])
            if recipe_ids is None:
                recipe_ids = fetch_facet_ids(client, robots, facet, option["id"])
                checkpoint.put_facet_ids(facet, option["id"], option["title"], recipe_ids)
            else:
                resumed += 1
            unknown = set(recipe_ids) - active_ids
            if unknown:
                sample = ", ".join(sorted(unknown, key=int)[:5])
                raise CrawlError(f"facet {facet}={option['id']} returned IDs absent from the Greek sitemap: {sample}")
            for recipe_id in recipe_ids:
                associations[recipe_id][facet].append(dict(option))
                memberships += 1
    return associations, memberships, resumed


def fetch_recipe_payload(
    client: AuthorizedHttpClient,
    robots: RobotFileParser,
    recipe_id: str,
) -> Mapping[str, Any]:
    envelope = _object(client.json(f"{API_ROOT}/recipe/{recipe_id}?lang=el", robots=robots), "recipe detail")
    payload = _object(envelope.get("data"), "recipe detail data")
    if str(payload.get("id") or "") != recipe_id:
        raise CrawlError(f"recipe detail ID does not match requested ID {recipe_id}")
    return _object(sanitize_source_payload(payload), "sanitized recipe detail")


def load_records(path: Path) -> list[Mapping[str, Any]]:
    if not path.is_file():
        return []
    records: list[Mapping[str, Any]] = []
    if path.suffix.casefold() in {".jsonl", ".ndjson"}:
        with path.open("r", encoding="utf-8-sig") as handle:
            for line_number, line in enumerate(handle, 1):
                if not line.strip():
                    continue
                try:
                    value = json.loads(line)
                except json.JSONDecodeError as exc:
                    raise CrawlError(f"invalid previous JSONL line {line_number}: {exc.msg}") from exc
                records.append(_object(value, f"previous record line {line_number}"))
        return records
    try:
        value = json.loads(path.read_text(encoding="utf-8-sig"))
    except json.JSONDecodeError as exc:
        raise CrawlError(f"invalid previous JSON: {exc.msg}") from exc
    if isinstance(value, Mapping) and isinstance(value.get("recipes"), list):
        value = value["recipes"]
    if not isinstance(value, list):
        value = [value]
    return [_object(item, "previous recipe") for item in value]


def previous_record_map(path: Path | None) -> dict[str, Mapping[str, Any]]:
    if path is None or not path.is_file():
        return {}
    result = {}
    for record in load_records(path):
        try:
            ensure_full_record(record)
        except FullSchemaError as exc:
            raise CrawlError(f"previous catalog contains an invalid full recipe: {exc}") from exc
        recipe_id = str(record["id"])
        if recipe_id in result:
            raise CrawlError(f"previous catalog contains duplicate id {recipe_id}")
        result[recipe_id] = record
    return result


def _atomic_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def _atomic_jsonl(path: Path, records: Iterable[Mapping[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False))
            handle.write("\n")
    os.replace(temporary, path)


def _ids_hash(recipe_ids: Iterable[str]) -> str:
    return _canonical_hash(sorted(recipe_ids, key=int))


def _catalog_hash(records: list[Mapping[str, Any]]) -> str:
    return _canonical_hash(sorted(records, key=lambda item: int(str(item["id"]))))


def _payload_hash(records: list[Mapping[str, Any]]) -> str:
    payloads = [{"id": str(item["id"]), "sourcePayload": item["sourcePayload"]} for item in sorted(records, key=lambda item: int(str(item["id"])))]
    return _canonical_hash(payloads)


def run_catalog_crawl(
    *,
    client: AuthorizedHttpClient,
    robots: RobotFileParser,
    output_path: Path,
    manifest_path: Path,
    failures_path: Path,
    checkpoint_path: Path,
    previous_path: Path | None = None,
    expected_active_count: int | None = None,
    resume: bool = True,
    progress: Callable[[int, int, str], None] | None = None,
) -> dict[str, Any]:
    progress = progress or (lambda done, total, source: None)
    discovery = discover_recipes(client, robots)
    api_count = api_reported_recipe_count(client, robots)
    discovered_ids = set(discovery.recipes)
    if len(discovered_ids) != api_count:
        raise CrawlError(
            f"completeness mismatch: sitemap has {len(discovered_ids)} Greek IDs, API reports {api_count}"
        )
    if expected_active_count is not None and len(discovered_ids) != expected_active_count:
        raise CrawlError(
            f"expected {expected_active_count} active recipes but discovered {len(discovered_ids)}"
        )

    taxonomy = fetch_taxonomy(client, robots)
    taxonomy_hash = _canonical_hash(taxonomy)
    checkpoint = CheckpointStore(checkpoint_path)
    try:
        checkpoint.prepare(discovery_run_key(discovery), taxonomy_hash, resume=resume)
        associations, membership_count, resumed_facets = build_associations(
            client, robots, taxonomy, discovered_ids, checkpoint
        )
        previous = previous_record_map(previous_path)
        records: list[dict[str, Any]] = []
        failures: list[dict[str, str]] = []
        fetched_count = incremental_count = resumed_count = 0
        ordered = sorted(discovery.recipes.values(), key=lambda item: int(item.recipe_id))
        for index, discovered in enumerate(ordered, 1):
            source = "network"
            try:
                payload = checkpoint.get_payload(discovered.recipe_id, discovered.last_modified)
                if payload is not None:
                    source = "checkpoint"
                    resumed_count += 1
                else:
                    old = previous.get(discovered.recipe_id)
                    if (
                        old is not None
                        and bool(discovered.last_modified)
                        and old.get("sitemapLastModified") == discovered.last_modified
                    ):
                        payload = _object(old.get("sourcePayload"), "previous sourcePayload")
                        source = "previous"
                        incremental_count += 1
                    else:
                        payload = fetch_recipe_payload(client, robots, discovered.recipe_id)
                        fetched_count += 1
                    checkpoint.put_payload(discovered.recipe_id, discovered.last_modified, payload)
                record = normalize_recipe_detail(
                    payload,
                    source_url=discovered.source_url,
                    sitemap_last_modified=discovered.last_modified,
                    associations=associations[discovered.recipe_id],
                    active=True,
                )
                if not record["active"]:
                    raise FullSchemaError("a sitemap recipe is not marked published by its detail API")
                records.append(record)
            except (CrawlError, FullSchemaError, OSError, requests.RequestException, json.JSONDecodeError) as exc:
                failures.append({"id": discovered.recipe_id, "url": discovered.source_url, "error": str(exc)})
            progress(index, len(ordered), source)
    finally:
        checkpoint.close()

    if failures:
        report = {
            "complete": False,
            "detailSchemaVersion": DETAIL_SCHEMA_VERSION,
            "discoveredActiveRecipeCount": len(discovered_ids),
            "successfulRecipeCount": len(records),
            "failedRecipeCount": len(failures),
            "failures": failures,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
        }
        _atomic_json(failures_path, report)
        raise IncompleteCrawlError(failures)

    now = datetime.now(timezone.utc).isoformat()
    for recipe_id, old in sorted(previous.items(), key=lambda item: int(item[0])):
        if recipe_id in discovered_ids:
            continue
        retired = normalize_recipe_detail(
            _object(old.get("sourcePayload"), "retired sourcePayload"),
            source_url=str(old["sourceUrl"]),
            sitemap_last_modified=str(old.get("sitemapLastModified") or ""),
            associations=_object(old.get("filterAssociations"), "retired filterAssociations"),
            active=False,
        )
        retired["retiredDetectedAt"] = str(old.get("retiredDetectedAt") or now)
        ensure_full_record(retired)
        records.append(retired)

    records.sort(key=lambda item: int(item["id"]))
    record_ids = [record["id"] for record in records]
    if len(record_ids) != len(set(record_ids)):
        raise CrawlError("output would contain duplicate recipe IDs")
    active_output_ids = {record["id"] for record in records if record["active"]}
    if active_output_ids != discovered_ids:
        raise CrawlError("active output IDs do not exactly equal discovered Greek sitemap IDs")
    sizes = [(record["id"], *document_sizes(record)) for record in records]
    largest_recipe = max(sizes, key=lambda item: item[1])
    largest_detail = max(sizes, key=lambda item: item[2])
    largest_payload = max(sizes, key=lambda item: item[3])
    catalog_hash = _catalog_hash(records)
    summary_hash = collection_hash(records, firestore_recipe_payload)
    detail_hash = collection_hash(records, firestore_detail_payload)
    payload_hash = collection_hash(records, firestore_source_payload)
    manifest = {
        "complete": True,
        "detailSchemaVersion": DETAIL_SCHEMA_VERSION,
        "language": "el",
        "source": "akispetretzikis.com",
        "sitemapUrl": SITEMAP_URL,
        "sitemapDocumentCount": discovery.sitemap_documents,
        "duplicateSitemapRecipeEntries": discovery.duplicate_recipe_entries,
        "discoveredActiveRecipeCount": len(discovered_ids),
        "apiReportedActiveRecipeCount": api_count,
        "expectedActiveRecipeCount": expected_active_count,
        "outputRecipeCount": len(records),
        "activeRecipeCount": len(discovered_ids),
        "retiredRecipeCount": len(records) - len(discovered_ids),
        "fetchedRecipeCount": fetched_count,
        "incrementalReusedRecipeCount": incremental_count,
        "checkpointResumedRecipeCount": resumed_count,
        "failedRecipeCount": 0,
        "sourcePayloadCount": len(records),
        "detailRecipeCount": len(records),
        "taxonomyOptionCount": sum(len(options) for options in taxonomy.values()),
        "facetAssociationMembershipCount": membership_count,
        "checkpointResumedFacetCount": resumed_facets,
        "taxonomy": taxonomy,
        "taxonomyHash": taxonomy_hash,
        "activeIdsHash": _ids_hash(discovered_ids),
        "catalogHash": catalog_hash,
        "catalogVersion": f"sha256:{catalog_hash}",
        "summaryHash": summary_hash,
        "detailHash": detail_hash,
        "sourcePayloadHash": payload_hash,
        "maximumNormalizedDocumentBytes": largest_recipe[1],
        "maximumNormalizedDocumentId": largest_recipe[0],
        "maximumDetailDocumentBytes": largest_detail[2],
        "maximumDetailDocumentId": largest_detail[0],
        "maximumSourcePayloadDocumentBytes": largest_payload[3],
        "maximumSourcePayloadDocumentId": largest_payload[0],
        "oversizedDocumentCount": 0,
        "generatedAt": now,
        "catalogFile": output_path.name,
    }
    _atomic_jsonl(output_path, records)
    _atomic_json(manifest_path, manifest)
    _atomic_json(failures_path, {"complete": True, "failedRecipeCount": 0, "failures": []})
    return manifest


def _positive_int(value: str) -> int:
    try:
        result = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("must be an integer") from exc
    if result <= 0:
        raise argparse.ArgumentTypeError("must be positive")
    return result


def build_parser() -> argparse.ArgumentParser:
    output_dir = Path(__file__).resolve().parent / "output"
    parser = argparse.ArgumentParser(
        description="Fetch every canonical Greek recipe and full detail under explicit permission."
    )
    parser.add_argument(
        "--i-have-permission",
        action="store_true",
        help="Required acknowledgement that the site owner authorized this full-content use.",
    )
    parser.add_argument("--output", type=Path, default=output_dir / "akis-greek-full.jsonl")
    parser.add_argument("--manifest", type=Path, default=output_dir / "akis-greek-full.manifest.json")
    parser.add_argument("--failures", type=Path, default=output_dir / "akis-greek-full.failures.json")
    parser.add_argument("--checkpoint", type=Path, default=output_dir / ".akis-greek-full.checkpoint.sqlite3")
    parser.add_argument(
        "--previous-catalog",
        type=Path,
        help="Prior complete JSON/JSONL for lastmod reuse and explicit inactive retirement records.",
    )
    parser.add_argument("--no-incremental", action="store_true", help="Do not reuse the existing output as the prior catalog.")
    parser.add_argument("--no-resume", action="store_true", help="Reset checkpoint entries for this run.")
    parser.add_argument("--expected-active-count", type=_positive_int)
    parser.add_argument("--min-delay-seconds", type=float, default=DEFAULT_DELAY_SECONDS)
    parser.add_argument("--timeout-seconds", type=float, default=DEFAULT_TIMEOUT_SECONDS)
    parser.add_argument("--max-retries", type=_positive_int, default=DEFAULT_RETRIES)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if not args.i_have_permission:
        print("error: full catalog access requires explicit --i-have-permission", file=sys.stderr)
        return 2
    if args.timeout_seconds <= 0:
        print("error: --timeout-seconds must be positive", file=sys.stderr)
        return 2
    previous_path = args.previous_catalog
    if previous_path is None and not args.no_incremental and args.output.is_file():
        previous_path = args.output
    client = AuthorizedHttpClient(
        min_delay_seconds=args.min_delay_seconds,
        timeout_seconds=args.timeout_seconds,
        max_retries=args.max_retries,
    )

    def progress(done: int, total: int, source: str) -> None:
        if done == 1 or done == total or done % 100 == 0:
            print(json.dumps({"progress": done, "total": total, "source": source}), file=sys.stderr, flush=True)

    try:
        robots = load_robots(client)
        manifest = run_catalog_crawl(
            client=client,
            robots=robots,
            output_path=args.output,
            manifest_path=args.manifest,
            failures_path=args.failures,
            checkpoint_path=args.checkpoint,
            previous_path=previous_path,
            expected_active_count=args.expected_active_count,
            resume=not args.no_resume,
            progress=progress,
        )
    except (CrawlError, FullSchemaError, OSError, sqlite3.Error, requests.RequestException) as exc:
        if not isinstance(exc, IncompleteCrawlError):
            try:
                _atomic_json(args.failures, {
                    "complete": False,
                    "failedRecipeCount": 1,
                    "failures": [{"stage": "pipeline", "error": str(exc)}],
                    "generatedAt": datetime.now(timezone.utc).isoformat(),
                })
            except OSError:
                pass
        print(f"error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(manifest, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
