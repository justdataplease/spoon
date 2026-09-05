# Permission-gated full Greek recipe catalog pipeline

This directory contains the crawlers, normalizers, validators, deterministic
local-catalog builder, and Firestore importer used by Spoon. It is designed to
capture every currently published
canonical Greek recipe exposed by `akispetretzikis.com`, `argiro.gr`, and
`gastronomos.gr`, including the details and filter taxonomy needed by the app.

Run the full-content commands only when the publisher has authorized the intended
collection, storage, media display, and refresh cadence. The required permission
switches—`--i-have-permission` for Akis, `--i-have-argiro-permission` for Argiro,
and `--i-have-gastronomos-permission` for Gastronomos—are explicit operational
guards; they do not create permission by themselves. The generated catalog,
checkpoints, reports, and credentials are ignored by Git.

## Multiple recipe providers

Records include additive provenance fields: source (legacy publisher domain),
sourceKey (`akis`, `argiro`, or `gastronomos`), string providerRecipeId, legacy
numeric sourceRecipeId, sourceUrl/canonicalUrl, and Greek sourceName. Existing
Akis document IDs remain numeric so saved plans, favorites, history, and notes do
not break. Other providers are namespaced (`argiro_...` and `gastronomos_...`),
with a deterministic hash fallback only when a native ID is not document-safe.

Full imports are deliberately single-provider. Retirement inventory is filtered
by source (including legacy Akis inference), so refreshing any one provider cannot
tombstone another provider's recipes. `spoon_catalog/status` remains
app-compatible and gains per-source metadata;
`spoon_catalog/status_{sourceKey}` stores each provider's own counts and
freshness.

### Argiro provider and permission requirement

crawl_argiro.py reads all recipe-sitemap XML files declared by the sitemap
index, accepts only strict Greek recipe URLs on approved HTTPS hosts, honors
robots.txt, rate-limits globally to at least one request per second, retries
transient responses, resumes unchanged URL/last-modified pairs in SQLite, and
extracts JSON-LD Recipe data plus narrowly scoped page metadata. Tests contain
synthetic content only.

Run or schedule it only while the operator's Argiro/ALTER EGO permission covers
systematic recipe text, image/video metadata, Firestore storage, personal-app
display, and the requested refresh cadence. The command's permission switch is
an explicit operational acknowledgement, not a substitute for that permission.

Run these commands after confirming that authorization:

    python tools/recipe_importer/crawl_argiro.py --i-have-argiro-permission
    python tools/recipe_importer/import_catalog.py tools/recipe_importer/output/argiro-greek-full.jsonl --manifest tools/recipe_importer/output/argiro-greek-full.manifest.json --i-have-permission --i-have-argiro-permission
    python tools/recipe_importer/import_catalog.py tools/recipe_importer/output/argiro-greek-full.jsonl --manifest tools/recipe_importer/output/argiro-greek-full.manifest.json --i-have-permission --i-have-argiro-permission --commit --project-id spoontheplanner

Outputs are argiro-greek-full.jsonl, argiro-greek-full.manifest.json,
argiro-greek-full.failures.json, and the private resumable
.argiro-greek-full.checkpoint.sqlite3.

### Gastronomos provider and permission requirement

`crawl_gastronomos.py` discovers the strict recipe URLs declared by the official
Gastronomos sitemap index, verifies the publisher's recipe identities and
canonical redirects, and extracts Greek JSON-LD Recipe data plus scoped metadata.
It uses bounded parallel requests while enforcing the shared per-host delay and
the same fail-closed manifest/checkpoint rules.

Run or schedule it only while the operator's Gastronomos permission covers the
systematic recipe text, image/video metadata, Firestore storage, personal-app
display, and requested refresh cadence. The exact crawler acknowledgement is
`--i-have-gastronomos-permission`; a full-content import requires that flag plus
the general `--i-have-permission` acknowledgement.

