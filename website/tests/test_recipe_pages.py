"""Checks for build_recipe_pages.py. Run: python -m unittest discover website/tests"""

import json
import sqlite3
import sys
import tempfile
import unittest
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import build_recipe_pages as pages  # noqa: E402


def make_db(path: Path, rows: list[tuple[str, str, int, dict]]) -> None:
    connection = sqlite3.connect(path)
    connection.execute("CREATE TABLE recipes (id TEXT PRIMARY KEY, source_key TEXT, prep_minutes INTEGER, recipe_json BLOB)")
    connection.executemany(
        "INSERT INTO recipes VALUES (?, ?, ?, ?)",
        [(i, s, m, zlib.compress(json.dumps(r).encode())) for i, s, m, r in rows],
    )
    connection.commit()
    connection.close()


class RecipePagesTest(unittest.TestCase):
    def test_page_shows_recipe_and_escapes_text(self):
        page = pages.render(
            "argiro_1",
            {
                "title": 'Πίτα <script>alert("x")</script>',
                "description": "Νόστιμη   πίτα. #ΧρυσόΠιρούνι",
                "categoryLabel": "Πίτες",
                "totalMinutes": 80,
                "imageUrl": "https://example.gr/p.jpg",
                "canonicalUrl": "https://www.argiro.gr/recipe/pita/",
            },
            "argiro",
            0,
        )
        self.assertIn("Πίτα &lt;script&gt;", page)
        self.assertNotIn("<script>", page)
        self.assertIn("Νόστιμη πίτα.</p>", page)
        self.assertNotIn("#", page.split("<body>")[1].split("intent://")[0])
        self.assertIn("Πίτες · 1 ώρα και 20 λεπτά", page)
        self.assertIn("Αργυρώ Μπαρμπαρίγου", page)
        self.assertIn('href="https://www.argiro.gr/recipe/pita/"', page)
        self.assertIn('content="https://example.gr/p.jpg"', page)
        self.assertIn("intent://recipe/argiro_1#Intent;scheme=spoon;package=com.spoon.app;end", page)
        self.assertIn('href="https://justdataplease.github.io/spoon/recipe/argiro_1"', page)

    def test_unsafe_urls_and_missing_parts_fall_back(self):
        page = pages.render(
            "cookpad_2",
            {"title": "Σούπα", "imageUrl": "javascript:alert(1)", "canonicalUrl": "http://x"},
            "cookpad",
            0,
        )
        self.assertNotIn("javascript:", page)
        self.assertNotIn("http://x", page)
        self.assertNotIn("recipe-photo", page)
        self.assertIn("app-icon.png", page)
        self.assertIn("Μια συνταγή από Cookpad.", page)

    def test_private_or_bad_ids_get_no_page(self):
        for recipe_id in ["custom_1", "_a", "a/b", "a.b", ""]:
            self.assertIsNone(pages.render(recipe_id, {"title": "x"}, "akis", 0), recipe_id)
        self.assertIsNone(pages.render("a1", {"title": "  "}, "akis", 0))

    def test_build_writes_pages_and_removes_stale_ones(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            db = tmp / "c.db"
            make_db(db, [("1006", "akis", 40, {"title": "Πεϊνιρλί"}), ("custom_9", "akis", 0, {"title": "x"})])
            stale = tmp / "out/spoon/recipe/old.html"
            stale.parent.mkdir(parents=True)
            stale.write_text("old")
            count, removed = pages.build(db, tmp / "out")
            self.assertEqual((count, removed), (1, 1))
            self.assertTrue((tmp / "out/spoon/recipe/1006.html").is_file())
            self.assertFalse(stale.exists())

    def test_minutes_labels(self):
        self.assertEqual(pages.minutes_label(0), "")
        self.assertEqual(pages.minutes_label(45), "45 λεπτά")
        self.assertEqual(pages.minutes_label(60), "1 ώρα")
        self.assertEqual(pages.minutes_label(150), "2 ώρες και 30 λεπτά")


if __name__ == "__main__":
    unittest.main()
