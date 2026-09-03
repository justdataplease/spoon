"""Synthetic-only tests for the Gastronomos Firestore import contract."""

import json

import pytest

from tools.recipe_importer import crawl_gastronomos
import tools.recipe_importer.import_catalog as importer
from tools.recipe_importer.gastronomos_schema import normalize_gastronomos_page
from tools.recipe_importer.import_catalog import CatalogError


SOURCE_URL = (
    "https://www.gastronomos.gr/syntagh/"
    "synthetiki-syntagi-me-fakes/203399/"
)


def _full_gastronomos_record() -> dict:
    """Build a valid full record through the real normalizer, without network data."""

    recipe = {
        "@context": "https://schema.org",
        "@type": "Recipe",
        "url": SOURCE_URL,
        "name": "\u03a3\u03c5\u03bd\u03b8\u03b5\u03c4\u03b9\u03ba\u03ae \u03c3\u03c5\u03bd\u03c4\u03b1\u03b3\u03ae \u03bc\u03b5 \u03c6\u03b1\u03ba\u03ad\u03c2",
        "description": (
            "\u039c\u03b9\u03b1 \u03c0\u03bb\u03ae\u03c1\u03c9\u03c2 \u03c3\u03c5\u03bd\u03b8\u03b5\u03c4\u03b9\u03ba\u03ae \u03b5\u03bb\u03bb\u03b7\u03bd\u03b9\u03ba\u03ae "
            "\u03c0\u03b5\u03c1\u03b9\u03b3\u03c1\u03b1\u03c6\u03ae \u03b3\u03b9\u03b1 \u03b4\u03bf\u03ba\u03b9\u03bc\u03ae."
        ),
        "inLanguage": "el-GR",
        "recipeCategory": ["\u038c\u03c3\u03c0\u03c1\u03b9\u03b1"],
        "prepTime": "PT10M",
        "cookTime": "PT20M",
        "totalTime": "PT30M",
        "recipeYield": "4 \u03bc\u03b5\u03c1\u03af\u03b4\u03b5\u03c2",
        "recipeIngredient": [
            "250 \u03b3\u03c1. \u03c6\u03b1\u03ba\u03ad\u03c2",
            "1 \u03bb\u03af\u03c4\u03c1\u03bf \u03bd\u03b5\u03c1\u03cc",
        ],
        "recipeInstructions": [
            {
                "@type": "HowToStep",
                "text": "\u039e\u03b5\u03c0\u03bb\u03ad\u03bd\u03bf\u03c5\u03bc\u03b5 \u03ba\u03b1\u03b9 \u03b2\u03c1\u03ac\u03b6\u03bf\u03c5\u03bc\u03b5 \u03c4\u03b9\u03c2 \u03c6\u03b1\u03ba\u03ad\u03c2."
            },
            {
                "@type": "HowToStep",
                "text": "\u03a3\u03b5\u03c1\u03b2\u03af\u03c1\u03bf\u03c5\u03bc\u03b5 \u03c4\u03bf \u03c6\u03b1\u03b3\u03b7\u03c4\u03cc \u03b6\u03b5\u03c3\u03c4\u03cc."
            },
        ],
    }
    html = (
        '<!doctype html><html lang="el"><head>'
        f'<link rel="canonical" href="{SOURCE_URL}">'
        '<script type="application/ld+json">'
        f"{json.dumps(recipe, ensure_ascii=False)}"
        "</script></head><body><article class=\"single-recipe\"></article>"
        "</body></html>"
    )
    return normalize_gastronomos_page(
        html,
        source_url=SOURCE_URL,
        sitemap_last_modified="2026-01-03",
    )


def test_full_gastronomos_content_requires_both_permission_acknowledgements():
    record = _full_gastronomos_record()

    with pytest.raises(CatalogError, match="--i-have-permission"):
        importer.validate_record(
            record,
            have_gastronomos_permission=True,
        )
    with pytest.raises(CatalogError, match="--i-have-gastronomos-permission"):
        importer.validate_record(
            record,
            have_permission=True,
        )

    validated = importer.validate_record(
        record,
        have_permission=True,
        have_gastronomos_permission=True,
    )
    assert validated["id"] == "gastronomos_203399"
    assert validated["sourceKey"] == "gastronomos"


