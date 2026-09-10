# Proposed rules for local history synchronization

Status: review proposal only. This document has not changed Firestore rules or
production configuration. Automatic approval review rejected the attempted local policy edit
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

The current production read confirmed that source exclusions and side/dessert
weekday preference maps are unsupported. Their additional category/preference
changes are documented separately in
[the current-app sync proposal](current-app-sync-rules-proposal.md). Full
preference backup cannot be claimed until its applicable policy is approved,
verified and deployed.

## Read-only production check

An initial read attempt on 2026-09-10 used the existing local Google identity
with an `x-goog-user-project` header and returned 403 because that caller lacked
`serviceusage.services.use` on `spoontheplanner`. No role, credential, cloud
configuration or production data was changed by that attempt.

A later read-only verification on the same date used the existing project
service account `firebase-adminsdk-fbsvc@spoontheplanner.iam.gserviceaccount.com`
without the quota header. Both the active release and its complete ruleset
source returned HTTP 200. The active release is
`projects/spoontheplanner/releases/cloud.firestore`, referencing
`projects/spoontheplanner/rulesets/8bb3a79e-8af2-4516-830f-72528073cc21`,
with update time `2026-09-07T20:19:01.430670Z`.

The historical deployment record available locally was dated
`2026-09-07T20:19:01.430670Z` and referenced ruleset
`8bb3a79e-8af2-4516-830f-72528073cc21`. The successful current GET now confirms
that this recorded ruleset remains active; the conclusion no longer relies only
on the historical record.

The live history match block is identical to the current workspace block:
creation requires a matching completed current plan, every update is denied,
and deletion requires an existing valid event with no protected active course
reference. The three proposed history changes above therefore apply directly
to the verified live baseline. No rules were edited, loaded or deployed by the
verification.

The readback also confirmed that live rules do not yet accept `drinks`, side and
dessert weekday preference maps, or source exclusions. Those additional accepted
values and fields require separate approval and are described, with the exact
four live-to-workspace diff hunks, in
[the current-app categories and preferences proposal](current-app-sync-rules-proposal.md).
The ignored local evidence is
[`build/firestore-production-readback-2026-09-10.json`](../build/firestore-production-readback-2026-09-10.json).
