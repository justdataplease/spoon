from pathlib import Path

import pytest

from tools.recipe_importer.inspect_recipe import (
    InspectionError,
    RecipeInspectorClient,
    extract_metadata_from_html,
    inspect_recipe_url,
)


FIXTURE = Path(__file__).parent / "fixtures" / "recipe.html"


def test_extracts_only_allowlisted_metadata_from_json_ld():
    metadata = extract_metadata_from_html(
        FIXTURE.read_text(encoding="utf-8"),
        "https://akispetretzikis.com/recipe/123/synthetic?tracking=discarded",
    )

    assert metadata == {
        "id": "123",
        "title": "Synthetic salmon street-food bowl",
        "language": "el",
        "sourceUrl": "https://akispetretzikis.com/recipe/123/synthetic",
        "categoryKeys": ["fish", "dirty"],
        "prepMinutes": 65,
        "cookMinutes": 45,
        "totalMinutes": 110,
        "preparationCount": 2,
        "stepCount": 3,
        "rating10": 8.5,
        "ease": "moderate",
    }
    serialized = str(metadata).casefold()
    assert "ingredient" not in serialized
    assert "private step" not in serialized
    assert "example.invalid" not in serialized


def test_rejects_listing_other_hosts_and_english_recipe_urls():
    with pytest.raises(InspectionError):
        extract_metadata_from_html("", "https://akispetretzikis.com/recipes/fish")
    with pytest.raises(InspectionError):
        extract_metadata_from_html("", "https://example.com/recipe/123/test")
    with pytest.raises(InspectionError, match="/en/recipe URLs are rejected"):
        extract_metadata_from_html(
            FIXTURE.read_text(encoding="utf-8"),
            "https://akispetretzikis.com/en/recipe/123/synthetic",
        )


def test_accepts_el_alias_but_records_the_greek_canonical_url():
    metadata = extract_metadata_from_html(
        FIXTURE.read_text(encoding="utf-8"),
        "https://akispetretzikis.com/el/recipe/123/synthetic",
    )
    assert metadata["language"] == "el"
    assert metadata["sourceUrl"] == "https://akispetretzikis.com/recipe/123/synthetic"


def test_rejects_non_greek_document_even_on_greek_path():
    html = FIXTURE.read_text(encoding="utf-8").replace('lang="el"', 'lang="en"')
    with pytest.raises(InspectionError, match="Greek document language"):
        extract_metadata_from_html(
            html,
            "https://akispetretzikis.com/recipe/123/synthetic",
        )


def test_network_inspection_requires_explicit_permission_before_request():
    with pytest.raises(PermissionError):
        inspect_recipe_url(
            "https://akispetretzikis.com/recipe/123/test",
            have_permission=False,
        )


class FakeResponse:
    def __init__(self, *, status_code, text, url, content_type="text/plain"):
        self.status_code = status_code
        self.text = text
        self.url = url
        self.content = text.encode("utf-8")
        self.headers = {"Content-Type": content_type}
        self.is_redirect = False
        self.is_permanent_redirect = False


class FakeSession:
    def __init__(self, responses):
        self.headers = {}
        self.responses = list(responses)
        self.calls = []

    def get(self, url, **kwargs):
        self.calls.append((url, kwargs))
        return self.responses.pop(0)


def test_robots_denial_stops_before_recipe_request(monkeypatch):
    robots_url = "https://akispetretzikis.com/robots.txt"
    session = FakeSession(
        [
            FakeResponse(
                status_code=200,
                text="User-agent: *\nDisallow: /recipe/",
                url=robots_url,
            )
        ]
    )
    client = RecipeInspectorClient(session=session, min_delay_seconds=0)
    monkeypatch.setattr(client.rate_limiter, "wait", lambda: None)

    with pytest.raises(InspectionError, match="robots.txt does not allow"):
        client.inspect("https://akispetretzikis.com/recipe/123/test")
    assert len(session.calls) == 1
    assert client.rate_limiter.interval_seconds >= 1
