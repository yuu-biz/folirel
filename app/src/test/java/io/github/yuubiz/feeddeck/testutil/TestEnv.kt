package io.github.yuubiz.feeddeck.testutil

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.yuubiz.feeddeck.backend.local.FeedFetcher
import io.github.yuubiz.feeddeck.backend.local.LocalBackend
import io.github.yuubiz.feeddeck.data.db.AppDatabase
import io.github.yuubiz.feeddeck.data.repository.ArticleRepository
import io.github.yuubiz.feeddeck.data.repository.FeedStore
import io.github.yuubiz.feeddeck.data.repository.IdGenerator
import io.github.yuubiz.feeddeck.data.repository.SubscriptionRepository
import io.github.yuubiz.feeddeck.network.AddressPolicy
import io.github.yuubiz.feeddeck.network.HttpClients
import io.github.yuubiz.feeddeck.network.NetworkGuard
import io.github.yuubiz.feeddeck.network.NetworkState
import io.github.yuubiz.feeddeck.network.NetworkStatus
import mockwebserver3.MockWebServer
import okhttp3.Dns
import java.net.InetAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Settable clock for deterministic timestamps. */
class MutableClock(var now: Instant = Instant.parse("2026-10-03T03:00:00Z")) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    override fun instant(): Instant = now
    fun advanceMinutes(minutes: Long) {
        now = now.plusSeconds(minutes * 60)
    }
}

class SequentialIds(private val prefix: String = "id") : IdGenerator {
    private val next = AtomicInteger()
    override fun newId(): String = "$prefix-${next.incrementAndGet()}"
}

/**
 * TEST ONLY: allows loopback so MockWebServer (bound to the 127.0.0.1 literal) is reachable.
 * Production code always uses [AddressPolicy.PUBLIC_ONLY]; this policy exists only in the test source set.
 */
val LOOPBACK_ALLOWED_FOR_TESTS = AddressPolicy { it.isLoopbackAddress || AddressPolicy.PUBLIC_ONLY.isAllowed(it) }

/** Wires the real data/network stack against an in-memory Room DB and a local MockWebServer. */
class TestEnv(
    val db: AppDatabase = inMemoryDb(),
    val clock: MutableClock = MutableClock(),
    val ids: IdGenerator = SequentialIds(),
    addressPolicy: AddressPolicy = LOOPBACK_ALLOWED_FOR_TESTS,
    resolver: Dns = Dns.SYSTEM,
    budgetMillis: Long = LocalBackend.DEFAULT_BUDGET_MS,
    maxParallel: Int = 4,
    maxPerHost: Int = 2,
) {
    val allowHttp = AtomicBoolean(true)
    var networkState = NetworkState(connected = true, metered = false)
    val network = NetworkStatus { networkState }
    val guard = NetworkGuard(addressPolicy, { allowHttp.get() }, resolver)
    val httpClient = HttpClients.feedClient(guard, "FeedDeckTest/1")
    val fetcher = FeedFetcher(httpClient, clock)
    val subscriptions = SubscriptionRepository(db, clock, ids)
    val store = FeedStore(db, clock, ids)
    val articles = ArticleRepository(db, clock)
    val backend = LocalBackend(store, fetcher, clock, network, { allowHttp.get() }, budgetMillis, maxParallel, maxPerHost)

    fun close() {
        db.close()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    companion object {
        fun context(): Context = ApplicationProvider.getApplicationContext()

        fun inMemoryDb(): AppDatabase = Room.inMemoryDatabaseBuilder(context(), AppDatabase::class.java).build()

        fun startServer(): MockWebServer = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    }
}

/** URL with the 127.0.0.1 literal (server.url() may use a host name that reverse-resolves on this machine). */
fun MockWebServer.literalUrl(path: String): okhttp3.HttpUrl = url(path).newBuilder().host("127.0.0.1").build()