def _gastronomos_manifest_contract(monkeypatch):
    record = importer.validate_record(
        _full_gastronomos_record(),
        have_permission=True,
        have_gastronomos_permission=True,
    )
    records = [record]
    alias_allowlist = {
        "https://www.gastronomos.gr/syntagh/palia-syntagi/203398/": {
            "canonicalUrl": record["canonicalUrl"],
            "providerRecipeId": record["providerRecipeId"],
        }
    }
    external_allowlist = {
        "https://www.gastronomos.gr/syntagh/diagrafimeni-syntagi/203400/": {
            "finalUrl": "https://www.gastronomos.gr/oles-oi-syntages/",
            "finalStatus": 200,
        }
    }
    stub_allowlist = {
        "https://www.gastronomos.gr/syntagh/english-stub/203401/": "203401"
    }
    source_incomplete_allowlist = {
        (
            "https://www.gastronomos.gr/syntagh/"
            "source-incomplete/203402/"
        ): {
            "providerRecipeId": "203402",
            "expectedError": "Gastronomos JSON-LD Recipe has no ingredients",
            "finalStatus": 200,
            "reason": "source omits every ingredient",
        }
    }
    monkeypatch.setattr(
        crawl_gastronomos,
        "AUDITED_CANONICAL_ALIASES",
        alias_allowlist,
    )
    monkeypatch.setattr(
        crawl_gastronomos,
        "AUDITED_EXTERNAL_REDIRECTS",
        external_allowlist,
    )
    monkeypatch.setattr(
        crawl_gastronomos,
        "AUDITED_NON_GREEK_STUBS",
        stub_allowlist,
    )
    monkeypatch.setattr(
        crawl_gastronomos,
        "AUDITED_SOURCE_INCOMPLETE_PAGES",
        source_incomplete_allowlist,
    )

    aliases = sorted(
        [
            {"sourceUrl": source_url, **details}
            for source_url, details in alias_allowlist.items()
        ],
        key=lambda item: item["sourceUrl"],
    )
    external = sorted(
        [
            {
                "sourceUrl": source_url,
                **details,
                "reason": (
                    "redirected outside /syntagh/{slug}/{numeric-id}/"
                ),
            }
            for source_url, details in external_allowlist.items()
        ],
        key=lambda item: item["sourceUrl"],
    )
    stubs = sorted(
        [
            {
                "sourceUrl": source_url,
                "providerRecipeId": provider_recipe_id,
                "finalStatus": 200,
                "reason": "recipe content is not substantively Greek",
            }
            for source_url, provider_recipe_id in stub_allowlist.items()
        ],
        key=lambda item: item["sourceUrl"],
    )
    source_incomplete = sorted(
        [
            {
                "sourceUrl": source_url,
                "providerRecipeId": details["providerRecipeId"],
                "finalStatus": details["finalStatus"],
                "sourceError": details["expectedError"],
                "reason": details["reason"],
            }
            for source_url, details in source_incomplete_allowlist.items()
        ],
        key=lambda item: item["sourceUrl"],
    )
    exclusions = sorted(
        [{"kind": "externalRedirect", **item} for item in external]
        + [{"kind": "nonGreekStub", **item} for item in stubs]
        + [
            {"kind": "sourceIncompletePage", **item}
            for item in source_incomplete
        ],
        key=lambda item: item["sourceUrl"],
    )
    non_recipe_sitemap_entries = sorted(
        crawl_gastronomos.AUDITED_NON_RECIPE_SITEMAP_ENTRIES
    )
    active_ids = sorted(item["id"] for item in records if item["active"])
    discovered = len(records) + len(aliases) + len(exclusions)
    duplicate_entries = 2
    manifest = {
        "complete": True,
        "failedRecipeCount": 0,
        "sourceKey": "gastronomos",
        "detailSchemaVersion": record["detailSchemaVersion"],
        "outputRecipeCount": len(records),
        "activeRecipeCount": len(active_ids),
        "detailRecipeCount": len(records),
        "sourcePayloadCount": len(records),
        "discoveredActiveRecipeCount": len(active_ids),
        "activeIdsHash": importer._manifest_value_hash(active_ids),
        "catalogHash": importer.compute_catalog_hash(records),
        "summaryHash": importer.collection_hash(
            records,
            importer.firestore_recipe_payload,
        ),
        "detailHash": importer.collection_hash(
            records,
            importer.firestore_detail_payload,
        ),
        "sourcePayloadHash": importer.collection_hash(
            records,
            importer.firestore_source_payload,
        ),
        "oversizedDocumentCount": 0,
        "canonicalGreekRecipeCount": len(records),
        "canonicalAliasCount": len(aliases),
        "canonicalAliases": aliases,
        "canonicalAliasesHash": importer._manifest_value_hash(aliases),
        "externalRedirectExclusionCount": len(external),
        "externalRedirectExclusions": external,
        "externalRedirectExclusionsHash": importer._manifest_value_hash(external),
        "nonGreekStubExclusionCount": len(stubs),
        "nonGreekStubExclusions": stubs,
        "nonGreekStubExclusionsHash": importer._manifest_value_hash(stubs),
        "sourceIncompletePageExclusionCount": len(source_incomplete),
        "sourceIncompletePageExclusions": source_incomplete,
        "sourceIncompletePageExclusionsHash": importer._manifest_value_hash(
            source_incomplete
        ),
        "excludedRecipeUrlCount": len(exclusions),
        "excludedRecipeUrls": exclusions,
        "excludedRecipeUrlsHash": importer._manifest_value_hash(exclusions),
        "nonRecipeSitemapEntryCount": len(non_recipe_sitemap_entries),
        "nonRecipeSitemapEntries": non_recipe_sitemap_entries,
        "nonRecipeSitemapEntriesHash": importer._manifest_value_hash(
            non_recipe_sitemap_entries
        ),
        "parserContractHash": crawl_gastronomos.parser_contract_hash(),
        "checkpointRunKey": crawl_gastronomos.checkpoint_run_key(),
        "discoveredRecipeUrlCount": discovered,
        "duplicateRecipeEntryCount": duplicate_entries,
        "declaredRecipeEntryCount": (
            discovered + duplicate_entries + len(non_recipe_sitemap_entries)
        ),
    }
    return records, manifest


