# Publisher source icons

These original publisher PNG files are bundled unchanged for source attribution and offline display. They are not generated or upscaled. The badge preserves the full image on a white background in both themes.

| Publisher | Original website asset | Native size | SHA-256 |
| --- | --- | --- | --- |
| akis | [Official image](https://akispetretzikis.com/icons/icon-384x384.png) | 384 x 384 | `797d33e97339ed683c88438dd909447549f5499dd4f007831f371914885aa468` |
| argiro | [Official image](https://www.argiro.gr/wp-content/uploads/2025/04/cropped-%CE%91rgiro-favicon.png) | 512 x 512 | `10316254b8d531960563768da99ddce7970f4a26042cfa82c893ccc3d8f7e9aa` |
| gastronomos | [Official image](https://www.gastronomos.gr/wp-content/uploads/2021/06/cropped-SimaGastro_AM-500x500-1.png) | 512 x 512 | `9f01b503ac4997edadf174b6ccbb9a75f9e8c9fd8d35a263f20625634117b7fc` |

Akis publishes the available icon sizes in [site.webmanifest](https://akispetretzikis.com/site.webmanifest). Argiro and Gastronomos link their icons in their homepage metadata; the unsuffixed WordPress image URLs are the native 512px originals.

Keep these raster assets in `drawable-nodpi` so Android does not pre-scale them as density-specific resources. The 34dp badge uses Fit with padding, and its light canvas preserves dark or transparent logo details.

Akis includes a large blank border inside its official PNG. The painter skips the outer one-sixth on each side, leaving the complete seal and its white margin visible at a comparable size to the other logos. The stored image bytes remain unchanged.
