"""Permission-gated public recipe download primitives with durable raw checkpoints.

Each source owns its discovery and parser. This transport checks robots, stays on
approved hosts, respects pacing and Retry-After, and never solves access challenges.
Raw responses remain local so parser fixes do not redownload the publisher.
"""
from __future__ import annotations

import gzip
import hashlib
import json
import os
import re
import sqlite3
import time
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path
from urllib.parse import unquote, urljoin, urlsplit
from urllib.robotparser import RobotFileParser

import requests

USER_AGENT = "PeltesRecipeImporter/1.0 (+mailto:hey@spoon.gr)"
MAX_BYTES = 8 * 1024 * 1024


class PublicCrawlError(RuntimeError):
    pass


class AccessBlocked(PublicCrawlError):
    pass


class RecipeExcluded(ValueError):
    """An explicit source defect or non-recipe exclusion, retained in the audit."""
    pass


class PublicHttpClient:
    def __init__(self, origin: str, *, delay: float = 1.0, session=None):
        self.origin = origin.rstrip("/")
        self.host = urlsplit(origin).hostname
        self.hosts = {self.host, self.host.removeprefix("www."), "www." + self.host.removeprefix("www.")}
        self.delay = max(1.0, delay)
        self.last_request = 0.0
        self.session = session or requests.Session()
        self.session.headers.update({"User-Agent": USER_AGENT})
        self.robots = None

    def validate(self, url: str) -> str:
        parts = urlsplit(url)
        if parts.scheme != "https" or parts.hostname not in self.hosts or parts.username or parts.password or parts.port not in (None, 443):
            raise PublicCrawlError(f"refusing unapproved URL: {url}")
        return url

    def load_robots(self):
        url = self.origin + "/robots.txt"
        response = self.get(url, check_robots=False)
        self.robots = RobotFileParser(url)
        self.robots.parse(response.text.splitlines())
        self.delay = max(self.delay, self.robots.crawl_delay(USER_AGENT) or 0, self.robots.crawl_delay("*") or 0)
        return response.text

    def robots_allows(self, url: str) -> bool:
        # urllib's parser stores the applicable agent groups and crawl delay but
        # its path matcher treats '*' and '$' literally. Publishers use both.
        robots = self.robots
        if robots is None or not robots.last_checked or robots.disallow_all:
            return False
        if robots.allow_all:
            return True
        entry = next((item for item in robots.entries if item.applies_to(USER_AGENT)), robots.default_entry)
        if entry is None:
            return True
        parts = urlsplit(url)
        target = unquote(parts.path or "/") + ("?" + unquote(parts.query) if parts.query else "")
        matches = []
        for rule in entry.rulelines:
            path = unquote(rule.path)
            anchored = path.endswith("$")
            expression = path[:-1] if anchored else path
            pattern = "^" + ".*".join(re.escape(piece) for piece in expression.split("*")) + ("$" if anchored else "")
            if re.match(pattern, target):
                matches.append((len(expression.replace("*", "")), rule.allowance))
        return max(matches)[1] if matches else True

    def get(self, url: str, *, check_robots: bool = True):
        self.validate(url)
        if check_robots and self.robots is None:
            self.load_robots()
        if check_robots and not self.robots_allows(url):
            raise AccessBlocked(f"robots.txt disallows {url}")
        for attempt in range(4):
            time.sleep(max(0.0, self.delay - (time.monotonic() - self.last_request)))
            self.last_request = time.monotonic()
            response = self.session.get(url, timeout=(15, 60), allow_redirects=False, stream=True)
            if response.status_code in (301, 302, 303, 307, 308):
                location = urljoin(url, response.headers.get("Location", ""))
                response.close()
                location = self.validate(location)
                if attempt == 3:
                    raise PublicCrawlError(f"too many redirects: {url}")
                url = location
                if check_robots and not self.robots_allows(url):
                    raise AccessBlocked(f"robots.txt disallows redirect {url}")
                continue
            if response.status_code in (401, 403):
                response.close()
                raise AccessBlocked(f"HTTP {response.status_code} access restriction: {url}")
            if response.status_code in (408, 425, 429, 500, 502, 503, 504) and attempt < 3:
                retry = response.headers.get("Retry-After", "")
                response.close()
                try:
                    wait = float(retry)
                except ValueError:
                    try:
                        wait = (parsedate_to_datetime(retry) - datetime.now(timezone.utc)).total_seconds()
                    except (ValueError, TypeError):
                        wait = 2 ** (attempt + 1)
                time.sleep(max(self.delay, wait))
                continue
            try:
                response.raise_for_status()
            except requests.HTTPError:
                response.close()
                raise
            chunks, size = [], 0
            for chunk in response.iter_content(65536):
                size += len(chunk)
                if size > MAX_BYTES:
                    response.close()
                    raise PublicCrawlError(f"response exceeds {MAX_BYTES} bytes: {url}")
                chunks.append(chunk)
            response._content = b"".join(chunks)
            response._content_consumed = True
            response.encoding = "utf-8"
            return response
        raise PublicCrawlError(f"request failed: {url}")


