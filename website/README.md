# «Τι θα φάμε;» shared-recipe landing page

Publish the contents of this folder to the root of the
`justdataplease/justdataplease.github.io` repository with GitHub Pages serving
`main` from `/`. Keep `.nojekyll` so the root `.well-known/assetlinks.json` remains
public. The deployment needs no server, dependencies, build, or workflow.

Android verifies `https://justdataplease.github.io/.well-known/assetlinks.json`
against package `com.spoon.app` and the existing personal-release signing
certificate. The verified URL is
`https://justdataplease.github.io/spoon/recipe/<id>`.

## Recipe pages

`build_recipe_pages.py` writes one small page per catalog recipe to
`spoon/recipe/<id>.html`, which GitHub Pages serves at the shared link. It shows
the name, photo (loaded from the publisher), category, time, a short
description and the publisher, with buttons to open the recipe in the app or on
the publisher's site. Ingredients and steps are not published. The pages carry
Open Graph tags, so chat apps show the dish photo and name in link previews.
Pages are `noindex` and need no JavaScript.

Publish or refresh them after a catalog change:

```powershell
git clone https://github.com/justdataplease/justdataplease.github.io <site>
# copy .nojekyll, .well-known, *.html, *.mjs, styles.css, app-icon.* into <site>
python website/build_recipe_pages.py --out <site>
# commit and push <site>
```

The script removes pages of recipes that left the catalog. Run its checks with
`python -m unittest discover -s website/tests -p "test_*.py"`.

## Fallback page

Links to IDs that have no page (for example from a newer app catalog) get
`404.html`. GitHub Pages serves `404.html` for those paths. It only offers the explicit
app intent for canonical public recipe IDs: ASCII letters/digits first,
letters/digits/underscores/hyphens thereafter, maximum 128 characters, excluding
the private `custom_` prefix. Query strings, fragments, extra segments, trailing
slashes, encoded IDs, and other hosts are rejected. Recipe details remain in the
app's bundled catalog; nothing is uploaded to this website.

The page never redirects or downloads automatically. Unsupported or uninstalled
devices can deliberately download the current APK using the displayed link.

Run link validation checks with:

```powershell
node --test website/tests/recipe-link.test.mjs
```

After publishing, verify that the association returns HTTP 200 with JSON content,
and exercise a shared link on a release-signed Android install. Android App Links route
directly into the verified app before loading any page.

The landing and recipe pages use the Android app name, terracotta color, and the
same plate/question-mark launcher icon. `app-icon.svg` is the browser favicon and
visible logo; `app-icon.png` supplies social previews and the Apple touch icon.
Publish both assets together with the HTML, CSS, and modules. Social apps may
cache previews of links that have already been shared.

To regenerate icons after changing Android launcher artwork, install CairoSVG with its native Cairo library, or Playwright with a
Chromium browser (`python -m playwright install chromium`), and run `python website/generate-app-icon.py`. The published site uses the
checked-in outputs and still needs no build or runtime dependencies.

Run all website checks with `node --test website/tests/*.test.mjs`.
