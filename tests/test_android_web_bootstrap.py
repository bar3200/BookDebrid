import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class AndroidWebBootstrapTests(unittest.TestCase):
    def test_android_bootstrap_removes_stale_service_worker_caches(self):
        index = (ROOT / "static" / "index.html").read_text(encoding="utf-8")

        self.assertIn("navigator.serviceWorker.getRegistrations()", index)
        self.assertIn("registration.unregister()", index)
        self.assertIn("caches.delete(name)", index)
        self.assertIn("sessionStorage.setItem(cleanupVersion, '1')", index)
        self.assertIn(".then(() => import(appUrl))", index)

    def test_android_does_not_register_the_desktop_service_worker(self):
        app = (ROOT / "static" / "app.js").read_text(encoding="utf-8")

        self.assertIn("!document.documentElement.classList.contains('android-app')", app)
        self.assertIn("navigator.serviceWorker.register('/sw.js')", app)

    def test_android_audiobook_search_uses_native_webview_bridge(self):
        search = (ROOT / "static" / "search.js").read_text(encoding="utf-8")
        activity = (
            ROOT
            / "android/app/src/main/java/com/freedify/android/MainActivity.kt"
        ).read_text(encoding="utf-8")

        self.assertIn("window.FreedifyAndroidSearch", search)
        self.assertIn("bridge.searchAudiobookBay(requestId, query, page)", search)
        self.assertIn("fun searchAudiobookBay(requestId: String, query: String, page: Int)", activity)
        self.assertIn("document.querySelector('input[name=\"s\"]')", activity)
        self.assertIn("document.querySelectorAll('div.post')", activity)
        self.assertIn("const genres = [...post.querySelectorAll", activity)
        self.assertIn("genres,", activity)

    def test_audiobook_discovery_controls_are_in_web_ui(self):
        index = (ROOT / "static" / "index.html").read_text(encoding="utf-8")
        views = (ROOT / "static" / "views.js").read_text(encoding="utf-8")

        self.assertIn('id="book-info-similar-btn"', index)
        self.assertIn("/api/audiobooks/discover", views)
        self.assertIn("audiobook-genre-chip", views)
        self.assertIn("fetchGoodreadsData(book);", views)
        self.assertIn("goodreads_rating_count", views)

    def test_android_shell_is_audiobook_focused(self):
        index = (ROOT / "static" / "index.html").read_text(encoding="utf-8")
        app = (ROOT / "static" / "app.js").read_text(encoding="utf-8")
        styles = (ROOT / "static" / "styles.css").read_text(encoding="utf-8")

        self.assertIn('id="android-library-btn"', index)
        self.assertIn('aria-label="Go to My Books"', index)
        self.assertIn('search-type-selector android-desktop-only', index)
        self.assertIn('class="settings-section android-desktop-only">\n                        <h3 class="settings-section-title">Local Files', index)
        self.assertIn("if (!isAndroidApp) {", app)
        self.assertIn("track?.source === 'audiobook'", app)
        self.assertIn("currentResults?.dataset.androidView !== 'library'", app)
        self.assertIn("FreedifyAndroidNavigation.goHome", app)
        self.assertIn("document.getElementById('error-message')?.classList.add('hidden')", app)
        self.assertIn(".android-app .search-type-selector", styles)
        self.assertIn(".android-app .settings-modal-content", styles)
        self.assertIn("keep its SDK traffic out of the audiobook APK", index)

        integrations = (ROOT / "static" / "integrations.js").read_text(encoding="utf-8")
        self.assertIn("Check initial LB status only in the full desktop/web experience", integrations)

    def test_android_player_uses_audiobook_seek_labels(self):
        index = (ROOT / "static" / "index.html").read_text(encoding="utf-8")
        service = (
            ROOT
            / "android/app/src/main/java/com/freedify/android/PlaybackService.kt"
        ).read_text(encoding="utf-8")

        self.assertIn('android-seek-label">−15', index)
        self.assertIn('android-seek-label">+15', index)
        self.assertIn('"Back 15 seconds"', service)
        self.assertIn('"Forward 15 seconds"', service)


if __name__ == "__main__":
    unittest.main()
