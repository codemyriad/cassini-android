package io.codemyriad.cassini.whistle

/** One outstanding command at a time; the client waits on replies, never sleeps or an unbounded PCM queue. */
internal object WhistleProtocol {
    const val OPEN = 1
    const val ACCEPT = 2
    const val FINISH = 3
    const val SNAPSHOT = 4
    const val SEGMENT = 5
    const val ERROR = 6
    const val SERVICE_PROCESS = ":whistle"

    /** Android can append the service component to an isolated process's declared name. */
    fun ownsProcess(packageName: String, processName: String): Boolean {
        val prefix = "$packageName$SERVICE_PROCESS"
        return processName == prefix || processName.startsWith("$prefix:")
    }
}
