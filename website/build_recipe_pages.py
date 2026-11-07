"""Write one small static page per catalog recipe for GitHub Pages.

Each page lives at spoon/recipe/<id>.html, which GitHub Pages serves at the
shared link https://justdataplease.github.io/spoon/recipe/<id>. It shows the
recipe's name, photo, category, time, a short description and the publisher,
links to the original recipe and opens it in the app. Ingredients and steps stay
with the publisher and in the app.

Usage:
    python website/build_recipe_pages.py --out <clone of justdataplease.github.io>
    python website/build_recipe_pages.py --out <dir> --db <recipe_catalog.db>

Pages for recipes that left the catalog are removed. Links to IDs without a page
(a newer app catalog) still fall back to 404.html.
"""

from __future__ import annotations

import argparse
import html
import json
import re
import sqlite3
import sys
import zlib
from pathlib import Path

SITE = "https://justdataplease.github.io"
RECIPE_DIR = "spoon/recipe"
APP_NAME = "Τι θα φάμε;"
APK_URL = "https://media.githubusercontent.com/media/justdataplease/spoon/main/dist/spoon.apk"
SAFE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$")
WHITESPACE = re.compile(r"\s+")
HASHTAG = re.compile(r"(?:^|\s)#\S+")

# Keep in step with RecipePublisherOptions.kt.
PUBLISHERS = {
    "akis": "Άκης Πετρετζίκης",
    "argiro": "Αργυρώ Μπαρμπαρίγου",
    "gastronomos": "Γαστρονόμος",
    "tsoulis": "Γιώργος Τσούλης",
    "cookpad": "Cookpad",
    "lucacos": "Γιάννης Λουκάκος",
    "funkycook": "Funky Cook",
}

DEFAULT_DB = Path(__file__).resolve().parent.parent / "app/src/main/assets/recipe_catalog.db"


def is_shareable_id(recipe_id: str) -> bool:
    return bool(SAFE_ID.match(recipe_id)) and not recipe_id.startswith("custom_")


def https_or_none(url: object) -> str | None:
    if isinstance(url, str) and url.startswith("https://") and not WHITESPACE.search(url):
        return url
    return None


def clean(text: object) -> str:
    return WHITESPACE.sub(" ", text).strip() if isinstance(text, str) else ""


def shorten(text: str, limit: int) -> str:
    if len(text) <= limit:
        return text
    cut = text[:limit].rsplit(" ", 1)[0].rstrip(" ,.;:·-–")
    return cut + "…"


def minutes_label(minutes: int) -> str:
    if minutes <= 0:
        return ""
    if minutes < 60:
        return f"{minutes} λεπτά"
    hours, rest = divmod(minutes, 60)
    label = f"{hours} ώρα" if hours == 1 else f"{hours} ώρες"
    return f"{label} και {rest} λεπτά" if rest else label


def recipe_minutes(recipe: dict, prep_minutes: int) -> int:
    total = recipe.get("totalMinutes")
    if isinstance(total, int) and total > 0:
        return total
    return prep_minutes if prep_minutes > 0 else 0


def description_of(recipe: dict, publisher: str) -> str:
    for key in ("description", "seoDescription"):
        text = clean(HASHTAG.sub(" ", clean(recipe.get(key))))
        if text:
            return text
    return f"Μια συνταγή από {publisher}."


