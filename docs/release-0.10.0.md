# Τι θα φάμε; 0.10.0 (30)

Personal actions now save to an independent device SQLite database before any
cloud operation starts. A guest can cook/undo, use history and favorites, manage
shopping, save private notes and recipes/photos, and change preferences without
Firebase authentication or connectivity. Network and token failures leave these
actions usable. Explicit email/password sign-in can still fail for invalid
credentials or unavailable authentication service.

The database stores account-scoped records and durable pending revisions in the
same transaction. Cached server snapshots cannot erase local changes; matching
server write acknowledgements clear the queue. Retries preserve data after process
restarts, do not repeatedly enqueue a waiting SDK write, and use bounded bytes,
document counts and rule access budgets. Account UI separately reports local
storage, syncing, waiting and acknowledged sync.

Signing in to an administrator-provisioned email account automatically claims
existing guest data. Older anonymous installations also transfer local/cached
data; a persisted source UID and destination email recover a crash between sign-in
and the atomic local claim. Passwords never enter this transfer journal. Separate
signed-in accounts remain separate. Source deletions do not delete independent
records in the destination account, conflicting legacy history IDs are preserved,
and initial generated plans yield to existing cloud days. Local preferences, old
plan outboxes and available Firebase caches migrate on upgrade.

## Cloud deployment dependency

This release does not deploy or change Firestore security rules. Automatic approval
review blocked the policy change needed to accept archived history and exact
history retries. The approval request remains pending. The minimal owner-only diff
and verification requirements are in
[the review proposal](local-history-sync-rules-proposal.md). Source-exclusion
preference rules also require production verification.

Consequently, all-history cloud synchronization is not yet verified or complete.
Uploads begin automatically after successful sign-in and server reconciliation,
but records rejected by existing rules remain stored and queued locally. The app
does not mark rejected uploads as synced. Cloud transfer completion also requires
connectivity and time proportional to the pending data; it cannot be instantaneous
for an unbounded history. No production accounts or personal records were changed.

## Verification

- 426 JVM tests passed, including owner isolation, all seven personal collections,
  legacy multicourse history collisions, transfer recovery, acknowledged and stale
  revisions, destination protection, bounded upload preparation and sync wording.
- Three isolated device tests passed: actual repository CRUD/cooking/undo with
  Auth and Firestore both absent; 1,200 history events plus an embedded 650 KB photo
  through SQLite reopen/owner claim/deletion acknowledgement; and durable transfer
  intent write/reopen/clear. Tests used private files in emulator user 11.
- Release lint: zero errors, 129 warnings.
- The real installed app completed a meal in airplane mode, displayed Άκυρο and
  retained the event in History after a cold restart. The final signed APK then
  installed as an update, retained that completed state, and successfully undid
  it offline; the deletion survived another cold restart. Networking was restored.
- Both APKs retain the bundled 37,550-recipe catalog and the update-compatible
  signing identity. Recipe text is offline; publisher media that has never been
  cached still requires connectivity.

Evidence is retained locally in `build/local-sync-verify.log`,
`build/local-sync-device.log`, `build/release-0.10.0-*.xml`, and the artifact manifest.
The sync completion callback behavior and batch-rule limits follow the
[Firebase listener documentation](https://firebase.google.com/docs/firestore/query-data/listen)
and [rule conditions documentation](https://firebase.google.com/docs/firestore/security/rules-conditions).

## APKs

- `dist/spoon.apk`: 157,170,336 bytes; SHA-256 `2d14a5bbd6eb3353085e0991b965d3b8defc1c065f1ea04db7ab7bf105d4d5f7`.
- `dist/spoon-debug.apk`: 178,733,459 bytes; SHA-256 `7f7b4899cdfcac082e3679eaff2d0083240180773964e799869b23f4c33797b1`.

Catalog SHA-256: `64ec01cefd16bd85b4d99f6d21b36122f6a7f35a391dc1c8852d2a17a9c55688`.
Signer SHA-256: `892d89776ee0d6693a19b7cdfbdaeccb881b85c92ddf3b1476c40d90990f56b3`.
