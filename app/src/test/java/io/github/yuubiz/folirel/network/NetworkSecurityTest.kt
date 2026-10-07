package io.github.yuubiz.folirel.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.backend.local.FeedFetcher
import io.github.yuubiz.folirel.backend.local.FetchOutcome
import io.github.yuubiz.folirel.model.FetchError
import io.github.yuubiz.folirel.testutil.LOOPBACK_ALLOWED_FOR_TESTS
import io.github.yuubiz.folirel.testutil.MutableClock
import io.github.yuubiz.folirel.testutil.TestEnv
import io.github.yuubiz.folirel.testutil.literalUrl
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** T41 URL/DNS, T42 HTTP/TLS, T43 image limits — through the same client factory the app uses. */
@RunWith(AndroidJUnit4::class)
class NetworkSecurityTest {
    private val server: MockWebServer = TestEnv.startServer()
    private val clock = MutableClock()

    @After
    fun tearDown() = server.close()

    private fun client(policy: AddressPolicy, allowHttp: Boolean, resolver: Dns = Dns.SYSTEM): OkHttpClient =
        HttpClients.feedClient(NetworkGuard(policy, { allowHttp }, resolver), "FolirelTest/1")

    private fun fetch(client: OkHttpClient, url: String) = runBlocking { FeedFetcher(client, clock).fetch(url, null, initialized = false) }

    private fun okFeed() = MockResponse.Builder().body("<rss version=\"2.0\"><channel><title>t</title></channel></rss>").build()

    @Test
    fun publicAddressClassification() {
        val blocked = listOf(
            "127.0.0.1", "127.8.9.10", "0.0.0.0", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1", "169.254.169.254",
            "100.64.0.1", "224.0.0.1", "255.255.255.255", "198.18.0.1", "192.0.2.1",
            "::1", "::", "fe80::1", "fc00::1", "fd12:3456::1", "ff02::1", "::ffff:127.0.0.1", "::ffff:10.0.0.1",
            "2002:0a00:0001::1", "64:ff9b::a00:1", "2001:db8::1",
        )
        val public = listOf("93.184.216.34", "1.1.1.1", "172.32.0.1", "100.128.0.1", "2606:4700:4700::1111", "2001:4860:4860::8888", "64:ff9b::808:808")
        for (a in blocked) assertFalse(a, PublicAddresses.isPublic(InetAddress.getByName(a)))
        for (a in public) assertTrue(a, PublicAddresses.isPublic(InetAddress.getByName(a)))
    }

