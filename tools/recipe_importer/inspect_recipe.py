"""Inspect exactly one permitted recipe URL and emit minimal JSON metadata."""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from html.parser import HTMLParser
from pathlib import Path
from typing import Any
from urllib.parse import urljoin, urlsplit, urlunsplit
from urllib.robotparser import RobotFileParser

import requests

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from helpers import (  # type: ignore[import-not-found]
        classify_category_keys,
        classify_ease,
        count_preparation_sections,
        count_recipe_steps,
        derive_total_minutes,
        parse_duration_minutes,
        rating_to_ten,
    )
else:
    from .helpers import (
        classify_category_keys,
        classify_ease,
        count_preparation_sections,
        count_recipe_steps,
        derive_total_minutes,
        parse_duration_minutes,
        rating_to_ten,
    )


ALLOWED_HOSTS = {"akispetretzikis.com", "www.akispetretzikis.com"}
RECIPE_PATH_RE = re.compile(r"^/(?:el/)?recipe/(?P<id>\d+)(?:/[^/?#]+)?/?$")
USER_AGENT = "SpoonRecipeMetadataInspector/1.0"
MAX_RESPONSE_BYTES = 5 * 1024 * 1024
MINIMUM_DELAY_SECONDS = 1.0


class InspectionError(RuntimeError):
    """Raised when a guarded network or extraction condition is not met."""


class _JsonLdScriptParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.blocks: list[str] = []
        self.document_language: str | None = None
        self._capturing = False
        self._parts: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attributes = {key.casefold(): (value or "") for key, value in attrs}
        if tag.casefold() == "html":
            self.document_language = attributes.get("lang") or None
            return
        if tag.casefold() != "script":
            return
        if attributes.get("type", "").casefold().split(";")[0].strip() == "application/ld+json":
            self._capturing = True
            self._parts = []

    def handle_data(self, data: str) -> None:
        if self._capturing:
            self._parts.append(data)

    def handle_endtag(self, tag: str) -> None:
        if tag.casefold() == "script" and self._capturing:
            self.blocks.append("".join(self._parts))
            self._capturing = False
            self._parts = []


def _is_recipe_type(value: object) -> bool:
    values = value if isinstance(value, list) else [value]
    return any(str(item).casefold() == "recipe" for item in values)


def _recipe_nodes(value: Any):
    if isinstance(value, list):
        for item in value:
            yield from _recipe_nodes(item)
    elif isinstance(value, dict):
        if _is_recipe_type(value.get("@type")):
            yield value
        graph = value.get("@graph")
        if graph is not None:
            yield from _recipe_nodes(graph)


def _clean_source_url(url: str) -> str:
    parts = urlsplit(url)
    path = parts.path[3:] if parts.path.startswith("/el/recipe/") else parts.path
    return urlunsplit(("https", "akispetretzikis.com", path, "", ""))


def _validate_recipe_url(url: str) -> tuple[str, str]:
    parts = urlsplit(url)
    if parts.scheme.casefold() != "https":
        raise InspectionError("recipe URL must use HTTPS")
    if parts.username or parts.password or parts.port not in (None, 443):
        raise InspectionError("recipe URL must not contain credentials or a non-standard port")
    host = (parts.hostname or "").casefold().rstrip(".")
    if host not in ALLOWED_HOSTS:
        raise InspectionError("only akispetretzikis.com recipe URLs are allowed")
    match = RECIPE_PATH_RE.fullmatch(parts.path)
    if not match:
        raise InspectionError(
            "URL must be a Greek numeric Akis recipe page; /en/recipe URLs are rejected"
        )
    return _clean_source_url(url), match.group("id")


def _duration(value: object) -> int | None:
    try:
        return parse_duration_minutes(value)
    except ValueError:
        return None


def extract_metadata_from_html(html: str, source_url: str) -> dict[str, object]:
    """Extract an allowlisted summary from Recipe JSON-LD only."""

    clean_url, source_id = _validate_recipe_url(source_url)
    parser = _JsonLdScriptParser()
    parser.feed(html)
    document_language = (parser.document_language or "").casefold()
    if document_language != "el" and not document_language.startswith("el-"):
        raise InspectionError("recipe page must declare Greek document language (el)")

    recipe: dict[str, Any] | None = None
    for block in parser.blocks:
        try:
            candidate = json.loads(block)
        except (TypeError, json.JSONDecodeError):
            continue
        recipe = next(_recipe_nodes(candidate), None)
        if recipe is not None:
            break
    if recipe is None:
        raise InspectionError("no valid Schema.org Recipe JSON-LD was found")

    title = str(recipe.get("name") or "").strip()
    if not title:
        raise InspectionError("Recipe JSON-LD has no title")

    instructions = recipe.get("recipeInstructions")
    step_count = count_recipe_steps(instructions)
    preparation_count = count_preparation_sections(instructions)
    prep_minutes = _duration(recipe.get("prepTime"))
    cook_minutes = _duration(recipe.get("cookTime"))
    total_minutes = derive_total_minutes(
        prep_minutes,
        cook_minutes,
        _duration(recipe.get("totalTime")),
    )
    ease = classify_ease(preparation_count, step_count, total_minutes)

    # Do not add description, instruction/ingredient text, image, video, or
    # nutrition fields here. import_catalog.py enforces the same allowlist.
    return {
        "id": source_id,
        "title": title,
        "language": "el",
        "sourceUrl": clean_url,
        "categoryKeys": classify_category_keys(
            title,
            recipe.get("recipeCategory"),
            recipe.get("keywords"),
        ),
        "prepMinutes": prep_minutes,
        "cookMinutes": cook_minutes,
        "totalMinutes": total_minutes,
        "preparationCount": preparation_count,
        "stepCount": step_count,
        "rating10": rating_to_ten(recipe.get("aggregateRating")),
        "ease": ease,
    }


