# Catalog verification on Android - 2026-09-05

Release 0.7.7 (version code 17) adds a repeatable device contract over the actual
20,861-recipe APK asset. This supplements the source taxonomy and Python database
audits, which alone did not establish agreement with the app's Kotlin filters.

## Additional defects found and fixed

- Explore normalized punctuation differently in memory and SQLite. The query
  `κοτόπουλο-λεμόνι` returned zero Kotlin matches but 52 SQL matches. Both now use
  the catalog normalizer, including for facets and title ordering.
- Vegan checks used different ingredient rules in Python and Kotlin: the first
  device run found 1,450 Kotlin matches versus 1,577 stored matches, with both
  missing and additional IDs. A SQL-selected recipe could therefore be rejected
  by the planner after selection, producing a false no-match result.
- The catalog's vegan flag ignored ingredient facets that the Kotlin code checked.
  Both now evaluate facets and raw ingredient title/info, recognize the same local
  plant qualifiers and food names, and preserve separate animal/plant alternatives.
  Explicit Unicode boundaries make Greek alternative splitting consistent on JVM
  and Android. The final catalog has 1,557 vegan-eligible records under these rules.

Recipe IDs, raw recipe payloads, publisher source artifacts, and category totals
remain unchanged in this release. Only derived vegan indexes/catalog metadata and
app filtering change. The earlier category mapping and repeat-selection fixes
remain included. Poultry has 1,327 recipes; previously selected recipes and other
weeks do not shrink the selection pool.

## Verification coverage

`CatalogContractDeviceTest` runs production `CatalogSqlBuilder`,
`BundledRecipeCatalog`, `RecipeSelector`, `ExploreRecipeFilter`,
`LocalSpoonRepository`, and `MealPlanner` on an Android 15 / API 35 emulator.

- Exact SQL/Kotlin eligible-ID agreement across every bundled recipe for every
  primary category, category-only preferences, ease/rating/time constraints,
  vegan settings, ingredient exclusions, compound category keys, searches,
  multi-facet selections, and provider restrictions.
- Every published facet: 296 ingredient, 41 cuisine, 20 diet, 29 meal, 10 method,
  and 28 occasion options (424 total), including ingredient alias expansion.
- Actual random selections and counts from the database; Explore counts and
  first/second page membership, ordering, and absence of duplicates.
- Twenty rerolls per category while previous/current/future weeks and cooked
  history exist. The suite also verifies cache eviction, reopening saved state,
  and that a real no-match result preserves the saved plan.
- Replacement of a catalog with an outdated APK install stamp while unrelated
  personal preferences remain intact. Tests use isolated files/preferences.

All 5 device tests, 287 JVM tests, and 398 importer tests passed. Optimized release
assembly and vital release lint passed. The signed APK was installed over the test
build and cold-launched successfully in airplane mode; the planner rendered local
recipe suggestions. Its version, existing signing certificate, and embedded
database hash were verified.

The final source audit reports zero category drift, tag-family mismatches, or
unreadable source records. The final database audit reports zero index/payload
or vegan recomputation mismatches. Full local evidence is retained under ignored
`tools/recipe_importer/output/device-contract-*-audit.json`.

Catalog SHA-256:
`083ee30a534ba4afdaca73b85e5d3445a7dbb5294ee644a2b53c32a36a6c7336`.
Catalog content hash:
`4ff1c279a37cf428723a927b3edd9f2a77735f332594eaba4c7726a340eecf4c`.

## Reproduction

Start a disposable Android emulator, then run. For an existing multi-user AVD,
use the explicit test-user install/instrument commands in
[Android device validation](android-device-validation.md) instead of the connected
Gradle task, whose cleanup uninstalls packages across users:

```powershell
.\gradlew.bat testDebugUnitTest connectedDebugAndroidTest assembleRelease lintVitalRelease --console=plain
python -m pytest tools/recipe_importer/tests -q
python tools/recipe_importer/audit_catalog_quality.py
python -m tools.recipe_importer.audit_source_taxonomy --output tools/recipe_importer/output/source-taxonomy-audit.json
```

The source audit requires the private complete publisher artifacts. The device
suite and bundled database audit use the checked-in asset. Run device verification
again when the catalog generator or Kotlin selection/filter rules change.

## Limits

This verifies filtering and persistence against the current dataset. It does not
certify every publisher's culinary assertions or every possible future ingredient
phrase. There are still 2,324 recipes without publisher main-ingredient facets;
that source completeness limitation is documented in the earlier all-category
audit. Live cloud synchronization and the user's physical phone were not tested.
