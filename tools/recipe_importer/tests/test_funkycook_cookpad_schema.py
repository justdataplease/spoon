import json

import pytest

from tools.recipe_importer.funkycook_schema import normalize_funkycook_page
from tools.recipe_importer.cookpad_schema import normalize_cookpad_page, parse_cookpad_minutes
from tools.recipe_importer.ingredient_taxonomy import canonical_ingredient_label


def funky_page(content):
    article = {"@context": "https://schema.org", "@type": "Article", "headline": "Μακαρόνια", "articleSection": ["Ζυμαρικά"]}
    return '<html lang="el"><body class="postid-123"><script type="application/ld+json">' + json.dumps(article) + '</script><nav>Άσχετα υλικά</nav><div class="entry-content">' + content + '</div></body></html>'


def test_funkycook_legacy_sections_and_explicit_breaks():
    result = normalize_funkycook_page(funky_page('''
    <p>Χρόνος Προετοιμασίας: 10′<br>Χρόνος Εκτέλεσης: 12 λεπτά</p>
    <p><b>Υλικά</b><br><b>Για τη σάλτσα</b><br>2 ντομάτες<br>1 κ.σ. ελαιόλαδο</p>
    <h3>Εκτέλεση συνταγής</h3><p>Κόβουμε τις <a href="/test/">ντομάτες</a>.</p>
    <p>Μαγειρεύουμε και σερβίρουμε.</p><h3>Σημειώσεις για τη συνταγή</h3><p>Προσθέτουμε βασιλικό.</p>
    '''), source_url="https://funkycook.gr/makaronia/")
    assert result["id"] == "funkycook_123"
    assert len(result["ingredientSections"][0]["ingredients"]) == 2
    assert result["ingredientSections"][0]["title"] == "Για τη σάλτσα"
    assert result["methodSections"][0]["steps"] == ["Κόβουμε τις ντομάτες.", "Μαγειρεύουμε και σερβίρουμε."]
    assert result["totalMinutes"] == 22
    assert result["tips"] == ["Προσθέτουμε βασιλικό."]


def test_funkycook_hrecipe_keeps_recipe_times_and_excludes_footer():
    result = normalize_funkycook_page(funky_page('''<div class="hrecipe">
    <span class="preptime"><span class="value-title" title="PT10M"></span>10 mins</span>
    <span class="cooktime"><span class="value-title" title="PT20M"></span>20 mins</span>
    <h3>Υλικά</h3><ul><li class="ingredient">200 γρ. μακαρόνια</li></ul>
    <h3>Οδηγίες</h3><ol class="instructions"><li>Βράζουμε τα μακαρόνια.</li></ol>
    <ul class="recipe-taxes"><li>ΤΥΠΟΣ: Κυρίως πιάτο</li></ul></div>'''), source_url="https://funkycook.gr/makaronia/")
    assert result["totalMinutes"] == 30
    assert result["methodSections"][0]["steps"] == ["Βράζουμε τα μακαρόνια."]


def test_funkycook_non_recipe_article_is_explicit_exclusion():
    assert normalize_funkycook_page(funky_page("<p>Παρουσίαση βιβλίου.</p>"), source_url="https://funkycook.gr/book/") is None


def test_cookpad_photo_less_public_recipe_keeps_scoped_content_only():
    html = '''<html lang="el"><meta property="og:title" content="Σαλάτα Συνταγή από τον/την Μαρία">
    <meta property="og:image" content="https://global-web-assets.cpcdn.com/logo.png">
    <h1>Σαλάτα</h1><section id="ingredients"><div id="serving_recipe_42">2 άτομα</div>
    <li id="ingredient_1">2 <a href="/gr/anazitisi/tomato">ντομάτες</a></li></section>
    <section id="steps"><li id="step_1"><div>1</div><p>Κόβουμε τις ντομάτες.</p></li></section>
    <aside>Άσχετη συνταγή με γαρίδες</aside></html>'''
    result = normalize_cookpad_page(html, source_url="https://cookpad.com/gr/sintages/42")
    assert result["authorName"] == "Μαρία"
    assert result["imageUrl"] == ""
    assert result["servings"] == "2 άτομα"
    assert result["methodSections"][0]["steps"] == ["Κόβουμε τις ντομάτες."]
    assert result["sourcePayload"]["htmlMetadata"]["ingredientSearchKeywords"] == ["ντομάτες"]
    assert result["ingredientLabels"] == [canonical_ingredient_label("ντομάτες")]


