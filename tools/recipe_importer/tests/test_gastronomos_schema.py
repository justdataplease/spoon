"""Synthetic-only Gastronomos parsing tests; no publisher recipe text is stored."""

import copy
import hashlib
import json

import pytest

from tools.recipe_importer import gastronomos_schema as gastronomos_schema
from tools.recipe_importer.full_schema import FullSchemaError, ensure_full_record
from tools.recipe_importer.gastronomos_schema import (
    GastronomosLanguageError,
    derive_gastronomos_taxonomy,
    normalize_gastronomos_page,
    normalize_gastronomos_payload,
    parse_gastronomos_page,
    parse_duration_minutes,
)


SOURCE_URL = "https://www.gastronomos.gr/syntagh/synthetikes-fakes/203399/"
LEGACY_SOURCE_URL = "https://www.gastronomos.gr/syntagh/synthetiki-palaiotypi/90262/"


def _recipe() -> dict:
    return {
        "@type": "Recipe",
        "url": SOURCE_URL,
        "name": "Συνθετικές φακές με μυρωδικά",
        "headline": "Συνθετικές φακές",
        "description": "Μια πλήρως συνθετική ελληνική περιγραφή για δοκιμή.",
        "inLanguage": "el-GR",
        "author": {"@type": "Person", "name": "Δοκιμαστικός Μάγειρας"},
        "datePublished": "2026-01-02T10:00:00+02:00",
        "dateModified": "2026-01-03T11:00:00+02:00",
        "image": [
            "https://www.gastronomos.gr/wp-content/uploads/synthetic-main.jpg",
            {"url": "https://cdn.example.test/synthetic-second.jpg"},
        ],
        "prepTime": "PT15M",
        "cookTime": "PT30M",
        "totalTime": "PT1H",
        "recipeYield": ["4 μερίδες"],
        "recipeCategory": ["Κυρίως πιάτο", "Όσπρια"],
        "recipeCuisine": "Ελληνική",
        "suitableForDiet": "https://schema.org/VeganDiet",
        "keywords": "δοκιμή, κατσαρόλα",
        "aggregateRating": {
            "@type": "AggregateRating",
            "ratingValue": "4.3",
            "bestRating": "5",
            "ratingCount": "27",
        },
        "recipeIngredient": [
            "250 γρ. συνθετικές φακές",
            "1 λίτρο νερό",
            "2 κ.σ. ελαιόλαδο",
        ],
        "recipeInstructions": [
            {
                "@type": "HowToSection",
                "name": "Προετοιμασία",
                "itemListElement": [
                    {"@type": "HowToStep", "text": "Ξεπλένουμε τα υλικά."},
                ],
            },
            {
                "@type": "HowToSection",
                "name": "Μαγείρεμα",
                "itemListElement": [
                    {"@type": "HowToStep", "text": "Βράζουμε σε χαμηλή φωτιά."},
                    {"@type": "HowToTip", "text": "Δοκιμάζουμε πριν σερβίρουμε."},
                    {"@type": "HowToStep", "text": "Σερβίρουμε ζεστό."},
                ],
            },
        ],
        "nutrition": {
            "@type": "NutritionInformation",
            "servingSize": "1 μερίδα",
            "calories": "320 kcal",
            "fatContent": "8 g",
            "proteinContent": "17 g",
            "fiberContent": "12 g",
        },
        "video": {
            "@type": "VideoObject",
            "name": "Συνθετικό βίντεο",
            "contentUrl": "https://www.youtube.com/watch?v=synthetic123",
            "embedUrl": "https://www.youtube.com/embed/synthetic123",
        },
        "syntheticExtension": {"unknownField": "Διατηρείται αυτούσιο"},
    }


