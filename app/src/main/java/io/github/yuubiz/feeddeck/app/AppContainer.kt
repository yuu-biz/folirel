package io.github.yuubiz.feeddeck.app

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.serviceLoaderEnabled
import io.github.yuubiz.feeddeck.BuildConfig
import io.github.yuubiz.feeddeck.backend.BackendSynchronizer
import io.github.yuubiz.feeddeck.backend.local.FeedFetcher
import io.github.yuubiz.feeddeck.backend.local.LocalBackend
import io.github.yuubiz.feeddeck.browser.BrowserLauncher
import io.github.yuubiz.feeddeck.data.db.AppDatabase
import io.github.yuubiz.feeddeck.data.preferences.SettingsRepository
import io.github.yuubiz.feeddeck.data.repository.ArticleRepository
import io.github.yuubiz.feeddeck.data.repository.FeedStore
import io.github.yuubiz.feeddeck.data.repository.IdGenerator
import io.github.yuubiz.feeddeck.data.repository.SubscriptionRepository
import io.github.yuubiz.feeddeck.model.Limits
import io.github.yuubiz.feeddeck.network.AddressPolicy
import io.github.yuubiz.feeddeck.network.AndroidNetworkStatus
import io.github.yuubiz.feeddeck.network.HttpClients
import io.github.yuubiz.feeddeck.network.NetworkGuard
import io.github.yuubiz.feeddeck.sync.RefreshCoordinator
import io.github.yuubiz.feeddeck.sync.RefreshScheduler
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** Manual dependency container (docs/05: no DI framework). One instance per process. */
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
    private val userAgent = "FeedDeck/${BuildConfig.VERSION_NAME} (Android; local RSS reader)"
    private val feedHttpClient = HttpClients.feedClient(guard, userAgent)
    private val imageHttpClient = HttpClients.imageClient(feedHttpClient, Limits.IMAGE_MAX_BYTES)

    val subscriptions = SubscriptionRepository(database, clock, ids)
    val articles = ArticleRepository(database, clock)
    private val feedStore = FeedStore(database, clock, ids)

    val backend: BackendSynchronizer = LocalBackend(
        store = feedStore,
        fetcher = FeedFetcher(feedHttpClient, clock),
        clock = clock,
        network = networkStatus,
        allowHttp = { allowHttp.get() },
    )

    val refresh = RefreshCoordinator(
        backend = backend,
        accountId = { subscriptions.ensureLocalAccount() },
        automaticAllowed = {
            val s = settings.current()
            val net = networkStatus.current()
            s.onboardingDone && s.refreshIntervalHours > 0 && net.connected && (!s.unmeteredOnly || !net.metered)
        },
        onAutomaticAttempt = { settings.setLastAutoRefreshAttempt(it) },
        scope = appScope,
        clock = clock,
    )

    val scheduler = RefreshScheduler(appContext)

    val browser = BrowserLauncher()

    val documents = DocumentIo(appContext)

    /** Thumbnails: same network policy, bounded disk cache, no service-loaded default fetchers. */
    val imageLoader: ImageLoader = ImageLoader.Builder(appContext)
        .serviceLoaderEnabled(false)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { imageHttpClient })) }
        .diskCache {
            DiskCache.Builder()
                .directory(appContext.cacheDir.resolve("thumbnails").toOkioPath())
                .maxSizeBytes(IMAGE_CACHE_BYTES)
                .build()
        }
        .build()

    init {
        appScope.launch {
            settings.settings.collect { s ->
                allowHttp.set(s.allowHttp)
                scheduler.apply(s)
            }
        }
    }

    companion object {
        private const val TAG = "FeedDeck"
        const val IMAGE_CACHE_BYTES = 128L * 1024 * 1024
        const val DB_WARNING_BYTES = 100L * 1024 * 1024
    }
}
