# Τι θα φάμε; 0.9.1 (28)

Rapid typing in Βρες could feed an older query back into the text field while
search results refreshed, interrupting Greek keyboard composition or moving the
cursor. Explore and Favorites now keep the complete editor value synchronously,
including text, selection and active composition. Search results use a separate
debounced query; typing no longer rebuilds the entire planner UI snapshot.

Clearing takes effect immediately, cursor-only changes do not rerun a search,
and the existing 160-character limit keeps selection and composition in bounds.
Results no longer scroll the search field back to the top while it has focus.
The release includes the same 37,550-recipe catalog as 0.9.0.

The home-screen widget picker now offers three options:

- **Σημερινή συνταγή**: a large photo with a white title over a dark bottom gradient.
- **Σημερινή συνταγή · μικρή κάρτα**: the same photo treatment in a compact horizontal card.
- **Ημερολόγιο γευμάτων**: a month grid with today, planned-meal dots and completed-meal marks. Previous/next controls browse months, tapping the heading returns to the current month, and tapping a date opens that selected day in the app calendar.

Both photo widgets show only the recipe name and image, with rounded corners and
matching loading/empty artwork. The calendar follows the same account-scoped
meal plans as the app; each widget remembers its own displayed month.

The weekly planner also supports an explicit **Κενή ημέρα**. This clears all
planned courses for that date and preserves the empty day during week reload,
regeneration and preference updates. **Προσθήκη γεύματος** or choosing a favorite
adds a meal again; an unsuccessful search leaves the day blank. Existing cooking
history remains intact. The saved state uses existing empty-recipe/lock fields,
without a new backend field. Today's photo widgets show **Κενή ημέρα** for that
selection.

## Verification

All 377 Android unit tests passed, including seven blank-day planner/persistence
regressions. Release lint reported zero errors. Device coverage verified eight cases: Greek input/composition and cursor edits,
all three real widget providers including the compact minimum size, month/day
PendingIntents, and valid/invalid shared links. The missing-link case passed its
focused rerun after its test began observing the transient snackbar state before
intent delivery. Production lookup behavior did not change for that test fix.

The signed release was also checked on the launcher (large photo, compact 4x1
card, calendar, date tap) and through the week screen: selecting a blank day,
restarting the process, regenerating the week, and adding a meal again. The blank
selection survived until the explicit add restored the recipe card. Android key
events verified typed text and middle-cursor insertion in the signed app.

The two input-protocol regressions reproduce on the published 0.9.0 debug APK
and pass on the fixed debug app: rapid Greek composition across result updates,
and middle insertion/cursor preservation followed by clear and rapid refill.
The old APK produced `κκοτόπουλο` and inserted a composing word at the wrong
position. The same expected text, selection and focus assertions pass with the fix.

These tests manually drive Android InputConnection and require a disposable
emulator user with competing keyboard services temporarily disabled. A live
Gboard otherwise issues its own composition changes alongside the test driver.
Run the instrumentation class `ExploreSearchInputDeviceTest` with
`-e isolatedIme true` only under that isolated setup. Preserve and restore the
user's enabled/default IME settings and package enabled states in a finally
block. Without that explicit argument the protocol tests skip. The ordinary
unit suite requires no keyboard configuration.

Device logs are local build artifacts:
`build/search-input-baseline-isolated-ime.log` and
`build/search-input-fixed-isolated-ime.log`,
`build/release-0.9.1-device.log`, and
`build/release-0.9.1-message-device.log`.

## APKs

- debug: 178,643,336 bytes; SHA-256 `eb449ff8fdc9770c3dc2db432338497e3429accb52148b5c2553028208cf1ca3`.
- release: 157,146,644 bytes; SHA-256 `a33ab709f659dea4f063216ce9dfaae16326341e3af539b7c7156d57225e9320`.

Both APKs contain catalog SHA-256 `64ec01cefd16bd85b4d99f6d21b36122f6a7f35a391dc1c8852d2a17a9c55688` and retain the existing update-compatible signing certificate.
