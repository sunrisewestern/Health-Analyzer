package com.healthanalyzer.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceholderTest {

    @Test
    fun detectsPlaceholderImmediatelyAfterDoubleBrackets() {
        val text = "Analyze this: {{"
        val match = detectPlaceholderQuery(text, text.length)
        assertTrue(match.isActive)
        assertEquals("", match.query)
        assertEquals(14, match.openIndex)
    }

    @Test
    fun detectsPlaceholderWhileTypingTag() {
        val text = "Analyze this: {{run"
        val match = detectPlaceholderQuery(text, text.length)
        assertTrue(match.isActive)
        assertEquals("run", match.query)
        assertEquals(14, match.openIndex)
    }

    @Test
    fun doesNotDetectWhenClosed() {
        val text = "Analyze this: {{run}} more text"
        val match = detectPlaceholderQuery(text, text.length)
        assertFalse(match.isActive)
    }

    @Test
    fun doesNotDetectAcrossNewlines() {
        val text = "Analyze this: {{\nsomething"
        val match = detectPlaceholderQuery(text, text.length)
        assertFalse(match.isActive)
    }

    @Test
    fun filtersPlaceholdersCorrectly() {
        val all = PROMPT_PLACEHOLDERS
        val filteredRun = filterPlaceholders("run", all)
        assertTrue(filteredRun.any { it.tag == "run" })
        assertTrue(filteredRun.any { it.tag == "recent_runs" })
        assertTrue(filteredRun.any { it.tag == "running_stats" })
        assertEquals("run", filteredRun.first().tag) // Prefix match sorted first

        val filteredAth = filterPlaceholders("ath", all)
        assertTrue(filteredAth.any { it.tag == "athlete" })
        assertEquals("athlete", filteredAth.first().tag)
    }

    @Test
    fun insertsPlaceholderAndPositionsCursor() {
        val text = "Hello {{r and world"
        val cursor = 9 // right after 'r'
        val openIndex = 6 // at '{{'
        val (newText, newCursor) = insertPlaceholder(text, cursor, openIndex, "run")
        assertEquals("Hello {{run}} and world", newText)
        assertEquals(13, newCursor) // right after '{{run}}'
    }

    @Test
    fun insertsPlaceholderReplacingTrailingClosingBrackets() {
        val text = "Hello {{r}} and world"
        val cursor = 9 // right before '}}'
        val openIndex = 6
        val (newText, newCursor) = insertPlaceholder(text, cursor, openIndex, "athlete")
        assertEquals("Hello {{athlete}} and world", newText)
        assertEquals(17, newCursor)
    }
}
