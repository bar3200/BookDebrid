"""Lifecycle bridge between the Android shell and embedded FastAPI server."""

import logging
import os
from pathlib import Path
import threading


logger = logging.getLogger(__name__)
_server_thread: threading.Thread | None = None


def _configure(files_dir: str, api_key: str) -> None:
    data_dir = Path(files_dir)
    data_dir.mkdir(parents=True, exist_ok=True)
    cache_dir = data_dir / "cache"
    cache_dir.mkdir(parents=True, exist_ok=True)

    os.environ["HOME"] = str(data_dir)
    os.environ["FREEDIFY_DATA_DIR"] = str(data_dir)
    os.environ["CACHE_DIR"] = str(cache_dir)
    os.environ["ANDROID_EMBEDDED"] = "1"
    os.environ["AUDIOBOOK_DEBRID_PROVIDER"] = "alldebrid"
    os.environ["ALLDEBRID_API_KEY"] = api_key


def update_api_key(api_key: str) -> None:
    """Update the process-only credential used by subsequent API requests."""
    os.environ["ALLDEBRID_API_KEY"] = api_key


def _run_server() -> None:
    import uvicorn

    # A string import keeps app.main initialization on this background thread.
    uvicorn.run(
        "app.main:app",
        host="127.0.0.1",
        port=8000,
        log_level="info",
        access_log=False,
        loop="asyncio",
    )


def start_server(files_dir: str, api_key: str) -> bool:
    """Start localhost once, returning immediately to the Android UI thread."""
    global _server_thread
    _configure(files_dir, api_key)

    if _server_thread and _server_thread.is_alive():
        update_api_key(api_key)
        return False

    _server_thread = threading.Thread(
        target=_run_server,
        name="freedify-fastapi",
        daemon=True,
    )
    _server_thread.start()
    return True


def is_server_running() -> bool:
    return bool(_server_thread and _server_thread.is_alive())
