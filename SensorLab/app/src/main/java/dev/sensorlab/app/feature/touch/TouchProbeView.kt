package dev.sensorlab.app.feature.touch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

data class ProbeStats(
    val toolType: String = "—",
    val pointers: Int = 0,
    val maxPointers: Int = 0,
    val pressure: Float = 0f,
    val tiltDeg: Float = 0f,
    val orientationDeg: Float = 0f,
    val hoverDistance: Float = Float.NaN,
    val hovering: Boolean = false,
    val buttonPressed: Boolean = false,
    val touchMajorMm: Float = 0f,
    val touchMinorMm: Float = 0f,
    val reportRateHz: Float = 0f,
)

/**
 * Raw MotionEvent probe. A classic View is used (instead of Compose pointer input) because it
 * exposes everything the digitizer reports: tilt, orientation, hover distance, stylus buttons,
 * touch ellipse size and batched historical samples.
 */
class TouchProbeView(context: Context) : View(context) {

    enum class Mode { MultiTouch, Draw }

    var mode: Mode = Mode.MultiTouch
        set(value) {
            field = value; invalidate()
        }
    var inkColor: Int = 0xFF5EEAD4.toInt()
    var fingerColor: Int = 0xFFFFB547.toInt()
    var gridColor: Int = 0x22FFFFFF
    var textColor: Int = 0xFFE3E7EB.toInt()
    var onStats: ((ProbeStats) -> Unit)? = null

    private class Pointer(var x: Float, var y: Float, var pressure: Float, var major: Float, var tool: Int)

    private val pointers = HashMap<Int, Pointer>()
    private val lastPoint = HashMap<Int, FloatArray>()
    private var hoverX = Float.NaN
    private var hoverY = Float.NaN
    private var hoverTilt = 0f
    private var hoverOrientation = 0f
    private var maxPointers = 0
    private var sampleCount = 0
    private var rateWindowStart = 0L
    private var rateHz = 0f

