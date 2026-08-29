import unittest
from unittest.mock import patch

from app import book_discovery_service
from app.book_discovery_service import discover_similar_books, select_discovery_genres


class FakeResponse:
    def __init__(self, payload):
        self.payload = payload

    def raise_for_status(self):
        return None

    def json(self):
        return self.payload


class FakeClient:
    def __init__(self, *args, **kwargs):
        pass

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, traceback):
        return False

    async def get(self, url, **kwargs):
        params = kwargs.get("params") or {}
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

    def test_genres_prefer_useful_shelves_and_remove_noise(self):
        genres = select_discovery_genres([
            "Fiction", "Science Fiction", "Fantasy fiction", "Large type books",
            "1990-1999", "Science Fiction", "Very " * 20
        ])

        self.assertEqual(genres, ["Science Fiction", "Fantasy fiction", "Fiction"])

    async def test_discovery_excludes_seed_and_deduplicates_related_works(self):
        with patch("app.book_discovery_service.httpx.AsyncClient", FakeClient, create=True):
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


if __name__ == "__main__":
    unittest.main()