    @Test
    fun productionPolicyRejectsLoopbackLiteralBeforeSending() {
        server.enqueue(okFeed())
        val literal = fetch(client(AddressPolicy.PUBLIC_ONLY, allowHttp = true), server.literalUrl("/f").toString())
        assertEquals(FetchOutcome.Failure(FetchError.SECURITY), literal)
        val viaLocalhostName = fetch(client(AddressPolicy.PUBLIC_ONLY, allowHttp = true), "http://localhost:${server.port}/f")
        assertEquals(FetchOutcome.Failure(FetchError.SECURITY), viaLocalhostName)
        assertEquals(0, server.requestCount)
        for (literal in listOf("http://127.1:${server.port}/f", "http://2130706433:${server.port}/f", "http://[::1]:${server.port}/f")) {
            assertEquals(literal, FetchOutcome.Failure(FetchError.SECURITY), fetch(client(AddressPolicy.PUBLIC_ONLY, true), literal))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun dnsAnswerIsValidatedAndUsedForTheConnection() {
        // Private answer → refused. The resolver is consulted exactly once per connection and its validated
        // answer is what OkHttp connects to (no separate pre-check that DNS rebinding could bypass).
        val lookups = AtomicInteger()
        val privateDns = Dns { lookups.incrementAndGet(); listOf(InetAddress.getByName("10.0.0.1")) }
        assertEquals(FetchOutcome.Failure(FetchError.SECURITY), fetch(client(AddressPolicy.PUBLIC_ONLY, true, privateDns), "http://evil.test/feed"))
        assertEquals(1, lookups.get())

        val rebinding = AtomicBoolean(false)
        val flipping = Dns {
            lookups.incrementAndGet()
            // Second answer would be private; it must never be requested for an established fetch.
            if (rebinding.getAndSet(true)) listOf(InetAddress.getByName("10.0.0.1")) else listOf(InetAddress.getByName("127.0.0.1"))
        }
        lookups.set(0)
        server.enqueue(okFeed())
        val outcome = fetch(client(LOOPBACK_ALLOWED_FOR_TESTS, true, flipping), "http://rebind.test:${server.port}/feed")
        assertTrue(outcome is FetchOutcome.Success)
        assertEquals(1, lookups.get())
    }

    @Test
    fun redirectToPrivateOrUnsafeTargetIsBlocked() {
        val c = client(LOOPBACK_ALLOWED_FOR_TESTS, allowHttp = true)
        server.enqueue(MockResponse.Builder().code(302).setHeader("Location", "http://169.254.169.254/latest/meta-data").build())
        assertEquals(FetchOutcome.Failure(FetchError.SECURITY), fetch(c, server.url("/r1").toString()))
        server.enqueue(MockResponse.Builder().code(302).setHeader("Location", "file:///etc/passwd").build())
        val fileRedirect = fetch(c, server.url("/r2").toString())
        assertTrue("$fileRedirect", fileRedirect is FetchOutcome.Failure)
        server.enqueue(MockResponse.Builder().code(301).setHeader("Location", "http://user:pw@127.0.0.1:${server.port}/x").build())
        assertEquals(FetchOutcome.Failure(FetchError.SECURITY), fetch(c, server.url("/r3").toString()))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun httpDisabledSendsNothingForFeedsRedirectsOrImages() {
        // Initial request
        assertEquals(FetchOutcome.Failure(FetchError.HTTP_BLOCKED), fetch(client(LOOPBACK_ALLOWED_FOR_TESTS, allowHttp = false), server.url("/f").toString()))
        assertEquals(0, server.requestCount)

        // HTTPS → HTTP redirect
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").addSubjectAlternativeName("localhost").build()
        val httpsServer = MockWebServer()
        httpsServer.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory())
        httpsServer.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val trust = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
            val trusting = client(LOOPBACK_ALLOWED_FOR_TESTS, allowHttp = false).newBuilder()
                .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
            // Positive control: HTTPS itself works with a trusted certificate while HTTP is disabled.
            httpsServer.enqueue(okFeed())
            assertTrue(fetch(trusting, httpsServer.literalUrl("/ok").toString()) is FetchOutcome.Success)
            httpsServer.enqueue(MockResponse.Builder().code(301).setHeader("Location", server.literalUrl("/plain").toString()).build())
            assertEquals(FetchOutcome.Failure(FetchError.HTTP_BLOCKED), fetch(trusting, httpsServer.literalUrl("/secure").toString()))
            assertEquals(2, httpsServer.requestCount)
            assertEquals(0, server.requestCount)
        } finally {
            httpsServer.close()
        }

        // Images use the same guard
        val images = HttpClients.imageClient(client(LOOPBACK_ALLOWED_FOR_TESTS, allowHttp = false), 8L * 1024 * 1024)
        val error = runCatching { images.newCall(Request.Builder().url(server.url("/img.jpg")).build()).execute().close() }.exceptionOrNull()
        assertTrue("$error", error is BlockedRequestException && error.error == FetchError.HTTP_BLOCKED)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun tlsVerificationIsNeverRelaxed() {
        val selfSigned = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
        val httpsServer = MockWebServer()
        httpsServer.useHttps(HandshakeCertificates.Builder().heldCertificate(selfSigned).build().sslSocketFactory())
        httpsServer.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            // Untrusted certificate (system trust only), even with HTTP allowed.
            assertEquals(FetchOutcome.Failure(FetchError.TLS), fetch(client(LOOPBACK_ALLOWED_FOR_TESTS, allowHttp = true), httpsServer.literalUrl("/f").toString()))
        } finally {
            httpsServer.close()
        }
        // Trusted certificate but for another host name → hostname verification fails.
        val wrongHost = HeldCertificate.Builder().addSubjectAlternativeName("other.example").build()
        val second = MockWebServer()
        second.useHttps(HandshakeCertificates.Builder().heldCertificate(wrongHost).build().sslSocketFactory())
        second.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val trust = HandshakeCertificates.Builder().addTrustedCertificate(wrongHost.certificate).build()
            val trusting = client(LOOPBACK_ALLOWED_FOR_TESTS, allowHttp = true).newBuilder()
                .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
            assertEquals(FetchOutcome.Failure(FetchError.TLS), fetch(trusting, second.literalUrl("/f").toString()))
            // Positive control with the same trust: matching host name succeeds.
            val right = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").build()
            val third = MockWebServer()
            third.useHttps(HandshakeCertificates.Builder().heldCertificate(right).build().sslSocketFactory())
            third.start(InetAddress.getByName("127.0.0.1"), 0)
            third.enqueue(okFeed())
            val trustRight = HandshakeCertificates.Builder().addTrustedCertificate(right.certificate).build()
            val ok = client(LOOPBACK_ALLOWED_FOR_TESTS, allowHttp = true).newBuilder().sslSocketFactory(trustRight.sslSocketFactory(), trustRight.trustManager).build()
            assertTrue(fetch(ok, third.literalUrl("/f").toString()) is FetchOutcome.Success)
            third.close()
        } finally {
            second.close()
        }
    }