    private var bitmap: Bitmap? = null
    private var bitmapCanvas: Canvas? = null
    private val density = resources.displayMetrics.density
    private val mmPerPx = 25.4f / resources.displayMetrics.xdpi

    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val erasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * resources.displayMetrics.scaledDensity }

    fun clear() {
        bitmap?.eraseColor(0)
        maxPointers = 0
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val old = bitmap
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (old != null) c.drawBitmap(old, 0f, 0f, null)
        bitmap = bmp
        bitmapCanvas = c
    }

    private fun toolName(tool: Int) = when (tool) {
        MotionEvent.TOOL_TYPE_FINGER -> "Finger"
        MotionEvent.TOOL_TYPE_STYLUS -> "S Pen"
        MotionEvent.TOOL_TYPE_ERASER -> "Eraser"
        MotionEvent.TOOL_TYPE_MOUSE -> "Mouse"
        else -> "Unknown"
    }

    private fun countSamples(e: MotionEvent) {
        val now = System.nanoTime()
        if (rateWindowStart == 0L) rateWindowStart = now
        sampleCount += e.historySize + 1
        val dt = now - rateWindowStart
        if (dt >= 500_000_000L) {
            rateHz = sampleCount * 1e9f / dt
            sampleCount = 0
            rateWindowStart = now
        }
    }

    private fun publish(e: MotionEvent, index: Int, hovering: Boolean) {
        val tool = e.getToolType(index)
        onStats?.invoke(
            ProbeStats(
                toolType = toolName(tool),
                pointers = if (hovering) 0 else pointers.size,
                maxPointers = maxPointers,
                pressure = e.getPressure(index),
                tiltDeg = Math.toDegrees(e.getAxisValue(MotionEvent.AXIS_TILT, index).toDouble()).toFloat(),
                orientationDeg = Math.toDegrees(e.getOrientation(index).toDouble()).toFloat(),
                hoverDistance = e.getAxisValue(MotionEvent.AXIS_DISTANCE, index),
                hovering = hovering,
                buttonPressed = (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0,
                touchMajorMm = e.getTouchMajor(index) * mmPerPx,
                touchMinorMm = e.getTouchMinor(index) * mmPerPx,
                reportRateHz = rateHz,
            ),
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // When hosted inside Compose, stylus hover events can arrive through the touch path.
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT ->
                return onHoverEvent(e)
        }
        countSamples(e)
        parent?.requestDisallowInterceptTouchEvent(true)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                pointers[id] = Pointer(e.getX(i), e.getY(i), e.getPressure(i), e.getTouchMajor(i), e.getToolType(i))
                lastPoint[id] = floatArrayOf(e.getX(i), e.getY(i), e.getPressure(i))
                maxPointers = max(maxPointers, pointers.size)
                hoverX = Float.NaN
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    if (mode == Mode.Draw) {
                        for (h in 0 until e.historySize) {
                            drawSegment(
                                e, i, id,
                                e.getHistoricalX(i, h), e.getHistoricalY(i, h), e.getHistoricalPressure(i, h),
                                e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i, h),
                            )
                        }
                        drawSegment(e, i, id, e.getX(i), e.getY(i), e.getPressure(i), e.getAxisValue(MotionEvent.AXIS_TILT, i))
                    }
                    pointers[id]?.apply {
                        x = e.getX(i); y = e.getY(i); pressure = e.getPressure(i); major = e.getTouchMajor(i); tool = e.getToolType(i)
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                pointers.remove(id)
                lastPoint.remove(id)
            }

            MotionEvent.ACTION_CANCEL -> {
                pointers.clear(); lastPoint.clear()
            }
        }
        val idx = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP || e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) e.actionIndex else 0
        publish(e, idx, hovering = false)
        invalidate()
        return true
    }

    private fun drawSegment(e: MotionEvent, index: Int, id: Int, x: Float, y: Float, pressure: Float, tilt: Float) {
        val canvas = bitmapCanvas ?: return
        val last = lastPoint[id]
        val tool = e.getToolType(index)
        val erasing = tool == MotionEvent.TOOL_TYPE_ERASER || (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        if (last != null) {
            if (erasing) {
                erasePaint.strokeWidth = 28f * density
                canvas.drawLine(last[0], last[1], x, y, erasePaint)
            } else {
                val isStylus = tool == MotionEvent.TOOL_TYPE_STYLUS
                val p = (pressure + last[2]) / 2f
                // Pressure sets the width; tilting the pen broadens the stroke like a pencil side.
                val tiltFactor = 1f + 1.2f * (tilt / (Math.PI.toFloat() / 2f)).coerceIn(0f, 1f)
                val base = if (isStylus) 1.0f else 3f
                inkPaint.strokeWidth = (base + 9f * p) * tiltFactor * density
                inkPaint.color = if (isStylus) inkColor else fingerColor
                inkPaint.alpha = if (isStylus) (140 + 115 * p).toInt().coerceIn(0, 255) else 200
                canvas.drawLine(last[0], last[1], x, y, inkPaint)
            }
        }
        lastPoint[id] = floatArrayOf(x, y, pressure)
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        countSamples(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                hoverX = e.x; hoverY = e.y
                hoverTilt = e.getAxisValue(MotionEvent.AXIS_TILT)
                hoverOrientation = e.orientation
                publish(e, 0, hovering = true)
            }

            MotionEvent.ACTION_HOVER_EXIT -> {
                hoverX = Float.NaN
                publish(e, 0, hovering = false)
            }
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Background grid every 10 mm
        linePaint.color = gridColor
        val step = 10f / mmPerPx
        var gx = 0f
        while (gx < width) {
            canvas.drawLine(gx, 0f, gx, height.toFloat(), linePaint); gx += step
        }
        var gy = 0f
        while (gy < height) {
            canvas.drawLine(0f, gy, width.toFloat(), gy, linePaint); gy += step
        }

        if (mode == Mode.Draw) {
            bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        } else {
            for ((id, p) in pointers) {
                val color = if (p.tool == MotionEvent.TOOL_TYPE_STYLUS) inkColor else fingerColor
                linePaint.color = (color and 0x00FFFFFF) or 0x66000000
                canvas.drawLine(0f, p.y, width.toFloat(), p.y, linePaint)
                canvas.drawLine(p.x, 0f, p.x, height.toFloat(), linePaint)
                val r = max(p.major / 2f, 26f * density)
                fillPaint.color = (color and 0x00FFFFFF) or 0x33000000
                fillPaint.style = Paint.Style.FILL
                canvas.drawCircle(p.x, p.y, r, fillPaint)
                fillPaint.color = color
                fillPaint.style = Paint.Style.STROKE
                fillPaint.strokeWidth = 2f * density
                canvas.drawCircle(p.x, p.y, r, fillPaint)
                textPaint.color = textColor
                canvas.drawText("#$id  ${"%.2f".format(p.pressure)}", p.x + r + 6f * density, p.y - r, textPaint)
            }
        }

        // Stylus hover cursor with a tilt/orientation indicator
        if (!hoverX.isNaN()) {
            fillPaint.style = Paint.Style.STROKE
            fillPaint.strokeWidth = 1.5f * density
            fillPaint.color = inkColor
            canvas.drawCircle(hoverX, hoverY, 10f * density, fillPaint)
            val len = 40f * density * sin(hoverTilt)
            val dx = sin(hoverOrientation) * len
            val dy = -cos(hoverOrientation) * len
            canvas.drawLine(hoverX, hoverY, hoverX + dx, hoverY + dy, fillPaint)
        }
    }
}
