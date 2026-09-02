"""Low-volume book metadata and recommendation helpers backed by Open Library."""

from __future__ import annotations

import asyncio
import re
import time
from typing import Any

import httpx


OPEN_LIBRARY_BASE = "https://openlibrary.org"
OPEN_LIBRARY_HEADERS = {
    "User-Agent": "Freedify/1.0 (https://github.com/bar3200/Freedify-alldebrid)",
    "Accept": "application/json",
}
DISCOVERY_CACHE_TTL = 60 * 60 * 6

_cache: dict[str, tuple[float, dict[str, Any]]] = {}

# Open Library subjects are catalog labels, not a genre taxonomy. Keep only
# reader-facing shelves and collapse common aliases to one stable display name.
# More specific aliases must precede broader ones (True Crime before Crime).
GENRE_ALIASES = (
    ("Full Cast", ("full cast",)),
    ("Historical Fiction", ("historical fiction", "historical novel")),
    ("Science Fiction", ("science fiction", "sci fi", "space opera")),
    ("Literary Fiction", ("literary fiction",)),
    ("Contemporary Fiction", ("contemporary fiction",)),
    ("Young Adult", ("young adult", "ya fiction")),
    ("True Crime", ("true crime",)),
    ("Self-Help", ("self help", "personal development")),
    ("Fantasy", ("fantasy",)),
    ("Mystery", ("mystery", "detective fiction", "detective and mystery")),
    ("Thriller", ("thriller", "suspense", "psychological fiction")),
    ("Romance", ("romance", "love stories")),
    ("Horror", ("horror", "ghost stories")),
    ("Biography", ("biography", "biographical")),
    ("Memoir", ("memoir", "autobiography")),
    ("Business", ("business", "entrepreneurship")),
    ("Philosophy", ("philosophy",)),
    ("Humor", ("humor", "humour", "comedy")),
    ("Classics", ("classic fiction", "classics")),
    ("Adventure", ("adventure",)),
    ("Crime", ("crime", "criminal fiction")),
    ("History", ("history",)),
    ("Science", ("popular science", "science")),
    ("Politics", ("politics", "political science")),
    ("Religion & Spirituality", ("spirituality", "religion")),
    ("Nonfiction", ("nonfiction", "non fiction")),
)
GENRE_PRIORITY = {name: len(GENRE_ALIASES) - index for index, (name, _) in enumerate(GENRE_ALIASES)}


def _normalize(value: str) -> str:
    return " ".join(re.findall(r"[a-z0-9]+", str(value).lower()))


def select_discovery_genres(subjects: list[str], limit: int = 3) -> list[str]:
    """Choose stable, human-friendly genres from noisy catalog subjects."""
    unique: dict[str, str] = {}
    for subject in subjects:
        clean = " ".join(str(subject).replace("_", " ").split()).strip(" ,.;")
        normalized = _normalize(clean)
        if not normalized or len(clean) > 60 or any(char.isdigit() for char in clean):
            continue
        canonical = next(
            (name for name, aliases in GENRE_ALIASES if any(alias in normalized for alias in aliases)),
            None,
        )
        if canonical:
            unique.setdefault(canonical.lower(), canonical)

    ranked = sorted(
        unique.items(),
        key=lambda item: (-GENRE_PRIORITY[item[1]], item[1].lower()),
    )
    return [display for _, display in ranked[:limit]]


def _book_score(book: dict[str, Any], title: str, author: str) -> int:
    wanted_title = _normalize(title)
    candidate_title = _normalize(book.get("title", ""))
    wanted_author = _normalize(author)
    candidate_authors = [_normalize(value) for value in book.get("author_name", [])]
    score = 0
    if candidate_title == wanted_title:
        score += 100
    elif wanted_title and (wanted_title in candidate_title or candidate_title in wanted_title):
        score += 50
    wanted_words = set(wanted_title.split())
    candidate_words = set(candidate_title.split())
    if wanted_words:
        score += round(40 * len(wanted_words & candidate_words) / len(wanted_words))
    if wanted_author and any(
        wanted_author == candidate or wanted_author in candidate or candidate in wanted_author
        for candidate in candidate_authors
        if candidate
    ):
        score += 50
    return score


def _cover_url(cover_id: Any) -> str | None:
    return f"https://covers.openlibrary.org/b/id/{cover_id}-M.jpg" if cover_id else None


def _recommendation_from_work(work: dict[str, Any], genres: list[str]) -> dict[str, Any] | None:
    title = str(work.get("title") or "").strip()
    if not title:
        return None
    authors = work.get("authors") or []
    author_names = work.get("author_name") or []
    author = next((str(item.get("name")).strip() for item in authors if item.get("name")), "")
    if not author and author_names:
        author = str(author_names[0]).strip()
    cover_id = work.get("cover_id") or work.get("cover_i")
    return {
        "id": work.get("key") or f"openlibrary:{_normalize(title)}:{_normalize(author)}",
        "title": title,
        "author": author,
        "cover_image": _cover_url(cover_id),
        "first_publish_year": work.get("first_publish_year"),
        "genres": genres,
        "source": "openlibrary",
        "availability_query": " ".join(value for value in (title, author) if value),
    }


