# Τι θα φάμε; (Spoon)

«Τι θα φάμε;» is an Android meal planner written in Kotlin and Jetpack Compose. The entire
user interface is Greek. It builds a weekly food plan, proposes a matching recipe
for each day, and makes it easy to reroll, filter, save, replace, and mark meals as
cooked.

The Android application ID is `com.spoon.app`. Its Greek recipe catalog from seven publishers is bundled as an indexed SQLite
database, while Firebase
project `spoontheplanner` provides account-backed synchronization for personal
data.

## App features

- Monday-to-Sunday planning with default main-food groups such as όσπρια, κοτόπουλο,
  λαχανικά, κρέας, ψάρι, βρώμικο, and ζυμαρικά/ρύζι.
- Preferences let you choose default main, side, and dessert categories for each
  weekday, Monday through Sunday, and generate new proposals only from favorites.
  Matching favorites can repeat across days and weeks; missing categories show an unavailable recipe card
  with an action to edit that day's category or filters.
- The main recipe stays visible on each day card. The optional «Πλήρες μενού»
  button opens a dedicated screen with saved main, side, and dessert courses.
  Each course supports filters, favorites, replacement, locks, and cooking history.
  Reopening the screen preserves saved selections. Weekly new proposals refresh
  the full menu, keeping each locked or already-cooked course independently. New
  courses respect the selected source and food preferences; missing matches show
  unavailable. The preset week uses main-dish categories for mains, matching
  accompaniments for sides, and desserts for the sweet course.
- Lock a recipe at the top of its day card to keep it during weekly regeneration.
  Already-cooked meals are automatically kept too. Locks persist offline and sync
  with the plan; unlocking makes the day eligible for weekly regeneration again.
- Independent random reroll for one day or the whole week. Every matching recipe
  has the same selection probability across the seven publishers and custom
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
- The quick filter means known total time strictly under 30 minutes, including
  preparation, cooking, and waiting. A dedicated «Χριστουγεννιάτικη» filter uses
  the same Christmas occasion identity across publishers. Common ingredient and
  facet aliases are shared with the importer and checked against Android.
- All seven publishers are selected by default in Settings. Deselect any source
  to exclude it from Explore matches and new meal suggestions; existing saved
  meals remain intact. Source choices persist offline; cross-phone synchronization
  requires the accompanying source-preference Firestore rules. Their production
  deployment is pending approval; source exclusions already work locally.
- An Android home-screen widget shows today's planned main recipe with only its
  picture and name. Tapping opens that recipe. See [widget details](docs/today-recipe-widget.md).
- A discreet personal-use and linked publisher credit appears at the start of
  each recipe. The Settings «Σχετικά» button lists all publishers and credits.
- Photo-rich cards and a complete recipe page with gallery, descriptions, timing,
  difficulty, servings, rating distribution, grouped ingredients and conversions,
  numbered method steps, tips, nutrition, equipment, publication metadata, and the
  canonical source link.
- Share any built-in catalog recipe with another «Τι θα φάμε;» user from the recipe's
  «Κοινοποίηση συνταγής» button. The Android share sheet sends a clickable HTTPS
  link that opens the same recipe in «Τι θα φάμε;», including on a cold launch. Both
  phones need version 0.8.6 or later and a catalog containing that recipe; the four
  new publishers require version 0.9.0 or later. The recipe is read from the recipient's
  offline catalog; private notes and personal recipes are never included.
- User-initiated inline video for YouTube, Vimeo, and direct HTTPS video files.
  Nothing autoplays, unsafe URLs/navigation are blocked, loading failures are shown
  instead of a blank player, and an external fallback remains available.
- The bundled Greek recipe catalog works offline from indexed local SQLite. Explore loads 24 rows at a time, so opening and filtering the catalog
  does not download every recipe or issue Firestore recipe reads.
- Optional Firebase email/password registration and sign-in, sign-out, and password
  reset. The app does not create anonymous Firebase accounts. Every personal feature
  works before sign-in. Creating an account or signing in automatically attaches
  existing device data without an export/import step. Interrupted uploads remain
  queued locally. Production signup activation and cloud history rules require
  the pending approvals described below. Account metadata checks are throttled to
  once per 15 seconds.

Strict filters are never silently relaxed. If no recipe matches a valid request,
the day is saved as unavailable, keeping its category and filters for the next
attempt. Adding a matching favorite allows the day to recover. Changing weekday
defaults updates the corresponding saved, unlocked and unfinished courses of the
selected week; completed meals and their cooking history remain intact during
preference reconciliation.

## Architecture

All public recipe summaries and full details are read from the bundled indexed
SQLite catalog. Search, filters, 24-row Explore pages, random planning, favorites
lookup, and recipe details therefore remain available without a network
connection and produce zero reads from the Firestore recipe collections. Images
and videos keep their publisher HTTPS URLs and are fetched and cached only when
needed, so media that has not already been cached still requires connectivity.

Personal state is saved first in a separate, durable SQLite database: plans,
favorites, complete cooked history, shopping items, notes, custom recipes/photos,
and preferences. Each edit and its pending cloud revision commit atomically before
success is shown. No Firebase account, token, initial bootstrap, or network is
required for personal actions. Guest data and each signed-in account have separate
storage. Existing local preferences, old plan queues, and Firebase caches migrate
on upgrade; source files are retained.

