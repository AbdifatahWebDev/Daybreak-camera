package com.brekwol.daybreak.edit

import com.brekwol.daybreak.engine.CurvePoint
import kotlin.math.sqrt

/**
 * The engine's tone curve (Fritsch-Carlson monotone cubic, flat beyond the end
 * points), in Kotlin, so the curve editor draws exactly what the photo gets.
 * Mirrors `Spline` in native/src/edit/edit.cpp.
 */
class CurveSpline(points: List<CurvePoint>) {
    private val p: List<CurvePoint> = clean(points)
    private val m = FloatArray(p.size)

    init {
        val n = p.size
        if (n >= 2) {
            val d = FloatArray(n - 1) { i -> (p[i + 1].y - p[i].y) / (p[i + 1].x - p[i].x) }
            m[0] = d[0]
            m[n - 1] = d[n - 2]
            for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else 0.5f * (d[i - 1] + d[i])
            for (i in 0 until n - 1) {
                if (d[i] == 0f) {
                    m[i] = 0f
                    m[i + 1] = 0f
                    continue
                }
                val a = m[i] / d[i]
                val b = m[i + 1] / d[i]
                val h = a * a + b * b
                if (h > 9f) {
                    val t = 3f / sqrt(h)
                    m[i] = t * a * d[i]
                    m[i + 1] = t * b * d[i]
                }
            }
        }
    }

    operator fun invoke(x: Float): Float {
        val n = p.size
        if (n == 0) return x
        if (n == 1) return p[0].y
        if (x <= p.first().x) return p.first().y
        if (x >= p.last().x) return p.last().y
        var i = 0
        while (i + 2 < n && x > p[i + 1].x) i++
        val h = p[i + 1].x - p[i].x
        val t = (x - p[i].x) / h
        val t2 = t * t
        val t3 = t2 * t
        val y = (2 * t3 - 3 * t2 + 1) * p[i].y + (t3 - 2 * t2 + t) * h * m[i] +
            (-2 * t3 + 3 * t2) * p[i + 1].y + (t3 - t2) * h * m[i + 1]
        return y.coerceIn(0f, 1f)
    }

    companion object {
        /** Finite points clamped to 0..1, sorted by x, one per x (the later one wins). */
        fun clean(points: List<CurvePoint>): List<CurvePoint> {
            val sorted = points
                .filter { it.x.isFinite() && it.y.isFinite() }
                .map { CurvePoint(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
                .sortedBy { it.x }
            val unique = ArrayList<CurvePoint>(sorted.size)
            for (q in sorted) {
                if (unique.isNotEmpty() && q.x - unique.last().x < 1e-4f) {
                    unique[unique.lastIndex] = q
                } else {
                    unique += q
                }
            }
            return unique
        }

        /** The straight line the curve editor starts from. */
        val Identity: List<CurvePoint> = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
    }
}
