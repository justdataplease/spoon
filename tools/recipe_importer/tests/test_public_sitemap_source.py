"""Synthetic sitemap regressions; no network access."""
from types import SimpleNamespace
import pytest
from tools.recipe_importer.public_sitemap_source import discover_sitemap


class Client:
    origin = "https://www.yiannislucacos.gr"

    def __init__(self, xml):
        self.xml = xml
        self.requested = []

    def get(self, url):
        self.requested.append(url)
        return SimpleNamespace(content=self.xml.encode())

    def validate(self, url):
        return url


def test_lucacos_external_video_is_reported_without_being_requested():
    client = Client('<urlset><url><loc>https://youtu.be/Example</loc></url><url><loc>https://www.yiannislucacos.gr/recipe/glyka/123/test</loc></url></urlset>')
    recipes = discover_sitemap("lucacos", client)
    assert recipes == [("https://www.yiannislucacos.gr/recipe/glyka/123/test", "")]
    assert client.requested == ["https://www.yiannislucacos.gr/sitemap.xml"]
    assert client.discovery_exclusions == [{"url": "https://youtu.be/Example", "reason": "external_video_not_recipe"}]


@pytest.mark.parametrize("xml", [
    '<urlset><url><loc>https://outside.example/recipe/glyka/123/test</loc></url></urlset>',
    '<urlset><url><loc>https://youtu.be/recipe/glyka/123/test</loc></url></urlset>',
    '<sitemapindex><sitemap><loc>https://youtu.be/Example</loc></sitemap></sitemapindex>',
])
def test_external_recipe_urls_and_child_sitemaps_still_fail_closed(xml):
    client = Client(xml)
    with pytest.raises(ValueError, match="unapproved publisher host"):
        discover_sitemap("lucacos", client)
    assert client.requested == ["https://www.yiannislucacos.gr/sitemap.xml"]
