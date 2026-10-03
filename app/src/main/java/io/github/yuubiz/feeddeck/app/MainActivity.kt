package io.github.yuubiz.feeddeck.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.yuubiz.feeddeck.data.preferences.AppSettings
import io.github.yuubiz.feeddeck.ui.home.HomeNavigation
import io.github.yuubiz.feeddeck.ui.home.HomeScreen
import io.github.yuubiz.feeddeck.ui.home.HomeViewModel
import io.github.yuubiz.feeddeck.ui.onboarding.OnboardingNext
import io.github.yuubiz.feeddeck.ui.onboarding.OnboardingScreen
import io.github.yuubiz.feeddeck.ui.settings.SettingsScreen
import io.github.yuubiz.feeddeck.ui.settings.SettingsViewModel
import io.github.yuubiz.feeddeck.ui.subscriptions.AddFeedDialog
import io.github.yuubiz.feeddeck.ui.subscriptions.CategoriesScreen
import io.github.yuubiz.feeddeck.ui.subscriptions.FeedDetailScreen
import io.github.yuubiz.feeddeck.ui.subscriptions.HttpsUpgradeScreen
import io.github.yuubiz.feeddeck.ui.subscriptions.ImportScreen
import io.github.yuubiz.feeddeck.ui.subscriptions.ImportViewModel
import io.github.yuubiz.feeddeck.ui.subscriptions.SubscriptionEvents
import io.github.yuubiz.feeddeck.ui.subscriptions.SubscriptionsScreen
import io.github.yuubiz.feeddeck.ui.subscriptions.SubscriptionsViewModel
import io.github.yuubiz.feeddeck.ui.theme.FeedDeckTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as FeedDeckApp).container
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null as AppSettings?)
            val current = settings ?: return@setContent // render nothing until preferences are loaded
            FeedDeckTheme(current.theme, current.textScale) {
                Surface(Modifier.fillMaxSize()) {
                    AppNavigation(container, current)
                }
            }
        }
    }
}

/** Minimal back stack: home is the root; routes are plain strings so they survive process death. */
@Composable
private fun AppNavigation(container: AppContainer, settings: AppSettings) {
    var stack by rememberSaveable { mutableStateOf(listOf(ROUTE_HOME)) }
    var addFeedOpen by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun push(route: String) {
        stack = stack + route
    }
    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }
    BackHandler(enabled = stack.size > 1) { pop() }

    val homeVm: HomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    container.articles, container.subscriptions, container.settings, container.refresh,
                    container.networkStatus.observe(), createSavedStateHandle(),
                )
            }
        },
    )
    val importVm: ImportViewModel = viewModel(
        factory = viewModelFactory { initializer { ImportViewModel(container.documents, container.subscriptions, container.settings, container.refresh) } },
    )
    val subscriptionsVm: SubscriptionsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SubscriptionsViewModel(
                    container.subscriptions, container.refresh, container.httpsUpgrade, container.documents,
                    { container.settings.current().allowHttp }, container.clock,
                )
            }
        },
    )
    val settingsVm: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    container.settings, container.articles, container.retention, container.imageLoader,
                    accountId = { container.subscriptions.ensureLocalAccount() },
                    databaseFileBytes = { container.databaseFileBytes() },
                )
            }
        },
    )
    // Results of add-feed / export / category operations, wherever they were triggered from.
    val rootSnackbar = remember { SnackbarHostState() }
    SubscriptionEvents(subscriptionsVm, rootSnackbar)

    val openOpml = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // Cancel: nothing happens, no message.
        if (uri != null) {
            importVm.load(uri)
            if (stack.lastOrNull() != ROUTE_IMPORT) push(ROUTE_IMPORT)
        }
    }
    val createOpml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
        if (uri != null) subscriptionsVm.export(uri)
    }
    val pickOpml = { openOpml.launch(arrayOf("*/*")) }
    val exportOpml = { createOpml.launch("feeddeck-subscriptions.opml") }

    Box(Modifier.fillMaxSize()) {
        if (!settings.onboardingDone) {
            OnboardingScreen { autoUpdate, next ->
                scope.launch {
                    container.settings.setRefreshIntervalMinutes(if (autoUpdate) 120 else 0)
                    container.settings.setStartupRefresh(autoUpdate)
                    container.settings.setOnboardingDone(true)
                }
                when (next) {
                    OnboardingNext.IMPORT -> pickOpml()
                    OnboardingNext.ADD_FEED -> addFeedOpen = true
                    OnboardingNext.SKIP -> Unit
                }
            }
        } else {
            val route = stack.last()
            when {
                route == ROUTE_HOME -> HomeScreen(
                    vm = homeVm,
                    imageLoader = container.imageLoader,
                    browser = container.browser,
                    httpsState = container.httpsUpgrade.state,
                    nav = HomeNavigation(
                        importOpml = pickOpml,
                        addFeed = { addFeedOpen = true },
                        subscriptions = { push(ROUTE_SUBSCRIPTIONS) },
                        settings = { push(ROUTE_SETTINGS) },
                    ),
                )
                route == ROUTE_IMPORT -> ImportScreen(
                    importVm,
                    onPickAgain = pickOpml,
                    onClose = { importVm.reset(); pop() },
                    onHttpsCheck = { start ->
                        // "Check and change" in the consent dialog is the user's consent for the whole batch.
                        if (start) subscriptionsVm.startHttpsUpgrade()
                        push(ROUTE_HTTPS)
                    },
                )
                route == ROUTE_SUBSCRIPTIONS -> SubscriptionsScreen(
                    vm = subscriptionsVm,
                    onBack = ::pop,
                    onOpenFeed = { push(ROUTE_FEED_PREFIX + it) },
                    onAddFeed = { addFeedOpen = true },
                    onImport = pickOpml,
                    onExport = exportOpml,
                    onCategories = { push(ROUTE_CATEGORIES) },
                    onHttpsCheck = { push(ROUTE_HTTPS) },
                )
                route == ROUTE_HTTPS -> HttpsUpgradeScreen(subscriptionsVm, onBack = ::pop)
                route == ROUTE_CATEGORIES -> CategoriesScreen(subscriptionsVm, onBack = ::pop)
                route.startsWith(ROUTE_FEED_PREFIX) -> FeedDetailScreen(subscriptionsVm, route.removePrefix(ROUTE_FEED_PREFIX), onBack = ::pop)
                route == ROUTE_SETTINGS -> SettingsScreen(settingsVm, onBack = ::pop, onExport = exportOpml, onHttpsCheck = { push(ROUTE_HTTPS) })
            }
        }
        SnackbarHost(rootSnackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
    if (addFeedOpen) {
        AddFeedDialog(
            onAdd = { url ->
                addFeedOpen = false
                subscriptionsVm.addFeed(url)
            },
            onDismiss = { addFeedOpen = false },
        )
    }
}

private const val ROUTE_HOME = "home"
private const val ROUTE_IMPORT = "import"
private const val ROUTE_SUBSCRIPTIONS = "subscriptions"
private const val ROUTE_CATEGORIES = "categories"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_FEED_PREFIX = "feed/"
private const val ROUTE_HTTPS = "https"
