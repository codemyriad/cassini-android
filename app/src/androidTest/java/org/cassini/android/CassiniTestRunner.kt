package org.cassini.android

import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.test.runner.AndroidJUnitRunner
import org.cassini.data.AppPaths
import java.io.File

/**
 * The instrumentation runner (docs/architecture.md section 9). Before `Application.onCreate` it installs a graph
 * factory that builds the process's [AppGraph] on a scratch root, `filesDir/androidTest-<run>` with the cache in
 * `cacheDir/androidTest-<run>`, so a test never touches the user's notes, voices or settings. The models stay at
 * `filesDir`, so the tests use the installed models; with the argument `-e emptyModelRoot true` the model root is an
 * empty directory inside the scratch root instead (the download screen tests). The scratch root is deleted when the
 * run ends, and roots that an earlier run left behind (a crash) are deleted when a run starts. So is the database
 * lock file that androidx.sqlite keeps at `cacheDir/<absolute database path>.lck`.
 */
class CassiniTestRunner : AndroidJUnitRunner() {
    private var root: File? = null
    private var cache: File? = null
    private var locks: File? = null

    @Volatile private var graph: AppGraph? = null

    override fun onCreate(arguments: Bundle?) {
        emptyModelRoot = arguments?.getString(ARG_EMPTY_MODEL_ROOT) == "true"
        super.onCreate(arguments)
    }

    override fun callApplicationOnCreate(app: Application) {
        val run = "$PREFIX${System.currentTimeMillis()}-${android.os.Process.myPid()}"
        removeLeftovers(app.filesDir)
        removeLeftovers(app.cacheDir)
        removeLeftovers(File(app.cacheDir, app.filesDir.path))
        val scratch = File(app.filesDir, run).also { root = it }
        val scratchCache = File(app.cacheDir, run).also { cache = it }
        locks = File(app.cacheDir, scratch.path)
        val models = if (emptyModelRoot) File(scratch, "models") else app.filesDir
        CassiniApplication.graphFactory = GraphFactory { application, _ ->
            AppGraph(application, AppGraph.Config(AppPaths(root = scratch, cache = scratchCache, modelRoot = models)))
                .also { graph = it }
        }
        Log.i(TAG, "Scratch root $scratch, models in $models")
        super.callApplicationOnCreate(app)
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        graph?.close()
        root?.deleteRecursively()
        cache?.deleteRecursively()
        locks?.deleteRecursively()
        super.finish(resultCode, results)
    }

    private fun removeLeftovers(directory: File) {
        directory.listFiles { file -> file.isDirectory && file.name.startsWith(PREFIX) }.orEmpty()
            .forEach { it.deleteRecursively() }
    }

    companion object {
        private const val TAG = "CassiniTestRunner"
        private const val PREFIX = "androidTest-"

        /** The instrumentation argument that gives the run an empty model root. */
        const val ARG_EMPTY_MODEL_ROOT = "emptyModelRoot"

        /** True when this run has an empty model root ([ARG_EMPTY_MODEL_ROOT]); a test that needs it checks it. */
        @Volatile
        var emptyModelRoot: Boolean = false
            private set
    }
}