def _replace_source_incomplete_contract(
    monkeypatch,
    manifest,
    *,
    source_url,
    details,
):
    allowlist = {source_url: details}
    monkeypatch.setattr(
        crawl_gastronomos,
        "AUDITED_SOURCE_INCOMPLETE_PAGES",
        allowlist,
    )
    source_incomplete = [{
        "sourceUrl": source_url,
        "providerRecipeId": details["providerRecipeId"],
        "finalStatus": details["finalStatus"],
        "sourceError": details["expectedError"],
        "reason": details["reason"],
    }]
    exclusions = sorted(
        [
            item
            for item in manifest["excludedRecipeUrls"]
            if item["kind"] != "sourceIncompletePage"
        ]
        + [
            {"kind": "sourceIncompletePage", **item}
            for item in source_incomplete
        ],
        key=lambda item: item["sourceUrl"],
    )
    return manifest | {
        "sourceIncompletePageExclusionCount": len(source_incomplete),
        "sourceIncompletePageExclusions": source_incomplete,
        "sourceIncompletePageExclusionsHash": importer._manifest_value_hash(
            source_incomplete
        ),
        "excludedRecipeUrlCount": len(exclusions),
        "excludedRecipeUrls": exclusions,
        "excludedRecipeUrlsHash": importer._manifest_value_hash(exclusions),
        "checkpointRunKey": crawl_gastronomos.checkpoint_run_key(),
    }


def test_gastronomos_manifest_accepts_exact_allowlists_hashes_and_algebra(
    monkeypatch,
):
    records, manifest = _gastronomos_manifest_contract(monkeypatch)
    importer.validate_manifest(records, manifest)


