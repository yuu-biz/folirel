package io.github.yuubiz.folirel.model

import io.github.yuubiz.folirel.model.HiddenKeywords.AddResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P3A-T24 (blank, duplicate) and T25 (the limits) of the hidden-keyword list, and the stored form. */
class HiddenKeywordsTest {
    private fun add(list: List<String>, raw: String) = HiddenKeywords.add(list, raw)

    @Test
    fun aKeywordIsNormalizedAndAppendedInOrder() {
        var list = emptyList<String>()
        for (raw in listOf("  AI  ", "機械 学習", "ガイド", "x")) list = (add(list, raw) as AddResult.Added).list
        assertEquals(listOf("AI", "機械 学習", "ガイド", "x"), list)
    }

    @Test
    fun blankIsRefused() {
        for (raw in listOf("", "   ", "\n\t", " ")) assertEquals(AddResult.Blank, add(listOf("a"), raw))
    }

    @Test
    fun aDuplicateIsRefusedIgnoringAsciiCaseAndSpacing() {
        val list = listOf("AI", "機械学習")
        assertEquals(AddResult.Duplicate, add(list, "ai"))
        assertEquals(AddResult.Duplicate, add(list, " Ai "))
        assertEquals(AddResult.Duplicate, add(list, "機械学習"))
        assertTrue(add(list, "機械") is AddResult.Added)
    }

    @Test
    fun thirtyTwoKeywordsAreTheLimit() {
        var list = emptyList<String>()
        repeat(HiddenKeywords.MAX_COUNT) { i -> list = (add(list, "word$i") as AddResult.Added).list }
        assertEquals(32, list.size)
        assertEquals(AddResult.ListFull, add(list, "one more"))
        // A duplicate is reported as a duplicate even when the list is full: the reader hears the real reason.
        assertEquals(AddResult.Duplicate, add(list, "WORD3"))
        assertEquals(31, HiddenKeywords.remove(list, "word3").size)
        assertTrue(add(HiddenKeywords.remove(list, "word3"), "one more") is AddResult.Added)
    }

    @Test
    fun sixtyFourCharactersAreTheLimitCountedInCodePoints() {
        assertTrue(add(emptyList(), "a".repeat(64)) is AddResult.Added)
        assertEquals(AddResult.TooLong, add(emptyList(), "a".repeat(65)))
        // 64 supplementary characters (two UTF-16 units each) are still 64 characters.
        val emoji = "😀".repeat(64)
        assertEquals(128, emoji.length)
        assertTrue(add(emptyList(), emoji) is AddResult.Added)
        assertEquals(AddResult.TooLong, add(emptyList(), emoji + "😀"))
        // The limit is on the normalized text: blanks around it do not count.
        assertTrue(add(emptyList(), "  " + "a".repeat(64) + "  ") is AddResult.Added)
    }

    @Test
    fun theStoredFormKeepsOrderAndSurvivesHandEditing() {
        val list = listOf("b", "AI", "機械 学習", "100%")
        assertEquals(list, HiddenKeywords.decode(HiddenKeywords.encode(list)))
        assertEquals(emptyList<String>(), HiddenKeywords.decode(null))
        assertEquals(emptyList<String>(), HiddenKeywords.decode(""))
        // Blank, duplicate and surplus lines are dropped on reading; the order of the rest stays.
        val messy = "a\n\n  \nA\nb\n" + (0 until 40).joinToString("\n") { "w$it" }
        val read = HiddenKeywords.decode(messy)
        assertEquals(listOf("a", "b"), read.take(2))
        assertEquals(32, read.size)
    }
}
