# Home-screen recipe widgets

Long-press an empty part of the Android home screen, choose **Widgets**, find
**Τι θα φάμε;**, and select one of three widgets:

- **Σημερινή συνταγή**: a large photo with the recipe title over a dark bottom gradient.
- **Σημερινή συνταγή · μικρή κάρτα**: the same treatment in a compact horizontal strip.
- **Ημερολόγιο γευμάτων**: an interactive month above the selected day's recipe photo and title.

Both standalone photo cards follow today's main dish from the planner. Tap one
to open that recipe, including personal recipes. When today has no main dish,
the card invites you to choose one. An intentionally blank day says
**Δεν θα μαγειρέψω**. Missing or offline photos use bundled artwork.

In the calendar, tap a date to select it without leaving the home screen. Its
recipe appears below the grid; tap the photo card to open the recipe, or its
date heading to open that day in the app calendar. An empty card also opens the day. Category symbols match the app. Previous/next controls browse
months, and tapping the month heading returns to today. Each calendar widget
remembers its own displayed month and selected date.

Photos are requested in physical pixels using the launcher's widget dimensions
and screen density. The original aspect ratio is preserved until the widget's
ImageView crops it, so the compact card does not inherit a second crop from the
large card. Decode requests refresh when the widget is resized. Very large
images are bounded to the Android RemoteViews bitmap allowance with headroom;
resolution remains limited by the publisher's original photo.

Plan, personal-photo and account changes update existing widgets while the app
process runs. The local calendar triggers a refresh at midnight; changes to the
device clock or time zone reschedule it. WorkManager also checks every 30 minutes
while the app is closed. Android can defer background work during sleep or battery
restrictions, so background refresh is not guaranteed at an exact minute.

Widgets read the same owner-scoped repository as the planner and keep no separate
personal-data cache. Async image results are checked against the current owner,
day, selected plan and personal-photo revision before publication. Calendar photo
results also require that the widget's selected date and month still match.

## Device validation

Use the [Android device validation commands](android-device-validation.md).
An existing multi-user AVD uses explicit `install --user` and `am instrument --user`
commands. Widget instrumentation allocates and deletes only its own temporary
host IDs. Photo tests use a deterministic full-resolution image to check density,
resize, aspect ratio and actual RemoteViews publication without network timing.

On the isolated test user, add widgets through the launcher and compare the
name/photo with the planner. Check a changed selection and a personal recipe,
tap to open the matching date or detail, and resize a long title. In the calendar,
select another day and month, then return to today. Check blank days, clock changes
and sign-out with test state only. Missing/offline photos should show placeholder
artwork until an image becomes available.
