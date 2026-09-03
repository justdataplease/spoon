import pytest

from tools.recipe_importer.providers import (
    AKIS,
    ARGIRO,
    ProviderError,
    canonical_recipe_url,
    provider_for,
    recipe_document_id,
)


def test_existing_akis_ids_remain_stable_while_argiro_ids_are_namespaced():
    assert recipe_document_id(AKIS, "17265") == "17265"
    assert recipe_document_id(ARGIRO, "17265") == "argiro_17265"
    assert recipe_document_id(ARGIRO, "17265") != recipe_document_id(AKIS, "17265")


def test_unsafe_or_unicode_native_ids_have_a_deterministic_safe_hash_fallback():
    first = recipe_document_id(ARGIRO, "σπανακόπιτα")
    second = recipe_document_id(ARGIRO, "σπανακόπιτα")
    assert first == second
    assert first.startswith("argiro_h_")
    assert len(first) < 128


def test_argiro_wordpress_id_does_not_need_to_equal_the_canonical_url_slug():
    assert canonical_recipe_url(
        ARGIRO,
        "https://www.argiro.gr/recipe/spanakopita/?utm_source=test#top",
        "17265",
    ) == "https://www.argiro.gr/recipe/spanakopita/"


def test_conflicting_source_signals_are_rejected():
    with pytest.raises(ProviderError, match="different providers"):
        provider_for(
            source_key="akis",
            source_url="https://www.argiro.gr/recipe/spanakopita/",
        )


@pytest.mark.parametrize(
    "url",
    [
        "http://argiro.gr/recipe/spanakopita/",
        "https://evil.test/recipe/spanakopita/",
        "https://argiro.gr/not-a-recipe/spanakopita/",
    ],
)
def test_argiro_recipe_urls_are_strict_same_site_https(url):
    with pytest.raises(ProviderError, match="sourceUrl"):
        canonical_recipe_url(ARGIRO, url, "17265")
