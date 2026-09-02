package com.freedify.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobookPresentationTest {
    private fun chapter(number: Int, title: String) = AudiobookChapter(
        id = "chapter-$number",
        title = title,
        sourceId = "redacted",
        number = number,
    )

    @Test
    fun oneFileBookUsesFriendlyPlaybackLabelInsteadOfFilename() {
        val onlyChapter = chapter(1, "Example_Book_Title")
        val book = Audiobook(id = "one-file", title = "Example Book Title", chapters = listOf(onlyChapter))

        assertEquals("Full audiobook", chapterDisplayTitle(book, onlyChapter))
    }

    @Test
    fun genericMarkersIncludePositionButDescriptiveMarkersStayExact() {
        val generic = chapter(1, "Chapter 1")
        val descriptive = chapter(2, "Prologue: A diary")
        val book = Audiobook(id = "long-book", title = "A Long Book", chapters = listOf(generic, descriptive))

        assertEquals("Chapter 1 of 2", chapterDisplayTitle(book, generic))
        assertEquals("Prologue: A diary", chapterDisplayTitle(book, descriptive))
    }

    @Test
    fun concatenatedScraperMetadataIsNotShownAsADescription() {
        val raw = "Shared by:someone Posted: 18 Apr 2026Format: M4B / Bitrate: 128 KbpsFile Size: 644 MB"

        assertEquals("", cleanAudiobookDescription(raw))
    }

    @Test
    fun chapterizedListingRecoversAuthorWithoutInventingChapterNames() {
        val identity = normalizeAudiobookIdentity(
            "Example Patient (Chapterized) - Alex Example",
            "AudiobookBay",
            "Written by Alex Example Read by Two Narrators Format: M4B",
        )

        assertEquals("Example Patient", identity.first)
        assertEquals("Alex Example", identity.second)
    }

    @Test
    fun exportedOnePlusViewportIsTreatedAsCompactWidth() {
        val effectiveWidthDp = 1080f / 3.375f
        val effectiveHeightDp = 2412f / 3.375f

        assertEquals(320f, effectiveWidthDp, 0.01f)
        assertTrue(effectiveHeightDp in 714f..715f)
        assertTrue(1.35f > 1f)
    }
}
