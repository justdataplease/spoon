"""Network-free tests for the permission-gated Gastronomos crawler."""

import json
import threading

import pytest

from tools.recipe_importer import crawl_gastronomos, import_catalog
from tools.recipe_importer.crawl_gastronomos import (
    AUDITED_CANONICAL_ALIASES,
    AUDITED_EXTERNAL_REDIRECTS,
    AUDITED_NON_RECIPE_SITEMAP_ENTRIES,
    AUDITED_NON_GREEK_STUBS,
    AUDITED_SOURCE_INCOMPLETE_PAGES,
    USER_AGENT,
    CheckpointStore,
    DiscoveredRecipe,
    DiscoveryResult,
    GastronomosCrawlError,
    IncompleteGastronomosCrawl,
    canonical_recipe_location,
    checkpoint_run_key,
    discover_recipes,
    parse_sitemap,
    parser_contract_hash,
    run_gastronomos_crawl,
)


def test_transparent_identity_and_exact_audited_exception_contracts():
    assert USER_AGENT == "PeltesSpoonRecipeImporter/1.0 (+mailto:hey@spoon.gr)"
    assert AUDITED_EXTERNAL_REDIRECTS == {
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
    assert AUDITED_NON_GREEK_STUBS == {
        (
            "https://www.gastronomos.gr/syntagh/"
            "moussaka-prepared-asia-minor-style-with-tomato-and-kasseri-cheese-"
            "and-no-bechamel/164705/"
        ): "164705",
    }
    assert set(AUDITED_CANONICAL_ALIASES) == {
        "https://www.gastronomos.gr/syntagh/chaloymo-pitakia/50895/",
        (
            "https://www.gastronomos.gr/syntagh/"
            "kotopoylo-foyrnoy-klasiko-kai-kalokairino-2/270507/"
        ),
        (
            "https://www.gastronomos.gr/syntagh/"
            "krya-pantzarosoypa-se-sfinaki-me-giaoyrti/51607/"
        ),
        (
            "https://www.gastronomos.gr/syntagh/"
            "melitzanes-gioyvetsi-me-mozzarella-burrata-kai-saltsa-ntomatas-"
            "piperias-florinis/209330/"
        ),
        "https://www.gastronomos.gr/syntagh/melopita/52805/",
        (
            "https://www.gastronomos.gr/syntagh/"
            "moscharisio-rolo-me-agria-manitaria-thymari-kai-dentrolivano/231261/"
        ),
        "https://www.gastronomos.gr/syntagh/pasta-froytoy-flora/51935/",
        (
            "https://www.gastronomos.gr/syntagh/"
            "soysame-nio-saragli-nistisimo-apo-ton-evro/99603/"
        ),
        (
            "https://www.gastronomos.gr/syntagh/"
            "tom-yum-taylandeziki-soypa/52219/"
        ),
        "https://www.gastronomos.gr/syntagh/tom-yum/83875/",
    }
    assert {
        details["providerRecipeId"]
        for details in AUDITED_CANONICAL_ALIASES.values()
    } == {
        "249974", "269126", "131465", "207162", "124447",
        "160128", "98166", "189302", "106238",
    }
    assert all(
        details["canonicalUrl"].endswith(
            f"/{details['providerRecipeId']}/"
        )
        for details in AUDITED_CANONICAL_ALIASES.values()
    )
    assert not any("51954" in url for url in AUDITED_NON_GREEK_STUBS)
    assert {
        details["providerRecipeId"]
        for details in AUDITED_SOURCE_INCOMPLETE_PAGES.values()
    } == {
        "50725", "50752", "52432", "52428", "50963",
        "52355", "51850", "51074", "51570", "53124",
        "50137", "51275", "50275", "51278", "52338",
    }
    assert len(AUDITED_SOURCE_INCOMPLETE_PAGES) == 15
    assert all(
        details["finalStatus"] == 200
        and details["expectedError"] in {
            "Gastronomos JSON-LD Recipe has no ingredients",
            "Gastronomos JSON-LD Recipe has no instructions",
        }
        and bool(details["reason"])
        for details in AUDITED_SOURCE_INCOMPLETE_PAGES.values()
    )
    assert AUDITED_NON_RECIPE_SITEMAP_ENTRIES == (
        "https://www.gastronomos.gr/oles-oi-syntages/",
    )
    assert len(parser_contract_hash()) == 64
    assert len(checkpoint_run_key()) == 64


def test_sitemap_index_keeps_all_and_only_recipe_sitemaps():
    xml = """<?xml version="1.0"?><sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
      <sitemap><loc>http://www.gastronomos.gr/recipe-sitemap.xml</loc></sitemap>
      <sitemap><loc>https://gastronomos.gr/recipe-sitemap13.xml</loc></sitemap>
      <sitemap><loc>https://www.gastronomos.gr/post-sitemap.xml</loc></sitemap>
      <sitemap><loc>https://evil.test/recipe-sitemap14.xml</loc></sitemap>
    </sitemapindex>"""
    recipes, children, declared, non_recipe_entries = parse_sitemap(xml)
    assert recipes == []
    assert declared == 0
    assert non_recipe_entries == []
    assert children == [
        "https://www.gastronomos.gr/recipe-sitemap.xml",
        "https://www.gastronomos.gr/recipe-sitemap13.xml",
    ]


def test_recipe_sitemap_accepts_only_strict_numeric_syntagh_locations():
    xml = """<?xml version="1.0"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
      <url><loc>http://gastronomos.gr/syntagh/synthetic-one/101/</loc><lastmod>2026-01-02</lastmod></url>
      <url><loc>https://www.gastronomos.gr/oles-oi-syntages/</loc></url>
      <url><loc>https://www.gastronomos.gr/syntages/synthetic-two/102/</loc></url>
      <url><loc>https://www.gastronomos.gr/syntagh/not-numeric/nope/</loc></url>
    </urlset>"""
    recipes, children, declared, non_recipe_entries = parse_sitemap(xml)
    assert children == []
    assert declared == 4
    assert recipes == [
        DiscoveredRecipe(
            "https://www.gastronomos.gr/syntagh/synthetic-one/101/",
            "2026-01-02",
        )
    ]
    assert non_recipe_entries == [
        "https://www.gastronomos.gr/oles-oi-syntages/",
        "https://www.gastronomos.gr/syntages/synthetic-two/102/",
        "https://www.gastronomos.gr/syntagh/not-numeric/nope/",
    ]
    assert canonical_recipe_location(
        "https://www.gastronomos.gr/syntagh/synthetic-one/101/?tracking=yes"
    ) is None
    assert canonical_recipe_location(
        "https://evil.test/syntagh/synthetic-one/101/"
    ) is None


def _sitemap_index(child_url: str) -> str:
    return f"""<?xml version="1.0"?><sitemapindex>
      <sitemap><loc>{child_url}</loc></sitemap>
    </sitemapindex>"""


def _recipe_urlset(non_recipe_locations: list[str]) -> str:
    extra = "".join(
        f"<url><loc>{location}</loc></url>"
        for location in non_recipe_locations
    )
    return f"""<?xml version="1.0"?><urlset>
      <url><loc>https://www.gastronomos.gr/syntagh/one/101/</loc></url>
      <url><loc>http://gastronomos.gr/syntagh/one/101/</loc></url>
      {extra}
    </urlset>"""


class _SitemapClient:
    def __init__(self, responses: dict[str, str]) -> None:
        self.responses = responses

    def get(self, url, *, robots):
        del robots
        return _Response(url, self.responses[url])


def test_discovery_accounts_for_the_one_exact_non_recipe_sitemap_entry():
    child_url = "https://www.gastronomos.gr/recipe-sitemap.xml"
    client = _SitemapClient({
        crawl_gastronomos.SITEMAP_URL: _sitemap_index(child_url),
        child_url: _recipe_urlset(list(AUDITED_NON_RECIPE_SITEMAP_ENTRIES)),
    })

    discovery = discover_recipes(client, object())

    assert list(discovery.recipes) == [
        "https://www.gastronomos.gr/syntagh/one/101/"
    ]
    assert discovery.duplicate_entries == 1
    assert discovery.declared_entries == 3
    assert discovery.non_recipe_entries == AUDITED_NON_RECIPE_SITEMAP_ENTRIES


@pytest.mark.parametrize(
    "non_recipe_locations",
    [
        [],
        ["http://gastronomos.gr/oles-oi-syntages/"],
        ["https://www.gastronomos.gr/agnosti-selida/"],
        [
            "https://www.gastronomos.gr/oles-oi-syntages/",
            "https://www.gastronomos.gr/agnosti-selida/",
        ],
    ],
)
def test_discovery_fails_closed_when_non_recipe_sitemap_inventory_drifts(
    non_recipe_locations,
):
    child_url = "https://www.gastronomos.gr/recipe-sitemap.xml"
    client = _SitemapClient({
        crawl_gastronomos.SITEMAP_URL: _sitemap_index(child_url),
        child_url: _recipe_urlset(non_recipe_locations),
    })

    with pytest.raises(GastronomosCrawlError, match="exact audited allowlist"):
        discover_recipes(client, object())


def test_checkpoint_key_binds_the_exact_non_recipe_sitemap_allowlist(monkeypatch):
    original = checkpoint_run_key()
    monkeypatch.setattr(
        crawl_gastronomos,
        "AUDITED_NON_RECIPE_SITEMAP_ENTRIES",
        AUDITED_NON_RECIPE_SITEMAP_ENTRIES
        + ("https://www.gastronomos.gr/agnosti-selida/",),
    )
    assert checkpoint_run_key() != original


def test_checkpoint_key_binds_the_exact_source_incomplete_allowlist(monkeypatch):
    original = checkpoint_run_key()
    changed = dict(AUDITED_SOURCE_INCOMPLETE_PAGES)
    first_url = next(iter(changed))
    changed[first_url] = {
        **changed[first_url],
        "reason": "tampered reason",
    }
    monkeypatch.setattr(
        crawl_gastronomos,
        "AUDITED_SOURCE_INCOMPLETE_PAGES",
        changed,
    )
    assert checkpoint_run_key() != original


def test_cli_permission_gate_prevents_client_and_network_initialization(monkeypatch, capsys):
    monkeypatch.setattr(
        crawl_gastronomos,
        "GastronomosHttpClient",
        lambda **kwargs: (_ for _ in ()).throw(AssertionError("network must stay closed")),
    )
    assert crawl_gastronomos.main([]) == 2
    assert "--i-have-gastronomos-permission" in capsys.readouterr().err


def test_worker_cli_default_is_four_and_values_above_eight_are_rejected(capsys):
    assert crawl_gastronomos.build_parser().parse_args([]).workers == 4
    assert crawl_gastronomos.main([
        "--i-have-gastronomos-permission",
        "--workers",
        "9",
    ]) == 2
    assert "workers 1-8" in capsys.readouterr().err


def test_http_client_checks_robots_before_opening_a_recipe_connection():
    class NoNetworkSession:
        headers: dict[str, str] = {}

        def get(self, *args, **kwargs):
            raise AssertionError("session.get must not run for a robots-denied URL")

    class DenyRobots:
        def can_fetch(self, user_agent, url):
            assert user_agent == USER_AGENT
            assert url.endswith("/syntagh/synthetic/101/")
            return False

    client = crawl_gastronomos.GastronomosHttpClient(
        session=NoNetworkSession(),
        min_delay_seconds=1,
    )
    with pytest.raises(GastronomosCrawlError, match="robots.txt does not allow"):
        client.get(
            "https://www.gastronomos.gr/syntagh/synthetic/101/",
            robots=DenyRobots(),
        )


def test_shared_rate_limiter_reserves_one_aggregate_start_slot(monkeypatch):
    clock = [100.0]
    sleeps: list[float] = []
    monkeypatch.setattr(crawl_gastronomos.time, "monotonic", lambda: clock[0])

    def advance(seconds):
        sleeps.append(seconds)
        clock[0] += seconds

    monkeypatch.setattr(crawl_gastronomos.time, "sleep", advance)
    limiter = crawl_gastronomos.RateLimiter(1.0)
    limiter.wait()
    clock[0] += 0.25
    limiter.wait()
    assert sleeps == [0.75]


def test_checkpoint_reuses_only_same_url_lastmod_and_parser_contract(tmp_path):
    path = tmp_path / "gastronomos.sqlite3"
    store = CheckpointStore(path)
    try:
        store.prepare("schema-parser-v1", resume=True)
        store.put(
            "https://www.gastronomos.gr/syntagh/one/101/",
            "2026-01-01",
            {"id": "gastronomos_101"},
        )
        store.prepare("schema-parser-v1", resume=True)
        assert store.get(
            "https://www.gastronomos.gr/syntagh/one/101/",
            "2026-01-01",
        ) == {"id": "gastronomos_101"}
        assert store.get(
            "https://www.gastronomos.gr/syntagh/one/101/",
            "2026-01-02",
        ) is None
        store.prepare("schema-parser-v2", resume=True)
        assert store.get(
            "https://www.gastronomos.gr/syntagh/one/101/",
            "2026-01-01",
        ) is None
        store.put(
            "https://www.gastronomos.gr/syntagh/no-lastmod/102/",
            "",
            {"id": "gastronomos_102"},
        )
        assert store.get(
            "https://www.gastronomos.gr/syntagh/no-lastmod/102/",
            "",
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


def _paths(tmp_path):
    return {
        "output_path": tmp_path / "gastronomos.jsonl",
        "manifest_path": tmp_path / "gastronomos.manifest.json",
        "failures_path": tmp_path / "gastronomos.failures.json",
        "checkpoint_path": tmp_path / "gastronomos.sqlite3",
    }


def _configure_run(monkeypatch, urls):
    # Synthetic inventories deliberately contain none of the live audited URLs.
    monkeypatch.setattr(crawl_gastronomos, "AUDITED_EXTERNAL_REDIRECTS", {})
    monkeypatch.setattr(crawl_gastronomos, "AUDITED_NON_GREEK_STUBS", {})
    monkeypatch.setattr(crawl_gastronomos, "AUDITED_CANONICAL_ALIASES", {})
    monkeypatch.setattr(
        crawl_gastronomos,
        "AUDITED_SOURCE_INCOMPLETE_PAGES",
        {},
    )
    recipes = {url: DiscoveredRecipe(url, "2026-01-01") for url in urls}
    monkeypatch.setattr(
        crawl_gastronomos,
        "discover_recipes",
        lambda client, robots: DiscoveryResult(
            recipes=recipes,
            recipe_sitemaps=13,
            declared_entries=(
                len(recipes) + len(AUDITED_NON_RECIPE_SITEMAP_ENTRIES)
            ),
            duplicate_entries=0,
            non_recipe_entries=AUDITED_NON_RECIPE_SITEMAP_ENTRIES,
        ),
    )
    monkeypatch.setattr(crawl_gastronomos, "document_sizes", lambda record: (100, 200, 300))
    monkeypatch.setattr(crawl_gastronomos, "collection_hash", lambda records, projector: "0" * 64)
    monkeypatch.setattr(import_catalog, "compute_catalog_hash", lambda records: "1" * 64)


def _minimal_record(source_url: str) -> dict:
    provider_id = source_url.rstrip("/").rsplit("/", 1)[-1]
    return {
        "id": f"gastronomos_{provider_id}",
        "providerRecipeId": provider_id,
        "sourceUrl": source_url,
        "canonicalUrl": source_url,
        "ingredientSections": [{"ingredients": [{"title": "synthetic"}]}],
        "methodSections": [{"steps": ["synthetic"]}],
    }


def test_complete_run_writes_manifest_only_after_exact_inventory_accounting(monkeypatch, tmp_path):
    urls = [
        "https://www.gastronomos.gr/syntagh/one/101/",
        "https://www.gastronomos.gr/syntagh/two/102/",
    ]
    _configure_run(monkeypatch, urls)
    client = _DetailClient({url: url for url in urls})
    monkeypatch.setattr(
        crawl_gastronomos,
        "normalize_gastronomos_page",
        lambda html, *, source_url, sitemap_last_modified: _minimal_record(source_url),
    )
    monkeypatch.setattr(
        crawl_gastronomos,
        "validate_checkpoint_record",
        lambda record, sitemap_url, sitemap_last_modified: dict(record),
    )

    paths = _paths(tmp_path)
    manifest = run_gastronomos_crawl(
        client=client,
        robots=object(),
        expected_discovered_count=2,
        expected_active_count=2,
        **paths,
    )

    assert manifest["complete"] is True
    assert manifest["sourceKey"] == "gastronomos"
    assert manifest["recipeSitemapCount"] == 13
    assert manifest["discoveredRecipeUrlCount"] == 2
    assert manifest["declaredRecipeEntryCount"] == 3
    assert manifest["nonRecipeSitemapEntryCount"] == 1
    assert manifest["nonRecipeSitemapEntries"] == list(
        AUDITED_NON_RECIPE_SITEMAP_ENTRIES
    )
    assert manifest["nonRecipeSitemapEntriesHash"] == (
        import_catalog._manifest_value_hash(
            list(AUDITED_NON_RECIPE_SITEMAP_ENTRIES)
        )
    )
    assert manifest["canonicalGreekRecipeCount"] == 2
    assert manifest["outputRecipeCount"] == 2
    assert manifest["failedRecipeCount"] == 0
    assert manifest["canonicalAliasCount"] == 0
    assert manifest["excludedRecipeUrlCount"] == 0
    assert manifest["catalogHash"] == "1" * 64
    assert len(paths["output_path"].read_text(encoding="utf-8").splitlines()) == 2
    failure_report = json.loads(paths["failures_path"].read_text(encoding="utf-8"))
    assert failure_report["complete"] is True
    assert failure_report["failures"] == []


def test_parallel_fetches_overlap_but_output_and_checkpoint_order_stay_deterministic(monkeypatch, tmp_path):
    urls = [
        "https://www.gastronomos.gr/syntagh/z-last/102/",
        "https://www.gastronomos.gr/syntagh/a-first/101/",
    ]
    _configure_run(monkeypatch, urls)

    class BarrierClient:
        def __init__(self):
            self.barrier = threading.Barrier(2)
            self.thread_ids: set[int] = set()
            self.lock = threading.Lock()

        def get(self, url, *, robots):
            del robots
            with self.lock:
                self.thread_ids.add(threading.get_ident())
            self.barrier.wait(timeout=3)
            return _Response(url)

    client = BarrierClient()
    monkeypatch.setattr(
        crawl_gastronomos,
        "normalize_gastronomos_page",
        lambda html, *, source_url, sitemap_last_modified: _minimal_record(source_url),
    )
    monkeypatch.setattr(
        crawl_gastronomos,
        "validate_checkpoint_record",
        lambda record, sitemap_url, sitemap_last_modified: dict(record),
    )
    paths = _paths(tmp_path)
    manifest = run_gastronomos_crawl(
        client=client,
        robots=object(),
        workers=2,
        **paths,
    )
    assert manifest["outputRecipeCount"] == 2
    assert len(client.thread_ids) == 2
    output = [
        json.loads(line)
        for line in paths["output_path"].read_text(encoding="utf-8").splitlines()
    ]
    assert [item["canonicalUrl"] for item in output] == sorted(urls)


@pytest.mark.parametrize("workers", [0, 9, True, 1.5])
def test_run_rejects_worker_counts_outside_bounded_integer_contract(tmp_path, workers):
    with pytest.raises(GastronomosCrawlError, match="workers must be between 1 and 8"):
        run_gastronomos_crawl(
            client=object(),
            robots=object(),
            workers=workers,
            **_paths(tmp_path),
        )


def test_schema_error_retries_three_fresh_fetches_and_never_publishes(monkeypatch, tmp_path):
    url = "https://www.gastronomos.gr/syntagh/broken/101/"
    _configure_run(monkeypatch, [url])
    client = _DetailClient({url: url})
    attempts: list[int] = []

    def fail_closed(html, *, source_url, sitemap_last_modified):
        del html, source_url, sitemap_last_modified
        attempts.append(1)
        raise crawl_gastronomos.FullSchemaError("synthetic mismatch")

    monkeypatch.setattr(crawl_gastronomos, "normalize_gastronomos_page", fail_closed)
    paths = _paths(tmp_path)
    with pytest.raises(IncompleteGastronomosCrawl, match="1 failures"):
        run_gastronomos_crawl(client=client, robots=object(), **paths)

    assert len(attempts) == 3
    assert client.calls == [url, url, url]
    assert not paths["output_path"].exists()
    assert not paths["manifest_path"].exists()
    report = json.loads(paths["failures_path"].read_text(encoding="utf-8"))
    assert report == {
        "complete": False,
        "failedRecipeCount": 1,
        "failures": [{"sourceUrl": url, "error": "synthetic mismatch"}],
    }


def test_exact_source_incomplete_page_is_accounted_after_three_fresh_checks(
    monkeypatch,
    tmp_path,
):
    good = "https://www.gastronomos.gr/syntagh/good/100/"
    incomplete = "https://www.gastronomos.gr/syntagh/incomplete/101/"
    expected_error = "Gastronomos JSON-LD Recipe has no ingredients"
    _configure_run(monkeypatch, [good, incomplete])
    monkeypatch.setattr(
        crawl_gastronomos,
        "AUDITED_SOURCE_INCOMPLETE_PAGES",
        {
            incomplete: {
                "providerRecipeId": "101",
                "expectedError": expected_error,
                "finalStatus": 200,
                "reason": "synthetic source omission",
            }
        },
    )
    client = _DetailClient({good: good, incomplete: incomplete})

    def normalize(html, *, source_url, sitemap_last_modified):
        del html, sitemap_last_modified
        if source_url == incomplete:
            raise crawl_gastronomos.FullSchemaError(expected_error)
        return _minimal_record(source_url)

    monkeypatch.setattr(crawl_gastronomos, "normalize_gastronomos_page", normalize)
    monkeypatch.setattr(
        crawl_gastronomos,
        "validate_checkpoint_record",
        lambda record, sitemap_url, sitemap_last_modified: dict(record),
    )

    manifest = run_gastronomos_crawl(
        client=client,
        robots=object(),
        expected_active_count=1,
        **_paths(tmp_path),
    )

    expected = [{
        "sourceUrl": incomplete,
        "providerRecipeId": "101",
        "finalStatus": 200,
        "sourceError": expected_error,
        "reason": "synthetic source omission",
    }]
    assert manifest["sourceIncompletePageExclusionCount"] == 1
    assert manifest["sourceIncompletePageExclusions"] == expected
    assert manifest["excludedRecipeUrls"] == [
        {"kind": "sourceIncompletePage", **expected[0]}
    ]
    assert client.calls.count(incomplete) == 3


def test_audited_source_incomplete_page_fails_if_its_observed_error_drifts(
    monkeypatch,
    tmp_path,
):
    url = "https://www.gastronomos.gr/syntagh/incomplete/101/"
    _configure_run(monkeypatch, [url])
    monkeypatch.setattr(
        crawl_gastronomos,
        "AUDITED_SOURCE_INCOMPLETE_PAGES",
        {
            url: {
                "providerRecipeId": "101",
                "expectedError": "expected exact error",
                "finalStatus": 200,
                "reason": "synthetic source omission",
            }
        },
    )
    monkeypatch.setattr(
        crawl_gastronomos,
        "normalize_gastronomos_page",
        lambda html, *, source_url, sitemap_last_modified: (
            (_ for _ in ()).throw(
                crawl_gastronomos.FullSchemaError("different error")
            )
        ),
    )

    with pytest.raises(IncompleteGastronomosCrawl, match="1 failures"):
        run_gastronomos_crawl(
            client=_DetailClient({url: url}),
            robots=object(),
            **_paths(tmp_path),
        )


def test_audited_source_incomplete_page_fails_if_it_becomes_complete(
    monkeypatch,
    tmp_path,
):
    url = "https://www.gastronomos.gr/syntagh/incomplete/101/"
    _configure_run(monkeypatch, [url])
    monkeypatch.setattr(
        crawl_gastronomos,
        "AUDITED_SOURCE_INCOMPLETE_PAGES",
        {
            url: {
                "providerRecipeId": "101",
                "expectedError": "Gastronomos JSON-LD Recipe has no ingredients",
                "finalStatus": 200,
                "reason": "synthetic source omission",
            }
        },
    )
    monkeypatch.setattr(
        crawl_gastronomos,
        "normalize_gastronomos_page",
        lambda html, *, source_url, sitemap_last_modified: _minimal_record(
            source_url
        ),
    )

    with pytest.raises(IncompleteGastronomosCrawl, match="1 failures"):
        run_gastronomos_crawl(
            client=_DetailClient({url: url}),
            robots=object(),
            **_paths(tmp_path),
        )


def test_unknown_non_recipe_redirect_is_a_failure_not_a_silent_exclusion(monkeypatch, tmp_path):
    url = "https://www.gastronomos.gr/syntagh/stale/101/"
    _configure_run(monkeypatch, [url])
    client = _DetailClient({
        url: "https://www.gastronomos.gr/oles-oi-syntages/",
    })
    paths = _paths(tmp_path)
    with pytest.raises(IncompleteGastronomosCrawl, match="1 failures"):
        run_gastronomos_crawl(client=client, robots=object(), **paths)
    report = json.loads(paths["failures_path"].read_text(encoding="utf-8"))
    assert "unapproved non-recipe redirect" in report["failures"][0]["error"]
    assert not paths["output_path"].exists()
