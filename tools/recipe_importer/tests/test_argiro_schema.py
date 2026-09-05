"""Synthetic-only Argiro parsing tests; no publisher recipe text is checked in."""

import pytest

from tools.recipe_importer.argiro_schema import (
    _RecipeDomParser,
    _align_method_groups,
    _extract_method_details,
    _filter_method_groups,
    _instructions,
    _leaf_texts,
    normalize_argiro_page,
    parse_iso8601_minutes,
)
from tools.recipe_importer.full_schema import FullSchemaError, ensure_full_record
from tools.recipe_importer.import_catalog import CatalogError, validate_record


SYNTHETIC_PAGE = """
<!doctype html><html lang="el"><head>
<link rel="shortlink" href="https://www.argiro.gr/?p=17265">
<script type="application/ld+json">
{"@context":"https://schema.org","@graph":[{"@type":"Recipe",
 "name":"Συνθετική φασολάδα","description":"Μια δοκιμαστική περιγραφή.",
 "author":{"@type":"Person","name":"Δοκιμαστική συγγραφέας"},
 "datePublished":"2026-01-02T10:00:00+02:00",
 "dateModified":"2026-01-03T11:00:00+02:00",
 "image":["https://www.argiro.gr/wp-content/uploads/synthetic.jpg"],
 "prepTime":"PT15M","cookTime":"PT1H","totalTime":"PT1H15M",
 "recipeYield":["4 μερίδες"],"recipeCategory":["Όσπρια"],
 "keywords":["κατσαρόλα"],
 "recipeIngredient":["1 συνθετικό υλικό","2 φλιτζάνια νερό"],
 "recipeInstructions":[{"@type":"HowToSection","name":"Μαγείρεμα",
   "itemListElement":[{"@type":"HowToStep","text":"Ανακατεύουμε τα υλικά."},
                      {"@type":"HowToStep","text":"Μαγειρεύουμε."}]}],
 "video":{"@type":"VideoObject","embedUrl":"https://www.youtube.com/embed/synthetic"}
}]}
</script>
<script>var AM = {recipe: {id: 17265, stats: {rating: 4.50, total_votes: 12, rating_percentage: 90}}};</script>
</head><body>
<div class="difficulty_level">Μέτρια</div>
<div class="article__tags"><span class="tag_item"><a href="https://www.argiro.gr/recipe-category/ospria/">Όσπρια</a></span></div>
<aside class="single_recipe__left_column">
  <div class="ingredients">
    <section class="ingredients__container">
      <h3 class="ingredients__title">Για τη βάση</h3>
      <ul>
        <li><span class="quantity">1</span><a class="ingredient-label" href="https://www.argiro.gr/basic-ingredient/synthetic/">συνθετικό υλικό</a></li>
        <li><span class="quantity">2 φλιτζάνια</span><span class="ingredient-label">νερό</span></li>
      </ul>
    </section>
  </div>
  <div class="ingredients tab"><ul><li>διπλότυπο που αγνοείται</li></ul></div>
</aside>
<section class="single_recipe__method_steps">
  <h3>Μαγείρεμα</h3>
  <ol><li>Ανακατεύουμε τα υλικά.</li><li>Μαγειρεύουμε.</li></ol>
</section>
<div class="equipment__item">Κατσαρόλα</div>
<section class="single_recipe__tips"><p>Συνθετική συμβουλή.</p></section>
<iframe src="https://www.youtube.com/embed/second-synthetic"></iframe>
</body></html>
"""


AUDITED_INSTRUCTION_ARTIFACTS = [
    ("16147", "RELATED ARTICLE"),
    ("16274", "https://www.argiro.gr/recipe/kineziko-tiganito-ruzi-sto-gouok/"),
    ("16315", "https://www.argiro.gr/recipe/fakes-pikantikes-me-xoriatiko-loukaniko/"),
    ("16315", "Θέλετε περισσότερες νόστιμες συνταγές με φακές; Βρείτε τες όλες εδώ!"),
    ("16586", "[adrotate banner=\u201d29\u2033]"),
    ("16601", "[adrotate banner=\u201d29\u2033]"),
    ("16601", "Δείτε εδώ και φτιάξτε τα ωραιότερα παγωτά για τους αγαπημένους σας."),
    ("16620", "[adrotate banner=\u201d32\u2033]"),
    (
        "16620",
        "Αγαπάτε τις φράουλες; Δείτε περισσότερες εύκολες και φαντασικές "
        "φραουλένιες συνταγές εδώ!",
    ),
    ("16642", "[adrotate banner=\u201d32\u2033]"),
    ("16726", "[adrotate banner=\u201d72\u2033]"),
    ("16759", "[adrotate banner=\u201d29\u2033]"),
    (
        "16759",
        "Θέλετε περισσότερες συνταγές για μοναδικά νηστίσιμα γλυκά; "
        "Βρείτε τες όλες εδώ!",
    ),
    ("17014", "https://www.argiro.gr/recipe/banoffee-me-mpiskoto/"),
    ("17030", "[adrotate banner=\u201d72\u2033]"),
    ("17032", "[adrotate banner=\u201d72\u2033]"),
    ("17308", "[adrotate banner=\u201d71\u2033]"),
    ("17314", "https://www.argiro.gr/recipe/keik-mpananas-me-mpiskoto/"),
    ("17522", "[adrotate banner=\u201d33\u2033]"),
    ("17717", "https://www.argiro.gr/recipe/galatopita-me-krema-sokolata-vanilia-diorofi/"),
    ("20163", "[adrotate banner=\u201d29\u2033]"),
    ("20193", "[adrotate banner=\u201d27]"),
    ("20193", "[adrotate banner=\u201d72\u2033]"),
    ("20222", "https://www.argiro.gr/recipe/kolluba/"),
    ("20264", "https://www.argiro.gr/recipe/cupcakes-sokolata/"),
    ("20311", "ΜΑΓΕΙΡΕΨΕ ΚΑΙ\nΜανιτάρια στο φούρνο"),
    ("20591", "ΜΑΓΕΙΡΕΨΕ ΚΑΙ\nΣεβίτσε (Ceviche)"),
    ("20597", "ΜΑΓΕΙΡΕΨΕ ΚΑΙ\nZombie Cocktail"),
    ("20690", "[adrotate banner=\u201d33\u2033]"),
    (
        "20708",
        "Τα Νούντλς (Noodles) της Kόμπρας του Άνταμ Κοντοβά, "
        "εύκολα & πεντανόστιμα!",
    ),
    ("20708", "http://www.argiro.gr/recipe/noodles-kotopoulo-kai-glykoksini-saltsa/"),
]


