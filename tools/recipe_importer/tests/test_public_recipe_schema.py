"""Synthetic public-source fixtures; no publisher recipe content is checked in."""
import json

import pytest

from tools.recipe_importer.full_schema import FullSchemaError, ensure_full_record
from tools.recipe_importer.public_recipe_schema import (
    NonGreekRecipe, derive_public_recipe_taxonomy, normalize_public_recipe_page,
    normalize_public_recipe_payload,
)
from tools.recipe_importer.tsoulis_schema import decode_nuxt_payload, normalize_tsoulis_page


LUCACOS_URL = "https://www.yiannislucacos.gr/recipe/vasikes-syntages/123/test"


def recipe(**changes):
    return {
        "@type": "Recipe", "url": LUCACOS_URL, "name": "Δοκιμαστικό φαγητό",
        "inLanguage": "el", "recipeIngredient": ["1 κούπα νερό", "2 πατάτες"],
        "recipeInstructions": [{"@type": "HowToStep", "text": "Ανακατεύουμε τα υλικά."}],
        "recipeCategory": "ΣΑΛΑΤΕΣ", "totalTime": "PT29M",
        "image": "https://www.yiannislucacos.gr/sites/default/files/test.jpg?itok=abc",
        **changes,
    }


def normalize(value=None, metadata=None):
    return normalize_public_recipe_payload(value or recipe(), metadata or {}, source_key="lucacos", source_url=LUCACOS_URL)


def test_multiline_recipe_jsonld_and_drupal_image_token():
    value = recipe(recipeIngredient="1 κούπα νερό\n2 πατάτες")
    encoded = json.dumps(value, ensure_ascii=False).replace("\\n", "\n")
    record = normalize_public_recipe_page(f'<html lang="el"><script type="application/ld+json">{encoded}</script></html>', source_key="lucacos", source_url=LUCACOS_URL)
    assert record["id"] == "lucacos_123"
    assert record["category"] == "vegetables"
    assert len(record["ingredientSections"][0]["ingredients"]) == 2
    assert record["imageUrl"].endswith("?itok=abc")
    ensure_full_record(record)


@pytest.mark.parametrize(("minutes", "quick"), [(0, False), (29, True), (30, False), (31, False)])
def test_quick_recipe_is_known_total_strictly_under_30(minutes, quick):
    assert normalize(recipe(totalTime=f"PT{minutes}M"))["quickRecipe"] is quick


def test_time_components_include_wait_and_fractional_hours():
    record = normalize(recipe(prepTime="PT5M", cookTime="PT0.33333333333333H", totalTime="PT20M"), {"waitMinutes": 10})
    assert (record["prepMinutes"], record["cookMinutes"], record["totalMinutes"]) == (5, 20, 35)


def test_title_cannot_create_category_or_diet():
    record = normalize(recipe(name="Κοτόπουλο vegan", recipeCategory="Άγνωστη"))
    assert record["category"] == "other"
    assert record["dietLabels"] == []


def test_taxonomy_can_be_rederived_and_tampering_fails():
    record = normalize()
    assert derive_public_recipe_taxonomy(record["sourcePayload"])["category"] == record["category"]
    record["categoryKeys"] = ["meat"]
    with pytest.raises(FullSchemaError, match="categoryKeys"):
        ensure_full_record(record)


def test_explicit_non_greek_language_rejected():
    with pytest.raises(NonGreekRecipe):
        normalize(recipe(inLanguage="en"))


def test_different_canonical_recipe_rejected():
    with pytest.raises(ValueError):
        normalize(recipe(url=LUCACOS_URL.replace("/123/", "/124/")))


def test_wordpress_native_post_id_wins_over_wprm_identifier():
    url = "https://funkycook.gr/test-recipe/"
    value = recipe(url=url, **{"@id": url + "#wprm-recipe-999"})
    html = '<html lang="el"><body class="postid-42"><script type="application/ld+json">' + json.dumps(value) + "</script></body></html>"
    record = normalize_public_recipe_page(html, source_key="funkycook", source_url=url)
    assert record["id"] == "funkycook_42"


def nuxt_html(value):
    table = []

    def encode(item):
        index = len(table)
        table.append(None)
        if isinstance(item, dict):
            table[index] = {key: encode(child) for key, child in item.items()}
        elif isinstance(item, list):
            table[index] = [encode(child) for child in item]
        else:
            table[index] = item
        return index

    encode(value)
    return '<script id="__NUXT_DATA__" type="application/json">' + json.dumps(table, ensure_ascii=False) + "</script>"


