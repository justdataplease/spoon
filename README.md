# Τι να φάω; (Spoon)

Greek-first Android meal planner built with Kotlin and Jetpack Compose. It creates a
seven-day plan, keeps each day's food category and constraints, proposes a random
matching recipe, and remembers favorites and cooked meals.

## What works

- A Monday–Sunday plan with sensible defaults: legumes, poultry, vegetables, meat,
  fish, street food, and pasta/rice.
- A separate reroll button on every day, plus a whole-week reroll.
- Per-day filters for main category, minimum rating on a 0–10 scale, maximum hands-on
  preparation time, and difficulty.
- A transparent effort index derived from structural preparation and step counts:
  unknown when both counts are zero, easy for at most one preparation and 1–5
  steps, involved for 3+ preparations or 10+ steps, and moderate otherwise.
- Favorites, cooked/not-cooked state, previous/next weeks, and a month calendar.
- Tappable week and favorite cards open a recipe details page with attribution,
  rating, preparation/total time, preparation count, effort explanation, favorite
  control, and a button that opens the publisher's canonical recipe page.
- A persistent on-device demo catalog so the app is usable before Firebase setup.
- Anonymous Firebase Auth and Firestore sync when a valid configuration is present.
- A runtime status banner distinguishes local storage, cloud connection, connecting,
  and actionable Firebase errors; configured Firebase failures never silently fall
  back to the demo catalog.
- A unique, network-constrained Android WorkManager check of server-owned catalog
  status approximately every 90 days.
- A trusted, dry-run-first metadata importer under `tools/recipe_importer`.

The UI does not reproduce recipe instructions, ingredients, descriptions, or
videos. The bundled
[`food_hero.png`](app/src/main/res/drawable-nodpi/food_hero.png) is the default,
loading, and error artwork. A production catalog may supply a remote `imageUrl`
only when written photo-display rights exist and the importer is explicitly run
with `--allow-licensed-images`; otherwise the bundled artwork remains visible.
The details page opens `sourceUrl` in the browser for the actual recipe.

## Run the Android app

Prerequisites: Android Studio/JBR 17+ and Android SDK 36.1.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

Without `app/google-services.json`, Spoon automatically uses its local persistent
repository. No code change is needed.

Production deployment still requires two environment-owned inputs that are not
checked into this repository: the matching `app/google-services.json` for cloud
mode and a private release signing key/configuration supplied by the release CI or
Play App Signing. The repository intentionally contains neither secret.

## Connect Firebase

1. Create a new Firebase Android app with package name
   `com.justdataplease.spoon`. Do not reuse another app's configuration.
2. Put its client configuration at `app/google-services.json` (the path is ignored
   by Git).
3. In Firebase Authentication, enable Anonymous sign-in.
4. Create a Firestore database and deploy the checked-in rules:

   ```powershell
   firebase use YOUR_PROJECT_ID
   firebase deploy --only firestore
   ```

   The rules allow signed-in reads of only the normalized recipe catalog and its
   exact status document. User writes are limited to that authenticated user's
   exact meal-plan and favorite paths, with document-ID, field allowlist, type,
   enum, length, and numeric-range checks. Admin SDK importer writes bypass these
   client rules.
5. Before production release, configure Firebase App Check with Play Integrity:
   add and initialize the Play Integrity provider in the release app, register
   the release package/signing SHA-256 in Firebase, use the debug provider only
   for local development or CI, monitor App Check metrics, and then enable Cloud
   Firestore enforcement in the Firebase console. This repository does not
   enable enforcement or include provider initialization because the Firebase
   project and release signing identity are deployment-owned inputs. App Check
   is a separate attestation/enforcement layer; it cannot be expressed in
   `firestore.rules` and does not replace Authentication or these authorization
   rules.
6. Supply authorized recipe metadata with the importer described in
   [`tools/recipe_importer/README.md`](tools/recipe_importer/README.md).

The app chooses the Firestore implementation at startup when its Firebase config
exists. Recipe metadata is globally readable to signed-in users but never writable
from the APK. Each user's plans and favorites are owner-only.
Cloud catalog reads query `active == true` and `language == "el"` in Firestore and
repeat those checks defensively after decoding.

## Firestore layout

```text
spoon_recipes/{recipeId}                 # trusted importer writes; clients read
spoon_catalog/status                     # importer writes; client worker reads
spoon/{uid}/mealPlans/{yyyy-MM-dd}       # owner-only plan + filter + completion
spoon/{uid}/favorites/{recipeId}         # owner-only favorite marker
```

