package com.handmouse.app

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

data class MouseFrameOutput(
    val rightHandLandmarks: List<NormalizedLandmark>?,
    val leftClick: Boolean,
    val rightClick: Boolean,
    val scrolling: Boolean,
    val pausedClutch: Boolean,
    val dx: Int,
    val dy: Int,
    val wheel: Int
)

class HandMouseController {
    var sensitivity: Float = 1.6f
    var isFrontCamera: Boolean = false

    private var smoothX: Float? = null
    private var smoothY: Float? = null
    private var prevX: Float? = null
    private var prevY: Float? = null

    private var isLeftDown = false
    private var isRightDown = false
    private var isScrolling = false
    private var scrollAccumulator = 0f

    fun process(result: HandLandmarkerResult): MouseFrameOutput {
        val rightHandIndex = findRightHandIndex(result)
        if (rightHandIndex == -1) {
            resetTracking()
            return MouseFrameOutput(null, false, false, false, false, 0, 0, 0)
        }

        val lm = result.landmarks()[rightHandIndex]
        val handScale = dist(lm[0], lm[9]).coerceAtLeast(0.05f)

        val palmX = (lm[0].x() + lm[5].x() + lm[9].x() + lm[13].x() + lm[17].x()) / 5f
        val palmY = (lm[0].y() + lm[5].y() + lm[9].y() + lm[13].y() + lm[17].y()) / 5f

        val alpha = 0.45f
        smoothX = if (smoothX == null) palmX else smoothX!! + alpha * (palmX - smoothX!!)
        smoothY = if (smoothY == null) palmY else smoothY!! + alpha * (palmY - smoothY!!)

        val isFist = isFingerCurled(lm, 8, 6) &&
            isFingerCurled(lm, 12, 10) &&
            isFingerCurled(lm, 16, 14) &&
            isFingerCurled(lm, 20, 18)

        val thumbIndexRatio = dist(lm[4], lm[8]) / handScale
        val thumbMiddleRatio = dist(lm[4], lm[12]) / handScale
        val thumbRingRatio = dist(lm[4], lm[16]) / handScale

        isLeftDown = if (isLeftDown) thumbIndexRatio < 0.46f else thumbIndexRatio < 0.34f
        isRightDown = if (isRightDown) thumbMiddleRatio < 0.46f else thumbMiddleRatio < 0.34f
        isScrolling = if (isScrolling) thumbRingRatio < 0.46f else thumbRingRatio < 0.34f

        var outDx = 0
        var outDy = 0
        var outWheel = 0
        val curX = smoothX!!
        val curY = smoothY!!

        if (!isFist && prevX != null && prevY != null) {
            val rawDx = (curX - prevX!!) * (if (isFrontCamera) -1f else 1f)
            val rawDy = (curY - prevY!!)
            if (isScrolling) {
                scrollAccumulator += -rawDy * 45f
                val ticks = scrollAccumulator.toInt()
                if (ticks != 0) {
                    outWheel = ticks
                    scrollAccumulator -= ticks
                }
            } else {
                val gain = 1400f * sensitivity
                val deadzone = 0.002f
                if (abs(rawDx) > deadzone) outDx = (rawDx * gain).roundToInt()
                if (abs(rawDy) > deadzone) outDy = (rawDy * gain).roundToInt()
            }
        } else {
            scrollAccumulator = 0f
        }

        prevX = curX
        prevY = curY

        return MouseFrameOutput(
            rightHandLandmarks = lm,
            leftClick = if (isFist) false else isLeftDown,
            rightClick = if (isFist) false else isRightDown,
            scrolling = isScrolling && !isFist,
            pausedClutch = isFist,
            dx = outDx,
            dy = outDy,
            wheel = outWheel
        )
    }

    private fun findRightHandIndex(result: HandLandmarkerResult): Int {
        val handednessList = result.handedness()
        for (i in handednessList.indices) {
            val label = handednessList[i].firstOrNull()?.categoryName()
            if (label.equals("Right", ignoreCase = true)) return i
        }
        return -1
    }

    private fun isFingerCurled(lm: List<NormalizedLandmark>, tip: Int, pip: Int): Boolean =
        dist(lm[0], lm[tip]) < dist(lm[0], lm[pip]) * 1.05f

    private fun dist(a: NormalizedLandmark, b: NormalizedLandmark): Float =
        hypot(a.x() - b.x(), a.y() - b.y())

    private fun resetTracking() {
        smoothX = null; smoothY = null; prevX = null; prevY = null
        isLeftDown = false; isRightDown = false; isScrolling = false; scrollAccumulator = 0f
    }
}
