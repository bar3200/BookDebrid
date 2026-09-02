import unittest
from unittest.mock import patch

import httpx

from app import book_discovery_service
from app.book_discovery_service import discover_similar_books, select_discovery_genres


class FakeResponse:
    def __init__(self, payload, status_code=200, headers=None):
        self.payload = payload
        self.status_code = status_code
        self.headers = headers or {}

    def raise_for_status(self):
        if self.status_code >= 400:
            request = httpx.Request("GET", "https://openlibrary.org/search.json")
            response = httpx.Response(self.status_code, request=request, headers=self.headers)
            raise httpx.HTTPStatusError("upstream error", request=request, response=response)

    def json(self):
        return self.payload


class FakeClient:
    calls = []

    def __init__(self, *args, **kwargs):
        pass

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, traceback):
        return False

    async def get(self, url, **kwargs):
        params = kwargs.get("params") or {}
        self.calls.append(params)
        if url.endswith("/search.json") and params.get("title"):
            return FakeResponse({
                "docs": [{
                    "key": "/works/OL1W",
                    "title": "Dune",
                    "author_name": ["Frank Herbert"],
                    "subject": ["Fiction", "Science Fiction", "Adventure"],
                    "cover_i": 10,
                }]
            })
        if url.endswith("/search.json") and params.get("q"):
            return FakeResponse({"docs": [
                {"key": "/works/OL1W", "title": "Dune", "author_name": ["Frank Herbert"], "subject": ["Science Fiction"]},
                {"key": "/works/OL2W", "title": "Foundation", "author_name": ["Isaac Asimov"], "cover_i": 20, "subject": ["Science Fiction", "Adventure"]},
                {"key": "/works/OL2W", "title": "Foundation", "author_name": ["Isaac Asimov"], "cover_i": 20, "subject": ["Science Fiction"]},
                {"key": "/works/OL3W", "title": "Treasure Island", "author_name": ["Robert Louis Stevenson"], "subject": ["Adventure"]},
            ]})
        raise AssertionError(f"Unexpected URL: {url}")


class BookDiscoveryTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        book_discovery_service._cache.clear()
        FakeClient.calls.clear()

    def test_genres_prefer_useful_shelves_and_remove_noise(self):
        genres = select_discovery_genres([
            "Fiction", "Science Fiction", "Fantasy fiction", "Large type books",
            "1990-1999", "Science Fiction", "English literature", "Very " * 20
        ])

        self.assertEqual(genres, ["Science Fiction", "Fantasy"])

    def test_genres_are_canonical_and_drop_catalog_subjects(self):
        genres = select_discovery_genres([
            "English literature",
            "Suspense fiction",
            "Detective and mystery stories",
            "Translations into French",
            "Personal development",
            "Full cast recording",
            "Dramatized audio drama",
        ])

        self.assertEqual(genres, ["Full Cast", "Self-Help", "Mystery"])

    async def test_discovery_excludes_seed_and_deduplicates_related_works(self):
        with patch("app.book_discovery_service.httpx.AsyncClient", FakeClient, create=True), \
             patch("app.book_discovery_service.asyncio.sleep", return_value=None):
            result = await discover_similar_books("Dune", "Frank Herbert")

        self.assertEqual(result["genres"][:2], ["Science Fiction", "Adventure"])
        self.assertEqual(
            [book["title"] for book in result["recommendations"]],
            ["Foundation", "Treasure Island"],
        )
        self.assertEqual(
            result["recommendations"][0]["availability_query"],
            "Foundation Isaac Asimov",
        )

    async def test_supplied_genres_avoid_extra_metadata_request(self):
        with patch("app.book_discovery_service.httpx.AsyncClient", FakeClient, create=True):
            result = await discover_similar_books("Dune", "Frank Herbert", ["Science Fiction"])

        self.assertEqual(len(FakeClient.calls), 1)
        self.assertIn("q", FakeClient.calls[0])
        self.assertEqual(result["genres"], ["Science Fiction"])

    async def test_temporary_upstream_failure_returns_retryable_result(self):
        class UnavailableClient(FakeClient):
            async def get(self, url, **kwargs):
                return FakeResponse({}, status_code=503)

        with patch("app.book_discovery_service.httpx.AsyncClient", UnavailableClient, create=True), \
             patch("app.book_discovery_service.asyncio.sleep", return_value=None):
            result = await discover_similar_books("Dune", "Frank Herbert")

        self.assertEqual(result["recommendations"], [])
        self.assertIn("temporarily unavailable", result["warning"])


if __name__ == "__main__":
    unittest.main()
