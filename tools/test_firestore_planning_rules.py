"""Check owner-scoped planning writes against the local Firestore emulator.

Run with firebase emulators:exec --only firestore --project demo-spoon-planning --config firebase.planning-test.json
"python tools/test_firestore_planning_rules.py". Never targets a live database.
"""
import base64
import json
import os
import time
import unittest
import uuid
from urllib.error import HTTPError
from urllib.request import Request, urlopen

HOST = os.environ.get("FIRESTORE_EMULATOR_HOST", "")
PROJECT = os.environ.get("GCLOUD_PROJECT", "demo-spoon-planning")


def value(data):
    if isinstance(data, bool):
        return {"booleanValue": data}
    if isinstance(data, int):
        return {"integerValue": str(data)}
    if isinstance(data, float):
        return {"doubleValue": data}
    if data is None:
        return {"nullValue": None}
    if isinstance(data, str):
        return {"stringValue": data}
    if isinstance(data, list):
        return {"arrayValue": {"values": [value(item) for item in data]}}
    return {"mapValue": {"fields": {key: value(item) for key, item in data.items()}}}


def token(owner):
    def encode(data):
        return base64.urlsafe_b64encode(json.dumps(data).encode()).rstrip(b"=").decode()
    now = int(time.time())
    return encode({"alg": "none", "typ": "JWT"}) + "." + encode({
        "iss": "https://securetoken.google.com/" + PROJECT,
        "aud": PROJECT, "sub": owner, "user_id": owner,
        "iat": now, "exp": now + 3600,
        "firebase": {"sign_in_provider": "anonymous", "identities": {}},
    }) + "."


