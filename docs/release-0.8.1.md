# Spoon 0.8.1 (19)

«Πλήρες μενού» opens a dedicated screen with the day's saved main, side, and
sweet. Each course has recipe details, favorite toggling, replacement from
favorites, category/difficulty/rating/time filters, an individual reroll, a lock,
and cooking history. Missing matches remain visible as unavailable courses.

Opening the menu again preserves all selections. Generating new weekly main
proposals preserves saved sides and desserts. Course selections, filters, locks,
and cooking state persist across app restarts and sync with the owner's day plan.
Cooking and undoing one course preserves the other courses and their history.

«Ιστορικό» has a dedicated bottom navigation button. Main recipes remain the
focus of the week view, with the menu icon opening the other dishes on demand.

Repeated readiness actions reload Firebase account metadata at most every 15
seconds. Failed connection retries start at 5 seconds and increase to a maximum
of 30 seconds. Live Firestore listeners and personal-data saves remain immediate.

Includes 0.8.0's weekday category preferences, favorites-only generation with
repeats and unavailable-day cards, weekly lock protection, and the same search
and combined filters in Favorites as Explore. Dessert remains a category choice
and is excluded only from the default weekday preset.

Validation:

- 306 unit tests passed.
- 8 Android emulator contract tests passed, including saved-course persistence,
  recipe hydration, cooking history, and weekly regeneration.
- 6 Firestore emulator rule tests passed, including atomic course completions,
  independent undo, invalid nested data, backward compatibility, and owner isolation.
- Debug and optimized release APKs built successfully, including release lint.
- Running UI checked for the History tab, full-menu controls, details/back
  navigation, and saved side/dessert stability after weekly regeneration.
- Release signing certificate matches the previous APK.

Deploy the accompanying backward-compatible firestore.rules before distributing
this version so all saved courses and new personal-state fields can sync.

Release APK: dist/spoon.apk (92,626,992 bytes).
SHA-256: 5691ac66d8ba0dede42e573727ee359c7f8e60adbfb86aac7d2f97edcd4e659a
