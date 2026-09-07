# Τι θα φάμε; (Spoon)

Spoon is an Android meal planner written in Kotlin and Jetpack Compose. The entire
user interface is Greek. It builds a weekly food plan, proposes a matching recipe
for each day, and makes it easy to reroll, filter, save, replace, and mark meals as
cooked.

The Android application ID is `com.spoon.app`. Its complete public catalog of
20,861 Greek recipes is bundled as an indexed SQLite database, while Firebase
project `spoontheplanner` provides account-backed synchronization for personal
data.

## App features

- Monday-to-Sunday planning with default main-food groups such as όσπρια, κοτόπουλο,
  λαχανικά, κρέας, ψάρι, βρώμικο, and ζυμαρικά/ρύζι.
- Preferences let you choose a default category for each weekday, Monday through
  Sunday, and generate new proposals only from favorites. Matching favorites can
  repeat across days and weeks; missing categories show an unavailable recipe card
  with an action to edit that day's category or filters.
- The main recipe stays visible on each day card. The optional «Πλήρες μενού»
  button opens a dedicated screen with saved main, side, and dessert courses.
  Each course supports filters, favorites, replacement, locks, and cooking history.
  Reopening the screen and regenerating weekly mains preserve saved extras. Only
  an explicit course replacement changes them. New courses respect the selected
  source and food preferences; missing matches show unavailable. Dessert remains
  an explicit category choice; the preset week uses main-dish categories.
- Lock a recipe at the top of its day card to keep it during weekly regeneration.
  Already-cooked meals are automatically kept too. Locks persist offline and sync
  with the plan; unlocking makes the day eligible for weekly regeneration again.
- Independent random reroll for one day or the whole week. Every matching recipe
  has the same selection probability across Akis, Argiro, Gastronomos, and custom
  recipes; no provider receives priority. Existing selections, other days/weeks,
  and cooked history do not remove recipes from the pool for eligible days.
- Per-day constraints for category, difficulty, minimum rating on a 0–10 scale,
  and maximum hands-on preparation time.
- A clear «Ευκολάκι» effort index based on preparation sections and method steps:
  unknown when both are absent, easy for at most one preparation and 1–5 steps,
  demanding for at least three preparations or ten steps, and moderate otherwise.
- Favorites share Explore's Greek text search and complete combined filters, with
  independent search state, an active-filter count, and clear/reset actions.
- Favorites, cooked/not-cooked tracking for each course, a dedicated «Ιστορικό»
  bottom tab, previous/next weeks, and a month calendar.
- Replacement of an existing day's suggestion with any saved favorite.
- A persistent shopping list. Add every ingredient from a recipe in one tap, add
  manual items, tick them off, remove them, or clear everything completed.
- A private note on every recipe and a Greek custom-recipe editor with ingredients,
  ordered steps, category, timings, servings, and a compressed photo from the gallery
  or camera.
- An Explore screen with accent-insensitive Greek search and combined filters for
  category, effort, rating, preparation time, quick recipes, special diet, meal
  type, occasion, cooking method, country/cuisine, and main ingredient.
- Photo-rich cards and a complete recipe page with gallery, descriptions, timing,
  difficulty, servings, rating distribution, grouped ingredients and conversions,
  numbered method steps, tips, nutrition, equipment, publication metadata, and the
  canonical source link.
- User-initiated inline video for YouTube, Vimeo, and direct HTTPS video files.
  Nothing autoplays, unsafe URLs/navigation are blocked, loading failures are shown
  instead of a blank player, and an external fallback remains available.
- The complete 20,861-recipe Greek catalog works offline from indexed local
  SQLite. Explore loads 24 rows at a time, so opening and filtering the catalog
  does not download every recipe or issue Firestore recipe reads.
- Anonymous Firebase Authentication plus optional email/password account linking,
  sign-in, sign-out, and password reset. Linking upgrades the same UID so the
  owner's plans, favorites, history, notes, shopping list, and custom recipes remain
  attached to the account and sync across phones. Account metadata checks are
  throttled to once per 15 seconds; failed connections retry after 5, 10, 20, then
  30 seconds. Live personal-data listeners and queued saves remain immediate.