def _page(recipe: dict | None = None, *, language: str = "el") -> str:
    payload = {"@context": "https://schema.org", "@graph": [recipe or _recipe()]}
    return f"""<!doctype html>
<html lang="{language}"><head>
  <link rel="canonical" href="{SOURCE_URL}">
  <meta charset="utf-8">
  <meta name="description" content="Συνθετική περιγραφή SEO.">
  <meta property="og:title" content="Συνθετικός τίτλος SEO">
  <script type="application/ld+json">{{not valid json</script>
  <script type="application/ld+json">{json.dumps(payload, ensure_ascii=False)}</script>
</head><body>
<nav><a href="https://www.gastronomos.gr/vasiko-yliko/kreas/">Άσχετο κρέας πλοήγησης</a></nav>
<article class="single-recipe syntagh-content">
  <a href="https://www.gastronomos.gr/vasiko-yliko/ospria/">Όσπρια</a>
  <a href="https://www.gastronomos.gr/eidos-geumatos/kyrio-piato/">Κυρίως πιάτο</a>
  <a href="https://www.gastronomos.gr/eidiki-diatrofi/vegan/">Vegan</a>
  <a href="https://www.gastronomos.gr/eidos-syntagon/ellinika-paradosiaka/">Ελληνικά παραδοσιακά</a>
  <a href="https://www.gastronomos.gr/eidos-syntagon/katsarolas/">Κατσαρόλας</a>
  <a href="https://www.gastronomos.gr/eidos-syntagon/agnostos-typos/">Άγνωστος τύπος</a>
  <div class="related-recipes">
    <a href="https://www.gastronomos.gr/vasiko-yliko/kreas/">Άσχετο κρέας προτάσεων</a>
    <img src="https://www.gastronomos.gr/wp-content/uploads/unrelated-card.jpg">
  </div>
  <dl class="recipe-times">
    <dt>Αναμονή</dt><dd>15 λεπτά</dd>
  </dl>
  <div data-label="Δυσκολία">Εύκολη</div>
  <section class="ingredient-section">
    <h3>Για τις φακές</h3>
    <ul>
      <li>250 γρ. συνθετικές φακές</li>
      <li>1 λίτρο νερό</li>
    </ul>
  </section>
  <section class="ingredient-section">
    <h3>Για το τελείωμα</h3>
    <ul><li>2 κ.σ. ελαιόλαδο</li></ul>
  </section>
  <section class="recipe-tip"><p>Χρησιμοποιούμε καθαρά συνθετικά δεδομένα.</p></section>
  <section class="equipment"><ul><li>Κατσαρόλα</li></ul></section>
  <img src="https://www.gastronomos.gr/wp-content/uploads/synthetic-inline.jpg">
  <iframe src="https://www.youtube.com/embed/synthetic123"></iframe>
</article></body></html>"""


def _legacy_page(source_url: str = LEGACY_SOURCE_URL) -> str:
    recipe = _recipe()
    recipe["url"] = source_url
    recipe["name"] = "Χειροποίητη συνθετική δοκιμή"
    recipe["description"] = "Παλαιότυπη ελληνική συνταγή για συνθετική δοκιμή."
    recipe.pop("recipeIngredient")
    recipe.pop("recipeInstructions")
    payload = {"@context": "https://schema.org", "@graph": [recipe]}
    return f"""<!doctype html>
<html lang="el"><head>
  <link rel="canonical" href="{source_url}">
  <script type="application/ld+json">{json.dumps(payload, ensure_ascii=False)}</script>
</head><body class="recipe-template-default single single-recipe rightsidebar">
<header>
  <img src="https://www.gastronomos.gr/wp-content/uploads/global-logo.jpg">
  <iframe src="https://www.googletagmanager.com/ns.html?id=synthetic"></iframe>
</header>
<article class="single-recipe">
<div class="entry-content">
  <p>Εισαγωγικό κείμενο που δεν είναι υλικό ή βήμα.</p>
  <h2>Χειροποίητη συνθετική δοκιμή</h2>
  <h3>ΥΛΙΚΑ</h3>
  <p>• 400 γρ. <strong>συνθετικό αλεύρι</strong> + 100 γρ. για το άνοιγμα<br>
     • 200 ml νερό<br>• 1 κ.σ. ελαιόλαδο<br>• 1 πρέζα αλάτι</p>
  <div class="page">
    <h3>ΔΙΑΔΙΚΑΣΙΑ</h3>
    <p>Κάνουμε το βήμα <em>ένα</em>.</p>
    <p>Κάνουμε το βήμα δύο.</p>
    <div class="related-recipes"><p>Άσχετη προτεινόμενη συνταγή.</p></div>
    <p>Κάνουμε το βήμα τρία.</p>
    <p>Κάνουμε το βήμα τέσσερα.</p>
    <p>Κάνουμε το βήμα πέντε.</p>
    <h2>Συνθετική συλλογή από σάλτσες</h2>
    <h2>Συνθετική σάλτσα Α</h2>
    <p>Ετοιμάζουμε την πρώτη σάλτσα.</p>
    <p>Σερβίρουμε την πρώτη σάλτσα.</p>
    <h2>Συνθετική σάλτσα Β</h2>
    <p>Ετοιμάζουμε τη δεύτερη σάλτσα.</p>
    <p>Ανακατεύουμε τη δεύτερη σάλτσα.</p>
    <p>Σερβίρουμε τη δεύτερη σάλτσα.</p>
  </div>
</div></article></body></html>"""


