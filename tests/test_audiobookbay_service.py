import unittest
import sys
import types
from unittest.mock import patch

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

from app.audiobookbay_service import (
    _android_search_urls,
    _is_search_results_page,
    _rank_search_results,
    _split_title_author,
    search_audiobooks,
)


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

    def test_author_name_is_searchable_after_title_is_split(self):
        results = [
            {"title": "Dune", "author": "Frank Herbert", "description": "Science fiction"},
            {"title": "Herbert West", "author": "H. P. Lovecraft", "description": "Horror"},
        ]

        ranked = _rank_search_results(results, "Frank Herbert")

        self.assertEqual(ranked[0], results[0])

    def test_unrelated_homepage_posts_are_removed(self):
        results = [
            {"title": "Newest Romance Upload", "description": "Recent post"},
            {"title": "The Martian", "description": "A novel by Andy Weir"},
        ]

        ranked = _rank_search_results(results, "Martian")

        self.assertEqual(ranked, [results[1]])

    def test_android_search_uses_explicit_first_page_route(self):
        urls = _android_search_urls("The Silent Patient", 1)

        self.assertEqual(
            urls[0],
            "https://audiobookbay.lu/page/1/?s=The+Silent+Patient",
        )

    def test_search_page_is_distinguished_from_homepage(self):
        self.assertTrue(
            _is_search_results_page(
                "<html><h1>The Silent Patient Audiobooks Free Download</h1></html>",
                "The Silent Patient",
            )
        )
        self.assertFalse(
            _is_search_results_page(
                "<html><h2>Recent Audiobooks</h2></html>",
                "The Silent Patient",
            )
        )


class AndroidAudiobookSearchTests(unittest.IsolatedAsyncioTestCase):
    async def test_android_search_retries_canonical_route_after_homepage(self):
        homepage = "<html><div class='post'><div class='postTitle'><h2><a href='/audio-books/new-upload/'>Newest Upload</a></h2></div></div></html>"
        results_page = """
            <html><h1>Dune Audiobooks Free Download</h1>
            <div class="post">
              <div class="postTitle"><h2><a href="/audio-books/dune-frank-herbert/">Dune - Frank Herbert</a></h2></div>
              <div class="postContent">Science fiction classic</div>
            </div></html>
        """

        with patch.dict("os.environ", {"ANDROID_EMBEDDED": "1"}), patch(
            "app.audiobookbay_service._fetch_page_http",
            side_effect=[homepage, results_page],
        ) as fetch_page, patch(
            "app.audiobookbay_service._parse_search_results",
            return_value=[
                {"title": "Dune", "author": "Frank Herbert", "description": "Science fiction classic"}
            ],
        ):
            results = await search_audiobooks("Dune")

        self.assertEqual(results[0]["title"], "Dune")
        self.assertEqual(fetch_page.call_count, 2)
        self.assertIn("/page/1/?s=Dune", fetch_page.call_args_list[0].args[0])
        self.assertEqual(fetch_page.call_args_list[1].args[0], "https://audiobookbay.lu/?s=Dune")


if __name__ == "__main__":
    unittest.main()