class _RateLimiter:
    def __init__(self, interval_seconds: float) -> None:
        self.interval_seconds = max(MINIMUM_DELAY_SECONDS, interval_seconds)
        self._last_request_at: float | None = None

    def wait(self) -> None:
        if self._last_request_at is not None:
            remaining = self.interval_seconds - (time.monotonic() - self._last_request_at)
            if remaining > 0:
                time.sleep(remaining)
        self._last_request_at = time.monotonic()


class RecipeInspectorClient:
    def __init__(
        self,
        *,
        session: requests.Session | None = None,
        min_delay_seconds: float = 2.0,
        timeout_seconds: float = 15.0,
    ) -> None:
        self.session = session or requests.Session()
        self.session.headers.update({"User-Agent": USER_AGENT, "Accept": "text/html,application/xhtml+xml"})
        self.rate_limiter = _RateLimiter(min_delay_seconds)
        self.timeout_seconds = timeout_seconds

    def _get(
        self,
        url: str,
        robots: RobotFileParser | None = None,
        *,
        require_greek_recipe: bool = False,
    ) -> requests.Response:
        current = url
        for _ in range(6):
            _validate_same_site_url(current)
            if require_greek_recipe:
                _validate_recipe_url(current)
            if robots is not None and not robots.can_fetch(USER_AGENT, current):
                raise InspectionError(f"robots.txt does not allow inspection of {current}")
            self.rate_limiter.wait()
            response = self.session.get(
                current,
                allow_redirects=False,
                timeout=self.timeout_seconds,
            )
            if len(response.content) > MAX_RESPONSE_BYTES:
                raise InspectionError("response exceeds the 5 MiB safety limit")
            if response.is_redirect or response.is_permanent_redirect:
                location = response.headers.get("Location")
                if not location:
                    raise InspectionError("redirect response has no Location header")
                current = urljoin(current, location)
                continue
            return response
        raise InspectionError("too many redirects")

    def inspect(self, url: str) -> dict[str, object]:
        clean_url, _ = _validate_recipe_url(url)
        parts = urlsplit(clean_url)
        robots_url = f"https://{parts.hostname}/robots.txt"
        robots_response = self._get(robots_url)

        robots = RobotFileParser()
        robots.set_url(robots_url)
        if robots_response.status_code in (401, 403):
            raise InspectionError("robots.txt access was denied; failing closed")
        if robots_response.status_code >= 500:
            raise InspectionError("robots.txt is temporarily unavailable; failing closed")
        if robots_response.status_code == 200:
            robots.parse(robots_response.text.splitlines())
        elif 400 <= robots_response.status_code < 500:
            # RFC 9309 treats most 4xx responses as "unavailable" (no rules).
            robots.parse([])
        else:
            raise InspectionError(f"unexpected robots.txt status {robots_response.status_code}")

        if not robots.can_fetch(USER_AGENT, clean_url):
            raise InspectionError("robots.txt does not allow this recipe URL")
        response = self._get(clean_url, robots=robots, require_greek_recipe=True)
        if response.status_code != 200:
            raise InspectionError(f"recipe request returned HTTP {response.status_code}")
        content_type = response.headers.get("Content-Type", "").casefold()
        if content_type and "html" not in content_type:
            raise InspectionError(f"recipe response is not HTML ({content_type})")
        return extract_metadata_from_html(response.text, response.url or clean_url)


def _validate_same_site_url(url: str) -> None:
    parts = urlsplit(url)
    host = (parts.hostname or "").casefold().rstrip(".")
    if parts.scheme.casefold() != "https" or host not in ALLOWED_HOSTS:
        raise InspectionError("refusing a redirect outside the approved HTTPS site")
    if parts.username or parts.password or parts.port not in (None, 443):
        raise InspectionError("refusing URL credentials or a non-standard port")


def inspect_recipe_url(
    url: str,
    *,
    have_permission: bool,
    session: requests.Session | None = None,
    min_delay_seconds: float = 2.0,
    timeout_seconds: float = 15.0,
) -> dict[str, object]:
    if not have_permission:
        raise PermissionError(
            "network inspection requires explicit permission and --i-have-permission"
        )
    return RecipeInspectorClient(
        session=session,
        min_delay_seconds=min_delay_seconds,
        timeout_seconds=timeout_seconds,
    ).inspect(url)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Inspect one permitted Akis recipe page and print minimal JSON-LD metadata."
    )
    parser.add_argument("url", help="One explicit numeric recipe URL")
    parser.add_argument(
        "--i-have-permission",
        action="store_true",
        help="Confirm that you have written permission for this inspection and intended use.",
    )
    parser.add_argument("--min-delay-seconds", type=float, default=2.0)
    parser.add_argument("--timeout-seconds", type=float, default=15.0)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        metadata = inspect_recipe_url(
            args.url,
            have_permission=args.i_have_permission,
            min_delay_seconds=args.min_delay_seconds,
            timeout_seconds=args.timeout_seconds,
        )
    except (InspectionError, PermissionError, requests.RequestException) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(metadata, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
