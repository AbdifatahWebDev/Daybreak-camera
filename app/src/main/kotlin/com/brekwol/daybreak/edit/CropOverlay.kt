package com.brekwol.daybreak.edit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.brekwol.daybreak.designsystem.Daybreak
import com.brekwol.daybreak.engine.CropRect
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Where an image of [aspect] sits when fitted into [width] x [height] with [pad] around it. */
private fun imageRect(width: Float, height: Float, aspect: Float, pad: Float): Rect {
    val availW = (width - 2 * pad).coerceAtLeast(1f)
    val availH = (height - 2 * pad).coerceAtLeast(1f)
    val w = min(availW, availH * aspect)
    val h = w / aspect
    val left = (width - w) / 2f
    val top = (height - h) / 2f
    return Rect(left, top, left + w, top + h)
}

/**
 * The whole straightened frame with the crop drawn over it. Drag a corner or an
 * edge to resize, or inside to move. [ratio] (width / height) locks the shape.
 */
@Composable
fun CropOverlay(
    frame: ImageBitmap?,
    aspect: Float,
    crop: CropRect,
    ratio: Float?,
    onCrop: (CropRect) -> Unit,
    onCropDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Daybreak.colors
    val currentCrop by rememberUpdatedState(crop)
    val currentRatio by rememberUpdatedState(ratio)
    val currentAspect by rememberUpdatedState(aspect)
    val currentOnCrop by rememberUpdatedState(onCrop)
    val currentOnDone by rememberUpdatedState(onCropDone)
    val padDp = 24.dp

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = "Crop frame. Drag the corners or edges to crop, or inside to move it." }
            .pointerInput(Unit) {
                val pad = padDp.toPx()
                val corner = 30.dp.toPx()
                val edge = 22.dp.toPx()
                var handle: CropHandle? = null
                var start = CropRect.Full
                var total = Offset.Zero
                var image = Rect.Zero
                detectDragGestures(
                    onDragStart = { at ->
                        image = imageRect(size.width.toFloat(), size.height.toFloat(), currentAspect, pad)
                        start = currentCrop
                        total = Offset.Zero
                        val l = image.left + start.left * image.width
                        val t = image.top + start.top * image.height
                        val r = image.left + start.right * image.width
                        val b = image.top + start.bottom * image.height
                        val nearL = abs(at.x - l) < corner
                        val nearR = abs(at.x - r) < corner
                        val nearT = abs(at.y - t) < corner
                        val nearB = abs(at.y - b) < corner
                        val withinX = at.x > l - edge && at.x < r + edge
                        val withinY = at.y > t - edge && at.y < b + edge
                        handle = when {
                            nearL && nearT -> CropHandle.TopLeft
                            nearR && nearT -> CropHandle.TopRight
                            nearL && nearB -> CropHandle.BottomLeft
                            nearR && nearB -> CropHandle.BottomRight
                            abs(at.x - l) < edge && withinY -> CropHandle.Left
                            abs(at.x - r) < edge && withinY -> CropHandle.Right
                            abs(at.y - t) < edge && withinX -> CropHandle.Top
                            abs(at.y - b) < edge && withinX -> CropHandle.Bottom
                            at.x in l..r && at.y in t..b -> CropHandle.Move
                            else -> null
                        }
                    },
                    onDragEnd = { if (handle != null) currentOnDone() },
                    onDragCancel = { if (handle != null) currentOnDone() },
                ) { change, amount ->
                    val grabbed = handle ?: return@detectDragGestures
                    change.consume()
                    total += amount
                    currentOnCrop(
                        CropMath.drag(
                            start,
                            grabbed,
                            total.x / image.width.coerceAtLeast(1f),
                            total.y / image.height.coerceAtLeast(1f),
                            currentRatio,
                            currentAspect,
                        ),
                    )
                }
            },
    ) {
        val image = imageRect(size.width, size.height, aspect, padDp.toPx())
        if (frame != null) {
            drawImage(
                image = frame,
                dstOffset = IntOffset(image.left.roundToInt(), image.top.roundToInt()),
                dstSize = IntSize(image.width.roundToInt(), image.height.roundToInt()),
            )
        }
        val l = image.left + crop.left * image.width
        val t = image.top + crop.top * image.height
        val r = image.left + crop.right * image.width
        val b = image.top + crop.bottom * image.height
        // Dim what will be cut away.
        val dim = colors.scrim
        drawRect(dim, Offset(image.left, image.top), Size(image.width, t - image.top))
        drawRect(dim, Offset(image.left, b), Size(image.width, image.bottom - b))
        drawRect(dim, Offset(image.left, t), Size(l - image.left, b - t))
        drawRect(dim, Offset(r, t), Size(image.right - r, b - t))
        // Frame, thirds and corner handles.
        val ink = colors.ink
        drawRect(ink, Offset(l, t), Size(r - l, b - t), style = Stroke(1.5.dp.toPx()))
        val third = ink.copy(alpha = 0.4f)
        for (i in 1..2) {
            val x = l + (r - l) * i / 3f
            val y = t + (b - t) * i / 3f
            drawLine(third, Offset(x, t), Offset(x, b), strokeWidth = 1.dp.toPx())
            drawLine(third, Offset(l, y), Offset(r, y), strokeWidth = 1.dp.toPx())
        }
        val arm = 22.dp.toPx()
        val thick = 4.dp.toPx()
        val o = thick / 2f
        listOf(
            Offset(l - o, t - o) to Offset(1f, 1f),
            Offset(r + o, t - o) to Offset(-1f, 1f),
            Offset(l - o, b + o) to Offset(1f, -1f),
            Offset(r + o, b + o) to Offset(-1f, -1f),
        ).forEach { (corner, d) ->
            drawLine(ink, corner, corner + Offset(arm * d.x, 0f), strokeWidth = thick)
            drawLine(ink, corner, corner + Offset(0f, arm * d.y), strokeWidth = thick)
        }
    }
}