Run these commands after confirming that authorization:

    python tools/recipe_importer/crawl_gastronomos.py --i-have-gastronomos-permission
    python tools/recipe_importer/import_catalog.py tools/recipe_importer/output/gastronomos-greek-full.jsonl --manifest tools/recipe_importer/output/gastronomos-greek-full.manifest.json --i-have-permission --i-have-gastronomos-permission
    python tools/recipe_importer/import_catalog.py tools/recipe_importer/output/gastronomos-greek-full.jsonl --manifest tools/recipe_importer/output/gastronomos-greek-full.manifest.json --i-have-permission --i-have-gastronomos-permission --commit --project-id spoontheplanner

Outputs are `gastronomos-greek-full.jsonl`,
`gastronomos-greek-full.manifest.json`,
`gastronomos-greek-full.failures.json`, and the private resumable
`.gastronomos-greek-full.checkpoint.sqlite3`.

## What the pipeline captures

`crawl_catalog.py` performs the complete, consistency-checked Akis run:

1. Reads `robots.txt` and follows only approved same-site HTTPS URLs.
2. Walks the sitemap and keeps only canonical Greek
   `/recipe/{numeric-id}/{slug}` entries.
3. Enumerates the official Greek recipe API as well. Sitemap IDs missing from the
   API fail the run; a newly published API recipe may be included while its
   sitemap entry is still lagging, and that difference is recorded in the
   manifest.
4. Reads all six Greek filter groups: diet, meal type, occasion, method,
   cuisine/country, and main ingredient. Every returned recipe ID must also exist
   in the Greek sitemap.
5. Fetches the full Greek detail payload for every discovered ID and derives the
   app schema: descriptions, ratings, time and difficulty, servings, image and
   video links, grouped ingredients and unit conversions, grouped method steps,
   tips, nutrition, equipment, author/publication fields, source links, and all
   filter labels.
6. Preserves the complete JSON-compatible source response in a separate audit
   projection. HTML fragments used by the normalized app model are converted to
   safe plain text, and its URL fields accept only safe HTTPS values.
7. Verifies uniqueness, publication state, active-ID equality, deterministic
   hashes, and a conservative 900 KiB limit independently for every Firestore
   projection.

Planner categories are derived only from exact publisher category ancestry and
official facet IDs. Recipe-title and free-text substring guessing is deliberately
excluded, so words such as `Τρουφάκια` cannot be misclassified as lentils.

Each HTTP client identifies itself exactly as
`PeltesSpoonRecipeImporter/1.0 (+mailto:hey@spoon.gr)`, enforces a global delay of
at least one second per provider host, honors `Retry-After`, retries only transient
statuses, limits response size, rejects cross-site redirects, and can resume from
a transactional SQLite checkpoint. Images and videos are referenced by their
authorized HTTPS URLs; the crawlers do not download or transform media files.

## Install and test

From the repository root, using Python 3.12 or a compatible supported Python 3:

```powershell
python -m pip install --require-hashes -r tools/recipe_importer/requirements.lock
python -m pytest tools/recipe_importer/tests
```

`requirements.lock` is the execution input for local and CI runs: every direct
and transitive distribution is version-pinned and hash-checked. Edit
`requirements.txt` only as the source for an intentional lock refresh.

Tests cover URL and robots safeguards, Greek-only discovery, retry/rate behavior,
resume and incremental runs, full-schema projections, Firestore size limits,
manifest verification, replacement writes, retirement, and status publication.

## Run a complete crawl

```powershell
python tools/recipe_importer/crawl_catalog.py --i-have-permission
python tools/recipe_importer/crawl_argiro.py --i-have-argiro-permission
python tools/recipe_importer/crawl_gastronomos.py --i-have-gastronomos-permission
```

The default private output directory is `tools/recipe_importer/output/`:

```text
akis-greek-full.jsonl                    complete normalized records
akis-greek-full.manifest.json            counts, taxonomy, hashes, sizes, provenance
akis-greek-full.failures.json            explicit success/failure report
.akis-greek-full.checkpoint.sqlite3      resumable payload and facet checkpoint
argiro-greek-full.jsonl                  complete normalized Argiro records
argiro-greek-full.manifest.json          Argiro completeness/audit contract
argiro-greek-full.failures.json          Argiro success/failure report
.argiro-greek-full.checkpoint.sqlite3    resumable Argiro checkpoint
gastronomos-greek-full.jsonl             complete normalized Gastronomos records
gastronomos-greek-full.manifest.json     Gastronomos completeness/audit contract
gastronomos-greek-full.failures.json     Gastronomos success/failure report
.gastronomos-greek-full.checkpoint.sqlite3 resumable Gastronomos checkpoint
```

The JSONL and manifest are written atomically only after a zero-failure run. The
failure report contains the stage or recipe ID for any incomplete run. Never
import after a crawler exit code other than zero.

## Build the bundled Android catalog

After all three complete artifacts and manifests have been produced, build the
indexed SQLite asset used by the app:

```powershell
python tools/recipe_importer/build_local_catalog.py
```

The builder deterministically combines all 20,861 active Greek recipes, including
their summaries, ingredients, steps, filter facets, source links, and image/video
URLs. It verifies the input counts and hashes against the manifests and records
the resulting catalog count and content hashes in SQLite metadata. A missing,
truncated, duplicate, or mismatched provider artifact fails the build.

The app queries this database directly: Explore returns 24 rows per page and all
catalog search, filters, random planning, and detail views generate zero Firestore
recipe reads. Recipe media is not copied into SQLite; HTTPS images and videos are
loaded and cached on demand. Consequently all recipe text and previously cached
media work offline, while uncached media needs a connection.

After the count/hash checks pass, build the optimized signed personal release and
place it at `dist/spoon.apk`. The APK can be installed or upgraded with:

```powershell
adb install -r dist\spoon.apk
```

Useful controls:

```powershell
# Add a temporary operator-known count assertion in addition to API/sitemap parity.
python tools/recipe_importer/crawl_catalog.py --i-have-permission `
  --expected-active-count EXPECTED_COUNT

# Reuse a prior complete catalog when sitemap last-modified values are unchanged,
# and carry recipes missing from the new sitemap forward as inactive records.
python tools/recipe_importer/crawl_catalog.py --i-have-permission `
  --previous-catalog path/to/previous-complete.jsonl

# Ignore checkpoint state for this discovery run.
python tools/recipe_importer/crawl_catalog.py --i-have-permission --no-resume
```

Running the default command again resumes matching recipe and facet work from the
checkpoint. If the sitemap discovery key or taxonomy changes, incompatible
checkpoint portions are reset automatically. When the default output already
exists it is also used as the previous catalog unless `--no-incremental` is set.
Argiro and Gastronomos likewise resume only records whose sitemap identity,
last-modified value, and parser contract still match; use their own `--no-resume`
flag for a deliberately clean run.

## Validate before Firestore

Always validate the exact catalog/manifest pair first. Dry-run mode initializes no
Firebase client and performs no Firestore writes:

```powershell
python tools/recipe_importer/import_catalog.py `
  tools/recipe_importer/output/akis-greek-full.jsonl `
  --manifest tools/recipe_importer/output/akis-greek-full.manifest.json `
  --i-have-permission

python tools/recipe_importer/import_catalog.py `
  tools/recipe_importer/output/argiro-greek-full.jsonl `
  --manifest tools/recipe_importer/output/argiro-greek-full.manifest.json `
  --i-have-permission --i-have-argiro-permission

python tools/recipe_importer/import_catalog.py `
  tools/recipe_importer/output/gastronomos-greek-full.jsonl `
  --manifest tools/recipe_importer/output/gastronomos-greek-full.manifest.json `
  --i-have-permission --i-have-gastronomos-permission