AUDITED_INSTRUCTION_ARTIFACTS.extend([
    (
        '15401',
        'Σπιτικό fast food που θα σας συναρπάσει και θα σας γλιτώσει από περιττά έξοδα!',
    ),
    (
        '20035',
        'Διαβάστε και όλα τα μυστικά μου, για να λιώσετε σωστά τη σοκολάτα.',
    ),
    ('17166', '[recipe_grid recipe_ids=”14154″]'),
    ('17673', '[recipe_grid recipe_ids=”3863,4598,2569″]'),
    ('20854', '[recipe_grid recipe_ids=”4264,1479,11893″]'),
    ('20946', '[recipe_grid recipe_ids=”3743,3789,3763,14154″]'),
])


def test_audited_mixed_instruction_keeps_only_the_legitimate_step():
    legitimate = 'Μπορείτε να φτιάξετε τη συνταγή με μαρόν γλασέ.'
    cta = 'Διαβάστε και όλα τα μυστικά μου, για να λιώσετε σωστά τη σοκολάτα.'
    combined = f'{legitimate}\n{cta}'
    _jsonld_sections, jsonld_steps = _instructions(
        [{'@type': 'HowToStep', 'text': combined}],
        provider_recipe_id='20035',
    )
    groups = _filter_method_groups(
        [{
            'title': '',
            'steps': [legitimate, cta],
            'sourceSteps': [legitimate, cta],
            'isTip': False,
        }],
        provider_recipe_id='20035',
    )
    sections, tips = _align_method_groups(groups, jsonld_steps)
    assert jsonld_steps == [legitimate]
    assert sections == [{'title': '', 'steps': [legitimate]}]
    assert tips == []


def test_method_alignment_accepts_only_exact_concatenated_adjacent_html_steps():
    sections, tips = _align_method_groups(
        [{
            "title": "METHOD",
            "steps": ["First action.", "Read the linked technique."],
            "isTip": False,
        }],
        ["First action. Read the linked technique."],
    )
    assert sections == [{
        "title": "METHOD",
        "steps": ["First action. Read the linked technique."],
    }]
    assert tips == []

    with pytest.raises(FullSchemaError, match="anchor not found"):
        _align_method_groups(
            [{
                "title": "METHOD",
                "steps": ["First action.", "Different text."],
                "isTip": False,
            }],
            ["First action. Read the linked technique."],
        )


def test_method_alignment_removes_only_explicit_inline_related_recipe_card():
    source_step = (
        "Ετοιμάζουμε το μείγμα για κρέπες. "
        "ΜΑΓΕΙΡΕΨΕ ΚΑΙ Κρέπες (βασική συνταγή)"
    )
    sections, tips = _align_method_groups(
        [{
            "title": "",
            "steps": ["Ετοιμάζουμε το μείγμα για κρέπες."],
            "sourceSteps": [source_step],
            "isTip": False,
        }],
        [source_step],
    )
    assert sections == [{
        "title": "",
        "steps": ["Ετοιμάζουμε το μείγμα για κρέπες."],
    }]
    assert tips == []

    with pytest.raises(FullSchemaError, match="anchor not found"):
        _align_method_groups(
            [{
                "title": "",
                "steps": ["Ετοιμάζουμε το μείγμα για κρέπες."],
                "isTip": False,
            }],
            ["Ετοιμάζουμε το μείγμα για κρέπες. Διαφορετική οδηγία."],
        )


def test_method_dom_records_full_anchor_only_for_exact_read_also_container():
    parser = _RecipeDomParser()
    parser.feed(
        '<section class="single_recipe__method_steps"><ol>'
        '<li>Πρώτο βήμα.'
        '<div class="read_also__container media_content">'
        '<span class="read_also_item__caption">ΜΑΓΕΙΡΕΨΕ ΚΑΙ</span>'
        '<h2 class="read_also_item__title"><a>Σχετική συνταγή</a></h2>'
        '</div></li>'
        '<li>Δεύτερο βήμα.<span> Κανονικό inline κείμενο.</span></li>'
        '</ol></section>'
    )
    parser.close()
    details = _extract_method_details(parser.root)
    assert details["methodGroups"] == [{
        "title": "",
        "steps": ["Πρώτο βήμα.", "Δεύτερο βήμα. Κανονικό inline κείμενο."],
        "sourceSteps": [
            "Πρώτο βήμα.\nΜΑΓΕΙΡΕΨΕ ΚΑΙ\nΣχετική συνταγή",
            "Δεύτερο βήμα. Κανονικό inline κείμενο.",
        ],
        "isTip": False,
    }]


def test_method_alignment_ignores_only_terminal_escaped_span_artifact():
    sections, tips = _align_method_groups(
        [{
            "title": "METHOD",
            "steps": ["Arrange the fruit closely.</span"],
            "isTip": False,
        }],
        ["Arrange the fruit closely."],
    )
    assert sections == [{
        "title": "METHOD",
        "steps": ["Arrange the fruit closely."],
    }]
    assert tips == []

    with pytest.raises(FullSchemaError, match="anchor not found"):
        _align_method_groups(
            [{
                "title": "METHOD",
                "steps": ["Arrange different fruit.</span"],
                "isTip": False,
            }],
            ["Arrange the fruit closely."],
        )


