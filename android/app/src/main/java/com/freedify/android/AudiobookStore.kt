package com.freedify.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

class AudiobookStore private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun books(): List<Audiobook> = runCatching {
        val array = JSONArray(preferences.getString(KEY_BOOKS, "[]"))
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(Audiobook::fromJson) }
    }.getOrDefault(emptyList())

    fun book(id: String): Audiobook? = books().firstOrNull { it.id == id }

    fun save(book: Audiobook) {
        val updated = books().filterNot { it.id == book.id }.toMutableList()
        updated.add(0, book)
        writeBooks(updated)
    }

    fun remove(id: String) = writeBooks(books().filterNot { it.id == id })

    fun replaceAll(books: List<Audiobook>) = writeBooks(books)

    fun updateProgress(chapterId: String, positionMs: Long) {
        preferences.edit().putLong("progress:$chapterId", positionMs.coerceAtLeast(0)).apply()
    }

    fun progress(chapterId: String): Long = preferences.getLong("progress:$chapterId", 0L)

    fun snapshot(): PlaybackSnapshot = PlaybackSnapshot(
        bookId = preferences.getString(KEY_CURRENT_BOOK, "").orEmpty(),
        chapterId = preferences.getString(KEY_CURRENT_CHAPTER, "").orEmpty(),
        positionMs = preferences.getLong(KEY_CURRENT_POSITION, 0L),
        speed = preferences.getFloat(KEY_SPEED, 1f),
    )

    fun snapshotForBook(bookId: String): PlaybackSnapshot {
        val current = snapshot()
        val chapterId = preferences.getString("book_chapter:$bookId", null)
            ?: current.chapterId.takeIf { current.bookId == bookId }
            ?: ""
        return current.copy(
            bookId = bookId,
            chapterId = chapterId,
            positionMs = preferences.getLong(
                "book_position:$bookId",
                if (current.bookId == bookId) current.positionMs else 0L,
            ),
        )
    }

    fun updateSnapshot(bookId: String, chapterId: String, positionMs: Long, speed: Float) {
        preferences.edit()
            .putString(KEY_CURRENT_BOOK, bookId)
            .putString(KEY_CURRENT_CHAPTER, chapterId)
            .putLong(KEY_CURRENT_POSITION, positionMs.coerceAtLeast(0))
            .putFloat(KEY_SPEED, speed.coerceIn(0.5f, 3f))
            .putLong("progress:$chapterId", positionMs.coerceAtLeast(0))
            .putString("book_chapter:$bookId", chapterId)
            .putLong("book_position:$bookId", positionMs.coerceAtLeast(0))
            .apply()
    }

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }

    /** Import the prior WebView library after the user opens the legacy interface once. */
    fun importLegacy(payload: String) {
        val array = runCatching { JSONArray(payload) }.getOrNull() ?: return
        val imported = (0 until array.length()).mapNotNull { index ->
            val old = array.optJSONObject(index) ?: return@mapNotNull null
            val cached = old.optJSONArray("cachedTracks") ?: JSONArray()
            val chapters = (0 until cached.length()).mapNotNull { chapterIndex ->
                val track = cached.optJSONObject(chapterIndex) ?: return@mapNotNull null
                val sourceId = track.optString("isrc")
                if (sourceId.isBlank()) return@mapNotNull null
                AudiobookChapter(
                    id = track.optString("id", "${old.optString("id")}:$chapterIndex"),
                    title = track.optString("name", "Chapter ${chapterIndex + 1}"),
                    sourceId = sourceId,
                    startSeconds = track.optDouble("chapter_start", 0.0),
                    endSeconds = track.opt("chapter_end").let(::parseFlexibleDuration),
                    durationSeconds = parseFlexibleDuration(track.opt("duration")),
                    number = track.optInt("track_number", chapterIndex + 1),
                )
            }
            Audiobook(
                id = old.optString("id"),
                title = old.optString("name", "Untitled"),
                author = old.optString("artist", "Unknown author"),
                coverUrl = old.optString("artwork"),
                description = old.optString("description"),
                genres = normalizeAudiobookGenres(old.optJSONArray("genres").let { genres ->
                    if (genres == null) emptyList() else (0 until genres.length()).map { genres.optString(it) }
                }),
                debridId = old.optString("debrid_id").takeIf(String::isNotBlank),
                chapters = chapters,
                addedAt = old.optLong("addedAt", System.currentTimeMillis()),
            ).takeIf { it.id.isNotBlank() }
        }
        val existing = books().associateBy { it.id }
        replaceAll(imported.map { candidate ->
            val saved = existing[candidate.id]
            when {
                saved == null -> candidate
                chapterTitleScore(candidate.chapters) > chapterTitleScore(saved.chapters) -> saved.copy(
                    title = candidate.title,
                    author = candidate.author,
                    coverUrl = candidate.coverUrl.ifBlank { saved.coverUrl },
                    description = candidate.description.ifBlank { saved.description },
                    genres = candidate.genres.ifEmpty { saved.genres },
                    magnetLink = candidate.magnetLink ?: saved.magnetLink,
                    debridId = candidate.debridId ?: saved.debridId,
                    chapters = candidate.chapters,
                )
                else -> saved
            }
        } +
            books().filter { book -> imported.none { it.id == book.id } })
    }

    private fun chapterTitleScore(chapters: List<AudiobookChapter>): Int = chapters.count { chapter ->
        val normalized = chapter.title.trim()
        normalized.isNotBlank() && !normalized.matches(Regex("(?i)^chapter\\s*\\d*$"))
    }

    private fun writeBooks(books: List<Audiobook>) {
        preferences.edit().putString(KEY_BOOKS, JSONArray(books.map { it.toJson() }).toString()).apply()
        notifyChanged()
    }

    private fun notifyChanged() = listeners.forEach { it.invoke() }

    companion object {
        private const val PREFS = "bookdebrid_native_library"
        private const val KEY_BOOKS = "books"
        private const val KEY_CURRENT_BOOK = "current_book"
        private const val KEY_CURRENT_CHAPTER = "current_chapter"
        private const val KEY_CURRENT_POSITION = "current_position"
        private const val KEY_SPEED = "playback_speed"

        @Volatile private var instance: AudiobookStore? = null
        fun get(context: Context): AudiobookStore = instance ?: synchronized(this) {
            instance ?: AudiobookStore(context).also { instance = it }
        }
    }
}
