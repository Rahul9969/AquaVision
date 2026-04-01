package com.surendramaran.yolov8tflite.ui.customview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.surendramaran.yolov8tflite.R
import com.surendramaran.yolov8tflite.ml.BoundingBox
import kotlin.math.min

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var results = listOf<BoundingBox>()
    private var resultColors = listOf<Int>() // Store colors for results
    private var eyeResults = listOf<BoundingBox>()

    private var boxPaint = Paint()
    private var eyeBoxPaint = Paint()
    private var textBackgroundPaint = Paint()
    private var textPaint = Paint()

    private var bounds = Rect()

    private var imageWidth = 0
    private var imageHeight = 0
    private var isImageMode = false

    init {
        initPaints()
    }

    fun clear() {
        results = listOf()
        resultColors = listOf()
        eyeResults = listOf()
        textPaint.reset()
        textBackgroundPaint.reset()
        boxPaint.reset()
        eyeBoxPaint.reset()
        invalidate()
        initPaints()
    }

    private fun initPaints() {
        textBackgroundPaint.color = Color.BLACK
        textBackgroundPaint.style = Paint.Style.FILL
        textBackgroundPaint.textSize = 50f

        textPaint.color = Color.WHITE
        textPaint.style = Paint.Style.FILL
        textPaint.textSize = 50f

        // Base box paint (will be overridden by specific colors)
        boxPaint.color = ContextCompat.getColor(context!!, R.color.bounding_box_color)
        boxPaint.strokeWidth = 8F
        boxPaint.style = Paint.Style.STROKE

        eyeBoxPaint.color = ContextCompat.getColor(context!!, R.color.overlay_red)
        eyeBoxPaint.strokeWidth = 8F
        eyeBoxPaint.style = Paint.Style.STROKE
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        // Draw Fish Boxes with specific colors
        drawBoxes(canvas, results, boxPaint, true, resultColors)

        // Draw Eye Boxes (Red)
        drawBoxes(canvas, eyeResults, eyeBoxPaint, false, emptyList())
    }

    private fun drawBoxes(
        canvas: Canvas,
        boxes: List<BoundingBox>,
        basePaint: Paint,
        drawLabel: Boolean,
        colors: List<Int>
    ) {
        boxes.forEachIndexed { index, box ->
            // Use specific color if available, otherwise default
            if (colors.isNotEmpty() && index < colors.size) {
                basePaint.color = colors[index]
            }

            val left: Float
            val top: Float
            val right: Float
            val bottom: Float

            if (isImageMode && imageWidth > 0 && imageHeight > 0) {
                val scaleX = width.toFloat() / imageWidth
                val scaleY = height.toFloat() / imageHeight
                val scale = min(scaleX, scaleY)

                val scaledWidth = imageWidth * scale
                val scaledHeight = imageHeight * scale
                val offsetX = (width - scaledWidth) / 2
                val offsetY = (height - scaledHeight) / 2

                left = box.x1 * scaledWidth + offsetX
                top = box.y1 * scaledHeight + offsetY
                right = box.x2 * scaledWidth + offsetX
                bottom = box.y2 * scaledHeight + offsetY
            } else {
                left = box.x1 * width
                top = box.y1 * height
                right = box.x2 * width
                bottom = box.y2 * height
            }

            canvas.drawRect(left, top, right, bottom, basePaint)

            if (drawLabel) {
                val drawableText = "${box.clsName} ${String.format("%.2f", box.cnf)}"
                textBackgroundPaint.getTextBounds(drawableText, 0, drawableText.length, bounds)
                val textWidth = bounds.width()
                val textHeight = bounds.height()

                // Draw text background using the same color as box but semi-transparent?
                // Or keep black for readability. Let's keep black.
                canvas.drawRect(
                    left,
                    top,
                    left + textWidth + BOUNDING_RECT_TEXT_PADDING,
                    top + textHeight + BOUNDING_RECT_TEXT_PADDING,
                    textBackgroundPaint
                )
                canvas.drawText(drawableText, left, top + bounds.height(), textPaint)
            }
        }
    }

    fun setResults(boundingBoxes: List<BoundingBox>, colors: List<Int> = emptyList()) {
        results = boundingBoxes
        resultColors = colors
        invalidate()
    }

    fun setEyeResults(boundingBoxes: List<BoundingBox>) {
        eyeResults = boundingBoxes
        invalidate()
    }

    fun setImageDimensions(width: Int, height: Int) {
        imageWidth = width
        imageHeight = height
        isImageMode = true
    }

    fun setCameraMode() {
        isImageMode = false
        imageWidth = 0
        imageHeight = 0
    }

    companion object {
        private const val BOUNDING_RECT_TEXT_PADDING = 8
    }
}