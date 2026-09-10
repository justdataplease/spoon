# Τι θα φάμε; 0.11.0 (32)

Accounts are optional. The account screen now offers email/password registration
as well as sign-in. Both use the same durable local-data transfer: existing guest
data becomes part of the accepted account immediately and stays queued for cloud
synchronization. Registration/password errors do not remove guest data or block
normal local use. Account creation itself requires connectivity.

A usability review confirmed that recipe ingredients and manual shopping items
save without Firebase authentication or connectivity. In the installed signed
0.10.0 app, adding all 15 ingredients from Φασολάδα άσπρη in airplane mode showed
the success message; all 15 remained after a cold restart. Ticking an item updated
the count immediately, and adding a manual product also worked offline.

The review identified further cases addressed in this patch:

- Undo and history removal record an explicit durable deletion even when the
  separate cloud history snapshot has not arrived yet. A late snapshot cannot
  restore the removed cooked event, and the plan change/deletion marker commit in
  one database transaction.
- A signed-in cold start without a server connection changes from syncing to
  locally saved/waiting after a bounded initial attempt. Successful authoritative
  reads restore the normal sync status; elapsed time never means data is synced.
- Unreadable optional account-transfer metadata or older migration sources no
  longer block healthy local storage. The originals are retained, unfinished
  imports stay retryable, and recovery issues are shown separately. Already
  completed legacy imports do not reread the old files on every startup.
- Shopping drafts survive tab changes and rotation. Preference drafts survive
  rotation and preserve locally edited fields during background updates. New note
  edits survive a delayed acknowledgement of the previous saved text. Draft state
  is checked against account ownership before restoration.
- Personal recipe saving waits for the selected photo to finish processing.
  Photo-read failures require retry or explicit continuation with the previous/no
  photo, so a selection is not silently omitted from the saved recipe.

Production account registration is currently blocked by Firebase
`client.permissions.disabledUserSignup=true`. Automatic approval review rejected
the attempted enable operation because it changes a project-wide production
setting, so explicit approval is pending. No production users were created.

This release does not change or deploy Firestore security rules. The
[history synchronization rules proposal](local-history-sync-rules-proposal.md)
and [current preference/category validator update](current-app-sync-rules-proposal.md)
remain pending approval and deployment. A read-only production check confirmed
that the active rules reject these current app fields. Local use is
independent of that cloud deployment; rejected uploads remain queued.

## Local registration verification

Start the isolated Auth emulator with:

```powershell
firebase emulators:start --only auth --project demo-spoon-registration `
  --config firebase.registration-test.json
```

Install the debug and Android test APKs into an isolated Android emulator user,
then run `FirebaseRegistrationDeviceTest` with instrumentation argument
`-e authEmulator true`. Both tests passed on Android 15. They use a separately
named Firebase app, a fake key, demo project, and `10.0.2.2:9199`; they create and
delete only local emulated accounts. They never use production Auth or Firestore.
The debug-only network configuration permits HTTP only for local emulator
addresses. Release networking retains Android's HTTPS-only default.

The tests cover successful registration with 1,200 cooked events and all seven
personal collections, persisted account attachment, failed duplicate registration
without guest-data loss, later sign-in merging additional guest edits, and older
anonymous-account transfer with durable-intent cleanup. Firestore is deliberately
absent in these two tests: they verify local attachment and queued upload state,
not successful production cloud acknowledgements.

Six further Android device regressions passed for no-auth personal CRUD,
SQLite/repository reopen, photos and history persistence, durable transfer intent,
failed registration, optional legacy-reader recovery, and history deletion before
a remote history snapshot arrives.


## Final validation

The final Gradle run passed `:app:testDebugUnitTest`, `:app:lintRelease`,
`:app:assembleDebug`, `:app:assembleRelease`, and
`:app:assembleDebugAndroidTest`. The verification log is retained locally at
[`build/release-0.11.0-verify.log`](../build/release-0.11.0-verify.log).

- All **459 JVM tests** passed, with zero failures, errors or skipped tests.
- Release lint reported **zero errors and 130 warnings**.
- All **eight Android device tests** passed: six local-storage/repository cases
  and the two isolated Auth-emulator cases described above.
- The final signed release APK was installed as an update on isolated Android 15
  user 11, retaining that user's previous app data.
- In the final signed APK, an unsaved source-preference draft survived
  landscape-to-portrait activity recreation. Saving the deselection offline and
  cold-starting the app retained it. All seven sources were selected again after
  this check.
- The signed 0.11.0 account screen showed locally saved status and optional
  Sign in/Register tabs. Registration exposed email, password, password
  confirmation and the create-account action. No production account was created.
- Shopping-draft preservation across tab changes and rotation had already passed
  on the installed signed 0.10.1 build.

These checks verify local use and isolated registration. Production registration
activation and the separate rules approvals remain pending; they do not establish
successful production account creation or complete cloud synchronization.

## Final APK artifacts

| Artifact | Size in bytes | SHA-256 |
| --- | ---: | --- |
| [`dist/spoon.apk`](../dist/spoon.apk) | 157,186,720 | `ce39c50f6a346ad60a48379b5d21e7f747b955e577f0ab46ebf6c16f06c0a0d0` |
| [`dist/spoon-debug.apk`](../dist/spoon-debug.apk) | 178,766,786 | `40c3ed6bab77c3f6b4f0e3e12ad0f484bf6ecbfd3d895aa539ed052a567c7065` |

The signing certificate is unchanged, preserving installation upgrades. Its
SHA-256 is
`892d89776ee0d6693a19b7cdfbdaeccb881b85c92ddf3b1476c40d90990f56b3`.

The bundled recipe catalog is unchanged. Its SHA-256 remains
`64ec01cefd16bd85b4d99f6d21b36122f6a7f35a391dc1c8852d2a17a9c55688`.
