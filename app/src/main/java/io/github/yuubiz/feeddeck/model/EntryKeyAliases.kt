package io.github.yuubiz.feeddeck.model

/**
 * When two article keys of one feed name the same permalink (V3-R02). A key is `id:<guid>` or `url:<link>`; a feed that
 * moved may give the same article as a link-only entry in the old feed and as a GUID that is the link in the new one,
 * or with http/https and `www.` spelled differently. Discovery counts such articles as shared, so storing them must
 * resolve them to the same article. Only URL-shaped keys are related; an opaque GUID, a fallback digest or a title is
 * never matched to anything else, and nothing is compared across feeds. The text of the address is not normalized
 * further (case, trailing slash, query order): a GUID differing in those is a different key.
 */
object EntryKeyAliases {
    private val prefixes = listOf("id:", "url:")
    private val schemes = listOf("https://", "http://")

    /** `host/path…` without prefix, scheme and a leading `www.`, or null for a key that is not a URL. */
    private fun addressOf(key: String): String? {
        val rest = prefixes.firstOrNull { key.startsWith(it) }?.let { key.substring(it.length) } ?: return null
        val scheme = schemes.firstOrNull { rest.startsWith(it) } ?: return null
        return rest.substring(scheme.length).removePrefix("www.")
    }

    /** The same address for every spelling above (`url:` + http + no www), or [key] itself when it is not URL-shaped. */
    fun comparable(key: String): String = addressOf(key)?.let { "url:http://$it" } ?: key

    /** Every other stored spelling of the address [key] names (id:/url: × https/http × with/without www.), or empty. */
    fun variants(key: String): List<String> {
        val address = addressOf(key) ?: return emptyList()
        return buildList {
            for (prefix in prefixes) for (scheme in schemes) for (www in listOf("", "www.")) add("$prefix$scheme$www$address")
        }.filter { it != key }
    }
}