def test_tsoulis_nuxt_identity_group_order_and_wait():
    raw = {
        "id": 77, "title": "Δοκιμή ζύμης", "slug": "test", "is_published": 1,
        "ingredients_raw": "<div><strong>Για τη ζύμη</strong><br>1 κούπα αλεύρι<br>1 κούπα νερό</div>",
        "instructions": [{"id": 1, "description": "Ανακατεύουμε.", "recipe_part_id": 4}, {"id": 2, "description": "Περιμένουμε.", "recipe_part_id": 4}],
        "instruction_parts": [{"title": "Για τη ζύμη", "items": [{"id": 1, "description": "Ανακατεύουμε."}, {"id": 2, "description": "Περιμένουμε."}]}],
        "category": {"title": "Ζυμαρικά"}, "time": {"preparation": 5, "execution": 10, "waiting": 20, "total": 35},
        "media": {"large": "https://api.giorgostsoulis.com/storage/media/recipe/77/test.jpg"},
    }
    record = normalize_tsoulis_page(nuxt_html({"data": {"recipe": {"data": raw}}}), source_url="https://www.giorgostsoulis.com/syntages/zymarika/test")
    assert record["id"] == "tsoulis_77"
    assert record["authorName"] == "Γιώργος Τσούλης"
    assert record["stepCount"] == 2
    assert record["methodSections"] == [{"title": "Για τη ζύμη", "steps": ["Ανακατεύουμε.", "Περιμένουμε."]}]
    assert record["ingredientSections"][0]["title"] == "Για τη ζύμη"
    assert len(record["ingredientSections"][0]["ingredients"]) == 2
    assert record["totalMinutes"] == 35
    assert record["category"] == "pasta_rice"


def test_nuxt_cycle_rejected():
    with pytest.raises(FullSchemaError, match="cyclic"):
        decode_nuxt_payload('<script id="__NUXT_DATA__">[{"cycle":0}]</script>')


def test_ingredient_measures_preserve_specific_food_and_comma_notes():
    from tools.recipe_importer.public_recipe_schema import ingredient
    entry = ingredient("1/2 κ.γ. καπνιστή πάπρικα, γλυκιά")
    assert (entry["quantity"], entry["unit"], entry["title"], entry["info"]) == ("1/2", "κ.γ.", "καπνιστή πάπρικα", "γλυκιά")
    assert ingredient("2x400 γρ. φασόλια")["quantity"] == ""
    assert ingredient("1½ κ.σ. πάπρικα")["quantity"] == "1½"
    assert ingredient("1½ κ.σ. πάπρικα")["title"] == "πάπρικα"
    record = normalize(recipe(recipeIngredient=["100 γρ. Αμύγδαλο", "1 Vegan τυρί"]))
    assert "Αμύγδαλα" in record["ingredientLabels"]
    assert "Τυρί" not in record["ingredientLabels"]


def test_scoped_source_tags_populate_common_facets_and_bean_category():
    record = normalize(recipe(recipeCategory="Vegan", keywords=[]), {"tags": ["φασόλια", "Κυρίως Γεύματα", "BBQ", "Χριστούγεννα"]})
    assert record["category"] == "legumes"
    assert record["occasionLabels"] == ["Χριστούγεννα"]
    assert record["methodLabels"] == ["BBQ"]
    assert "Κυρίως γεύμα" in record["mealTypeLabels"]


def test_lucacos_scoped_tags_replace_concatenated_jsonld_keywords():
    value = recipe(keywords="Αλάτι Αμύγδαλο Χριστούγεννα Αλάτι Αμύγδαλο Χριστούγεννα")
    html = '<html lang="el"><a href="/free-tags/nav">Navigation</a><script type="application/ld+json">' + json.dumps(value) + '</script><section class="recipe-tags"><a href="/main-free-tags/alati">Αλάτι</a><a href="/main-free-tags/amygdalo">Αμύγδαλο</a><a href="/free-tags/christmas">Χριστούγεννα</a></section></html>'
    record = normalize_public_recipe_page(html, source_key="lucacos", source_url=LUCACOS_URL)
    assert value["keywords"] not in record["tags"]
    assert "Navigation" not in record["tags"]
    assert record["occasionLabels"] == ["Χριστούγεννα"]
    assert "Αμύγδαλο" in record["tags"]
    assert "Αμύγδαλα" not in record["ingredientLabels"]


