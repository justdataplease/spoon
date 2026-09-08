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
            self.assertEqual(200, self.commit((("mealPlans/2026-09-07", plan), ("cookedHistory/" + event_id, history))), role)
        # Parent revisions change without changing any cooking timestamp.
        plan["updatedAtEpochMillis"] = 300
        self.assertEqual(200, self.write("mealPlans/2026-09-07", plan))
        for role, (event_id, history) in histories.items():
            self.assertEqual(403, self.commit(deletes=("cookedHistory/" + event_id,)))
        side_id = histories["side"][0]
        plan["side"].update(completed=False, completionEventId="", completedAtEpochMillis=0, updatedAtEpochMillis=301)
        plan["updatedAtEpochMillis"] = 301
        self.assertEqual(200, self.commit((("mealPlans/2026-09-07", plan),), ("cookedHistory/" + side_id,)))
        self.assertEqual(403, self.commit(deletes=("cookedHistory/" + histories["main"][0],)))
        self.assertEqual(403, self.write("mealPlans/2026-09-07", plan, "different-owner"))

    def test_nested_courses_reject_extra_fields_bad_types_and_orphan_history(self):
        extra = {key: val for key, val in self.plan.items() if key != "date"}
        self.assertEqual(200, self.write("mealPlans/2026-09-07", self.plan | {"side": extra, "dessert": extra}))
        for invalid in ([], extra | {"locked": "yes"}, extra | {"date": "2026-09-08"},
                        extra | {"side": extra}, extra | {"completed": True},
                        extra | {"completedAtEpochMillis": -1}, extra | {"recipeTitle": "Stale"}):
            with self.subTest(invalid=invalid):
                self.assertEqual(403, self.write("mealPlans/2026-09-07", self.plan | {"side": invalid}))
        history = {"date": self.plan["date"], "recipeId": "side", "recipeTitle": "Side", "completedAtEpochMillis": 300}
        self.assertEqual(403, self.write("cookedHistory/cooked_" + "a" * 32, history))

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