def test_normalizes_full_greek_jsonld_and_narrow_recipe_html_evidence():
    record = normalize_gastronomos_page(
        _page(),
        source_url=SOURCE_URL,
        sitemap_last_modified="2026-01-03",
    )
    ensure_full_record(record)
    assert record["id"] == "gastronomos_203399"
    assert record["providerRecipeId"] == "203399"
    assert record["sourceRecipeId"] == 203399
    assert record["sourceKey"] == "gastronomos"
    assert record["source"] == "gastronomos.gr"
    assert record["sourceName"] == "Γαστρονόμος"
    assert record["slug"] == "synthetikes-fakes"
    assert record["language"] == "el"
    assert record["category"] == "legumes"
    assert record["categoryKeys"] == ["legumes"]
    assert record["rating"] == 8.6
    assert record["ratingCount"] == 27
    assert record["prepMinutes"] == 15
    assert record["cookMinutes"] == 30
    assert record["waitMinutes"] == 15
    assert record["totalMinutes"] == 60
    assert record["stepCount"] == 3
    assert record["preparationCount"] == 2
    assert record["sourceDifficulty"] == "Εύκολη"
    assert record["servings"] == "4 μερίδες"
    assert record["authorName"] == "Δοκιμαστικός Μάγειρας"
    assert record["ingredientSections"] == [
        {
            "title": "Για τις φακές",
            "ingredients": [
                {
                    "title": "250 γρ. συνθετικές φακές",
                    "unit": "", "quantity": "", "info": "",
                    "internalLink": "", "externalLink": "",
                    "ukUnit": "", "ukQuantity": "", "usUnit": "", "usQuantity": "",
                },
                {
                    "title": "1 λίτρο νερό",
                    "unit": "", "quantity": "", "info": "",
                    "internalLink": "", "externalLink": "",
                    "ukUnit": "", "ukQuantity": "", "usUnit": "", "usQuantity": "",
                },
            ],
        },
        {
            "title": "Για το τελείωμα",
            "ingredients": [{
                "title": "2 κ.σ. ελαιόλαδο",
                "unit": "", "quantity": "", "info": "",
                "internalLink": "", "externalLink": "",
                "ukUnit": "", "ukQuantity": "", "usUnit": "", "usQuantity": "",
            }],
        },
    ]
    assert record["methodSections"] == [
        {"title": "Προετοιμασία", "steps": ["Ξεπλένουμε τα υλικά."]},
        {
            "title": "Μαγείρεμα",
            "steps": ["Βράζουμε σε χαμηλή φωτιά.", "Σερβίρουμε ζεστό."],
        },
    ]
    assert record["tips"] == [
        "Δοκιμάζουμε πριν σερβίρουμε.",
        "Χρησιμοποιούμε καθαρά συνθετικά δεδομένα.",
    ]
    assert record["equipment"] == ["Κατσαρόλα"]
    assert record["nutritionPer"] == "1 μερίδα"
    assert record["nutritionSections"][0]["kcalPortion"] == "320 kcal"
    assert record["nutritionSections"][0]["proteinPortion"] == "17 g"
    assert record["imageUrls"] == [
        "https://www.gastronomos.gr/wp-content/uploads/synthetic-main.jpg",
        "https://cdn.example.test/synthetic-second.jpg",
        "https://www.gastronomos.gr/wp-content/uploads/synthetic-inline.jpg",
    ]
    assert record["videoUrls"] == [
        "https://www.youtube.com/watch?v=synthetic123",
    ]
    assert record["dietLabels"] == ["Vegan"]
    assert record["mealTypeLabels"] == ["Κυρίως πιάτο"]
    assert record["ingredientLabels"] == ["Όσπρια"]
    assert record["cuisineLabels"] == ["Ελληνικά παραδοσιακά", "Ελληνική"]
    assert record["methodLabels"] == ["Κατσαρόλας"]
    assert "Άγνωστος τύπος" in record["tags"]
    assert "Άσχετο κρέας πλοήγησης" not in record["tags"]
    assert "Άσχετο κρέας προτάσεων" not in record["tags"]
    assert all("unrelated-card" not in value for value in record["imageUrls"])
    assert record["sourcePayload"]["jsonLd"]["recipeIngredient"] == _recipe()["recipeIngredient"]
    assert record["sourcePayload"]["jsonLd"]["syntheticExtension"] == {
        "unknownField": "Διατηρείται αυτούσιο"
    }
    assert record["sourcePayload"]["htmlMetadata"]["invalidJsonLdBlockCount"] == 1
    assert record["sitemapLastModified"] == "2026-01-03"