@pytest.mark.parametrize("line_ending", ["\n", "\r\n", "\r\r\r\n"])
def test_lucacos_malformed_inner_quotes_are_recovered_without_dropping_title(line_ending):
    value = recipe(name='Τάρτα με "κρέμα"', description='Φαγητό με "γεύση"', recipeIngredient='1 πατάτα', recipeInstructions='Ανακατεύουμε.')
    encoded = json.dumps(value, ensure_ascii=False, indent=2).replace('\\"', '"')
    encoded = encoded.replace("\n", line_ending)
    record = normalize_public_recipe_page('<html lang="el"><script type="application/ld+json">' + encoded + '</script></html>', source_key="lucacos", source_url=LUCACOS_URL)
    assert record["title"] == 'Τάρτα με "κρέμα"'
    from tools.recipe_importer.public_recipe_schema import parse_public_recipe_page
    _, metadata = parse_public_recipe_page('<html lang="el"><script type="application/ld+json">' + encoded + '</script></html>', source_key="lucacos", source_url=LUCACOS_URL)
    assert metadata["recoveredJsonLdTemplate"] == encoded
    assert record["sourcePayload"]["htmlMetadata"]["recoveredJsonLdTemplate"] == encoded.replace("\r\n", "\n").replace("\r", "\n")


def test_cookpad_uses_recipe_keyword_section_without_navigation_terms():
    from tools.recipe_importer.cookpad_schema import normalize_cookpad_page
    url = "https://cookpad.com/gr/sintages/42"
    value = recipe(url=url, recipeCategory=[], keywords=[])
    html = '<html lang="el"><nav><a href="/gr/anazitisi/chicken">Κοτόπουλο</a></nav><script type="application/ld+json">' + json.dumps(value) + '</script><section data-recipe-section-show-logger-section-name-value="related_keywords"><h2>Λέξεις-κλειδιά</h2><a href="/gr/anazitisi/pasta">Μακαρονάδα</a></section></html>'
    record = normalize_cookpad_page(html, source_url=url)
    assert record["category"] == "pasta_rice"
    assert record["tags"] == ["Μακαρονάδα"]


def test_new_source_taxonomy_audit_rederives_from_preserved_payload(tmp_path):
    from tools.recipe_importer.audit_source_taxonomy import source_taxonomy, audit_sources
    record = normalize()
    expected = source_taxonomy(record)
    assert expected["category"] == "vegetables"
    record["ingredientLabels"] = ["unrelated"]
    path = tmp_path / "public.jsonl"
    path.write_text(json.dumps(record, ensure_ascii=False), encoding="utf-8")
    report = audit_sources([path])
    assert report["sourceErrorCount"] == 0
    assert report["tagMismatchCount"] == 1


def test_refresh_public_manifest_never_assigns_gastronomos_checkpoint_contract():
    import hashlib
    from tools.recipe_importer.refresh_taxonomy_artifacts import _updated_manifest, SOURCES
    record = normalize()
    updated = _updated_manifest("lucacos", [record], {"artifactSha256": "old", "checkpointRunKey": "stale"})
    assert all(source in SOURCES for source in ("tsoulis", "lucacos", "funkycook", "cookpad"))
    assert "checkpointRunKey" not in updated
    assert len(updated["parserContractHash"]) == 64
    encoded = json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False) + "\n"
    assert updated["artifactSha256"] == hashlib.sha256(encoded.encode("utf-8")).hexdigest()


