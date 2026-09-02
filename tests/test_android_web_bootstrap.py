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
        self.assertIn("document.title = 'BookDebrid'", index)
        self.assertIn("content: 'BookDebrid'", styles)
        self.assertIn(".android-app .android-library-btn span", styles)
        self.assertIn("? 'Search audiobooks'", app)
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

    def test_native_android_player_and_auto_share_the_media_service(self):
        manifest = (ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        activity = (ROOT / "android/app/src/main/java/com/freedify/android/NativeMainActivity.kt").read_text(encoding="utf-8")
        service = (
            ROOT
            / "android/app/src/main/java/com/freedify/android/PlaybackService.kt"
        ).read_text(encoding="utf-8")

        self.assertIn('android:name=".NativeMainActivity"', manifest)
        self.assertIn('android.media.browse.MediaBrowserService', manifest)
        self.assertIn('com.google.android.gms.car.application', manifest)
        self.assertIn("class PlaybackService : MediaBrowserServiceCompat()", service)
        self.assertIn('browsable(CONTINUE_ID, "Continue listening"', service)
        self.assertIn('items += browsable(BOOKS_ID, "My Books"', service)
        self.assertIn("ExoPlayer.Builder(this).build()", service)
        self.assertIn("setHandleAudioBecomingNoisy(true)", service)
        self.assertIn("C.AUDIO_CONTENT_TYPE_SPEECH", service)
        self.assertIn("setAudioAttributes(", service)
        self.assertIn("true,", service)
        self.assertIn("playbackErrorMessage(error)", service)
        self.assertIn("intent.getBooleanExtra(EXTRA_RESTART, false)", service)
        self.assertIn("private fun FullPlayer", activity)
        self.assertIn("PlaybackService.setSpeed(speed)", activity)

    def test_native_library_migrates_legacy_webview_books(self):
        activity = (ROOT / "android/app/src/main/java/com/freedify/android/MainActivity.kt").read_text(encoding="utf-8")
        store = (ROOT / "android/app/src/main/java/com/freedify/android/AudiobookStore.kt").read_text(encoding="utf-8")
        data = (ROOT / "static/data.js").read_text(encoding="utf-8")

        self.assertIn("fun syncAudiobookLibrary(payload: String)", activity)
        self.assertIn("fun importLegacy(payload: String)", store)
        self.assertIn("window.FreedifyAndroid?.syncAudiobookLibrary", data)

    def test_native_book_covers_have_network_loader_and_local_proxy(self):
        gradle = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
        api = (ROOT / "android/app/src/main/java/com/freedify/android/BookDebridApi.kt").read_text(encoding="utf-8")
        activity = (ROOT / "android/app/src/main/java/com/freedify/android/NativeMainActivity.kt").read_text(encoding="utf-8")

        self.assertIn("coil-network-okhttp", gradle)
        self.assertIn("fun imageUrl(rawUrl: String)", api)
        self.assertIn("/api/proxy_image?url=", api)
        self.assertIn("BookDebridApi.imageUrl(url)", activity)
        self.assertIn("contentScale = ContentScale.Crop", activity)
        self.assertIn("aspectRatio(2f / 3f)", activity)

    def test_native_startup_and_playback_show_platform_loading_ui(self):
        gradle = (ROOT / "android/app/build.gradle.kts").read_text(encoding="utf-8")
        manifest = (ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        themes = (ROOT / "android/app/src/main/res/values/themes.xml").read_text(encoding="utf-8")
        activity = (ROOT / "android/app/src/main/java/com/freedify/android/NativeMainActivity.kt").read_text(encoding="utf-8")
        service = (ROOT / "android/app/src/main/java/com/freedify/android/PlaybackService.kt").read_text(encoding="utf-8")

        self.assertIn("core-splashscreen", gradle)
        self.assertIn('android:theme="@style/Theme.BookDebrid.Splash"', manifest)
        self.assertIn('name="Theme.BookDebrid.Splash"', themes)
        self.assertIn("installSplashScreen()", activity)
        self.assertGreaterEqual(activity.count("if (playback.buffering)"), 2)
        self.assertIn("mediaPlayer.playbackState == Player.STATE_BUFFERING", service)

    def test_native_library_prefers_descriptive_chapter_metadata(self):
        api = (ROOT / "android/app/src/main/java/com/freedify/android/BookDebridApi.kt").read_text(encoding="utf-8")
        store = (ROOT / "android/app/src/main/java/com/freedify/android/AudiobookStore.kt").read_text(encoding="utf-8")

        self.assertIn('listOf("title", "name", "label")', api)
        self.assertIn("val transferId = book.debridId ?: run", api)
        self.assertIn("chapterTitleScore(candidate.chapters) > chapterTitleScore(saved.chapters)", store)
        self.assertIn("chapters = candidate.chapters", store)

    def test_native_book_details_prioritize_resume_and_discovery(self):
        activity = (ROOT / "android/app/src/main/java/com/freedify/android/NativeMainActivity.kt").read_text(encoding="utf-8")
        store = (ROOT / "android/app/src/main/java/com/freedify/android/AudiobookStore.kt").read_text(encoding="utf-8")

        self.assertIn('"Resume listening"', activity)
        self.assertIn('Text("Start from the beginning")', activity)
        self.assertIn('var chaptersExpanded by remember(book.id)', activity)
        self.assertIn('Text("Books like this"', activity)
        self.assertLess(activity.index('Text("Books like this"'), activity.index('Text("Chapters"'))
        self.assertIn('Text("Book options"', activity)
        self.assertIn("fun snapshotForBook(bookId: String)", store)
        self.assertIn('.putString("book_chapter:$bookId", chapterId)', store)
        self.assertIn("primary = Color(0xFF8AB4FF)", activity)

    def test_native_search_has_field_specific_modes(self):
        activity = (ROOT / "android/app/src/main/java/com/freedify/android/NativeMainActivity.kt").read_text(encoding="utf-8")

        self.assertIn("enum class AudiobookSearchMode", activity)
        for mode in ('TITLE("Title"', 'AUTHOR("Author"', 'GENRE("Genre"', 'URL("URL"'):
            self.assertIn(mode, activity)
        self.assertIn('Text("Search by"', activity)
        self.assertIn("FilterChip(", activity)
        self.assertIn("rankSearchResults(results, query, mode)", activity)
        self.assertIn("Paste a complete AudiobookBay book URL", activity)

    def test_android_uses_full_size_adaptive_bookdebrid_icon(self):
        manifest = (
            ROOT / "android/app/src/main/AndroidManifest.xml"
        ).read_text(encoding="utf-8")
        adaptive = (
            ROOT / "android/app/src/main/res/mipmap-anydpi-v26/ic_bookdebrid.xml"
        ).read_text(encoding="utf-8")

        self.assertIn('android:icon="@mipmap/ic_bookdebrid"', manifest)
        self.assertIn('android:roundIcon="@mipmap/ic_bookdebrid_round"', manifest)
        self.assertIn('@color/bookdebrid_icon_background', adaptive)
        self.assertIn('@drawable/ic_bookdebrid_foreground', adaptive)
        self.assertTrue((ROOT / "android/app/src/main/res/drawable-xxxhdpi/ic_bookdebrid_foreground.png").is_file())


if __name__ == "__main__":
    unittest.main()