def test_funkycook_modern_recipe_preserves_matching_groups_amounts_and_notes():
    recipe = {"@context": "https://schema.org", "@type": "Recipe", "name": "Κέικ", "recipeIngredient": ["220 γρ. βούτυρο", "100 γρ. τυρί κρέμα"], "recipeInstructions": [{"@type": "HowToStep", "text": "Ψήνουμε το κέικ."}]}
    html = '<html lang="el"><body class="postid-10178"><script type="application/ld+json">' + json.dumps(recipe) + '</script>' + '''
    <div class="wprm-recipe-container"><h2 class="wprm-recipe-name">Κέικ</h2>
    <div class="wprm-recipe-ingredient-group"><h5 class="wprm-recipe-ingredient-group-name">Για το κέικ</h5>
    <li class="wprm-recipe-ingredient"><span class="wprm-recipe-ingredient-amount">220</span> <span class="wprm-recipe-ingredient-unit">γρ.</span> <span class="wprm-recipe-ingredient-name">βούτυρο</span></li></div>
    <div class="wprm-recipe-ingredient-group"><h5 class="wprm-recipe-ingredient-group-name">Για το γλάσο</h5>
    <li class="wprm-recipe-ingredient"><span class="wprm-recipe-ingredient-amount">100</span> <span class="wprm-recipe-ingredient-unit">γρ.</span> <span class="wprm-recipe-ingredient-name">τυρί κρέμα</span>, <span class="wprm-recipe-ingredient-notes">σε θερμοκρασία περιβάλλοντος</span></li></div>
    <div class="wprm-recipe-notes">- Αφήνουμε να κρυώσει.<br>- Γλασάρουμε μετά.</div></div>
    <aside><div class="wprm-recipe-container"><h2 class="wprm-recipe-name">Άσχετη συνταγή</h2><li class="wprm-recipe-ingredient">10 γαρίδες</li></div></aside></body></html>'''
    result = normalize_funkycook_page(html, source_url="https://funkycook.gr/gingerbread-cake/")
    assert [section["title"] for section in result["ingredientSections"]] == ["Για το κέικ", "Για το γλάσο"]
    item = result["ingredientSections"][1]["ingredients"][0]
    assert (item["quantity"], item["unit"], item["title"], item["info"]) == ("100", "γρ.", "τυρί κρέμα", "σε θερμοκρασία περιβάλλοντος")
    assert result["tips"] == ["Αφήνουμε να κρυώσει.", "Γλασάρουμε μετά."]


@pytest.mark.parametrize("raw,minutes", [("10 λεπτά", 10), ("5 λεπτά", 5), ("1 ώρα και 30 λεπτά", 90), ("15-20 λεπτά", 20), ("20'", 20), ("30 λεπτα ψησιμο", 30), ("Ελάχιστος", 0), ("1/2 ώρα", 0), ("10 λεπτά + αναμονή", 0), ("10' + 25' ψήσιμο + αναμονή", 0), ("Προετοιμασία 10’ – Μαγείρεμα 20’", 0), ("01:30 hr", 0)])
def test_cookpad_time_accepts_complete_expressions_and_preserves_ambiguity(raw, minutes):
    assert parse_cookpad_minutes(raw) == minutes


