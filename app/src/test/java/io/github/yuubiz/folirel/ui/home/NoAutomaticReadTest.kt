package io.github.yuubiz.folirel.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T29 (static guard): the only code that can set `isRead` is the explicit manual path.
 * - SQL writes to isRead exist only in ArticleDao (setRead / markReadByIds)
 * - those DAO methods are called only from ArticleRepository.setRead / markRead
 * - repository read-state calls come only from HomeViewModel.setRead / confirmMarkAllRead, which are wired
 *   to the explicit menu, accessibility action and mark-all confirmation.
 * Opening, scrolling, refreshing and time passing have no code path to these functions.
 */
class NoAutomaticReadTest {
    private val main = File("src/main/java")

    private fun sources(): Map<String, String> =
        main.walkTopDown().filter { it.isFile && it.extension == "kt" }.associate { it.relativeTo(main).path.replace('\\', '/') to it.readText() }

    @Test
    fun readStateWritesAreConfinedToManualActions() {
        val files = sources()
        assertTrue("source tree not found from ${File(".").absolutePath}", files.size > 20)

        val sqlWriters = files.filter { (_, text) -> Regex("SET\\s+isRead", RegexOption.IGNORE_CASE).containsMatchIn(text) }.keys
        assertEquals(setOf("io/github/yuubiz/folirel/data/db/ArticleDao.kt"), sqlWriters)

        val daoCallers = files.filter { (_, text) -> Regex("dao\\.(setRead|markReadByIds)\\(").containsMatchIn(text) }.keys
        assertEquals(setOf("io/github/yuubiz/folirel/data/repository/ArticleRepository.kt"), daoCallers)

        val repoCallers = files.filter { (_, text) -> Regex("articles\\.(setRead|markRead)\\(").containsMatchIn(text) }.keys
        assertEquals(setOf("io/github/yuubiz/folirel/ui/home/HomeViewModel.kt"), repoCallers)

        // Inside HomeViewModel, only the two explicit entry points touch read state.
        val vm = files.getValue("io/github/yuubiz/folirel/ui/home/HomeViewModel.kt")
        val functionsCalling = Regex("fun (\\w+)\\([^)]*\\)[^{]*\\{").findAll(vm).map { it.groupValues[1] to it.range.first }.toList()
        val callSites = Regex("articles\\.(setRead|markRead)\\(").findAll(vm).map { m -> functionsCalling.last { it.second < m.range.first }.first }.toSet()
        assertEquals(setOf("setRead", "confirmMarkAllRead"), callSites)

        // UI callers of the view-model entry points: the home screen's explicit actions only.
        val uiCallers = files.filter { (_, text) -> Regex("vm\\.(setRead|confirmMarkAllRead)\\(").containsMatchIn(text) }.keys
        assertEquals(setOf("io/github/yuubiz/folirel/ui/home/HomeScreen.kt"), uiCallers)

        // The browser launcher has no data access at all.
        val launcher = files.getValue("io/github/yuubiz/folirel/browser/BrowserLauncher.kt")
        assertTrue(!launcher.contains("Repository") && !launcher.contains("Dao"))
        // Nothing in the code base writes a WebView.
        assertTrue(files.values.none { it.contains("android.webkit.WebView") })
    }
}
