"""Tests for exact discovery, resume, incremental refresh, and completeness."""

import json
from pathlib import Path

import pytest

import tools.recipe_importer.crawl_catalog as crawler
from tools.recipe_importer.crawl_catalog import (
    CheckpointStore,
    CrawlError,
    DiscoveredRecipe,
    DiscoveryResult,
    IncompleteCrawlError,
    RateLimiter,
    canonical_recipe_location,
    discover_api_recipes,
    fetch_facet_ids,
    parse_sitemap,
    run_catalog_crawl,
)


FIXTURE = Path(__file__).parent / "fixtures" / "api_recipe.json"


def api_payload(recipe_id):
    value = json.loads(FIXTURE.read_text(encoding="utf-8"))
    value["id"] = recipe_id
    value["slug"] = f"synthetic-{recipe_id}"
    value["short_url"] = f"https://akispetretzikis.com/recipe/{recipe_id}"
    return value


def test_sitemap_selects_only_canonical_greek_numeric_recipe_urls():
    xml = """<?xml version='1.0'?>
    <urlset xmlns='http://www.sitemaps.org/schemas/sitemap/0.9'>
      <url><loc>https://akispetretzikis.com/recipe/12/greek</loc><lastmod>2026-01-01</lastmod></url>
      <url><loc>https://akispetretzikis.com/en/recipe/12/english</loc></url>
      <url><loc>https://akispetretzikis.com/recipes/list</loc></url>
      <url><loc>https://example.com/recipe/13/no</loc></url>
    </urlset>"""
    recipes, children = parse_sitemap(xml)
    assert children == []
    assert recipes == [DiscoveredRecipe("12", "https://akispetretzikis.com/recipe/12/greek", "2026-01-01")]
    assert canonical_recipe_location("https://akispetretzikis.com/en/recipe/1/x") is None


def test_sitemap_index_and_invalid_xml_are_handled_strictly():
    recipes, children = parse_sitemap(
        "<sitemapindex><sitemap><loc>https://akispetretzikis.com/sitemap-recipes.xml</loc></sitemap></sitemapindex>"
    )
    assert recipes == []
    assert children == ["https://akispetretzikis.com/sitemap-recipes.xml"]
    with pytest.raises(CrawlError, match="invalid sitemap"):
        parse_sitemap("<broken")


def test_rate_limiter_enforces_one_second_minimum_and_cli_permission_precedes_network(monkeypatch, capsys):
    assert RateLimiter(0).interval_seconds == 1.0
    monkeypatch.setattr(crawler, "AuthorizedHttpClient", lambda **kwargs: (_ for _ in ()).throw(AssertionError("no network")))
    assert crawler.main([]) == 2
    assert "--i-have-permission" in capsys.readouterr().err


class FacetClient:
    def __init__(self, pages):
        self.pages = list(pages)

    def json(self, url, *, robots):
        del url, robots
        return self.pages.pop(0)


def test_facet_pagination_requires_exact_unique_reported_total():
    pages = [
        {"data": [{"id": 1}, {"id": 2}], "meta": {"current_page": 1, "last_page": 2, "total": 3}},
        {"data": [{"id": 3}], "meta": {"current_page": 2, "last_page": 2, "total": 3}},
    ]
    assert fetch_facet_ids(FacetClient(pages), object(), "cuisine", "117") == ["1", "2", "3"]
    duplicate = [
        {"data": [{"id": 1}], "meta": {"current_page": 1, "last_page": 2, "total": 2}},
        {"data": [{"id": 1}], "meta": {"current_page": 2, "last_page": 2, "total": 2}},
    ]
    with pytest.raises(CrawlError, match="duplicate"):
        fetch_facet_ids(FacetClient(duplicate), object(), "cuisine", "117")


def test_api_inventory_keeps_a_published_recipe_while_the_sitemap_lags():
    pages = [{
        "data": [
            {
                "id": 9968,
                "slug": "keik-me-karamelomena-syka",
                "published": 1,
                "updated_at": "2026-09-03T16:10:40.000000Z",
            },
        ],
        "meta": {"current_page": 1, "last_page": 1, "total": 1},
    }]
    inventory = discover_api_recipes(FacetClient(pages), object())
    assert inventory == {
        "9968": DiscoveredRecipe(
            "9968",
            "https://akispetretzikis.com/recipe/9968/keik-me-karamelomena-syka",
            "2026-09-03T16:10:40.000000Z",
        ),
    }


def test_api_inventory_percent_encodes_an_official_slug_space():
    pages = [{
        "data": [{
            "id": 5483,
            "slug": "kotosoupa-me-kotopoulo- leftover",
            "published": 1,
            "updated_at": "2024-08-07T17:18:05.000000Z",
        }],
        "meta": {"current_page": 1, "last_page": 1, "total": 1},
    }]
    assert discover_api_recipes(FacetClient(pages), object())["5483"].source_url == (
        "https://akispetretzikis.com/recipe/5483/"
        "kotosoupa-me-kotopoulo-%20leftover"
    )


def test_sqlite_checkpoint_reuses_matching_payloads_across_inventory_drift(tmp_path):
    store = CheckpointStore(tmp_path / "checkpoint.sqlite3")
    store.prepare("run-a", "taxonomy-a", resume=True)
    store.put_payload("1", "2026-01-01", {"id": 1})
    store.put_facet_ids("cuisine", "117", "Ελλάδα", ["1"])
    assert store.get_payload("1", "2026-01-01") == {"id": 1}
    assert store.get_payload("1", "changed") is None
    assert store.get_facet_ids("cuisine", "117", "Ελλάδα") == ["1"]
    store.prepare("run-b", "taxonomy-a", resume=True)
    assert store.get_payload("1", "2026-01-01") == {"id": 1}
    assert store.get_facet_ids("cuisine", "117", "Ελλάδα") is None
    store.prepare("run-b", "taxonomy-a", resume=False)
    assert store.get_payload("1", "2026-01-01") is None
    store.close()