@unittest.skipUnless(HOST, "Requires FIRESTORE_EMULATOR_HOST")
class PlanningRulesTest(unittest.TestCase):
    def setUp(self):
        self.assertIn(HOST.split(":")[0], ("localhost", "127.0.0.1"))
        self.assertEqual(PROJECT, "demo-spoon-planning")
        self.owner = "planning-test-" + uuid.uuid4().hex
        self.preferences = {
            "excludedCategories": [], "veganOnly": False,
            "excludedIngredientTerms": [], "updatedAtEpochMillis": 100,
        }
        self.plan = {
            "date": "2026-09-07", "category": "fish", "recipeId": "", "recipeTitle": "",
            "filters": {"category": "fish", "easeLevel": "", "minRating": 0, "maxPrepMinutes": 0},
            "completed": False, "completionEventId": "", "updatedAtEpochMillis": 100,
        }

    def write(self, suffix, data, authenticated_owner=None):
        url = f"http://{HOST}/v1/projects/{PROJECT}/databases/(default)/documents/spoon/{self.owner}/{suffix}"
        request = Request(url, json.dumps({"fields": {key: value(item) for key, item in data.items()}}).encode(),
                          headers={"Content-Type": "application/json", "Authorization": "Bearer " + token(authenticated_owner or self.owner)}, method="PATCH")
        try:
            with urlopen(request, timeout=15) as response:
                return response.status
        except HTTPError as error:
            return error.code

    def read(self, suffix, authenticated_owner=None):
        url = f"http://{HOST}/v1/projects/{PROJECT}/databases/(default)/documents/spoon/{self.owner}/{suffix}"
        request = Request(url, headers={"Authorization": "Bearer " + token(authenticated_owner or self.owner)})
        try:
            with urlopen(request, timeout=15) as response:
                return response.status, json.load(response)
        except HTTPError as error:
            return error.code, None

    def commit(self, updates=(), deletes=(), authenticated_owner=None):
        root = f"projects/{PROJECT}/databases/(default)/documents/spoon/{self.owner}/"
        writes = [{"update": {"name": root + suffix, "fields": {k: value(v) for k, v in data.items()}}}
                  for suffix, data in updates]
        writes += [{"delete": root + suffix} for suffix in deletes]
        request = Request(f"http://{HOST}/v1/projects/{PROJECT}/databases/(default)/documents:commit",
                          json.dumps({"writes": writes}).encode(),
                          headers={"Content-Type": "application/json", "Authorization": "Bearer " + token(authenticated_owner or self.owner)}, method="POST")
        try:
            with urlopen(request, timeout=15) as response:
                return response.status
        except HTTPError as error:
            return error.code

    def test_all_courses_complete_atomically_and_keep_independent_history(self):
        plan = self.plan | {"recipeId": "main", "recipeTitle": "Main", "completedAtEpochMillis": 0}
        extra = {key: val for key, val in plan.items() if key != "date"}
        plan.update(side=extra | {"recipeId": "side", "recipeTitle": "Side", "locked": True},
                    dessert=extra | {"recipeId": "dessert", "recipeTitle": "Dessert"})
        self.assertEqual(200, self.write("mealPlans/2026-09-07", plan))
        histories = {}
        for index, role in enumerate(("main", "side", "dessert")):
            event_id = "cooked_" + str(index + 1) * 32
            timestamp = 200 + index
            course = plan if role == "main" else plan[role]
            course.update(completed=True, completionEventId=event_id,
                          completedAtEpochMillis=timestamp, updatedAtEpochMillis=timestamp)
            plan["updatedAtEpochMillis"] = timestamp
            history = {"date": plan["date"], "recipeId": course["recipeId"], "recipeTitle": course["recipeTitle"],
                       "completedAtEpochMillis": timestamp}
            histories[role] = (event_id, history)
            self.assertEqual(403, self.write("mealPlans/2026-09-07", plan))
            pair = (("mealPlans/2026-09-07", plan), ("cookedHistory/" + event_id, history))
            self.assertEqual(200, self.commit(pair), role)
            self.assertEqual(200, self.commit(pair), role + " exact replay")
        # Parent revisions change without changing any cooking timestamp.
        plan["updatedAtEpochMillis"] = 300
        self.assertEqual(200, self.write("mealPlans/2026-09-07", plan))
        for role, (event_id, history) in histories.items():
            self.assertEqual(403, self.commit(deletes=("cookedHistory/" + event_id,)))
        side_id = histories["side"][0]
        plan["side"].update(completed=False, completionEventId="", completedAtEpochMillis=0, updatedAtEpochMillis=301)
        plan["updatedAtEpochMillis"] = 301
        undo = (("mealPlans/2026-09-07", plan),)
        self.assertEqual(200, self.commit(undo, ("cookedHistory/" + side_id,)))
        self.assertEqual(200, self.commit(undo, ("cookedHistory/" + side_id,)))
        self.assertEqual(404, self.read("cookedHistory/" + side_id)[0])
        for role in ("main", "dessert"):
            self.assertEqual(403, self.commit(deletes=("cookedHistory/" + histories[role][0],)), role)
        self.assertEqual(403, self.write("mealPlans/2026-09-07", plan, "different-owner"))

    def test_nested_courses_reject_extra_fields_bad_types_and_missing_history(self):
        extra = {key: val for key, val in self.plan.items() if key != "date"}
        self.assertEqual(200, self.write("mealPlans/2026-09-07", self.plan | {"side": extra, "dessert": extra}))
        for invalid in ([], extra | {"locked": "yes"}, extra | {"date": "2026-09-08"},
                        extra | {"side": extra}, extra | {"completed": True},
                        extra | {"completedAtEpochMillis": -1}, extra | {"recipeTitle": "Stale"}):
            with self.subTest(invalid=invalid):
                self.assertEqual(403, self.write("mealPlans/2026-09-07", self.plan | {"side": invalid}))
        completed = extra | {"recipeId": "side", "recipeTitle": "Side", "completed": True,
                             "completionEventId": "cooked_" + "a" * 32, "completedAtEpochMillis": 300}
        for role in ("side", "dessert"):
            self.assertEqual(403, self.write("mealPlans/2026-09-07", self.plan | {role: completed}), role)

    def test_archived_history_imports_and_exact_replays_preserve_all_events(self):
        replanned = self.plan | {"recipeId": "new-main", "recipeTitle": "New main"}
        self.assertEqual(200, self.write("mealPlans/2026-09-07", replanned))
        for index, date in enumerate(("2026-09-07", "2026-09-07", "2026-09-06")):
            suffix = "cookedHistory/cooked_" + str(index + 1) * 32
            history = {"date": date, "recipeId": "archived-" + str(index),
                       "recipeTitle": "Archived meal " + str(index), "completedAtEpochMillis": 200 + index}
            with self.subTest(date=date, event=suffix):
                self.assertEqual(200, self.write(suffix, history))
                # Firestore field order is immaterial to an exact replay.
                self.assertEqual(200, self.write(suffix, dict(reversed(list(history.items())))))
                status, saved = self.read(suffix)
                self.assertEqual(200, status)
                self.assertEqual({key: value(item) for key, item in history.items()}, saved["fields"])
        self.assertEqual(404, self.read("mealPlans/2026-09-06")[0])
        self.assertEqual({key: value(item) for key, item in replanned.items()},
                         self.read("mealPlans/2026-09-07")[1]["fields"])

    def test_history_accepts_legacy_ids_and_timestamp_boundaries(self):
        history = {"date": "2026-09-07", "recipeId": "main", "recipeTitle": "Main",
                   "completedAtEpochMillis": 1}
        for event_id, timestamp in (("2026-09-07", 1), ("cooked_20260907_123", 253402300799999)):
            suffix = "cookedHistory/" + event_id
            data = history | {"completedAtEpochMillis": timestamp}
            self.assertEqual(200, self.write(suffix, data))
            self.assertEqual(200, self.write(suffix, data))

    def test_history_rejects_invalid_ids_and_fields(self):
        history = {"date": "2026-09-07", "recipeId": "main", "recipeTitle": "Main",
                   "completedAtEpochMillis": 200}
        for event_id in ("invalid", "2026-13-07", "cooked_abc", "cooked_" + "A" * 32):
            with self.subTest(event_id=event_id):
                self.assertEqual(403, self.write("cookedHistory/" + event_id, history))
        invalid_data = [history | update for update in (
            {"date": "invalid"}, {"date": "2026-13-07"}, {"date": "2026-09-32"}, {"date": 20260907},
            {"recipeId": ""}, {"recipeId": "not allowed"}, {"recipeId": "a" * 129},
            {"recipeTitle": ""}, {"recipeTitle": "x" * 301}, {"recipeTitle": True},
            {"completedAtEpochMillis": 0}, {"completedAtEpochMillis": -1},
            {"completedAtEpochMillis": 253402300800000}, {"completedAtEpochMillis": "200"},
            {"completedAtEpochMillis": 200.5}, {"completedAtEpochMillis": True},
            {"completedAtEpochMillis": None}, {"extra": True},
        )]
        invalid_data.extend({key: val for key, val in history.items() if key != missing} for missing in history)
        for data in invalid_data:
            with self.subTest(data=data):
                self.assertEqual(403, self.write("cookedHistory/cooked_" + "a" * 32, data))

    def test_history_replays_cannot_modify_stored_content(self):
        suffix = "cookedHistory/cooked_" + "a" * 32
        history = {"date": "2026-09-07", "recipeId": "main", "recipeTitle": "Main",
                   "completedAtEpochMillis": 200}
        self.assertEqual(200, self.write(suffix, history))
        for update in ({"date": "2026-09-08"}, {"recipeId": "other"}, {"recipeTitle": "Renamed"},
                       {"completedAtEpochMillis": 201}, {"completedAtEpochMillis": 200.0}, {"extra": True}):
            with self.subTest(update=update):
                self.assertEqual(403, self.write(suffix, history | update))
        self.assertEqual({key: value(item) for key, item in history.items()}, self.read(suffix)[1]["fields"])
        self.assertEqual(200, self.write(suffix, history))

    def test_history_creation_replay_reads_and_deletion_are_owner_scoped(self):
        suffix = "cookedHistory/cooked_" + "a" * 32
        history = {"date": "2026-09-07", "recipeId": "main", "recipeTitle": "Main",
                   "completedAtEpochMillis": 200}
        self.assertEqual(403, self.write(suffix, history, "other-owner"))
        self.assertEqual(200, self.write(suffix, history))
        self.assertEqual(403, self.write(suffix, history, "other-owner"))
        self.assertEqual(403, self.read(suffix, "other-owner")[0])
        self.assertEqual(403, self.commit(deletes=(suffix,), authenticated_owner="other-owner"))
        self.assertEqual(200, self.commit(deletes=(suffix,)))
        self.assertEqual(200, self.commit(deletes=(suffix,)))
        self.assertEqual(403, self.commit(deletes=(suffix,), authenticated_owner="other-owner"))
        self.assertEqual(403, self.commit(deletes=("cookedHistory/invalid",)))
        self.assertEqual(404, self.read(suffix)[0])

    def test_completed_plans_require_matching_imported_history(self):
        event_id = "cooked_" + "a" * 32
        history = {"date": "2026-09-07", "recipeId": "main", "recipeTitle": "Main",
                   "completedAtEpochMillis": 200}
        completed = self.plan | {"recipeId": "main", "recipeTitle": "Main", "completed": True,
                                 "completionEventId": event_id, "completedAtEpochMillis": 200}
        self.assertEqual(403, self.write("mealPlans/2026-09-07", completed))
        self.assertEqual(200, self.write("cookedHistory/" + event_id, history))
        for update in ({"recipeId": "other"}, {"recipeTitle": "Other"}, {"completedAtEpochMillis": 201}):
            with self.subTest(update=update):
                self.assertEqual(403, self.write("mealPlans/2026-09-07", completed | update))
        self.assertEqual(403, self.write("mealPlans/2026-09-08", completed | {"date": "2026-09-08"}))
        self.assertEqual(200, self.write("mealPlans/2026-09-07", completed))

    def test_existing_favorite_is_readable_but_all_updates_remain_forbidden(self):
        suffix = "favorites/main"
        favorite = {"recipeId": "main", "addedAtEpochMillis": 200}
        self.assertEqual(200, self.write(suffix, favorite))
        self.assertEqual(200, self.read(suffix)[0])
        self.assertEqual(403, self.write(suffix, favorite))
        self.assertEqual(403, self.write(suffix, favorite | {"addedAtEpochMillis": 201}))
        self.assertEqual(403, self.read(suffix, "other-owner")[0])
        self.assertEqual({key: value(item) for key, item in favorite.items()}, self.read(suffix)[1]["fields"])

    def test_source_exclusions_are_optional_bounded_unique_and_owner_scoped(self):
        keys = ["akis", "argiro", "gastronomos", "tsoulis", "cookpad", "lucacos", "funkycook"]
        self.assertEqual(200, self.write("preferences/meal", self.preferences))
        for excluded in ([], ["cookpad"], keys):
            self.assertEqual(200, self.write("preferences/meal", self.preferences | {"excludedSourceKeys": excluded}))
        self.assertEqual(403, self.write("preferences/meal", self.preferences | {"excludedSourceKeys": []}, "other-owner"))
        for invalid in ("akis", True, {}, ["akis", "akis"], ["AKIS"], ["unknown"], ["personal"], keys + ["extra"]):
            with self.subTest(exclusions=invalid):
                self.assertEqual(403, self.write("preferences/meal", self.preferences | {"excludedSourceKeys": invalid}))

    def test_legacy_preferences_and_new_planner_options(self):
        self.assertEqual(200, self.write("preferences/meal", self.preferences))
        self.preferences.update(weekdayCategories={"MONDAY": "meat", "TUESDAY": "any", "WEDNESDAY": "legumes"}, favoritesOnly=True,
                                sideWeekdayCategories={"MONDAY": "vegetables"}, dessertWeekdayCategories={"SUNDAY": "other"})
        self.assertEqual(200, self.write("preferences/meal", self.preferences))
        self.assertEqual(403, self.write("preferences/meal", self.preferences, "different-owner"))

    def test_drinks_category_is_valid_for_preferences_and_plans(self):
        self.assertEqual(200, self.write(
            "preferences/meal",
            self.preferences | {"excludedCategories": ["drinks"]},
        ))
        drinks_plan = self.plan | {
            "category": "drinks",
            "filters": self.plan["filters"] | {"category": "drinks"},
        }
        self.assertEqual(200, self.write("mealPlans/2026-09-07", drinks_plan))

    def test_reject_invalid_weekdays_categories_and_source_types(self):
        for update in (
            {"weekdayCategories": {"FUNDAY": "meat"}},
            {"weekdayCategories": {"MONDAY": "invalid"}},
            {"weekdayCategories": ["meat"]}, {"favoritesOnly": "true"},
            {"sideWeekdayCategories": {"FUNDAY": "meat"}},
            {"sideWeekdayCategories": {"MONDAY": "invalid"}},
            {"sideWeekdayCategories": ["vegetables"]},
            {"dessertWeekdayCategories": {"FUNDAY": "dessert"}},
            {"dessertWeekdayCategories": {"MONDAY": "invalid"}},
            {"dessertWeekdayCategories": ["dessert"]},
            {"unexpectedField": True},
        ):
            with self.subTest(update=update):
                self.assertEqual(403, self.write("preferences/meal", self.preferences | update))

    def test_unavailable_plan_roundtrip_and_recovery_to_recipe(self):
        self.assertEqual(200, self.write("mealPlans/2026-09-07", self.plan))
        selected = self.plan | {"recipeId": "favorite-fish", "recipeTitle": "Fish", "updatedAtEpochMillis": 101}
        self.assertEqual(200, self.write("mealPlans/2026-09-07", selected))
        self.assertEqual(200, self.write("mealPlans/2026-09-07", selected | {"locked": True}))
        self.assertEqual(403, self.write("mealPlans/2026-09-07", selected | {"locked": "true"}))
        self.assertEqual(200, self.write("mealPlans/2026-09-07", self.plan | {"updatedAtEpochMillis": 102}))
        self.assertEqual(403, self.write("mealPlans/2026-09-07", self.plan, "different-owner"))

    def test_unavailable_day_cannot_claim_recipe_title_completion_or_history(self):
        for update in (
            {"recipeTitle": "Stale title"}, {"completed": True},
            {"completionEventId": "cooked_" + "a" * 32},
            {"recipeId": "fish"}, {"category": "meat"},
        ):
            with self.subTest(update=update):
                self.assertEqual(403, self.write("mealPlans/2026-09-07", self.plan | update))


if __name__ == "__main__":
    unittest.main(verbosity=2)
