"""Synthetic-only Gastronomos parsing tests; no publisher recipe text is stored."""

import copy
import json

import pytest

from tools.recipe_importer.full_schema import FullSchemaError, ensure_full_record
from tools.recipe_importer.gastronomos_schema import (
    GastronomosLanguageError,
    derive_gastronomos_taxonomy,
    normalize_gastronomos_page,
    parse_duration_minutes,
)


SOURCE_URL = "https://www.gastronomos.gr/syntagh/synthetikes-fakes/203399/"


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


def test_taxonomy_precedence_is_exact_and_never_uses_title_or_ingredient_prose():
    base = normalize_gastronomos_page(_page(), source_url=SOURCE_URL)["sourcePayload"]

    dessert = copy.deepcopy(base)
    dessert["jsonLd"]["recipeCategory"] = ["Γλυκά"]
    assert derive_gastronomos_taxonomy(dessert)["category"] == "dessert"

    non_meal = copy.deepcopy(base)
    non_meal["jsonLd"]["recipeCategory"] = ["Ποτά"]
    assert derive_gastronomos_taxonomy(non_meal)["category"] == "other"

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
