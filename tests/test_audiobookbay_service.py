import unittest
import sys
import types

# Keep the pure ranking tests runnable without installing the full backend.
try:
    import bs4  # noqa: F401
except ModuleNotFoundError:
    sys.modules["bs4"] = types.SimpleNamespace(BeautifulSoup=object)

try:
    import httpx  # noqa: F401
except ModuleNotFoundError:
    sys.modules["httpx"] = types.SimpleNamespace(Client=object)

try:
    import fastapi  # noqa: F401
except ModuleNotFoundError:
    sys.modules["fastapi"] = types.SimpleNamespace(HTTPException=Exception)

from app.audiobookbay_service import _rank_search_results, _split_title_author


class AudiobookSearchRankingTests(unittest.TestCase):
    def test_title_author_suffix_is_extracted(self):
        self.assertEqual(
            _split_title_author("Project Hail Mary - Andy Weir"),
            ("Project Hail Mary", "Andy Weir"),
        )

    def test_exact_title_is_ranked_before_partial_matches(self):
        results = [
            {"title": "Project Management", "description": "Andy Weir interview"},
            {"title": "Project Hail Mary - Andy Weir", "description": "Science fiction"},
            {"title": "Hail Mary", "description": "A different author"},
        ]

        ranked = _rank_search_results(results, "Project Hail Mary")

        self.assertEqual(ranked[0]["title"], "Project Hail Mary - Andy Weir")

    def test_unrelated_homepage_posts_are_removed(self):
        results = [
            {"title": "Newest Romance Upload", "description": "Recent post"},
            {"title": "The Martian", "description": "A novel by Andy Weir"},
        ]

        ranked = _rank_search_results(results, "Martian")

        self.assertEqual(ranked, [results[1]])


if __name__ == "__main__":
    unittest.main()
