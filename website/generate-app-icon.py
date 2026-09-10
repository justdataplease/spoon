"""Regenerate website icons from Android using CairoSVG or Playwright + a browser."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
ANDROID = "{http://schemas.android.com/apk/res/android}"
vector = ET.parse(ROOT / "app/src/main/res/drawable/ic_launcher_foreground.xml").getroot()
background = ET.parse(ROOT / "app/src/main/res/values/ic_launcher_background.xml").getroot().find("color").text
parts = [
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72" fill="none">',
    f'  <rect x="18" y="18" width="72" height="72" rx="18" fill="{background}"/>',
]
attributes = {
    "pathData": "d", "fillColor": "fill", "strokeColor": "stroke",
    "strokeWidth": "stroke-width", "strokeLineCap": "stroke-linecap",
    "strokeLineJoin": "stroke-linejoin",
}
for path in vector.findall("path"):
    values = " ".join(f'{target}="{path.attrib[ANDROID + source]}"' for source, target in attributes.items() if ANDROID + source in path.attrib)
    parts.append(f"  <path {values}/>")
parts.append("</svg>")
svg = "\n".join(parts) + "\n"
(ROOT / "website/app-icon.svg").write_text(svg, encoding="utf-8")
try:
    import cairosvg
except (ImportError, OSError):
    from playwright.sync_api import sync_playwright
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch()
        page = browser.new_page(viewport={"width": 512, "height": 512}, device_scale_factor=1)
        page.set_content('<style>html,body{margin:0;background:transparent}svg{display:block;width:512px;height:512px}</style>' + svg)
        page.locator("svg").screenshot(path=str(ROOT / "website/app-icon.png"), omit_background=True)
        browser.close()
else:
    cairosvg.svg2png(bytestring=svg.encode("utf-8"), write_to=str(ROOT / "website/app-icon.png"), output_width=512, output_height=512)
