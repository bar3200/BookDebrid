package com.freedify.android

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** Builds a user-shareable UI fixture without credentials or debrid links. */
object UiQaExport {
    fun create(context: Context): String {
        val configuration = context.resources.configuration
        val metrics = context.resources.displayMetrics
        val books = AudiobookStore.get(context).books().map { book ->
            JSONObject()
                .put("title", book.title)
                .put("author", book.author)
                .put("cover_url", book.coverUrl)
                .put("description", book.description)
                .put("genres", JSONArray(book.genres))
                .put("rating", book.rating)
                .put("ratings_count", book.ratingsCount)
                .put("downloaded", book.chapters.isNotEmpty())
                .put("chapters", JSONArray(book.chapters.map { chapter ->
                    JSONObject()
                        .put("title", chapter.title)
                        .put("number", chapter.number)
                        .put("start_seconds", chapter.startSeconds)
                        .put("duration_seconds", chapter.effectiveDurationSeconds)
                }))
        }
        return JSONObject()
            .put("schema", 1)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("android_sdk", Build.VERSION.SDK_INT)
                .put("width_px", metrics.widthPixels)
                .put("height_px", metrics.heightPixels)
                .put("density", metrics.density)
                .put("font_scale", configuration.fontScale))
            .put("books", JSONArray(books))
            .toString(2)
    }
}
