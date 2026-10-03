package io.github.yuubiz.feeddeck.testutil

/** Reads synthetic fixtures (repository `fixtures/`, mounted as test resources) as raw bytes. */
object Fixtures {
    fun bytes(path: String): ByteArray {
        val stream = Fixtures::class.java.classLoader!!.getResourceAsStream(path)
            ?: error("Missing fixture: $path")
        return stream.use { it.readBytes() }
    }

    fun text(path: String): String = bytes(path).toString(Charsets.UTF_8)
}
