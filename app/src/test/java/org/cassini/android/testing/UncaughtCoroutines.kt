package org.cassini.android.testing

import kotlinx.coroutines.CoroutineExceptionHandler
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Records each exception that no coroutine handled, for example a failed `launch` in the application scope. The file
 * `META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler` installs it in every JVM test. kotlinx-coroutines
 * also gives the exception to its other handlers, so `runTest` still reports it.
 */
class UncaughtCoroutines : AbstractCoroutineContextElement(CoroutineExceptionHandler), CoroutineExceptionHandler {
    override fun handleException(context: CoroutineContext, exception: Throwable) {
        failures += exception
    }

    companion object {
        private val failures = ConcurrentLinkedQueue<Throwable>()

        /** Removes and returns the exceptions recorded until now. */
        fun drain(): List<Throwable> = generateSequence { failures.poll() }.toList()
    }
}

/**
 * Fails the test when a coroutine failed with an exception that nothing handled: before the test (work of an
 * earlier test failed after that test ended) or during the test and its `@After` methods. Without it, the exception
 * shows only at the start of the next `runTest`, in any class, as UncaughtExceptionsBeforeTest. Make it the outer
 * rule, so that it checks after the test closed its scopes.
 */
class NoUncaughtCoroutines : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            failure("before ${description.displayName}, from an earlier test")?.let { throw it }
            val result = runCatching { base.evaluate() }
            val leaked = failure("in ${description.displayName}")
            result.exceptionOrNull()?.let { error ->
                leaked?.let(error::addSuppressed)
                throw error
            }
            leaked?.let { throw it }
        }
    }

    private fun failure(where: String): AssertionError? {
        val failures = UncaughtCoroutines.drain()
        if (failures.isEmpty()) return null
        return AssertionError("A coroutine failed with an uncaught exception $where", failures.first()).apply {
            failures.drop(1).forEach(::addSuppressed)
        }
    }
}
