package io.codemyriad.cassini.qualification

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.media.MediaCodec
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Off-main-thread hardware telemetry sampler for D-1126 physical Android handset qualification.
 *
 * Samples JVM heap, native heap (`Debug.getNativeHeapAllocatedSize()`), `/proc/self/status` `VmRSS`/`VmHWM`,
 * `ActivityManager.MemoryInfo` available RAM, `ComponentCallbacks2` trim/low-memory signals, process CPU time
 * (`Process.getElapsedCpuTime()` + isolated `:whistle` child-process CPU), `PowerManager.currentThermalStatus`,
 * and `BatteryManager` `%` / `CHARGE_COUNTER` / `ENERGY_COUNTER` / `CURRENT_NOW` every 500 ms.
 */
@Suppress("TooManyFunctions") // Focused telemetry collector with one helper per OS subsystem.
internal class HandsetTelemetryMonitor private constructor(
    private val app: Application,
    private val isolatedWorkerCpuUnavailable: Boolean,
) {
    data class Snapshot(
        val manufacturer: String,
        val model: String,
        val device: String,
        val hardware: String,
        val release: String,
        val sdkInt: Int,
        val totalRamMiB: Double,
        val isLowRamDevice: Boolean,
        val opusEncoderName: String,
        val opusDecoderName: String,
        val wallSeconds: Double,
        val appCpuSeconds: Double,
        val whistleWorkerCpuSeconds: Double?,
        val totalCpuSeconds: Double?,
        val cpuScope: String,
        val baselineRssMiB: Double,
        val peakRssMiB: Double,
        val vmHwmMiB: Double,
        val peakJvmHeapMiB: Double,
        val peakNativeHeapMiB: Double,
        val minAvailRamMiB: Double,
        val trimMemoryPressureEvents: Int,
        val uiHiddenEvents: Int,
        val sampledLowMemoryTicks: Int,
        val maxTrimLevel: Int,
        val peakThermalStatus: String,
        val peakThermalCode: Int,
        val thermalTimeline: List<String>,
        val batteryStartPercent: Int,
        val batteryEndPercent: Int,
        val pluggedSource: String,
        val chargeStartUah: Long?,
        val chargeEndUah: Long?,
        val energyStartNwh: Long?,
        val energyEndNwh: Long?,
        val minCurrentNowUa: Long?,
        val maxCurrentNowUa: Long?,
        val avgCurrentNowUa: Double?,
        val sampleCount: Int,
    ) {
        fun toJson(): JSONObject {
            val chargeDelta = if (chargeStartUah != null && chargeEndUah != null) {
                chargeEndUah - chargeStartUah
            } else {
                null
            }
            return JSONObject()
                .put("manufacturer", manufacturer)
                .put("model", model)
                .put("device", device)
                .put("hardware", hardware)
                .put("release", release)
                .put("sdkInt", sdkInt)
                .put("totalRamMiB", round2(totalRamMiB))
                .put("isLowRamDevice", isLowRamDevice)
                .put("opusEncoderName", opusEncoderName)
                .put("opusDecoderName", opusDecoderName)
                .put("wallSeconds", round3(wallSeconds))
                .put("appCpuSeconds", round3(appCpuSeconds))
                .put("whistleWorkerCpuSeconds", whistleWorkerCpuSeconds?.let(::round3) ?: JSONObject.NULL)
                .put("totalCpuSeconds", totalCpuSeconds?.let(::round3) ?: JSONObject.NULL)
                .put("cpuScope", cpuScope)
                .put("baselineRssMiB", round2(baselineRssMiB))
                .put("peakRssMiB", round2(peakRssMiB))
                .put("vmHwmMiB", round2(vmHwmMiB))
                .put("peakJvmHeapMiB", round2(peakJvmHeapMiB))
                .put("peakNativeHeapMiB", round2(peakNativeHeapMiB))
                .put("minAvailRamMiB", round2(minAvailRamMiB))
                .put("lowMemoryEvents", trimMemoryPressureEvents)
                .put("trimMemoryPressureEvents", trimMemoryPressureEvents)
                .put("uiHiddenEvents", uiHiddenEvents)
                .put("sampledLowMemoryTicks", sampledLowMemoryTicks)
                .put("maxTrimLevel", maxTrimLevel)
                .put("peakThermalStatus", peakThermalStatus)
                .put("peakThermalCode", peakThermalCode)
                .put("thermalTimeline", JSONArray(thermalTimeline))
                .put("batteryStartPercent", batteryStartPercent)
                .put("batteryEndPercent", batteryEndPercent)
                .put("pluggedSource", pluggedSource)
                .putOpt("chargeStartUah", chargeStartUah)
                .putOpt("chargeEndUah", chargeEndUah)
                .putOpt("chargeDeltaUah", chargeDelta)
                .putOpt("energyStartNwh", energyStartNwh)
                .putOpt("energyEndNwh", energyEndNwh)
                .putOpt("minCurrentNowUa", minCurrentNowUa)
                .putOpt("maxCurrentNowUa", maxCurrentNowUa)
                .putOpt("avgCurrentNowUa", avgCurrentNowUa?.let(::round2))
                .put("sampleCount", sampleCount)
        }
    }

    private val lock = Any()
    private val startedWallNs = SystemClock.elapsedRealtimeNanos()
    private val startedCpuMs = Process.getElapsedCpuTime()
    private val whistleCpuMs = AtomicLong(0L)
    private val whistleCpuRecorded = AtomicBoolean(false)
    private val startBattery = readBattery(app)

    private var baselineRssKiB = 0L
    private var peakRssKiB = 0L
    private var vmHwmKiB = 0L
    private var peakJvmBytes = 0L
    private var peakNativeBytes = 0L
    private var minAvailBytes = Long.MAX_VALUE
    private var trimPressureEvents = 0
    private var uiHiddenEvents = 0
    private var sampledLowMemTicks = 0
    private var maxTrim = 0
    private var peakThermal = -1
    private val thermalSamples = ArrayList<String>()
    private var currentSamples = 0L
    private var currentSumUa = 0.0
    private var minCurrentUa: Long? = null
    private var maxCurrentUa: Long? = null
    private var ticks = 0
    private var closed = false

    private val callbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() {
            synchronized(lock) { trimPressureEvents++ }
        }

        override fun onTrimMemory(level: Int) {
            synchronized(lock) {
                if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                    uiHiddenEvents++
                    return
                }
                if (level > maxTrim) maxTrim = level
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                    trimPressureEvents++
                }
            }
        }
    }

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "cassini-qual-telemetry").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    init {
        app.registerComponentCallbacks(callbacks)
        sampleOnce(isInitial = true)
        executor.scheduleAtFixedRate(
            { sampleOnce(isInitial = false) },
            SAMPLE_PERIOD_MS,
            SAMPLE_PERIOD_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    fun recordWhistleWorkerCpuMs(cpuMs: Long) {
        whistleCpuRecorded.set(true)
        if (cpuMs > 0L) whistleCpuMs.addAndGet(cpuMs)
    }

    fun finish(): Snapshot {
        synchronized(lock) {
            if (!closed) {
                closed = true
                app.unregisterComponentCallbacks(callbacks)
                executor.shutdownNow()
            }
        }
        executor.awaitTermination(2, TimeUnit.SECONDS)
        sampleOnce(isInitial = false, forceThermal = true)

        val endWallNs = SystemClock.elapsedRealtimeNanos()
        val endCpuMs = Process.getElapsedCpuTime()
        val endBattery = readBattery(app)
        val activityMgr = app.getSystemService(ActivityManager::class.java)
        val memInfo = ActivityManager.MemoryInfo().also { activityMgr?.getMemoryInfo(it) }
        val appCpuSec = (endCpuMs - startedCpuMs).coerceAtLeast(0L) / 1000.0
        val workerCpuSec: Double? = if (isolatedWorkerCpuUnavailable && !whistleCpuRecorded.get()) {
            null
        } else {
            whistleCpuMs.get().coerceAtLeast(0L) / 1000.0
        }
        return synchronized(lock) {
            buildSnapshot(
                memInfo = memInfo,
                lowRam = activityMgr?.isLowRamDevice == true,
                wallSec = (endWallNs - startedWallNs).coerceAtLeast(0L) / 1_000_000_000.0,
                appCpuSec = appCpuSec,
                workerCpuSec = workerCpuSec,
                endBattery = endBattery,
            )
        }
    }

    @Suppress("LongParameterList")
    private fun buildSnapshot(
        memInfo: ActivityManager.MemoryInfo,
        lowRam: Boolean,
        wallSec: Double,
        appCpuSec: Double,
        workerCpuSec: Double?,
        endBattery: BatterySample,
    ): Snapshot = Snapshot(
        manufacturer = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
        device = Build.DEVICE.orEmpty(),
        hardware = Build.HARDWARE.orEmpty(),
        release = Build.VERSION.RELEASE.orEmpty(),
        sdkInt = Build.VERSION.SDK_INT,
        totalRamMiB = memInfo.totalMem / MIB,
        isLowRamDevice = lowRam,
        opusEncoderName = CODEC_NAMES.first,
        opusDecoderName = CODEC_NAMES.second,
        wallSeconds = wallSec,
        appCpuSeconds = appCpuSec,
        whistleWorkerCpuSeconds = workerCpuSec,
        totalCpuSeconds = workerCpuSec?.let { appCpuSec + it },
        cpuScope = if (workerCpuSec == null) "app_process_only_isolated_worker_unavailable" else "app_and_worker",
        baselineRssMiB = baselineRssKiB / 1024.0,
        peakRssMiB = peakRssKiB / 1024.0,
        vmHwmMiB = vmHwmKiB / 1024.0,
        peakJvmHeapMiB = peakJvmBytes / MIB,
        peakNativeHeapMiB = peakNativeBytes / MIB,
        minAvailRamMiB = if (minAvailBytes == Long.MAX_VALUE) 0.0 else minAvailBytes / MIB,
        trimMemoryPressureEvents = trimPressureEvents,
        uiHiddenEvents = uiHiddenEvents,
        sampledLowMemoryTicks = sampledLowMemTicks,
        maxTrimLevel = maxTrim,
        peakThermalStatus = thermalName(peakThermal),
        peakThermalCode = peakThermal,
        thermalTimeline = thermalSamples.toList(),
        batteryStartPercent = startBattery.percent,
        batteryEndPercent = endBattery.percent,
        pluggedSource = endBattery.plugged,
        chargeStartUah = startBattery.chargeUah,
        chargeEndUah = endBattery.chargeUah,
        energyStartNwh = startBattery.energyNwh,
        energyEndNwh = endBattery.energyNwh,
        minCurrentNowUa = minCurrentUa,
        maxCurrentNowUa = maxCurrentUa,
        avgCurrentNowUa = if (currentSamples > 0L) currentSumUa / currentSamples else null,
        sampleCount = ticks,
    )

    private fun sampleOnce(isInitial: Boolean, forceThermal: Boolean = false) {
        val rt = Runtime.getRuntime()
        val jvmUsed = (rt.totalMemory() - rt.freeMemory()).coerceAtLeast(0L)
        val nativeUsed = Debug.getNativeHeapAllocatedSize().coerceAtLeast(0L)
        val (rssKiB, hwmKiB) = readProcStatusKiB()
        val activityMgr = app.getSystemService(ActivityManager::class.java)
        val memInfo = activityMgr?.let { ActivityManager.MemoryInfo().also(it::getMemoryInfo) }
        val thermalCode = readThermalStatus(app)
        val currentUa = readCurrentNowUa(app)

        synchronized(lock) {
            recordMemorySample(isInitial, rssKiB, hwmKiB, jvmUsed, nativeUsed, memInfo)
            recordThermalAndCurrent(isInitial, forceThermal, thermalCode, currentUa)
            ticks++
        }
    }

    @Suppress("LongParameterList")
    private fun recordMemorySample(
        isInitial: Boolean,
        rssKiB: Long,
        hwmKiB: Long,
        jvmUsed: Long,
        nativeUsed: Long,
        memInfo: ActivityManager.MemoryInfo?,
    ) {
        if (isInitial) baselineRssKiB = rssKiB
        peakRssKiB = maxOf(peakRssKiB, rssKiB)
        vmHwmKiB = maxOf(vmHwmKiB, hwmKiB)
        peakJvmBytes = maxOf(peakJvmBytes, jvmUsed)
        peakNativeBytes = maxOf(peakNativeBytes, nativeUsed)
        if (memInfo != null) {
            minAvailBytes = minOf(minAvailBytes, memInfo.availMem)
            if (memInfo.lowMemory) sampledLowMemTicks++
        }
    }

    private fun recordThermalAndCurrent(
        isInitial: Boolean,
        forceThermal: Boolean,
        thermalCode: Int,
        currentUa: Long?,
    ) {
        peakThermal = maxOf(peakThermal, thermalCode)
        if (isInitial || forceThermal || ticks % THERMAL_TIMELINE_STRIDE == 0) {
            thermalSamples.add(thermalName(thermalCode))
        }
        if (currentUa != null) {
            currentSamples++
            currentSumUa += currentUa.toDouble()
            minCurrentUa = minCurrentUa?.let { minOf(it, currentUa) } ?: currentUa
            maxCurrentUa = maxCurrentUa?.let { maxOf(it, currentUa) } ?: currentUa
        }
    }

    private data class BatterySample(
        val percent: Int,
        val plugged: String,
        val chargeUah: Long?,
        val energyNwh: Long?,
    )

    companion object {
        private const val SAMPLE_PERIOD_MS = 500L
        private const val THERMAL_TIMELINE_STRIDE = 60 // 60 * 500 ms = 30 s
        private const val MIB = 1024.0 * 1024.0

        private val CODEC_NAMES: Pair<String, String> by lazy { probeOpusCodecNames() }

        fun start(
            context: Context,
            isolatedWorkerCpuUnavailable: Boolean = false,
        ): HandsetTelemetryMonitor =
            HandsetTelemetryMonitor(context.applicationContext as Application, isolatedWorkerCpuUnavailable)

        private fun probeOpusCodecNames(): Pair<String, String> {
            val enc = runCatching {
                val c = MediaCodec.createEncoderByType("audio/opus")
                try {
                    c.name
                } finally {
                    c.release()
                }
            }.getOrElse { "unavailable" }
            val dec = runCatching {
                val c = MediaCodec.createDecoderByType("audio/opus")
                try {
                    c.name
                } finally {
                    c.release()
                }
            }.getOrElse { "unavailable" }
            return enc to dec
        }

        private fun readProcStatusKiB(): Pair<Long, Long> = runCatching {
            var rss = 0L
            var hwm = 0L
            File("/proc/self/status").forEachLine { line ->
                if (line.startsWith("VmRSS:")) {
                    rss = line.substringAfter(':').trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: 0L
                } else if (line.startsWith("VmHWM:")) {
                    hwm = line.substringAfter(':').trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: 0L
                }
            }
            rss to hwm
        }.getOrDefault(0L to 0L)

        private fun readThermalStatus(context: Context): Int {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
            return runCatching {
                context.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: -1
            }.getOrDefault(-1)
        }

        fun thermalName(code: Int): String = when (code) {
            -1 -> "unsupported_api_${Build.VERSION.SDK_INT}"
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> "status_$code"
        }

        private fun readBattery(context: Context): BatterySample {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else -1
            val pluggedInt = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val plugged = when {
                pluggedInt and BatteryManager.BATTERY_PLUGGED_USB != 0 -> "USB"
                pluggedInt and BatteryManager.BATTERY_PLUGGED_AC != 0 -> "AC"
                pluggedInt and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> "WIRELESS"
                else -> "UNPLUGGED"
            }
            val bm = context.getSystemService(BatteryManager::class.java)
            val charge = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                ?.takeIf { it != Long.MIN_VALUE && it > 0L }
            val energy = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
                ?.takeIf { it != Long.MIN_VALUE && it > 0L }
            return BatterySample(pct, plugged, charge, energy)
        }

        private fun readCurrentNowUa(context: Context): Long? {
            val bm = context.getSystemService(BatteryManager::class.java) ?: return null
            val raw = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            return raw.takeIf { it != Long.MIN_VALUE && it != 0L }
        }

        private fun round2(v: Double): Double = String.format(Locale.US, "%.2f", v).toDouble()
        private fun round3(v: Double): Double = String.format(Locale.US, "%.3f", v).toDouble()
    }
}