def test_complete_page_and_pure_payload_normalization_remain_byte_identical():
    page_record = normalize_gastronomos_page(
        _page(),
        source_url=SOURCE_URL,
        sitemap_last_modified="2026-01-03",
    )
    recipe, metadata = parse_gastronomos_page(_page(), source_url=SOURCE_URL)
    payload_record = normalize_gastronomos_payload(
        recipe,
        metadata,
        source_url=SOURCE_URL,
        sitemap_last_modified="2026-01-03",
        active=True,
    )
    assert payload_record == page_record
    compact = json.dumps(
        page_record,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=False,
    ).encode("utf-8")
    compact_source = json.dumps(
        page_record["sourcePayload"],
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=False,
    ).encode("utf-8")
    assert hashlib.sha256(compact).hexdigest() == (
        "2d8ec5d2c9ac1bc15eceb14c4cede42be4e78339222d1c4daef13ba5ff8e9723"
    )
    assert hashlib.sha256(compact_source).hexdigest() == (
        "1e40819631b7b6a1911bd6f871907df81f837c0591117ebe31b164ff57d9869a"
    )
    assert not any(key.startswith("legacy") for key in metadata)


def test_audited_90262_legacy_entry_fallback_preserves_groups_and_inline_order():
    record = normalize_gastronomos_page(
        _legacy_page(),
        source_url=LEGACY_SOURCE_URL,
    )
    ensure_full_record(record)
    assert record["providerRecipeId"] == "90262"
    assert record["ingredientSections"] == [{
        "title": "ΥΛΙΚΑ",
        "ingredients": [
            {
                "title": title,
                "unit": "", "quantity": "", "info": "",
                "internalLink": "", "externalLink": "",
                "ukUnit": "", "ukQuantity": "", "usUnit": "", "usQuantity": "",
            }
            for title in [
                "400 γρ. συνθετικό αλεύρι + 100 γρ. για το άνοιγμα",
                "200 ml νερό",
                "1 κ.σ. ελαιόλαδο",
                "1 πρέζα αλάτι",
            ]
        ],
    }]
    assert record["methodSections"] == [
        {
            "title": "ΔΙΑΔΙΚΑΣΙΑ",
            "steps": [
                "Κάνουμε το βήμα ένα.",
                "Κάνουμε το βήμα δύο.",
                "Κάνουμε το βήμα τρία.",
                "Κάνουμε το βήμα τέσσερα.",
                "Κάνουμε το βήμα πέντε.",
            ],
        },
        {
            "title": "Συνθετική σάλτσα Α",
            "steps": [
                "Ετοιμάζουμε την πρώτη σάλτσα.",
                "Σερβίρουμε την πρώτη σάλτσα.",
            ],
        },
        {
            "title": "Συνθετική σάλτσα Β",
            "steps": [
                "Ετοιμάζουμε τη δεύτερη σάλτσα.",
                "Ανακατεύουμε τη δεύτερη σάλτσα.",
                "Σερβίρουμε τη δεύτερη σάλτσα.",
            ],
        },
    ]
    assert record["stepCount"] == 10
    assert record["preparationCount"] == 3
    assert all(
        "Άσχετη" not in step
        for section in record["methodSections"]
        for step in section["steps"]
    )
    assert all("global-logo" not in url for url in record["imageUrls"])
    assert all("googletagmanager" not in url for url in record["videoUrls"])
    metadata = record["sourcePayload"]["htmlMetadata"]
    assert metadata["legacyEntryProfile"] == "entry-content-90262-v1"
    assert metadata["legacyIngredientSections"] == record["ingredientSections"]
    assert metadata["legacyMethodSections"] == record["methodSections"]


def test_legacy_entry_fallback_is_provider_bound_and_shape_drift_fails_closed():
    other_url = "https://www.gastronomos.gr/syntagh/synthetiki-palaiotypi/90263/"
    with pytest.raises(FullSchemaError, match="has no ingredients"):
        normalize_gastronomos_page(
            _legacy_page(other_url),
            source_url=other_url,
        )

    drifted = _legacy_page().replace(
        "<p>Σερβίρουμε τη δεύτερη σάλτσα.</p>",
        "",
    )
    with pytest.raises(FullSchemaError, match="changed method shape"):
        normalize_gastronomos_page(drifted, source_url=LEGACY_SOURCE_URL)