Successful email registration or sign-in automatically transfers guest data, or older anonymous
account data, into the account. History collisions are preserved, newer destination
records are reconciled, and guest deletions do not delete independent account data.
A durable transfer intent recovers an interrupted anonymous-to-email sign-in.
Firebase uploads start immediately when authenticated server reads are available;
large histories upload in bounded batches. Only successful server acknowledgements
clear matching queued revisions. The account screen distinguishes device storage,
syncing, waiting, and acknowledged synchronization. Password/account errors remain
possible for explicit account actions; they do not gate local personal features.

**Cloud activation pending:** Firebase currently disables new-user signup project-wide.
The explicit production setting approval is pending. Archived-history imports and safe history retries
require approval and deployment of the exact owner-only change in
[the history sync rules proposal](docs/local-history-sync-rules-proposal.md).
Production also rejects the current preference fields and drinks category;
[the exact validator update](docs/current-app-sync-rules-proposal.md) awaits approval.
The APK retains rejected changes locally and retries; it does not label them synced. Pushing an APK does not
deploy these rules. The owner-scoped rules fix is implemented and emulator-tested; production deployment still requires explicit approval. See [0.11.1 verification](docs/release-0.11.1.md).

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
  crawl_tsoulis.py    permission-gated Tsoulis sitemap crawler
  crawl_lucacos.py    permission-gated Lucacos sitemap crawler
  crawl_funkycook.py  permission-gated Funky Cook post crawler
  crawl_cookpad.py    resumable public Greek Cookpad link-graph crawler
  build_local_catalog.py deterministic indexed SQLite catalog builder
  full_schema.py      rich normalization and Firestore projections
  ingredient_taxonomy.py shared ingredient identities for Firestore projections
  migrate_firestore_taxonomy.py guarded repair of existing public taxonomy fields
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
before distributing an APK. Use the connected Gradle task below on a disposable
Android emulator. For an existing multi-user AVD, follow the explicit test-user
install/instrument commands in [Android device validation](docs/android-device-validation.md);
Gradle's automatic cleanup uninstalls its app packages across users.

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
See [the device verification report](docs/device-catalog-audit-2026-09-10.md).

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
unavailable. With valid Firebase configuration, an email account, and deployed compatible
rules, queued personal changes sync automatically
after connectivity returns. Local personal use requires neither an account nor
Firebase configuration.

## Recipe sharing links

Public recipe links use `https://justdataplease.github.io/spoon/recipe/<id>`.
Android verifies this domain using the published association with `com.spoon.app`
and the existing APK signing certificate. The `website/` directory contains the
static site and association, deployed to the root of the separate
`justdataplease/justdataplease.github.io` repository. See
[the website deployment notes](website/README.md).

When a messaging app keeps links in its own browser, the landing page offers
«Άνοιγμα στο Τι θα φάμε;». If the app is absent or outdated, it also links to the current
APK. After upgrading, tap the original recipe link again. No account identifier
or personal content is placed in these links, and opening one does not add a
favorite or change a meal plan. Manually created recipes remain owner-private.

Links are validated before catalog lookup. Unknown recipes show a Greek update
message; malformed or private IDs are rejected. Incoming links work while the app
is running, survive activity recreation, and can be dismissed while loading.

## Firebase configuration

For your own distribution, create a Firebase project and register an Android app
with package `com.spoon.app`. Download that project's `google-services.json` to
`app/google-services.json`; the path is ignored by Git. This file contains public,
project-specific Firebase client configuration. Never place a service-account JSON
key or other administrator credential in the repository or APK.

The upstream maintainer uses project `spoontheplanner`. Forks and independent
distributions should use their own Firebase project and configuration so account,
quota, billing, rules, and data ownership remain under their control.

Firebase setup requires:

1. For a new independent project, enable Email/Password sign-in and end-user signup
   in Firebase Authentication so users can register in the app. Anonymous sign-in
   is unnecessary. This setup guidance does not change the upstream production
   project; its signup activation remains pending the approval described above.
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

python tools/recipe_importer/build_local_catalog.py `
  --partial-artifact build/recipe-importer/cookpad/cookpad-full.jsonl `
  build/recipe-importer/cookpad/cookpad-full.manifest.json
```

The catalog builder validates each selected artifact and its manifest,
then deterministically writes the indexed Android asset. It records recipe counts
and content hashes so a truncated, stale, or mismatched input cannot silently
become an APK catalog.

The crawlers identify themselves, read `robots.txt`, restrict themselves to
allowed publisher HTTPS hosts, honor publisher crawl delays and `Retry-After`,
pace requests independently per source, and resume through SQLite. Any failed recipe prevents
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
installed app. After a successful refresh, rebuild the bundled catalog using the source pairs
documented in [the download workflow](docs/new-source-downloads.md), verify the recorded counts
and hashes, build the optimized release, and distribute a new `dist/spoon.apk`.

## Privacy and operating notes

- The app contains no advertising profile and creates no anonymous Firebase
  accounts. Personal cloud data is available after optional email registration or
  sign-in, subject to the production activation and rules described above.
- Personal changes commit to an independent device SQLite journal before any
  upload. They remain usable if authentication or cloud access fails. Account data
  stays separated on sign-out; only guest/anonymous account upgrades transfer data.
  Public recipe text and metadata always come from the bundled catalog.
- Unknown ratings do not pass a positive rating filter. Unknown preparation time
  does not pass a positive time cap.
- Recipe pages preserve source attribution and a link to the canonical publisher
  page.

New source onboarding is documented in [the Claude project skill](.claude/skills/add-recipe-source/SKILL.md), invoked with `/add-recipe-source <publisher URL>`. Download coverage and resumable commands for the four additional publishers are in [the source download notes](docs/new-source-downloads.md).
