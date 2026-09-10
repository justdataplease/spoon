"""Resumable authorized crawl of publicly linked Greek Cookpad recipes.

Cookpad publishes no exhaustive Greek sitemap. Discovery follows its public
trending searches, pagination, recipe links, and linked recipe search tags. The
manifest always preserves that coverage limitation; it never claims all Cookpad.
"""
from __future__ import annotations

import argparse
import json
import re
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import parse_qs, urlencode, urljoin, urlsplit, urlunsplit

import requests

from .public_recipe_crawler import AccessBlocked, PublicCrawlError, PublicHttpClient, RawCheckpoint, export_checkpoint

ORIGIN = "https://cookpad.com"
COVERAGE = "Publicly linked Greek recipe/search graph; Cookpad exposes no exhaustive Greek catalog index. Full-source completeness cannot be established."


class LinkParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links = []

    def handle_starttag(self, tag, attrs):
        if tag == "a":
            href = dict(attrs).get("href")
            if href:
                self.links.append(href)


def discover_links(html_text, base_url):
    parser = LinkParser()
    parser.feed(html_text)
    recipes, searches = set(), set()
    for href in parser.links:
        parts = urlsplit(urljoin(base_url, href))
        if parts.scheme != "https" or parts.hostname != "cookpad.com" or parts.username or parts.password or parts.port not in (None, 443):
            continue
        match = re.fullmatch(r"/gr/sintages/(\d+)(?:-[^/]+)?/?", parts.path)
        if match:
            recipes.add(ORIGIN + "/gr/sintages/" + match.group(1))
        elif re.fullmatch(r"/gr/anazitisi/[^/]+", parts.path):
            query = parse_qs(parts.query)
            page = query.get("page", [""])[0]
            if page and (not page.isdigit() or int(page) < 1):
                continue
            searches.add(urlunsplit(("https", "cookpad.com", parts.path, urlencode({"page": page}) if page else "", "")))
    return recipes, searches


def enqueue_links(checkpoint, html_text, url):
    recipes, searches = discover_links(html_text, url)
    checkpoint.add(recipes)
    checkpoint.db.executemany("INSERT OR IGNORE INTO discovery(url) VALUES (?)", [(x,) for x in sorted(searches)])
    checkpoint.db.commit()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--i-have-permission", action="store_true", required=True)
    parser.add_argument("--output-dir", type=Path, default=Path("build/recipe-importer/cookpad"))
    parser.add_argument("--raw-only", action="store_true")
    parser.add_argument("--export-only", action="store_true")
    parser.add_argument("--retry-failed", action="store_true")
    parser.add_argument("--max-recipes", type=int)
    args = parser.parse_args(argv)
    checkpoint = RawCheckpoint(args.output_dir)
    if args.retry_failed:
        checkpoint.db.execute("UPDATE pages SET status='pending',error=NULL WHERE status='failed'")
        checkpoint.db.execute("UPDATE discovery SET done=0 WHERE done=-1")
        checkpoint.db.commit()
    if not args.export_only:
        client = PublicHttpClient(ORIGIN)
        (args.output_dir / "robots.txt").write_text(client.load_robots(), encoding="utf-8")
        checkpoint.db.execute("INSERT OR IGNORE INTO discovery(url) VALUES (?)", (ORIGIN + "/gr",))
        checkpoint.db.commit()
        downloaded = 0
        while args.max_recipes is None or downloaded < args.max_recipes:
            # Interleave discovery and downloads so a large search graph does not
            # postpone all recipe downloads until the end of discovery.
            discovery = checkpoint.db.execute("SELECT url FROM discovery WHERE done=0 ORDER BY rowid LIMIT 1").fetchone()
            if discovery:
                url = discovery[0]
                try:
                    response = client.get(url)
                    enqueue_links(checkpoint, response.text, response.url)
                    checkpoint.db.execute("UPDATE discovery SET done=1 WHERE url=?", (url,))
                except AccessBlocked as exc:
                    checkpoint.db.execute("UPDATE discovery SET done=-1 WHERE url=?", (url,))
                    checkpoint.db.commit()
                    print(json.dumps({"sourceKey": "cookpad", "blocked": str(exc)}), flush=True)
                    break
                except (PublicCrawlError, requests.RequestException) as exc:
                    checkpoint.db.execute("UPDATE discovery SET done=-1 WHERE url=?", (url,))
                    print(json.dumps({"sourceKey": "cookpad", "discoveryFailure": url, "error": str(exc)}), flush=True)
                checkpoint.db.commit()
            pending = checkpoint.db.execute("SELECT url,lastmod FROM pages WHERE status='pending' ORDER BY rowid LIMIT 30").fetchall()
            if not discovery and not pending:
                break
            blocked = False
            for url, lastmod in pending:
                if args.max_recipes is not None and downloaded >= args.max_recipes:
                    break
                try:
                    response = client.get(url)
                    checkpoint.save(url, lastmod, response)
                    enqueue_links(checkpoint, response.text, response.url)
                    downloaded += 1
                except AccessBlocked as exc:
                    checkpoint.fail(url, exc)
                    print(json.dumps({"sourceKey": "cookpad", "blocked": str(exc)}), flush=True)
                    blocked = True
                    break
                except (PublicCrawlError, requests.RequestException) as exc:
                    checkpoint.fail(url, exc)
            status = {"sourceKey": "cookpad", "counts": checkpoint.counts(), "discovery": dict(checkpoint.db.execute("SELECT done,count(*) FROM discovery GROUP BY done")), "coverageNote": COVERAGE}
            (args.output_dir / "download-status.json").write_text(json.dumps(status, indent=2), encoding="utf-8")
            print(json.dumps(status, ensure_ascii=False), flush=True)
            if blocked:
                break
    if not args.raw_only:
        from .cookpad_schema import normalize_cookpad_page
        manifest = export_checkpoint("cookpad", checkpoint, normalize_cookpad_page, discovery_complete=False, coverage_note=COVERAGE)
        print(json.dumps(manifest, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
