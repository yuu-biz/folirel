package io.github.yuubiz.folirel.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.yuubiz.folirel.R
import io.github.yuubiz.folirel.data.backup.BackupFormat
import io.github.yuubiz.folirel.data.preferences.AppSettings
import io.github.yuubiz.folirel.ui.home.FeedIconStore
import io.github.yuubiz.folirel.ui.home.HomeNavigation
import io.github.yuubiz.folirel.ui.home.HomeScreen
import io.github.yuubiz.folirel.ui.home.HomeViewModel
import io.github.yuubiz.folirel.ui.home.ProvideFeedIcons
import io.github.yuubiz.folirel.ui.onboarding.OnboardingNext
import io.github.yuubiz.folirel.ui.onboarding.OnboardingScreen
import io.github.yuubiz.folirel.ui.settings.BackupViewModel
import io.github.yuubiz.folirel.ui.settings.RestoreScreen
import io.github.yuubiz.folirel.ui.settings.SettingsPage
import io.github.yuubiz.folirel.ui.settings.SettingsScreen
import io.github.yuubiz.folirel.ui.settings.SettingsViewModel
import io.github.yuubiz.folirel.ui.share.ShareSubscribeOverlay
import io.github.yuubiz.folirel.ui.subscriptions.AddFeedDialog
import io.github.yuubiz.folirel.ui.subscriptions.CategoriesScreen
import io.github.yuubiz.folirel.ui.subscriptions.FeedDetailScreen
import io.github.yuubiz.folirel.ui.subscriptions.HttpsUpgradeScreen
import io.github.yuubiz.folirel.ui.subscriptions.ImportScreen
import io.github.yuubiz.folirel.ui.subscriptions.ImportViewModel
import io.github.yuubiz.folirel.ui.subscriptions.SubscriptionEvents
import io.github.yuubiz.folirel.ui.subscriptions.SubscriptionsScreen
import io.github.yuubiz.folirel.ui.subscriptions.SubscriptionsViewModel
import io.github.yuubiz.folirel.ui.theme.FolirelTheme
import io.github.yuubiz.folirel.ui.theme.isDarkTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class MainActivity : ComponentActivity() {
    private val container get() = (application as FolirelApp).container

    // Owned here, not by the composition, so that a share can be handed over before anything is drawn and survives recreation.
    private val subscriptionsVm: SubscriptionsViewModel by viewModels {
        viewModelFactory {
            initializer {
                SubscriptionsViewModel(
                    container.subscriptions, container.refresh, container.httpsUpgrade, container.discovery, container.shareDiscovery, container.documents,
                    { container.settings.current().allowHttp }, container.clock, container.settings,
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = container
        // A recreated activity (rotation, process restart) gets its old intent back; that share was handled when it arrived.
        if (savedInstanceState == null) handleShare(intent)
        container.trimImageCacheOnce()
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null as AppSettings?)
            val current = settings ?: return@setContent // render nothing until preferences are loaded
            val dark = isDarkTheme(current.theme, isSystemInDarkTheme())
            LaunchedEffect(dark) { applySystemBars(dark) }
            FolirelTheme(current.theme, current.textScale, current.palette, current.customSeed, current.customLight, current.customDark) {
                val iconStore = remember { FeedIconStore { feedId -> container.feedIcons.png(feedId) } }
                val iconVersions = remember { flow { emit(container.subscriptions.ensureLocalAccount()) }.flatMapLatest { container.feedIcons.observeVersions(it) } }
                ProvideFeedIcons(iconStore, iconVersions, enabled = current.showImages) {
                    Surface(Modifier.fillMaxSize()) {
                        AppNavigation(container, current, subscriptionsVm)
                    }
                }
            }
        }
    }

    /** A later share while the app is running (or was left in the background): a new request over whatever is showing. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** Intent in, request out; looking at the text and the network are the share controller's business. */
    private fun handleShare(intent: Intent?) {
        val shared = ShareIntent.consume(intent) ?: return
        subscriptionsVm.share.receive(shared.text)
    }
}

/** Minimal back stack: home is the root; routes are plain strings so they survive process death. */
@Composable
private fun AppNavigation(container: AppContainer, settings: AppSettings, subscriptionsVm: SubscriptionsViewModel) {
    var stack by rememberSaveable { mutableStateOf(listOf(ROUTE_HOME)) }
    var addFeedOpen by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Each screen in the stack keeps its scroll position and inputs while another one is on top; a screen that leaves the stack forgets them.
    val screens = rememberSaveableStateHolder()
    fun setStack(next: List<String>) {
        stack.filterNot { it in next }.forEach(screens::removeState)
        stack = next
    }
    fun push(route: String) = setStack(stack + route)
    fun pop() {
        if (stack.size > 1) setStack(stack.dropLast(1))
    }
    // The back gesture drags the transition towards the screen below; letting go completes it, cancelling plays it back.
    val routeTransition = remember { SeekableTransitionState(stack) }
    LaunchedEffect(stack) { routeTransition.animateTo(stack) }
    PredictiveBackHandler(enabled = stack.size > 1) { progress ->
        val below = stack.dropLast(1)
        try {
            progress.collect { routeTransition.seekTo(it.progress, targetState = below) }
            pop()
        } catch (e: CancellationException) {
            scope.launch { routeTransition.animateTo(stack) }
            throw e
        }
    }

    val homeVm: HomeViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    container.articles, container.subscriptions, container.settings, container.refresh,
                    container.networkStatus.observe(), createSavedStateHandle(), container.clock, container.backup.replaced,
                )
            }
        },
    )
    val importVm: ImportViewModel = viewModel(
        factory = viewModelFactory { initializer { ImportViewModel(container.documents, container.subscriptions, container.settings, container.refresh) } },
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
    val backupVm: BackupViewModel = viewModel(
        factory = viewModelFactory { initializer { BackupViewModel(container.backup, container.documents, container.appScope) } },
    )
    // Results of add-feed / export / category operations, wherever they were triggered from.
    val rootSnackbar = remember { SnackbarHostState() }
    SubscriptionEvents(subscriptionsVm, rootSnackbar)
    // A share that arrived during first-run setup waits; it is looked at only after setup, which a share never skips.
    LaunchedEffect(settings.onboardingDone) { subscriptionsVm.share.setReady(settings.onboardingDone) }

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
    val noDocumentApp = stringResource(R.string.no_document_app)
    // Some devices (work profiles, stripped-down builds) have no app that handles the system file picker.
    fun launchDocuments(launch: () -> Unit) {
        try {
            launch()
        } catch (_: ActivityNotFoundException) {
            scope.launch { rootSnackbar.showSnackbar(noDocumentApp) }
        }
    }
    val pickOpml = { launchDocuments { openOpml.launch(arrayOf("*/*")) } }
    val exportOpml = { launchDocuments { createOpml.launch("folirel-subscriptions.opml") } }
    val createBackupDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupFormat.MIME_TYPE)) { uri ->
        if (uri != null) backupVm.createBackup(uri)
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        // Cancel: nothing happens. A picked file is only read and checked; the restore screen shows what it holds.
        if (uri != null) {
            backupVm.load(uri)
            if (stack.lastOrNull() != ROUTE_RESTORE) push(ROUTE_RESTORE)
        }
    }
    val createBackup = { launchDocuments { createBackupDocument.launch(BackupFormat.fileName(ZonedDateTime.now())) } }
    // Any type: some providers label a ZIP as octet-stream; the content decides, not the label.
    val pickBackup = { launchDocuments { openBackup.launch(arrayOf("*/*")) } }
    val settingsSaveFailed = stringResource(R.string.settings_save_failed)

    Box(Modifier.fillMaxSize()) {
        if (!settings.onboardingDone) {
            OnboardingScreen { autoUpdate, next ->
                // Not the composition's scope: a rotation would cancel the writes halfway. If they fail, setup stays on screen.
                container.appScope.launch {
                    try {
                        container.settings.setRefreshIntervalMinutes(if (autoUpdate) 120 else 0)
                        container.settings.setStartupRefresh(autoUpdate)
                        container.settings.setOnboardingDone(true)
                    } catch (_: IOException) {
                        withContext(Dispatchers.Main) { rootSnackbar.showSnackbar(settingsSaveFailed) }
                    }
                }
                when (next) {
                    OnboardingNext.IMPORT -> pickOpml()
                    OnboardingNext.ADD_FEED -> addFeedOpen = true
                    OnboardingNext.SKIP -> Unit
                }
            }
        } else rememberTransition(routeTransition, label = "route").AnimatedContent(
            transitionSpec = { routeTransition(forward = targetState.size >= initialState.size) },
            contentKey = { it.last() },
        ) { shown ->
            val route = shown.last()
            screens.SaveableStateProvider(route) {
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
                            settings = { push(SettingsPage.TOP_ROUTE) },
                            organizeTabs = { push(ROUTE_TAB_ORDER) },
                            feedDetail = { push(ROUTE_FEED_PREFIX + it) },
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
                    route == ROUTE_TAB_ORDER -> CategoriesScreen(subscriptionsVm, onBack = ::pop, initialOrder = true)
                    route.startsWith(ROUTE_FEED_PREFIX) -> FeedDetailScreen(subscriptionsVm, route.removePrefix(ROUTE_FEED_PREFIX), onBack = ::pop)
                    route == SettingsPage.TOP_ROUTE || SettingsPage.fromRoute(route) != null -> SettingsScreen(
                        settingsVm,
                        page = SettingsPage.fromRoute(route),
                        onOpenPage = { push(it.route) },
                        onBack = ::pop,
                        onExport = exportOpml,
                        onHttpsCheck = { push(ROUTE_HTTPS) },
                        backup = backupVm,
                        onCreateBackup = createBackup,
                        onRestore = pickBackup,
                    )
                    route == ROUTE_RESTORE -> RestoreScreen(
                        backupVm,
                        onPickAgain = pickBackup,
                        onClose = { backupVm.close(); pop() },
                        // The data changed under every screen: start again from the article list.
                        onFinished = {
                            backupVm.close()
                            setStack(listOf(ROUTE_HOME))
                            screens.removeState(ROUTE_HOME)
                        },
                    )
                }
            }
        }
        SnackbarHost(rootSnackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
    // A dialog over the current screen, not a route: the stack and the state under it stay as they are.
    ShareSubscribeOverlay(subscriptionsVm.share, onOpenFeed = { push(ROUTE_FEED_PREFIX + it) })
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

/**
 * Forward: the new screen comes in from the end over the old one. Back: the screen on top leaves towards the end and uncovers the one
 * below, which is also what the back gesture previews.
 */
private fun AnimatedContentTransitionScope<List<String>>.routeTransition(forward: Boolean): ContentTransform {
    val slide = tween<IntOffset>(ROUTE_MILLIS, easing = FastOutSlowInEasing)
    val fade = tween<Float>(ROUTE_MILLIS)
    return if (forward) {
        slideIntoContainer(SlideDirection.Start, slide) { it / 5 } + fadeIn(fade) togetherWith fadeOut(fade)
    } else {
        (fadeIn(fade) togetherWith slideOutOfContainer(SlideDirection.End, slide) { it / 5 } + fadeOut(fade)).apply { targetContentZIndex = -1f }
    }
}

private const val ROUTE_MILLIS = 300
private const val ROUTE_HOME = "home"
private const val ROUTE_IMPORT = "import"
private const val ROUTE_SUBSCRIPTIONS = "subscriptions"
private const val ROUTE_CATEGORIES = "categories"
private const val ROUTE_TAB_ORDER = "categories/order"
private const val ROUTE_FEED_PREFIX = "feed/"
private const val ROUTE_HTTPS = "https"
private const val ROUTE_RESTORE = "restore"
