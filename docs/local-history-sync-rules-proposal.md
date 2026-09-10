# Proposed rules for local history synchronization

Status: review proposal only. No Firestore rules or production configuration have
been changed. Automatic approval review rejected the attempted local policy edit
because the exact change to owner history creation and favorite updates had not
been explicitly authorized.

The app must save personal actions on the device and preserve them through
process restarts regardless of account or network availability. After successful
sign-in, its durable pending changes can begin synchronization. The existing
cloud rules reject several valid synchronization cases described below.

## Required policy changes

All reads and writes remain restricted to `request.auth.uid == uid`. Collection
paths, document fields, value bounds, public catalog permissions and App Check
configuration remain unchanged.

| Collection/action | Current restriction | Proposed behavior |
| --- | --- | --- |
| `cookedHistory` create | The event must match the current plan for its date. | Allow an owner to create a document passing `validCookedMeal`. A meal cooked before sign-in, or before that date was replanned, can be imported. |
| `cookedHistory` update | Every update is denied. | Allow only an exact replay: valid incoming document and `request.resource.data == resource.data`. Changes to date, recipe, title or completion time remain denied. |
| `cookedHistory` delete | Requires a valid existing document and no active plan reference. | Also allow the owner to retry deletion of an already absent document. Existing events remain protected while a completed main, side or dessert course references them. |

Completed meal plans must continue to reference a matching history event through
`historyConfirmsCourse`. Importing archived history does not weaken that
plan-to-history validation. Existing unrelated account data stays inaccessible.
History rows are private records authored by their owner; this change does not
make them a trusted record of independently verified cooking activity.

Do not work around the current rule by temporarily rewriting a cloud day plan
for every archived event. Other devices could observe those fabricated plan
transitions, and concurrent edits would be at risk.

Favorites do not require a policy expansion. The sync client must read before
creating a favorite and treat an already existing valid favorite as acknowledged,
preserving its addition timestamp. A remove followed by a new add is represented
by the final desired favorite state; it need not overwrite a remote addition
timestamp to represent that state. A read-and-create race can retry by re-reading.

## Exact proposed diff

This text is inert and has not been applied or loaded into an emulator. The
unused `historyMatchesCompletedPlan` helper can remain to keep the policy diff
minimal; it will no longer be called by history creation. No favorite rule changes
are included in this proposal.

```diff
     match /spoon/{uid}/cookedHistory/{historyId} {
       allow get, list: if isOwner(uid);
       allow create: if isOwner(uid) &&
-        validCookedMeal(request.resource.data, historyId) &&
-        historyMatchesCompletedPlan(uid, request.resource.data, historyId);
-      allow update: if false;
-      allow delete: if isOwner(uid) &&
-        validCookedMeal(resource.data, historyId) &&
-        historyCanBeDeleted(uid, resource.data, historyId);
+        validCookedMeal(request.resource.data, historyId);
+      allow update: if isOwner(uid) &&
+        validCookedMeal(request.resource.data, historyId) &&
+        request.resource.data == resource.data;
+      allow delete: if isOwner(uid) && validHistoryId(historyId) && (
+        !exists(/databases/$(database)/documents/spoon/$(uid)/cookedHistory/$(historyId)) ||
+        (validCookedMeal(resource.data, historyId) &&
+          historyCanBeDeleted(uid, resource.data, historyId))
+      );
     }
```

The equality check compares the complete field map, including value types; field
ordering is immaterial in Firestore. It does not permit changes to stored history
content. Already absent history deletion is allowed only for a valid history ID.

## Required emulator verification

Use only the isolated `demo-spoon-planning` project and the local emulator defined
in `firebase.planning-test.json`; never use production account data.

1. Import multiple historical meals for one replanned day and a day without a
   current plan. Repeat the exact imports and verify success without changing IDs.
2. Reject modified history content, malformed IDs/dates, extra fields, empty or
   oversized titles, invalid timestamp types and out-of-range timestamps.
3. Reject creation, replay and deletion by a different account.
4. Preserve rejection of completed plans without matching history; accept an
   atomic plan/history pair and exact replay of the same pair.
5. Reject deleting history referenced by an active main, side or dessert course;
   accept atomic undo plus deletion and repetition after the history is absent.
6. Keep favorite updates forbidden, and verify the client acknowledges an existing
   favorite through reads without issuing a forbidden update.
7. Run every existing planning, preferences, category and nested-course test.

The current harness is `tools/test_firestore_planning_rules.py`. Its existing
expectation that archived history without a matching current plan is rejected
must intentionally change only after approval of the policy above.

## Release and deployment dependency

Preparing an APK or pushing source to GitHub does not deploy Firestore rules.
Until these changes are approved, applied, tested and deployed, archived history
imports and certain repeated acknowledgements may still be rejected by the server. The app must retain all such data and pending work
locally, communicate pending sync separately from local usability, and retry
after server policy allows the writes. It must not mark rejected writes as synced.

The prior source-exclusion preference rule change also requires checking the
currently deployed rules before claiming full preference backup. No production
rule equivalence has been established by this review proposal.

## Read-only production check

On 2026-09-10 the existing local Google identity could not read the active rules
release. A GET request with the API-required `x-goog-user-project` header returned
403: the caller lacks `serviceusage.services.use` on `spoontheplanner`. No role,
credential, cloud configuration or production data was changed.

The last historical deployment record available locally is dated
2026-09-07T20:19:01.430670Z and references ruleset
`8bb3a79e-8af2-4516-830f-72528073cc21`. That old record does not establish which
rules are currently deployed. A permitted read of the active release and its
source is required before the deployment diff can be certified.