class RawCheckpoint:
    def __init__(self, directory: Path):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        (self.directory / "raw").mkdir(exist_ok=True)
        self.db = sqlite3.connect(self.directory / "checkpoint.sqlite")
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("CREATE TABLE IF NOT EXISTS pages (url TEXT PRIMARY KEY, lastmod TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'pending', final_url TEXT, raw_path TEXT, error TEXT, fetched_at TEXT)")
        self.db.execute("CREATE TABLE IF NOT EXISTS discovery (url TEXT PRIMARY KEY, done INTEGER NOT NULL DEFAULT 0)")
        self.db.commit()

    def add(self, urls):
        rows = [(x, "") if isinstance(x, str) else x for x in urls]
        self.db.executemany("INSERT OR IGNORE INTO pages(url,lastmod) VALUES (?,?)", rows)
        self.db.executemany("UPDATE pages SET lastmod=?,status='pending',error=NULL WHERE url=? AND lastmod<>?", [(lastmod, url, lastmod) for url, lastmod in rows if lastmod])
        self.db.commit()

    def save(self, url, lastmod, response):
        relative = "raw/" + hashlib.sha256(url.encode()).hexdigest() + "-" + hashlib.sha256(response.text.encode("utf-8")).hexdigest() + ".html.gz"
        with gzip.open(self.directory / relative, "wt", encoding="utf-8") as stream:
            stream.write(response.text)
        self.db.execute("UPDATE pages SET status='downloaded', final_url=?,raw_path=?,error=NULL,fetched_at=? WHERE url=?", (response.url, relative, datetime.now(timezone.utc).isoformat(), url))
        self.db.commit()

    def fail(self, url, error):
        self.db.execute("UPDATE pages SET status='failed',error=? WHERE url=?", (str(error), url))
        self.db.commit()

    def counts(self):
        return dict(self.db.execute("SELECT status,count(*) FROM pages GROUP BY status"))

    def downloaded(self):
        for url, lastmod, final_url, raw_path in self.db.execute("SELECT url,lastmod,final_url,raw_path FROM pages WHERE status='downloaded' ORDER BY url"):
            with gzip.open(self.directory / raw_path, "rt", encoding="utf-8") as stream:
                yield url, lastmod, final_url, stream.read()


def crawl_urls(source_key, origin, urls, output, *, normalizer=None, delay=1.0, retry_failed=False, max_recipes=None, client=None):
    """Download all supplied URLs sequentially; independent sources can run in parallel."""
    checkpoint = RawCheckpoint(Path(output))
    checkpoint.add(urls)
    if retry_failed:
        checkpoint.db.execute("UPDATE pages SET status='pending',error=NULL WHERE status='failed'")
        checkpoint.db.commit()
    client = client or PublicHttpClient(origin, delay=delay)
    downloaded = 0
    for url, lastmod in checkpoint.db.execute("SELECT url,lastmod FROM pages WHERE status='pending' ORDER BY lastmod DESC,url").fetchall():
        if max_recipes is not None and downloaded >= max_recipes:
            break
        try:
            response = client.get(url)
            checkpoint.save(url, lastmod, response)
            downloaded += 1
        except AccessBlocked as exc:
            checkpoint.fail(url, exc)
            print(json.dumps({"sourceKey": source_key, "blocked": str(exc), "counts": checkpoint.counts()}), flush=True)
            break
        except (requests.RequestException, PublicCrawlError) as exc:
            checkpoint.fail(url, exc)
        if downloaded % 25 == 0:
            print(json.dumps({"sourceKey": source_key, "counts": checkpoint.counts()}), flush=True)
    status = {"sourceKey": source_key, "updatedAt": datetime.now(timezone.utc).isoformat(), "counts": checkpoint.counts()}
    (Path(output) / "download-status.json").write_text(json.dumps(status, indent=2), encoding="utf-8")
    if normalizer is not None:
        return export_checkpoint(source_key, checkpoint, normalizer)
    return checkpoint


