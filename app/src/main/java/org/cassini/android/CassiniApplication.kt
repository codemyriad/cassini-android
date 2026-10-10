package org.cassini.android

import android.app.Application
import android.os.StrictMode
import android.util.Log
import org.cassini.android.processing.NotificationChannels
import org.cassini.data.AppPaths

/** Builds the process's [AppGraph]. The instrumentation runner installs one on a scratch root. */
fun interface GraphFactory {
    fun create(app: CassiniApplication, paths: AppPaths): AppGraph
}

class CassiniApplication : Application() {
    /** One instance each, so their synchronized methods serialize every reader and writer. */
    internal val voices by lazy { VoiceStore(filesDir) }
    internal val noteVoices by lazy { NoteVoiceStore(filesDir) }

    /** Resolved once at process start, before StrictMode, so `files/debug-root.txt` is read only then. */
    private lateinit var paths: AppPaths

    /**
     * The process's graph, created on first use. Creating it starts the startup work of architecture 4.8 on IO, so
     * that work runs once per process, also when only a service starts.
     */
    val graph: AppGraph by lazy {
        (graphFactory ?: GraphFactory { app, paths -> AppGraph(app, AppGraph.Config(paths)) }).create(this, paths)
    }

    override fun onCreate() {
        super.onCreate()
        paths = AppRoots.resolve(filesDir, cacheDir, BuildConfig.DEBUG)
        if (paths.root != filesDir) Log.i(TAG, "Debug root: ${paths.root}")
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
        }
        NotificationChannels.create(this)
        // The old model directory contains only downloads. Retry on launch if deletion failed.
        if (!InstalledModels.removeLegacyModel(filesDir)) {
            Log.w(TAG, "Could not remove obsolete FP32 model downloads")
        }
        if (!InstalledModels.removeRetiredModels(filesDir)) {
            Log.w(TAG, "Could not remove obsolete speaker model downloads")
        }
        getSharedPreferences("recording", MODE_PRIVATE).edit().remove("transcribe_while_recording").apply()
    }

    companion object {
        private const val TAG = "Cassini"

        /**
         * The test hook: set before `Application.onCreate` (by the instrumentation runner) to build the graph on a
         * scratch root. Null in the app.
         */
        @Volatile
        var graphFactory: GraphFactory? = null
    }
}
