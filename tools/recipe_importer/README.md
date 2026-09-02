# Permission-gated Greek recipe metadata importer

This tool intentionally does **not** bulk-scrape recipes. Akis Petretzikis' current
[Terms of Use](https://akispetretzikis.com/en/terms-of-use) say that site content,
including text, photos, information, and data, may not be copied, stored,
reproduced, republished, translated, or otherwise used without prior explicit
written consent. A permissive `robots.txt` is a crawler instruction, not a
copyright or database license.

Obtain written permission before using either tool with publisher-derived data.
The permission should cover the exact fields, languages, attribution, caching,
images/trademarks, update cadence, deletion requests, and commercial/private use.
This README is an engineering safeguard, not legal advice.

The production catalog is Greek-only. English `/en/recipe/...` URLs and records
whose language is not `el` are rejected.

## What is included

- `inspect_recipe.py` accepts exactly one Greek numeric recipe URL. It requires
  `--i-have-permission`, reads `robots.txt`, waits at least one second between
  requests (two seconds by default), follows only same-site HTTPS redirects, and
  reads only Schema.org Recipe JSON-LD. It emits a minimal summary and never emits
  ingredients, instructions, descriptions, images, videos, or nutrition text.
- `import_catalog.py` accepts reviewed JSON/JSONL metadata and validates every
  record before doing anything. It is a dry-run unless `--commit` is present. A
  commit uses Firebase Admin and fully replaces each supplied `spoon_recipes`
  document with normalized metadata, in batches of at most 500 documents. Only
  after every recipe batch succeeds, it merge-writes the
  `spoon_catalog/status` checkpoint.
- No sitemap walker, undocumented API client, ID enumerator, or image downloader
  is provided.

The one-page inspector never extracts an image URL. A separately supplied,
explicitly licensed catalog may include `imageUrl` only when the importer is run
with `--allow-licensed-images`. Without that switch the field is rejected in both
dry-run and commit mode. A record without a licensed URL writes `imageUrl: ""`,
which also clears a URL left by an earlier document version.

## Install and test

From the repository root:

```powershell
python -m pip install -r tools/recipe_importer/requirements.txt
python -m pytest tools/recipe_importer/tests
```

## Inspect one authorized URL

```powershell
python tools/recipe_importer/inspect_recipe.py `
  "https://akispetretzikis.com/recipe/13/keik-me-elaiolado-kai-giaoyrti" `
  --i-have-permission
```

The flag is an acknowledgement, not a substitute for permission. The output is:

```json
{
  "id": "13",
  "title": "Παράδειγμα συνταγής",
  "language": "el",
  "sourceUrl": "https://akispetretzikis.com/recipe/13/example",
  "categoryKeys": ["other"],
  "prepMinutes": 15,
  "cookMinutes": 50,
  "totalMinutes": 65,
  "preparationCount": 1,
  "stepCount": 6,
  "rating10": 9.58,
  "ease": "moderate"
}
```

`categoryKeys` are coarse, app-owned planner labels inferred from the title and
JSON-LD category/keyword metadata. `dirty` represents street/fast-food terms; it
is not a publisher category. Review classifications before import.

The ease band is transparent and deterministic:

- `unknown`: both preparation and step counts are unavailable/zero;
- `involved`: at least 3 preparation sections or at least 10 steps;
- `easy`: at most 1 preparation section and 1–5 steps;
- `moderate`: everything between those bounds.

Preparation and step counts are structural counts only; their text is discarded.
Duration is retained as separate filter metadata and never changes the ease band.

The site's current Greek canonical route is `/recipe/{numeric-id}/{slug}`. The
working `/el/recipe/{numeric-id}/{slug}` alias is accepted but normalized to that
canonical route. `/en/recipe/...` is always rejected. The inspector also requires
the returned HTML document to declare `lang="el"` (or an `el-*` variant).

## Future authorized full-catalog run

No enumerator is included. If written permission is obtained and an enumerator is
added later, it must select only Greek sitemap locations matching
`https://akispetretzikis.com/recipe/{numeric-id}/...`, never `/en/recipe/...`.
Deduplicate by the numeric recipe ID before inspection/import because locale or
alias URLs can otherwise describe the same recipe. Preserve the existing rate,
robots, minimal-field, and no-media safeguards.

## Validate, then write

JSON may be one object, an array, or `{ "recipes": [...] }`. JSONL/NDJSON must
contain one object per non-empty line.

```powershell
# Safe default: validates only, makes no Firebase connection or write.
python tools/recipe_importer/import_catalog.py authorized-catalog.jsonl

# Uses Application Default Credentials.
python tools/recipe_importer/import_catalog.py authorized-catalog.jsonl `
  --commit --project-id YOUR_FIREBASE_PROJECT_ID

# Or use an explicitly supplied service-account file.
python tools/recipe_importer/import_catalog.py authorized-catalog.jsonl `
  --commit --credentials path/to/service-account.json
```

For a catalog whose photo-display rights are explicitly covered by the written
license:

```powershell
python tools/recipe_importer/import_catalog.py authorized-catalog.jsonl `
  --allow-licensed-images

python tools/recipe_importer/import_catalog.py authorized-catalog.jsonl `
  --allow-licensed-images --commit --project-id YOUR_FIREBASE_PROJECT_ID
```

This permits only HTTPS URLs on `akispetretzikis.com` beneath `/photos/`, with no
credentials, nonstandard port, traversal, query string, or fragment. The URL is
persisted to the Android `imageUrl` field. The importer never requests, downloads,
caches, transforms, or uploads the image. The flag confirms intended handling; it
does not itself grant a license.

The importer rejects unknown fields so full descriptions, ingredient lists,
instructions, media URLs, and other copied content cannot accidentally enter the
database. It also rejects duplicate IDs and validates the recipe ID against its
source URL before opening a Firestore connection. During validation it adds a
deterministic app-owned `randomKey` in `[0, 1)`; a planner can query from a random
start key and wrap to the beginning without downloading an entire category.
The input `id` selects the Firestore document path and participates in the catalog
hash, but is intentionally omitted from document data because Android populates
`Recipe.id` with Firestore's `@DocumentId`. Full replacement ensures a legacy
stored `id`, copied content, or other stale fields do not survive re-import.

Each Firestore document includes the Android-facing fields `category`, `rating`,
`prepMinutes`, `cookMinutes`, `totalMinutes`, `preparationCount`, `stepCount`,
`language`, `active`, `sourceName`, `sourceUrl`, `tags`, and `imageUrl`. Unknown
app-facing durations and counts are normalized to integer `0`; when no explicit
total is supplied, `totalMinutes` is derived from the known preparation/cooking
values. `imageUrl` is an empty string unless that record supplied a validated URL
under `--allow-licensed-images`. `category` uses the first supported tag,
translates `dirty` to `street_food`, combines pasta/rice as `pasta_rice`, and
maps seafood into `fish`. If no supported tag exists it remains blank instead of
inventing a misleading food group; the app's `any` filter can still include it.
`rating` falls back to `0.0`, and `sourceName` is always `Άκης Πετρετζίκης`.

After all recipe batches commit, `spoon_catalog/status` is merge-written with:

- `language: "el"`;
- `recipeCount`, the number of records in the validated catalog;
- `catalogVersion`, formatted as `sha256:<digest>`;
- `catalogHash`, the full deterministic SHA-256 digest; and
- `lastImportedAt`, a Firestore server timestamp.

The hash is calculated from normalized records sorted by numeric-ID string, so
the same content produces the same version regardless of JSON/JSONL ordering. A
failed recipe batch leaves the previous status untouched. Recipe batches already
committed before a later failure may remain, but they are never advertised as a
new complete catalog version.

## Quarterly authorized refresh

Operate this as a reviewed quarterly refresh, approximately every 90 days. A
Cloud Scheduler calendar-quarter schedule can use `0 3 1 1,4,7,10 *` in the
`Europe/Athens` timezone to invoke a secured Cloud Run Job; CI may instead use an
equivalent 90-day/manual approval cadence.

For every run:

1. Obtain a newly supplied, explicitly authorized Greek metadata catalog. Do not
   fetch or regenerate it from the publisher site automatically. An included
   document is fully replaced, but the importer does not enumerate or delete
   documents omitted from the feed. Therefore carry forward every retired recipe
   as an explicit `active: false` record; merely omitting an ID does not deactivate
   the existing document. Android excludes inactive records from planning.
2. Place the artifact in a private, access-controlled job input or CI artifact.
3. Run the importer without `--commit`; review the count, validation result, and
   deterministic hash. This dry-run never initializes Firebase or makes a network
   request.
4. After approval, run the same immutable artifact with `--commit`. Use Workload
   Identity or Application Default Credentials supplied by the runtime, never a
   credential embedded in code, the repository, or the catalog.
5. Alert on a non-zero exit and verify `spoon_catalog/status` only after success.

The scheduled job invokes this importer against the supplied artifact; it is not
a crawler. The repository intentionally contains neither a site enumerator nor
hard-coded project credentials.

This backend refresh is distinct from the APK's unique 90-day WorkManager task.
The device task only reads `spoon_catalog/status` from Firestore to record freshness
metadata; it never crawls the site, imports recipes, or starts this backend job.

Only a trusted backend/service account should write `spoon_recipes`. Mobile
clients should get read-only access to that collection; each user's favorites and
weekly calendar should live under that user's own Firestore path and security
rules.