def test_samali_style_tip_only_dom_recovers_the_unanchored_method_prefix():
    sections, tips = _align_method_groups(
        [{
            "title": "ΜΥΣΤΙΚΑ ΓΙΑ ΤΕΛΕΙΟ ΣΑΜΑΛΙ",
            "steps": ["Τελικό μυστικό."],
            "isTip": True,
        }],
        ["Πρώτο βήμα.", "Δεύτερο βήμα.", "Τελικό μυστικό."],
    )
    assert sections == [{
        "title": "",
        "steps": ["Πρώτο βήμα.", "Δεύτερο βήμα."],
    }]
    assert tips == ["Τελικό μυστικό."]


def test_leaf_text_excludes_nested_recommendations_but_keeps_inline_content():
    parser = _RecipeDomParser()
    parser.feed(
        '<div><p>Keep <span>inline</span> and '
        '<a href="https://www.argiro.gr/help/">linked text</a>.'
        '<h4>Ignore recommendation heading</h4>'
        '<div class="media_content read_also__container">Ignore card</div>'
        '</p></div>'
    )
    parser.close()
    assert _leaf_texts(parser.root, frozenset({"p"})) == [
        "Keep inline and linked text."
    ]


def test_method_details_use_paragraph_steps_when_legacy_group_has_no_list():
    parser = _RecipeDomParser()
    parser.feed(
        '<div class="single_recipe__method_container">'
        '<h3 class="single_recipe__method_steps__title">METHOD</h3>'
        '<div class="single_recipe__method_steps">'
        '<p>First legacy step.</p><p>Second legacy step.</p>'
        '</div></div>'
    )
    parser.close()
    details = _extract_method_details(parser.root)
    assert details["methodSections"] == [{
        "title": "METHOD",
        "steps": ["First legacy step.", "Second legacy step."],
    }]


def test_normalizes_jsonld_and_recipe_scoped_supplements_to_full_schema():
    record = normalize_argiro_page(
        SYNTHETIC_PAGE,
        source_url="https://www.argiro.gr/recipe/synthetiki-fasolada/",
        sitemap_last_modified="2026-01-03",
    )
    ensure_full_record(record)
    assert record["id"] == "argiro_17265"
    assert record["sourceKey"] == "argiro"
    assert record["providerRecipeId"] == "17265"
    assert record["sourceRecipeId"] == 17265
    assert record["category"] == "legumes"
    assert record["rating"] == 9.0
    assert record["ratingCount"] == 12
    assert record["totalMinutes"] == 75
    assert record["stepCount"] == 2
    assert record["ingredientSections"][0]["title"] == "Για τη βάση"
    assert record["ingredientSections"][0]["ingredients"][0]["title"] == "συνθετικό υλικό"
    assert record["ingredientSections"][0]["ingredients"][0]["quantity"] == "1"
    assert record["methodSections"][0]["title"] == "Μαγείρεμα"
    assert record["equipment"] == ["Κατσαρόλα"]
    assert record["tips"] == ["Συνθετική συμβουλή."]
    # Only JSON-LD and recipe-scoped video blocks are trusted. A page-wide
    # iframe can be advertising or unrelated embedded content.
    assert record["videoUrls"] == ["https://www.youtube.com/embed/synthetic"]


def test_full_argiro_import_requires_both_permission_acknowledgements():
    record = normalize_argiro_page(
        SYNTHETIC_PAGE,
        source_url="https://argiro.gr/recipe/synthetiki-fasolada/",
    )
    with pytest.raises(CatalogError, match="--i-have-permission"):
        validate_record(record)
    with pytest.raises(CatalogError, match="--i-have-argiro-permission"):
        validate_record(record, have_permission=True)
    assert validate_record(
        record,
        have_permission=True,
        have_argiro_permission=True,
    )["id"] == "argiro_17265"


def test_nested_taxonomy_and_void_elements_do_not_corrupt_tag_ancestry():
    page = (
        SYNTHETIC_PAGE
        .replace('"recipeCategory":["Όσπρια"]', '"recipeCategory":["Κυρίως"]')
        .replace(
            '<span class="tag_item"><a href="https://www.argiro.gr/recipe-category/ospria/">Όσπρια</a></span>',
            '<img src="synthetic"><meta name="x"><a class="tag_item" href="https://www.argiro.gr/recipe-category/ospria/fakes/">Φακές</a>',
        )
    )
    record = normalize_argiro_page(
        page,
        source_url="https://argiro.gr/recipe/synthetiki-fasolada/",
    )
    assert record["category"] == "legumes"
    assert "Φακές" in record["tags"]


def test_conflicting_shortlink_and_inline_recipe_ids_fail_closed():
    with pytest.raises(Exception, match="IDs disagree"):
        normalize_argiro_page(
            SYNTHETIC_PAGE.replace("id: 17265", "id: 99999"),
            source_url="https://www.argiro.gr/recipe/synthetiki-fasolada/",
        )


