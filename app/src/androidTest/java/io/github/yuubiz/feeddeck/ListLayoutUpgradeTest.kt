package io.github.yuubiz.feeddeck

import android.content.Context
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import io.github.yuubiz.feeddeck.app.MainActivity
import io.github.yuubiz.feeddeck.data.preferences.SettingsMigrations
import io.github.yuubiz.feeddeck.data.preferences.SettingsRepository
import io.github.yuubiz.feeddeck.model.ArticleRow
import io.github.yuubiz.feeddeck.model.CardLayoutStyle
import io.github.yuubiz.feeddeck.model.ListMode
import io.github.yuubiz.feeddeck.ui.home.ArticleActions
import io.github.yuubiz.feeddeck.ui.home.ArticleList
import io.github.yuubiz.feeddeck.ui.home.ArticlesState
import io.github.yuubiz.feeddeck.ui.home.ListAnchor
import io.github.yuubiz.feeddeck.ui.home.ListHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The list layout was renamed in 0.3.2 (old MAGAZINE -> LIST, old LIST -> COMPACT_LIST, old CARD -> MAGAZINE). For each of
 * the three values 0.3.1 stored, a real DataStore file on the device is written the way 0.3.1 wrote it, opened with the
 * production migrations, and the layout it selects is DRAWN: what is on screen must be what 0.3.1 drew for that value.
 * "What 0.3.1 drew" is described by what can be seen, not by names: a grid of cards (two articles side by side),
 * one column with a summary line under each title, or one dense row per article without summary.
 */
@RunWith(AndroidJUnit4::class)
class ListLayoutUpgradeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val legacyKey = stringPreferencesKey("list_mode")

    private enum class Drawn { GRID_OF_CARDS, ONE_COLUMN_WITH_SUMMARY, ONE_DENSE_ROW_EACH }

    @Before
    fun quietApp() {
        TestData.reset()
    }

    /** The settings of a user who last ran 0.3.1 with [stored] chosen, upgraded to this build. */
    private fun upgradedLayout(stored: String): ListMode {
        val file = File(context.cacheDir, "upgrade-$stored-${System.nanoTime()}.preferences_pb")
        val old = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        runBlocking { PreferenceDataStoreFactory.create(scope = old, produceFile = { file }).edit { it[legacyKey] = stored } }
        old.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope, migrations = SettingsMigrations.all, produceFile = { file })
            return runBlocking { SettingsRepository(store).settings.first().listMode }
        } finally {
            scope.cancel()
        }
    }

    private fun row(n: Int) = ArticleRow(
        id = "a$n", feedId = "f", title = "記事 $n", summaryText = "概要 $n", linkUrl = "https://example.com/$n", imageUrl = null,
        author = null, publishedAt = 1_000_000L - n, sortAt = 1_000_000L - n, isRead = false, isSaved = false,
        feedName = "feed", feedUrl = "https://feed.example.com/", feedSubscribed = true,
    )

    private fun draw(mode: ListMode) {
        val host = object : ListHost {
            override fun anchorFor(listKey: String): ListAnchor? = null
            override fun saveAnchor(anchor: ListAnchor) = Unit
            override fun markNewSeen(ids: Collection<String>) = Unit
        }
        val items = (0 until 40).map(::row)
        val actions = ArticleActions({}, {}, {}, { _, _ -> }, {}, {})
        compose.runOnUiThread {
            val view = ComposeView(compose.activity)
            compose.activity.setContentView(view)
            view.setContent {
                io.github.yuubiz.feeddeck.ui.theme.FeedDeckTheme(io.github.yuubiz.feeddeck.data.preferences.ThemeMode.SYSTEM, 1.0f) {
                    ArticleList(
                        host = host, state = ArticlesState("layout-check", items, loaded = true), mode = mode, showImages = false,
                        cardColumns = 0, cardStyle = CardLayoutStyle.MIXED, imageLoader = ImageLoader.Builder(context).build(),
                        actions = actions, newIds = emptySet(), newestFirst = true, isCurrent = true, scrollToTopTick = 0,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** What the screen shows, judged from what is visible. */
    private fun whatIsDrawn(): Drawn {
        val tops = (0 until 12).mapNotNull { n ->
            runCatching { compose.onAllNodes(hasTestTag("article_a$n"))[0].getUnclippedBoundsInRoot() }.getOrNull()
        }
        val sideBySide = tops.any { a -> tops.any { b -> a !== b && kotlin.math.abs(a.top.value - b.top.value) < 1f && kotlin.math.abs(a.left.value - b.left.value) > 20f } }
        if (sideBySide) return Drawn.GRID_OF_CARDS
        val hasSummary = compose.onAllNodes(hasText("概要 0")).fetchSemanticsNodes().isNotEmpty()
        return if (hasSummary) Drawn.ONE_COLUMN_WITH_SUMMARY else Drawn.ONE_DENSE_ROW_EACH
    }

    private fun check(stored: String, drawnBy031: Drawn) {
        val layout = upgradedLayout(stored)
        draw(layout)
        assertEquals("0.3.1 stored $stored and drew $drawnBy031; after the upgrade ($layout) the screen shows", drawnBy031, whatIsDrawn())
    }

    @Test
    fun oldMagazineStillDrawsTheOneColumnListWithSummary() = check("MAGAZINE", Drawn.ONE_COLUMN_WITH_SUMMARY)

    @Test
    fun oldListStillDrawsTheDenseRows() = check("LIST", Drawn.ONE_DENSE_ROW_EACH)

    @Test
    fun oldCardStillDrawsTheGridOfCards() = check("CARD", Drawn.GRID_OF_CARDS)
}
