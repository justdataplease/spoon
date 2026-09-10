# Τι θα φάμε; 0.9.0 (27)

Adds Giorgos Tsoulis, Greek Cookpad, Yiannis Lucacos and Funky Cook to the offline
recipe catalog, with the same circular official publisher badges and detail
presentation as Akis, Argiro and Gastronomos.

All seven publishers start selected in Settings. Deselecting a publisher affects
searches and future meal suggestions; saved meals remain available. Preferences
persist offline, including an explicit all-deselected state. Ordinary all-selected
preferences retain the legacy Firestore document shape. Synchronizing exclusions
requires the accompanying optional, owner-only source allowlist in Firestore rules.
Production rules deployment remains pending approval; exclusions work locally.

The quick filter now means known total time strictly under 30 minutes. Unknown
or incomplete durations do not qualify. When a mandatory duration in the method
contradicts the publisher total, the offline total is unknown; individual phase
times and full source wording remain available. Optional chilling, alternative
fast routes and storage-life notes do not create mandatory waits. The detail page
does not reconstruct an unknown total from incomplete phase times. The final
catalog has 6,119 quick recipes after removing 879 contradicted classifications. A dedicated «Χριστουγεννιάτικη» choice
uses the shared Christmas occasion. The 434 reviewed ingredient identities and common
facet aliases give equivalent publisher labels the same filtering behavior,
while preserving distinct ingredients and dietary restrictions. Raw publisher
wording and complete recipe evidence stay in the local download archives.

Recipe pages start with a discreet personal-use notice, creator/rightsholder
credit and an invitation to visit the creator's original recipe page. Publisher
icons also open the corresponding publisher website. Settings has a «Σχετικά» dialog with all publisher
links. The Android home-screen widget shows today's planned main recipe with only
its picture and name; tapping opens the same recipe. It follows account, date,
plan, custom-photo and time-zone changes without a separate personal-data cache.

Shared recipe landing pages now use «Τι θα φάμε;» and the exact current Android
launcher artwork. The live website was updated in the separate Pages repository
at commit `0c82bc5`. Existing public links and the signing association remain valid.
The repository also includes the Claude project skill `/add-recipe-source`.

## Download coverage

The resumable source commands, consent flags, pacing and coverage rules are in
[new-source-downloads.md](new-source-downloads.md). Tsoulis processed every one of
3,541 published recipe URLs: 3,540 valid recipes and one source page missing its
ingredient list. Funky Cook processed 1,293 published posts: 1,169 recipes and 124
explicitly audited exclusions. Lucacos processed all 1,159 sitemap recipe pages:
1,156 valid recipes and three documented exclusions (two publisher pages without
ingredients and one test page). No missing ingredients or methods were invented.

Cookpad exposes no exhaustive public Greek index. Its link-graph snapshot is
explicitly incomplete, even if a work queue finishes. The bundled SQLite metadata
retains each source's complete/discovery flags, pending counts, failures and
coverage note. Media remains at publisher HTTPS URLs and is cached on demand.

The bundled catalog contains **37,550 recipes**:

| Publisher | Recipes | Coverage |
| --- | ---: | --- |
| Akis | 5,369 | Complete imported catalog |
| Argiro | 3,165 | Complete imported catalog |
| Gastronomos | 12,327 | Complete imported catalog |
| Giorgos Tsoulis | 3,540 | Complete official recipe sitemap; 1 exclusion |
| Yiannis Lucacos | 1,156 | Complete official recipe sitemap; 3 exclusions |
| Funky Cook | 1,169 | Complete post discovery; 124 exclusions |
| Cookpad Greece | 10,824 | Validated partial snapshot; 7,762 pending at snapshot |

The Cookpad snapshot was frozen on 2026-09-10 at 09:19 UTC from 18,586 discovered
URLs. Further download progress is not part of this APK. Original source archives
and resumable checkpoints remain local; the APK bundles recipe text and metadata,
with publisher images loaded on demand.

## Validation and artifacts

Android unit tests: 352 passed. Importer tests: 651 passed. Release lint: zero
errors. Firestore emulator contracts: 8 passed; website tests: 5 passed.
All-record catalog and source audits passed, including shared ingredients and
categories. Lucacos' complete cached DOM comparison preserved all recipe prose.
All 11 Android device contracts passed against the final catalog in 295.372
seconds, including APK asset integrity and replacement, SQL/Kotlin filtering
agreement, planner persistence, and recipe links. Visual/runtime checks are
recorded in [the device audit](device-catalog-audit-2026-09-10.md).

Both APKs contain the exact validated database. Version is 0.9.0, code 27, package
`com.spoon.app`. The optimized release retains the previous signing certificate.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `dist/spoon.apk` | 157,107,060 | `691bf12face3a47b0c4a263e4857eadf7b81fad7426b31460353ffc764a1ca07` |
| `dist/spoon-debug.apk` | 178,601,879 | `5a95fc94ebf02c86d60c6a686e8b2a5f0ef755500d62e9fd4ba7b7e8028015b2` |
| `app/src/main/assets/recipe_catalog.db` | 150,634,496 | `64ec01cefd16bd85b4d99f6d21b36122f6a7f35a391dc1c8852d2a17a9c55688` |

Signing certificate SHA-256: `892d89776ee0d6693a19b7cdfbdaeccb881b85c92ddf3b1476c40d90990f56b3`.