def test_modern_and_legacy_ingredient_shapes_preserve_groups_and_quantities():
    modern = SYNTHETIC_PAGE.replace(
        '''<section class="ingredients__container">
      <h3 class="ingredients__title">Για τη βάση</h3>
      <ul>
        <li><span class="quantity">1</span><a class="ingredient-label" href="https://www.argiro.gr/basic-ingredient/synthetic/">συνθετικό υλικό</a></li>
        <li><span class="quantity">2 φλιτζάνια</span><span class="ingredient-label">νερό</span></li>
      </ul>
    </section>''',
        '''<h3 class="ingredients__title">Πρώτη ομάδα</h3>
    <div class="ingredients__container">
      <div class="ingredients__item"><label class="ingredient-label"><span class="ingredients__quantity">1</span><p>συνθετικό υλικό</p></label></div>
    </div>
    <h3 class="ingredients__title">Δεύτερη ομάδα</h3>
    <div class="ingredients__container">
      <div class="ingredients__item"><label class="ingredient-label"><span class="ingredients__quantity">2 φλιτζάνια</span><p>νερό</p></label></div>
    </div>''',
    )
    record = normalize_argiro_page(
        modern,
        source_url="https://www.argiro.gr/recipe/synthetiki-fasolada/",
    )
    assert [(item["title"], len(item["ingredients"])) for item in record["ingredientSections"]] == [
        ("Πρώτη ομάδα", 1), ("Δεύτερη ομάδα", 1),
    ]
    assert record["ingredientSections"][0]["ingredients"][0] == {
        "title": "συνθετικό υλικό", "unit": "", "quantity": "1", "info": "",
        "internalLink": "", "externalLink": "", "ukUnit": "",
        "ukQuantity": "", "usUnit": "", "usQuantity": "",
    }

    legacy = modern.replace(
        '"recipeIngredient":["1 συνθετικό υλικό","2 φλιτζάνια νερό"]',
        '"recipeIngredient":[]',
    ).replace(
        '''<h3 class="ingredients__title">Πρώτη ομάδα</h3>
    <div class="ingredients__container">
      <div class="ingredients__item"><label class="ingredient-label"><span class="ingredients__quantity">1</span><p>συνθετικό υλικό</p></label></div>
    </div>
    <h3 class="ingredients__title">Δεύτερη ομάδα</h3>
    <div class="ingredients__container">
      <div class="ingredients__item"><label class="ingredient-label"><span class="ingredients__quantity">2 φλιτζάνια</span><p>νερό</p></label></div>
    </div>''',
        '''<div class="ingredients__item"><label class="ingredient-label without_quantity"><p>500 γραμ συνθετικό υλικό</p></label></div>
    <h3 class="ingredients__title">Για το σερβίρισμα</h3>
    <div class="ingredients__item"><label class="ingredient-label without_quantity"><p>λίγο νερό</p></label></div>''',
    )
    legacy_record = normalize_argiro_page(
        legacy,
        source_url="https://www.argiro.gr/recipe/synthetiki-fasolada/",
    )
    assert [len(item["ingredients"]) for item in legacy_record["ingredientSections"]] == [1, 1]
    first = legacy_record["ingredientSections"][0]["ingredients"][0]
    assert first["title"] == "500 γραμ συνθετικό υλικό"
    assert first["quantity"] == ""


def test_method_containers_split_secrets_but_validate_complete_sequence():
    page = SYNTHETIC_PAGE.replace(
        '''"recipeInstructions":[{"@type":"HowToSection","name":"Μαγείρεμα",
   "itemListElement":[{"@type":"HowToStep","text":"Ανακατεύουμε τα υλικά."},
                      {"@type":"HowToStep","text":"Μαγειρεύουμε."}]}],''',
        '''"recipeInstructions":[{"@type":"HowToStep","text":"Βήμα ένα."},
   {"@type":"HowToStep","text":"Βήμα δύο."},
   {"@type":"HowToStep","text":"Μυστικό πεζό."},
   {"@type":"HowToStep","text":"Μυστικό λίστας."}],''',
    ).replace(
        '''<section class="single_recipe__method_steps">
  <h3>Μαγείρεμα</h3>
  <ol><li>Ανακατεύουμε τα υλικά.</li><li>Μαγειρεύουμε.</li></ol>
</section>''',
        '''<section class="single_recipe__method_container">
  <h3 class="single_recipe__method_steps__title">Εκτέλεση</h3>
  <div class="single_recipe__method_steps"><ol><li>Βήμα ένα.</li><li>Βήμα δύο.</li></ol></div>
</section>
<section class="single_recipe__method_container">
  <h3 class="single_recipe__method_steps__title">ΜΥΣΤΙΚΑ</h3>
  <div class="single_recipe__method_steps"><p>Μυστικό πεζό.</p><ul><li><p>Μυστικό λίστας.</p></li></ul></div>
</section>''',
    )
    record = normalize_argiro_page(
        page,
        source_url="https://www.argiro.gr/recipe/synthetiki-fasolada/",
    )
    assert record["methodSections"] == [{"title": "Εκτέλεση", "steps": ["Βήμα ένα.", "Βήμα δύο."]}]
    assert record["stepCount"] == 2
    assert record["preparationCount"] == 1
    assert "Μυστικό πεζό." in record["tips"]
    assert "Μυστικό λίστας." in record["tips"]

    with pytest.raises(Exception, match="method steps HTML/JSON-LD mismatch"):
        normalize_argiro_page(
            page.replace("Μυστικό λίστας.</p>", "Διαφορετικό μυστικό.</p>"),
            source_url="https://www.argiro.gr/recipe/synthetiki-fasolada/",
        )


def test_ingredient_html_jsonld_mismatch_fails_closed():
    with pytest.raises(Exception, match="ingredients HTML/JSON-LD mismatch"):
        normalize_argiro_page(
            SYNTHETIC_PAGE.replace(">νερό</span>", ">άλλο υλικό</span>"),
            source_url="https://www.argiro.gr/recipe/synthetiki-fasolada/",
        )


def test_html_ingredient_superset_is_kept_only_when_jsonld_is_ordered_subsequence():
    page = SYNTHETIC_PAGE.replace(
        '"recipeIngredient":["1 συνθετικό υλικό","2 φλιτζάνια νερό"]',
        '"recipeIngredient":["2 φλιτζάνια νερό"]',
    )
    record = normalize_argiro_page(
        page,
        source_url="https://www.argiro.gr/recipe/synthetiki-fasolada/",
    )
    ingredients = [
        item
        for section in record["ingredientSections"]
        for item in section["ingredients"]
    ]
    assert [item["title"] for item in ingredients] == ["συνθετικό υλικό", "νερό"]

    with pytest.raises(Exception, match="ingredients HTML/JSON-LD mismatch"):
        normalize_argiro_page(
            page.replace(
                '"recipeIngredient":["2 φλιτζάνια νερό"]',
                '"recipeIngredient":["ανύπαρκτο υλικό"]',
            ),
            source_url="https://www.argiro.gr/recipe/synthetiki-fasolada/",
        )