Every Firestore model has defaults for safe decoding. Recipe records store compact
selection facts such as title, category, rating, `prepMinutes`, `cookMinutes`,
`totalMinutes`, `preparationCount`, `stepCount`, `active`, attribution, language,
canonical source URL, and an optional licensed `imageUrl`. Cooking time
contributes to the details page's total-time fallback when an explicit total is
unavailable. The meal plan stores a title snapshot so history remains
understandable even if a catalog item is later withdrawn.

All publisher-derived production records are Greek-only: the importer records
`language: "el"`, rejects `/en/recipe/...`, and accepts only Greek recipe URLs.
The bundled demo catalog is a separate local fallback, not a publisher import.

## Recipe catalog and publisher permission

The current Akis Petretzikis terms reserve site content and explicitly restrict
copying and storage without prior written consent. For that reason this repository
does not contain an all-site crawler or copied recipe content.

The included tooling supports a compliant workflow after permission is obtained:

```powershell
python -m pip install -r tools/recipe_importer/requirements.txt
python -m pytest tools/recipe_importer/tests

# Safe default: validate an authorized catalog without connecting to Firebase.
python tools/recipe_importer/import_catalog.py authorized-catalog.jsonl

# Explicit write after review.
python tools/recipe_importer/import_catalog.py authorized-catalog.jsonl `
  --commit --project-id YOUR_PROJECT_ID

# Use this additional flag only when written rights cover remote photo display.
python tools/recipe_importer/import_catalog.py authorized-catalog.jsonl `
  --allow-licensed-images --commit --project-id YOUR_PROJECT_ID
```

`inspect_recipe.py` can inspect one explicitly supplied, authorized recipe URL. It
checks `robots.txt`, rate-limits requests, discards full-content fields, and requires
an acknowledgement flag. Robots permission does not replace publisher permission.

## Catalog freshness: device check vs backend ingestion

These are two independent operations:

1. **Android status check.** `SpoonApplication` enqueues the unique periodic work
   `spoon-quarterly-catalog-freshness` with `ExistingPeriodicWorkPolicy.KEEP`.
   WorkManager runs it on an inexact, constraint-aware 90-day interval when a
   network is available. In Firebase mode it authenticates anonymously and reads
   only `spoon_catalog/status` with a server-only read. A valid checkpoint must
   contain `language: "el"`, a positive bounded `recipeCount`, a server
   `lastImportedAt` timestamp, and matching lowercase SHA-256 hash/version fields.
   The worker stores the last successful check, last attempt, last-known-good
   import time/count/hash/version, and any explicit failure in private app
   preferences. Current/stale state is derived from that persisted successful
   timestamp, so a missing status document or failed request cannot reset the
   catalog to successful zero values. Missing status and transient transport
   errors retry with WorkManager backoff; malformed status and permanent errors
   fail the attempt while preserving the last-known-good metadata. It never
   visits the recipe website, imports records, or triggers the backend job.
   Without a Firebase configuration it records only the bundled demo count
   locally.
2. **Backend catalog ingestion.** Approximately quarterly, a trusted Cloud
   Scheduler/Cloud Run or reviewed CI workflow receives a newly supplied,
   authorized Greek-only catalog, runs the importer dry-run, and then explicitly
   commits the same reviewed artifact. A retired recipe must remain in the feed
   with `active: false`; omission does not delete or deactivate an existing
   document, and the Android catalog filters inactive records. Each supplied
   recipe document is fully replaced with normalized metadata so legacy,
   disallowed, or stale fields do not survive.
   After all recipe batches succeed, the importer updates `spoon_catalog/status`
   with `language`, `recipeCount`, the deterministic catalog hash/version, and
   a server `lastImportedAt` timestamp.

Android timing can be delayed by device constraints and is not the ingestion
schedule. The backend cadence, credentials, approval steps, and failure semantics
are documented in
[`tools/recipe_importer/README.md`](tools/recipe_importer/README.md#quarterly-authorized-refresh).

## Project structure

```text
app/src/main/java/com/justdataplease/spoon/
  data/       Firestore/local repositories, models, demo metadata
  domain/     strict filtering, random selection, weekly defaults, planner
  di/         Hilt backend selection
  sync/       unique 90-day WorkManager catalog-status check
  ui/         Compose screens, recipe details, calendar, filters, theme, ViewModel
tools/recipe_importer/
  inspect_recipe.py   single-URL metadata inspection
  import_catalog.py   validated Firestore batch importer
```

## Privacy and operating notes

- Anonymous Firebase user IDs isolate cloud data; there is no profile or advertising
  identity in this version.
- Firestore's Android SDK supplies offline caching when cloud mode is active. Local
  mode persists plans and favorites in private app storage.
- A strict filter with no match leaves the current choice unchanged and tells the
  user. Filters are never silently relaxed.
- Ratings with no source value are treated as unknown/zero, so they do not pass a
  positive rating threshold.
