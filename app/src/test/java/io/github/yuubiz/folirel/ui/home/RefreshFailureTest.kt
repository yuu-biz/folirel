package io.github.yuubiz.folirel.ui.home

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuubiz.folirel.data.preferences.SettingsRepository
import io.github.yuubiz.folirel.network.NetworkState
import io.github.yuubiz.folirel.sync.RefreshCoordinator
import io.github.yuubiz.folirel.testutil.TestEnv
import io.github.yuubiz.folirel.testutil.cancelAndJoinAll
import io.github.yuubiz.folirel.testutil.scopeJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A manual refresh whose own bookkeeping fails (the storage is full: the attempt time cannot be written) must not end the process. The
 * view model's coroutine would otherwise end with an uncaught exception, which on a device is a crash.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class RefreshFailureTest {
    private val env = TestEnv()
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val viewModels = ArrayList<HomeViewModel>()
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        cancelAndJoinAll(viewModels.map { it.scopeJob } + bg.coroutineContext.job)
        env.close()
        Dispatchers.resetMain()
    }

    @Test
    fun aRefreshThatFailsBeforeFetchingDoesNotEscapeTheViewModel() {
        val coordinator = RefreshCoordinator(env.backend, { env.subscriptions.ensureLocalAccount() }, { true }, { throw IOException("No space left on device") }, bg, env.clock)
        val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = bg) { File(TestEnv.context().cacheDir, "rf-${System.nanoTime()}.preferences_pb") })
        val vm = HomeViewModel(env.articles, env.subscriptions, settings, coordinator, flowOf(NetworkState(true, false)), SavedStateHandle(), env.clock)
            .also { viewModels += it }
        // The dispatcher is unconfined: each call runs to the failing write before it returns.
        vm.refreshCurrentScope()
        vm.retryPending()
        assertEquals(emptyList<Throwable>(), uncaught.toList())
    }
}