def test_jsonld_unit_prefix_enriches_without_quantity_html_row():
    page = SYNTHETIC_PAGE.replace(
        '"recipeIngredient":["1 συνθετικό υλικό","2 φλιτζάνια νερό"]',
        '"recipeIngredient":["συσκ. συνθετικό υλικό","κ.σ. νερό"]',
    ).replace(
        '<span class="quantity">1</span><a class="ingredient-label"',
        '<a class="ingredient-label without_quantity"',
    ).replace(
        '<span class="quantity">2 φλιτζάνια</span><span class="ingredient-label">',
        '<span class="ingredient-label without_quantity">',
    )
    record = normalize_argiro_page(
        page,
        source_url="https://www.argiro.gr/recipe/synthetiki-fasolada/",
    )
    ingredients = record["ingredientSections"][0]["ingredients"]
    assert [(item["quantity"], item["title"]) for item in ingredients] == [
        ("συσκ.", "συνθετικό υλικό"),
        ("κ.σ.", "νερό"),
    ]


def test_orphan_numeric_jsonld_ingredient_node_is_not_a_recipe_ingredient():
    page = SYNTHETIC_PAGE.replace(
        '"recipeIngredient":["1 συνθετικό υλικό","2 φλιτζάνια νερό"]',
        '"recipeIngredient":["1 συνθετικό υλικό","1","2 φλιτζάνια νερό"]',
    )
    record = normalize_argiro_page(
        page,
        source_url="https://www.argiro.gr/recipe/synthetiki-fasolada/",
    )
    ingredients = [
        item
        for section in record["ingredientSections"]
        for item in section["ingredients"]
    ]
    assert [item["title"] for item in ingredients] == ["συνθετικό υλικό", "νερό"]
    assert "1" in record["sourcePayload"]["jsonLd"]["recipeIngredient"]


def test_quoted_am_rating_and_semantic_youtube_duplicates():
    page = SYNTHETIC_PAGE.replace(
        "var AM = {recipe: {id: 17265, stats: {rating: 4.50, total_votes: 12, rating_percentage: 90}}};",
        'var AM = {"recipe":{"id":17265,"stats":{"rating":"4.50","total_votes":"12","rating_percentage":90}}};',
    ).replace(
        "https://www.youtube.com/embed/second-synthetic",
        "https://www.youtube.com/embed/synthetic?enablejsapi=1",
    )
    record = normalize_argiro_page(
        page,
        source_url="https://www.argiro.gr/recipe/synthetiki-fasolada/",
    )
    assert record["rating10"] == 9.0
    assert record["ratingCount"] == 12
    assert record["videoUrls"] == ["https://www.youtube.com/embed/synthetic"]


def test_schema_org_nutrition_maps_exact_portion_strings_and_serving_size():
    page = SYNTHETIC_PAGE.replace(
        '"video":{"@type":"VideoObject"',
        '''"nutrition":{"@type":"NutritionInformation","servingSize":"1 μερίδα","calories":"245 kcal","fatContent":"8 g","saturatedFatContent":"2 g","carbohydrateContent":"35 g","sugarContent":"4 g","proteinContent":"11 g","fiberContent":"6 g","sodiumContent":"320 mg"},"video":{"@type":"VideoObject"''',
    )
    record = normalize_argiro_page(
        page,
        source_url="https://www.argiro.gr/recipe/synthetiki-fasolada/",
    )
    assert record["nutritionPer"] == "1 μερίδα"
    section = record["nutritionSections"][0]
    assert section["kcalPortion"] == "245 kcal"
    assert section["fatPortion"] == "8 g"
    assert section["saturatedFatPortion"] == "2 g"
    assert section["carbsPortion"] == "35 g"
    assert section["sugarsPortion"] == "4 g"
    assert section["proteinPortion"] == "11 g"
    assert section["fiberPortion"] == "6 g"
    assert section["sodiumPortion"] == "320 mg"
    assert section["kcal100g"] == ""
    assert record["sourcePayload"]["jsonLd"]["nutrition"]["calories"] == "245 kcal"


def test_schema_org_nutrition_uses_neutral_portion_label_when_unspecified():
    page = SYNTHETIC_PAGE.replace(
        '"video":{"@type":"VideoObject"',
        '''"nutrition":{"@type":"NutritionInformation","calories":"100 kcal"},"video":{"@type":"VideoObject"''',
    )
    record = normalize_argiro_page(
        page,
        source_url="https://www.argiro.gr/recipe/synthetiki-fasolada/",
    )
    assert record["nutritionPer"] == "τη δηλωμένη μερίδα"
    assert record["nutritionSections"][0]["kcalPortion"] == "100 kcal"


def test_basic_ingredient_category_cuisine_diet_and_split_keywords_fallbacks():
    page = (
        SYNTHETIC_PAGE
        .replace('"recipeCategory":["Όσπρια"]', '"recipeCategory":["Κυρίως"],"recipeCuisine":"Μεξικάνικη","suitableForDiet":"https://schema.org/GlutenFreeDiet"')
        .replace('"keywords":["κατσαρόλα"]', '"keywords":"ένα, δύο; τρία"')
        .replace(
            'href="https://www.argiro.gr/recipe-category/ospria/">Όσπρια',
            'href="https://www.argiro.gr/basic-ingredient/psaria/solomos/">Σολομός',
        )
    )
    record = normalize_argiro_page(
        page,
        source_url="https://www.argiro.gr/recipe/synthetiki-fasolada/",
    )
    assert record["category"] == "fish"
    assert record["cuisineLabels"] == ["Μεξικάνικη"]
    assert record["dietLabels"] == ["Χωρίς γλουτένη"]
    assert {"ένα", "δύο", "τρία"}.issubset(record["tags"])
    assert "ένα, δύο; τρία" not in record["tags"]


