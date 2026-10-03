package io.github.yuubiz.feeddeck.backend.local.opml

/**
 * OPML 2.0 export (docs/03 section 5): subscribed feeds with title/text/xmlUrl/htmlUrl and category hierarchy.
 * A feed in several categories is repeated under each; uncategorized feeds go directly under body.
 * No articles, reading state, settings or images are written.
 */
object OpmlWriter {
    data class ExportFeed(
        val title: String,
        val xmlUrl: String,
        val htmlUrl: String?,
        /** Direct category paths; empty for uncategorized. */
        val categoryPaths: List<List<String>>,
    )

    private class Node(val name: String) {
        val children = sortedMapOf<String, Node>()
        val feeds = mutableListOf<ExportFeed>()
    }

    fun write(title: String, feeds: List<ExportFeed>, createdRfc822: String? = null): String {
        val root = Node("")
        for (feed in feeds.sortedBy { it.title.lowercase() }) {
            if (feed.categoryPaths.isEmpty()) root.feeds += feed
            for (path in feed.categoryPaths) {
                var node = root
                for (segment in path) node = node.children.getOrPut(segment) { Node(segment) }
                node.feeds += feed
            }
        }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<opml version=\"2.0\">\n  <head>\n    <title>").append(escape(title)).append("</title>\n")
        if (createdRfc822 != null) sb.append("    <dateCreated>").append(escape(createdRfc822)).append("</dateCreated>\n")
        sb.append("  </head>\n  <body>\n")
        fun writeNode(node: Node, indent: String) {
            for (child in node.children.values) {
                sb.append(indent).append("<outline text=\"").append(escape(child.name)).append("\" title=\"").append(escape(child.name)).append("\">\n")
                writeNode(child, "$indent  ")
                sb.append(indent).append("</outline>\n")
            }
            for (feed in node.feeds) {
                sb.append(indent).append("<outline type=\"rss\" text=\"").append(escape(feed.title))
                    .append("\" title=\"").append(escape(feed.title))
                    .append("\" xmlUrl=\"").append(escape(feed.xmlUrl)).append('"')
                if (feed.htmlUrl != null) sb.append(" htmlUrl=\"").append(escape(feed.htmlUrl)).append('"')
                sb.append("/>\n")
            }
        }
        writeNode(root, "    ")
        sb.append("  </body>\n</opml>\n")
        return sb.toString()
    }

    /** XML attribute/text escaping; characters not allowed in XML 1.0 are dropped. */
    fun escape(value: String): String = buildString(value.length) {
        for (c in value) {
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c == '\'' -> append("&apos;")
                c == '\n' -> append("&#10;")
                c == '\r' -> append("&#13;")
                c == '\t' -> append("&#9;")
                c < ' ' || c == '￾' || c == '￿' -> Unit
                else -> append(c)
            }
        }
    }
}
