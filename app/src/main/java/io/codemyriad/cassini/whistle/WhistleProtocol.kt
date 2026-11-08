package io.codemyriad.cassini.whistle

/** One outstanding command at a time; the client waits on replies, never sleeps or an unbounded PCM queue. */
internal object WhistleProtocol {
    const val OPEN = 1
    const val ACCEPT = 2
    const val FINISH = 3
    const val SEGMENT = 5
    const val ERROR = 6
    const val SERVICE_PROCESS = ":whistle"
}
