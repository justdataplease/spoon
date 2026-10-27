"""Check personal recipe photo rules (Firestore photoUrl and Cloud Storage) on the emulators.

Run with firebase emulators:exec --only firestore,storage --project demo-spoon-photos
--config firebase.photo-test.json "python tools/test_recipe_photo_rules.py". Never targets a
live project.
"""
import base64
import json
import os
import time
import unittest
import uuid
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen

FIRESTORE = os.environ.get("FIRESTORE_EMULATOR_HOST", "")
STORAGE = os.environ.get("FIREBASE_STORAGE_EMULATOR_HOST", "")
PROJECT = os.environ.get("GCLOUD_PROJECT", "demo-spoon-photos")
BUCKET = PROJECT + ".appspot.com"
JPEG = b"\xff\xd8\xff\xe0" + b"\x00" * 64 + b"\xff\xd9"


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
        "firebase": {"sign_in_provider": "password", "identities": {}},
    }) + "."


def status(request):
    try:
        with urlopen(request) as response:
            return response.status
    except HTTPError as error:
        return error.code


def photo_name():
    return uuid.uuid4().hex + ".jpg"


def download_url(owner, recipe_id, name):
    path = quote(f"spoon/{owner}/customRecipes/{recipe_id}/{name}", safe="")
    return f"https://firebasestorage.googleapis.com/v0/b/{BUCKET}/o/{path}?alt=media&token={uuid.uuid4()}"


@unittest.skipUnless(FIRESTORE and STORAGE, "Requires the Firestore and Storage emulators")
class RecipePhotoRulesTest(unittest.TestCase):
    def setUp(self):
        for host in (FIRESTORE, STORAGE):
            self.assertIn(host.split(":")[0], ("localhost", "127.0.0.1"))
        self.owner = "owner" + uuid.uuid4().hex[:12]
        self.recipe_id = f"custom_{uuid.uuid4()}"

    def recipe(self, **extra):
        now = int(time.time() * 1000)
        return {
            "title": "Ράμεν", "description": "", "category": "meat", "prepMinutes": 10,
            "cookMinutes": 20, "servings": "",
            "ingredientSections": [{"title": "", "ingredients": [{"title": "Noodles", "unit": "", "quantity": "", "info": ""}]}],
            "methodSections": [{"title": "", "steps": ["Βράσε"]}],
            "photoDataUri": "", "active": True, "createdAtEpochMillis": now, "updatedAtEpochMillis": now,
            **extra,
        }

    def write_recipe(self, owner, fields):
        url = f"http://{FIRESTORE}/v1/projects/{PROJECT}/databases/(default)/documents/spoon/{owner}/customRecipes/{self.recipe_id}"
        body = json.dumps({"fields": {key: value(item) for key, item in fields.items()}}).encode()
        return status(Request(url, data=body, method="PATCH", headers={
            "Authorization": "Bearer " + token(owner), "Content-Type": "application/json",
        }))

    def upload(self, owner, path_owner, name, content_type="image/jpeg", data=JPEG):
        # Like the SDKs, send the content type as metadata; simple uploads ignore the header.
        object_name = f"spoon/{path_owner}/customRecipes/{self.recipe_id}/{name}"
        boundary = uuid.uuid4().hex
        metadata = json.dumps({"name": object_name, "contentType": content_type}).encode()
        body = (
            f"--{boundary}\r\nContent-Type: application/json; charset=utf-8\r\n\r\n".encode() + metadata +
            f"\r\n--{boundary}\r\nContent-Type: {content_type}\r\n\r\n".encode() + data +
            f"\r\n--{boundary}--".encode()
        )
        return status(Request(f"http://{STORAGE}/v0/b/{BUCKET}/o?name={quote(object_name, safe='')}", data=body, method="POST", headers={
            "Authorization": "Firebase " + token(owner),
            "Content-Type": f"multipart/related; boundary={boundary}",
            "X-Goog-Upload-Protocol": "multipart",
        }))

    def read(self, owner, path_owner, name):
        path = quote(f"spoon/{path_owner}/customRecipes/{self.recipe_id}/{name}", safe="")
        return status(Request(f"http://{STORAGE}/v0/b/{BUCKET}/o/{path}?alt=media", headers={
            "Authorization": "Firebase " + token(owner),
        }))

    def test_recipe_accepts_owned_photo_url_and_legacy_inline_photo(self):
        url = download_url(self.owner, self.recipe_id, photo_name())
        self.assertEqual(200, self.write_recipe(self.owner, self.recipe(photoUrl=url)))
        self.assertEqual(200, self.write_recipe(self.owner, self.recipe(photoDataUri="data:image/jpeg;base64,YWJj")))

    def test_recipe_rejects_foreign_or_arbitrary_photo_urls(self):
        foreign = download_url("someoneelse", self.recipe_id, photo_name())
        self.assertEqual(403, self.write_recipe(self.owner, self.recipe(photoUrl=foreign)))
        self.assertEqual(403, self.write_recipe(self.owner, self.recipe(photoUrl="https://example.com/photo.jpg")))

    def test_owner_uploads_and_reads_own_photo(self):
        name = photo_name()
        self.assertEqual(200, self.upload(self.owner, self.owner, name))
        self.assertEqual(200, self.read(self.owner, self.owner, name))
        self.assertEqual(403, self.read("intruder", self.owner, name))

    def test_storage_rejects_other_paths_types_and_sizes(self):
        self.assertEqual(403, self.upload("intruder", self.owner, photo_name()))
        self.assertEqual(403, self.upload(self.owner, self.owner, photo_name(), content_type="image/png"))
        self.assertEqual(403, self.upload(self.owner, self.owner, "photo.jpg"))
        self.assertEqual(403, self.upload(self.owner, self.owner, photo_name(), data=b"\xff" * 525001))


if __name__ == "__main__":
    unittest.main()
