package io.github.yuubiz.feeddeck.backend.local.parser

import android.util.Xml
import io.github.yuubiz.feeddeck.network.UrlNormalizer
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.StringReader

/** Rejection of an untrusted XML document. The whole document is refused; nothing is stored. */
class XmlRejectedException(val kind: Kind, message: String, val detail: String? = null) : Exception(message) {
    enum class Kind { SECURITY, LIMIT_EXCEEDED, MALFORMED }
}

data class XmlLimits(
    val maxDepth: Int,
    val maxElements: Int = 500_000,
    val maxAttributeLength: Int = 64 * 1024,
    val maxAttributes: Int = 256,
)

/** Small immutable-after-build element tree. Only used while parsing one document. */
class XmlElement(
    val namespace: String,
    val name: String,
    private val attributes: Map<String, String>,
    /** Effective xml:base for this element (validated http/https), inherited from ancestors. */
    val base: String?,
) {
    val children = ArrayList<XmlElement>()
    internal val textBuilder = StringBuilder()

    /** Direct character data of this element (CDATA, text and entity references). */
    val text: String get() = textBuilder.toString()

    fun attr(name: String, namespace: String = ""): String? = attributes["$namespace|$name"]

    /** Local names of non-namespaced attributes, for diagnostics of non-standard spellings. */
    val plainAttributeNames: List<String> get() = attributes.keys.filter { it.startsWith("|") }.map { it.substring(1) }

    fun child(namespace: String, name: String): XmlElement? = children.firstOrNull { it.namespace == namespace && it.name == name }

    fun children(namespace: String, name: String): List<XmlElement> = children.filter { it.namespace == namespace && it.name == name }

    fun childText(namespace: String, name: String): String? = child(namespace, name)?.text

    /** All descendant character data, in document order. */
    fun deepText(): String {
        val sb = StringBuilder()
        fun walk(e: XmlElement) {
            sb.append(e.textBuilder)
            e.children.forEach(::walk)
        }
        walk(this)
        return sb.toString()
    }
}

/**
 * Reads already-decoded XML text into an [XmlElement] tree using the platform pull parser in
 * `nextToken()` mode, so a DOCTYPE is seen as a token and rejected (not silently skipped).
 * External entities, DTDs and XInclude are never resolved; unknown entity references are refused.
 */
object SafeXmlReader {
    const val XML_NS = "http://www.w3.org/XML/1998/namespace"
    const val XINCLUDE_NS = "http://www.w3.org/2001/XInclude"
    private val BUILTIN_ENTITIES = setOf("amp", "lt", "gt", "quot", "apos")

    fun read(text: String, limits: XmlLimits, documentBase: String? = null): XmlElement {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(text))
        val stack = ArrayDeque<XmlElement>()
        var root: XmlElement? = null
        var elements = 0
        try {
            while (true) {
                when (parser.nextToken()) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.DOCDECL -> throw XmlRejectedException(XmlRejectedException.Kind.SECURITY, "DOCTYPE is not allowed")
                    XmlPullParser.START_TAG -> {
                        if (parser.namespace == XINCLUDE_NS) {
                            throw XmlRejectedException(XmlRejectedException.Kind.SECURITY, "XInclude is not allowed")
                        }
                        if (stack.size + 1 > limits.maxDepth) {
                            throw XmlRejectedException(XmlRejectedException.Kind.LIMIT_EXCEEDED, "XML depth > ${limits.maxDepth}", "XML_DEPTH")
                        }
                        if (++elements > limits.maxElements) {
                            throw XmlRejectedException(XmlRejectedException.Kind.LIMIT_EXCEEDED, "Too many XML elements", "XML_ELEMENTS")
                        }
                        if (parser.attributeCount > limits.maxAttributes) {
                            throw XmlRejectedException(XmlRejectedException.Kind.LIMIT_EXCEEDED, "Too many attributes", "XML_ATTRIBUTES")
                        }
                        val attrs = HashMap<String, String>(parser.attributeCount * 2)
                        var xmlBase: String? = null
                        for (i in 0 until parser.attributeCount) {
                            val value = parser.getAttributeValue(i)
                            if (value.length > limits.maxAttributeLength) {
                                throw XmlRejectedException(XmlRejectedException.Kind.LIMIT_EXCEEDED, "Attribute too long", "XML_ATTRIBUTE_LENGTH")
                            }
                            val ns = parser.getAttributeNamespace(i).orEmpty()
                            val name = parser.getAttributeName(i)
                            attrs["$ns|$name"] = value
                            if ((ns == XML_NS && name == "base") || (ns.isEmpty() && name == "xml:base")) xmlBase = value
                        }
                        val parent = stack.lastOrNull()
                        val inherited = parent?.base ?: documentBase
                        val base = if (xmlBase != null) {
                            // An unsafe or unresolvable xml:base is ignored; the inherited base stays in effect.
                            (UrlNormalizer.resolve(inherited, xmlBase) as? UrlNormalizer.Result.Valid)?.url ?: inherited
                        } else {
                            inherited
                        }
                        val element = XmlElement(parser.namespace.orEmpty(), parser.name, attrs, base)
                        if (parent != null) {
                            parent.children.add(element)
                        } else {
                            if (root != null) throw XmlRejectedException(XmlRejectedException.Kind.MALFORMED, "Multiple roots")
                            root = element
                        }
                        stack.addLast(element)
                    }
                    XmlPullParser.END_TAG -> stack.removeLastOrNull()
                    XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.IGNORABLE_WHITESPACE ->
                        stack.lastOrNull()?.textBuilder?.append(parser.text)
                    XmlPullParser.ENTITY_REF -> {
                        val name = parser.name.orEmpty()
                        if (name !in BUILTIN_ENTITIES && !name.startsWith("#")) {
                            throw XmlRejectedException(XmlRejectedException.Kind.SECURITY, "Undeclared entity reference")
                        }
                        stack.lastOrNull()?.textBuilder?.append(parser.text.orEmpty())
                    }
                    else -> Unit // comments and processing instructions are ignored
                }
            }
        } catch (e: XmlPullParserException) {
            throw XmlRejectedException(XmlRejectedException.Kind.MALFORMED, "Malformed XML: ${e.message?.take(200)}")
        } catch (e: IllegalArgumentException) {
            throw XmlRejectedException(XmlRejectedException.Kind.MALFORMED, "Malformed XML: ${e.message?.take(200)}")
        }
        if (stack.isNotEmpty()) throw XmlRejectedException(XmlRejectedException.Kind.MALFORMED, "Unclosed element")
        return root ?: throw XmlRejectedException(XmlRejectedException.Kind.MALFORMED, "Empty document")
    }
}