@pytest.mark.parametrize("raw,minutes", [("10 λεπτά", 10), ("Ελάχιστος", 0), (None, 0)])
def test_cookpad_scopes_public_time_to_current_recipe_and_keeps_evidence(raw, minutes):
    recipe = {"@context": "https://schema.org", "@type": "Recipe", "name": "Ρύζι", "recipeIngredient": ["200 γρ. ρύζι"], "recipeInstructions": [{"@type": "HowToStep", "text": "Βράζουμε το ρύζι."}], "prepTime": "PT25M"}
    html = '<html lang="el"><script type="application/ld+json">' + json.dumps(recipe) + '</script><div id="cooking_time_recipe_999">5 λεπτά</div>'
    if raw is not None:
        html += '<div id="cooking_time_recipe_26532174">' + raw + '</div>'
    html += '</html>'
    result = normalize_cookpad_page(html, source_url="https://cookpad.com/gr/sintages/26532174")
    assert result["cookMinutes"] == minutes
    assert result["prepMinutes"] == 25
    assert result["totalMinutes"] == (0 if raw and not minutes else 25 + minutes)
    evidence = result["sourcePayload"]["htmlMetadata"].get("cookingTimeEvidence")
    if raw is None:
        assert evidence is None
    else:
        assert evidence["text"] == raw
        assert evidence["elementId"] == "cooking_time_recipe_26532174"


@pytest.mark.parametrize("has_jsonld", [True, False])
def test_cookpad_explicit_dressing_heading_is_preserved_as_group(has_jsonld):
    recipe = {"@context": "https://schema.org", "@type": "Recipe", "name": "Σαλάτα", "recipeIngredient": ["200 γρ μαρούλι", "2 κ.σ. ελαιόλαδο"], "recipeInstructions": [{"@type": "HowToStep", "text": "Κόβουμε και ανακατεύουμε."}]}
    html = '<html lang="el"><h1>Σαλάτα</h1>'
    if has_jsonld:
        html += '<script type="application/ld+json">' + json.dumps(recipe) + '</script>'
    html += '''<section id="ingredients"><ul><li id="ingredient_1">200 γρ μαρούλι</li>
    <li id="ingredient_2" class="py-sm font-semibold border-0 mt-rg mb-sm">Για το dressing</li>
    <li id="ingredient_3">2 κ.σ. ελαιόλαδο</li></ul></section>
    <section id="steps"><li id="step_1"><p>Κόβουμε και ανακατεύουμε.</p></li></section></html>'''
    result = normalize_cookpad_page(html, source_url="https://cookpad.com/gr/sintages/26532174")
    assert [group["title"] for group in result["ingredientSections"]] == ["", "Για το dressing"]
    assert sum(len(group["ingredients"]) for group in result["ingredientSections"]) == 2
    assert "Για το dressing" not in result["sourcePayload"]["jsonLd"]["recipeIngredient"]


def test_cookpad_quantified_ingredient_with_publisher_heading_style_is_retained():
    recipe = {"@context": "https://schema.org", "@type": "Recipe", "name": "Κολοκυθάκια", "recipeIngredient": ["2 κολοκυθάκια", "1 ποτήρι γραβιέρα"], "recipeInstructions": [{"@type": "HowToStep", "text": "Ψήνουμε τα κολοκυθάκια."}]}
    html = '<html lang="el"><script type="application/ld+json">' + json.dumps(recipe) + '</script>' + '''
    <section id="ingredients"><li id="ingredient_1">2 κολοκυθάκια</li><li id="ingredient_2" class="font-semibold border-0">1 ποτήρι γραβιέρα</li></section></html>'''
    result = normalize_cookpad_page(html, source_url="https://cookpad.com/gr/sintages/26366299")
    assert sum(len(group["ingredients"]) for group in result["ingredientSections"]) == 2
    assert result["ingredientSections"][0]["ingredients"][1]["quantity"] == "1"
    assert "γραβιέρα" in result["ingredientSections"][0]["ingredients"][1]["title"]


