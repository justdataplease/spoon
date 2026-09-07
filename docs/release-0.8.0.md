# Spoon 0.8.0 (18)

Preferences now include a category for every weekday and a favorites-only source
for weekly proposals. The preset rhythm remains Monday legumes, Tuesday poultry,
Wednesday vegetables, Thursday meat, Friday fish, Saturday street food, and Sunday
pasta/rice. Dessert remains available when explicitly chosen.

Favorites-only planning can repeat recipes across days and weeks. When nothing
matches a day, its category and filters remain saved with an unavailable recipe
card. Adding a matching favorite lets the day recover without relaxing filters.
Favorites also have the same Greek search and combined filters as Explore, with
independent search state and reset controls.

A lock at the top of each weekly recipe card preserves it during weekly
regeneration. Cooked meals are automatically protected. Locks persist locally and
sync with meal plans. Preference reconciliation preserves both locked and cooked
meals, including their existing cooking history.

The optional menu icon opens a panel with the saved main dish plus side and
dessert suggestions. Extra courses use the selected source, food preferences, and
day filters; unavailable courses stay explicit. These optional suggestions are
created when the panel opens and do not replace or modify the saved main dish.

Validation:

- 301 Android unit tests passed.
- 7 Android emulator contract tests passed, including catalog parity, favorites,
  unavailable-day recovery, lock persistence, and cooked-history preservation.
- 398 recipe-importer tests and the full catalog quality audit passed.
- 4 Firestore emulator rule tests passed, including legacy documents, new
  preference fields, locks, unavailable-day writes, and owner isolation.
- Release APK installed and launched on the emulator; optional menu and Favorites
  search controls checked in the running UI.
- Release signing certificate matches the previous APK; versionCode 18,
  versionName 0.8.0.

Deploy the accompanying backward-compatible firestore.rules before distributing
this version so the new personal-state fields can sync.

Release APK: dist/spoon.apk (92,610,608 bytes).
SHA-256: 9b18180196ff087f1e41895686db6a8141c5c04eb8fdfbac0f217a95fb06ae4e
