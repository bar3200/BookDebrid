import unittest
import sys
import types
from unittest.mock import AsyncMock, MagicMock, patch

# Keep these focused adapter tests runnable even when the full application
# requirements have not been installed in the checkout environment.
try:
    import httpx  # noqa: F401
except ModuleNotFoundError:
    sys.modules["httpx"] = types.SimpleNamespace(RequestError=Exception, AsyncClient=object)

try:
    import fastapi  # noqa: F401
except ModuleNotFoundError:
    class HTTPException(Exception):
        def __init__(self, status_code, detail):
            super().__init__(detail)
            self.status_code = status_code
            self.detail = detail

    sys.modules["fastapi"] = types.SimpleNamespace(HTTPException=HTTPException)

from app import alldebrid_service


class AllDebridServiceTests(unittest.IsolatedAsyncioTestCase):
    async def test_request_reads_api_key_at_call_time(self):
        response = MagicMock()
        response.json.return_value = {"status": "success", "data": {"ok": True}}
        client = AsyncMock()
        client.get.return_value = response

        context = MagicMock()
        context.__aenter__ = AsyncMock(return_value=client)
        context.__aexit__ = AsyncMock(return_value=None)

        with patch.dict("os.environ", {"ALLDEBRID_API_KEY": "runtime-key"}), patch(
            "app.alldebrid_service.httpx.AsyncClient", return_value=context
        ):
            result = await alldebrid_service._make_request("/v4/test")

        self.assertEqual(result, {"ok": True})
        self.assertEqual(
            client.get.await_args.kwargs["headers"],
            {"Authorization": "Bearer runtime-key"},
        )

    def test_flatten_files_preserves_nested_paths(self):
        tree = [
            {
                "n": "Book",
                "e": [
                    {"n": "01.mp3", "s": 10, "l": "https://alldebrid.com/f/one"},
                    {
                        "n": "Disc 2",
                        "e": [
                            {"n": "02.m4b", "s": 20, "l": "https://alldebrid.com/f/two"}
                        ],
                    },
                ],
            }
        ]

        self.assertEqual(
            alldebrid_service._flatten_files(tree),
            [
                {
                    "name": "01.mp3",
                    "path": "Book/01.mp3",
                    "size": 10,
                    "source_link": "https://alldebrid.com/f/one",
                },
                {
                    "name": "02.m4b",
                    "path": "Book/Disc 2/02.m4b",
                    "size": 20,
                    "source_link": "https://alldebrid.com/f/two",
                },
            ],
        )

    def test_normalise_transfer_maps_progress_and_ready_state(self):
        running = alldebrid_service._normalise_transfer(
            {"id": 123, "filename": "Book", "size": 200, "downloaded": 50, "statusCode": 1, "status": "Downloading"}
        )
        ready = alldebrid_service._normalise_transfer(
            {"id": 123, "filename": "Book", "size": 200, "statusCode": 4, "status": "Ready"}
        )

        self.assertEqual(running["progress"], 0.25)
        self.assertEqual(running["status"], "running")
        self.assertEqual(ready["progress"], 1.0)
        self.assertEqual(ready["status"], "finished")
        self.assertEqual(ready["folder_id"], "123")

    def test_normalise_transfer_accepts_string_status_code(self):
        ready = alldebrid_service._normalise_transfer(
            {"id": 456, "filename": "Book", "statusCode": "4", "status": "Ready"}
        )

        self.assertEqual(ready["status"], "finished")
        self.assertEqual(ready["progress"], 1.0)
        self.assertEqual(ready["folder_id"], "456")

    @patch("app.alldebrid_service._make_request", new_callable=AsyncMock)
    async def test_create_transfer_returns_common_shape(self, request):
        request.return_value = {
            "magnets": [{"id": 42, "name": "Book", "ready": True}]
        }

        result = await alldebrid_service.create_transfer("magnet:?xt=urn:btih:test")

        self.assertEqual(result, {"status": "success", "id": "42", "name": "Book", "ready": True})
        request.assert_awaited_once_with(
            "/v4/magnet/upload",
            method="POST",
            data={"magnets[]": "magnet:?xt=urn:btih:test"},
        )

    @patch("app.alldebrid_service._make_request", new_callable=AsyncMock)
    async def test_transfer_status_filters_by_id_and_normalises_ready(self, request):
        request.return_value = {
            "magnets": [
                {"id": 42, "filename": "Book", "statusCode": "4", "status": "Ready"}
            ]
        }

        result = await alldebrid_service.check_transfer_status("42")

        self.assertEqual(result["id"], "42")
        self.assertEqual(result["status"], "finished")
        self.assertEqual(result["folder_id"], "42")
        request.assert_awaited_once_with(
            "/v4.1/magnet/status", method="POST", data={"id": "42"}
        )

    @patch("app.alldebrid_service._make_request", new_callable=AsyncMock)
    async def test_list_folder_filters_audio_and_preserves_stable_links(self, request):
        request.return_value = {
            "magnets": [
                {
                    "id": "42",
                    "files": [
                        {"n": "notes.txt", "s": 1, "l": "https://alldebrid.com/f/notes"},
                        {"n": "02.m4b", "s": 2, "l": "https://alldebrid.com/f/two"},
                        {"n": "01.mp3", "s": 3, "l": "https://alldebrid.com/f/one"},
                    ],
                }
            ]
        }
        result = await alldebrid_service.list_folder_contents("42")

        self.assertEqual([item["name"] for item in result["audio_files"]], ["01.mp3", "02.m4b"])
        self.assertEqual(result["audio_files"][0]["source_link"], "https://alldebrid.com/f/one")
        self.assertEqual(result["audio_files"][0]["link"], "https://alldebrid.com/f/one")

    @patch("app.alldebrid_service.unlock_link", new_callable=AsyncMock)
    async def test_refresh_generates_a_new_playable_link(self, unlock):
        unlock.return_value = "https://cdn.example/fresh"

        result = await alldebrid_service.refresh_link_by_source(
            "https%3A//alldebrid.com/f/one"
        )

        self.assertEqual(result, "https://cdn.example/fresh")
        unlock.assert_awaited_once_with("https://alldebrid.com/f/one")

    @patch("app.alldebrid_service.refresh_link_by_source", new_callable=AsyncMock)
    async def test_resolver_rejects_malformed_identifier_without_refresh(self, refresh):
        with self.assertRaises(alldebrid_service.HTTPException) as raised:
            await alldebrid_service.resolve_playable_link("not-valid-base64!")

        self.assertEqual(raised.exception.status_code, 400)
        self.assertEqual(raised.exception.detail, "Invalid AllDebrid stream identifier")
        refresh.assert_not_awaited()

    @patch("app.alldebrid_service.refresh_link_by_source", new_callable=AsyncMock)
    async def test_resolver_propagates_clean_refresh_http_error(self, refresh):
        encoded = "aHR0cHM6Ly9hbGxkZWJyaWQuY29tL2Yvb25l"
        refresh.side_effect = alldebrid_service.HTTPException(
            status_code=502, detail="Request to AllDebrid failed"
        )

        with self.assertRaises(alldebrid_service.HTTPException) as raised:
            await alldebrid_service.resolve_playable_link(encoded)

        self.assertEqual(raised.exception.status_code, 502)
        self.assertEqual(raised.exception.detail, "Request to AllDebrid failed")

    @patch("app.alldebrid_service.refresh_link_by_source", new_callable=AsyncMock)
    async def test_resolver_wraps_unexpected_refresh_failure(self, refresh):
        encoded = "aHR0cHM6Ly9hbGxkZWJyaWQuY29tL2Yvb25l"
        refresh.side_effect = RuntimeError("boom")

        with self.assertRaises(alldebrid_service.HTTPException) as raised:
            await alldebrid_service.resolve_playable_link(encoded)

        self.assertEqual(raised.exception.status_code, 502)
        self.assertEqual(
            raised.exception.detail, "Failed to refresh AllDebrid stream link"
        )

    @patch("app.alldebrid_service.refresh_link_by_source", new_callable=AsyncMock)
    async def test_resolver_rejects_empty_refresh_result(self, refresh):
        encoded = "aHR0cHM6Ly9hbGxkZWJyaWQuY29tL2Yvb25l"
        refresh.return_value = None

        with self.assertRaises(alldebrid_service.HTTPException) as raised:
            await alldebrid_service.resolve_playable_link(encoded)

        self.assertEqual(raised.exception.status_code, 502)
        self.assertEqual(
            raised.exception.detail,
            "AllDebrid did not return a playable stream link",
        )

    @patch("app.alldebrid_service._make_request", new_callable=AsyncMock)
    async def test_search_matches_ready_magnets(self, request):
        request.return_value = {
            "magnets": [
                {"id": 1, "filename": "The Long Book Audiobook", "size": 100},
                {"id": 2, "filename": "Something Else", "size": 200},
            ]
        }

        results = await alldebrid_service.search_my_files("long book")

        self.assertEqual(results, [{"id": "1", "name": "The Long Book Audiobook", "type": "folder", "size": 100}])

    @patch("app.alldebrid_service._make_request", new_callable=AsyncMock)
    async def test_delete_uses_magnet_endpoint(self, request):
        request.return_value = {"message": "deleted"}

        result = await alldebrid_service.delete_item("42")

        self.assertEqual(result, {"message": "deleted"})
        request.assert_awaited_once_with(
            "/v4/magnet/delete", method="POST", data={"id": "42"}
        )


if __name__ == "__main__":
    unittest.main()
