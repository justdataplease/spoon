"""Authorized Giorgos Tsoulis Greek recipe sitemap crawler."""
if __package__ in (None, ""):
    import sys
    from pathlib import Path
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from tools.recipe_importer.public_sitemap_source import run_source
else:
    from .public_sitemap_source import run_source

if __name__ == "__main__":
    raise SystemExit(run_source("tsoulis"))
