import unittest
import sys
import types
from types import SimpleNamespace

try:
    import httpx  # noqa: F401
except ModuleNotFoundError:
    sys.modules["httpx"] = types.SimpleNamespace(Client=object)

from app.m4b_chapter_service import HTTPRangeReader, _serialize_chapters


class M4BChapterServiceTests(unittest.TestCase):
    def test_range_reader_is_seekable_and_caches_chunks(self):
        content = bytes(range(64))
        requested_ranges = []

        class Response:
            def __init__(self, status_code, headers=None, body=b""):
                self.status_code = status_code
                self.headers = headers or {}
                self.body = body
                self.is_success = 200 <= status_code < 300

            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return None

            def iter_bytes(self):
                yield self.body

        class Client:
            def head(self, _url):
                return Response(200, {"content-length": str(len(content))})

            def stream(self, _method, _url, headers):
                requested = headers["Range"]
                requested_ranges.append(requested)
                start, end = [int(value) for value in requested.removeprefix("bytes=").split("-")]
                return Response(206, body=content[start:end + 1])

        client = Client()
        with HTTPRangeReader("https://cdn.example/book.m4b", chunk_size=16, client=client) as reader:
            reader.seek(18)
            self.assertEqual(reader.read(5), content[18:23])
            reader.seek(20)
            self.assertEqual(reader.read(2), content[20:22])
            reader.seek(-4, 2)
            self.assertEqual(reader.read(), content[-4:])

        self.assertEqual(requested_ranges.count("bytes=16-31"), 1)
        self.assertEqual(requested_ranges.count("bytes=48-63"), 1)

    def test_serializes_chapter_end_times_and_duration(self):
        parsed = [
            SimpleNamespace(start=0.0, title="Intro"),
            SimpleNamespace(start=12.5, title="  Chapter One  "),
        ]
        parsed = type(
            "FakeChapters",
            (list,),
            {"_duration": 30000, "_timescale": 1000},
        )(parsed)

        result = _serialize_chapters(parsed)

        self.assertEqual(result["duration"], 30.0)
        self.assertEqual(
            result["chapters"],
            [
                {"title": "Intro", "start": 0.0, "end": 12.5, "duration": 12.5},
                {"title": "Chapter One", "start": 12.5, "end": 30.0, "duration": 17.5},
            ],
        )


if __name__ == "__main__":
    unittest.main()
