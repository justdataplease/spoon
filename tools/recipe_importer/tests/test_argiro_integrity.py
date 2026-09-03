"""Independent fail-closed integrity regressions for the Argiro normalizer.

These fixtures are synthetic and contain no publisher recipe prose.  They focus
on invariants that protect the Greek-only catalogue and its source evidence.
"""

from copy import deepcopy
import json

import pytest

from tools.recipe_importer.argiro_schema import normalize_argiro_page
from tools.recipe_importer.full_schema import (
    CATEGORY_LABELS,
    FullSchemaError,
    ensure_full_record,
)


SOURCE_URL = "https://www.argiro.gr/recipe/synthetiki-faki/"


def _synthetic_page(
    *,
    html_language: str = "el",
    jsonld_language: str | None = None,
    video: object = "https://www.youtube.com/embed/synthetic-video",
    extra_body: str = "",
) -> str:
    recipe = {
        "@context": "https://schema.org",
        "@type": "Recipe",
        "name": "Συνθετική φακή",
        "description": "Συνθετική ελληνική περιγραφή.",
        "author": {"@type": "Person", "name": "Δοκιμαστική συγγραφέας"},
        "datePublished": "2026-01-02T10:00:00+02:00",
        "dateModified": "2026-01-03T11:00:00+02:00",
        "image": ["https://www.argiro.gr/wp-content/uploads/synthetic.jpg"],
        "prepTime": "PT10M",
        "cookTime": "PT20M",
        "totalTime": "PT30M",
        "recipeYield": ["2 μερίδες"],
        "recipeCategory": ["Όσπρια"],
        "keywords": ["κατσαρόλα"],
        "recipeIngredient": ["1 φλιτζάνι φακές"],
        "recipeInstructions": [
            {"@type": "HowToStep", "text": "Βράζουμε τις φακές."}
        ],
        "video": video,
    }
    if jsonld_language is not None:
        recipe["inLanguage"] = jsonld_language
    encoded_recipe = json.dumps(recipe, ensure_ascii=False)
    return f"""<!doctype html>
<html lang="{html_language}"><head>
<link rel="shortlink" href="https://www.argiro.gr/?p=990001">
<script type="application/ld+json">{encoded_recipe}</script>
<script>var AM = {{"recipe":{{"id":990001,"stats":{{"rating":4.5,"total_votes":12,"rating_percentage":90}}}}}};</script>
</head><body>
<div class="difficulty_level">Εύκολη</div>
<div class="article__tags"><a class="tag_item" href="https://www.argiro.gr/recipe-category/ospria/">Όσπρια</a></div>
<aside class="single_recipe__left_column"><div class="ingredients">
  <div class="ingredients__item"><label class="ingredient-label"><span class="ingredients__quantity">1 φλιτζάνι</span><p>φακές</p></label></div>
</div></aside>
<section class="single_recipe__method_steps"><h3>Εκτέλεση</h3><ol><li>Βράζουμε τις φακές.</li></ol></section>
{extra_body}
</body></html>"""


@pytest.mark.parametrize(
    ("html_language", "jsonld_language"),
    [("en", None), ("el", "en")],
)
def test_explicit_non_greek_language_signal_fails_closed(
    html_language: str,
    jsonld_language: str | None,
):
    with pytest.raises(FullSchemaError, match="Greek|language"):
        normalize_argiro_page(
            _synthetic_page(
                html_language=html_language,
                jsonld_language=jsonld_language,
            ),
            source_url=SOURCE_URL,
        )


def test_english_recipe_with_one_greek_lookalike_letter_fails_closed():
    page = (
        _synthetic_page()
        .replace("Συνθετική φακή", "English lentil")
        .replace("Συνθετική ελληνική περιγραφή.", "English description.")
        .replace("1 φλιτζάνι φακές", "1 cup lentils")
        .replace("Βράζουμε τις φακές.", "Μake the lentils.")
    )
    with pytest.raises(FullSchemaError, match="Greek"):
        normalize_argiro_page(page, source_url=SOURCE_URL)


def test_direct_string_jsonld_video_is_preserved():
    video_url = "https://www.youtube.com/embed/synthetic-video"
    record = normalize_argiro_page(
        _synthetic_page(video=video_url),
        source_url=SOURCE_URL,
    )

    assert record["videoUrls"] == [video_url]


@pytest.mark.parametrize(
    "video",
    [
        {"@type": "VideoObject", "embedUrl": "https://www.youtube.com/embed/synthetic-video"},
        [{"contentUrl": "https://cdn.example.test/synthetic-video.mp4"}],
    ],
)
def test_mapping_and_list_jsonld_videos_are_preserved(video):
    record = normalize_argiro_page(
        _synthetic_page(video=video),
        source_url=SOURCE_URL,
    )
    expected = (
        "https://www.youtube.com/embed/synthetic-video"
        if isinstance(video, dict)
        else "https://cdn.example.test/synthetic-video.mp4"
    )
    assert record["videoUrls"] == [expected]


def test_recipe_scoped_supported_iframe_is_preserved():
    video_url = "https://player.vimeo.com/video/123456"
    record = normalize_argiro_page(
        _synthetic_page(
            video=None,
            extra_body=(
                '<section class="single_recipe__video">'
                f'<iframe src="{video_url}"></iframe></section>'
            ),
        ),
        source_url=SOURCE_URL,
    )
    assert record["videoUrls"] == [video_url]


def test_unrelated_page_iframe_is_not_accepted_as_recipe_video():
    record = normalize_argiro_page(
        _synthetic_page(
            video=None,
            extra_body='<iframe src="https://ads.example.test/unrelated-frame"></iframe>',
        ),
        source_url=SOURCE_URL,
    )

    assert record["videoUrls"] == []


def test_argiro_taxonomy_is_rederived_from_preserved_source_evidence():
    record = normalize_argiro_page(
        _synthetic_page(),
        source_url=SOURCE_URL,
    )
    payload = record["sourcePayload"]

    assert payload["jsonLd"]["recipeCategory"] == ["Όσπρια"]
    assert (
        {
            "href": "https://www.argiro.gr/recipe-category/ospria/",
            "label": "Όσπρια",
        }
        in payload["htmlMetadata"]["tagLinks"]
    )

    tampered = deepcopy(record)
    tampered["categoryKeys"] = ["fish"]
    tampered["category"] = "fish"
    tampered["categoryLabel"] = CATEGORY_LABELS["fish"]
    with pytest.raises(FullSchemaError, match="taxonomy|categoryKeys"):
        ensure_full_record(tampered)
