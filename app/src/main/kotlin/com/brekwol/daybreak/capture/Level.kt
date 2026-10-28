package com.brekwol.daybreak.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The phone's roll in degrees from the gravity sensor: 0 when held upright,
 * 90 when turned to landscape. Null while the phone lies flat or has no sensor.
 */
@Composable
fun rememberRoll(enabled: Boolean): State<Float?> {
    val context = LocalContext.current
    val roll = remember { mutableStateOf<Float?>(null) }
    DisposableEffect(enabled) {
        if (!enabled) {
            roll.value = null
            return@DisposableEffect onDispose { }
        }
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var smoothed: Float? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
                val g = sqrt(x * x + y * y + z * z)
                if (g < 1f || abs(z) / g > FLAT_LIMIT) {
                    smoothed = null
                    roll.value = null
                    return
                }
                val angle = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
                val previous = smoothed
                // Low-pass filter, taking the shortest way round ±180°.
                val next = if (previous == null) {
                    angle
                } else {
                    var delta = angle - previous
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f
                    previous + delta * SMOOTHING
                }
                smoothed = next
                roll.value = next
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensors != null && sensor != null) {
            sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose { sensors?.unregisterListener(listener) }
    }
    return roll
}

/** How far [roll] is from the nearest upright or landscape position, in degrees. */
fun levelError(roll: Float): Float {
    val nearest = (roll / 90f).roundToInt() * 90f
    return roll - nearest
}

/**
 * A horizon line through the middle of the viewfinder that stays level with the
 * world as the phone tilts. It turns [levelColor] within one degree of level.
 */
@Composable
fun LevelOverlay(roll: Float, levelColor: Color, modifier: Modifier = Modifier) {
    val level = abs(levelError(roll)) < 1f
    val color = if (level) levelColor else Color.White.copy(alpha = 0.75f)
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val half = 64.dp.toPx()
        val gap = 10.dp.toPx()
        val stroke = 2.dp.toPx()
        // Turning the phone clockwise gives a negative roll; the horizon turns the same way on screen.
        rotate(degrees = roll, pivot = center) {
            drawLine(color, Offset(center.x - half, center.y), Offset(center.x - gap, center.y), stroke, StrokeCap.Round)
            drawLine(color, Offset(center.x + gap, center.y), Offset(center.x + half, center.y), stroke, StrokeCap.Round)
        }
        if (level) {
            drawCircle(color, radius = 3.dp.toPx(), center = center)
        }
    }
}

private const val FLAT_LIMIT = 0.85f
private const val SMOOTHING = 0.25f