def test_cookpad_preserves_bold_ingredients_omitted_by_jsonld_and_ordinal_groups():
    recipe = {"@context": "https://schema.org", "@type": "Recipe", "name": "Κρέμα", "recipeIngredient": ["200 ml γάλα"], "recipeInstructions": [{"@type": "HowToStep", "text": "Ανακατεύουμε."}]}
    html = '<html lang="el"><script type="application/ld+json">' + json.dumps(recipe) + '</script>' + '''
    <section id="ingredients"><li id="ingredient_1">200 ml γάλα</li>
    <li id="ingredient_2" class="font-semibold border-0">2η κρέμα</li>
    <li id="ingredient_3" class="font-semibold border-0">70 γρ ζάχαρη</li>
    <li id="ingredient_4" class="font-semibold border-0">Αλάτι</li>
    <li id="ingredient_5" class="font-semibold border-0">Άλλο γαρνίρισμα</li></section></html>'''
    result = normalize_cookpad_page(html, source_url="https://cookpad.com/gr/sintages/25394391")
    assert [group["title"] for group in result["ingredientSections"]] == ["", "2η κρέμα", "Άλλο γαρνίρισμα"]
    assert result["preparationCount"] == 1
    assert sum(len(group["ingredients"]) for group in result["ingredientSections"]) == 3
    assert [item["title"] for item in result["ingredientSections"][1]["ingredients"]] == ["ζάχαρη", "Αλάτι"]
    assert result["sourcePayload"]["jsonLd"]["recipeIngredient"] == ["200 ml γάλα"]
    evidence = result["sourcePayload"]["htmlMetadata"]["ingredientReconciliation"]
    assert evidence["jsonLdIngredientCount"] == 1
    assert evidence["domIngredientCount"] == 3
    assert evidence["additionalDomIngredientLines"] == ["70 γρ ζάχαρη", "Αλάτι"]


@pytest.mark.parametrize("attached_path", ["/gr/sintages/16586551", "/gr/simvoules/6460-cooking-tip"])
def test_cookpad_attached_recipe_card_is_link_not_ingredient_text(attached_path):
    recipe = {"@context": "https://schema.org", "@type": "Recipe", "name": "Μακαρόνια", "recipeIngredient": ["4 αυγά τηγανητά"], "recipeInstructions": [{"@type": "HowToStep", "text": "Σερβίρουμε με αυγά."}]}
    html = '<html lang="el"><script type="application/ld+json">' + json.dumps(recipe) + '</script>' + '''
    <section id="ingredients"><li id="ingredient_1"><bdi>4</bdi> <span>αυγά τηγανητά</span>
    <div><svg class="mise-icon mise-icon-attachment"></svg><a href="/gr/sintages/16586551?via=see_link">Αυγά τηγανητά (μάτια)</a></div></li></section></html>'''
    html = html.replace("/gr/sintages/16586551", attached_path)
    result = normalize_cookpad_page(html, source_url="https://cookpad.com/gr/sintages/13074468")
    item = result["ingredientSections"][0]["ingredients"][0]
    assert item["title"] == "αυγά τηγανητά"
    assert item["quantity"] == "4"
    assert item["externalLink"] == "https://cookpad.com" + attached_path
    assert result["sourcePayload"]["htmlMetadata"]["ingredientReferences"] == [{"ingredientElementId": "ingredient_1", "url": "https://cookpad.com" + attached_path, "title": "Αυγά τηγανητά (μάτια)"}]


def test_cookpad_preserves_publisher_dom_and_jsonld_wording_variants():
    recipe = {"@context": "https://schema.org", "@type": "Recipe", "name": "Πιπεριές", "recipeIngredient": ["(προαιρετικά) Ελαιόλαδο", "1 κιλό τυρί Gouda τριμμένο"], "recipeInstructions": [{"@type": "HowToStep", "text": "Ανακατεύουμε."}]}
    html = '<html lang="el"><script type="application/ld+json">' + json.dumps(recipe) + '</script><section id="ingredients"><li id="ingredient_1">Ελαιόλαδο (προαιρετικά)</li><li id="ingredient_2">1 κιλό τυρί Gooda τριμμένο</li></section></html>'
    result = normalize_cookpad_page(html, source_url="https://cookpad.com/gr/sintages/26489072")
    assert result["ingredientSections"][0]["ingredients"][0]["title"] == "Ελαιόλαδο (προαιρετικά)"
    evidence = result["sourcePayload"]["htmlMetadata"]["ingredientReconciliation"]
    assert evidence["jsonLdIngredientCount"] == evidence["domIngredientCount"] == 2
    assert evidence["jsonLdTextDifferences"] == recipe["recipeIngredient"]
    assert result["sourcePayload"]["jsonLd"]["recipeIngredient"] == recipe["recipeIngredient"]