```

The importer recomputes the catalog, active-ID, summary, detail, and raw-payload
hashes. It also checks the schema version, record counts, zero-failure marker, and
document-size assertion from the manifest. A mismatch stops before Firebase is
opened.

After reviewing the dry-run result, commit the same immutable files using Google
Application Default Credentials:

```powershell
python tools/recipe_importer/import_catalog.py `
  tools/recipe_importer/output/akis-greek-full.jsonl `
  --manifest tools/recipe_importer/output/akis-greek-full.manifest.json `
  --i-have-permission `
  --commit `
  --project-id spoontheplanner
```

`--credentials path/to/service-account.json` is supported for a controlled local
environment, but a short-lived identity (developer ADC locally or Workload
Identity Federation in CI) is preferred. Service-account JSON, Firebase admin
keys, `.env` files, the generated catalog, and checkpoints must never be added to
Git.

For an already-published catalog created by an older category classifier,
`migrate_categories.py` audits the immutable catalog/manifest pair and merges only
`categoryKeys`, `category`, `categoryLabel`, and `tags` into summary/detail docs.
It never writes the raw source payload collection. Run it without `--commit`
first, review the counts and hashes, then repeat with the explicit project and
credential options:

```powershell
python tools/recipe_importer/migrate_categories.py `
  tools/recipe_importer/output/akis-greek-full.jsonl `
  --manifest tools/recipe_importer/output/akis-greek-full.manifest.json `
  --i-have-permission
