package com.brekwol.daybreak.camera

import android.hardware.camera2.CameraMetadata
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** White balance presets, mapped to Camera2 AWB modes. */
enum class WhiteBalance(val label: String, val awbMode: Int) {
    Auto("Auto", CameraMetadata.CONTROL_AWB_MODE_AUTO),
    Daylight("Daylight", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
    Cloudy("Cloudy", CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    Shade("Shade", CameraMetadata.CONTROL_AWB_MODE_SHADE),
    Tungsten("Tungsten", CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
    Fluorescent("Fluorescent", CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT),
}

/** How the lens focuses. */
enum class FocusMode(val label: String) {
    /** The camera picks what to focus on, anywhere in the frame. */
    Multi("Multi"),
    /** Keeps focusing on the middle of the frame. */
    Center("Center"),
    /** A fixed distance set by hand. */
    Manual("Manual"),
}

/** Manual camera settings for Pro mode. A null value means automatic. */
data class ProSettings(
    val iso: Int? = null,
    val shutterNs: Long? = null,
    /** Lens focus distance in diopters (1 / metres); 0 is infinity. Set means manual focus. */
    val focusDiopters: Float? = null,
    val whiteBalance: WhiteBalance = WhiteBalance.Auto,
    /** Exposure compensation in device steps; used while exposure is automatic. */
    val evIndex: Int = 0,
    /** Autofocus on the middle of the frame only (ignored while focus is manual). */
    val focusCenter: Boolean = false,
) {
    val focusMode: FocusMode
        get() = when {
            focusDiopters != null -> FocusMode.Manual
            focusCenter -> FocusMode.Center
            else -> FocusMode.Multi
        }

    /** ISO or shutter is set by hand, so auto exposure is off. */
    val manualExposure: Boolean
        get() = iso != null || shutterNs != null

    /** Nothing is set by hand. */
    val allAuto: Boolean
        get() = iso == null && shutterNs == null && focusDiopters == null && !focusCenter &&
            whiteBalance == WhiteBalance.Auto && evIndex == 0

    /**
     * Manual ISO and shutter changed to bring the exposure [meterEv] stops back to
     * normal. A value set by hand stays when the other one can make up the
     * difference; with both set, the shutter moves first (down to 1/4 s, still
     * sharp handheld) and ISO makes up the rest. Values snap to the camera's stops.
     */
    fun balanced(meterEv: Float, caps: CameraCapabilities, live: LiveExposure): ProSettings {
        if (!manualExposure || kotlin.math.abs(meterEv) < 0.2f) return this
        val isoStops = caps.isoStops
        val shutterStops = caps.shutterStops
        if (isoStops.isEmpty() || shutterStops.isEmpty()) return this
        val currentIso = (iso ?: live.iso.takeIf { it > 0 } ?: return this).toDouble()
        val currentShutter = (shutterNs ?: live.shutterNs.takeIf { it > 0 } ?: return this).toDouble()
        val factor = Math.pow(2.0, -meterEv.toDouble())
        val shortest = shutterStops.first().toDouble()
        val longest = minOf(shutterStops.last(), HANDHELD_LIMIT_NS).toDouble().coerceAtLeast(shortest)

        val newShutter: Double
        val newIso: Double
        if (iso != null && shutterNs == null) {
            // ISO was chosen: change the shutter, and ISO only if the shutter runs out.
            newShutter = (currentShutter * factor).coerceIn(shortest, longest)
            newIso = (currentIso * factor * currentShutter / newShutter)
                .coerceIn(isoStops.first().toDouble(), isoStops.last().toDouble())
        } else if (shutterNs != null && iso == null) {
            // Shutter was chosen: change ISO, and the shutter only if ISO runs out.
            newIso = (currentIso * factor).coerceIn(isoStops.first().toDouble(), isoStops.last().toDouble())
            newShutter = (currentShutter * factor * currentIso / newIso).coerceIn(shortest, longest)
        } else {
            newShutter = (currentShutter * factor).coerceIn(shortest, longest)
            newIso = (currentIso * factor * currentShutter / newShutter)
                .coerceIn(isoStops.first().toDouble(), isoStops.last().toDouble())
        }
        return copy(
            iso = isoStops.minBy { kotlin.math.abs(kotlin.math.ln(it / newIso)) },
            shutterNs = shutterStops.minBy { kotlin.math.abs(kotlin.math.ln(it / newShutter)) },
        )
    }

    companion object {
        /** Longest shutter speed balancing picks, 1/4 s: slower blurs handheld photos. */
        const val HANDHELD_LIMIT_NS = 250_000_000L

        /** Saves Pro values in the screen's saved state. */
        val Saver: Saver<ProSettings, Any> = listSaver(
            save = { listOf(it.iso, it.shutterNs, it.focusDiopters, it.whiteBalance.name, it.evIndex, it.focusCenter) },
            restore = { values ->
                ProSettings(
                    iso = values[0] as Int?,
                    shutterNs = values[1] as Long?,
                    focusDiopters = values[2] as Float?,
                    whiteBalance = WhiteBalance.entries.firstOrNull { it.name == values[3] } ?: WhiteBalance.Auto,
                    evIndex = values[4] as Int? ?: 0,
                    focusCenter = values.getOrNull(5) as Boolean? ?: false,
                )
            },
        )
    }
}

/** What the current camera can do. */
data class CameraCapabilities(
    val manualSensor: Boolean = false,
    val isoRange: IntRange? = null,
    val shutterRangeNs: LongRange? = null,
    /** Closest focus in diopters; 0 means the lens has fixed focus. */
    val minFocusDiopters: Float = 0f,
    val whiteBalances: List<WhiteBalance> = listOf(WhiteBalance.Auto),
    val evRange: IntRange = 0..0,
    /** Exposure value of one compensation step, e.g. 1/3. */
    val evStep: Float = 0f,
    val raw: Boolean = false,
    /** The camera accepts an autofocus area, so focus can stay on the middle of the frame. */
    val afRegions: Boolean = false,
    /** Size of the sensor's active pixel array, the coordinate space of focus areas. */
    val activeArrayWidth: Int = 0,
    val activeArrayHeight: Int = 0,
) {
    val manualFocus: Boolean
        get() = minFocusDiopters > 0f

    val centerFocus: Boolean
        get() = afRegions && activeArrayWidth > 0 && activeArrayHeight > 0

    val exposureCompensation: Boolean
        get() = evRange.first < evRange.last && evStep > 0f

    /** ISO values in 1/3 stops that this sensor supports. */
    val isoStops: List<Int>
        get() = isoRange?.let { range -> ISO_STOPS.filter { it in range } }.orEmpty()

    /** Shutter speeds in 1/3 stops that this sensor supports. */
    val shutterStops: List<Long>
        get() = shutterRangeNs?.let { range -> SHUTTER_STOPS_NS.filter { it in range } }.orEmpty()

    companion object {
        val ISO_STOPS = listOf(
            25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250,
            1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800,
        )

        private val FRACTIONS = listOf(
            8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250,
            200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4,
        )
        private val SECONDS = listOf(0.3, 0.4, 0.5, 0.6, 0.8, 1.0, 1.3, 1.6, 2.0, 2.5, 3.2, 4.0, 5.0, 6.0, 8.0, 10.0, 15.0, 20.0, 30.0)

        val SHUTTER_STOPS_NS: List<Long> =
            FRACTIONS.map { 1_000_000_000L / it } + SECONDS.map { (it * 1_000_000_000L).roundToLong() }
    }
}

/** What the camera is doing right now, read from its capture results. */
data class LiveExposure(
    val iso: Int = 0,
    val shutterNs: Long = 0L,
    val focusDiopters: Float = 0f,
)

object ProFormat {
    fun iso(value: Int): String = value.toString()

    fun shutter(ns: Long): String {
        if (ns <= 0L) return "–"
        val seconds = ns / 1_000_000_000.0
        return if (seconds < 0.29) {
            "1/" + (1.0 / seconds).roundToInt()
        } else {
            val text = if (seconds < 10) String.format(Locale.US, "%.1f", seconds) else seconds.roundToInt().toString()
            text.removeSuffix(".0") + "s"
        }
    }

    fun focus(diopters: Float): String = when {
        diopters <= 0.05f -> "∞"
        1f / diopters < 1f -> "${(100f / diopters).roundToInt()} cm"
        else -> String.format(Locale.US, "%.1f m", 1f / diopters)
    }

    fun ev(index: Int, step: Float): String {
        val value = index * step
        if (abs(value) < 0.05f) return "0"
        return (if (value > 0) "+" else "−") + String.format(Locale.US, "%.1f", abs(value))
    }

    /** Index of the stop closest to [value]. */
    fun <T : Comparable<T>> nearestIndex(stops: List<T>, value: T, distance: (T, T) -> Double): Int =
        stops.indices.minByOrNull { distance(stops[it], value) } ?: 0
}
