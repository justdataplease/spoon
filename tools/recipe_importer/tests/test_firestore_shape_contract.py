"""Independent regressions for Firestore-safe source evidence shapes."""

import pytest

from tools.recipe_importer.argiro_schema import parse_argiro_page
from tools.recipe_importer.import_catalog import CatalogError, _validate_firestore_value


def test_argiro_tag_links_preserve_exact_pairs_and_order_as_maps():
    page = """<!doctype html>
<html lang="el"><head>
  <script type="application/ld+json">
    {"@context":"https://schema.org","@type":"Recipe","name":"Δοκιμή"}
  </script>
</head><body>
  <span class="tag_item"><a href="https://www.argiro.gr/recipe-category/ospria/">Όσπρια</a></span>
  <span class="tag_item"><a href="https://www.argiro.gr/recipe-category/ospria/">Όσπρια</a></span>
  <span class="tag_item"><a href="https://www.argiro.gr/basic-ingredient/fakes/">Φακές &amp; όσπρια</a></span>
</body></html>"""

    _, metadata = parse_argiro_page(page)

    assert metadata["tagLinks"] == [
        {
            "href": "https://www.argiro.gr/recipe-category/ospria/",
            "label": "Όσπρια",
        },
        {
            "href": "https://www.argiro.gr/basic-ingredient/fakes/",
            "label": "Φακές & όσπρια",
        },
    ]


def test_firestore_shape_allows_twenty_levels_but_rejects_twenty_one():
    def nested_maps(depth: int):
        value = "leaf"
        for _ in range(depth):
            value = {"child": value}
        return value

    _validate_firestore_value(nested_maps(20), path="collection/document.field")

    with pytest.raises(CatalogError, match="nesting exceeds 20"):
        _validate_firestore_value(nested_maps(21), path="collection/document.field")


def test_firestore_shape_allows_array_map_array_but_rejects_direct_nested_array():
    _validate_firestore_value(
        [{"href": "https://www.argiro.gr/", "labels": ["Αργυρώ"]}],
        path="spoon_recipe_payloads/id.payload.tagLinks",
    )

    with pytest.raises(CatalogError, match=r"directly inside another array.*field\[0\]"):
        _validate_firestore_value(
            [["forbidden"]],
            path="collection/document.field",
        )
