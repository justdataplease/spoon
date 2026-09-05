# Recipe selection and catalog audit - 2026-09-05

Historical 0.7.5 investigation. The broader audit and current 0.7.6 APK are documented in [the all-category report](all-category-audit-2026-09-05.md).

Chicken-only selection had a filtering bug even though the bundled catalog already contained 1,208 poultry recipes. The corrected release is 0.7.5 (version code 15), delivered as `dist/spoon.apk`.

## Confirmed causes and fixes

The SQLite preference filter applied search-text normalization to machine category keys. This changed `pasta_rice` and `street_food` to space-separated values that did not match stored database keys. Chicken-only settings therefore admitted 3,209 rows: 1,208 poultry plus 2,001 pasta/street-food rows. The planner's in-memory preference check rejected those extra rows after the random draw, returning a false no-match result. Explore also used the broken normalization for direct category selection. Both SQL paths now preserve machine keys.

Reroll also excluded the day's current recipe. It now draws from the full eligible pool on every request, including current selections. Other days, previous/future weeks, and cooked history do not reduce the pool. A regression test repeats a single-recipe selection 100 times with the same recipe already present across weeks and history, then applies an explicit category filter.

Gastronomos's exact official chicken-mince taxonomy label was missing from the poultry mapping. Correcting it reclassified 76 recipes: 60 from Other, 12 from pasta/rice, and 4 from vegetables. Publisher category precedence remains intact; titles and raw ingredient prose do not automatically reclassify dishes. The original local artifact and manifest were validated and fingerprinted before repair, then backed up under the ignored importer output directory. Only derived category fields changed; the source-payload collection hash was verified unchanged. Corrected records and manifests passed full importer validation before rebuilding SQLite.

## Dataset findings

| Provider | Total recipes | Poultry before | Poultry after | Without main-ingredient facet |
| --- | ---: | ---: | ---: | ---: |
| Akis | 5,369 | 369 | 369 | 2,013 |
| Argiro | 3,165 | 147 | 147 | 21 |
| Gastronomos | 12,327 | 692 | 768 | 290 |
| Total | 20,861 | 1,208 | 1,284 | 2,324 |

The complete rebuilt database passes SQLite integrity checking. All indexed categories, titles, facets, raw ingredient texts, effort, rating, preparation time, quick flags, and vegan eligibility agree with their recipe payloads. The audit now checks the additional planner columns automatically, with corrupted-index regression fixtures.

Publisher main-ingredient facets remain incomplete. Ingredient-facet search uses those exact labels and reviewed aliases, so it can miss recipes whose publisher supplied no relevant tag. Ingredient exclusions also inspect normalized raw ingredient titles and notes; they are not limited to facets. No empty or HTML-containing ingredient titles were found. Some publishers retain quantities inside ingredient title text; this affects structure/display quality rather than category-key matching. Mixed dishes may legitimately have a primary category different from their chicken ingredient tag. These limitations should not be interpreted as a shortage of poultry choices.

## Infrastructure and verification

Public selection queries the bundled SQLite database, not the bounded observable recipe cache or Firestore history. Public/custom recipe weighting remains based on eligible recipe counts. The existing install-stamp check replaces the local public catalog after an APK upgrade; no personal-data reset is required. Quarterly Firestore catalog refreshes do not replace an installed APK's bundled database.

Validation completed:

- 284 Android unit tests passed.
- 367 importer tests passed.
- Every category-only SQL pool matched its stored category count.
- 1,000 chicken-only SQL draws returned valid poultry payloads, with zero missing/rejected rows.
- Release assembly and release vital lint passed.
- APK version and embedded database hash verified; signing certificate matches the preceding release.

No Android device was connected, so installation and on-device interaction were not tested. No live Firestore catalog or personal-state writes were performed.

Detailed local audit evidence is under `tools/recipe_importer/output/`: `catalog-audit-before-chicken-fix.json`, `catalog-audit-after-chicken-fix.json`, and `chicken-taxonomy-repair.json`. These files remain ignored because they include private dataset samples.
