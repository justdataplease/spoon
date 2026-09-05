# All-category and source-tag audit - 2026-09-05

The expanded review found missing taxonomy mappings beyond chicken. Release 0.7.6
(version code 16) contains the full corrections and replaces 0.7.5 at `dist/spoon.apk`.

Later device verification found app/database filter disagreements that the Python
index audit could not detect. Release 0.7.7 corrects them; see the
[full device verification report](device-catalog-audit-2026-09-05.md).

## Scope and findings

Every one of the 20,861 records was compared with its preserved publisher evidence:
primary category, secondary category keys, general tags, diet, meal type, occasion,
method, cuisine, and main-ingredient facets. All publisher recipe-category labels
and the ingredient-label inventories from Akis, Argiro, and Gastronomos were reviewed.
The inventory contains 63 Akis, 191 Argiro, and 213 Gastronomos display labels,
including harmless case variants. This is a source-data audit; publisher pages
were not crawled again.

The earlier SQL machine-key fix applies to every category. New domain regressions
also reroll every category repeatedly while permitting the current recipe.

The broader taxonomy corrections affect 3,545 primary categories: 50 Akis,
11 Argiro, and 3,484 Gastronomos recipes. Additional secondary category keys were
updated without changing those recipes' primary category. Ingredients, instructions,
identities, and captured source payloads were preserved.

Confirmed mapping gaps included:

- Gastronomos fish species and seafood, beef/pork/lamb/mixed mince, rooster/turkey
  mince, the chickpea spelling variant, vegetables, and grain/pasta labels.
- Argiro quinoa, bulgur, and avocado ingredient paths.
- Akis bread, dough, sauce/dip, marinade, and cooking-base source families, which
  must remain Other instead of inheriting a main-food category from an ingredient.
- Greek-labelled cocktails, which must remain Other even when tagged with tomato.

Classification still uses exact publisher taxonomy and the established precedence:
explicit desserts, non-meal families, and street-food formats are protected;
explicit food categories precede fallback ingredient evidence. Titles and ingredient
prose do not classify a recipe through substring guessing. Flour, dairy, sugar,
fruit, and garnish/herb labels alone do not identify a main-meal category.

## Complete category counts

| Category | Before broader correction (0.7.5) | After (0.7.6) |
| --- | ---: | ---: |
| Legumes | 673 | 788 |
| Chicken / poultry | 1,284 | 1,327 |
| Vegetables | 1,862 | 2,896 |
| Meat | 2,298 | 3,208 |
| Fish / seafood | 937 | 1,887 |
| Street food | 329 | 317 |
| Pasta / rice | 1,660 | 1,483 |
| Desserts | 5,493 | 5,493 |
| Other | 6,325 | 3,462 |
| Total | 20,861 | 20,861 |

These are single-primary-category counts, not counts of every recipe containing an
ingredient. For example, a mixed seafood/pasta dish can have Fish as its primary
fallback category while retaining both source ingredient tags. The poultry group
also includes the publisher's turkey, duck, and other poultry recipes.

## Verification and infrastructure

- All original source artifacts/manifests were validated and fingerprinted before
  migration; local originals were backed up under the ignored importer output.
- Every corrected artifact passed importer/manifest validation. Raw source-payload
  hashes are unchanged for all three providers.
- The final source audit reports zero category drift, zero tag-family mismatches,
  and zero unreadable source records.
- The complete rebuilt SQLite catalog passes integrity and index/payload checks,
  including category, title, facets, raw ingredient text, effort, rating, time,
  quick flags, and strict vegan eligibility.
- Every category-only SQL pool equals its direct stored category count. 250 random
  SQL draws per category passed (2,250 total), with zero missing or wrong-category
  payloads.
- 285 Android unit tests and 393 importer tests passed. Release assembly and vital
  lint passed. The APK version, embedded database hash, and existing signing
  certificate were verified.

Public selection uses the full bundled database, not the bounded UI cache,
Firestore history, or a previously chosen recipe list. Installing the new APK
updates its public catalog through the existing install-stamp mechanism; personal
state does not need a reset. There were no live Firestore writes. No Android device
was connected, so on-device installation/interactions were not tested.

## Limits and remaining data quality

Source agreement cannot certify that every publisher assertion is culinarily
correct. There are still 2,324 recipes without a publisher main-ingredient facet
(2,013 Akis, 21 Argiro, 290 Gastronomos). Some mixed dishes also have incomplete
source ingredient labels. Ingredient-facet searches can therefore omit recipes
that contain that ingredient. Ingredient exclusions additionally consult normalized
raw ingredient titles/notes and are not restricted to these facets.

Publisher quantities sometimes remain embedded in ingredient titles. The catalog
has no empty or HTML-containing ingredient titles. Raw text and heuristic food
signals are useful review evidence, but are not a safe blanket replacement for
publisher taxonomy. A remaining Other category is not automatically a bad recipe:
it includes eggs/dairy dishes, breads, drinks, condiments, and recipes without
sufficient category evidence.

## Repeatable audit

```powershell
python -m tools.recipe_importer.audit_source_taxonomy --output tools/recipe_importer/output/source-taxonomy-audit.json
python tools/recipe_importer/audit_catalog_quality.py
python -m pytest tools/recipe_importer/tests -q
```

Detailed local evidence: `all-category-source-audit-before.json`,
`all-category-source-audit-after.json`, `all-category-taxonomy-repair.json`, and
`all-category-bundled-audit.json` under `tools/recipe_importer/output/`. These remain
ignored because they contain private recipe-data samples.
