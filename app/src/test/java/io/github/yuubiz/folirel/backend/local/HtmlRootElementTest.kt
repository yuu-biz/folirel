package io.github.yuubiz.folirel.backend.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V3-R05: the root element decides between a site page and a feed. */
class HtmlRootElementTest {
    private fun root(text: String) = HtmlLinks.rootElement(text.toByteArray(Charsets.UTF_8))

    @Test
    fun skipsEverythingBeforeTheRoot() {
        assertEquals("rss", root("<rss version=\"2.0\"></rss>"))
        assertEquals("rss", root("﻿  \n<?xml version=\"1.0\"?>\n<!-- <html> --><?xml-stylesheet href=\"a\"?><rss>"))
        assertEquals("html", root("<?xml version=\"1.0\"?><!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0//EN\" \"http://www.w3.org/x.dtd\"><html xmlns=\"x\">"))
        assertEquals("html", root("<!DOCTYPE html>\n<html>"))
        assertEquals("rss", root("<!DOCTYPE rss [ <!ENTITY a \"<html>\"> ]><rss>"))
        assertEquals("feed", root("<a:feed xmlns:a=\"x\">"))
        assertEquals("rdf", root("<rdf:RDF xmlns:rdf=\"x\">"))
        assertEquals("html", root("<HTML>"))
    }

    @Test
    fun utf16DocumentsAreScannedToo() {
        assertEquals("rss", HtmlLinks.rootElement("﻿<?xml version=\"1.0\"?><rss>".toByteArray(Charsets.UTF_16)))
        assertEquals("html", HtmlLinks.rootElement(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "<html>".toByteArray(Charsets.UTF_16LE)))
    }

    @Test
    fun noRootMeansNoAnswerAndNeverAFeed() {
        assertNull(root(""))
        assertNull(root("plain text, no markup"))
        assertNull(root("<?xml version=\"1.0\"?>"))
        assertNull(root("<!-- unterminated"))
        assertNull(root("<!DOCTYPE html [ unterminated"))
        assertFalse(HtmlLinks.isFeedDocument("<?xml version=\"1.0\"?><html xmlns=\"x\"></html>".toByteArray()))
        assertTrue(HtmlLinks.isFeedDocument("<?xml version=\"1.0\"?><feed xmlns=\"x\"></feed>".toByteArray()))
    }
}
