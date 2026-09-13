# Τι θα φάμε; 0.11.2 (34)

Each recipe card in Συλλογή now has a small **Στην εβδομάδα** switch.
When **Μόνο από τη συλλογή** is enabled in Settings, new weekly menus and
replacement suggestions use only switched-on favorites that match the day's
category and food preferences. This also applies to sides and desserts. If no
enabled favorite matches, the app shows no available suggestion.

Existing favorites start enabled. Switching a recipe off keeps it in the
collection and preserves saved meals and manual selection. The switch does not
filter ordinary catalog planning when collection-only mode is disabled.

Choices persist offline, survive app restarts, and synchronize with the account.
The Firestore update permits changes only to weekly participation and its
revision timestamp, keeping favorite identity and original save time immutable.
Legacy favorites and duplicate adds preserve existing cloud choices; retry
acknowledgments cannot requeue unchanged favorites after unrelated edits.

## Verification

- All 468 JVM tests passed, with no failures, errors or skipped tests.
- Release lint: zero errors, 130 warnings.
- All 19 isolated Firestore rules tests passed, including owner isolation,
  immutable fields, malformed toggle values, legacy favorites and exact retries.
- All six Android storage/sync tests passed on isolated emulator user 10,
  including an offline toggle, database/repository reopening, cloud confirmation,
  switching back on, and preservation of the favorite's original save time.
- The collection switch was checked on Android: tapping it leaves the card open
  and the recipe saved; the disabled state survived a cold start and installing
  the signed release as an update. The switch row has a 48 dp minimum touch target.
- Debug, release and Android test APKs built successfully. The release identifies
  as `com.spoon.app`, version `0.11.2`, version code `34`.

The tested rules were deployed to `spoontheplanner` on
`2026-09-13T09:04:29.924500Z`. Readback verified the exact source and unchanged
Auth settings. Public signup remains disabled.

- Ruleset: `891b630b-b7cd-464f-bcb0-7b7a68c1ea97`.
- Rules SHA-256: `95f95a993c583957138c49ebdd3ab27c7943e093841806fcc8e0747c159a1ecb`.
- Local evidence: `build/release-0.11.2-final-verify.log`,
  `build/release-0.11.2-device-tests.log`, `build/release-0.11.2-rules-test.log`,
  and `build/production-rules-deployed-0.11.2.json`.

## APKs

| Artifact | SHA-256 |
| --- | --- |
| `dist/spoon.apk` | `a6fe5b68ab545906892f277fe0df21cbc31f85c58914d8cb247fa41c7e691555` |
| `dist/spoon-debug.apk` | `04c92516a8e92545ac414b8e0e035bf11aec594aa2d83a771044757046e0c070` |

The signing certificate is unchanged:
`892d89776ee0d6693a19b7cdfbdaeccb881b85c92ddf3b1476c40d90990f56b3`.