def test_brand_heavy_ingredients_do_not_swamp_independent_greek_evidence():
    recipe = _recipe()
    recipe["recipeIngredient"] = [
        f"International Brand Product Number {index} With Long English Label"
        for index in range(40)
    ]
    recipe["recipeInstructions"] = [
        {
            "@type": "HowToStep",
            "text": "Ανακατεύουμε προσεκτικά τα υλικά.",
        },
        {
            "@type": "HowToStep",
            "text": (
                "Finish with International Brand Garnish Product and several "
                "additional English serving directions that are deliberately "
                "longer than the genuine Greek preparation step."
            ),
        },
    ]
    record = normalize_gastronomos_page(_page(recipe), source_url=SOURCE_URL)
    assert record["language"] == "el"
    assert len(record["ingredientSections"][0]["ingredients"]) == 40
    assert record["stepCount"] == 2


def test_greek_title_alone_does_not_admit_an_english_recipe_body():
    recipe = _recipe()
    recipe["name"] = "Συνθετική συνταγή"
    recipe["description"] = "An entirely English description for this test recipe."
    recipe["recipeIngredient"] = [
        "International Brand Product With A Long English Ingredient Label"
        for _ in range(5)
    ]
    recipe["recipeInstructions"] = [{
        "@type": "HowToStep",
        "text": "Mix every ingredient carefully and serve the finished dish immediately.",
    }]
    with pytest.raises(GastronomosLanguageError, match="not substantively Greek"):
        normalize_gastronomos_page(_page(recipe), source_url=SOURCE_URL)


def test_taxonomy_precedence_is_exact_and_never_uses_title_or_ingredient_prose():
    base = normalize_gastronomos_page(_page(), source_url=SOURCE_URL)["sourcePayload"]

    dessert = copy.deepcopy(base)
    dessert["jsonLd"]["recipeCategory"] = ["Γλυκά"]
    assert derive_gastronomos_taxonomy(dessert)["category"] == "dessert"

    non_meal = copy.deepcopy(base)
    non_meal["jsonLd"]["recipeCategory"] = ["Ποτά"]
    assert derive_gastronomos_taxonomy(non_meal)["category"] == "drinks"

    street = copy.deepcopy(base)
    street["jsonLd"]["recipeCategory"] = ["Σάντουιτς"]
    assert derive_gastronomos_taxonomy(street)["category"] == "street_food"

    explicit_fish = copy.deepcopy(base)
    explicit_fish["jsonLd"]["recipeCategory"] = ["Ψάρια"]
    assert derive_gastronomos_taxonomy(explicit_fish)["categoryKeys"] == ["fish", "legumes"]

    generic_snack = copy.deepcopy(base)
    generic_snack["jsonLd"]["name"] = "Γλυκά κεφτεδάκια με φακές"
    generic_snack["jsonLd"]["recipeCategory"] = ["Snack"]
    assert derive_gastronomos_taxonomy(generic_snack)["category"] == "legumes"


def test_exact_publisher_keywords_are_category_evidence_without_prose_guessing():
    payload = normalize_gastronomos_page(
        _page(), source_url=SOURCE_URL
    )["sourcePayload"]
    payload["htmlMetadata"]["facetLinks"] = []
    payload["jsonLd"]["recipeCategory"] = ["Κυρίως Γεύμα"]
    payload["jsonLd"]["keywords"] = "Ψάρι, Ρύζι"
    payload["jsonLd"]["name"] = "Γλυκό κρέας με φακές"
    payload["jsonLd"]["recipeIngredient"] = [
        "κοτόπουλο και λαχανικά σε ελεύθερο κείμενο"
    ]

    assert derive_gastronomos_taxonomy(payload)["categoryKeys"] == [
        "fish",
        "pasta_rice",
    ]

    payload["jsonLd"]["keywords"] = "ψαρόσουπα, ρυζότο κουνουπιδιού"
    assert derive_gastronomos_taxonomy(payload)["categoryKeys"] == ["other"]


