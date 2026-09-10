"""Sitemap discovery and command interface for the Tsoulis and Lucacos sources."""
from __future__ import annotations

import argparse
import json
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

from .providers import provider_for
from .public_recipe_crawler import PublicHttpClient, RawCheckpoint, RecipeExcluded, crawl_urls, export_checkpoint


def discover_sitemap(source_key, client):
    provider = provider_for(source_key=source_key)
    pending = [client.origin + "/sitemap.xml"]
    visited, recipes = set(), {}
    client.discovery_exclusions = []
    while pending:
        url = pending.pop(0)
        if url in visited:
            continue
        if len(visited) >= 100:
            raise ValueError("sitemap discovery exceeds 100 documents")
        visited.add(url)
        root = ET.fromstring(client.get(url).content)
        kind = root.tag.rsplit("}", 1)[-1]
        if kind not in {"urlset", "sitemapindex"}:
            raise ValueError("unexpected sitemap XML document")
        for node in root:
            fields = {child.tag.rsplit("}", 1)[-1]: child.text or "" for child in node}
            location = fields.get("loc", "")
            parts = urlsplit(location)
            # The Lucacos urlset includes a public YouTube video link. It is
            # reported as non-recipe discovery metadata and is never requested.
            # Child sitemaps and recipe-looking links still require source hosts.
            if (source_key == "lucacos" and kind == "urlset"
                    and parts.hostname in {"youtu.be", "www.youtube.com", "youtube.com"}
                    and parts.scheme == "https" and parts.port in (None, 443)
                    and not parts.username and not parts.password
                    and (parts.path.startswith("/watch") or (parts.hostname == "youtu.be" and parts.path.count("/") == 1))
                    and not provider.recipe_path.fullmatch(parts.path)):
                client.discovery_exclusions.append({"url": location, "reason": "external_video_not_recipe"})
                continue
            if parts.hostname not in provider.hosts or parts.scheme not in {"https", "http"} or parts.username or parts.password:
                raise ValueError(f"sitemap contains an unapproved publisher host: {location}")
            canonical = urlunsplit(("https", provider.canonical_host, parts.path, "", ""))
            if kind == "sitemapindex":
                pending.append(client.validate(canonical))
            elif provider.recipe_path.fullmatch(parts.path):
                recipes[canonical] = fields.get("lastmod", "")
    if not recipes:
        raise ValueError("sitemap contains no canonical Greek recipes")
    return sorted(recipes.items())


def normalize_source(html_text, *, source_key, **kwargs):
    if source_key == "tsoulis":
        from .tsoulis_schema import IncompleteTsoulisRecipe, normalize_tsoulis_page
        try:
            return normalize_tsoulis_page(html_text, **kwargs)
        except IncompleteTsoulisRecipe as exc:
            raise RecipeExcluded(f"publisher_incomplete_recipe: {exc}") from exc
    from .public_recipe_schema import NonGreekRecipe, normalize_public_recipe_page
    try:
        return normalize_public_recipe_page(html_text, source_key=source_key, **kwargs)
    except NonGreekRecipe as exc:
        # Retain the exact exclusion reason separately from non-recipe pages.
        raise RecipeExcluded(f"non_greek_recipe: {exc}") from exc


def run_source(source_key, argv=None):
    parser = argparse.ArgumentParser(description=f"Download the authorized complete Greek {source_key} sitemap with resumable raw checkpoints")
    parser.add_argument(f"--i-have-{source_key}-permission", action="store_true", required=True)
    parser.add_argument("--output-dir", type=Path, default=Path(__file__).parent / "output")
    parser.add_argument("--delay", type=float, default=1.0)
    parser.add_argument("--retry-failed", action="store_true")
    parser.add_argument("--export-only", action="store_true")
    parser.add_argument("--max-recipes", type=int)
    args = parser.parse_args(argv)
    provider = provider_for(source_key=source_key)
    directory = args.output_dir / source_key
    directory.mkdir(parents=True, exist_ok=True)
    if args.export_only:
        checkpoint = RawCheckpoint(directory)
    else:
        client = PublicHttpClient(f"https://{provider.canonical_host}", delay=args.delay)
        client.load_robots()
        recipes = discover_sitemap(source_key, client)
        (directory / "discovery.json").write_text(json.dumps({"complete": True, "urls": recipes, "excludedDiscoveryUrls": client.discovery_exclusions}, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"sourceKey": source_key, "discovered": len(recipes), "delaySeconds": client.delay}), flush=True)
        checkpoint = crawl_urls(source_key, client.origin, recipes, directory, client=client, retry_failed=args.retry_failed, max_recipes=args.max_recipes)
    discovery = json.loads((directory / "discovery.json").read_text(encoding="utf-8"))
    name = f"{source_key}-greek-full"
    manifest = export_checkpoint(source_key, checkpoint, normalize_source, discovery_complete=discovery.get("complete") is True, coverage_note="Download in progress from complete publisher sitemap; pending recipes remain." if checkpoint.counts().get("pending") else "All Greek recipe URLs in the official publisher sitemap have been processed.", catalog_name=name)
    # Only complete artifacts replace the published builder inputs.
    if manifest["complete"]:
        for suffix in ("jsonl", "failures.json", "exclusions.json", "manifest.json"):
            source = directory / f"{name}.{suffix}"
            temporary = args.output_dir / f".{name}.{suffix}.tmp"
            shutil.copyfile(source, temporary)
            temporary.replace(args.output_dir / f"{name}.{suffix}")
    print(json.dumps(manifest, ensure_ascii=False, indent=2), flush=True)
    return 0 if manifest["complete"] else 2