def configure_run(monkeypatch, recipe_ids):
    discovery = DiscoveryResult(
        {
            str(recipe_id): DiscoveredRecipe(
                str(recipe_id),
                f"https://akispetretzikis.com/recipe/{recipe_id}/synthetic-{recipe_id}",
                "2026-02-01",
            )
            for recipe_id in recipe_ids
        },
        sitemap_documents=1,
        duplicate_recipe_entries=0,
    )
    taxonomy = {facet: [{"id": f"{facet}-1", "title": f"Label {facet}"}] for facet in crawler.FACET_KEYS}
    associations = {
        str(recipe_id): {facet: [] for facet in crawler.FACET_KEYS}
        for recipe_id in recipe_ids
    }
    monkeypatch.setattr(crawler, "discover_recipes", lambda client, robots: discovery)
    monkeypatch.setattr(
        crawler,
        "discover_api_recipes",
        lambda client, robots: dict(discovery.recipes),
    )
    monkeypatch.setattr(crawler, "fetch_taxonomy", lambda client, robots: taxonomy)
    monkeypatch.setattr(
        crawler,
        "build_associations",
        lambda client, robots, taxonomy, active_ids, checkpoint: (associations, 0, 0),
    )


def paths(tmp_path, prefix="run"):
    return {
        "output_path": tmp_path / f"{prefix}.jsonl",
        "manifest_path": tmp_path / f"{prefix}.manifest.json",
        "failures_path": tmp_path / f"{prefix}.failures.json",
        "checkpoint_path": tmp_path / f"{prefix}.sqlite3",
    }


def test_complete_run_writes_atomic_manifest_and_resume_then_incremental(monkeypatch, tmp_path):
    configure_run(monkeypatch, [1, 2])
    calls = []

    def fetch(client, robots, recipe_id):
        calls.append(recipe_id)
        return api_payload(int(recipe_id))

    monkeypatch.setattr(crawler, "fetch_recipe_payload", fetch)
    first_paths = paths(tmp_path, "first")
    manifest = run_catalog_crawl(client=object(), robots=object(), **first_paths)
    assert calls == ["1", "2"]
    assert manifest["complete"] is True
    assert manifest["activeRecipeCount"] == manifest["detailRecipeCount"] == manifest["sourcePayloadCount"] == 2
    assert manifest["failedRecipeCount"] == manifest["oversizedDocumentCount"] == 0
    assert all(len(manifest[field]) == 64 for field in ("catalogHash", "summaryHash", "detailHash", "sourcePayloadHash"))
    records = [json.loads(line) for line in first_paths["output_path"].read_text(encoding="utf-8").splitlines()]
    assert [record["id"] for record in records] == ["1", "2"]

    calls.clear()
    resumed = run_catalog_crawl(client=object(), robots=object(), **first_paths)
    assert calls == []
    assert resumed["checkpointResumedRecipeCount"] == 2

    incremental_paths = paths(tmp_path, "incremental")
    incremental = run_catalog_crawl(
        client=object(), robots=object(), previous_path=first_paths["output_path"], **incremental_paths
    )
    assert calls == []
    assert incremental["incrementalReusedRecipeCount"] == 2


def test_quarterly_refresh_carries_missing_previous_id_as_explicit_inactive(monkeypatch, tmp_path):
    configure_run(monkeypatch, [1, 2])
    monkeypatch.setattr(crawler, "fetch_recipe_payload", lambda client, robots, recipe_id: api_payload(int(recipe_id)))
    first_paths = paths(tmp_path, "prior")
    run_catalog_crawl(client=object(), robots=object(), **first_paths)

    configure_run(monkeypatch, [1])
    refresh_paths = paths(tmp_path, "refresh")
    manifest = run_catalog_crawl(
        client=object(), robots=object(), previous_path=first_paths["output_path"], **refresh_paths
    )
    records = [json.loads(line) for line in refresh_paths["output_path"].read_text(encoding="utf-8").splitlines()]
    assert manifest["activeRecipeCount"] == 1
    assert manifest["retiredRecipeCount"] == 1
    assert len(records) == 2
    assert records[1]["id"] == "2" and records[1]["active"] is False
    assert records[1]["retiredDetectedAt"]


def test_any_detail_failure_is_reported_and_no_incomplete_catalog_is_published(monkeypatch, tmp_path):
    configure_run(monkeypatch, [1, 2])

    def fetch(client, robots, recipe_id):
        if recipe_id == "2":
            raise CrawlError("synthetic detail failure")
        return api_payload(1)

    monkeypatch.setattr(crawler, "fetch_recipe_payload", fetch)
    run_paths = paths(tmp_path, "failed")
    with pytest.raises(IncompleteCrawlError):
        run_catalog_crawl(client=object(), robots=object(), **run_paths)
    assert not run_paths["output_path"].exists()
    report = json.loads(run_paths["failures_path"].read_text(encoding="utf-8"))
    assert report["complete"] is False
    assert report["failedRecipeCount"] == 1
    assert report["failures"][0]["id"] == "2"