def export_checkpoint(source_key, checkpoint, normalizer, *, discovery_complete=False, coverage_note="", catalog_name=None):
    from .full_schema import collection_hash, document_sizes, ensure_full_record, firestore_recipe_payload, firestore_detail_payload, firestore_source_payload
    from .import_catalog import compute_catalog_hash
    checkpoint.db.execute("BEGIN")
    counts = checkpoint.counts()
    discovery_error = ""
    if discovery_complete:
        try:
            discovery = json.loads((checkpoint.directory / "discovery.json").read_text(encoding="utf-8"))
            rows = discovery.get("urls", []) if isinstance(discovery, dict) else discovery
            if isinstance(discovery, dict) and discovery.get("complete") is not True:
                raise ValueError("discovery does not certify completeness")
            expected_urls = {item if isinstance(item, str) else item[0] for item in rows}
            checkpoint_urls = {row[0] for row in checkpoint.db.execute("SELECT url FROM pages")}
            if not expected_urls or expected_urls != checkpoint_urls:
                raise ValueError("checkpoint URLs do not match the complete discovery; use a fresh source output directory for changed discovery")
        except (OSError, ValueError, TypeError, IndexError, KeyError) as exc:
            discovery_error = str(exc)
            discovery_complete = False
    records, failures, exclusions = [], [], []
    for url, lastmod, final_url, html_text in checkpoint.downloaded():
        try:
            record = normalizer(html_text, source_key=source_key, source_url=final_url or url, sitemap_last_modified=lastmod)
            if record is None:
                exclusions.append({"url": url, "reason": "not_a_recipe"})
            else:
                ensure_full_record(record)
                records.append(record)
        except RecipeExcluded as exc:
            exclusions.append({"url": url, "reason": str(exc)})
        except Exception as exc:
            failures.append({"url": url, "error": str(exc)})
    transport_failures = [{"url": url, "error": error} for url, error in checkpoint.db.execute("SELECT url,error FROM pages WHERE status='failed'")]
    checkpoint.db.commit()
    unique = {}
    for record in records:
        if record["id"] in unique and unique[record["id"]] != record:
            failures.append({"url": record["sourceUrl"], "error": "duplicate provider recipe identity"})
        else:
            unique[record["id"]] = record
    duplicate_canonical_count = len(records) - len(unique)
    records = sorted(unique.values(), key=lambda row: row["id"])
    name = catalog_name or f"{source_key}-full"
    target = checkpoint.directory / f"{name}.jsonl"
    temporary = target.with_name(target.name + f".{os.getpid()}.tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as stream:
        for record in records:
            stream.write(json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n")
    temporary.replace(target)
    normalization_failure_count = len(failures)
    failures.extend(transport_failures)
    sizes = [document_sizes(record) for record in records]
    active_ids = sorted((record["id"] for record in records if record["active"]), key=lambda value: (0, int(value)) if value.isdigit() else (1, value))
    manifest = {
        "sourceKey": source_key, "language": "el", "generatedAt": datetime.now(timezone.utc).isoformat(),
        "detailSchemaVersion": f"{source_key}-full-v1",
        "complete": bool(records) and discovery_complete and not failures and not counts.get("pending", 0),
        "discoveryComplete": discovery_complete, "coverageNote": coverage_note or ("Download in progress; checkpoint snapshot only." if counts.get("pending", 0) else "Public recipe discovery snapshot."),
        "snapshotValidated": bool(records) and not normalization_failure_count and not discovery_error,
        "discoveryValidationError": discovery_error,
        "normalizationFailureCount": normalization_failure_count,
        "artifactSha256": hashlib.sha256(target.read_bytes()).hexdigest(),
        "activeRecipeCount": len(active_ids), "failedRecipeCount": len(failures),
        "discoveredActiveRecipeCount": len(active_ids), "outputRecipeCount": len(records),
        "detailRecipeCount": len(records), "sourcePayloadCount": len(records),
        "activeIdsHash": hashlib.sha256(json.dumps(active_ids, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest(),
        "excludedRecipeCount": len(exclusions), "discoveredRecipeCount": sum(counts.values()),
        "excludedRecipeUrls": exclusions,
        "excludedRecipeUrlsHash": hashlib.sha256(json.dumps(exclusions, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest(),
        "duplicateCanonicalRecordCount": duplicate_canonical_count,
        "pendingRecipeCount": counts.get("pending", 0),
        "catalogHash": compute_catalog_hash(records),
        "summaryHash": collection_hash(records, firestore_recipe_payload),
        "detailHash": collection_hash(records, firestore_detail_payload),
        "sourcePayloadHash": collection_hash(records, firestore_source_payload),
        "oversizedDocumentCount": 0,
        "maximumSummaryDocumentBytes": max((size[0] for size in sizes), default=0),
        "maximumDetailDocumentBytes": max((size[1] for size in sizes), default=0),
        "maximumSourcePayloadDocumentBytes": max((size[2] for size in sizes), default=0),
        "catalogFile": target.name,
    }
    for suffix, value in (("failures.json", failures), ("exclusions.json", exclusions), ("manifest.json", manifest)):
        destination = checkpoint.directory / f"{name}.{suffix}"
        staged = destination.with_name(destination.name + f".{os.getpid()}.tmp")
        staged.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False), encoding="utf-8")
        staged.replace(destination)
    return manifest
