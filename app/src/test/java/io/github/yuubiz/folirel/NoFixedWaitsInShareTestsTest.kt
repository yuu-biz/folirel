package io.github.yuubiz.folirel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P3C-T14 (0.9.3): the tests of the share flow wait for the state they need (a flow value, a deferred, the app's own readiness), never for
 * a length of time. A fixed sleep is either too short (a flaky test) or too long (a slow one); a stretched one is not a fix, so none of
 * these files may contain one.
 */
class NoFixedWaitsInShareTestsTest {
    private val shareTests = listOf(
        "src/test/java/io/github/yuubiz/folirel/sync/ShareToSubscribeTest.kt",
        "src/test/java/io/github/yuubiz/folirel/app/MainActivityShareTest.kt",
        "src/test/java/io/github/yuubiz/folirel/app/ShareIntentTest.kt",
        "src/test/java/io/github/yuubiz/folirel/ui/share/ShareSubscriptionControllerTest.kt",
        "src/test/java/io/github/yuubiz/folirel/network/SharedUrlExtractorTest.kt",
        "src/androidTest/java/io/github/yuubiz/folirel/ShareOverlayUiTest.kt",
    )

    private val forbidden = listOf(
        Regex("""Thread\s*\.\s*sleep"""),
        Regex("""SystemClock\s*\.\s*sleep"""),
        Regex("""TimeUnit\.[A-Z]+\s*\.\s*sleep"""),
        Regex("""\bdelay\s*\("""),
        Regex("""\bsleep\s*\("""),
        Regex("""\bPOLL_INTERVAL\b"""),
    )

    @Test
    fun noShareTestSleeps() {
        val violations = ArrayList<String>()
        for (path in shareTests) {
            val file = File(path)
            assertTrue("$path exists", file.exists())
            file.readLines().forEachIndexed { i, line ->
                // The explanations in comments may name what is not done.
                val code = line.substringBefore("//")
                forbidden.filter { it.containsMatchIn(code) }.forEach { violations += "$path:${i + 1}: ${it.pattern}" }
            }
        }
        assertEquals(emptyList<String>(), violations)
    }
}