@pytest.mark.parametrize(("source_key", "source_url"), [
    ("tsoulis", "https://www.giorgostsoulis.com/syntages/zymarika/test"),
    ("lucacos", LUCACOS_URL),
    ("funkycook", "https://funkycook.gr/test/"),
    ("cookpad", "https://cookpad.com/gr/sintages/42"),
])
def test_public_ingredient_facets_accept_only_reviewed_complete_identities(source_key, source_url):
    from tools.recipe_importer.ingredient_taxonomy import is_reviewed_ingredient
    from tools.recipe_importer.audit_source_taxonomy import source_taxonomy
    raw_labels = ["Αμύγδαλο", "κόκκινο", "κονσέρβας", "καπνιστή", "μοσχαρίσιο"]
    raw_ingredients = ["1 κούπα νερό", "2 απροσδιόριστα συστατικά"]
    record = normalize_public_recipe_payload(
        recipe(url=source_url, recipeIngredient=raw_ingredients),
        {"ingredientLabels": raw_labels, "tags": ["Σοκολάτα", "κόκκινο"]},
        source_key=source_key, source_url=source_url,
    )
    assert "Αμύγδαλα" in record["ingredientLabels"]
    assert "Σοκολάτα" not in record["ingredientLabels"]
    assert not set(raw_labels[1:]).intersection(record["ingredientLabels"])
    assert all(is_reviewed_ingredient(label) for label in record["ingredientLabels"])
    assert record["sourcePayload"]["htmlMetadata"]["ingredientLabels"] == raw_labels
    assert record["sourcePayload"]["jsonLd"]["recipeIngredient"] == raw_ingredients
    assert "Σοκολάτα" in record["tags"]
    assert source_taxonomy(record)["ingredientLabels"] == record["ingredientLabels"]


def test_cookpad_search_fragments_cannot_replace_whole_ingredient_identity():
    from tools.recipe_importer.cookpad_schema import normalize_cookpad_page
    from tools.recipe_importer.ingredient_taxonomy import canonical_ingredient_label
    url = "https://cookpad.com/gr/sintages/42"
    value = recipe(url=url, recipeIngredient=["100 ml Γάλα αμυγδάλου"], recipeCategory=[])
    html = '<html lang="el"><script type="application/ld+json">' + json.dumps(value) + '</script><section id="ingredients"><p id="ingredient_1">100 ml <a href="/gr/anazitisi/milk">Γάλα</a> <a href="/gr/anazitisi/almond">αμυγδάλου</a></p></section></html>'
    record = normalize_cookpad_page(html, source_url=url)
    assert "Γάλα" not in record["ingredientLabels"]
    assert "Αμύγδαλα" not in record["ingredientLabels"]
    assert canonical_ingredient_label("Γάλα αμυγδάλου") in record["ingredientLabels"]
    metadata = record["sourcePayload"]["htmlMetadata"]
    assert metadata["ingredientSearchKeywords"] == ["Γάλα", "αμυγδάλου"]
    assert record["sourcePayload"]["jsonLd"]["recipeIngredient"] == value["recipeIngredient"]


def test_lucacos_ingredient_dictionary_links_are_scoped_to_recipe_column():
    value = recipe(recipeIngredient=["1 αμύγδαλο"])
    html = '<html lang="el"><aside><a href="/ingredient/9/pork">Χοιρινό</a></aside><script type="application/ld+json">' + json.dumps(value) + '</script><div class="medium-6 columns ingredients"><article><ul><li>1 αμύγδαλο</li></ul></article><section><a href="/ingredient/8/almond">ΑΜΥΓΔΑΛΟ</a></section></div></html>'
    record = normalize_public_recipe_page(html, source_key="lucacos", source_url=LUCACOS_URL)
    assert record["ingredientLabels"] == ["Αμύγδαλα"]
    assert record["sourcePayload"]["htmlMetadata"]["ingredientLabels"] == ["ΑΜΥΓΔΑΛΟ"]


@pytest.mark.parametrize(("text", "quantity", "unit", "title"), [
    ("200 ml. γάλα", "200", "ml.", "γάλα"),
    ("2 κουτ. Σούπας ελαιόλαδο", "2", "κουτ. Σούπας", "ελαιόλαδο"),
    (r"1\2 κ.γ. βανίλια", r"1\2", "κ.γ.", "βανίλια"),
    ("1 κουταλιά της σούπας λευκό ξύδι", "1", "κουταλιά της σούπας", "λευκό ξύδι"),
    ("2 φλ. τσ. ρύζι basmati", "2", "φλ. τσ.", "ρύζι basmati"),
    ("1 φλιτζάνι νερό", "1", "φλιτζάνι", "νερό"),
    ("1 γρεναδίνη", "1", "", "γρεναδίνη"),
])
def test_observed_quantity_and_compound_units_keep_food_identity(text, quantity, unit, title):
    from tools.recipe_importer.public_recipe_schema import ingredient
    item = ingredient(text)
    assert (item["quantity"], item["unit"], item["title"]) == (quantity, unit, title)


