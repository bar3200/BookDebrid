package com.freedify.android

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

data class AudiobookChapter(
    val id: String,
    val title: String,
    val sourceId: String,
    val startSeconds: Double = 0.0,
    val endSeconds: Double? = null,
    val durationSeconds: Double? = null,
    val number: Int = 0,
) {
    val effectiveDurationSeconds: Double?
        get() = durationSeconds ?: endSeconds?.minus(startSeconds)?.coerceAtLeast(0.0)

    fun toJson() = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("source_id", sourceId)
        .put("start", startSeconds)
        .put("end", endSeconds)
        .put("duration", durationSeconds)
        .put("number", number)

    companion object {
        fun fromJson(json: JSONObject) = AudiobookChapter(
            id = json.optString("id"),
            title = json.optString("title", "Chapter"),
            sourceId = json.optString("source_id"),
            startSeconds = json.optDouble("start", 0.0),
            endSeconds = json.optNullableDouble("end"),
            durationSeconds = json.optNullableDouble("duration"),
            number = json.optInt("number"),
        )
    }
}

data class Audiobook(
    val id: String,
    val title: String,
    val author: String = "Unknown author",
    val coverUrl: String = "",
    val description: String = "",
    val genres: List<String> = emptyList(),
    val magnetLink: String? = null,
    val debridId: String? = null,
    val rating: Double? = null,
    val ratingsCount: Long? = null,
    val chapters: List<AudiobookChapter> = emptyList(),
    val addedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("author", author)
        .put("cover_url", coverUrl)
        .put("description", description)
        .put("genres", JSONArray(genres))
        .put("magnet_link", magnetLink)
        .put("debrid_id", debridId)
        .put("rating", rating)
        .put("ratings_count", ratingsCount)
        .put("chapters", JSONArray(chapters.map { it.toJson() }))
        .put("added_at", addedAt)

    companion object {
        fun fromJson(json: JSONObject): Audiobook {
            val chaptersJson = json.optJSONArray("chapters") ?: JSONArray()
            return Audiobook(
                id = json.optString("id"),
                title = json.optString("title", json.optString("name", "Untitled")),
                author = json.optString("author", json.optString("artist", "Unknown author")),
                coverUrl = json.optString("cover_url", json.optString("artwork")),
                description = json.optString("description"),
                genres = normalizeAudiobookGenres(json.optJSONArray("genres").toStringList()),
                magnetLink = json.optNullableString("magnet_link"),
                debridId = json.optNullableString("debrid_id"),
                rating = json.optNullableDouble("rating"),
                ratingsCount = json.optNullableLong("ratings_count"),
                chapters = (0 until chaptersJson.length()).mapNotNull {
                    chaptersJson.optJSONObject(it)?.let(AudiobookChapter::fromJson)
                },
                addedAt = json.optLong("added_at", json.optLong("addedAt", System.currentTimeMillis())),
            )
        }

        fun fromSearch(json: JSONObject) = Audiobook(
            id = json.optString("id"),
            title = json.optString("title", json.optString("name", "Untitled")),
            author = json.optString("author", json.optString("artist", "Unknown author")),
            coverUrl = json.optString("cover_image", json.optString("cover_url")),
            description = json.optString("description"),
            genres = normalizeAudiobookGenres(json.optJSONArray("genres").toStringList()),
        )
    }
}

internal val CANONICAL_AUDIOBOOK_GENRES = listOf(
    "Full Cast",
    "Fantasy",
    "Science Fiction",
    "Mystery",
    "Thriller",
    "Romance",
    "Horror",
    "Historical Fiction",
    "Literary Fiction",
    "Contemporary Fiction",
    "Crime",
    "True Crime",
    "Biography",
    "Memoir",
    "History",
    "Business",
    "Self-Help",
    "Science",
    "Philosophy",
    "Humor",
    "Classics",
    "Adventure",
    "Young Adult",
    "Politics",
    "Religion & Spirituality",
    "Nonfiction",
)

private val GENRE_ALIASES = listOf(
    "Full Cast" to listOf("full cast"),
    "Historical Fiction" to listOf("historical fiction", "historical novel"),
    "Science Fiction" to listOf("science fiction", "sci fi", "space opera"),
    "Literary Fiction" to listOf("literary fiction"),
    "Contemporary Fiction" to listOf("contemporary fiction"),
    "Young Adult" to listOf("young adult", "ya fiction"),
    "True Crime" to listOf("true crime"),
    "Self-Help" to listOf("self help", "personal development"),
    "Fantasy" to listOf("fantasy"),
    "Mystery" to listOf("mystery", "detective fiction", "detective and mystery"),
    "Thriller" to listOf("thriller", "suspense", "psychological fiction"),
    "Romance" to listOf("romance", "love stories"),
    "Horror" to listOf("horror", "ghost stories"),
    "Biography" to listOf("biography", "biographical"),
    "Memoir" to listOf("memoir", "autobiography"),
    "Business" to listOf("business", "entrepreneurship"),
    "Philosophy" to listOf("philosophy"),
    "Humor" to listOf("humor", "humour", "comedy"),
    "Classics" to listOf("classic fiction", "classics"),
    "Adventure" to listOf("adventure"),
    "Crime" to listOf("crime", "criminal fiction"),
    "History" to listOf("history"),
    "Science" to listOf("popular science", "science"),
    "Politics" to listOf("politics", "political science"),
    "Religion & Spirituality" to listOf("spirituality", "religion"),
    "Nonfiction" to listOf("nonfiction", "non fiction"),
)

internal fun normalizeAudiobookGenres(values: List<String>): List<String> = values.mapNotNull { value ->
    val normalized = value.lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
    GENRE_ALIASES.firstOrNull { (_, aliases) -> aliases.any(normalized::contains) }?.first
}.distinct()

data class PlaybackSnapshot(
    val bookId: String = "",
    val chapterId: String = "",
    val positionMs: Long = 0,
    val speed: Float = 1f,
)

internal fun sourceIdForAllDebrid(sourceLink: String): String {
    val encoded = Base64.encodeToString(
        sourceLink.toByteArray(Charsets.UTF_8),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )
    return "ALLDEBRID:$encoded"
}

internal fun parseFlexibleDuration(value: Any?): Double? = when (value) {
    is Number -> value.toDouble()
    is String -> {
        val parts = value.split(':').mapNotNull(String::toDoubleOrNull)
        when (parts.size) {
            1 -> parts[0]
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> null
        }
    }
    else -> null
}

private fun JSONObject.optNullableString(key: String): String? =
    optString(key).takeIf { it.isNotBlank() && it != "null" }

private fun JSONObject.optNullableDouble(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

private fun JSONObject.optNullableLong(key: String): Long? =
    if (!has(key) || isNull(key)) null else optLong(key)

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
}
