package dev.sensorlab.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sensorlab.app.core.SeriesBuffer
import dev.sensorlab.app.core.fmt
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Scrolling multi-series line chart. Newest samples are on the right.
 * When there are more samples than pixels it draws a min/max envelope per pixel column.
 */
@Composable
fun LiveChart(
    buffer: SeriesBuffer,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    chartHeight: androidx.compose.ui.unit.Dp = 180.dp,
    fixedMin: Float? = null,
    fixedMax: Float? = null,
    minSpan: Float = 0f,
    symmetric: Boolean = false,
    visible: List<Boolean>? = null,
    yFormat: (Float) -> String = { v -> if (abs(v) >= 100f) v.fmt(0) else if (abs(v) >= 10f) v.fmt(1) else v.fmt(2) },
    referenceLines: List<Float> = emptyList(),
) {
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val refColor = MaterialTheme.colorScheme.secondary
    Canvas(modifier.fillMaxWidth().height(chartHeight)) {
        @Suppress("UNUSED_VARIABLE")
        val v = buffer.version // read state so the chart redraws on every new sample
        val n = buffer.size
        val w = size.width
        val h = size.height

        // ---- range
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (s in 0 until buffer.seriesCount) {
            if (visible != null && visible.getOrElse(s) { true } == false) continue
            val rb = buffer.series[s]
            for (i in 0 until n) {
                val x = rb[i]
                if (!x.isNaN() && !x.isInfinite()) {
                    if (x < lo) lo = x
                    if (x > hi) hi = x
                }
            }
        }
        if (lo == Float.POSITIVE_INFINITY) {
            lo = -1f; hi = 1f
        }
        if (symmetric) {
            val m = max(abs(lo), abs(hi))
            lo = -m; hi = m
        }
        if (hi - lo < minSpan) {
            val mid = (hi + lo) / 2f
            lo = mid - minSpan / 2f
            hi = mid + minSpan / 2f
        }
        if (hi - lo < 1e-6f) {
            lo -= 0.5f; hi += 0.5f
        }
        val pad = (hi - lo) * 0.08f
        if (fixedMin == null) lo -= pad
        if (fixedMax == null) hi += pad
        if (fixedMin != null) lo = fixedMin
        if (fixedMax != null) hi = fixedMax
        val span = hi - lo
        fun yOf(value: Float): Float = h - ((value - lo) / span) * h

        // ---- grid
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        for (k in 0..4) {
            val y = h * k / 4f
            drawLine(grid, Offset(0f, y), Offset(w, y), 1f, pathEffect = if (k == 0 || k == 4) null else dash)
        }
        if (lo < 0f && hi > 0f) {
            val y0 = yOf(0f)
            drawLine(grid, Offset(0f, y0), Offset(w, y0), 1.5f)
        }
        for (r in referenceLines) {
            if (r in lo..hi) {
                val y = yOf(r)
                drawLine(refColor.copy(alpha = 0.7f), Offset(0f, y), Offset(w, y), 1.5f, pathEffect = dash)
            }
        }

        // ---- series
        if (n >= 2) {
            val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val cap = buffer.capacity
            val dx = w / (cap - 1).coerceAtLeast(1)
            val columns = w.toInt().coerceAtLeast(1)
            val envelope = cap > columns * 2
            for (s in 0 until buffer.seriesCount) {
                if (visible != null && visible.getOrElse(s) { true } == false) continue
                val rb = buffer.series[s]
                val color = colors.getOrElse(s) { colors.last() }
                val path = Path()
                var started = false
                if (!envelope) {
                    for (i in 0 until n) {
                        val value = rb[i]
                        if (value.isNaN()) {
                            started = false; continue
                        }
                        val x = w - (n - 1 - i) * dx
                        val y = yOf(value)
                        if (!started) {
                            path.moveTo(x, y); started = true
                        } else {
                            path.lineTo(x, y)
                        }
                    }
                } else {
                    val perCol = cap.toFloat() / columns
                    val firstCol = ((cap - n) / perCol).toInt()
                    for (c in firstCol until columns) {
                        val start = ((c * perCol).toInt() - (cap - n)).coerceAtLeast(0)
                        val end = (((c + 1) * perCol).toInt() - (cap - n)).coerceAtMost(n)
                        if (end <= start) continue
                        var mn = Float.POSITIVE_INFINITY
                        var mx = Float.NEGATIVE_INFINITY
                        for (i in start until end) {
                            val value = rb[i]
                            if (!value.isNaN()) {
                                mn = min(mn, value); mx = max(mx, value)
                            }
                        }
                        if (mn == Float.POSITIVE_INFINITY) continue
                        val x = c.toFloat()
                        if (!started) {
                            path.moveTo(x, yOf(mx)); started = true
                        } else {
                            path.lineTo(x, yOf(mx))
                        }
                        path.lineTo(x, yOf(mn))
                    }
                }
                drawPath(path, color, style = stroke)
            }
        }

        // ---- labels
        val top = measurer.measure(yFormat(hi), labelStyle)
        val bottom = measurer.measure(yFormat(lo), labelStyle)
        drawText(top, topLeft = Offset(4f, 2f))
        drawText(bottom, topLeft = Offset(4f, h - bottom.size.height - 2f))
    }
}
