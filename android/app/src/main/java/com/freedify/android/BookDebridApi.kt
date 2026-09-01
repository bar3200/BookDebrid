package com.freedify.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class BookDebridApi {
    suspend fun search(query: String): List<Audiobook> {
        val payload = request("/api/search?q=${encode(query)}&type=audiobook&offset=0")
        val results = payload.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { results.optJSONObject(it)?.let(Audiobook::fromSearch) }
    }

    suspend fun details(id: String): Audiobook {
        val payload = request("/api/audiobooks/details?id=${encode(id)}")
        return Audiobook.fromSearch(payload).copy(
            id = payload.optString("id", id),
            magnetLink = payload.optString("magnet_link").takeIf(String::isNotBlank),
        )
    }

    suspend fun enrich(book: Audiobook): Audiobook {
        val discovery = runCatching {
            request(
                "/api/audiobooks/discover?title=${encode(book.title)}&author=${encode(book.author)}" +
                    "&genres=${encode(book.genres.joinToString(","))}&limit=12",
            )
        }.getOrNull()
        val goodreads = runCatching {
            request("/api/goodreads/book?title=${encode(book.title)}&author=${encode(book.author)}")
        }.getOrNull()

        val metadata = discovery
        return book.copy(
            description = book.description.ifBlank {
                metadata?.optString("description").orEmpty().ifBlank {
                    goodreads?.optString("description").orEmpty()
                }
            },
            genres = ((metadata?.optJSONArray("genres")?.let { array ->
                (0 until array.length()).map { array.optString(it) }
            } ?: emptyList()) + book.genres).filter(String::isNotBlank).distinct(),
            rating = goodreads?.number("rating") ?: book.rating,
            ratingsCount = goodreads?.flexibleLong("ratings_count")
                ?: goodreads?.flexibleLong("rating_count") ?: book.ratingsCount,
        )
    }

    suspend fun related(book: Audiobook): List<Audiobook> {
        val payload = request(
            "/api/audiobooks/discover?title=${encode(book.title)}&author=${encode(book.author)}" +
                "&genres=${encode(book.genres.joinToString(","))}&limit=12",
        )
        val array = payload.optJSONArray("recommendations")
            ?: payload.optJSONArray("results")
            ?: payload.optJSONArray("related")
            ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(Audiobook::fromSearch) }
    }

    suspend fun download(book: Audiobook, onProgress: suspend (Float, String) -> Unit): Audiobook {
        val magnet = book.magnetLink ?: details(book.id).magnetLink
            ?: throw ApiException("This result did not include a magnet link")
        val transfer = request(
            "/api/debrid/alldebrid/transfer",
            method = "POST",
            body = JSONObject().put("magnet_link", magnet),
        )
        val transferId = transfer.optString("id", transfer.optString("transfer_id"))
        if (transferId.isBlank()) throw ApiException("AllDebrid did not return a transfer ID")

        var ready = transfer.optBoolean("ready")
        while (!ready) {
            val status = request("/api/debrid/alldebrid/transfer/${encode(transferId)}")
                .optJSONObject("transfer")
                ?: throw ApiException("AllDebrid transfer was not found")
            if (status.optString("status") == "error") {
                throw ApiException(status.optString("message", "AllDebrid transfer failed"))
            }
            val progress = status.optDouble("progress", 0.0).toFloat().coerceIn(0f, 1f)
            onProgress(progress, status.optString("message", "Downloading"))
            ready = status.optString("status") == "finished" || progress >= 1f
            if (!ready) delay(4_000)
        }

        onProgress(1f, "Reading chapters…")
        val files = request("/api/debrid/alldebrid/files/${encode(transferId)}")
        val audioFiles = files.optJSONArray("audio_files")
            ?: throw ApiException("No audio files were found in this transfer")
        val chapters = mutableListOf<AudiobookChapter>()
        for (fileIndex in 0 until audioFiles.length()) {
            val file = audioFiles.optJSONObject(fileIndex) ?: continue
            val sourceLink = file.optString("source_link", file.optString("link"))
            if (sourceLink.isBlank()) continue
            val embedded = file.optJSONArray("chapters")
            if (embedded != null && embedded.length() > 0) {
                for (chapterIndex in 0 until embedded.length()) {
                    val chapter = embedded.optJSONObject(chapterIndex) ?: continue
                    chapters += AudiobookChapter(
                        id = "${book.id}:$fileIndex:$chapterIndex",
                        title = chapter.optString("title", "Chapter ${chapters.size + 1}"),
                        sourceId = sourceIdForAllDebrid(sourceLink),
                        startSeconds = chapter.optDouble("start", 0.0),
                        endSeconds = chapter.opt("end").let(::parseFlexibleDuration),
                        durationSeconds = chapter.opt("duration").let(::parseFlexibleDuration),
                        number = chapters.size + 1,
                    )
                }
            } else {
                chapters += AudiobookChapter(
                    id = "${book.id}:$fileIndex",
                    title = friendlyChapterName(file.optString("path", file.optString("name"))),
                    sourceId = sourceIdForAllDebrid(sourceLink),
                    durationSeconds = file.opt("duration").let(::parseFlexibleDuration),
                    number = chapters.size + 1,
                )
            }
        }
        if (chapters.isEmpty()) throw ApiException("No playable audiobook files were found")
        return book.copy(magnetLink = magnet, debridId = transferId, chapters = chapters)
    }

    suspend fun delete(book: Audiobook) {
        val id = book.debridId ?: return
        request(
            "/api/debrid/alldebrid/delete",
            method = "POST",
            body = JSONObject().put("id", id).put("is_transfer", false),
        )
    }

    private suspend fun request(
        path: String,
        method: String = "GET",
        body: JSONObject? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        val connection = URL("$BASE_URL$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 20_000
            connection.readTimeout = 65_000
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter().use { it.write(body.toString()) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            val payload = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (status !in 200..299) {
                throw ApiException(payload.optString("detail", "Request failed ($status)"), status)
            }
            payload
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val BASE_URL = "http://127.0.0.1:8000"
        fun streamUrl(chapter: AudiobookChapter): String =
            "$BASE_URL/api/stream/${encode(chapter.sourceId)}?q=${encode(chapter.title)}&source=audiobook"

        fun imageUrl(rawUrl: String): String {
            val value = rawUrl.trim()
            if (value.isBlank()) return ""
            val normalized = if (value.startsWith("//")) "https:$value" else value
            if (normalized.startsWith("/")) return "$BASE_URL$normalized"
            if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) return ""
            return "$BASE_URL/api/proxy_image?url=${encode(normalized)}"
        }

        private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
            .replace("+", "%20")

        private fun friendlyChapterName(path: String): String {
            val clean = path.substringBeforeLast('.').replace('\\', '/')
            return clean.split('/').takeLast(2).joinToString(" · ").ifBlank { "Chapter" }
        }
    }
}

class ApiException(message: String, val statusCode: Int? = null) : Exception(message)

private fun JSONObject.number(key: String): Double? = if (has(key) && !isNull(key)) {
    optDouble(key).takeIf { !it.isNaN() }
} else null

private fun JSONObject.flexibleLong(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    val raw = opt(key)
    if (raw is Number) return raw.toLong()
    return raw?.toString()?.replace(Regex("[^0-9]"), "")?.toLongOrNull()
}
