package io.github.yuubiz.folirel

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.data.preferences.ThemeMode
import io.github.yuubiz.folirel.model.ArticleScope
import io.github.yuubiz.folirel.model.CardLayoutStyle
import io.github.yuubiz.folirel.model.ListMode
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not an assertion test: puts a realistic state into an installed app for the upgrade check (P1-T31). It is run with
 * `am instrument -e class io.github.yuubiz.folirel.P1SeedTest` against the PREVIOUS release (0.4.2), which is installed first, so it
 * uses only what that release has: its own repositories and settings. Subscriptions in nested categories, articles, some read
 * and some saved, and a handful of changed settings; no network.
 */
@RunWith(AndroidJUnit4::class)
class P1SeedTest {
    @Test
    fun seedForTheUpgradeCheck() = runBlocking<Unit> {
        TestData.reset()
        val seeded = TestData.seedCategories(listOf("Alpha", "Beta", "Gamma", "Delta", "Epsilon", "Zeta"), perCategory = 30)
        val articles = seeded.values.flatten()
        val c = TestData.container
        articles.filterIndexed { i, _ -> i % 12 == 0 }.forEach { c.articles.setRead(it, true) }
        articles.filterIndexed { i, _ -> i % 35 == 0 }.forEach { c.articles.setSaved(it, true) }
        c.settings.setListMode(ListMode.MAGAZINE)
        c.settings.setCardColumns(2)
        c.settings.setCardStyle(CardLayoutStyle.EVEN)
        c.settings.setTheme(ThemeMode.DARK)
        c.settings.setTextScale(1.15f)
        c.settings.setLastScope(ArticleScope.Category(seeded.keys.elementAt(2)))
        android.util.Log.i("P1SEED", "articles=${articles.size} read=${articles.count { TestData.state(it).isRead }} saved=${articles.count { TestData.state(it).isSaved }}")
    }
}