@pytest.mark.parametrize("missing_media", ["", " ", "#video", None, {"url": ""}])
def test_absent_media_cannot_resolve_to_current_recipe_page(missing_media):
    record = normalize(recipe(image=missing_media, video=missing_media))
    assert record["imageUrl"] == ""
    assert record["imageUrls"] == []
    assert record["videoUrls"] == []


def test_lucacos_bold_sections_order_links_and_tips_are_preserved():
    value = recipe(recipeIngredient=["1 πατάτα"], video="", recipeYield="ΓΙΑ 4 ΜΕΡΙΔΕΣ", cookTime="PT1.75H", totalTime="")
    article = """<div class="medium-6 columns ingredients"><article>
<p><strong>Για τη βάση</strong></p><ul><li>1 πατάτα</li><li>20 ml. <a href="/recipe/vasikes/125/test-sauce">σάλτσα</a></li></ul>
<p><strong>Για το γαρνίρισμα</strong></p><ul><li>1 φύλλο βασιλικού</li></ul></article>
<section class="related"><p>Άσχετη συνταγή</p></section></div>
<div class="medium-6 columns directions"><article>
<h4>VIDEO DIRECTIONS</h4><div><iframe src="https://www.youtube.com/embed/example"></iframe></div>
<p><strong>Για τη βάση</strong></p><p>Πρώτο βήμα.</p><p>Δεύτερο <strong>σημαντικό</strong> βήμα.</p>
<p><strong>Για το γαρνίρισμα</strong></p><p>Τελικό βήμα.</p><p><strong>Tip</strong><strong>:</strong></p><p>Μία συμβουλή.</p>
</article><section class="related"><p>Άσχετη οδηγία.</p></section></div>"""
    html = '<html lang="el"><script type="application/ld+json">' + json.dumps(value) + '</script>' + article + '</html>'
    record = normalize_public_recipe_page(html, source_key="lucacos", source_url=LUCACOS_URL)
    assert [section["title"] for section in record["ingredientSections"]] == ["Για τη βάση", "Για το γαρνίρισμα"]
    assert [len(section["ingredients"]) for section in record["ingredientSections"]] == [2, 1]
    assert record["ingredientSections"][0]["ingredients"][1]["externalLink"] == "https://www.yiannislucacos.gr/recipe/vasikes/125/test-sauce"
    assert record["methodSections"] == [
        {"title": "Για τη βάση", "steps": ["Πρώτο βήμα.", "Δεύτερο σημαντικό βήμα."]},
        {"title": "Για το γαρνίρισμα", "steps": ["Τελικό βήμα."]},
    ]
    assert record["stepCount"] == 3
    assert record["tips"] == ["Μία συμβουλή."]
    assert record["videoUrls"] == []
    assert record["servings"] == "ΓΙΑ 4 ΜΕΡΙΔΕΣ"
    assert record["totalMinutes"] == 105
    assert record["sourcePayload"]["jsonLd"] == value

def test_explicit_unbounded_wait_cannot_become_a_quick_recipe():
    record = normalize(recipe(prepTime="PT25M", totalTime=""), {"totalDurationUncertain": True, "cookingTimeEvidence": "10 λεπτά + αναμονή"})
    assert record["prepMinutes"] == 25
    assert record["totalMinutes"] == 0
    assert record["quickRecipe"] is False
    assert record["sourcePayload"]["htmlMetadata"]["cookingTimeEvidence"] == "10 λεπτά + αναμονή"

def test_explicitly_incomplete_published_tsoulis_recipe_is_accounted_separately():
    from tools.recipe_importer.public_sitemap_source import normalize_source
    from tools.recipe_importer.public_recipe_crawler import RecipeExcluded
    raw = {"id": 999, "title": "Δοκιμή", "slug": "test", "is_published": 1,
           "instructions": [{"id": 1, "description": "Μία οδηγία."}],
           "ingredient": None, "ingredients": [], "ingredients_formated": []}
    with pytest.raises(RecipeExcluded, match="publisher_incomplete_recipe: native_id=999"):
        normalize_source(nuxt_html({"data": {"recipe": {"data": raw}}}),
                         source_key="tsoulis", source_url="https://www.giorgostsoulis.com/syntages/member-recipes/test")

