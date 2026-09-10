import json
from pathlib import Path
from urllib.robotparser import RobotFileParser

import pytest

from tools.recipe_importer.crawl_cookpad import discover_links
from tools.recipe_importer.public_recipe_crawler import AccessBlocked, PublicCrawlError, PublicHttpClient, RawCheckpoint, export_checkpoint
from tools.recipe_importer.cookpad_schema import normalize_cookpad_page
from tools.recipe_importer.import_catalog import validate_manifest


def test_transport_rejects_external_redirect_targets_and_private_paths():
    client = PublicHttpClient("https://cookpad.com")
    with pytest.raises(PublicCrawlError):
        client.validate("https://example.com/gr/sintages/1")
    with pytest.raises(PublicCrawlError):
        client.validate("https://user:pass@cookpad.com/gr/sintages/1")
    robots = RobotFileParser()
    robots.parse(["User-agent: *", "Disallow: /gr/private/"])
    client.robots = robots
    with pytest.raises(AccessBlocked):
        client.get("https://cookpad.com/gr/private/1")


def test_discovery_follows_only_greek_read_only_recipe_search_urls():
    recipes, searches = discover_links('''
    <a href="/gr/sintages/42-soup">recipe</a>
    <a href="/gr/sintages/42/diorthosi">edit</a>
    <a href="/us/recipes/99">foreign</a>
    <a href="https://example.com/gr/sintages/44">external</a>
    <a href="/gr/anazitisi/soup?page=2&amp;event=tracking">next</a>
    <a href="/gr/xristes/55">profile</a>
    ''', "https://cookpad.com/gr")
    assert recipes == {"https://cookpad.com/gr/sintages/42"}
    assert searches == {"https://cookpad.com/gr/anazitisi/soup?page=2"}


def test_checkpoint_manifest_cannot_claim_pending_or_unknown_discovery_complete(tmp_path):
    checkpoint = RawCheckpoint(tmp_path)
    checkpoint.add(["https://cookpad.com/gr/sintages/42", "https://cookpad.com/gr/sintages/43"])
    html = '''<html lang="el"><h1>Σαλάτα</h1><section id="ingredients">
    <li id="ingredient_1">2 ντομάτες</li></section><section id="steps">
    <li id="step_1"><p>Κόβουμε τις ντομάτες.</p></li></section></html>'''
    class Response:
        text = html
        url = "https://cookpad.com/gr/sintages/42"
    checkpoint.save(Response.url, "", Response())
    manifest = export_checkpoint("cookpad", checkpoint, normalize_cookpad_page, discovery_complete=False, coverage_note="Public graph only")
    assert manifest["complete"] is False
    assert manifest["snapshotValidated"] is True
    assert manifest["activeRecipeCount"] == 1
    assert manifest["pendingRecipeCount"] == 1
    assert manifest["normalizationFailureCount"] == 0
    assert len(manifest["artifactSha256"]) == 64
    checkpoint.db.execute("DELETE FROM pages WHERE status='pending'")
    checkpoint.db.commit()
    (tmp_path / "discovery.json").write_text(json.dumps({"complete": True, "urls": [Response.url]}), encoding="utf-8")
    manifest = export_checkpoint("cookpad", checkpoint, normalize_cookpad_page, discovery_complete=True)
    records = [json.loads(line) for line in (tmp_path / "cookpad-full.jsonl").read_text(encoding="utf-8").splitlines()]
    validate_manifest(records, manifest)


def test_normalization_failure_never_marks_snapshot_valid(tmp_path):
    checkpoint = RawCheckpoint(tmp_path)
    checkpoint.add(["https://cookpad.com/gr/sintages/42"])
    class Response:
        text = "<html>no recipe</html>"
        url = "https://cookpad.com/gr/sintages/42"
    checkpoint.save(Response.url, "", Response())
    manifest = export_checkpoint("cookpad", checkpoint, normalize_cookpad_page)
    assert manifest["snapshotValidated"] is False
    assert manifest["complete"] is False
    assert manifest["normalizationFailureCount"] == 1


