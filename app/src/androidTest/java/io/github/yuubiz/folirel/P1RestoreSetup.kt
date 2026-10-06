package io.github.yuubiz.folirel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.CardLayoutStyle
import io.github.yuubiz.folirel.model.ListMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not an assertion test (skipped unless `-e p1evidence true`): puts the state that `tools/p1_restore_check.py` restarts and restores
 * (P1-T29) into the installed app, using only the app's own repositories, the way the screens do: eight categories, two pinned, a saved
 * order, display overrides of three categories, the others inheriting a global List layout. No network. It uses only calls that 0.5.0 has too,
 * so it can prepare the state in 0.5.0 before 0.5.1 is installed over it. Run it with `am instrument`
 * (not through Gradle's connected task, which uninstalls the app afterwards).
 *
 * Resulting tab order: All, Zeta, Gamma (pinned, in that order), then Epsilon, Alpha, Beta, Delta, Eta, Theta.
 * Overrides: Delta = Compact list; Beta = Magazine, even sizes, two columns; Eta = two columns (layout inherited);
 * Alpha, Gamma, Epsilon, Zeta, Theta inherit everything. Selected category: Delta.
 */
@RunWith(AndroidJUnit4::class)
class P1RestoreSetup {
    @Before
    fun onlyWhenAsked() {
        Assume.assumeTrue("evidence helper (pass -e p1evidence true)", InstrumentationRegistry.getArguments().getString("p1evidence") == "true")
    }

    @Test
    fun configure() = runBlocking<Unit> {
        TestData.reset()
        val names = listOf("Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Zeta", "Eta", "Theta")
        val byPath = TestData.seedPaths(names.map { listOf(it) }, perCategory = 40)
        val c = TestData.container
        val repo = c.subscriptions
        val account = repo.ensureLocalAccount()
        fun id(name: String) = byPath.getValue(name)
        c.settings.setListMode(ListMode.LIST)
        c.settings.setCardColumns(0)
        // Pins first (Gamma, then Zeta), then the order of each group the way the organizer does it.
        repo.setCategoryPinned(account, id("Gamma"), true)
        repo.setCategoryPinned(account, id("Zeta"), true)
        // The explicit saves of 0.5.0 (so that this also runs against that release): the pinned group, then the others.
        repo.reorderCategoryTabs(account, true, listOf(id("Zeta"), id("Gamma")))
        repo.reorderCategoryTabs(account, false, listOf("Epsilon", "Alpha", "Beta", "Delta", "Eta", "Theta").map { id(it) })
        repo.setCategoryListLayout(account, id("Delta"), ListMode.COMPACT_LIST)
        repo.setCategoryListLayout(account, id("Beta"), ListMode.MAGAZINE)
        repo.setCategoryCardStyle(account, id("Beta"), CardLayoutStyle.EVEN)
        repo.setCategoryCardColumns(account, id("Beta"), 2)
        repo.setCategoryCardColumns(account, id("Eta"), 2)
        // Some reading state, so that an install over this release can be checked for it too.
        val articles = byPath.values.flatMap { cat -> c.database.subscriptionDao().memberships(account).filter { it.categoryId == cat }.flatMap { m -> c.database.articleDao().articlesOfFeed(m.feedId).map { it.id } } }
        articles.filterIndexed { i, _ -> i % 7 == 0 }.forEach { c.articles.setRead(it, true) }
        articles.filterIndexed { i, _ -> i % 11 == 0 }.forEach { c.articles.setSaved(it, true) }
        c.settings.setLastScope(ArticleScope.Category(id("Delta")))
        val order = repo.observeStructure(account).first().tabCategories.map { it.display }
        android.util.Log.i("P1RESTORE", "order=$order")
    }
}