async def _get_json(client: httpx.AsyncClient, url: str, **params: Any) -> dict[str, Any]:
    """Fetch Open Library JSON, retrying only temporary upstream failures."""
    for attempt in range(3):
        try:
            response = await client.get(url, params=params, headers=OPEN_LIBRARY_HEADERS)
            response.raise_for_status()
            return response.json()
        except (httpx.TimeoutException, httpx.NetworkError, httpx.HTTPStatusError) as error:
            status = getattr(getattr(error, "response", None), "status_code", None)
            retryable = status is None or status == 429 or status >= 500
            if not retryable or attempt == 2:
                raise
            retry_after = getattr(getattr(error, "response", None), "headers", {}).get("Retry-After")
            try:
                delay = min(4.0, max(0.25, float(retry_after))) if retry_after else 0.75 * (2 ** attempt)
            except (TypeError, ValueError):
                delay = 0.75 * (2 ** attempt)
            await asyncio.sleep(delay)


def _unavailable_result(title: str, author: str, genres: list[str]) -> dict[str, Any]:
    return {
        "title": title,
        "author": author,
        "genres": genres,
        "recommendations": [],
        "source": "openlibrary",
        "warning": "Book recommendations are temporarily unavailable. Try again in a moment.",
    }


async def discover_similar_books(
    title: str,
    author: str = "",
    genres: list[str] | None = None,
    limit: int = 12,
) -> dict[str, Any]:
    """Return metadata genres and related catalog works for an audiobook."""
    if _normalize(author) in {"audiobook", "audiobookbay", "unknown", "unknown author"}:
        author = ""
    supplied_genres = select_discovery_genres(genres or [], limit=4)
    cache_key = "|".join((_normalize(title), _normalize(author), ",".join(g.lower() for g in supplied_genres), str(limit)))
    cached = _cache.get(cache_key)
    if cached and time.monotonic() - cached[0] < DISCOVERY_CACHE_TTL:
        return cached[1]

    match: dict[str, Any] = {}
    matched_genres = supplied_genres
    related_docs: list[dict[str, Any]] = []
    async with httpx.AsyncClient(follow_redirects=True, timeout=15.0) as client:
        # Saved ABB books often already have useful genres. In that case skip
        # the metadata lookup, reducing Open Library traffic from two calls to
        # one and avoiding its low anonymous rate limit.
        if not supplied_genres:
            try:
                search_data = await _get_json(
                    client,
                    f"{OPEN_LIBRARY_BASE}/search.json",
                    title=title,
                    author=author or None,
                    fields="key,title,author_name,subject,cover_i,first_publish_year",
                    limit=10,
                )
            except httpx.HTTPError:
                return _unavailable_result(title, author, supplied_genres)
            docs = search_data.get("docs") or []
            match = max(docs, key=lambda item: _book_score(item, title, author), default={})
            if match and _book_score(match, title, author) < 60:
                match = {}
        matched_genres = select_discovery_genres(
            supplied_genres + list(match.get("subject") or []),
            limit=4,
        )
        lookup_genres = [genre for genre in matched_genres if _normalize(genre) != "fiction"][:2]
        if not lookup_genres and matched_genres:
            lookup_genres = matched_genres[:1]

        if lookup_genres:
            subject_query = " AND ".join(
                f'subject_key:"{re.sub(r"[^a-z0-9]+", "_", genre.lower()).strip("_")}"'
                for genre in lookup_genres
            )
            # Open Library asks anonymous clients to stay around one request
            # per second. A metadata lookup immediately followed by discovery
            # otherwise produces intermittent 429/502 errors on Android.
            if not supplied_genres:
                await asyncio.sleep(1.05)
            try:
                related_data = await _get_json(
                    client,
                    f"{OPEN_LIBRARY_BASE}/search.json",
                    q=subject_query,
                    fields="key,title,author_name,subject,cover_i,first_publish_year",
                    limit=max(20, limit * 2),
                )
                related_docs = related_data.get("docs") or []
            except httpx.HTTPError:
                return _unavailable_result(
                    match.get("title") or title,
                    (match.get("author_name") or [author or ""])[0],
                    matched_genres,
                )

    seed_title = _normalize(title)
    recommendations: list[dict[str, Any]] = []
    seen: set[str] = set()
    for work in related_docs:
        work_subjects = {_normalize(subject) for subject in work.get("subject") or []}
        work_genres = [genre for genre in lookup_genres if _normalize(genre) in work_subjects]
        candidate = _recommendation_from_work(work, work_genres or lookup_genres[:1])
        if not candidate:
            continue
        normalized_title = _normalize(candidate["title"])
        if not normalized_title or normalized_title == seed_title or normalized_title in seen:
            continue
        seen.add(normalized_title)
        recommendations.append(candidate)

    result = {
        "title": match.get("title") or title,
        "author": (match.get("author_name") or [author or ""])[0],
        "genres": matched_genres,
        "recommendations": recommendations[:limit],
        "source": "openlibrary",
    }
    _cache[cache_key] = (time.monotonic(), result)
    return result
