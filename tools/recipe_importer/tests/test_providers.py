import pytest

from tools.recipe_importer.providers import (
    AKIS,
    ARGIRO,
    GASTRONOMOS,
    ProviderError,
    canonical_recipe_url,
    provider_for,
    recipe_document_id,
)


def test_existing_akis_ids_remain_stable_while_other_provider_ids_are_namespaced():
    assert recipe_document_id(AKIS, "17265") == "17265"
    assert recipe_document_id(ARGIRO, "17265") == "argiro_17265"
    assert recipe_document_id(GASTRONOMOS, "17265") == "gastronomos_17265"
    assert recipe_document_id(ARGIRO, "17265") != recipe_document_id(AKIS, "17265")
    assert recipe_document_id(GASTRONOMOS, "17265") != recipe_document_id(ARGIRO, "17265")


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


def test_gastronomos_url_id_is_numeric_and_must_match_the_canonical_path():
    assert canonical_recipe_url(
        GASTRONOMOS,
        "https://gastronomos.gr/syntagh/fakes-soupa/203399?utm_source=test#ylika",
        "203399",
    ) == "https://www.gastronomos.gr/syntagh/fakes-soupa/203399/"

    with pytest.raises(ProviderError, match="sourceUrl"):
        canonical_recipe_url(
            GASTRONOMOS,
            "https://www.gastronomos.gr/syntagh/fakes-soupa/203399/",
            "203400",
        )


def test_conflicting_source_signals_are_rejected():
    with pytest.raises(ProviderError, match="different providers"):
        provider_for(
            source_key="akis",
            source_url="https://www.argiro.gr/recipe/spanakopita/",
        )


@pytest.mark.parametrize(
    "url",
    [
        "http://www.gastronomos.gr/syntagh/fakes-soupa/203399/",
        "https://evil.test/syntagh/fakes-soupa/203399/",
        "https://www.gastronomos.gr/syntages/203399/",
        "https://www.gastronomos.gr/oles-oi-syntages/",
        "https://www.gastronomos.gr/syntagh/fakes-soupa/not-an-id/",
    ],
)
def test_gastronomos_recipe_urls_are_strict_same_site_https(url):
    with pytest.raises(ProviderError, match="sourceUrl"):
        canonical_recipe_url(GASTRONOMOS, url, "203399")


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


@pytest.mark.parametrize("key,native,url,image", [
    ("tsoulis", "42", "https://www.giorgostsoulis.com/syntages/glyka/keik", "https://api.giorgostsoulis.com/storage/recipes/keik.jpg"),
    ("lucacos", "5769", "https://www.yiannislucacos.gr/recipe/5769/salata", "https://www.yiannislucacos.gr/sites/default/files/styles/image/public/a.jpg?itok=abc"),
    ("funkycook", "42", "https://funkycook.gr/keik/", "https://funkycook.gr/wp-content/uploads/a.jpg"),
    ("cookpad", "42", "https://cookpad.com/gr/sintages/42-keik", "https://img-global.cpcdn.com/recipes/abc/680x482cq80/photo.jpg"),
])
def test_new_publishers_preserve_identity_and_official_media_host(key, native, url, image):
    from tools.recipe_importer.providers import provider_for, recipe_document_id, canonical_recipe_url, canonical_image_url
    provider = provider_for(source_key=key)
    assert recipe_document_id(provider, native) == key + "_" + native
    assert canonical_recipe_url(provider, url, native) == url
    assert canonical_image_url(provider, image) == image


def test_new_provider_media_allowlist_does_not_allow_foreign_hosts_or_unknown_tokens():
    from tools.recipe_importer.providers import provider_for, canonical_image_url, ProviderError
    for key,url in [("cookpad", "https://evil.example/recipes/a.jpg"),
                    ("lucacos", "https://www.yiannislucacos.gr/sites/a.jpg?redirect=bad"),
                    ("tsoulis", "https://api.giorgostsoulis.com/storage/../private/a.jpg")]:
        with pytest.raises(ProviderError):
            canonical_image_url(provider_for(source_key=key), url)
