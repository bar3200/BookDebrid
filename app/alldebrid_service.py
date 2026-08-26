"""AllDebrid API adapter for audiobook magnet caching and playback."""

import base64
import binascii
import logging
import os
from urllib.parse import unquote, urlparse

import httpx
from fastapi import HTTPException


logger = logging.getLogger(__name__)
API_BASE_URL = "https://api.alldebrid.com"
APP_NAME = "Freedify"
AUDIO_EXTENSIONS = (".mp3", ".m4b", ".m4a", ".flac", ".wav", ".ogg", ".aac", ".opus")


async def _make_request(
    endpoint: str,
    method: str = "GET",
    params: dict | None = None,
    data: dict | None = None,
):
    api_key = os.getenv("ALLDEBRID_API_KEY")
    if not api_key:
        raise HTTPException(
            status_code=500,
            detail="AllDebrid API key is missing. Add ALLDEBRID_API_KEY to your .env file.",
        )

    headers = {"Authorization": f"Bearer {api_key}"}
    query_params = {"agent": APP_NAME}
    if params:
        query_params.update(params)

    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            if method == "GET":
                response = await client.get(
                    f"{API_BASE_URL}{endpoint}", headers=headers, params=query_params
                )
            elif method == "POST":
                response = await client.post(
                    f"{API_BASE_URL}{endpoint}",
                    headers=headers,
                    params=query_params,
                    data=data,
                )
            else:
                raise ValueError(f"Unsupported HTTP method: {method}")
    except httpx.RequestError as exc:
        raise HTTPException(
            status_code=502, detail=f"Request to AllDebrid failed: {exc}"
        ) from exc

    try:
        payload = response.json()
    except ValueError as exc:
        raise HTTPException(status_code=502, detail="AllDebrid returned an invalid response") from exc

    if payload.get("status") != "success":
        error = payload.get("error") or {}
        message = error.get("message") or payload.get("message") or "Unknown AllDebrid error"
        code = error.get("code")
        detail = f"AllDebrid Error: {message}"
        if code:
            detail += f" ({code})"
        raise HTTPException(status_code=400, detail=detail)

    return payload.get("data", {})


async def create_transfer(magnet_link: str):
    """Upload one magnet and return the transfer ID in Freedify's common shape."""
    data = await _make_request(
        "/v4/magnet/upload", method="POST", data={"magnets[]": magnet_link}
    )
    magnets = data.get("magnets", [])
    if not magnets:
        raise HTTPException(status_code=502, detail="AllDebrid did not return a magnet")

    magnet = magnets[0]
    if magnet.get("error"):
        error = magnet["error"]
        raise HTTPException(
            status_code=400,
            detail=f"AllDebrid Error: {error.get('message', 'Magnet upload failed')}",
        )
    return {
        "status": "success",
        "id": str(magnet["id"]),
        "name": magnet.get("name"),
        "ready": bool(magnet.get("ready")),
    }


def _normalise_transfer(magnet: dict) -> dict:
    size = magnet.get("size") or 0
    downloaded = magnet.get("downloaded") or 0
    try:
        status_code = int(magnet.get("statusCode"))
    except (TypeError, ValueError):
        status_code = None
    status_text = str(magnet.get("status") or "").strip()
    is_ready = bool(magnet.get("ready")) or status_code == 4 or status_text.lower() == "ready"
    progress = 1.0 if is_ready else (downloaded / size if size else 0.0)
    return {
        **magnet,
        "id": str(magnet.get("id")),
        "name": magnet.get("filename") or magnet.get("name"),
        "message": status_text or "Downloading",
        "status": "finished" if is_ready else ("error" if status_code and status_code >= 5 else "running"),
        "progress": max(0.0, min(progress, 1.0)),
        "folder_id": str(magnet.get("id")) if is_ready else None,
    }


async def check_transfer_status(transfer_id: str | None = None):
    params = {"id": transfer_id} if transfer_id else None
    data = await _make_request("/v4.1/magnet/status", method="POST", data=params)
    transfers = [_normalise_transfer(item) for item in data.get("magnets", [])]
    if transfer_id:
        return transfers[0] if transfers else None
    return transfers


def _flatten_files(nodes: list, path: str = "") -> list[dict]:
    """Flatten AllDebrid's nested n/e/s/l magnet tree."""
    files = []
    for node in nodes or []:
        name = node.get("n", "")
        item_path = f"{path}/{name}" if path else name
        if isinstance(node.get("e"), list):
            files.extend(_flatten_files(node["e"], item_path))
        elif node.get("l"):
            files.append(
                {
                    "name": name,
                    "path": item_path,
                    "size": node.get("s", 0),
                    "source_link": node["l"],
                }
            )
    return files


