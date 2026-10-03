package io.github.yuubiz.feeddeck.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V3-R02: the one definition of "same address" used by discovery (shared articles) and by storage after a move. */
class EntryKeyAliasesTest {
    private val spellings = listOf(
        "url:https://site.example/p/1", "id:https://site.example/p/1", "url:http://site.example/p/1", "id:http://site.example/p/1",
        "url:https://www.site.example/p/1", "id:https://www.site.example/p/1", "url:http://www.site.example/p/1", "id:http://www.site.example/p/1",
    )

    @Test
    fun everySpellingOfOneAddressIsComparableAndListsTheOthers() {
        for (key in spellings) {
            assertEquals(key, "url:http://site.example/p/1", EntryKeyAliases.comparable(key))
            assertEquals(key, (spellings - key).toSet(), EntryKeyAliases.variants(key).toSet())
        }
    }

    @Test
    fun discoveryAndStorageAgree() {
        // comparable() equal exactly when one key is among the other's variants (or they are the same key).
        val keys = spellings + listOf("url:https://site.example/p/2", "id:opaque", "id:tag:site.example,2026:1", "fallback:abc", "url:https://site.example/p/1/")
        for (a in keys) for (b in keys) {
            val same = EntryKeyAliases.comparable(a) == EntryKeyAliases.comparable(b)
            assertEquals("$a ~ $b", same, a == b || b in EntryKeyAliases.variants(a))
        }
    }

    @Test
    fun nonAddressKeysAreNeverRelated() {
        for (key in listOf("id:opaque-guid", "id:tag:site.example,2026:1", "fallback:0123", "id:urn:uuid:1234", "id:ftp://site.example/p/1")) {
            assertTrue(key, EntryKeyAliases.variants(key).isEmpty())
            assertEquals(key, EntryKeyAliases.comparable(key))
        }
        // A different path, a trailing slash or another host is another article.
        assertFalse("url:https://site.example/p/1/" in EntryKeyAliases.variants("url:https://site.example/p/1"))
        assertFalse("url:https://other.example/p/1" in EntryKeyAliases.variants("url:https://site.example/p/1"))
    }
}
