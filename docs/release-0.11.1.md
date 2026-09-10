# Τι θα φάμε; 0.11.1 (33)

The home screen now displays the actual personal synchronization state. It
previously used local catalog readiness, so it always described device storage
even when the account's changes were synchronized. The home and account screens
share a single status of two or three Greek words. The two explanatory paragraphs
below the weekly planner header have been removed.

## Cloud synchronization

A fresh production read confirmed that the active Firestore rules reject the
app's side/dessert weekday preferences, source exclusions and drinks category.
They also reject archived cooking-history imports and exact history retries.
These failures keep valid changes in the durable local upload queue.

The prepared rules accept the existing preference fields and categories, plus
owner-authored valid history imports, exact unchanged history replays, and retries
of already absent history deletions. Ownership restrictions, history content
immutability, completed-plan/history matching, active-course deletion protection,
and forbidden favorite updates remain enforced.

Production deployment is pending explicit approval. Automatic approval review
rejected the deployment because the production access-control change requires
specific user approval. Building the APK and pushing main do not deploy these
rules. Until deployment, affected uploads remain queued and are shown as waiting.
Firebase account-signup configuration is outside this release's changes.

## Verification

- All 459 JVM tests passed, with no failures, errors or skipped tests.
- Release lint passed with zero errors and 129 warnings.
- All 15 isolated Firestore rules tests passed, including accepted imports and
  retries, malformed values, unauthorized accounts, immutable history, protected
  deletions, completed-plan references and favorite restrictions.
- The Android Auth/Firestore integration test passed against local emulators: guest data in all seven collections reached server-confirmed state with no pending revisions; offline favorite, note and shopping edits survived SQLite/repository reopening and synchronized after reconnection. Existing favorite timestamps stayed unchanged.
- The signed APK was installed as an update on isolated Android 15 user 11.
  Its home screen shows the short device-storage status and no longer contains
  either removed introductory paragraph.
- The release and debug APKs built successfully. The signing certificate is
  unchanged, preserving upgrades over previous installations.

Local verification logs are retained in `build/release-0.11.1-build.log` and
`build/history-sync-rules-test.log`, and `build/release-0.11.1-sync-device.log`; the production baseline is backed up in
`build/production-rules-backup-0.11.1.json`.

## APKs

| Artifact | SHA-256 |
| --- | --- |
| [`dist/spoon.apk`](../dist/spoon.apk) | `e4bfcfbd12204cc33882b66f2567791af046eebc7c4170deb83f7e6c2c770fc4` |
| [`dist/spoon-debug.apk`](../dist/spoon-debug.apk) | `12938f8c57b97c5aabeeb5eaf006263f1d1e43eeefaf9d393a103bfc0eb6e85d` |

Both APKs identify as `com.spoon.app`, version `0.11.1`, version code `33`.
The signing certificate SHA-256 remains
`892d89776ee0d6693a19b7cdfbdaeccb881b85c92ddf3b1476c40d90990f56b3`.

## Reproduce the Android sync test

Start the isolated Firebase emulators:

```powershell
firebase emulators:start --only auth,firestore --project demo-spoon-planning --config firebase.sync-test.json
```

Build/install the debug app and Android test APK into an isolated emulator user
as described in [Android device validation](android-device-validation.md), then
run `com.justdataplease.spoon.data.remote.FirebasePersonalSyncDeviceTest` with
instrumentation argument `-e personalSyncEmulator true`. The test creates a named
Firebase app with a fake key and uses only `10.0.2.2:9199`/`10.0.2.2:8187` in the
demo project; it does not write production account data.