```

`inspect_recipe.py` remains available for a metadata-only inspection of one
explicit Greek numeric recipe URL. Legacy, separately supplied metadata catalogs
are also accepted by `import_catalog.py`; an `imageUrl` in that legacy format
requires `--allow-licensed-images`. A full crawler record is already guarded by
`--i-have-permission` and retains its authorized media URLs without that additional
legacy flag.

## Firestore projections and publication order

A full import uses the stable provider-aware document ID described above and
writes three projections:

```text
spoon_recipes/{id}           lean list/planner/search fields and media previews
spoon_recipe_details/{id}    complete normalized app-facing recipe details
spoon_recipe_payloads/{id}   complete sanitized source envelope for backend audit
spoon_catalog/status         last successfully published catalog checkpoint
```

Summary, detail, and source documents are fully replaced in conservative batches
of 100 by default (`--batch-size` may be set up to 500). A batch commit that ends
with Firestore `DeadlineExceeded` or `ServiceUnavailable` is retried at most twice
with bounded exponential backoff; permanent errors fail immediately. Existing
active IDs absent from the new active catalog are merge-tombstoned with
`active: false` in all three collections; documents are not deleted. This also
protects scheduled fresh-run imports that do not have a previous JSONL file.

`spoon_catalog/status` is intentionally written last and includes deterministic
catalog/projection hashes, schema version, active/retired counts, maximum document
sizes, and a server `lastImportedAt` timestamp. If any earlier batch fails, the
previous status remains in place. Some replacement batches may already have
succeeded, so it is safe to rerun the same validated import. The catalog is not
advertised as complete until the final status write succeeds.

The Android app does not query the summary or detail collections; they remain a
backend publication/archive and source for building future bundled catalogs.
Mobile clients may read the exact status document for a tiny freshness check, but
normal public-catalog browsing produces zero Firestore recipe reads. Raw source
payloads and all catalog writes are denied to mobile clients. The Admin SDK
identity used by this importer bypasses client rules and should have only the
Firestore data access needed by this job.

Firestore is still used for personal account data only: favorite IDs, plans,
completion history, shopping items, notes, and custom recipes. Its persistent
device cache accepts those changes offline and synchronizes them as an account
backup when connectivity returns.

## Quarterly GitHub Actions refresh

`.github/workflows/quarterly-catalog-refresh.yml` runs at 03:17 UTC on January,
April, July, and October 1 for Akis, day 2 for Argiro, and day 3 for Gastronomos.
Splitting providers bounds each run's write burst and isolates failures; it does
not make the imports cost-free. A full import with
`N` catalog records and `R` newly retired IDs performs `3N + 3R` recipe-document
writes plus two status-document writes. It also reads active summary documents to
build the source-scoped retirement inventory. Review current Firestore pricing
and quotas for the project, and configure a billing budget/alerts, before enabling
the schedule. Each scheduled run tests the tooling, performs one complete crawl,
validates its manifest without cloud credentials, obtains a short-lived Google
credential through GitHub OIDC, and imports that same file.

A manual `workflow_dispatch` is structurally crawl-and-validate only. Its three
provider matrix jobs run concurrently with `contents: read` as their only
permission and have no production environment, Firebase variables,
`id-token: write`, authentication action, or import command. Only the
`schedule` event can run the scheduled import job.

The jobs have read-only repository access and cannot push. Third-party actions
are pinned to immutable commits. Each matrix job uploads only its provider's
manifest and failure report for 30 days—not the catalog, checkpoint, or generated
Google credential.

Create a GitHub environment named `recipe-catalog-production` and restrict its
deployment branch policy to `main`. Add these repository Actions variables
(they identify resources but are not private keys):

```text
FIREBASE_PROJECT_ID=spoontheplanner
GCP_WORKLOAD_IDENTITY_PROVIDER=projects/PROJECT_NUMBER/locations/global/workloadIdentityPools/POOL/providers/PROVIDER
GCP_SERVICE_ACCOUNT=spoon-catalog-importer@spoontheplanner.iam.gserviceaccount.com
```

The Workload Identity provider maps and checks immutable GitHub repository ID
`1355319945` and owner ID `117316710`. It also checks the expected repository
name `justdataplease/spoon`, `refs/heads/main`, and `schedule` event. The service
account impersonation binding uses a `principalSet` for the immutable repository
ID; do not add a broader owner-level binding.

The dedicated service account must not receive `roles/datastore.user`, Owner,
Editor, or another broad Firestore role. Instead, create the project custom role
`spoonCatalogWriter` with exactly:

```text
datastore.entities.create
datastore.entities.get
datastore.entities.list
datastore.entities.update
```

There is deliberately no `datastore.entities.delete` permission. Bind this custom
role only to `spoon-catalog-importer@spoontheplanner.iam.gserviceaccount.com` with
a recurring IAM condition that permits requests only on January/April/July/October
1, 2, and 3 from 03:00:00 through 09:59:59 UTC. The job is scheduled for 03:17
UTC and has a six-hour runtime limit; the remaining time is deliberate buffer for
possible GitHub schedule-delivery delay.

Important scope limitation: Firestore IAM cannot collection-scope this project
binding. During those quarterly windows, the four permissions therefore apply to
every Firestore document in every database in `spoontheplanner`, not only
`spoon_recipes`, `spoon_recipe_details`, `spoon_recipe_payloads`, and
`spoon_catalog/status`. The importer code targets only those catalog paths, but
IAM does not enforce that application-level boundary. Outside the time window the
conditional binding grants the service account no Firestore entity access.

An example setup from Google Cloud Shell is below; adjust pool/provider names if
they already exist. IAM timestamp extraction defaults to UTC and is zero-based for
months, so `0, 3, 6, 9` represent January, April, July, and October:

```bash
PROJECT_ID="spoontheplanner"
PROJECT_NUMBER="$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')"
SERVICE_ACCOUNT="spoon-catalog-importer@spoontheplanner.iam.gserviceaccount.com"
ROLE_NAME="projects/$PROJECT_ID/roles/spoonCatalogWriter"
IAM_CONDITION="(request.time.getDate() == 1 || request.time.getDate() == 2 || request.time.getDate() == 3) && (request.time.getMonth() == 0 || request.time.getMonth() == 3 || request.time.getMonth() == 6 || request.time.getMonth() == 9) && request.time.getHours() >= 3 && request.time.getHours() < 10"

