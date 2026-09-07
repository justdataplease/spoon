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

    def test_legacy_preferences_and_new_planner_options(self):
        self.assertEqual(200, self.write("preferences/meal", self.preferences))
        self.preferences.update(weekdayCategories={"MONDAY": "meat", "TUESDAY": "any", "WEDNESDAY": "legumes"}, favoritesOnly=True)
        self.assertEqual(200, self.write("preferences/meal", self.preferences))
        self.assertEqual(403, self.write("preferences/meal", self.preferences, "different-owner"))

    def test_reject_invalid_weekdays_categories_and_source_types(self):
        for update in (
            {"weekdayCategories": {"FUNDAY": "meat"}},
            {"weekdayCategories": {"MONDAY": "invalid"}},
            {"weekdayCategories": ["meat"]}, {"favoritesOnly": "true"},
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