@pytest.mark.parametrize(
    (
        "recipe_category", "recipe_slug", "ingredient_slug", "expected",
        "expected_keys",
    ),
    [
        (
            "Κοτόπουλο", "kotopoulo", "kreas/moschari", "poultry",
            ["poultry", "meat"],
        ),
        (
            "Ζυμαρικά", "zymarika", "laxanika/brokolo", "pasta_rice",
            ["pasta_rice", "vegetables"],
        ),
        (
            "Πίτες", "pites", "kreas/kotopoulo", "poultry",
            ["poultry", "meat"],
        ),
        (
            "Κρέας", "kreas", "kreas/galopoula", "poultry",
            ["poultry", "meat"],
        ),
        (
            "Κρέας", "kreas", "kreas/kokoras", "poultry",
            ["poultry", "meat"],
        ),
        (
            "Γλυκά", "glika", "laxanika/karoto", "dessert",
            ["dessert", "vegetables"],
        ),
    ],
)
def test_explicit_family_wins_and_nested_poultry_refines_broad_meat(
    recipe_category,
    recipe_slug,
    ingredient_slug,
    expected,
    expected_keys,
):
    page = (
        SYNTHETIC_PAGE
        .replace('"recipeCategory":["Όσπρια"]', f'"recipeCategory":["{recipe_category}"]')
        .replace(
            '<span class="tag_item"><a href="https://www.argiro.gr/recipe-category/ospria/">Όσπρια</a></span>',
            f'''<span class="tag_item"><a href="https://www.argiro.gr/recipe-category/{recipe_slug}/">{recipe_category}</a></span>
<span class="tag_item"><a href="https://www.argiro.gr/basic-ingredient/{ingredient_slug}/">Συνθετικό συστατικό</a></span>''',
        )
    )
    record = normalize_argiro_page(
        page,
        source_url="https://www.argiro.gr/recipe/synthetiki-fasolada/",
    )
    assert record["category"] == expected
    assert record["categoryKeys"] == expected_keys


@pytest.mark.parametrize(
    "recipe_slug",
    ["rofimata-pota", "ntip-saltses", "sinodeutika", "psomi-zymes"],
)
def test_explicit_nonmeal_families_are_terminal_over_ingredients(recipe_slug):
    page = (
        SYNTHETIC_PAGE
        .replace('"recipeCategory":["Όσπρια"]', '"recipeCategory":["Άλλο"]')
        .replace(
            'href="https://www.argiro.gr/recipe-category/ospria/">Όσπρια',
            f'href="https://www.argiro.gr/recipe-category/almira/{recipe_slug}/">Άλλο',
        )
        .replace(
            '</div>\n<aside class="single_recipe__left_column">',
            '''<a class="tag_item" href="https://www.argiro.gr/basic-ingredient/kreas/kotopoulo/">Κοτόπουλο</a></div>
<aside class="single_recipe__left_column">''',
        )
    )
    record = normalize_argiro_page(
        page,
        source_url="https://www.argiro.gr/recipe/synthetiki-fasolada/",
    )
    assert record["category"] == "other"
    assert record["categoryKeys"][:3] == ["other", "poultry", "meat"]


@pytest.mark.parametrize("recipe_slug", ["santouits", "fingerfood", "finger-food"])
def test_only_exact_sandwich_or_finger_format_is_street_food(recipe_slug):
    page = (
        SYNTHETIC_PAGE
        .replace('"recipeCategory":["Όσπρια"]', '"recipeCategory":["Σνακ"]')
        .replace(
            'href="https://www.argiro.gr/recipe-category/ospria/">Όσπρια',
            f'href="https://www.argiro.gr/recipe-category/almira/{recipe_slug}/">Σνακ',
        )
        .replace(
            '</div>\n<aside class="single_recipe__left_column">',
            '''<a class="tag_item" href="https://www.argiro.gr/basic-ingredient/kreas/moschari/">Μοσχάρι</a></div>
<aside class="single_recipe__left_column">''',
        )
    )
    record = normalize_argiro_page(
        page,
        source_url="https://www.argiro.gr/recipe/synthetiki-fasolada/",
    )
    assert record["category"] == "street_food"
    assert record["categoryKeys"][:2] == ["street_food", "meat"]


@pytest.mark.parametrize("recipe_slug", ["snak", "proino", "brunch", "pitsa"])
def test_broad_snack_breakfast_and_pizza_families_do_not_imply_street_food(
    recipe_slug,
):
    page = (
        SYNTHETIC_PAGE
        .replace('"recipeCategory":["Όσπρια"]', '"recipeCategory":["Σνακ"]')
        .replace(
            'href="https://www.argiro.gr/recipe-category/ospria/">Όσπρια',
            f'href="https://www.argiro.gr/recipe-category/almira/{recipe_slug}/">Σνακ',
        )
    )
    record = normalize_argiro_page(
        page,
        source_url="https://www.argiro.gr/recipe/synthetiki-fasolada/",
    )
    assert record["category"] == "other"


