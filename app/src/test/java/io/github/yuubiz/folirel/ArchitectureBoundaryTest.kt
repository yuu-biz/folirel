package io.github.yuubiz.folirel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T48 (static): the UI does not know how articles are fetched or stored. It talks to view models,
 * repositories and the RefreshCoordinator only; no OkHttp, network guard, fetcher/backend implementation or DAO.
 * No future-provider scaffolding (OAuth, Outbox, Feedly client) exists in the code base.
 */
class ArchitectureBoundaryTest {
    private val main = File("src/main/java/io/github/yuubiz/folirel")

    private fun kotlinFiles(dir: File) = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun uiDoesNotReachIntoTransportOrStorage() {
        val forbidden = listOf(
            "import okhttp3",
            "import io.github.yuubiz.folirel.network.SafeHttp",
            "import io.github.yuubiz.folirel.network.HttpClients",
            "import io.github.yuubiz.folirel.network.NetworkGuard",
            "import io.github.yuubiz.folirel.backend.local.FeedFetcher",
            "import io.github.yuubiz.folirel.backend.local.LocalBackend",
            "import io.github.yuubiz.folirel.data.db.",
        )
        val uiFiles = kotlinFiles(File(main, "ui"))
        assertTrue(uiFiles.size > 5)
        val violations = uiFiles.flatMap { f -> f.readLines().filter { line -> forbidden.any { line.startsWith(it) } }.map { "${f.name}: $it" } }
        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun onlyOneBackendAndNoSpeculativeProviderCode() {
        val all = kotlinFiles(main)
        val implementations = all.filter { f -> Regex("\\)\\s*:\\s*BackendSynchronizer\\s*\\{").containsMatchIn(f.readText()) }.map { it.name }
        assertEquals(listOf("LocalBackend.kt"), implementations)
        val speculative = listOf("OAuth", "Outbox", "FeedlyClient", "feedly.com/v3", "accessToken")
        val hits = all.flatMap { f -> speculative.filter { f.readText().contains(it) }.map { "${f.name}: $it" } }
        assertEquals(emptyList<String>(), hits)
    }
}
