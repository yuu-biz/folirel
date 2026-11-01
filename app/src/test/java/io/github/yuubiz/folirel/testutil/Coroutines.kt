package io.github.yuubiz.folirel.testutil

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Cancels [jobs] in order and waits until each has ended. Tests call this before `Dispatchers.resetMain()`: a coroutine
 * still running on another thread can dispatch to Main while it is reset, which fails with "Dispatchers.Main is used
 * concurrently with setting it". A job that does not end within the limit fails the test instead of hanging it.
 */
fun cancelAndJoinAll(jobs: Iterable<Job>) = runBlocking {
    withTimeout(10_000) { jobs.forEach { it.cancelAndJoin() } }
}

/** The job of the view model's own scope; nothing clears a view model in these tests, so its work outlives the test otherwise. */
val ViewModel.scopeJob: Job get() = viewModelScope.coroutineContext.job