def test_all_facet_and_tag_labels_dedupe_case_and_diacritics():
    page = (
        SYNTHETIC_PAGE
        .replace(
            '"recipeCategory":["Όσπρια"]',
            '"recipeCategory":["ΟΣΠΡΙΑ"],"recipeCuisine":"Μεξικάνικη","suitableForDiet":"https://schema.org/VeganDiet"',
        )
        .replace(
            '<span class="tag_item"><a href="https://www.argiro.gr/recipe-category/ospria/">Όσπρια</a></span>',
            '''<span class="tag_item"><a href="https://www.argiro.gr/recipe-category/ospria/">Όσπρια</a></span>
<span class="tag_item"><a href="https://www.argiro.gr/dietary-category/vegan/">VEGAN</a></span>
<span class="tag_item"><a href="https://www.argiro.gr/dietary-category/vegan-alt/">Vegan</a></span>
<span class="tag_item"><a href="https://www.argiro.gr/cuisine/mexikaniki/">ΜΕΞΙΚΑΝΙΚΗ</a></span>
<span class="tag_item"><a href="https://www.argiro.gr/cuisine/mexikaniki-alt/">μεξικανικη</a></span>''',
        )
    )
    record = normalize_argiro_page(
        page,
        source_url="https://www.argiro.gr/recipe/synthetiki-fasolada/",
    )
    assert record["dietLabels"] == ["VEGAN"]
    assert record["cuisineLabels"] == ["ΜΕΞΙΚΑΝΙΚΗ"]
    normalized_tags = [
        "".join(character for character in __import__("unicodedata").normalize("NFD", item.casefold()) if __import__("unicodedata").category(character) != "Mn")
        for item in record["tags"]
    ]
    assert len(normalized_tags) == len(set(normalized_tags))


def test_expert_advice_and_recipe_scoped_images_are_preserved_safely():
    page = SYNTHETIC_PAGE.replace(
        "</body>",
        '''<section class="expert_advice">
  <div class="expert_advice__info">Συμβουλή Ειδικού <span class="expert_advice__name">Dr. Δοκιμή</span></div>
  <div class="expert_advice__content"><p>Συνθετικό διατροφικό κείμενο.</p></div>
  <div class="expert_advice__image"><img src="https://www.argiro.gr/wp-content/uploads/expert-synthetic.jpg"></div>
</section>
<div class="equipment"><img src="https://www.argiro.gr/wp-content/uploads/pan.svg"></div>
</body>''',
    )
    record = normalize_argiro_page(
        page,
        source_url="https://www.argiro.gr/recipe/synthetiki-fasolada/",
    )
    assert record["imageUrls"] == [
        "https://www.argiro.gr/wp-content/uploads/synthetic.jpg",
    ]
    assert record["nutritionTips"] == [
        "Συμβουλή Ειδικού — Dr. Δοκιμή: Συνθετικό διατροφικό κείμενο."
    ]
    advice = record["sourcePayload"]["htmlMetadata"]["expertAdvice"][0]
    assert advice["name"] == "Dr. Δοκιμή"
    assert advice["imageUrl"].endswith("expert-synthetic.jpg")


def test_malformed_tip_prose_is_recovered_from_jsonld_between_dom_anchors():
    page = SYNTHETIC_PAGE.replace(
        '''"recipeInstructions":[{"@type":"HowToSection","name":"Μαγείρεμα",
   "itemListElement":[{"@type":"HowToStep","text":"Ανακατεύουμε τα υλικά."},
                      {"@type":"HowToStep","text":"Μαγειρεύουμε."}]}],''',
        '''"recipeInstructions":[{"@type":"HowToStep","text":"Κανονικό ένα."},
   {"@type":"HowToStep","text":"Κανονικό δύο."},
   {"@type":"HowToStep","text":"Κρυφό πεζό ένα."},
   {"@type":"HowToStep","text":"Κρυφό πεζό δύο."},
   {"@type":"HowToStep","text":"Ορατή άγκυρα ένα."},
   {"@type":"HowToStep","text":"Ορατή άγκυρα δύο."}],''',
    ).replace(
        '''<section class="single_recipe__method_steps">
  <h3>Μαγείρεμα</h3>
  <ol><li>Ανακατεύουμε τα υλικά.</li><li>Μαγειρεύουμε.</li></ol>
</section>''',
        '''<section class="single_recipe__method_container">
  <h3 class="single_recipe__method_steps__title">Εκτέλεση</h3>
  <div class="single_recipe__method_steps"><li>Κανονικό ένα.</li><li>Κανονικό δύο.</li></div>
</section>
<section class="single_recipe__method_container">
  <h3 class="single_recipe__method_steps__title">ΜΥΣΤΙΚΑ</h3>
  <div class="single_recipe__method_steps"><p><p>Κρυφό πεζό ένα.</p>Κρυφό πεζό δύο.<li>Ορατή άγκυρα ένα.</li><li>Ορατή άγκυρα δύο.</li></div>
</section>''',
    )
    record = normalize_argiro_page(
        page,
        source_url="https://www.argiro.gr/recipe/synthetiki-fasolada/",
    )
    assert record["stepCount"] == 2
    assert record["tips"][-4:] == [
        "Κρυφό πεζό ένα.", "Κρυφό πεζό δύο.",
        "Ορατή άγκυρα ένα.", "Ορατή άγκυρα δύο.",
    ]


def test_promotional_shortcode_and_signoff_are_not_recipe_method_steps():
    page = SYNTHETIC_PAGE.replace(
        '{"@type":"HowToStep","text":"Μαγειρεύουμε."}]}],',
        '''{"@type":"HowToStep","text":"Μαγειρεύουμε."},
      {"@type":"HowToStep","text":"[visual-link-preview encoded=synthetic]"},
      {"@type":"HowToStep","text":"Να φτιάχνετε τις συνταγές που σας προτείνω και περιμένω τα σχόλιά σας στα social media μου:"},
      {"@type":"HowToStep","text":"Καλή επιτυχία!"}]}],''',
    ).replace(
        '<li>Μαγειρεύουμε.</li></ol>',
        '''<li>Μαγειρεύουμε.</li>
      <li>[visual-link-preview encoded=synthetic]</li>
      <li>Να φτιάχνετε τις συνταγές που σας προτείνω και περιμένω τα σχόλιά σας στα social media μου:</li>
      <li>Καλή επιτυχία!</li></ol>''',
    )
    record = normalize_argiro_page(
        page,
        source_url="https://www.argiro.gr/recipe/synthetiki-fasolada/",
    )
    assert record["methodSections"][0]["steps"] == [
        "Ανακατεύουμε τα υλικά.", "Μαγειρεύουμε.",
    ]
    assert record["stepCount"] == 2


