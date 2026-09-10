# Proposed rules for the current app's categories and preferences

> Current status (2026-09-10 UTC): the exact owner-scoped changes below
> passed the isolated Firestore tests, received explicit user approval, and
> were deployed to `spoontheplanner`. The live source matches the tested
> rules. See [release verification](release-0.11.1.md). Firebase signup
> configuration was not changed.

## Archived proposal and predeployment baseline

The remaining document preserves the earlier proposal and production
baseline. References to pending approval, unapplied changes or production
restrictions below describe that earlier stage.

Original proposal status: inert review proposal. These category and preference changes have not
been approved for deployment, applied by this document, loaded into an emulator,
or deployed. Approval of Firebase signup or the separate history proposal does
not by itself approve the additional category and preference fields below.

## Verified production baseline

A read-only GET on 2026-09-10 retrieved the active `cloud.firestore` release and
its complete source using the existing project service account, without an
`x-goog-user-project` header. The active ruleset is
`projects/spoontheplanner/rulesets/8bb3a79e-8af2-4516-830f-72528073cc21`,
updated `2026-09-07T20:19:01.430670Z`. The SHA-256 of its source with LF line endings is
`458fa3afda6ede685f604372fa82687652e844cbefa93840557339279dc479e0`.

The exact source, release metadata and comparison are retained locally in the
ignored inspection snapshot
[`build/firestore-production-readback-2026-09-10.json`](../build/firestore-production-readback-2026-09-10.json).
This snapshot is evidence only; it is not an emulator or deployment input.

## Changes requiring approval

| Existing app feature | Production restriction | Proposed bounded change |
| --- | --- | --- |
| Drinks in planning, custom recipes and category exclusions | `drinks` is absent from the shared allowed category list. | Add only `drinks`; increase the maximum distinct excluded categories from nine to ten. The same category validation still applies wherever this helper is used. |
| Separate weekly categories for side dishes and desserts | The preferences document rejects `sideWeekdayCategories` and `dessertWeekdayCategories` as extra fields. | Accept these two optional maps, default missing values to an empty map, and validate them with the existing weekday/category validator. |
| User deselection of recipe sources | The preferences document rejects `excludedSourceKeys`. | Accept an optional list, default missing values to an empty list, and require at most seven distinct values drawn only from `akis`, `argiro`, `gastronomos`, `tsoulis`, `cookpad`, `lucacos`, and `funkycook`. |

The app's current preference serializer includes the side and dessert weekday
maps even when they are empty. Therefore production rejects ordinary preference
saves as well as saves with source exclusions. Adding the accepted fields allows
those existing local settings to be backed up. Drink selections also require the
shared category addition to pass the existing plan/custom-recipe validators.

## Access and validation that remain unchanged

Personal collections remain accessible only when `request.auth.uid == uid`.
This proposal does not change any collection match block, document path, read or
write operation permission, public catalog rule, account registration setting,
or App Check setting. Preference queries remain limited to the existing `meal`
document. Required preference fields, strict extra-field rejection, value types,
weekday checks, ingredient bounds and timestamps remain validated.

This is an expansion of accepted owner-authored values and fields, not an
expansion of who may access another user's data. It needs explicit approval
because it changes the production rules contract.

## Exact live-to-workspace diff

These are exactly the four category/preference hunks from the verified active
source to the current workspace's existing `firestore.rules`. They contain no
history, favorites or account-registration changes. The text below is inert.

```diff
--- LIVE_FIRESTORE_RULES
+++ WORKSPACE_FIRESTORE_RULES
@@ -41,6 +41,7 @@
         'street_food',
         'pasta_rice',
         'dessert',
+        'drinks',
         'other',
         'any'
       ];
@@ -79,6 +80,12 @@
         validCategory(values.get('SUNDAY', 'any'));
     }

+    function validExcludedSourceKeys(values) {
+      return values is list && values.size() <= 7 &&
+        values.size() == values.toSet().size() &&
+        values.hasOnly(['akis', 'argiro', 'gastronomos', 'tsoulis', 'cookpad', 'lucacos', 'funkycook']);
+    }
+
     function validMealPreferences(data) {
       return data.keys().hasAll([
           'excludedCategories',
@@ -92,12 +99,18 @@
           'excludedIngredientTerms',
           'updatedAtEpochMillis',
           'weekdayCategories',
-          'favoritesOnly'
+          'sideWeekdayCategories',
+          'dessertWeekdayCategories',
+          'favoritesOnly',
+          'excludedSourceKeys'
         ]) &&
         validWeekdayCategories(data.get('weekdayCategories', {})) &&
+        validWeekdayCategories(data.get('sideWeekdayCategories', {})) &&
+        validWeekdayCategories(data.get('dessertWeekdayCategories', {})) &&
         data.get('favoritesOnly', false) is bool &&
+        validExcludedSourceKeys(data.get('excludedSourceKeys', [])) &&
         data.excludedCategories is list &&
-        data.excludedCategories.size() <= 9 &&
+        data.excludedCategories.size() <= 10 &&
         data.excludedCategories.size() == data.excludedCategories.toSet().size() &&
         data.excludedCategories.hasOnly([
           'legumes',
@@ -108,6 +121,7 @@
           'street_food',
           'pasta_rice',
           'dessert',
+          'drinks',
           'other'
         ]) &&
         data.veganOnly is bool &&
```

## Separate history proposal

The additional policy needed for archived cooking history, exact history replay,
and retrying an already absent history deletion is documented separately in
[the local-history synchronization proposal](local-history-sync-rules-proposal.md).
Its approval and verification are separate from the category/preference changes
in this document. Neither proposal changes Firebase signup configuration.

## Verification required after approval

Use only the isolated `demo-spoon-planning` emulator project before deployment.
Verify that each new valid field and `drinks` value is accepted for its owner;
reject another owner's access, unknown source keys, duplicate source keys,
oversized source lists, malformed weekday maps and unknown extra fields. Keep
legacy preference documents valid when all three added fields are absent, and
rerun the existing planning/preferences rules checks. Compare the final reviewed
source against a fresh read of the active production rules before deploying it.
