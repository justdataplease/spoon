# Spoon 0.8.6 (24)

People using Spoon can share any built-in catalog recipe from its
«Κοινοποίηση συνταγής» button. The Android share sheet sends the title and an HTTPS
link; tapping it opens the exact recipe in the recipient's Spoon app. Both phones
should install this version or newer. Personal recipes remain owner-private.

Recipe links use `https://justdataplease.github.io/spoon/recipe/<id>`. The root
Android association and browser fallback are published in
`justdataplease/justdataplease.github.io` at commit
`ae7a5064a9bf99cfaf93b6be999e3d8590dc8e39`; their source is retained in `website/`.
The fallback includes an explicit open-in-Spoon button and current APK download.
Android verifies links asynchronously after installation; if a messaging app keeps
a link in its browser, use the page's «Άνοιγμα στο Spoon» button.

The activity handles cold and warm links, retains an open shared recipe across
recreation, and does not reopen dismissed links. Back can cancel initial loading.
Unknown recipes show an update message; malformed URLs and private IDs are rejected.
An existing Explore collector indentation issue was also corrected for release lint.

Validation:

- All 317 JVM tests passed, including 8 new link validation/share text tests.
- All 3 Android 15 device tests passed: cold and successive warm links across all
  three providers, invalid/missing links, activity recreation and Back dismissal.
- All 3 website URL validation tests passed.
- Debug and optimized signed release APKs built; release lint passed.
- Live association returned HTTP 200 JSON with the matching APK certificate;
  Android reported `justdataplease.github.io: verified`.
- A normal HTTPS VIEW intent without an explicit package opened the signed Spoon
  release directly and displayed the expected recipe.
- The release share sheet showed the exact recipe title and HTTPS link.
- After confirmed background process death, the open recipe restored correctly;
  a dismissed recipe stayed closed after another process death and launch.
- Release APK installed successfully as an upgrade; signing certificate matches
  the previous APK (SHA-256 `892d89776ee0d6693a19b7cdfbdaeccb881b85c92ddf3b1476c40d90990f56b3`).

Release APK: `dist/spoon.apk` (92,567,588 bytes).
SHA-256: `4b04fa8c5a8a45f25184d7acf8cfd68aeddc00c192ee3277387ebe5f9f7af4c8`.

Debug APK: `dist/spoon-debug.apk` (114,252,465 bytes).
SHA-256: `2743f097ec1e88bf715ae25c28fdc4aec08817e0ddd3789824c70fef49bb1408`.
