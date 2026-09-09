# Spoon shared-recipe landing page

Publish the contents of this folder to the root of the
`justdataplease/justdataplease.github.io` repository with GitHub Pages serving
`main` from `/`. Keep `.nojekyll` so the root `.well-known/assetlinks.json` remains
public. The deployment needs no server, dependencies, build, or workflow.

Android verifies `https://justdataplease.github.io/.well-known/assetlinks.json`
against package `com.spoon.app` and the existing personal-release signing
certificate. The verified URL is
`https://justdataplease.github.io/spoon/recipe/<id>`.

GitHub Pages serves `404.html` for recipe paths. It only offers the explicit
Spoon intent for canonical public recipe IDs: ASCII letters/digits first,
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
