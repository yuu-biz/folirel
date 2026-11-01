package io.github.yuubiz.folirel.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LatestSnackbarTest {
    @Test
    fun aNewMessageReplacesTheOneOnScreenAndOnlyItsUndoRuns() = runTest {
        val host = SnackbarHostState()
        val latest = LatestSnackbar(host, backgroundScope)
        val undone = mutableListOf<String>()

        latest.show("first", "Undo") { undone += "first" }
        runCurrent()
        assertEquals("first", host.currentSnackbarData?.visuals?.message)
        assertEquals(SnackbarDuration.Long, host.currentSnackbarData?.visuals?.duration)

        latest.show("second", "Undo") { undone += "second" }
        runCurrent()
        assertEquals("second", host.currentSnackbarData?.visuals?.message)
        host.currentSnackbarData!!.performAction()
        runCurrent()
        assertEquals(listOf("second"), undone)
        assertNull(host.currentSnackbarData)

        latest.show("plain")
        runCurrent()
        assertEquals(SnackbarDuration.Short, host.currentSnackbarData?.visuals?.duration)
    }
}