def test_weak_keyword_never_overrides_or_extends_structured_category_evidence():
    payload = normalize_gastronomos_page(
        _page(), source_url=SOURCE_URL
    )["sourcePayload"]
    payload["jsonLd"]["recipeCategory"] = ["Κυρίως Γεύμα", "Όσπρια"]
    payload["jsonLd"]["keywords"] = "Γλυκά, Ψάρι"

    assert derive_gastronomos_taxonomy(payload)["categoryKeys"] == ["legumes"]

    payload["jsonLd"]["recipeCategory"] = ["Κυρίως Γεύμα"]
    assert derive_gastronomos_taxonomy(payload)["categoryKeys"] == ["legumes"]

    payload["htmlMetadata"]["facetLinks"] = []
    payload["jsonLd"]["recipeCategory"] = ["Γλυκό"]
    assert derive_gastronomos_taxonomy(payload)["categoryKeys"] == ["dessert"]


@pytest.mark.parametrize(
    ("keywords", "expected"),
    [
        ("Γλυκό, Ψάρι", "dessert"),
        ("Ποτά, Ψάρι", "drinks"),
        ("Burger, Κρέας", "street_food"),
    ],
)
def test_terminal_keyword_taxonomy_precedes_main_ingredient_tags(
    keywords: str,
    expected: str,
):
    payload = normalize_gastronomos_page(
        _page(), source_url=SOURCE_URL
    )["sourcePayload"]
    payload["htmlMetadata"]["facetLinks"] = []
    payload["jsonLd"]["recipeCategory"] = ["Κυρίως Γεύμα"]
    payload["jsonLd"]["keywords"] = keywords

    taxonomy = derive_gastronomos_taxonomy(payload)
    assert taxonomy["category"] == expected
    assert taxonomy["categoryKeys"][0] == expected


def test_exact_publisher_taxonomy_populates_explore_facets_without_guessing():
    recipe, metadata = parse_gastronomos_page(
        _page(), source_url=SOURCE_URL
    )
    recipe = copy.deepcopy(recipe)
    metadata = copy.deepcopy(metadata)
    metadata["facetLinks"] = []
    recipe["recipeCategory"] = ["Κυρίως Γεύμα"]
    recipe["recipeCuisine"] = []
    recipe["suitableForDiet"] = []
    recipe["keywords"] = (
        "Vegan, Ιταλική Κουζίνα, BBQ, Brunch, Χριστούγεννα, "
        "Ψάρι, Sponsored, Ψαρόσουπα"
    )
    record = normalize_gastronomos_payload(
        recipe,
        metadata,
        source_url=SOURCE_URL,
    )

    assert record["categoryKeys"] == ["fish"]
    assert record["dietLabels"] == ["Vegan"]
    assert record["mealTypeLabels"] == ["Κυρίως Γεύμα", "Brunch"]
    assert record["occasionLabels"] == ["Χριστούγεννα"]
    assert record["methodLabels"] == ["BBQ"]
    assert record["cuisineLabels"] == ["Ιταλική Κουζίνα"]
    assert record["ingredientLabels"] == ["Ψάρι"]
    assert "Sponsored" in record["tags"]
    assert "Ψαρόσουπα" in record["tags"]
    for values in (
        record["dietLabels"],
        record["mealTypeLabels"],
        record["occasionLabels"],
        record["methodLabels"],
        record["cuisineLabels"],
        record["ingredientLabels"],
    ):
        assert "Sponsored" not in values
        assert "Ψαρόσουπα" not in values


def test_keyword_facet_matching_is_normalized_exact_not_substring_based():
    recipe, metadata = parse_gastronomos_page(
        _page(), source_url=SOURCE_URL
    )
    recipe = copy.deepcopy(recipe)
    metadata = copy.deepcopy(metadata)
    metadata["facetLinks"] = []
    recipe["recipeCategory"] = ["Κυρίως Γεύμα"]
    recipe["recipeCuisine"] = []
    recipe["suitableForDiet"] = []
    recipe["keywords"] = (
        "Vegan επιλογή, Ιταλική Κουζίνα σήμερα, BBQ sauce, "
        "Χριστούγεννα μαζί, Ψάρι πλακί"
    )
    record = normalize_gastronomos_payload(
        recipe,
        metadata,
        source_url=SOURCE_URL,
    )

    assert record["categoryKeys"] == ["other"]
    assert record["dietLabels"] == []
    assert record["mealTypeLabels"] == ["Κυρίως Γεύμα"]
    assert record["occasionLabels"] == []
    assert record["methodLabels"] == []
    assert record["cuisineLabels"] == []
    assert record["ingredientLabels"] == []


