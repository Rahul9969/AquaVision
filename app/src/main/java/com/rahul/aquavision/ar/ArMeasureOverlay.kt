package com.rahul.aquavision.ar

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * Transparent overlay drawn on top of the AR camera surface.
 * Renders tap markers (pulsing circles) and a dashed measurement line.
 */
class ArMeasureOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Tap point screen coordinates
    private var point1: PointF? = null
    private var point2: PointF? = null

    // Animation
    private var pulseRadius = 0f
    private var pulseAlpha = 255
    private var lineProgress = 0f
    private var dashOffset = 0f

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF") // Bright cyan
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
        textSize = 32f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4f, 0f, 2f, Color.parseColor("#88000000"))
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC1A1A2E")
        style = Paint.Style.FILL
    }

    private var lengthLabel: String? = null

    // Pulse animation
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

    // Dash march animation
    private val dashAnimator = ValueAnimator.ofFloat(0f, 30f).apply {
        duration = 800
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { anim ->
            dashOffset = anim.animatedValue as Float
            linePaint.pathEffect = DashPathEffect(floatArrayOf(20f, 10f), dashOffset)
            invalidate()
        }
    }

    // Line draw-in animation
    private var lineAnimator: ValueAnimator? = null

    fun setPoint1(x: Float, y: Float) {
        point1 = PointF(x, y)
        point2 = null
        lengthLabel = null
        lineProgress = 0f

        if (!pulseAnimator.isRunning) pulseAnimator.start()
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
            
            // Start line draw-in animation if it's the first time
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

    fun reset() {
        point1 = null
        point2 = null
        lengthLabel = null
        lineProgress = 0f
        pulseAnimator.cancel()
        dashAnimator.cancel()
        lineAnimator?.cancel()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val p1 = point1 ?: return

        // Draw point 1 marker
        drawMarker(canvas, p1)

        // Draw measurement line and point 2
        val p2 = point2
        if (p2 != null && lineProgress > 0f) {
            // Interpolated end point for line animation
            val endX = p1.x + (p2.x - p1.x) * lineProgress
            val endY = p1.y + (p2.y - p1.y) * lineProgress

            // Glow line
            canvas.drawLine(p1.x, p1.y, endX, endY, glowPaint)
            // Dashed line
            canvas.drawLine(p1.x, p1.y, endX, endY, linePaint)

            if (lineProgress >= 1f) {
                // Draw point 2 marker
                drawMarker(canvas, p2)

                // Draw length label at midpoint
                lengthLabel?.let { label ->
                    val midX = (p1.x + p2.x) / 2f
                    val midY = (p1.y + p2.y) / 2f - 30f

                    val textWidth = labelPaint.measureText(label)
                    val labelRect = RectF(
                        midX - textWidth / 2f - 16f,
                        midY - 20f,
                        midX + textWidth / 2f + 16f,
                        midY + 18f
                    )
                    canvas.drawRoundRect(labelRect, 12f, 12f, labelBgPaint)
                    canvas.drawText(label, midX, midY + 10f, labelPaint)
                }
            }
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
        lineAnimator?.cancel()
    }
}
