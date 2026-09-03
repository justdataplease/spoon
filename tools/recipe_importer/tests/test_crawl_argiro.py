"""Network-free tests for the permission-gated Argiro crawler scaffold."""

import json

import pytest

from tools.recipe_importer import crawl_argiro
from tools.recipe_importer import import_catalog
from tools.recipe_importer.crawl_argiro import (
    AUDITED_CANONICAL_ALIASES,
    AUDITED_EXTERNAL_REDIRECTS,
    AUDITED_INTERNAL_STALE_REDIRECTS,
    AUDITED_NON_GREEK_STUBS,
    CheckpointStore,
    DiscoveredRecipe,
    DiscoveryResult,
    IncompleteArgiroCrawl,
    canonical_recipe_location,
    checkpoint_run_key,
    parse_sitemap,
    parser_contract_hash,
    run_argiro_crawl,
    validate_checkpoint_record,
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


def test_canonical_recipe_location_normalizes_percent_escape_hex_case():
    assert canonical_recipe_location(
        "https://www.argiro.gr/recipe/christopso%c2%b5o/"
    ) == "https://www.argiro.gr/recipe/christopso%C2%B5o/"


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
        store.put("https://www.argiro.gr/recipe/no-lastmod/", "", {"id": "argiro_2"})
        assert store.get("https://www.argiro.gr/recipe/no-lastmod/", "") is None
    finally:
        store.close()


def test_audited_inventory_exceptions_and_content_contract_are_exact():
    assert len(AUDITED_EXTERNAL_REDIRECTS) == 5
    assert len(AUDITED_INTERNAL_STALE_REDIRECTS) == 1
    assert len(AUDITED_NON_GREEK_STUBS) == 3
    assert len(AUDITED_CANONICAL_ALIASES) == 8
    assert len(parser_contract_hash()) == 64
    assert len(checkpoint_run_key()) == 64


def test_checkpoint_record_requires_exact_greek_sitemap_identity(monkeypatch):
    source_url = "https://www.argiro.gr/recipe/synthetic-one/"
    lastmod = "2026-01-01"
    value = {
        "id": "argiro_123",
        "sourceKey": "argiro",
        "providerRecipeId": "123",
        "sourceUrl": source_url,
        "canonicalUrl": source_url,
        "sitemapLastModified": lastmod,
        "language": "el",
        "active": True,
    }
    monkeypatch.setattr(crawl_argiro, "ensure_full_record", lambda record: None)
    assert validate_checkpoint_record(value, source_url, lastmod) == value
    with pytest.raises(crawl_argiro.FullSchemaError, match="identity"):
        validate_checkpoint_record(value | {"language": "en"}, source_url, lastmod)
    with pytest.raises(crawl_argiro.FullSchemaError, match="identity"):
        validate_checkpoint_record(
            value | {"canonicalUrl": "https://www.argiro.gr/recipe/other/"},
            source_url,
            lastmod,
        )


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
    monkeypatch.setattr(crawl_argiro, "AUDITED_EXTERNAL_REDIRECTS", {
        redirect_url: {
            "finalUrl": "https://www.argiro.gr/recipe-category/glika/",
            "finalStatus": 200,
        },
    })
    monkeypatch.setattr(crawl_argiro, "AUDITED_NON_GREEK_STUBS", {})
    monkeypatch.setattr(crawl_argiro, "AUDITED_CANONICAL_ALIASES", {})
    monkeypatch.setattr(crawl_argiro, "AUDITED_INTERNAL_STALE_REDIRECTS", {})
    client = _DetailClient({
        recipe_url: recipe_url,
        redirect_url: "https://www.argiro.gr/recipe-category/glika/",
    })
    monkeypatch.setattr(
        crawl_argiro,
        "normalize_argiro_page",
        lambda html, *, source_url, sitemap_last_modified: {
            "id": "argiro_1",
            "sourceUrl": source_url,
            "canonicalUrl": source_url,
            "providerRecipeId": "1",
            "ingredientSections": [{"ingredients": [{}]}],
            "methodSections": [{"steps": ["synthetic"]}],
        },
    )
    monkeypatch.setattr(
        crawl_argiro,
        "validate_checkpoint_record",
        lambda record, sitemap_url, sitemap_last_modified: dict(record),
    )
    monkeypatch.setattr(import_catalog, "compute_catalog_hash", lambda records: "1" * 64)

    paths = _run_paths(tmp_path)
    manifest = run_argiro_crawl(client=client, robots=object(), **paths)

    assert manifest["discoveredRecipeUrlCount"] == 2
    assert manifest["outputRecipeCount"] == 1
    assert manifest["excludedRecipeUrlCount"] == 1
    assert manifest["failedRecipeCount"] == 0
    assert manifest["excludedRecipeUrls"] == [{
        "kind": "externalRedirect",
        "sourceUrl": redirect_url,
        "finalUrl": "https://www.argiro.gr/recipe-category/glika/",
        "finalStatus": 200,
        "reason": "redirected outside /recipe/{slug}/",
    }]
    report = json.loads(paths["failures_path"].read_text(encoding="utf-8"))
    assert report["complete"] is True
    assert report["failures"] == []


def test_internal_stale_recipe_redirect_is_excluded_without_aliasing_content(
    monkeypatch, tmp_path
):
    target_url = "https://www.argiro.gr/recipe/surviving-target/"
    stale_url = "https://www.argiro.gr/recipe/distinct-stale-source/"
    _configure_discovery(monkeypatch, [target_url, stale_url])
    monkeypatch.setattr(crawl_argiro, "AUDITED_EXTERNAL_REDIRECTS", {})
    monkeypatch.setattr(crawl_argiro, "AUDITED_NON_GREEK_STUBS", {})
    monkeypatch.setattr(crawl_argiro, "AUDITED_CANONICAL_ALIASES", {})
    monkeypatch.setattr(crawl_argiro, "AUDITED_INTERNAL_STALE_REDIRECTS", {
        stale_url: {"finalUrl": target_url, "finalStatus": 200},
    })
    client = _DetailClient({target_url: target_url, stale_url: target_url})
    monkeypatch.setattr(
        crawl_argiro,
        "normalize_argiro_page",
        lambda html, *, source_url, sitemap_last_modified: {
            "id": "argiro_1",
            "sourceUrl": source_url,
            "canonicalUrl": source_url,
            "providerRecipeId": "1",
            "ingredientSections": [{"ingredients": [{}]}],
            "methodSections": [{"steps": ["synthetic"]}],
        },
    )
    monkeypatch.setattr(
        crawl_argiro,
        "validate_checkpoint_record",
        lambda record, sitemap_url, sitemap_last_modified: dict(record),
    )
    monkeypatch.setattr(import_catalog, "compute_catalog_hash", lambda records: "1" * 64)

    paths = _run_paths(tmp_path)
    manifest = run_argiro_crawl(client=client, robots=object(), **paths)

    assert manifest["outputRecipeCount"] == 1
    assert manifest["canonicalAliasCount"] == 0
    assert manifest["internalStaleRedirectExclusionCount"] == 1
    assert manifest["excludedRecipeUrls"] == [{
        "kind": "internalStaleRedirect",
        "sourceUrl": stale_url,
        "finalUrl": target_url,
        "finalStatus": 200,
        "reason": (
            "redirected to a distinct surviving recipe; "
            "content substitution is forbidden"
        ),
    }]


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