def test_audited_keyword_vocabulary_is_an_exact_disjoint_partition():
    facet_sets = gastronomos_schema._KEYWORD_FACET_KEY_ALLOWLISTS
    assert {key: len(values) for key, values in facet_sets.items()} == {
        "cuisine": 4,
        "diet": 8,
        "meal_type": 1,
        "occasion": 8,
        "method": 1,
        "ingredient": 211,
    }
    facets = list(facet_sets.values())
    assert all(
        not left.intersection(right)
        for index, left in enumerate(facets)
        for right in facets[index + 1:]
    )
    facet_union = frozenset().union(*facets)
    tag_only = gastronomos_schema._AUDITED_KEYWORD_TAG_ONLY_KEYS
    assert len(facet_union) == 233
    assert len(tag_only) == 16
    assert facet_union.isdisjoint(tag_only)
    assert len(facet_union | tag_only) == 249


def test_audited_archive_navigation_labels_never_become_recipe_facets_or_tags():
    recipe, metadata = parse_gastronomos_page(
        _page(), source_url=SOURCE_URL
    )
    recipe = copy.deepcopy(recipe)
    metadata = copy.deepcopy(metadata)
    recipe["recipeCategory"] = ["Κυρίως Γεύμα"]
    recipe["recipeCuisine"] = []
    recipe["suitableForDiet"] = []
    recipe["keywords"] = ""
    navigation_labels = [
        "Άρθρα και Συνταγές για Κοκτέιλ",
        "Άρθρα και Συνταγές για Κυρίως Γεύμα",
        "Άρθρα και Συνταγές με Αλεύρι (ζύμες)",
        "Άρθρα και Συνταγές με Ζυμαρικά",
    ]
    metadata["facetLinks"] = [
        {
            "family": "eidos-geumatos",
            "label": "ΚΟΚΤΕΙΛ",
            "slug": "kokteil",
        },
        {
            "family": "vasiko-yliko",
            "label": "ΖΥΜΑΡΙΚΑ",
            "slug": "zymarika",
        },
        *[
            {
                "family": (
                    "eidos-geumatos"
                    if "για" in label.casefold()
                    else "vasiko-yliko"
                ),
                "label": label,
                "slug": "navigation-only",
            }
            for label in navigation_labels
        ],
    ]
    record = normalize_gastronomos_payload(
        recipe,
        metadata,
        source_url=SOURCE_URL,
    )

    assert record["mealTypeLabels"] == ["ΚΟΚΤΕΙΛ", "Κυρίως Γεύμα"]
    assert record["ingredientLabels"] == ["ΖΥΜΑΡΙΚΑ"]
    assert record["categoryKeys"] == ["drinks", "pasta_rice"]
    assert all(label not in record["tags"] for label in navigation_labels)
    assert all(
        label not in values
        for label in navigation_labels
        for values in (
            record["dietLabels"],
            record["mealTypeLabels"],
            record["occasionLabels"],
            record["methodLabels"],
            record["cuisineLabels"],
            record["ingredientLabels"],
        )
    )


def test_mismatched_or_non_numeric_recipe_identity_is_rejected():
    recipe = _recipe()
    recipe["url"] = "https://www.gastronomos.gr/syntagh/allo/999/"
    with pytest.raises(FullSchemaError, match="matching JSON-LD Recipe"):
        normalize_gastronomos_page(_page(recipe), source_url=SOURCE_URL)
    with pytest.raises(FullSchemaError, match="strict recipe URL"):
        normalize_gastronomos_page(
            _page(),
            source_url="https://www.gastronomos.gr/syntages/synthetikes-fakes/203399/",
        )


def test_non_greek_pages_fail_closed_even_when_recipe_shape_is_valid():
    recipe = _recipe()
    recipe["inLanguage"] = "en"
    with pytest.raises(GastronomosLanguageError, match="not Greek"):
        normalize_gastronomos_page(_page(recipe, language="en"), source_url=SOURCE_URL)


def test_html_ingredient_groups_are_used_only_when_they_align_with_jsonld():
    intact = _page()
    head, separator, tail = intact.rpartition("1 λίτρο νερό")
    assert separator
    broken = head + "1 λίτρο διαφορετικό υλικό" + tail
    record = normalize_gastronomos_page(broken, source_url=SOURCE_URL)
    assert record["ingredientSections"] == [{
        "title": "",
        "ingredients": [
            {
                "title": item,
                "unit": "", "quantity": "", "info": "",
                "internalLink": "", "externalLink": "",
                "ukUnit": "", "ukQuantity": "", "usUnit": "", "usQuantity": "",
            }
            for item in _recipe()["recipeIngredient"]
        ],
    }]


