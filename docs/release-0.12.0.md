# Τι θα φάμε; 0.12.0 (35)

**Search by title in any script.** Search in **Βρες** and **Συλλογή** now reads
only recipe titles, and it reads them the way you type. Case, accents and the
final ς no longer matter. Greek, Greeklish and Latin spellings meet as well:
«ράμεν» finds "Ramen", and "souvlaki" finds «σουβλάκι». Titles in which your
word starts a word come first, so «ράμεν» lists ramen dishes before
«αποξηραμένα». A new **Ολόκληρες λέξεις** chip under the search box matches
each word only as a complete title word, like a regular-expression `\bword\b`.

The installed catalog gains a small `recipe_title_keys` table. The app builds
it on the device when the catalog is installed or updated, so the packaged
catalog is unchanged. A search with paging takes milliseconds.

**Lists in Συλλογή.** Every save heart opens a popup. **Αγαπημένα** is always
ticked and holds every saved recipe; tick any of your own lists as well, or
create one in the popup. Συλλογή shows the lists as chips above the recipes,
with rename and delete for the selected list. Deleting a list keeps its
recipes in Αγαπημένα, and removing a recipe from the collection removes it
from every list. Existing favorites appear in Αγαπημένα. Lists are kept on the
device: favorites keep syncing, but list membership does not yet.

**Back where you were.** Opening a recipe from Βρες or Συλλογή and going back
returns to the same place in the list instead of the top.

**Personal recipe photos in Cloud Storage.** For signed-in accounts, a recipe
photo now uploads to Cloud Storage. The synced recipe stores only its download
URL instead of an inline image of up to 640 KB, and a replaced photo is
deleted. The phone keeps its own copy, so photos still work offline and
without an account. If Storage is unavailable, sync falls back to the inline
photo, so it never stalls. A photo picked in the editor can no longer be lost
when other fields change while it is still being processed.

## Verification

- All 484 JVM tests passed, with no failures, errors or skipped tests. They
  include new tests for transliterated title keys, whole-word and word-start
  matching, title-only filtering, list storage, and photo upload with its
  inline fallback.
- Release lint: zero errors, 131 warnings.
- All 19 existing Firestore rules tests and 4 new photo rules tests passed on
  the Firestore and Storage emulators. The photo tests cover owner-only paths,
  JPEG type and size limits, and rejection of foreign or arbitrary photo URLs.
- Checked on Android: Latin "souvlaki" found 175 Greek titles, and 62 with
  Ολόκληρες λέξεις. "ramen" listed ramen titles first. The scroll position
  survived opening a recipe. The list popup created, ticked and filtered a
  list, and lists survived a restart. Personal photos survived save, edit,
  back navigation and restart, including a 12-megapixel photo.
- The signed release was installed as an update over 0.11.2 and checked again
  with R8 shrinking active.

## Cloud setup

Applied to `spoontheplanner` on 2026-10-27:

- Enabled the Cloud Storage for Firebase API.
- Created the default bucket `spoontheplanner.firebasestorage.app` in `EU`.
- Deployed `storage.rules`, and deployed `firestore.rules` with the new
  `photoUrl` rule. Before the deploy, the live Firestore rules were identical
  to 0.11.2.
- Added `firebasestorage.googleapis.com` to the Android key's allowed APIs. The
  `com.spoon.app` app restriction is unchanged.

## APKs

| Artifact | SHA-256 |
| --- | --- |
| `dist/spoon.apk` | `75c2f1d4d6b911b4fbfb85e2e019b73742fa5984a554dec6f1189346356fabc3` |
| `dist/spoon-debug.apk` | `afed4f0ebdfdd78d89a38bf5f94cc0091d6c400ef4237f6f882e677bb36d125f` |

The signing certificate is unchanged:
`892d89776ee0d6693a19b7cdfbdaeccb881b85c92ddf3b1476c40d90990f56b3`.
