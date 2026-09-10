# Publisher source icons

Publisher badges keep their circular artwork and open the publisher website independently of the surrounding recipe card or source-selection chip. The seven website destinations live in `RecipePublisherOptions.kt`, shared by badges and About. Recipe attribution includes a small invitation to visit the creator, linked to that recipe’s original URL; unknown and personal sources do not gain a publisher badge link.

These original publisher PNG files are bundled unchanged for source attribution and offline display. They are not generated or upscaled. The badge presents each mark on a circular white canvas in both themes.

| Publisher | Original website asset | Native size | SHA-256 |
| --- | --- | --- | --- |
| akis | [Official image](https://akispetretzikis.com/icons/icon-384x384.png) | 384 x 384 | `797d33e97339ed683c88438dd909447549f5499dd4f007831f371914885aa468` |
| argiro | [Official image](https://www.argiro.gr/wp-content/uploads/2025/04/cropped-%CE%91rgiro-favicon.png) | 512 x 512 | `10316254b8d531960563768da99ddce7970f4a26042cfa82c893ccc3d8f7e9aa` |
| gastronomos | [Official image](https://www.gastronomos.gr/wp-content/uploads/2021/06/cropped-SimaGastro_AM-500x500-1.png) | 512 x 512 | `9f01b503ac4997edadf174b6ccbb9a75f9e8c9fd8d35a263f20625634117b7fc` |

Akis publishes the available icon sizes in [site.webmanifest](https://akispetretzikis.com/site.webmanifest). Argiro and Gastronomos link their icons in their homepage metadata; the unsuffixed WordPress image URLs are the native 512px originals.

Keep these raster assets in `drawable-nodpi` so Android does not pre-scale them as density-specific resources. The 34dp badge uses Fit inside a circle with no decorative border or inset padding. The light canvas preserves dark or transparent logo details, and Argiro's red background fills the circle.

Akis includes a large blank border inside its official PNG. The painter skips the outer one-sixth on each side, leaving the complete seal and its white margin visible at a comparable size to the other logos. The stored image bytes remain unchanged.


## Additional publishers (2026-09-10)

The same circular badges use the publishers' own website artwork:

| Publisher | Android asset | Official source |
| --- | --- | --- |
| Γιώργος Τσούλης | `source_tsoulis.png` (180 × 180) | https://www.giorgostsoulis.com/apple-icon-180x180.png |
| Γιάννης Λουκάκος | `source_lucacos.png` (48 × 48) | https://www.yiannislucacos.gr/favicon.ico |
| Funky Cook | `source_funkycook.jpg` (210 × 210) | [Official favicon](https://funkycook.gr/wp-content/uploads/2015/05/logo_mobile-210x210.jpg) |
| Cookpad | `source_cookpad.png` (152 × 152) | [Official Apple touch icon](https://global-web-assets.cpcdn.com/assets/favicons/apple-touch-icon-152x152-d8ef6f4b35aee81d7d317da0ef1254f12907a4275fa70c9b5fa4f347cf221119.png) |

Lucacos uses the original favicon's largest frame, losslessly extracted as PNG.
Tsoulis keeps the original PNG. These assets are publisher marks used for source
attribution; recipe rights and source links are also shown in recipe details and About.

SHA-256: Tsoulis `14212c5a70adfe5a571ba39358da4d82503b35d94224066f735bde7b2fab2c75`;
Lucacos `941ac043658d56ac202ebbc410ad3dda2174bd0dfc542f4badc52bb7b4423d30`.

Funky Cook and Cookpad retain their original downloaded bytes. SHA-256: Funky Cook
`79056d3f77ca0f969bf35041ca675c5be1178f2ef93be86dfa9c7c5e96753358`;
Cookpad `50dd1eb5b821a7d36c8016046a71bb8298db43c553b60bbd29fa902ac82a1807`.
