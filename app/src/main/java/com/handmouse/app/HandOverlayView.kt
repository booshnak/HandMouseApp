package com.handmouse.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import kotlin.math.max

class HandOverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var showCamera: Boolean = false
        set(value) { field = value; invalidate() }
    var showMousePreview: Boolean = true
        set(value) { field = value; invalidate() }
    var showAllFingers: Boolean = true
        set(value) { field = value; invalidate() }
    var rigLinesOnly: Boolean = false
        set(value) { field = value; invalidate() }

    private var landmarks: List<NormalizedLandmark>? = null
    private var imgWidth: Int = 1
    private var imgHeight: Int = 1
    private var isFrontCamera: Boolean = false

    private var virtualCursorX = 0.5f
    private var virtualCursorY = 0.5f
    private val trail = ArrayDeque<PointF>()
    private var leftClickActive = false
    private var rightClickActive = false
    private var scrollActive = false
    private var clutchActive = false

    private val bgPaint = Paint().apply { color = Color.parseColor("#0B0E17") }
    private val rigLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8"); strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE
    }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 30f; isFakeBoldText = true
    }
    private val padBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155"); strokeWidth = 4f; style = Paint.Style.STROKE
    }
    private val padFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#111827"); style = Paint.Style.FILL
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#88FACC15"); strokeWidth = 5f; style = Paint.Style.STROKE
    }

    fun updateFrame(output: MouseFrameOutput, imageWidth: Int, imageHeight: Int, frontCamera: Boolean) {
        this.landmarks = output.rightHandLandmarks
        this.imgWidth = imageWidth
        this.imgHeight = imageHeight
        this.isFrontCamera = frontCamera
        this.leftClickActive = output.leftClick
        this.rightClickActive = output.rightClick
        this.scrollActive = output.scrolling
        this.clutchActive = output.pausedClutch

        if (output.dx != 0 || output.dy != 0) {
            virtualCursorX = (virtualCursorX + output.dx / 900f).coerceIn(0.03f, 0.97f)
            virtualCursorY = (virtualCursorY + output.dy / 900f).coerceIn(0.03f, 0.97f)
            trail.addLast(PointF(virtualCursorX, virtualCursorY))
            if (trail.size > 18) trail.removeFirst()
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!showCamera || rigLinesOnly) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        }
        if (showMousePreview && !rigLinesOnly) drawMousePreview(canvas)

        val lm = landmarks ?: return
        val scale = max(width.toFloat() / imgWidth, height.toFloat() / imgHeight)
        val offsetX = (width - imgWidth * scale) / 2f
        val offsetY = (height - imgHeight * scale) / 2f
        fun mapX(nx: Float): Float {
            val rawX = offsetX + nx * imgWidth * scale
            return if (isFrontCamera) width - rawX else rawX
        }
        fun mapY(ny: Float): Float = offsetY + ny * imgHeight * scale

        if (rigLinesOnly || showAllFingers) {
            rigLinePaint.color = when {
                clutchActive -> Color.parseColor("#EF4444")
                leftClickActive -> Color.parseColor("#22C55E")
                rightClickActive -> Color.parseColor("#A855F7")
                scrollActive -> Color.parseColor("#F59E0B")
                else -> Color.parseColor("#38BDF8")
            }
            HandLandmarker.HAND_CONNECTIONS.forEach { conn ->
                val start = lm[conn.start()]
                val end = lm[conn.end()]
                canvas.drawLine(mapX(start.x()), mapY(start.y()), mapX(end.x()), mapY(end.y()), rigLinePaint)
            }
        }
        if (rigLinesOnly) return

        if (showAllFingers) {
            for (i in lm.indices) {
                jointPaint.color = fingerColor(i)
                val radius = if (i in listOf(4, 8, 12, 16, 20)) 14f else 9f
                canvas.drawCircle(mapX(lm[i].x()), mapY(lm[i].y()), radius, jointPaint)
            }
            val fingerTips = listOf(4 to "Thumb", 8 to "Index", 12 to "Middle", 16 to "Ring", 20 to "Pinky")
            textPaint.textSize = 26f
            for ((idx, label) in fingerTips) {
                textPaint.color = fingerColor(idx)
                canvas.drawText(label, mapX(lm[idx].x()) + 16f, mapY(lm[idx].y()) - 10f, textPaint)
            }
        }
    }

    private fun drawMousePreview(canvas: Canvas) {
        val padLeft = width * 0.1f
        val padRight = width * 0.9f
        val padTop = height * 0.16f
        val padBottom = height * 0.44f
        val padRect = RectF(padLeft, padTop, padRight, padBottom)
        canvas.drawRoundRect(padRect, 24f, 24f, padFillPaint)
        canvas.drawRoundRect(padRect, 24f, 24f, padBoxPaint)

        if (trail.size > 1) {
            for (i in 1 until trail.size) {
                val p1 = trail.elementAt(i - 1)
                val p2 = trail.elementAt(i)
                canvas.drawLine(
                    padLeft + p1.x * padRect.width(), padTop + p1.y * padRect.height(),
                    padLeft + p2.x * padRect.width(), padTop + p2.y * padRect.height(), trailPaint
                )
            }
        }

        val cx = padLeft + virtualCursorX * padRect.width()
        val cy = padTop + virtualCursorY * padRect.height()
        cursorPaint.color = when {
            leftClickActive -> Color.parseColor("#22C55E")
            rightClickActive -> Color.parseColor("#A855F7")
            scrollActive -> Color.parseColor("#F59E0B")
            clutchActive -> Color.parseColor("#EF4444")
            else -> Color.parseColor("#FACC15")
        }
        canvas.drawCircle(cx, cy, 16f, cursorPaint)

        textPaint.textSize = 28f
        textPaint.color = Color.WHITE
        val stateText = buildString {
            append("PC Mouse Preview   ")
            if (leftClickActive) append("[LEFT CLICK] ")
            if (rightClickActive) append("[RIGHT CLICK] ")
            if (scrollActive) append("[SWIPE / SCROLL] ")
            if (clutchActive) append("[PAUSED / FIST] ")
            if (landmarks == null) append("[Waiting for Right Hand]")
        }
        canvas.drawText(stateText, padLeft + 18f, padTop + 40f, textPaint)
    }

    private fun fingerColor(index: Int): Int = when (index) {
        in 1..4 -> Color.parseColor("#F43F5E")
        in 5..8 -> Color.parseColor("#22C55E")
        in 9..12 -> Color.parseColor("#A855F7")
        in 13..16 -> Color.parseColor("#F59E0B")
        in 17..20 -> Color.parseColor("#38BDF8")
        else -> Color.WHITE
    }
}