def test_robots_wildcards_and_longest_allow_are_honored():
    client = PublicHttpClient("https://cookpad.com")
    client.robots = RobotFileParser()
    client.robots.parse(["User-agent: *", "Disallow: /*/recipes/*/reactions", "Disallow: /private/*", "Allow: /private/public$", "Allow: /"])
    assert not client.robots_allows("https://cookpad.com/gr/recipes/42/reactions")
    assert not client.robots_allows("https://cookpad.com/private/secret")
    assert client.robots_allows("https://cookpad.com/private/public")
    assert not client.robots_allows("https://cookpad.com/private/public/more")
    assert client.robots_allows("https://cookpad.com/gr/sintages/42")


def test_changed_lastmod_invalidates_only_changed_source_page(tmp_path):
    checkpoint = RawCheckpoint(tmp_path)
    url = "https://cookpad.com/gr/sintages/42"
    checkpoint.add([(url, "2026-01-01")])
    class Response:
        text = "original body"
    response = Response()
    response.url = url
    checkpoint.save(url, "2026-01-01", response)
    old_raw = checkpoint.db.execute("SELECT raw_path FROM pages").fetchone()[0]
    checkpoint.add([(url, "2026-01-01")])
    assert checkpoint.counts() == {"downloaded": 1}
    checkpoint.add([(url, "2026-02-01")])
    assert checkpoint.counts() == {"pending": 1}
    response.text = "new body"
    checkpoint.save(url, "2026-02-01", response)
    assert checkpoint.db.execute("SELECT raw_path FROM pages").fetchone()[0] != old_raw
    assert (tmp_path / old_raw).exists()


def test_complete_export_requires_matching_persisted_discovery(tmp_path):
    checkpoint = RawCheckpoint(tmp_path)
    checkpoint.add(["https://cookpad.com/gr/sintages/42"])
    (tmp_path / "discovery.json").write_text(json.dumps({"complete": True, "urls": ["https://cookpad.com/gr/sintages/99"]}), encoding="utf-8")
    manifest = export_checkpoint("cookpad", checkpoint, normalize_cookpad_page, discovery_complete=True)
    assert manifest["complete"] is False
    assert manifest["snapshotValidated"] is False
    assert "checkpoint URLs" in manifest["discoveryValidationError"]


def test_funkycook_discovery_accounts_for_nested_post_paths():
    from tools.recipe_importer.crawl_funkycook import discover
    class Response:
        def __init__(self, text):
            self.content = text.encode("utf-8")
    class Client:
        def get(self, url):
            if url.endswith("sitemap_index.xml"):
                return Response('<sitemapindex><sitemap><loc>https://funkycook.gr/post-sitemap.xml</loc></sitemap></sitemapindex>')
            return Response('<urlset><url><loc>https://funkycook.gr/new-recipes/page/2/</loc></url><url><loc>https://funkycook.gr/soup/</loc><lastmod>2026-09-10</lastmod></url></urlset>')
    assert discover(Client()) == [("https://funkycook.gr/new-recipes/page/2/", ""), ("https://funkycook.gr/soup/", "2026-09-10")]


def test_funkycook_promotion_never_publishes_incomplete_catalog(tmp_path):
    from tools.recipe_importer.crawl_funkycook import promote_complete
    source = tmp_path / "raw"
    output = tmp_path / "published"
    source.mkdir()
    assert promote_complete(source, output, {"complete": False}) is False
    assert not output.exists()
    for suffix in ("jsonl", "failures.json", "exclusions.json", "manifest.json"):
        (source / f"funkycook-greek-full.{suffix}").write_text(suffix, encoding="utf-8")
    assert promote_complete(source, output, {"complete": True}) is True
    assert (output / "funkycook-greek-full.jsonl").read_text(encoding="utf-8") == "jsonl"
    assert (output / "funkycook-greek-full.manifest.json").read_text(encoding="utf-8") == "manifest.json"
