package com.rahul.aquavision.ui.customview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.rahul.aquavision.R
import com.rahul.aquavision.ml.BoundingBox
import kotlin.math.min

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var results = listOf<BoundingBox>()
    private var resultColors = listOf<Int>() // Store colors for results
    private var eyeResults = listOf<BoundingBox>()
    private var fishBox: BoundingBox? = null

    private var boxPaint = Paint()
    private var eyeBoxPaint = Paint()
    private var fishBoxPaint = Paint()
    private var textBackgroundPaint = Paint()
    private var textPaint = Paint()

    private var bounds = Rect()

    private var imageWidth = 0
    private var imageHeight = 0
    private var isImageMode = false

    init {
        // Required for DashPathEffect to render correctly on all devices
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        initPaints()
    }

    fun clear() {
        results = listOf()
        resultColors = listOf()
        eyeResults = listOf()
        fishBox = null
        textPaint.reset()
        textBackgroundPaint.reset()
        boxPaint.reset()
        eyeBoxPaint.reset()
        fishBoxPaint.reset()
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

        // Dashed white style for fish detection box
        fishBoxPaint.color = Color.WHITE
        fishBoxPaint.strokeWidth = 4F
        fishBoxPaint.style = Paint.Style.STROKE
        fishBoxPaint.pathEffect = DashPathEffect(floatArrayOf(20f, 12f), 0f)
        fishBoxPaint.isAntiAlias = true
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        // Draw Fish Detection Box (dashed white — always behind feature boxes)
        fishBox?.let { box ->
            val (left, top, right, bottom) = computeBoxCoords(box)
            canvas.drawRect(left, top, right, bottom, fishBoxPaint)

            // Draw "Fish" label
            val label = "${box.clsName} ${String.format("%.0f%%", box.cnf * 100)}"
            val labelPaint = Paint(textBackgroundPaint).apply { color = Color.parseColor("#88FFFFFF") }
            labelPaint.getTextBounds(label, 0, label.length, bounds)
            val textWidth = bounds.width()
            val textHeight = bounds.height()
            canvas.drawRect(left, top - textHeight - BOUNDING_RECT_TEXT_PADDING * 2,
                left + textWidth + BOUNDING_RECT_TEXT_PADDING * 2, top, labelPaint)
            canvas.drawText(label, left + BOUNDING_RECT_TEXT_PADDING, top - BOUNDING_RECT_TEXT_PADDING,
                Paint(textPaint).apply { color = Color.DKGRAY; textSize = 40f })
        }

        // Draw Fish Boxes with specific colors
        drawBoxes(canvas, results, boxPaint, true, resultColors)

        // Draw Eye/Gill Boxes (Green/Cyan with labels but no confidence)
        drawBoxes(canvas, eyeResults, eyeBoxPaint, true, emptyList(), showConfidence = false)
    }

    /**
     * Compute pixel coordinates for a normalized bounding box,
     * handling both image-mode (fitCenter scaling) and camera-mode.
     */
    private fun computeBoxCoords(box: BoundingBox): FloatArray {
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

        return floatArrayOf(left, top, right, bottom)
    }

    private fun drawBoxes(
        canvas: Canvas,
        boxes: List<BoundingBox>,
        basePaint: Paint,
        drawLabel: Boolean,
        colors: List<Int>,
        showConfidence: Boolean = true
    ) {
        boxes.forEachIndexed { index, box ->
            // Specific overrides for eye/gills feature detection
            if (box.clsName == "Eye") {
                basePaint.color = Color.GREEN
            } else if (box.clsName == "Gills") {
                basePaint.color = Color.parseColor("#00FFFF") // Cyan for gills
            } else if (colors.isNotEmpty() && index < colors.size) {
                basePaint.color = colors[index]
            }

            val (left, top, right, bottom) = computeBoxCoords(box)

            canvas.drawRect(left, top, right, bottom, basePaint)

            if (drawLabel) {
                val drawableText = if (showConfidence) {
                    "${box.clsName} ${String.format("%.2f", box.cnf)}"
                } else {
                    box.clsName
                }
                
                textBackgroundPaint.color = basePaint.color // Use bounding box color for text background
                textBackgroundPaint.getTextBounds(drawableText, 0, drawableText.length, bounds)
                val textWidth = bounds.width()
                val textHeight = bounds.height()

                canvas.drawRect(
                    left,
                    top - textHeight - BOUNDING_RECT_TEXT_PADDING * 2,
                    left + textWidth + BOUNDING_RECT_TEXT_PADDING * 2,
                    top,
                    textBackgroundPaint
                )
                canvas.drawText(drawableText, left + BOUNDING_RECT_TEXT_PADDING, top - BOUNDING_RECT_TEXT_PADDING, textPaint)
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
        // Use post() so the invalidate runs after the current layout pass completes.
        // Without this, the view dimensions may still be 0 when draw() is called,
        // causing bounding boxes to render at (0,0) or be invisible.
        post { invalidate() }
    }

    /**
     * Set the fish detection bounding box — drawn with a dashed white outline
     * behind the feature (eye/gill) boxes to show what region was detected.
     */
    fun setFishBox(box: BoundingBox?) {
        fishBox = box
        post { invalidate() }
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