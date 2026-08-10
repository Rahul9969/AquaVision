package com.rahul.aquavision.ar

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator

/**
 * Transparent overlay drawn on top of the AR camera surface.
 * Renders:
 *  - Crosshair / reticle when idle (helps user aim)
 *  - Pulsing tap markers for measurement points
 *  - Animated dashed measurement line (hit-test path)
 *  - Corner-bracket bounding box with sweep scan line (depth path)
 *  - Scan progress ring during depth computation
 *  - Floating length label
 */
class ArMeasureOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Tap point screen coordinates
    private var point1: PointF? = null
    private var point2: PointF? = null

    // Bounding Box for 3D Mesh
    private var boundingBox: RectF? = null

    // Crosshair visibility
    private var showCrosshair = true

    // Scan progress (0‒1, -1 = idle)
    private var scanProgress = -1f

    // Animation values
    private var pulseRadius = 0f
    private var pulseAlpha = 255
    private var lineProgress = 0f
    private var dashOffset = 0f
    private var crosshairPulse = 0f
    private var scanLineY = 0f

    // ── Paints ──────────────────────────────────────────────────────────

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
    }

    private val markerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4000E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 8f
        maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(6f, 0f, 2f, Color.parseColor("#AA000000"))
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DD1A1A2E")
        style = Paint.Style.FILL
    }

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val crosshairAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6000E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }

    private val scanFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1A00E5FF")
        style = Paint.Style.FILL
    }

    private val scanLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8000E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val progressArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }

    private var lengthLabel: String? = null

    // Frame progress text for multi-frame depth accumulation
    private var frameProgressText: String? = null

    // ── Animators ───────────────────────────────────────────────────────

    // Pulse animation for tap markers
    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1200
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { anim ->
            val fraction = anim.animatedValue as Float
            pulseRadius = 12f + fraction * 30f
            pulseAlpha = ((1f - fraction) * 180).toInt()
            invalidate()
        }
    }

    // Dash march animation for measurement line
    private val dashAnimator = ValueAnimator.ofFloat(0f, 30f).apply {
        duration = 800
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { anim ->
            dashOffset = anim.animatedValue as Float
            linePaint.pathEffect = DashPathEffect(floatArrayOf(20f, 10f), dashOffset)
            invalidate()
        }
    }

    // Crosshair breathing animation
    private val crosshairAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2000
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { anim ->
            crosshairPulse = anim.animatedValue as Float
            if (showCrosshair) invalidate()
        }
    }

    // Scan line sweep animation (inside bounding box)
    private var scanLineAnimator: ValueAnimator? = null

    // Line draw-in animation
    private var lineAnimator: ValueAnimator? = null

    // Scan progress ring animation
    private var scanProgressAnimator: ValueAnimator? = null

    init {
        crosshairAnimator.start()
    }

    // ── Public API ──────────────────────────────────────────────────────

    fun setPoint1(x: Float, y: Float) {
        point1 = PointF(x, y)
        point2 = null
        boundingBox = null
        lengthLabel = null
        lineProgress = 0f
        showCrosshair = false

        if (!pulseAnimator.isRunning) pulseAnimator.start()
        invalidate()
    }

    /** Start an indeterminate-style scan progress ring around point1. */
    fun startScanProgress() {
        scanProgress = 0f
        scanProgressAnimator?.cancel()
        scanProgressAnimator = ValueAnimator.ofFloat(0f, 0.9f).apply {
            duration = 2000
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                scanProgress = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Finish the progress ring quickly and prepare for bounding box. */
    fun completeScanProgress() {
        scanProgressAnimator?.cancel()
        scanProgressAnimator = ValueAnimator.ofFloat(scanProgress, 1f).apply {
            duration = 200
            addUpdateListener { anim ->
                scanProgress = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun setBoundingBox(left: Float, top: Float, right: Float, bottom: Float) {
        boundingBox = RectF(left, top, right, bottom)

        // Complete the scan progress
        completeScanProgress()

        // Animate bounding box drawing from center
        lineAnimator?.cancel()
        lineAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                lineProgress = anim.animatedValue as Float
                invalidate()
            }
            start()
        }

        // Scan line sweep inside the box
        scanLineAnimator?.cancel()
        scanLineAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                scanLineY = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
        invalidate()
    }

    fun updatePoint1(x: Float, y: Float) {
        if (point1 == null) return
        point1?.x = x
        point1?.y = y
        invalidate()
    }

    fun updatePoint2(x: Float, y: Float) {
        if (point2 == null) {
            point2 = PointF(x, y)

            // Start line draw-in animation
            lineAnimator?.cancel()
            lineAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 400
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { anim ->
                    lineProgress = anim.animatedValue as Float
                    invalidate()
                }
                start()
            }
            if (!dashAnimator.isRunning) dashAnimator.start()
        } else {
            point2?.x = x
            point2?.y = y
        }
        invalidate()
    }

    fun setPoint2Length(lengthCm: Float) {
        lengthLabel = "%.1f cm".format(lengthCm)
        invalidate()
    }

    /** Update multi-frame accumulation progress label. */
    fun setFrameProgress(current: Int, total: Int) {
        frameProgressText = "Frame $current/$total"
        // Smoothly advance progress ring based on frame count
        val targetProgress = (current.toFloat() / total.toFloat()).coerceIn(0f, 0.95f)
        scanProgressAnimator?.cancel()
        scanProgressAnimator = ValueAnimator.ofFloat(scanProgress, targetProgress).apply {
            duration = 250
            addUpdateListener { anim ->
                scanProgress = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
        invalidate()
    }

    fun reset() {
        point1 = null
        point2 = null
        boundingBox = null
        lengthLabel = null
        frameProgressText = null
        lineProgress = 0f
        showCrosshair = true
        scanProgress = -1f
        pulseAnimator.cancel()
        dashAnimator.cancel()
        lineAnimator?.cancel()
        scanLineAnimator?.cancel()
        scanProgressAnimator?.cancel()
        if (!crosshairAnimator.isRunning) crosshairAnimator.start()
        invalidate()
    }

    // ── Drawing ─────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw crosshair when idle
        if (showCrosshair && point1 == null) {
            drawCrosshair(canvas)
            return
        }

        val p1 = point1 ?: return

        // Draw scan progress ring around tap point (during depth computation)
        if (scanProgress in 0f..1f && boundingBox == null) {
            drawScanProgress(canvas, p1)
        }

        // Draw point 1 marker when no bounding box and no active scan / line
        if (boundingBox == null && scanProgress < 0f && point2 == null) {
            drawMarker(canvas, p1)
        }

        // ── Depth path: bounding box with corner brackets ──
        boundingBox?.let { box ->
            if (lineProgress > 0f) {
                val bx = box.centerX()
                val by = box.centerY()
                val w = box.width() / 2f * lineProgress
                val h = box.height() / 2f * lineProgress
                val animBox = RectF(bx - w, by - h, bx + w, by + h)

                // Tinted fill
                canvas.drawRect(animBox, scanFillPaint)

                // Corner brackets
                drawCornerBrackets(canvas, animBox)

                // Scan line sweeping vertically
                if (lineProgress >= 1f && scanLineAnimator?.isRunning == true) {
                    val sy = animBox.top + animBox.height() * scanLineY
                    canvas.drawLine(animBox.left, sy, animBox.right, sy, scanLinePaint)
                }

                // Length label below the box
                if (lineProgress >= 1f) {
                    lengthLabel?.let { label ->
                        val midX = bx
                        val midY = box.bottom + 44f

                        val textWidth = labelPaint.measureText(label)
                        val labelRect = RectF(
                            midX - textWidth / 2f - 20f,
                            midY - 22f,
                            midX + textWidth / 2f + 20f,
                            midY + 20f
                        )
                        canvas.drawRoundRect(labelRect, 14f, 14f, labelBgPaint)
                        canvas.drawText(label, midX, midY + 11f, labelPaint)
                    }
                }
            }
        }

        // ── Hit-test path: measurement line between two points ──
        val p2 = point2
        if (p2 != null && lineProgress > 0f) {
            val endX = p1.x + (p2.x - p1.x) * lineProgress
            val endY = p1.y + (p2.y - p1.y) * lineProgress

            // Glow + dashed line
            canvas.drawLine(p1.x, p1.y, endX, endY, glowPaint)
            canvas.drawLine(p1.x, p1.y, endX, endY, linePaint)

            // Always draw p1 marker on the line
            drawMarker(canvas, p1)

            if (lineProgress >= 1f) {
                // Draw p2 marker
                drawMarker(canvas, p2)

                // Floating label at midpoint
                lengthLabel?.let { label ->
                    val midX = (p1.x + p2.x) / 2f
                    val midY = (p1.y + p2.y) / 2f - 34f

                    val textWidth = labelPaint.measureText(label)
                    val labelRect = RectF(
                        midX - textWidth / 2f - 20f,
                        midY - 22f,
                        midX + textWidth / 2f + 20f,
                        midY + 20f
                    )
                    canvas.drawRoundRect(labelRect, 14f, 14f, labelBgPaint)
                    canvas.drawText(label, midX, midY + 11f, labelPaint)
                }
            }
        }
    }

    // ── Private drawing helpers ─────────────────────────────────────────

    private fun drawCrosshair(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val size = 24f + crosshairPulse * 6f
        val alpha = (180 + crosshairPulse * 75).toInt()

        crosshairPaint.alpha = alpha
        crosshairAccentPaint.alpha = alpha

        // Horizontal line segments with gap in center
        canvas.drawLine(cx - size - 8f, cy, cx - 8f, cy, crosshairPaint)
        canvas.drawLine(cx + 8f, cy, cx + size + 8f, cy, crosshairPaint)

        // Vertical line segments
        canvas.drawLine(cx, cy - size - 8f, cx, cy - 8f, crosshairPaint)
        canvas.drawLine(cx, cy + 8f, cx, cy + size + 8f, crosshairPaint)

        // Outer circle
        val circleRadius = 20f + crosshairPulse * 4f
        crosshairAccentPaint.alpha = (alpha * 0.5f).toInt()
        crosshairAccentPaint.style = Paint.Style.STROKE
        canvas.drawCircle(cx, cy, circleRadius, crosshairAccentPaint)

        // Center dot
        crosshairAccentPaint.alpha = alpha
        crosshairAccentPaint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, 3f, crosshairAccentPaint)
        crosshairAccentPaint.style = Paint.Style.STROKE
    }

    private fun drawCornerBrackets(canvas: Canvas, box: RectF) {
        val bracketLen = Math.min(box.width(), box.height()) * 0.25f
        val bl = bracketLen.coerceIn(12f, 40f)

        // Top-left
        canvas.drawLine(box.left, box.top, box.left + bl, box.top, bracketPaint)
        canvas.drawLine(box.left, box.top, box.left, box.top + bl, bracketPaint)

        // Top-right
        canvas.drawLine(box.right, box.top, box.right - bl, box.top, bracketPaint)
        canvas.drawLine(box.right, box.top, box.right, box.top + bl, bracketPaint)

        // Bottom-left
        canvas.drawLine(box.left, box.bottom, box.left + bl, box.bottom, bracketPaint)
        canvas.drawLine(box.left, box.bottom, box.left, box.bottom - bl, bracketPaint)

        // Bottom-right
        canvas.drawLine(box.right, box.bottom, box.right - bl, box.bottom, bracketPaint)
        canvas.drawLine(box.right, box.bottom, box.right, box.bottom - bl, bracketPaint)
    }

    private fun drawScanProgress(canvas: Canvas, center: PointF) {
        val radius = 32f
        val sweepAngle = scanProgress * 360f
        val rect = RectF(
            center.x - radius, center.y - radius,
            center.x + radius, center.y + radius
        )

        // Background track
        val trackPaint = Paint(progressArcPaint)
        trackPaint.alpha = 60
        canvas.drawCircle(center.x, center.y, radius, trackPaint)

        // Progress arc
        canvas.drawArc(rect, -90f, sweepAngle, false, progressArcPaint)

        // Center pulsing dot
        markerPaint.color = Color.parseColor("#00E5FF")
        markerPaint.alpha = 255
        canvas.drawCircle(center.x, center.y, 6f, markerPaint)

        // Frame progress text below the ring
        frameProgressText?.let { text ->
            val textY = center.y + radius + 28f
            val framePaint = Paint(labelPaint).apply {
                textSize = 28f
                color = Color.parseColor("#B0BEC5")
            }
            canvas.drawText(text, center.x, textY, framePaint)
        }
    }

    private fun drawMarker(canvas: Canvas, point: PointF) {
        // Outer pulse ring
        pulsePaint.alpha = pulseAlpha
        canvas.drawCircle(point.x, point.y, pulseRadius, pulsePaint)

        // Outer ring
        markerRingPaint.alpha = 200
        canvas.drawCircle(point.x, point.y, 14f, markerRingPaint)

        // Inner filled dot
        markerPaint.alpha = 255
        markerPaint.color = Color.parseColor("#00E5FF")
        canvas.drawCircle(point.x, point.y, 8f, markerPaint)

        // Center white dot
        markerPaint.color = Color.WHITE
        canvas.drawCircle(point.x, point.y, 3f, markerPaint)
        markerPaint.color = Color.parseColor("#00E5FF")
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator.cancel()
        dashAnimator.cancel()
        crosshairAnimator.cancel()
        lineAnimator?.cancel()
        scanLineAnimator?.cancel()
        scanProgressAnimator?.cancel()
    }
}