@pytest.mark.parametrize(
    ("provider_recipe_id", "artifact"),
    AUDITED_INSTRUCTION_ARTIFACTS,
    ids=[
        f"argiro-{provider_recipe_id}-{index}"
        for index, (provider_recipe_id, _artifact) in enumerate(
            AUDITED_INSTRUCTION_ARTIFACTS,
            start=1,
        )
    ],
)
def test_every_audited_instruction_artifact_is_excluded_exactly(
    provider_recipe_id,
    artifact,
):
    sections, steps = _instructions(
        [
            {"@type": "HowToStep", "text": "Ανακατεύουμε τα υλικά."},
            {"@type": "HowToStep", "text": artifact},
        ],
        provider_recipe_id=provider_recipe_id,
    )
    assert steps == ["Ανακατεύουμε τα υλικά."]
    assert sections == [{"title": "", "steps": steps}]


def test_instruction_artifact_filters_do_not_drop_similar_recipe_prose():
    legitimate = [
        "Μαγείρεψε και ανακάτεψε προσεκτικά.",
        "Δείτε εδώ και φτιάξτε διαφορετική δοκιμαστική οδηγία.",
        "Συμβουλευτείτε https://www.argiro.gr/recipe/example/ αν το χρειάζεστε.",
        "[adrotate banner=not-a-number]",
        "RELATED ARTICLE sauce is mixed into the filling.",
    ]
    _sections, steps = _instructions(
        legitimate,
        provider_recipe_id="unrelated",
    )
    assert steps == legitimate


def test_filtered_instruction_artifact_remains_in_raw_source_payload():
    artifact = "[adrotate banner=\u201d29\u2033]"
    page = SYNTHETIC_PAGE.replace(
        '{"@type":"HowToStep","text":"Μαγειρεύουμε."}]}],',
        '{"@type":"HowToStep","text":"Μαγειρεύουμε."},'
        '{"@type":"HowToStep","text":"[adrotate banner=\\u201d29\\u2033]"}]}],',
    ).replace(
        '<li>Μαγειρεύουμε.</li></ol>',
        '<li>Μαγειρεύουμε.</li><li>[adrotate banner=\u201d29\u2033]</li></ol>',
    )
    record = normalize_argiro_page(
        page,
        source_url="https://www.argiro.gr/recipe/synthetiki-fasolada/",
    )
    assert artifact not in [
        step
        for section in record["methodSections"]
        for step in section["steps"]
    ]
    raw_steps = record["sourcePayload"]["jsonLd"]["recipeInstructions"][0][
        "itemListElement"
    ]
    assert raw_steps[-1]["text"] == artifact


def test_missing_normal_dom_leaf_is_recovered_from_ordered_jsonld_anchors():
    page = SYNTHETIC_PAGE.replace(
        '''"recipeInstructions":[{"@type":"HowToSection","name":"Μαγείρεμα",
   "itemListElement":[{"@type":"HowToStep","text":"Ανακατεύουμε τα υλικά."},
                      {"@type":"HowToStep","text":"Μαγειρεύουμε."}]}],''',
        '''"recipeInstructions":[{"@type":"HowToStep","text":"Πρώτο βήμα."},
   {"@type":"HowToStep","text":"Κρυφό από κακοσχηματισμένο HTML."},
   {"@type":"HowToStep","text":"Τρίτο βήμα."}],''',
    ).replace(
        '''<section class="single_recipe__method_steps">
  <h3>Μαγείρεμα</h3>
  <ol><li>Ανακατεύουμε τα υλικά.</li><li>Μαγειρεύουμε.</li></ol>
</section>''',
        '''<section class="single_recipe__method_container">
  <h3 class="single_recipe__method_steps__title">Εκτέλεση</h3>
  <div class="single_recipe__method_steps"><li>Πρώτο βήμα.</li><div>Κρυφό από κακοσχηματισμένο HTML.</div><li>Τρίτο βήμα.</li></div>
</section>''',
    )
    record = normalize_argiro_page(
        page,
        source_url="https://www.argiro.gr/recipe/synthetiki-fasolada/",
    )
    assert record["methodSections"] == [{
        "title": "Εκτέλεση",
        "steps": [
            "Πρώτο βήμα.",
            "Κρυφό από κακοσχηματισμένο HTML.",
            "Τρίτο βήμα.",
        ],
    }]


@pytest.mark.parametrize(
    ("value", "minutes"),
    [("PT45M", 45), ("PT1H5M", 65), ("P1DT2H", 1560), ("bad", 0)],
)
def test_iso_duration_parsing(value, minutes):
    assert parse_iso8601_minutes(value) == minutes


@pytest.mark.parametrize("path, category", [
    ("dimitriaka/kinoa", "pasta_rice"), ("dimitriaka/pligouri", "pasta_rice"),
    ("frouta/avokanto", "vegetables"),
])
def test_reviewed_ingredient_paths_preserve_terminal_recipe_families(path, category):
    from tools.recipe_importer.argiro_schema import derive_argiro_taxonomy
    payload = {
        "htmlMetadata": {"tagLinks": [{
            "href": f"https://www.argiro.gr/basic-ingredient/{path}/", "label": "Fixture",
        }]},
        "jsonLd": {"recipeCategory": ["Κυρίως"]},
    }
    assert derive_argiro_taxonomy(payload)["category"] == category
    payload["jsonLd"]["recipeCategory"] = ["Γλυκά"]
    assert derive_argiro_taxonomy(payload)["category"] == "dessert"