def test_lucacos_explicit_empty_ingredient_article_is_reported_not_inferred():
    from tools.recipe_importer.public_recipe_crawler import RecipeExcluded
    value = recipe(recipeIngredient="")
    html = '<html lang="el"><script type="application/ld+json">' + json.dumps(value) + '</script><div class="medium-6 columns ingredients"><article> </article><section class="related"><a href="/ingredient/5/butter">Βούτυρο</a></section></div></html>'
    with pytest.raises(RecipeExcluded, match="publisher_incomplete_recipe: native_id=123; empty ingredient column"):
        normalize_public_recipe_page(html, source_key="lucacos", source_url=LUCACOS_URL)


def test_lucacos_missing_ingredient_dom_without_explicit_empty_column_still_fails():
    value = recipe(recipeIngredient="")
    html = '<html lang="el"><script type="application/ld+json">' + json.dumps(value) + '</script></html>'
    with pytest.raises(FullSchemaError, match="Recipe must contain ingredients"):
        normalize_public_recipe_page(html, source_key="lucacos", source_url=LUCACOS_URL)

@pytest.mark.parametrize(("source_label", "expected"), [
    ("Σούπα κρέας", "meat"), ("Οσομπούκο", "meat"),
    ("Κέικ λεμονιού", "dessert"), ("Νηστίσιμο κέικ", "dessert"),
    ("Ελληνικά & Παραδοσιακά Γλυκά", "dessert"),
    ("Ρεβύθια", "legumes"), ("Καλαμάρι", "fish"), ("Corn Dogs", "street_food"),
])
def test_reviewed_complete_publisher_labels_fill_unknown_categories(source_label, expected):
    value = recipe(name="Δοκιμή", recipeCategory=[], keywords=[source_label])
    record = normalize(value)
    assert record["category"] == expected
    assert record["tags"] == [source_label]
    assert record["sourcePayload"]["jsonLd"] == value


@pytest.mark.parametrize(("known_category", "new_label", "expected"), [
    ("Ζυμαρικά", "Ρεβύθια", "pasta_rice"),
    ("Ποτό", "Νηστίσιμα Γλυκά", "drinks"),
    ("ΣΑΛΑΤΕΣ", "Καλαμάρι", "vegetables"),
    ("Ψωμιά & Ζύμες", "Κέικ λεμονιού", "other"),
    ("Σάλτσες", "Οσομπούκο", "other"),
])
def test_new_label_fallback_preserves_known_dish_and_terminal_categories(known_category, new_label, expected):
    record = normalize(recipe(recipeCategory=known_category, keywords=[new_label]))
    assert record["category"] == expected


def test_ambiguous_formats_flavours_and_title_only_evidence_stay_unknown():
    ambiguous = ["Σνιτσελάκια", "Σαλάτα", "Muffins", "Καταΐφι", "Γεμιστά",
                 "Πατάτα", "Τυρόπιτα", "ΖΑΧΑΡΟΠΛΑΣΤΙΚΗ", "Γλυκές Ζύμες-Τάρτες",
                 "Σοκολατένιο", "Κέικ λεμονιού με ελιές", "Χωρίς Οσομπούκο"]
    for label in ambiguous:
        assert normalize(recipe(recipeCategory=[], keywords=[label]))["category"] == "other"
    record = normalize(recipe(name="Κέικ λεμονιού", recipeCategory=[], keywords=[],
                              recipeIngredient=["1 Ρεβύθια", "1 Καλαμάρι"]))
    assert record["category"] == "other"

def test_lucacos_inline_group_heading_and_tip_paragraphs_keep_structure():
    value = recipe(recipeIngredient=["1 γάλα"], recipeInstructions="Δοκιμή")
    body = '<div class="medium-6 columns directions"><article><p><strong>Για το ρόφημα</strong>Πρώτο <em>βήμα</em>.</p><p>Δεύτερο βήμα.</p><p><strong>Tip:</strong> Μία συμβουλή.</p><p>Tip: Δεύτερη συμβουλή.</p></article></div>'
    html = '<html lang="el"><script type="application/ld+json">' + json.dumps(value) + '</script>' + body + '</html>'
    record = normalize_public_recipe_page(html, source_key="lucacos", source_url=LUCACOS_URL)
    assert record["methodSections"] == [{"title": "Για το ρόφημα", "steps": ["Πρώτο βήμα.", "Δεύτερο βήμα."]}]
    assert record["stepCount"] == 2
    assert record["tips"] == ["Μία συμβουλή.", "Δεύτερη συμβουλή."]
    assert record["sourcePayload"]["jsonLd"] == value


