package io.github.yuubiz.folirel.app

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.serviceLoaderEnabled
import io.github.yuubiz.folirel.BuildConfig
import io.github.yuubiz.folirel.backend.BackendSynchronizer
import io.github.yuubiz.folirel.backend.local.FeedFetcher
import io.github.yuubiz.folirel.backend.local.HttpsUpgradeChecker
import io.github.yuubiz.folirel.backend.local.LocalBackend
import io.github.yuubiz.folirel.backend.local.SmallResourceFetcher
import io.github.yuubiz.folirel.backend.local.icon.AndroidIconDecoder
import io.github.yuubiz.folirel.backend.local.discovery.FeedDiscovery
import io.github.yuubiz.folirel.backend.local.discovery.FeedProbe
import io.github.yuubiz.folirel.backend.local.icon.FeedIconResolver
import io.github.yuubiz.folirel.browser.BrowserLauncher
import io.github.yuubiz.folirel.data.db.AppDatabase
import io.github.yuubiz.folirel.data.preferences.SettingsRepository
import io.github.yuubiz.folirel.data.repository.ArticleRepository
import io.github.yuubiz.folirel.data.repository.FeedIconRepository
import io.github.yuubiz.folirel.data.repository.FeedStore
import io.github.yuubiz.folirel.data.repository.IdGenerator
import io.github.yuubiz.folirel.data.repository.RetentionManager
import io.github.yuubiz.folirel.data.repository.SubscriptionRepository
import io.github.yuubiz.folirel.model.Limits
import io.github.yuubiz.folirel.network.AddressPolicy
import io.github.yuubiz.folirel.network.AndroidNetworkStatus
import io.github.yuubiz.folirel.network.HttpClients
import io.github.yuubiz.folirel.network.NetworkGuard
import io.github.yuubiz.folirel.sync.AutoRefreshPolicy
import io.github.yuubiz.folirel.sync.FeedDiscoveryService
import io.github.yuubiz.folirel.sync.ShareDiscoveryService
import io.github.yuubiz.folirel.sync.FeedIconCoordinator
import io.github.yuubiz.folirel.sync.HttpsUpgradeCoordinator
import io.github.yuubiz.folirel.sync.RefreshCoordinator
import io.github.yuubiz.folirel.sync.RefreshScheduler
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toOkioPath
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean

private val Context.settingsDataStore by preferencesDataStore(name = "settings", corruptionHandler = SettingsRepository.corruptionHandler)