@pytest.mark.parametrize(
    ("value", "minutes"),
    [
        ("PT45M", 45),
        ("PT1H5M", 65),
        ("P1DT2H", 1560),
        ("1 ώρα και 30 λεπτά", 90),
        ("45 δευτερόλεπτα", 1),
        ("bad", 0),
    ],
)
def test_duration_parsing(value, minutes):
    assert parse_duration_minutes(value) == minutes


@pytest.mark.parametrize("label", ["Κιμάς κοτόπουλου", "ΚΙΜΑΣ ΚΟΤΟΠΟΥΛΟΥ", "kimas-kotopoulou"])
def test_official_chicken_mince_tag_is_poultry(label):
    payload = {
        "htmlMetadata": {"facetLinks": []},
        "jsonLd": {"recipeCategory": ["Κυρίως Γεύμα"], "keywords": label},
    }
    assert derive_gastronomos_taxonomy(payload)["category"] == "poultry"
    payload["jsonLd"]["recipeCategory"] = ["Σάντουιτς"]
    assert derive_gastronomos_taxonomy(payload)["category"] == "street_food"
    payload["jsonLd"]["recipeCategory"] = ["Γλυκό"]
    assert derive_gastronomos_taxonomy(payload)["category"] == "dessert"


def test_chicken_mince_prose_alone_does_not_reclassify_a_recipe():
    payload = {
        "htmlMetadata": {"facetLinks": []},
        "jsonLd": {
            "recipeCategory": ["Κυρίως Γεύμα"],
            "name": "Κιμάς κοτόπουλου",
            "recipeIngredient": ["Κιμάς κοτόπουλου"],
            "keywords": "ζωμός κοτόπουλου",
        },
    }
    assert derive_gastronomos_taxonomy(payload)["category"] == "other"


@pytest.mark.parametrize("label, category", [
    ("Γαρίδες", "fish"), ("Καλαμάρι / Θράψαλο", "fish"), ("Μπακαλιάρος", "fish"),
    ("Κυδώνια / Όστρακα", "fish"), ("Ρεβύθια", "legumes"), ("Κουκιά", "legumes"),
    ("Κόκορας", "poultry"), ("Κιμάς γαλοπούλας", "poultry"),
    ("Μοσχαρίσιος κιμάς", "meat"), ("Χοιρινός κιμάς", "meat"),
    ("Πλιγούρι", "pasta_rice"), ("Χυλοπίτες", "pasta_rice"),
    ("Μανιτάρια", "vegetables"), ("Φασολάκια", "vegetables"),
])
def test_reviewed_main_ingredient_categories_preserve_dish_precedence(label, category):
    payload = {
        "htmlMetadata": {"facetLinks": [
            {"family": "vasiko-yliko", "slug": "fixture", "label": label},
        ]},
        "jsonLd": {"recipeCategory": ["Κυρίως Γεύμα"]},
    }
    assert derive_gastronomos_taxonomy(payload)["category"] == category
    for explicit, expected in [("Γλυκά", "dessert"), ("Ποτά", "drinks"), ("Σάντουιτς", "street_food")]:
        payload["jsonLd"]["recipeCategory"] = [explicit]
        assert derive_gastronomos_taxonomy(payload)["category"] == expected
    payload["htmlMetadata"]["facetLinks"] = []
    payload["jsonLd"] = {"recipeCategory": ["Κυρίως Γεύμα"], "name": label, "recipeIngredient": [label]}
    assert derive_gastronomos_taxonomy(payload)["category"] == "other"


@pytest.mark.parametrize("label", ["Κυδώνι", "Γάλα Ρυζιού", "Αλεύρι (ζύμες)", "Σοκολάτα", "Ζάχαρη", "Μαϊντανός"])
def test_pantry_and_ambiguous_food_labels_do_not_imply_main_meal_categories(label):
    payload = {"htmlMetadata": {"facetLinks": []}, "jsonLd": {"keywords": label}}
    assert derive_gastronomos_taxonomy(payload)["category"] == "other"


def test_greek_cocktail_category_cannot_become_vegetables_from_tomato_tag():
    payload = {
        "htmlMetadata": {"facetLinks": [
            {"family": "vasiko-yliko", "slug": "tomato", "label": "Ντομάτα"},
        ]}, "jsonLd": {"recipeCategory": ["Κοκτέιλ"]},
    }
    assert derive_gastronomos_taxonomy(payload)["category"] == "drinks"
