"""Download the authorized Funky Cook post-sitemap catalog with resumable raw pages."""
from __future__ import annotations

import argparse
import json
import os
import shutil
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import urlsplit

from .public_recipe_crawler import PublicHttpClient, RawCheckpoint, crawl_urls, export_checkpoint

ORIGIN = "https://funkycook.gr"
SITEMAP = ORIGIN + "/sitemap_index.xml"


def discover(client):
    root = ET.fromstring(client.get(SITEMAP).content)
    children = [node.text for node in root.findall("{*}sitemap/{*}loc") if re.fullmatch(r"/post-sitemap\d*\.xml", urlsplit(node.text or "").path)]
    if not children:
        raise RuntimeError("Funky Cook post sitemaps missing")
    urls = {}
    for sitemap in children:
        tree = ET.fromstring(client.get(sitemap).content)
        for node in tree.findall("{*}url"):
            location = node.findtext("{*}loc")
            parts = urlsplit(location or "")
            if parts.hostname not in ("funkycook.gr", "www.funkycook.gr") or parts.scheme not in {"http", "https"} or parts.username or parts.password or parts.port not in (None, 80, 443) or parts.query or parts.fragment:
                raise ValueError(f"unexpected post sitemap URL: {location}")
            location = ORIGIN + parts.path
            urls[location] = node.findtext("{*}lastmod") or ""
    return sorted(urls.items())


def promote_complete(directory, publish_dir, manifest):
    """Replace default builder inputs only after a fully validated source export."""
    if manifest.get("complete") is not True:
        return False
    name = "funkycook-greek-full"
    publish_dir.mkdir(parents=True, exist_ok=True)
    # The manifest is the publication marker and must be replaced last.
    for suffix in ("jsonl", "failures.json", "exclusions.json", "manifest.json"):
        source = directory / f"{name}.{suffix}"
        destination = publish_dir / source.name
        if source.resolve() == destination.resolve():
            continue
        temporary = destination.with_name(f".{destination.name}.{os.getpid()}.tmp")
        shutil.copyfile(source, temporary)
        temporary.replace(destination)
    return True


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--i-have-permission", action="store_true", required=True)
    parser.add_argument("--output-dir", type=Path, default=Path("build/recipe-importer/funkycook"))
    parser.add_argument("--publish-dir", type=Path, default=Path(__file__).parent / "output", help="Complete catalogs only: canonical builder input directory")
    parser.add_argument("--raw-only", action="store_true")
    parser.add_argument("--export-only", action="store_true")
    parser.add_argument("--retry-failed", action="store_true")
    parser.add_argument("--max-recipes", type=int)
    args = parser.parse_args(argv)
    if args.export_only:
        checkpoint = RawCheckpoint(args.output_dir)
    else:
        client = PublicHttpClient(ORIGIN)
        robots = client.load_robots()
        urls = discover(client)
        args.output_dir.mkdir(parents=True, exist_ok=True)
        (args.output_dir / "robots.txt").write_text(robots, encoding="utf-8")
        (args.output_dir / "discovery.json").write_text(json.dumps(urls, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"sourceKey": "funkycook", "discovered": len(urls)}), flush=True)
        checkpoint = crawl_urls("funkycook", ORIGIN, urls, args.output_dir, client=client, retry_failed=args.retry_failed, max_recipes=args.max_recipes)
    if not args.raw_only:
        from .funkycook_schema import normalize_funkycook_page
        manifest = export_checkpoint("funkycook", checkpoint, normalize_funkycook_page, discovery_complete=True, coverage_note="All post-sitemap entries; non-recipe articles are explicitly excluded.", catalog_name="funkycook-greek-full")
        promote_complete(args.output_dir, args.publish_dir, manifest)
        print(json.dumps(manifest, ensure_ascii=False), flush=True)
        return 0 if manifest["complete"] else 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
