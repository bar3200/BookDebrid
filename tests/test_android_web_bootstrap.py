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


if __name__ == "__main__":
    unittest.main()
