# Spoon 0.8.2 (20)

Weekly new proposals now refresh the main, side, and dessert courses, preserving
locked and already-cooked courses independently. Food preferences include weekday
category defaults for all three courses. Changing a default updates the eligible
saved courses of the selected week while keeping other filters and cooking history.

Validation:

- 309 unit tests and 6 local Firestore rules tests passed for the menu changes.
- 2 Android emulator tests passed for locks, cooking history, and menu persistence.
- Debug and optimized release APKs built successfully; release lint passed.
- The release APK installs as an upgrade, launches on the emulator, and displays
  working side and dessert selectors in food preferences.
- Release signing certificate matches the previous APK.

The accompanying firestore.rules must be deployed for the new preference fields
to sync. This APK publication does not deploy Firebase rules.

Release APK: dist/spoon.apk (92,626,992 bytes).
SHA-256: 71939817a812d288fc49b09d4b56586506b3d14008b2272595845b23484dd370

Debug APK: dist/spoon-debug.apk (114,077,028 bytes).
SHA-256: 928ff8cb6556c0ac730208b0f258481ca6d4a300c8d319952c375174edb740fd
