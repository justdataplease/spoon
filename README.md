# Τι να φάω; (Spoon)

Spoon is an Android meal planner written in Kotlin and Jetpack Compose. The entire
user interface is Greek. It builds a weekly food plan, proposes a matching recipe
for each day, and makes it easy to reroll, filter, save, replace, and mark meals as
cooked.

The production catalog is backed by Firebase project `spoontheplanner`; the
Android application ID is `com.spoon.app`.

## App features

- Monday-to-Sunday planning with default main-food groups such as όσπρια, κοτόπουλο,
  λαχανικά, κρέας, ψάρι, βρώμικο, and ζυμαρικά/ρύζι.
- Independent random reroll for one day or the whole week.
- Per-day constraints for category, difficulty, minimum rating on a 0–10 scale,
  and maximum hands-on preparation time.
- A clear «Ευκολάκι» effort index based on preparation sections and method steps:
  unknown when both are absent, easy for at most one preparation and 1–5 steps,
  demanding for at least three preparations or ten steps, and moderate otherwise.
- Favorites, cooked/not-cooked tracking, a dedicated cooking history, previous/next
  weeks, and a month calendar.
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
- A persistent local Greek demo catalog when no Firebase configuration is present.
- Anonymous Firebase Authentication plus optional email/password account linking,
  sign-in, sign-out, and password reset. Linking upgrades the same UID so the
  owner's plans, favorites, history, notes, shopping list, and custom recipes remain
  attached to the account and sync across phones.

Strict filters are never silently relaxed. If no recipe matches, the current plan
is preserved and the app explains that no alternative was found.

## Architecture

The app streams lean, active Greek recipe summaries for planning and Explore, then
fetches the rich details document only when a recipe is opened. This keeps the
normal catalog read small while retaining full detail pages.

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
  full_schema.py      rich normalization and Firestore projections
  import_catalog.py   dry-run-first validator and Admin SDK importer
  inspect_recipe.py   one-URL metadata inspector
```

The main libraries are Jetpack Compose/Material 3, Hilt, Navigation Compose,
Firebase Auth/Firestore, WorkManager, DataStore, Coil, and Kotlin coroutines.

## Build and install

Prerequisites are Android Studio (or JBR 17), Android SDK 36.1, and an Android 8.0
(API 26) or newer device.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME

.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

The installable debug APK is generated at
`app/build/outputs/apk/debug/app-debug.apk` and the delivered copy is kept at
`dist/spoon-debug.apk`. To install it over USB:

```powershell
adb devices
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Alternatively, copy the APK to the phone, open it, and approve Android's
per-app “install unknown apps” prompt. A production release still needs a private
release signing identity or Play App Signing; neither is stored in this repository.

Without `app/google-services.json`, the build automatically selects the local
repository. With a Firebase configuration present, connection/configuration
failures are shown explicitly and do not silently fall back to demo data.

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
spoon_recipes/{recipeId}                 lean summary; signed-in catalog reads
spoon_recipe_details/{recipeId}          rich detail; exact active Greek get only
spoon_recipe_payloads/{recipeId}         source audit envelope; backend only
spoon_catalog/status                     last complete backend import checkpoint
spoon/{uid}/mealPlans/{yyyy-MM-dd}       owner-only daily plan/filter/completion
spoon/{uid}/favorites/{recipeId}         owner-only favorite marker
spoon/{uid}/shoppingItems/{itemId}       owner-only shopping-list item
spoon/{uid}/recipeNotes/{recipeId}       owner-only private recipe note
spoon/{uid}/customRecipes/{recipeId}     owner-only manually authored recipe
spoon/{uid}/cookedHistory/{yyyy-MM-dd}   owner-only cooked-meal history
```

Catalog writes are denied to mobile clients. Raw source payload reads are also
denied; only trusted Admin SDK tooling can access them. All user data is isolated
by the Firebase UID and validated by field/type/range allowlists in
`firestore.rules`.

Every Firestore model has safe defaults. The repository queries only
`active == true` and `language == "el"` summaries and validates those values
again after decoding. Rich details are fetched server-side by a validated document
ID and must also be active Greek content.

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
```

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
  crawls the publisher or triggers an import.
- [The quarterly GitHub Actions workflow](.github/workflows/quarterly-catalog-refresh.yml)
  refreshes Akis at 03:00 UTC on January 1, April 1, July 1, and October 1, then
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
first three days of January, April, July, and October from 03:00 through 07:59 UTC.
Firestore IAM cannot scope this binding to particular collections, so during that
window these permissions apply across every Firestore document in the project.
Outside the window, the binding grants no catalog data access.

## Privacy and operating notes

- The app contains no advertising profile. Anonymous Firebase IDs exist only to
  isolate each user's data before an optional email account is linked.
- Firestore provides offline caching in cloud mode; local mode persists its state
  in app-private storage.
- Unknown ratings do not pass a positive rating filter. Unknown preparation time
  does not pass a positive time cap.
- Recipe pages preserve source attribution and a link to the canonical publisher
  page.