def test_gastronomos_manifest_rejects_contract_tampering(monkeypatch):
    records, manifest = _gastronomos_manifest_contract(monkeypatch)
    tampered_aliases = [
        manifest["canonicalAliases"][0]
        | {"canonicalUrl": "https://www.gastronomos.gr/syntagh/allo/999999/"}
    ]
    tampered_external = [
        manifest["externalRedirectExclusions"][0] | {"finalStatus": 301}
    ]
    tampered_stubs = [
        manifest["nonGreekStubExclusions"][0] | {"providerRecipeId": "999999"}
    ]
    tampered_source_incomplete = [
        manifest["sourceIncompletePageExclusions"][0]
        | {"sourceError": "different source error"}
    ]
    tampered_all_exclusions = [
        (
            item | {"sourceError": "different source error"}
            if item["kind"] == "sourceIncompletePage"
            else item
        )
        for item in manifest["excludedRecipeUrls"]
    ]
    tampered_non_recipe_entries = [
        "https://www.gastronomos.gr/agnosti-selida/"
    ]
    exclusions_without_source_incomplete = [
        item
        for item in manifest["excludedRecipeUrls"]
        if item["kind"] != "sourceIncompletePage"
    ]
    cases = (
        {
            "canonicalAliases": tampered_aliases,
            "canonicalAliasesHash": importer._manifest_value_hash(tampered_aliases),
        },
        {
            "externalRedirectExclusions": tampered_external,
            "externalRedirectExclusionsHash": importer._manifest_value_hash(
                tampered_external
            ),
        },
        {
            "nonGreekStubExclusions": tampered_stubs,
            "nonGreekStubExclusionsHash": importer._manifest_value_hash(
                tampered_stubs
            ),
        },
        {
            "sourceIncompletePageExclusions": tampered_source_incomplete,
            "sourceIncompletePageExclusionsHash": importer._manifest_value_hash(
                tampered_source_incomplete
            ),
            "excludedRecipeUrls": tampered_all_exclusions,
            "excludedRecipeUrlsHash": importer._manifest_value_hash(
                tampered_all_exclusions
            ),
        },
        {"canonicalAliasesHash": "0" * 64},
        {"externalRedirectExclusionsHash": "0" * 64},
        {"nonGreekStubExclusionsHash": "0" * 64},
        {"sourceIncompletePageExclusionCount": 0},
        {"sourceIncompletePageExclusionsHash": "0" * 64},
        {
            "excludedRecipeUrlCount": len(exclusions_without_source_incomplete),
            "excludedRecipeUrls": exclusions_without_source_incomplete,
            "excludedRecipeUrlsHash": importer._manifest_value_hash(
                exclusions_without_source_incomplete
            ),
            "discoveredRecipeUrlCount": (
                manifest["discoveredRecipeUrlCount"] - 1
            ),
            "declaredRecipeEntryCount": (
                manifest["declaredRecipeEntryCount"] - 1
            ),
        },
        {"excludedRecipeUrlsHash": "0" * 64},
        {"nonRecipeSitemapEntryCount": 0},
        {
            "nonRecipeSitemapEntries": tampered_non_recipe_entries,
            "nonRecipeSitemapEntriesHash": importer._manifest_value_hash(
                tampered_non_recipe_entries
            ),
        },
        {"nonRecipeSitemapEntriesHash": "0" * 64},
        {"parserContractHash": "0" * 64},
        {"checkpointRunKey": "0" * 64},
        {
            "discoveredRecipeUrlCount": (
                manifest["discoveredRecipeUrlCount"] + 1
            )
        },
        {
            "declaredRecipeEntryCount": (
                manifest["declaredRecipeEntryCount"] + 1
            )
        },
    )
    for changes in cases:
        with pytest.raises(CatalogError, match="manifest/catalog mismatch"):
            importer.validate_manifest(records, manifest | changes)


@pytest.mark.parametrize(
    ("source_url", "details"),
    (
        (
            "https://www.gastronomos.gr/syntagh/source-incomplete/203402/",
            {
                "providerRecipeId": "999999",
                "expectedError": "missing ingredients",
                "finalStatus": 200,
                "reason": "source is incomplete",
            },
        ),
        (
            "https://www.gastronomos.gr/syntagh/source-incomplete/not-numeric/",
            {
                "providerRecipeId": "203402",
                "expectedError": "missing ingredients",
                "finalStatus": 200,
                "reason": "source is incomplete",
            },
        ),
        (
            "https://www.gastronomos.gr/syntagh/source-incomplete/203402/",
            {
                "providerRecipeId": "203402",
                "expectedError": "missing ingredients",
                "finalStatus": 200.0,
                "reason": "source is incomplete",
            },
        ),
        (
            "https://www.gastronomos.gr/syntagh/source-incomplete/203402/",
            {
                "providerRecipeId": "203402",
                "expectedError": "",
                "finalStatus": 200,
                "reason": "source is incomplete",
            },
        ),
        (
            "https://www.gastronomos.gr/syntagh/source-incomplete/203402/",
            {
                "providerRecipeId": "203402",
                "expectedError": "missing ingredients",
                "finalStatus": 200,
                "reason": " ",
            },
        ),
    ),
)
def test_gastronomos_manifest_rejects_invalid_source_incomplete_contract(
    monkeypatch,
    source_url,
    details,
):
    records, manifest = _gastronomos_manifest_contract(monkeypatch)
    malformed_manifest = _replace_source_incomplete_contract(
        monkeypatch,
        manifest,
        source_url=source_url,
        details=details,
    )

    with pytest.raises(CatalogError, match="sourceIncompletePageExclusions"):
        importer.validate_manifest(records, malformed_manifest)