def test_lucacos_paragraph_ingredients_nested_headings_links_and_footnotes():
    # Actual publisher structures observed on 8213, 7971, 7986 and 7990.
    value = recipe(recipeIngredient=["1 πατάτα"])
    body = """<div class="medium-6 columns ingredients"><article>
<p><span><strong>1. Πρώτη παρασκευή</strong></span></p>
<p><span>250 γρ. νερό<br>100 γρ. <a href="/recipe/vasikes/125/test">βούτυρο</a></span></p>
<p>αλάτι</p><p><span>Για τη δεύτερη παρασκευή</span></p>
<ul><li>Για το μείγμα</li></ul><p>2 αυγά</p>
<p><em>* Προσθέτουμε νερό αν χρειάζεται.</em></p>
<p><strong>Toppings</strong></p><ul><li>κανέλα</li></ul>
<p><strong>Για τη σύνθεση</strong></p>
</article></div>"""
    html = '<html lang="el"><script type="application/ld+json">' + json.dumps(value) + '</script>' + body + '</html>'
    record = normalize_public_recipe_page(html, source_key="lucacos", source_url=LUCACOS_URL)
    sections = record["ingredientSections"]
    assert [section["title"] for section in sections] == [
        "1. Πρώτη παρασκευή", "Για τη δεύτερη παρασκευή", "Για το μείγμα", "Toppings", "Για τη σύνθεση"]
    assert [len(section["ingredients"]) for section in sections] == [3, 0, 1, 1, 0]
    assert [item["title"] for item in sections[0]["ingredients"]] == ["νερό", "βούτυρο", "αλάτι"]
    assert sections[0]["ingredients"][1]["externalLink"] == "https://www.yiannislucacos.gr/recipe/vasikes/125/test"
    assert sections[0]["ingredients"][0]["externalLink"] == ""
    assert record["tips"] == ["* Προσθέτουμε νερό αν χρειάζεται."]
    assert record["sourcePayload"]["jsonLd"] == value


def test_lucacos_nested_method_heading_resets_tips_and_unbolded_tip_label():
    value = recipe(recipeIngredient=["1 πατάτα"])
    body = """<div class="medium-6 columns directions"><article>
<p><strong>Για το πιάτο</strong></p><p>Πρώτο βήμα.</p>
<p>TIP:</p><p>Μία συμβουλή.</p>
<p><span><strong>Για τις πατάτες</strong></span></p><p>Ψήνουμε τις πατάτες.</p>
</article></div>"""
    html = '<html lang="el"><script type="application/ld+json">' + json.dumps(value) + '</script>' + body + '</html>'
    record = normalize_public_recipe_page(html, source_key="lucacos", source_url=LUCACOS_URL)
    assert record["methodSections"] == [
        {"title": "Για το πιάτο", "steps": ["Πρώτο βήμα."]},
        {"title": "Για τις πατάτες", "steps": ["Ψήνουμε τις πατάτες."]}]
    assert record["tips"] == ["Μία συμβουλή."]


def test_lucacos_dot_delimited_tip_and_consecutive_parent_heading_are_preserved():
    # Publisher 7693 styles the entire "Tip. ..." paragraph in bold; 8039
    # includes a parent heading immediately before the actual preparation.
    value = recipe(recipeIngredient=["1 πατάτα"])
    body = """<div class="medium-6 columns directions"><article>
<p><strong>Για το πιάτο</strong></p><p><strong>Για τη βάση</strong></p><p>Πρώτο βήμα.</p>
<p><span><strong>Tip. Μία συμβουλή.</strong></span></p>
<p>*Tip : Δεύτερη συμβουλή.</p></article></div>"""
    html = '<html lang="el"><script type="application/ld+json">' + json.dumps(value) + '</script>' + body + '</html>'
    record = normalize_public_recipe_page(html, source_key="lucacos", source_url=LUCACOS_URL)
    assert record["methodSections"] == [
        {"title": "Για το πιάτο", "steps": []},
        {"title": "Για τη βάση", "steps": ["Πρώτο βήμα."]}]
    assert record["tips"] == ["Μία συμβουλή.", "Δεύτερη συμβουλή."]
