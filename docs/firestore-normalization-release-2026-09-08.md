# Firestore normalization and Spoon 0.8.3 release

The live `spoontheplanner` catalog contained stale category values even though the
local publisher artifacts already reflected the reviewed category rules. The
backend migration corrected 3,621 recipes: 50 Akis, 11 Argiro, and 3,560 Gastronomos.

The Firestore summary and detail projections now apply one reviewed ingredient
vocabulary with 273 canonical labels. It covers all 420 distinct publisher display
spellings in the current artifacts. Missing equivalences included chestnut,
aubergine, courgette, and blueberry labels. Display spelling and capitalization
are consistent across providers. Distinct foods and compound publisher groups
remain separate. This changed ingredient labels on 7,314 recipes: 3,144 Argiro and
4,170 Gastronomos.

## Live migration verification

- Read all 20,861 recipe summaries and all 20,861 recipe details before writing.
- Validated complete source artifacts and their original hashes before refreshing
  only derived projection hashes, size checks, and parser fingerprints.
- Applied 19,865 updates to existing documents: 10,393 details and 9,472 summaries.
  Only changed category, ingredient-label, and tag fields were written.
- Used batches of 200 with exact update-time preconditions; no missing documents
  were created. Unchanged documents incurred no writes.
- Read back all 41,722 recipe documents and verified every expected taxonomy field.
- Published and verified the global and three existing provider status documents.
- Wrote no raw source-payload or personal-data documents.

The migration plan retains previous taxonomy values under the ignored local
importer output. Plan SHA-256:
`884c84a54d04227b473ee217f9ae9b1c64c6d6d7411ba27c23a807cd950872de`.

A partial taxonomy repair cannot certify hashes of unrelated fields. Stale
whole-document summary/detail status hashes were cleared, while original catalog
and source hashes were preserved. Verified per-provider taxonomy hashes identify
this repair. The next full import restores complete projection hashes.

## Production review and checks

The recurring import path uses the same backend normalization. Argiro and
Gastronomos parser fingerprints include the vocabulary and normalizer. The APK
catalog consumes the shared backend projection; Android normalization code was
not changed. The release audit now fails on noncanonical ingredient facets,
unreviewed ingredient labels, or category-label drift.

- 423 importer/backend tests passed, including cross-provider equivalence,
  distinct-food separation, source preservation, concurrent-edit guards,
  idempotence, plan validation, and failure handling.
- 309 Android unit tests and all eight connected device tests passed.
- All six isolated Firestore planning/security-rules contracts passed.
- Full release lint passed with zero errors and 31 warnings. The warnings concern
  available dependency/build-tool updates, a redundant manifest label, older API
  idioms, and version-catalog style. Dependency upgrades were not mixed into this
  data repair.
- Every rebuilt catalog record passed SQLite/index, ingredient normalization,
  category-label, and vegan eligibility checks.
- Compared all 20,861 payloads with the previous APK catalog: only ingredient
  facets and corresponding tags changed. IDs, categories, original ingredient
  lines, quantities, instructions, and all other recipe data were preserved.
- Verified release application ID, version, non-debuggable flag, embedded catalog
  hash, and the same signing certificate as the previous APK.
- Installed the release on the Android 15 emulator and cold-launched offline
  successfully (1.47 seconds reported by Activity Manager). Restored its prior
  connectivity setting afterward.

## Artifacts and limits

`dist/spoon.apk` is the optimized release, version 0.8.3, version code 21.
`dist/spoon-debug.apk` is also refreshed. The release is 92,561,456 bytes and retains
the existing personal/debug signing identity to allow sideload upgrades. Public
Play Store distribution requires a production signing setup; this is a verified
personal APK release, not a Play Store submission.

APK SHA-256:
`ec4ad98592893bae50c3e5b11a0c6cdf16988fc5cd1dadf17bef43025d284ac2`.

Bundled database SHA-256:
`4beb50cc7614afb2687f32c1c37a60e4c5e4e8a21cd58790ba726d22deed56b7`.

The catalog still contains 2,324 recipes without publisher main-ingredient facets.
Normalization does not invent missing labels or rewrite recipe prose/quantities.
The physical phone, public Play distribution, and live personal-data syncing were
not exercised by this release verification.