    @Test
    fun imageSizeLimitStopsLargeBodies() {
        val images = HttpClients.imageClient(client(LOOPBACK_ALLOWED_FOR_TESTS, allowHttp = true), 1024)
        // Declared too large
        server.enqueue(MockResponse.Builder().setHeader("Content-Type", "image/jpeg").body(Buffer().write(ByteArray(4096))).build())
        val declared = runCatching { images.newCall(Request.Builder().url(server.url("/big.jpg")).build()).execute().use { it.body.bytes() } }.exceptionOrNull()
        assertTrue("$declared", declared is IOException)
        // Chunked (no Content-Length): fails while reading
        server.enqueue(MockResponse.Builder().setHeader("Content-Type", "image/jpeg").chunkedBody(Buffer().write(ByteArray(4096)), 512).build())
        val streamed = runCatching { images.newCall(Request.Builder().url(server.url("/big2.jpg")).build()).execute().use { it.body.bytes() } }.exceptionOrNull()
        assertTrue("$streamed", streamed is IOException)
        // Within limit
        server.enqueue(MockResponse.Builder().setHeader("Content-Type", "image/jpeg").body(Buffer().write(ByteArray(512))).build())
        assertEquals(512, images.newCall(Request.Builder().url(server.url("/ok.jpg")).build()).execute().use { it.body.bytes().size })
    }

    /** The limit is on what the image decoder would get: a small gzip body that expands past it is stopped too. */
    @Test
    fun imageSizeLimitCountsTheDecompressedBody() {
        val images = HttpClients.imageClient(client(LOOPBACK_ALLOWED_FOR_TESTS, allowHttp = true), 1024)
        val compressed = Buffer().also { out -> GzipSink(out).buffer().use { it.write(ByteArray(64 * 1024)) } }
        assertTrue(compressed.size < 1024)
        server.enqueue(MockResponse.Builder().setHeader("Content-Type", "image/jpeg").setHeader("Content-Encoding", "gzip").body(compressed).build())
        val bomb = runCatching { images.newCall(Request.Builder().url(server.url("/bomb.jpg")).build()).execute().use { it.body.bytes() } }.exceptionOrNull()
        assertTrue("$bomb", bomb is IOException)
        // A gzip body that stays within the limit after expanding is delivered whole.
        val small = Buffer().also { out -> GzipSink(out).buffer().use { it.write(ByteArray(800)) } }
        server.enqueue(MockResponse.Builder().setHeader("Content-Type", "image/jpeg").setHeader("Content-Encoding", "gzip").body(small).build())
        assertEquals(800, images.newCall(Request.Builder().url(server.url("/ok.jpg")).build()).execute().use { it.body.bytes().size })
    }

    @Test
    fun noCookiesAreStoredOrSent() {
        server.enqueue(okFeed().newBuilder().addHeader("Set-Cookie", "session=secret; Path=/").build())
        server.enqueue(okFeed())
        val c = client(LOOPBACK_ALLOWED_FOR_TESTS, allowHttp = true)
        fetch(c, server.url("/one").toString())
        fetch(c, server.url("/two").toString())
        server.takeRequest()
        assertEquals(null, server.takeRequest().headers["Cookie"])
    }

    @Test
    fun linkCheckForBrowserOpening() {
        val launcher = io.github.yuubiz.folirel.browser.BrowserLauncher()
        assertEquals(io.github.yuubiz.folirel.browser.BrowserLauncher.LinkCheck.Missing, launcher.check(null))
        for (bad in listOf("javascript:alert(1)", "file:///sdcard/x", "intent://x#Intent;end", "content://a/b", "http://127.0.0.1/", "https://localhost/a", "https://user:p@example.com/")) {
            assertEquals(bad, io.github.yuubiz.folirel.browser.BrowserLauncher.LinkCheck.Unsafe, launcher.check(bad))
        }
        assertEquals(io.github.yuubiz.folirel.browser.BrowserLauncher.LinkCheck.Ok("https://example.com/a", true), launcher.check("https://example.com/a#x"))
        assertEquals(io.github.yuubiz.folirel.browser.BrowserLauncher.LinkCheck.Ok("http://example.com/a", false), launcher.check("http://example.com/a"))
    }
}
