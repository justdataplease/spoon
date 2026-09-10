# Android catalog verification — 2026-09-10

The 0.9.0 release adds four publishers, shared ingredient/facet aliases, source
preferences, quick/Christmas filters, and the today-recipe widget. Validation uses
the real bundled catalog and the production SQL and Kotlin filtering paths.

## Device checks

On Android 15, all 11 instrumentation tests passed against the final
37,550-recipe APK catalog in 295.372 seconds. The installed catalog matched the
current APK asset by SHA-256, including after forced replacement.

The checks cover:

- Every recipe against the planner's categories, effort, rating, preparation-time,
  source, vegan, and ingredient constraints, including random selection.
- Every recipe against Explore's combined filters, source exclusions and source
  intersections, the strictly-under-30-minute quick rule, Christmas and cuisine
  aliases, search ranking, pagination, and total counts.
- Every published ingredient, diet, meal, occasion, cooking-method, and cuisine
  filter against the full recipe payloads.
- Favorites-only planning, empty results, repeated meal generation, saved locks,
  cooked meals, full menus, history, cache eviction, and repository reopening.
- Catalog replacement after an APK upgrade while preserving personal test data;
  installed database SHA-256 must match the current APK asset before and after
  forced replacement.
- Cold and warm recipe links, activity recreation, and missing/invalid links.

The APK and instrumentation package were installed explicitly for disposable
Android user 10. Tests use a separate catalog directory and preference namespace.
Instrumentation ran directly, without Gradle's package-wide cleanup. Reproduction
commands are in [Android device validation](android-device-validation.md).

## Other validation

Android unit tests passed: 352. The complete importer suite passed 651 tests after
the shared taxonomy, complete source parsing, and mandatory-duration guard updates.
Release lint reported zero errors. The isolated
Firestore emulator rules contract passed all eight cases; this did not deploy
production rules. Five website branding and sharing-link tests passed.

The final database quality audit found no index, ingredient-normalization,
category-label, source-key, or vegan-eligibility contract violations. Source
snapshots retain their actual completeness and pending counts in SQLite metadata.
The optimized APK is signed by the same certificate as the previous release.

The optimized release also passed a visual/runtime smoke check: all seven sources
and official badges are present, deselecting two sources survives process restart,
and restoring all seven works. About shows the current name/icon/version and all
publisher links. A Funky Cook shared link opens the exact recipe with linked
creator/rightsholder attribution. The real launcher widget shows the same photo
and name as today's planned main recipe and opens that recipe when tapped. No
widget worker or Android runtime error appeared in the filtered smoke-check log.

The final UI code also passed explicit publisher-link checks: the discreet creator
invitation opened the exact original Lucacos recipe, and its badge opened the
publisher homepage. In Settings, a badge tap opened the publisher site without
changing the seven selections; ordinary selection still toggled and saved
correctly. A recipe with unknown total retained its reported cooking metric while
showing neither a reconstructed total nor a quick badge. The existing widget
survived the signed upgrade with the same matching image, name, and destination.
These visual checks preceded the last data-only Unicode/overnight correction;
the final catalog then received the complete device contract run.

A separate seven-source SQL spot check verified required waiting excludes known
examples from quick results, while optional immediate serving remains eligible.
The final catalog contains 6,119 quick recipes after 879 contradictory quick
classifications were removed. Original source artifacts and method text remain
unchanged; required durations are not used to invent a replacement total.

Final source counts, importer test results, APK checksums, and visual checks are
recorded in [the release notes](release-0.9.0.md).
