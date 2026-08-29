"""Read embedded M4B chapters over HTTP without downloading the audio payload."""

from __future__ import annotations

import io
from collections import OrderedDict

import httpx


class HTTPRangeReader(io.RawIOBase):
    """Small seekable HTTP reader backed by fixed-size byte-range requests."""

    def __init__(
        self,
        url: str,
        *,
        chunk_size: int = 256 * 1024,
        max_cached_chunks: int = 32,
        timeout: float = 20.0,
        client: httpx.Client | None = None,
    ):
        super().__init__()
        self.url = url
        self.chunk_size = chunk_size
        self.max_cached_chunks = max_cached_chunks
        self._position = 0
        self._cache: OrderedDict[int, bytes] = OrderedDict()
        self._owns_client = client is None
        self._client = client or httpx.Client(follow_redirects=True, timeout=timeout)
        self._size = self._discover_size()

    def _discover_size(self) -> int:
        response = self._client.head(self.url)
        if response.is_success and response.headers.get("content-length"):
            return int(response.headers["content-length"])

        # Some CDNs reject HEAD but still support ranges.
        with self._client.stream("GET", self.url, headers={"Range": "bytes=0-0"}) as ranged:
            if ranged.status_code != 206:
                raise OSError("The audiobook host does not support byte-range metadata reads")
            content_range = ranged.headers.get("content-range", "")
            try:
                return int(content_range.rsplit("/", 1)[1])
            except (IndexError, ValueError) as exc:
                raise OSError("The audiobook size was not provided by its host") from exc

    @property
    def size(self) -> int:
        return self._size

    def readable(self) -> bool:
        return True

    def seekable(self) -> bool:
        return True

    def tell(self) -> int:
        return self._position

    def seek(self, offset: int, whence: int = io.SEEK_SET) -> int:
        if whence == io.SEEK_SET:
            position = offset
        elif whence == io.SEEK_CUR:
            position = self._position + offset
        elif whence == io.SEEK_END:
            position = self._size + offset
        else:
            raise ValueError(f"Unsupported seek mode: {whence}")
        if position < 0:
            raise OSError("Cannot seek before the start of the audiobook")
        self._position = position
        return position

    def _read_chunk(self, chunk_index: int) -> bytes:
        cached = self._cache.get(chunk_index)
        if cached is not None:
            self._cache.move_to_end(chunk_index)
            return cached

        start = chunk_index * self.chunk_size
        if start >= self._size:
            return b""
        end = min(start + self.chunk_size, self._size) - 1
        headers = {"Range": f"bytes={start}-{end}"}
        with self._client.stream("GET", self.url, headers=headers) as response:
            if response.status_code != 206:
                raise OSError("The audiobook host stopped honoring byte-range requests")
            data = b"".join(response.iter_bytes())
        expected = end - start + 1
        if len(data) != expected:
            raise OSError(f"Incomplete audiobook metadata range ({len(data)} of {expected} bytes)")

        self._cache[chunk_index] = data
        self._cache.move_to_end(chunk_index)
        while len(self._cache) > self.max_cached_chunks:
            self._cache.popitem(last=False)
        return data

    def read(self, size: int = -1) -> bytes:
        if self._position >= self._size:
            return b""
        if size is None or size < 0:
            size = self._size - self._position
        size = min(size, self._size - self._position)
        output = bytearray()
        while size > 0:
            chunk_index = self._position // self.chunk_size
            chunk_offset = self._position % self.chunk_size
            chunk = self._read_chunk(chunk_index)
            take = min(size, len(chunk) - chunk_offset)
            if take <= 0:
                break
            output.extend(chunk[chunk_offset:chunk_offset + take])
            self._position += take
            size -= take
        return bytes(output)

    def close(self) -> None:
        if not self.closed and self._owns_client:
            self._client.close()
        super().close()


def _serialize_chapters(parsed) -> dict:
    total_duration = (
        parsed._duration / parsed._timescale
        if parsed._duration is not None and parsed._timescale
        else None
    )
    chapters = []
    for index, chapter in enumerate(parsed):
        start = float(chapter.start)
        end = (
            float(parsed[index + 1].start)
            if index + 1 < len(parsed)
            else total_duration
        )
        chapters.append(
            {
                "title": chapter.title.strip() or f"Chapter {index + 1}",
                "start": start,
                "end": end,
                "duration": max(0.0, end - start) if end is not None else None,
            }
        )
    return {"duration": total_duration, "chapters": chapters}


def extract_m4b_chapters(url: str) -> dict:
    """Return chapter start/end times from an unlocked M4B URL.

    Mutagen's atom walker seeks over the large ``mdat`` audio atom, while the
    reader above downloads only the small chunks it actually inspects.
    """
    from mutagen.mp4 import MP4Chapters
    from mutagen.mp4._atom import Atoms

    with HTTPRangeReader(url) as remote_file:
        atoms = Atoms(remote_file)
        parsed = MP4Chapters(atoms, remote_file)
        return _serialize_chapters(parsed)
