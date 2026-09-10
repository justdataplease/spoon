# Today's recipe widget

Long-press an empty part of the Android home screen, choose **Widgets**, find
**Τι θα φάμε;**, and add **Η σημερινή συνταγή**. Resize it as needed.

The widget shows only the name and picture of today's main dish from the planner.
Tap it to open that recipe in the app, including personal recipes. When today has
no main dish, it invites you to choose one and opens the app. Missing or offline
photos use the same bundled artwork as the recipe cards.

Plan, personal-photo and account changes update existing widgets while the app
process runs. The local calendar triggers a refresh at midnight; changes to the
device clock or time zone reschedule it. WorkManager also checks every 30 minutes
while the app is closed. Android can defer background work during sleep or battery
restrictions, so background refresh is not guaranteed at an exact minute.

The widget reads the same owner-scoped repository as the planner and keeps no
separate personal-data cache. Async image results are checked against the current
owner, day, selected plan and personal-photo revision before they can be shown.

## Device validation

Use the [Android device validation commands](android-device-validation.md):
Gradle connected tests belong on a disposable AVD; an existing multi-user AVD
uses explicit `install --user` and `am instrument --user` commands.

On the isolated test user, add the widget through the launcher and compare its
name/photo with today's main planner card. Check a changed selection and a
personal recipe, tap to open the matching detail, and resize a long title.
Check date/time-zone changes and sign-out with test state only. Missing/offline
photos should show the recipe-card placeholder until an image becomes available.
