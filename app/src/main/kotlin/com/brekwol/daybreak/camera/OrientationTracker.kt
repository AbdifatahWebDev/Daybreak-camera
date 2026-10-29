package com.brekwol.daybreak.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Works out which way up the phone is held, for the rotation saved with each photo.
 *
 * Android's own orientation listener keeps guessing when the phone points at the
 * sky or the floor, and flips at exactly 45°. This tracker keeps the last
 * orientation it was sure of while the phone is steeply tilted, and only switches
 * between portrait and landscape once the phone is clearly turned.
 */
class OrientationTracker(
    context: Context,
    private val onRotation: (Int) -> Unit,
) : SensorEventListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** A [Surface] rotation constant: ROTATION_0, _90, _180 or _270. */
    var rotation: Int = Surface.ROTATION_0
        private set

    /** False on phones without a gravity sensor or accelerometer. */
    val available: Boolean
        get() = sensor != null

    fun start() {
        val sensor = sensor ?: return
        sensors?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        sensors?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val orientation = orientationDegrees(event.values[0], event.values[1], event.values[2]) ?: return
        val next = nextRotation(rotation, orientation)
        if (next != rotation) {
            rotation = next
            onRotation(next)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        /** Beyond this share of gravity along the screen's axis, the phone faces up or down. */
        const val STEEP_TILT = 0.75f

        /** Extra degrees past the 45° boundary before turning to the next orientation. */
        const val HYSTERESIS = 15

        /**
         * The phone's orientation in degrees like [android.view.OrientationEventListener]:
         * 0 upright, 90 with its left side up, 180 upside down, 270 with its right side up.
         * Null while the phone is steeply tilted towards the sky or the floor.
         */
        fun orientationDegrees(x: Float, y: Float, z: Float): Int? {
            val g = sqrt(x * x + y * y + z * z)
            if (g < 1f || abs(z) / g > STEEP_TILT) return null
            val angle = Math.toDegrees(atan2(y.toDouble(), -x.toDouble())).roundToInt()
            return Math.floorMod(90 - angle, 360)
        }

        /** The [Surface] rotation for [orientation], staying on [current] near the boundaries. */
        fun nextRotation(current: Int, orientation: Int): Int {
            val center = centerOf(current)
            if (angularDistance(orientation, center) <= 45 + HYSTERESIS) return current
            return when {
                orientation >= 315 || orientation < 45 -> Surface.ROTATION_0
                orientation < 135 -> Surface.ROTATION_270
                orientation < 225 -> Surface.ROTATION_180
                else -> Surface.ROTATION_90
            }
        }

        private fun centerOf(rotation: Int): Int = when (rotation) {
            Surface.ROTATION_270 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_90 -> 270
            else -> 0
        }

        private fun angularDistance(a: Int, b: Int): Int {
            val d = Math.floorMod(a - b, 360)
            return if (d > 180) 360 - d else d
        }
    }
}
