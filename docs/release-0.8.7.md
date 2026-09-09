# Spoon 0.8.7 (25)

Recipe source badges now use original website images: Akis at 384px, Argiro and
Gastronomos at 512px. The previous files were 32px and 128px favicons. The new
images are bundled unchanged for offline display; [source URLs and hashes](source-icons.md)
are recorded alongside the implementation.

Badges fit each complete logo inside a padded light canvas with rounded corners.
This prevents clipping and preserves dark logo details in dark mode. The large
blank border inside the Akis image is excluded from its display frame so its seal
has a comparable visible size. Weekly cards wrap their category and source badges
when space is limited instead of squeezing the source image.

Source matching now prioritizes canonical provider keys and actual URL hosts,
then recognizes Greek names with or without accents. Recipe URL text mentioning
another publisher cannot override the correct source. An audit of all 20,861
bundled records confirmed that their current provider metadata already resolves.

Validation:

- All 322 JVM tests passed, including 5 provider identity regression tests.
- Debug and optimized signed release builds succeeded; release lint passed.
- Installed the release as an upgrade on the Android 15 emulator.
- Verified all three badges at full size in both light and dark modes with
  accessibility bounds and screenshots; restored the original emulator theme.
- Release signing certificate matches the existing APK identity.

Release APK: `dist/spoon.apk` (92,600,640 bytes).
SHA-256: `474e3d463364761ccb5f0acd84494af1496589ec4f37edf992ba45a77f5431cf`.

Debug APK: `dist/spoon-debug.apk` (114,064,630 bytes).
SHA-256: `4d3b4ef36ffb9319309cecbeb72f499e5aa0b10405a8dd7c7463f0457aab052e`.