/** Manual dependency container (docs/architecture.md: no DI framework). One instance per process. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val clock: Clock = Clock.systemUTC()
    private val ids: IdGenerator = IdGenerator.Random
    /** Background work outlives screens. Failures are logged without URLs or content, never crash the app. */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            Log.w(TAG, "Background task failed: ${e.javaClass.simpleName}")
        },
    )

    val database: AppDatabase = AppDatabase.build(appContext)
    val settings = SettingsRepository(appContext.settingsDataStore)

    /** Mirrors the HTTP opt-in for the synchronous network guard. Starts as false (deny) until settings load. */
    private val allowHttp = AtomicBoolean(false)

    val networkStatus = AndroidNetworkStatus(appContext)
    private val guard = NetworkGuard(AddressPolicy.PUBLIC_ONLY, { allowHttp.get() })
    private val userAgent = "Folirel/${BuildConfig.VERSION_NAME} (Android; local RSS reader)"
    private val feedHttpClient = HttpClients.feedClient(guard, userAgent)
    private val imageHttpClient = HttpClients.imageClient(feedHttpClient, Limits.IMAGE_MAX_BYTES)

    val subscriptions = SubscriptionRepository(database, clock, ids)
    val articles = ArticleRepository(database, clock)
    private val feedStore = FeedStore(database, clock, ids)
    val retention = RetentionManager(database, clock)

    val backend: BackendSynchronizer = LocalBackend(
        store = feedStore,
        fetcher = FeedFetcher(feedHttpClient, clock),
        clock = clock,
        network = networkStatus,
        allowHttp = { allowHttp.get() },
    )

    /**
     * Source icons (UX-ICON): looked up once per feed in the background, through the same network client (HTTP
     * opt-in, address policy, TLS verification). Only while images are on and the same network rule as automatic
     * refresh allows it; a failed lookup never affects a feed's refresh result.
     */
    val feedIcons = FeedIconRepository(database, clock)
    val iconCoordinator = FeedIconCoordinator(
        resolver = FeedIconResolver(SmallResourceFetcher(feedHttpClient), AndroidIconDecoder()),
        repository = feedIcons,
        accountId = { subscriptions.ensureLocalAccount() },
        enabled = {
            val s = settings.current()
            val n = networkStatus.current()
            s.showImages && s.onboardingDone && n.connected && (!s.unmeteredOnly || !n.metered)
        },
        scope = appScope,
    )

    val refresh = RefreshCoordinator(
        backend = backend,
        accountId = { subscriptions.ensureLocalAccount() },
        automaticAllowed = { reason -> AutoRefreshPolicy.networkAllowed(settings.current(), networkStatus.current(), reason) },
        onAttempt = { settings.setLastRefreshAttempt(it) },
        scope = appScope,
        clock = clock,
        afterRefresh = {
            val limit = settings.current().retentionLimit
            if (limit > 0) retention.apply(subscriptions.ensureLocalAccount(), limit)
            iconCoordinator.request()
        },
    )

    /**
     * Consented HTTP → HTTPS check. Its own client never allows cleartext HTTP, whatever the user's HTTP setting,
     * so a redirect back to http:// fails instead of being followed; certificates are verified as usual.
     */
    val httpsUpgrade = HttpsUpgradeCoordinator(
        checker = HttpsUpgradeChecker(
            fetcher = FeedFetcher(HttpClients.feedClient(NetworkGuard(AddressPolicy.PUBLIC_ONLY, { false }), userAgent), clock),
            httpFetcher = FeedFetcher(feedHttpClient, clock),
            httpAllowed = { allowHttp.get() },
        ),
        subscriptions = subscriptions,
        refresh = refresh,
        scope = appScope,
    )

    /** Page and feed reading shared by the moved-feed search and Share to Subscribe: same client and policy as feeds (HTTP opt-in, address policy, TLS). */
    private val feedProbe = FeedProbe(SmallResourceFetcher(feedHttpClient), FeedFetcher(feedHttpClient, clock))

    /** In-site search for a moved feed. */
    val discovery = FeedDiscoveryService(
        discovery = FeedDiscovery(feedProbe),
        subscriptions = subscriptions,
    )

    /** "Subscribe to a shared address": looks only; the user's confirmation registers the feed through the add-feed path. */
    val shareDiscovery = ShareDiscoveryService(feedProbe, subscriptions)

    val scheduler = RefreshScheduler(appContext)

    val browser = BrowserLauncher()

    val documents = DocumentIo(appContext)

    fun databaseFileBytes(): Long = AppDatabase.fileBytes(appContext)

    /**
     * Thumbnails: same network policy, bounded disk cache, no service-loaded default fetchers. The cache size
     * setting is read once when the loader is first used (preferences are already loaded by then), so a change
     * applies at the next app start.
     */
    val imageLoader: ImageLoader by lazy { buildImageLoader(runBlocking { settings.current().imageCacheMb }) }

    private fun buildImageLoader(cacheMb: Int): ImageLoader = ImageLoader.Builder(appContext)
        .serviceLoaderEnabled(false)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { imageHttpClient })) }
        .diskCache {
            DiskCache.Builder()
                .directory(appContext.cacheDir.resolve("thumbnails").toOkioPath())
                .maxSizeBytes(cacheMb * 1024L * 1024L)
                .build()
        }
        .build()

    init {
        appScope.launch {
            var lastIconInputs: Pair<Boolean, Boolean>? = null
            settings.settings.collect { s ->
                allowHttp.set(s.allowHttp)
                scheduler.apply(s)
                // Images switched on, or HTTP allowed: icon lookups that could not run before may run now.
                val inputs = s.showImages to s.allowHttp
                if (inputs != lastIconInputs) {
                    lastIconInputs = inputs
                    iconCoordinator.request()
                }
            }
        }
    }

    companion object {
        private const val TAG = "Folirel"
        const val DB_WARNING_BYTES = 100L * 1024 * 1024
    }
}
