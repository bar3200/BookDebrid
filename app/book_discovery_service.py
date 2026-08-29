"""Low-volume book metadata and recommendation helpers backed by Open Library."""

from __future__ import annotations

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

# Prefer useful shelves over catalog noise such as locations, eras, and generic
# terms. Earlier entries win when Open Library returns many subjects.
GENRE_PRIORITY = {
    "fantasy": 100,
    "science fiction": 100,
    "fantasy fiction": 98,
    "mystery": 95,
    "thriller": 95,
    "romance": 90,
    "horror": 90,
    "historical fiction": 90,
    "young adult": 85,
    "crime": 85,
    "adventure": 80,
    "biography": 80,
    "memoir": 80,
    "history": 75,
    "nonfiction": 70,
    "fiction": 20,
}
GENRE_NOISE = (
    "accessible book",
    "large type",
    "protected daisy",
    "translations into",
    "reading level",
    "open library staff picks",
)


def _normalize(value: str) -> str:
    return " ".join(re.findall(r"[a-z0-9]+", str(value).lower()))


def select_discovery_genres(subjects: list[str], limit: int = 3) -> list[str]:
    """Choose stable, human-friendly genres from noisy catalog subjects."""
    unique: dict[str, str] = {}
    for subject in subjects:
        clean = " ".join(str(subject).replace("_", " ").split()).strip(" ,.;")
        normalized = _normalize(clean)
        if not normalized or len(clean) > 40 or normalized in unique:
            continue
        if any(char.isdigit() for char in clean):
            continue
        if any(noise in normalized for noise in GENRE_NOISE):
            continue
        unique[normalized] = clean

    def priority(normalized: str) -> int:
        if normalized in GENRE_PRIORITY:
            return GENRE_PRIORITY[normalized]
        for genre, score in GENRE_PRIORITY.items():
            if genre != "fiction" and genre in normalized:
                return score - 2
        return 50

    ranked = sorted(
        unique.items(),
        key=lambda item: (-priority(item[0]), len(item[1]), item[1].lower()),
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
    response = await client.get(url, params=params, headers=OPEN_LIBRARY_HEADERS)
    response.raise_for_status()
    return response.json()


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

    async with httpx.AsyncClient(follow_redirects=True, timeout=15.0) as client:
        search_data = await _get_json(
            client,
            f"{OPEN_LIBRARY_BASE}/search.json",
            title=title,
            author=author or None,
            fields="key,title,author_name,subject,cover_i,first_publish_year",
            limit=10,
        )
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

        related_docs: list[dict[str, Any]] = []
        if lookup_genres:
            subject_query = " AND ".join(
                f'subject_key:"{re.sub(r"[^a-z0-9]+", "_", genre.lower()).strip("_")}"'
                for genre in lookup_genres
            )
            related_data = await _get_json(
                client,
                f"{OPEN_LIBRARY_BASE}/search.json",
                q=subject_query,
                fields="key,title,author_name,subject,cover_i,first_publish_year",
                limit=max(20, limit * 2),
            )
            related_docs = related_data.get("docs") or []

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
