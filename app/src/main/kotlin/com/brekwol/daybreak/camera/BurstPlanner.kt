package com.brekwol.daybreak.camera

import com.brekwol.daybreak.engine.BurstFinish
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** The two kinds of merged photo. */
enum class BurstKind { Hdr, Night }

/**
 * A planned burst: how many frames, the exposure of each (manual ISO and
 * shutter, or exposure compensation on cameras without manual exposure, or
 * neither), and how the merged frames are finished.
 */
data class BurstPlan(
    val kind: BurstKind,
    val frames: Int,
    val iso: Int? = null,
    val shutterNs: Long? = null,
    val evIndex: Int? = null,
    val finish: BurstFinish = BurstFinish(),
) {
    /** About how long taking the frames takes, for the progress display. */
    val captureMs: Long
        get() = frames * (max(shutterNs ?: FRAME_NS, FRAME_NS) / 1_000_000L + PER_FRAME_MS)

    private companion object {
        const val FRAME_NS = 33_333_333L
        const val PER_FRAME_MS = 180L
    }
}

/** Chooses the exposure of Night and HDR bursts from what auto exposure found. */
object BurstPlanner {
    /** HDR frames are this many stops darker than auto exposure, to keep bright areas. */
    const val HDR_UNDER = 1.5f
    const val HDR_FRAMES = 4

    /** Night frames are no longer than this each, so each one stays sharp in the hand. */
    const val NIGHT_SHUTTER_NS = 100_000_000L
    const val NIGHT_MAX_ISO = 1600
    const val NIGHT_FRAMES = 6
    const val NIGHT_DARK_FRAMES = 8

    /** Night photos stay a little darker than daylight ones, so they still look like night. */
    const val NIGHT_MOOD = 0.5f
    const val NIGHT_MAX_GAIN = 16f

    /**
     * [auto] is the exposure auto exposure chose (null before the first frame);
     * [meterEv] how many stops the preview is from a normal exposure.
     */
    fun plan(kind: BurstKind, caps: CameraCapabilities, auto: LiveExposure?, meterEv: Float?): BurstPlan {
        val isoRange = caps.isoRange
        val shutterRange = caps.shutterRangeNs
        val manual = caps.manualSensor && isoRange != null && shutterRange != null &&
            auto != null && auto.iso > 0 && auto.shutterNs > 0
        if (manual && isoRange != null && shutterRange != null && auto != null) {
            val exposure = auto.iso.toDouble() * auto.shutterNs
            return when (kind) {
                BurstKind.Hdr -> {
                    val target = exposure / 2.0.pow(HDR_UNDER.toDouble())
                    var shutter = auto.shutterNs.toDouble()
                    var iso = target / shutter
                    if (iso < isoRange.first) {
                        iso = isoRange.first.toDouble()
                        shutter = target / iso
                    }
                    val isoValue = iso.roundToInt().coerceIn(isoRange)
                    val shutterValue = shutter.roundToLong().coerceIn(shutterRange)
                    val gain = (exposure / (isoValue.toDouble() * shutterValue)).toFloat().coerceIn(1f, 8f)
                    BurstPlan(
                        kind = kind,
                        frames = HDR_FRAMES,
                        iso = isoValue,
                        shutterNs = shutterValue,
                        finish = BurstFinish(gain = gain, under = log2(gain), over = 1f),
                    )
                }
                BurstKind.Night -> {
                    // Auto exposure may have run out of range in the dark: the meter says by how much.
                    val short = (-(meterEv ?: 0f)).coerceIn(0f, 6f)
                    val wanted = exposure * 2.0.pow((short - NIGHT_MOOD).toDouble().coerceAtLeast(0.0))
                    val shutter = min(wanted / isoRange.first, NIGHT_SHUTTER_NS.toDouble())
                        .roundToLong().coerceIn(shutterRange)
                    val iso = (wanted / shutter).roundToInt()
                        .coerceIn(isoRange.first, min(isoRange.last, NIGHT_MAX_ISO).coerceAtLeast(isoRange.first))
                    val gain = (wanted / (iso.toDouble() * shutter)).toFloat().coerceIn(1f, NIGHT_MAX_GAIN)
                    BurstPlan(
                        kind = kind,
                        frames = if (gain > 3f) NIGHT_DARK_FRAMES else NIGHT_FRAMES,
                        iso = iso,
                        shutterNs = shutter,
                        finish = BurstFinish(gain = gain, under = 1.5f, over = 1f),
                    )
                }
            }
        }
        // No manual exposure: auto exposure, darker for HDR when the camera allows compensation.
        return when (kind) {
            BurstKind.Hdr -> {
                val step = caps.evStep
                if (caps.exposureCompensation && step > 0f) {
                    val index = (-HDR_UNDER / step).roundToInt().coerceIn(caps.evRange)
                    val stops = -index * step
                    val gain = 2f.pow(stops).coerceIn(1f, 8f)
                    BurstPlan(kind, HDR_FRAMES, evIndex = index, finish = BurstFinish(gain, log2(gain), 1f))
                } else {
                    BurstPlan(kind, HDR_FRAMES, finish = BurstFinish(1f, 1f, 1f))
                }
            }
            BurstKind.Night -> {
                val short = (-(meterEv ?: 0f) - NIGHT_MOOD).coerceIn(0f, 3f)
                BurstPlan(kind, NIGHT_FRAMES, finish = BurstFinish(2f.pow(short), 1.5f, 1f))
            }
        }
    }
}
