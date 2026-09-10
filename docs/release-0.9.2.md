# Τι θα φάμε; 0.9.2 (29)

Home-screen photos previously passed through a fixed 480×320 crop, which looked
soft when enlarged and cropped the compact widget twice. All recipe widgets now
request images at the launcher's physical pixel dimensions and preserve the
original aspect ratio until display. Resizing requests fresh dimensions. Bitmap
allocations stay below Android's RemoteViews limit, including pooled backing
memory. The existing catalog already supplies larger original photos; no catalog
migration is needed.

Personal recipe photos now pass decoded, validated image bytes to Coil. Coil 2
does not fetch the editor's data-URI strings directly; the same correction is
shared by in-app artwork and widgets.

The calendar widget now combines a month grid with a photo card for the selected
day. Tapping a date updates the card in place, with the same category symbols as
the app. Tapping the photo opens the recipe; its date heading opens that day in
the app calendar. Empty cards also open the selected day. Month navigation and
return-to-today remain available; each widget has its own selection. The card
uses white text over a dark photo gradient, matching the standalone recipe widgets.
Empty and deliberately blank days have clear states without a stale recipe photo.

The planner's blank-day action and state now say **Δεν θα μαγειρέψω**. After marking
a meal **Το έφτιαξα**, the undo action says **Άκυρο** in both the week and calendar.
Only the labels change; saved plans and cooking history retain their behavior.

The recipe catalog and update-compatible signing identity remain unchanged.

## Verification

All 388 Android unit tests passed. Release lint reported zero errors (129 warnings).
Five widget device cases passed across the photo suite and the final calendar
rerun: density-aware decode, resize, actual RemoteViews bitmap delivery, all three
providers at supported sizes, month navigation, and in-place date selection with
correct app navigation. At density 2.625, the deterministic source decoded to
840×588 for a 320dp-wide widget and 945×662 after resizing to 360dp, preserving the
original 10:7 aspect ratio instead of the former fixed 480×320 crop.

The calendar switches to inline category cells below 420dp, including existing
280dp widgets. The resize test waits for the new layout, since a photo delivery
queued before a resize can arrive first. The complete original assertions then
passed, including category/text fit and the 96dp photo footer.

The signed release was installed as an update in isolated emulator user 11.
Launcher checks verified the 4×5 calendar, category symbols, today's outline,
selected-day highlight, changing recipe photos/titles, and opening the selected
recipe from its photo. The renamed blank-day action was visible. The guest user's
completion tap encountered an existing Firebase authentication failure, so that
manual state transition was not verified; completion/history logic is unchanged.

Local device evidence is in `build/release-0.9.2-widget-device.log`,
`build/release-0.9.2-calendar-device.log`, and
`build/release-0.9.2-photo-dimensions.log`; launcher screenshots are in `.shots/`.

## APKs

- `dist/spoon.apk`: 157,153,964 bytes; SHA-256 `4b02ff9942ad8c13f78775ca1657637b5de670173629d73678257b0644790117`.
- `dist/spoon-debug.apk`: 178,667,923 bytes; SHA-256 `063231ad69592623f75aa9c351fa5eadb60d4e9f1478f5f6ccc6cc4c877591a8`.

Both APKs contain the unchanged catalog SHA-256
`64ec01cefd16bd85b4d99f6d21b36122f6a7f35a391dc1c8852d2a17a9c55688`.
The release retains signer SHA-256
`892d89776ee0d6693a19b7cdfbdaeccb881b85c92ddf3b1476c40d90990f56b3`.