Strict filters are never silently relaxed. If no recipe matches a valid request,
the day is saved as unavailable, keeping its category and filters for the next
attempt. Adding a matching favorite allows the day to recover. Changing weekday
defaults updates unfinished days of the selected week; completed meals and their
cooking history remain intact during preference reconciliation.

## Architecture

All public recipe summaries and full details are read from the bundled indexed
SQLite catalog. Search, filters, 24-row Explore pages, random planning, favorites
lookup, and recipe details therefore remain available without a network
connection and produce zero reads from the Firestore recipe collections. Images
and videos keep their publisher HTTPS URLs and are fetched and cached only when
needed, so media that has not already been cached still requires connectivity.

Personal state is offline-first: plans, favorite IDs, cooked history, shopping
items, notes, and custom recipes are written to Firestore's persistent local
cache immediately. Firebase synchronizes that queued state as a backup and makes
it available on another signed-in phone when connectivity returns. A new phone
needs one initial online sign-in/bootstrap before its personal cache can be used
offline; the bundled public catalog does not.

```text
app/src/main/java/com/justdataplease/spoon/
  data/       local/Firestore repositories and safe-decoding models
  domain/     weekly defaults, filtering, random selection, planner operations
  di/         runtime repository selection
  sync/       unique 90-day catalog-status check
  ui/         week, Explore, favorites, shopping, history, account, custom recipes,
              calendar, details, video, theme

tools/recipe_importer/
  crawl_catalog.py    complete permission-gated Akis Greek crawler
  crawl_argiro.py     complete permission-gated Argiro Greek crawler
  crawl_gastronomos.py complete permission-gated Gastronomos Greek crawler
  build_local_catalog.py deterministic indexed SQLite catalog builder
  full_schema.py      rich normalization and Firestore projections
  import_catalog.py   dry-run-first validator and Admin SDK importer
  inspect_recipe.py   one-URL metadata inspector

tools/launcher_icon/
  icons.py            launcher icon source: previews concepts, exports the vector drawables
```

The main libraries are Jetpack Compose/Material 3, Hilt, Navigation Compose,
Android SQLite, Firebase Auth/Firestore, WorkManager, DataStore, Coil, and Kotlin
coroutines.

## Build and install

Prerequisites are Android Studio (or JBR 17), Android SDK 36.1, and an Android 8.0
(API 26) or newer device.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME

.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleRelease
```

For catalog, taxonomy, or filtering changes, also run the full catalog contract
on a connected Android emulator before distributing an APK:

```powershell
.\gradlew.bat connectedDebugAndroidTest
python -m pytest tools/recipe_importer/tests -q
python tools/recipe_importer/audit_catalog_quality.py
```

For changes to personal planning and preference validation, run the isolated
Firestore rules contract (a local demo project; no production writes):

```powershell
firebase emulators:exec --only firestore --project demo-spoon-planning `
  --config firebase.planning-test.json "python tools/test_firestore_planning_rules.py"
```

This executes production SQLite queries and Kotlin filters against all bundled
recipes, checks every published facet, repeated planning across every category,
cache eviction, saved-state reopening, and catalog replacement on upgrade.
The tests keep their catalog and personal preferences in an isolated namespace.
See [the device verification report](docs/device-catalog-audit-2026-09-05.md).

The optimized, signed personal release APK is delivered at `dist/spoon.apk`. To
install or upgrade it over USB:

```powershell
adb devices
adb install -r dist\spoon.apk
```

Alternatively, copy the APK to the phone, open it, and approve Android's
per-app “install unknown apps” prompt.

The public catalog remains available offline regardless of Firebase state.
Without `app/google-services.json`, account backup and cross-phone sync are
unavailable. With a valid Firebase configuration, queued personal changes sync
automatically after connectivity returns.

## Firebase configuration

Use the dedicated Firebase Android app `com.spoon.app` in project
`spoontheplanner`. Do not copy another application's `google-services.json`:
the package and Firebase app registration must match. Keep the downloaded file at
`app/google-services.json`; that path is ignored by Git.

Firebase setup requires:

1. Anonymous and Email/Password sign-in enabled in Firebase Authentication.
2. A Firestore database in the selected European location.
3. The checked-in rules and indexes deployed:

   ```powershell
   firebase use spoontheplanner
   firebase deploy --only firestore
   ```

4. The authorized catalog imported with the backend tooling below.

Before a production Play release, register the release signing SHA-256 and
configure Firebase App Check with Play Integrity. App Check enforcement is a
Firebase project setting and is separate from the checked-in authorization rules.

## Firestore layout

```text
spoon_recipes/{recipeId}                 backend catalog summary/archive
spoon_recipe_details/{recipeId}          backend normalized detail/archive
spoon_recipe_payloads/{recipeId}         source audit envelope; backend only
spoon_catalog/status                     last complete backend import checkpoint
spoon/{uid}/mealPlans/{yyyy-MM-dd}       owner-only daily plan/filter/completion
spoon/{uid}/favorites/{recipeId}         owner-only favorite marker
spoon/{uid}/preferences/meal              food exclusions, weekday defaults, favorites-only source
spoon/{uid}/shoppingItems/{itemId}       owner-only shopping-list item
spoon/{uid}/recipeNotes/{recipeId}       owner-only private recipe note
spoon/{uid}/customRecipes/{recipeId}     owner-only manually authored recipe
spoon/{uid}/cookedHistory/{eventId}      owner-only immutable cooked event (legacy date ids readable)
```

Catalog writes are denied to mobile clients. Raw source payload reads are also
denied; only trusted Admin SDK tooling can access them. The Android app does not
query `spoon_recipes` or `spoon_recipe_details`; those collections remain the
backend publication/archive. All personal data is isolated by Firebase UID,
cached persistently on-device, and validated by field/type/range allowlists in
`firestore.rules` before synchronization.

## Complete Greek recipe catalog

The repository includes permission-gated full crawlers for Akis Petretzikis,
Argiro, and Gastronomos because the operator has confirmed authorization for this
personal use. Each provider is discovered from its official Greek sitemap and,
where available, API, normalized into the same auditable planner categories, and
emitted as three independently size-checked Firestore projections. Generated
catalog data is private and ignored by Git.

Install and test the tooling:

```powershell
python -m pip install --require-hashes -r tools/recipe_importer/requirements.lock
python -m pytest tools/recipe_importer/tests
```

The checked-in lock pins and hashes every direct and transitive package used by
the credentialed quarterly workflow. `requirements.txt` remains the human-edited
input when intentionally refreshing that lock.

Crawl, validate, then import the exact manifest/catalog pair:

```powershell
python tools/recipe_importer/crawl_catalog.py --i-have-permission
python tools/recipe_importer/crawl_argiro.py --i-have-argiro-permission
python tools/recipe_importer/crawl_gastronomos.py --i-have-gastronomos-permission

python tools/recipe_importer/import_catalog.py `
  tools/recipe_importer/output/akis-greek-full.jsonl `
  --manifest tools/recipe_importer/output/akis-greek-full.manifest.json `
  --i-have-permission

python tools/recipe_importer/import_catalog.py `
  tools/recipe_importer/output/akis-greek-full.jsonl `
  --manifest tools/recipe_importer/output/akis-greek-full.manifest.json `
  --i-have-permission --commit --project-id spoontheplanner

python tools/recipe_importer/import_catalog.py `
  tools/recipe_importer/output/argiro-greek-full.jsonl `
  --manifest tools/recipe_importer/output/argiro-greek-full.manifest.json `
  --i-have-permission --i-have-argiro-permission

python tools/recipe_importer/import_catalog.py `
  tools/recipe_importer/output/argiro-greek-full.jsonl `
  --manifest tools/recipe_importer/output/argiro-greek-full.manifest.json `
  --i-have-permission --i-have-argiro-permission --commit `
  --project-id spoontheplanner

python tools/recipe_importer/import_catalog.py `
  tools/recipe_importer/output/gastronomos-greek-full.jsonl `
  --manifest tools/recipe_importer/output/gastronomos-greek-full.manifest.json `
  --i-have-permission --i-have-gastronomos-permission

python tools/recipe_importer/import_catalog.py `
  tools/recipe_importer/output/gastronomos-greek-full.jsonl `
  --manifest tools/recipe_importer/output/gastronomos-greek-full.manifest.json `
  --i-have-permission --i-have-gastronomos-permission --commit `
  --project-id spoontheplanner

python tools/recipe_importer/build_local_catalog.py
```

