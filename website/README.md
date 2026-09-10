# «Τι θα φάμε;» shared-recipe landing page

Publish the contents of this folder to the root of the
`justdataplease/justdataplease.github.io` repository with GitHub Pages serving
`main` from `/`. Keep `.nojekyll` so the root `.well-known/assetlinks.json` remains
public. The deployment needs no server, dependencies, build, or workflow.

Android verifies `https://justdataplease.github.io/.well-known/assetlinks.json`
against package `com.spoon.app` and the existing personal-release signing
certificate. The verified URL is
`https://justdataplease.github.io/spoon/recipe/<id>`.

GitHub Pages serves `404.html` for recipe paths. It only offers the explicit
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
and exercise a shared link on a release-signed Android install. A browser visit to
a recipe path deliberately returns the Pages custom 404 document; Android App
Links route directly into the verified app before loading that fallback page.

The landing and recipe pages use the Android app name, terracotta color, and the
same plate/question-mark launcher icon. `app-icon.svg` is the browser favicon and
visible logo; `app-icon.png` supplies social previews and the Apple touch icon.
Publish both assets together with the HTML, CSS, and modules. Social apps may
cache previews of links that have already been shared.

To regenerate icons after changing Android launcher artwork, install CairoSVG with its native Cairo library, or Playwright with a
Chromium browser (`python -m playwright install chromium`), and run `python website/generate-app-icon.py`. The published site uses the
checked-in outputs and still needs no build or runtime dependencies.

Run all website checks with `node --test website/tests/*.test.mjs`.
