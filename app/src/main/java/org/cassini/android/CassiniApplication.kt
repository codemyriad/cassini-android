package org.cassini.android

import android.app.Application
import android.util.Log

class CassiniApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // The old model directory contains only downloads. Retry on launch if deletion failed.
        if (!ModelStore.removeLegacyModel(filesDir)) Log.w("Cassini", "Could not remove obsolete FP32 model downloads")
        if (!DiarizationModels.removeRetiredModels(filesDir)) Log.w("Cassini", "Could not remove obsolete speaker model downloads")
        getSharedPreferences("recording", MODE_PRIVATE).edit().remove("transcribe_while_recording").apply()
    }
}