The catalog builder validates all three complete artifacts and their manifests,
then deterministically writes the indexed Android asset. It records recipe counts
and content hashes so a truncated, stale, or mismatched input cannot silently
become an APK catalog.

The crawlers identify themselves exactly as
`PeltesSpoonRecipeImporter/1.0 (+mailto:hey@spoon.gr)`, read `robots.txt`, restrict
themselves to same-site HTTPS, wait at least one second globally between requests,
honor retries/`Retry-After`, and resume through SQLite. Any failed recipe prevents
a complete artifact. The importer recomputes counts and hashes, fully replaces
normalized documents, tombstones disappeared active IDs instead of deleting them,
and writes `spoon_catalog/status` only after every batch succeeds.

See [the importer guide](tools/recipe_importer/README.md) for the schema, failure
semantics, resume/incremental options, and secure identity setup.

## Quarterly refresh

Two independent mechanisms handle freshness:

- Android enqueues `spoon-quarterly-catalog-freshness` with WorkManager about
  every 90 days. It authenticates and reads only `spoon_catalog/status`; it never
  crawls the publisher or triggers an import. This metadata check is separate
  from normal catalog browsing, which performs zero Firestore recipe reads.
- [The quarterly GitHub Actions workflow](.github/workflows/quarterly-catalog-refresh.yml)
  refreshes Akis at 03:17 UTC on January 1, April 1, July 1, and October 1, then
  Argiro at the same time on day 2 and Gastronomos on day 3. Splitting providers
  bounds each run's write burst and isolates provider failures; it does not make
  the imports cost-free. A full provider import with `N` catalog records
  and `R` newly retired IDs performs `3N + 3R` recipe-document writes plus two
  status writes, and reads the provider's active summaries for retirement
  inventory. Review current Firestore pricing and quotas and configure a billing
  budget/alerts before enabling scheduled imports. Scheduled jobs test, crawl,
  validate, authenticate with short-lived Google OIDC, and import. A manual
  dispatch runs the three providers as isolated parallel matrix jobs and always
  stops after crawl and validation; those jobs have no OIDC permission, Firebase
  variables, authentication step, or import step.

The workflow has read-only repository access, pins every action to an immutable
commit, uploads audit reports but not recipe data, and uses no stored JSON key.
The `recipe-catalog-production` GitHub environment is restricted to `main`; the
three non-secret resource values are repository Actions variables documented in
the importer guide. The Google
identity checks immutable GitHub repository ID `1355319945` and owner ID
`117316710`, as well as `justdataplease/spoon`, `main`, and the scheduled event.

The dedicated importer service account receives only a custom
`spoonCatalogWriter` role with Firestore entity create/get/list/update permissions
and no delete permission. Its recurring IAM condition permits requests only on the
first three days of January, April, July, and October from 03:00 through 09:59 UTC
(`request.time.getHours() >= 3 && request.time.getHours() < 10`). Each scheduled
job has a six-hour runtime limit; this window covers a 03:17 start plus buffer for
possible GitHub schedule-delivery delay.
Firestore IAM cannot scope this binding to particular collections, so during that
window these permissions apply across every Firestore document in the project.
Outside the window, the binding grants no catalog data access.

A quarterly backend import does not replace the catalog already inside an
installed app. After a successful three-provider refresh, run
`python tools/recipe_importer/build_local_catalog.py`, verify the recorded counts
and hashes, build the optimized release, and distribute a new `dist/spoon.apk`.

## Privacy and operating notes

- The app contains no advertising profile. Anonymous Firebase IDs exist only to
  isolate each user's data before an optional email account is linked.
- Personal changes are accepted offline through Firestore's persistent local
  cache and synchronize/backup on reconnect. Public recipe text and metadata are
  always served from the bundled SQLite database.
- Unknown ratings do not pass a positive rating filter. Unknown preparation time
  does not pass a positive time cap.
- Recipe pages preserve source attribution and a link to the canonical publisher
  page.
