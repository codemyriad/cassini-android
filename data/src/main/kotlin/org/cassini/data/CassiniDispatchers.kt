package org.cassini.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The coroutine dispatchers every repository takes (docs/architecture.md, section 7): [io] for Room, DataStore and
 * file work, [default] for CPU work, [main] for the UI. Tests pass a test dispatcher for all three.
 */
data class CassiniDispatchers(
    val io: CoroutineDispatcher = Dispatchers.IO,
    val default: CoroutineDispatcher = Dispatchers.Default,
    val main: CoroutineDispatcher = Dispatchers.Main,
)
