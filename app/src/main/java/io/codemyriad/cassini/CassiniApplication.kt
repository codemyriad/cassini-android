package io.codemyriad.cassini

import android.app.Application
import android.os.Build
import android.os.StrictMode
import android.system.Os
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.processing.NotificationChannels

/** Builds the process's [AppGraph]. The instrumentation runner installs one on a scratch root. */
fun interface GraphFactory {
    fun create(app: CassiniApplication, paths: AppPaths): AppGraph
}

class CassiniApplication : Application() {
    /** Resolved once at process start, before StrictMode, so `files/debug-root.txt` is read only then. */
    private lateinit var paths: AppPaths

    /**
     * The process's graph, created on first use: a service start, or the first ON_START of the process (see
     * [onCreate]). Creating it starts the startup work of architecture 4.8 on IO, so that work runs once per process,
     * also when only a service starts.
     */
    val graph: AppGraph by lazy {
        (graphFactory ?: GraphFactory { app, paths -> AppGraph(app, AppGraph.Config(paths)) }).create(this, paths)
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && Application.getProcessName() == "$packageName:whistle") {
            Os.setenv("NEEDLE_TELEMETRY", "0", true)
            Os.setenv("DO_NOT_TRACK", "1", true)
            Os.setenv("ORT_DISABLE_TELEMETRY", "1", true)
            return // The private worker must not initialize the main graph or load sherpa's ORT.
        }
        paths = AppRoots.resolve(filesDir, cacheDir, BuildConfig.DEBUG)
        if (paths.root != filesDir) Log.i(TAG, "Debug root: ${paths.root}")
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
        }
        NotificationChannels.create(this)
        // The first ON_START creates the graph: its startup work runs, and its coordinator starts a waiting queue
        // (architecture 4.8 and 5.3). Not at once: a JVM test installs its graphFactory after onCreate.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    owner.lifecycle.removeObserver(this)
                    graph
                }
            },
        )
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