def render(recipe_id: str, recipe: dict, source_key: str, prep_minutes: int) -> str | None:
    title = clean(recipe.get("title"))
    if not title or not is_shareable_id(recipe_id):
        return None
    publisher = PUBLISHERS.get(source_key) or clean(recipe.get("sourceName")) or "τον εκδότη"
    original = https_or_none(recipe.get("canonicalUrl")) or https_or_none(recipe.get("sourceUrl"))
    image = https_or_none(recipe.get("imageUrl"))
    description = description_of(recipe, publisher)
    meta = [clean(recipe.get("categoryLabel")), minutes_label(recipe_minutes(recipe, prep_minutes))]
    meta_line = " · ".join(part for part in meta if part)

    e = lambda text: html.escape(text, quote=True)  # noqa: E731
    link = f"{SITE}/{RECIPE_DIR}/{recipe_id}"
    intent = f"intent://recipe/{recipe_id}#Intent;scheme=spoon;package=com.spoon.app;end"
    og_image = image or f"{SITE}/app-icon.png"

    photo = (
        f'\n      <img class="recipe-photo" src="{e(image)}" alt="{e(title)}" referrerpolicy="no-referrer">'
        if image
        else ""
    )
    eyebrow = f'\n      <p class="eyebrow">{e(meta_line)}</p>' if meta_line else ""
    source = (
        f'\n      <p class="source">Από <a href="{e(original)}" rel="noopener">{e(publisher)}</a></p>'
        if original
        else f'\n      <p class="source">Από {e(publisher)}</p>'
    )
    original_button = (
        f'\n      <a class="button secondary" href="{e(original)}" rel="noopener">Δες την πρωτότυπη συνταγή <span aria-hidden="true">↗</span></a>'
        if original
        else ""
    )

    return f"""<!doctype html>
<html lang="el">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta name="theme-color" content="#B8442E">
    <meta name="referrer" content="no-referrer">
    <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'self'; img-src 'self' https:; base-uri 'none'; form-action 'none'">
    <meta name="robots" content="noindex">
    <title>{e(title)} · {APP_NAME}</title>
    <meta name="description" content="{e(shorten(description, 160))}">
    <link rel="canonical" href="{e(link)}">
    <meta property="og:site_name" content="{APP_NAME}">
    <meta property="og:title" content="{e(title)}">
    <meta property="og:description" content="{e(shorten(description, 160))}">
    <meta property="og:type" content="article">
    <meta property="og:locale" content="el_GR">
    <meta property="og:url" content="{e(link)}">
    <meta property="og:image" content="{e(og_image)}">
    <meta name="twitter:card" content="{'summary_large_image' if image else 'summary'}">
    <link rel="icon" type="image/svg+xml" href="/app-icon.svg">
    <link rel="apple-touch-icon" href="/app-icon.png">
    <link rel="stylesheet" href="/styles.css">
  </head>
  <body>
    <main class="card recipe">
      <a class="brand home" href="/"><img class="app-icon" src="/app-icon.svg" width="46" height="46" alt=""> {APP_NAME}</a>{photo}{eyebrow}
      <h1>{e(title)}</h1>
      <p class="description">{e(shorten(description, 280))}</p>{source}
      <a class="button" href="{e(intent)}">Άνοιγμα στο «{APP_NAME}»</a>{original_button}
      <div class="download">
        <p>Δεν έχεις την εφαρμογή;</p>
        <a class="download-link" href="{APK_URL}">Λήψη «{APP_NAME}» για Android <span aria-hidden="true">↗</span></a>
        <p class="hint">Εγκατάστησε την εφαρμογή και πάτησε ξανά τον σύνδεσμο. Η συνταγή θα ανοίξει κατευθείαν εκεί.</p>
      </div>
    </main>
    <footer>Το επόμενο γεύμα ξεκινά με μια ιδέα.</footer>
  </body>
</html>
"""


def build(db_path: Path, out_dir: Path) -> tuple[int, int]:
    target = out_dir / RECIPE_DIR
    target.mkdir(parents=True, exist_ok=True)
    written: set[str] = set()
    connection = sqlite3.connect(f"file:{db_path.as_posix()}?mode=ro", uri=True)
    try:
        rows = connection.execute("SELECT id, source_key, prep_minutes, recipe_json FROM recipes ORDER BY id")
        for recipe_id, source_key, prep_minutes, blob in rows:
            recipe = json.loads(zlib.decompress(blob))
            page = render(recipe_id, recipe, source_key, prep_minutes)
            if page is None:
                continue
            name = f"{recipe_id}.html"
            path = target / name
            if not path.exists() or path.read_text(encoding="utf-8") != page:
                path.write_text(page, encoding="utf-8", newline="\n")
            written.add(name)
    finally:
        connection.close()
    removed = 0
    for stale in target.glob("*.html"):
        if stale.name not in written:
            stale.unlink()
            removed += 1
    return len(written), removed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=Path, required=True, help="site root, e.g. a clone of justdataplease.github.io")
    parser.add_argument("--db", type=Path, default=DEFAULT_DB, help="recipe_catalog.db")
    args = parser.parse_args()
    if not args.db.is_file():
        print(f"catalog not found: {args.db}", file=sys.stderr)
        return 1
    count, removed = build(args.db, args.out)
    print(f"{count} recipe pages in {args.out / RECIPE_DIR} ({removed} removed)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
