"""Network-free tests for the permission-gated Argiro crawler scaffold."""

import json

import pytest

from tools.recipe_importer import crawl_argiro
from tools.recipe_importer.crawl_argiro import (
    CheckpointStore,
    DiscoveredRecipe,
    DiscoveryResult,
    IncompleteArgiroCrawl,
    canonical_recipe_location,
    parse_sitemap,
    run_argiro_crawl,
)


def test_sitemap_index_keeps_only_recipe_sitemaps_and_upgrades_declared_http_urls():
    xml = """<?xml version="1.0"?><sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
      <sitemap><loc>http://www.argiro.gr/recipe-sitemap.xml</loc></sitemap>
      <sitemap><loc>http://www.argiro.gr/recipe-sitemap4.xml</loc></sitemap>
      <sitemap><loc>https://www.argiro.gr/post-sitemap.xml</loc></sitemap>
    </sitemapindex>"""
    recipes, children, declared = parse_sitemap(xml)
    assert recipes == []
    assert declared == 0
    assert children == [
        "https://www.argiro.gr/recipe-sitemap.xml",
        "https://www.argiro.gr/recipe-sitemap4.xml",
    ]


def test_recipe_sitemap_requires_every_declared_entry_to_be_canonical_greek_recipe():
    xml = """<?xml version="1.0"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
      <url><loc>http://www.argiro.gr/recipe/synthetic-one/</loc><lastmod>2026-01-02</lastmod></url>
      <url><loc>https://argiro.gr/not-recipe/synthetic-two/</loc></url>
    </urlset>"""
    recipes, children, declared = parse_sitemap(xml)
    assert children == []
    assert declared == 2
    assert [item.source_url for item in recipes] == [
        "https://www.argiro.gr/recipe/synthetic-one/"
    ]
    assert canonical_recipe_location("https://evil.test/recipe/no/") is None


def test_cli_refuses_to_initialize_network_without_argiro_permission(monkeypatch, capsys):
    monkeypatch.setattr(
        crawl_argiro,
        "ArgiroHttpClient",
        lambda **kwargs: (_ for _ in ()).throw(AssertionError("network must stay closed")),
    )
    assert crawl_argiro.main([]) == 2
    assert "--i-have-argiro-permission" in capsys.readouterr().err


def test_checkpoint_keeps_unchanged_urls_when_catalog_inventory_changes(tmp_path):
    path = tmp_path / "argiro.sqlite3"
    store = CheckpointStore(path)
    try:
        store.prepare("schema-parser-v1", resume=True)
        store.put("https://www.argiro.gr/recipe/one/", "2026-01-01", {"id": "argiro_1"})
        # The inventory is deliberately not part of this key. A new sitemap URL
        # must not discard the unchanged 3k+ page cache.
        store.prepare("schema-parser-v1", resume=True)
        assert store.get(
            "https://www.argiro.gr/recipe/one/", "2026-01-01"
        ) == {"id": "argiro_1"}
        assert store.get(
            "https://www.argiro.gr/recipe/one/", "2026-01-02"
        ) is None
    finally:
        store.close()


class _Response:
    def __init__(self, url: str, text: str = "synthetic", status_code: int = 200) -> None:
        self.url = url
        self.text = text
        self.status_code = status_code


class _DetailClient:
    def __init__(self, final_urls: dict[str, str]) -> None:
        self.final_urls = final_urls
        self.calls: list[str] = []

    def get(self, url, *, robots):
        del robots
        self.calls.append(url)
        return _Response(self.final_urls[url])


def _run_paths(tmp_path):
    return {
        "output_path": tmp_path / "argiro.jsonl",
        "manifest_path": tmp_path / "argiro.manifest.json",
        "failures_path": tmp_path / "argiro.failures.json",
        "checkpoint_path": tmp_path / "argiro.sqlite3",
    }


def _configure_discovery(monkeypatch, urls):
    recipes = {url: DiscoveredRecipe(url, "2026-01-01") for url in urls}
    monkeypatch.setattr(
        crawl_argiro,
        "discover_recipes",
        lambda client, robots: DiscoveryResult(
            recipes=recipes,
            recipe_sitemaps=4,
            declared_entries=len(recipes),
            duplicate_entries=0,
        ),
    )
    monkeypatch.setattr(
        crawl_argiro,
        "document_sizes",
        lambda record: (100, 200, 300),
    )
    monkeypatch.setattr(
        crawl_argiro,
        "collection_hash",
        lambda records, projector: "0" * 64,
    )


def test_non_recipe_redirect_is_an_explicit_exclusion_not_a_failure(monkeypatch, tmp_path):
    recipe_url = "https://www.argiro.gr/recipe/kept/"
    redirect_url = "https://www.argiro.gr/recipe/redirected/"
    _configure_discovery(monkeypatch, [recipe_url, redirect_url])
    client = _DetailClient({
        recipe_url: recipe_url,
        redirect_url: "https://www.argiro.gr/recipe-category/glika/",
    })
    monkeypatch.setattr(
        crawl_argiro,
        "normalize_argiro_page",
        lambda html, *, source_url, sitemap_last_modified: {"id": "argiro_1"},
    )

    paths = _run_paths(tmp_path)
    manifest = run_argiro_crawl(client=client, robots=object(), **paths)

    assert manifest["discoveredRecipeUrlCount"] == 2
    assert manifest["outputRecipeCount"] == 1
    assert manifest["excludedRecipeUrlCount"] == 1
    assert manifest["failedRecipeCount"] == 0
    assert manifest["excludedRecipeUrls"] == [{
        "sourceUrl": redirect_url,
        "finalUrl": "https://www.argiro.gr/recipe-category/glika/",
        "reason": "redirected outside /recipe/{slug}/",
    }]
    report = json.loads(paths["failures_path"].read_text(encoding="utf-8"))
    assert report["complete"] is True
    assert report["failures"] == []


def test_schema_error_retries_three_fresh_fetches_then_reports_failure(monkeypatch, tmp_path):
    recipe_url = "https://www.argiro.gr/recipe/broken/"
    _configure_discovery(monkeypatch, [recipe_url])
    client = _DetailClient({recipe_url: recipe_url})
    attempts = []

    def fail_closed(html, *, source_url, sitemap_last_modified):
        del html, source_url, sitemap_last_modified
        attempts.append(1)
        raise crawl_argiro.FullSchemaError("synthetic mismatch")

    monkeypatch.setattr(crawl_argiro, "normalize_argiro_page", fail_closed)
    reported = []
    paths = _run_paths(tmp_path)
    with pytest.raises(IncompleteArgiroCrawl, match="1 failures"):
        run_argiro_crawl(
            client=client,
            robots=object(),
            on_failure=lambda source, error: reported.append((source, error)),
            **paths,
        )

    assert len(attempts) == 3
    assert client.calls == [recipe_url, recipe_url, recipe_url]
    assert reported == [(recipe_url, "synthetic mismatch")]
    report = json.loads(paths["failures_path"].read_text(encoding="utf-8"))
    assert report == {
        "complete": False,
        "failedRecipeCount": 1,
        "failures": [{"sourceUrl": recipe_url, "error": "synthetic mismatch"}],
    }
    assert not paths["output_path"].exists()