gcloud services enable iamcredentials.googleapis.com sts.googleapis.com \
  firestore.googleapis.com --project="$PROJECT_ID"
gcloud iam service-accounts create spoon-catalog-importer --project="$PROJECT_ID"
gcloud iam roles create spoonCatalogWriter \
  --project="$PROJECT_ID" \
  --title="Spoon Catalog Writer" \
  --description="Quarterly Firestore catalog create/get/list/update; no delete" \
  --permissions="datastore.entities.create,datastore.entities.get,datastore.entities.list,datastore.entities.update" \
  --stage=GA
gcloud iam workload-identity-pools create github \
  --project="$PROJECT_ID" --location=global --display-name="GitHub Actions"
gcloud iam workload-identity-pools providers create-oidc spoon-repo \
  --project="$PROJECT_ID" --location=global --workload-identity-pool=github \
  --issuer-uri="https://token.actions.githubusercontent.com" \
  --attribute-mapping="google.subject=assertion.sub,attribute.repository_id=assertion.repository_id,attribute.repository_owner_id=assertion.repository_owner_id,attribute.repository=assertion.repository,attribute.ref=assertion.ref,attribute.event_name=assertion.event_name" \
  --attribute-condition="assertion.repository_id=='1355319945' && assertion.repository_owner_id=='117316710' && assertion.repository=='justdataplease/spoon' && assertion.ref=='refs/heads/main' && assertion.event_name=='schedule'"
gcloud iam service-accounts add-iam-policy-binding "$SERVICE_ACCOUNT" \
  --project="$PROJECT_ID" --role="roles/iam.workloadIdentityUser" \
  --member="principalSet://iam.googleapis.com/projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/github/attribute.repository_id/1355319945"
gcloud projects add-iam-policy-binding "$PROJECT_ID" \
  --member="serviceAccount:$SERVICE_ACCOUNT" \
  --role="$ROLE_NAME" \
  --condition="title=quarterly_catalog_window,description=UTC quarter-start days 1 through 3 hours 03 through 09,expression=$IAM_CONDITION"
gcloud iam workload-identity-pools providers describe spoon-repo \
  --project="$PROJECT_ID" --location=global --workload-identity-pool=github \
  --format='value(name)'
```

If the custom role or provider already exists, inspect it and use the corresponding
`gcloud iam roles update` or provider `update-oidc` command rather than adding a
second, broader binding. Audit the service account's project IAM bindings after
setup and remove any unconditional or broader data role.

The scheduled backend ingestion and Android freshness check are separate. The
app's unique WorkManager task runs approximately every 90 days and reads only
`spoon_catalog/status`; it never visits the recipe site or starts an import.
That status change does not mutate an installed APK. After all provider refreshes
succeed, rebuild the SQLite asset with
`python tools/recipe_importer/build_local_catalog.py`, verify its counts/hashes,
build the optimized signed release, and distribute the new `dist/spoon.apk`.


## Full category and tag audit

Run both audits after changing taxonomy mappings or rebuilding the catalog:

```powershell
python -m tools.recipe_importer.audit_source_taxonomy --output tools/recipe_importer/output/source-taxonomy-audit.json
python tools/recipe_importer/audit_catalog_quality.py
```

The source audit visits every record in the three complete artifacts. It compares
primary/secondary categories and all six facet families plus general tags with
preserved publisher taxonomy. It reports missing tag families and inventories
every ingredient label; category drift, tag drift, or unreadable source evidence
makes it fail. This verifies reproducibility of the reviewed mappings. It does
not certify that every publisher tag is complete or culinarily correct.

The bundled audit independently checks compressed recipe payloads against SQLite
category, title, facet, ingredient-text, effort, rating, time, quick, and vegan
indexes. Rebuild and distribute an APK to deliver corrected public data to phones.
