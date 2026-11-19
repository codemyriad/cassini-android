package io.codemyriad.cassini.whistle

import android.os.Bundle

/** One outstanding command at a time; the client waits on replies, never sleeps or an unbounded PCM queue. */
internal object WhistleProtocol {
    const val OPEN = 1
    const val ACCEPT = 2
    const val FINISH = 3
    const val SEED_CACHE = 4
    const val SEGMENT = 5
    const val ERROR = 6
    const val SERVICE_PROCESS = ":whistle"

    const val ERROR_MESSAGE_KEY = "error"
    const val ERROR_CODE_KEY = "errorCode"
    const val ERROR_CODE_GENERIC = 0
    const val ERROR_CODE_OUT_OF_MEMORY = 1

    /** Android can append the service component to an isolated process's declared name. */
    fun ownsProcess(packageName: String, processName: String): Boolean {
        val prefix = "$packageName$SERVICE_PROCESS"
        return processName == prefix || processName.startsWith("$prefix:")
    }

    fun errorBundle(message: String, code: Int = ERROR_CODE_GENERIC): Bundle = Bundle().apply {
        putString(ERROR_MESSAGE_KEY, message)
        putInt(ERROR_CODE_KEY, code)
    }

    fun throwError(data: Bundle): Nothing {
        val message = data.getString(ERROR_MESSAGE_KEY) ?: "Whistle worker failed"
        if (data.getInt(ERROR_CODE_KEY, ERROR_CODE_GENERIC) == ERROR_CODE_OUT_OF_MEMORY) {
            throw OutOfMemoryError(message)
        }
        error(message)
    }
}