async def unlock_link(source_link: str) -> str:
    """Generate a fresh playable URL for an AllDebrid file link."""
    data = await _make_request(
        "/v4/link/unlock", method="POST", data={"link": source_link}
    )
    link = data.get("link")
    if not link:
        raise HTTPException(status_code=502, detail="AllDebrid did not return a playable link")
    return link


async def list_folder_contents(magnet_id: str):
    """Enumerate every audio file in a ready magnet.

    The stable ``source_link`` is deliberately returned instead of eagerly
    unlocking every chapter. Freedify unlocks the selected chapter at playback
    time, which avoids rate-limiting on large audiobooks and refreshes expired
    playable URLs automatically.
    """
    data = await _make_request(
        "/v4/magnet/files", method="POST", data={"id[]": magnet_id}
    )
    magnets = data.get("magnets", [])
    if not magnets:
        raise HTTPException(status_code=404, detail="AllDebrid magnet not found")
    magnet = magnets[0]
    if magnet.get("error"):
        error = magnet["error"]
        raise HTTPException(status_code=400, detail=error.get("message", "Could not list magnet files"))

    audio_files = []
    for item in _flatten_files(magnet.get("files", [])):
        if item["name"].lower().endswith(AUDIO_EXTENSIONS):
            item["link"] = item["source_link"]
            item["type"] = "file"
            audio_files.append(item)
    audio_files.sort(key=lambda item: item["path"].lower())
    return {
        "status": "success",
        "audio_files": audio_files,
        "folders": [],
        "name": magnet.get("filename", "AllDebrid magnet"),
    }


async def search_my_files(query: str):
    """Search ready AllDebrid magnets by name (AllDebrid has no cloud search API)."""
    data = await _make_request(
        "/v4.1/magnet/status", method="POST", data={"status": "ready"}
    )
    terms = [term for term in query.lower().split() if term]
    results = []
    for magnet in data.get("magnets", []):
        name = magnet.get("filename") or magnet.get("name") or ""
        if not terms or all(term in name.lower() for term in terms):
            results.append(
                {
                    "id": str(magnet.get("id")),
                    "name": name,
                    "type": "folder",
                    "size": magnet.get("size", 0),
                }
            )
    return results


async def refresh_link_by_source(source_link: str) -> str:
    """Refresh an expired playable link from its stable AllDebrid file link."""
    return await unlock_link(unquote(source_link))


def decode_source_link(encoded_link: str) -> str:
    """Decode and validate an ``ALLDEBRID:`` track payload."""
    try:
        padded_link = encoded_link + "=" * ((4 - len(encoded_link) % 4) % 4)
        source_link = base64.b64decode(
            padded_link, altchars=b"-_", validate=True
        ).decode("utf-8")
    except (binascii.Error, UnicodeDecodeError, ValueError) as exc:
        raise HTTPException(
            status_code=400, detail="Invalid AllDebrid stream identifier"
        ) from exc

    parsed = urlparse(source_link)
    if parsed.scheme != "https" or parsed.hostname not in {
        "alldebrid.com",
        "www.alldebrid.com",
    }:
        raise HTTPException(
            status_code=400, detail="Invalid AllDebrid stream identifier"
        )
    return source_link


async def resolve_playable_link(encoded_link: str) -> str:
    """Resolve an encoded stable link or fail without entering other resolvers."""
    source_link = decode_source_link(encoded_link)
    try:
        playable_link = await refresh_link_by_source(source_link)
    except HTTPException:
        raise
    except Exception as exc:
        logger.error("Unexpected AllDebrid link refresh failure: %s", exc)
        raise HTTPException(
            status_code=502, detail="Failed to refresh AllDebrid stream link"
        ) from exc

    if not playable_link:
        raise HTTPException(
            status_code=502, detail="AllDebrid did not return a playable stream link"
        )
    return playable_link


async def refresh_link_by_filename(filename: str) -> str | None:
    """Best-effort fallback refresh when only a filename is available."""
    clean_name = unquote(filename).lower()
    try:
        data = await _make_request(
            "/v4.1/magnet/status", method="POST", data={"status": "ready"}
        )
        for magnet in data.get("magnets", []):
            files = await list_folder_contents(str(magnet["id"]))
            for item in files["audio_files"]:
                if item["name"].lower() == clean_name:
                    return await unlock_link(item["source_link"])
    except Exception as exc:  # playback should report the original upstream failure
        logger.warning("AllDebrid filename refresh failed for %s: %s", filename, exc)
    return None


async def delete_item(item_id: str, is_transfer: bool = False):
    """Delete an AllDebrid magnet (active and ready items share this endpoint)."""
    return await _make_request(
        "/v4/magnet/delete", method="POST", data={"id": item_id}
    )
