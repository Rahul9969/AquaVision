package com.rahul.aquavision.ml.segmentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.content.ContextCompat
import com.rahul.aquavision.ml.BoundingBox
import com.rahul.aquavision.R
import com.rahul.aquavision.data.SpeciesRepository
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class DrawImages(private val context: Context) {

    private val boxColor = listOf(
        R.color.overlay_orange, R.color.overlay_blue, R.color.overlay_green,
        R.color.overlay_red, R.color.overlay_pink, R.color.overlay_cyan,
        R.color.overlay_purple, R.color.overlay_gray
    )
    private var currentColorBox = 0
    private fun getNextColor(): Int {
        val color = boxColor[currentColorBox]
        currentColorBox = (currentColorBox + 1) % boxColor.size
        return color
    }

    fun invoke(
        original: Bitmap,
        success: Success,
        coinResults: List<SegmentationResult>,
        isSeparateOut: Boolean,
        isMaskOut: Boolean,
        speciesBoxes: List<BoundingBox>,
        pixelsPerCm: Float,
        isMarkerDetected: Boolean
    ) : List<AnalysisResult> {

        val width = original.width
        val height = original.height

        if (isSeparateOut) {
            val outputList = mutableListOf<AnalysisResult>()
            val theCoin = coinResults.firstOrNull()

            if (success.results.isNotEmpty()) {
                val colorPairs: MutableMap<Int, Int> = mutableMapOf()
                success.results.forEach { colorPairs[it.box.cls] = getNextColor() }

                success.results.forEachIndexed { index, fishResult ->
                    val overlay = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val sb = StringBuilder()

                    if (theCoin != null) {
                        val coinDesc = applyTransparentOverlay(
                            context, overlay, theCoin,
                            R.color.white,
                            emptyList(),
                            pixelsPerCm,
                            isCoin = true
                        )
                        sb.append(context.getString(R.string.reference_coin_description, coinDesc))
                    }

                    val fishDesc = applyTransparentOverlay(
                        context, overlay, fishResult,
                        colorPairs[fishResult.box.cls] ?: R.color.primary,
                        speciesBoxes,
                        pixelsPerCm,
                        isCoin = false
                    )
                    sb.append(context.getString(R.string.fish_description, index + 1, fishDesc))

                    outputList.add(AnalysisResult(original, overlay, sb.toString()))
                }
            } else if (theCoin != null) {
                val overlay = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val coinDesc = applyTransparentOverlay(
                    context, overlay, theCoin,
                    R.color.white,
                    emptyList(),
                    pixelsPerCm,
                    isCoin = true
                )
                outputList.add(AnalysisResult(original, overlay, context.getString(R.string.reference_only_description, coinDesc)))
            }

            return outputList
        } else {
            if (success.results.isEmpty() && coinResults.isEmpty()) return emptyList()

            val combined = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val sb = StringBuilder()

            coinResults.forEach { result ->
                val desc = applyTransparentOverlay(
                    context, combined, result,
                    R.color.white,
                    emptyList(),
                    pixelsPerCm,
                    isCoin = true
                )
                sb.append(context.getString(R.string.reference_description, desc))
            }

            val colorPairs: MutableMap<Int, Int> = mutableMapOf()
            success.results.forEach { colorPairs[it.box.cls] = getNextColor() }

            success.results.forEachIndexed { index, result ->
                val desc = applyTransparentOverlay(
                    context, combined, result,
                    colorPairs[result.box.cls] ?: R.color.primary,
                    speciesBoxes,
                    pixelsPerCm,
                    isCoin = false
                )
                sb.append(context.getString(R.string.fish_description, index + 1, desc))
            }

            return listOf(AnalysisResult(original, combined, sb.toString()))
        }
    }

    private fun applyTransparentOverlay(
        context: Context,
        overlay: Bitmap,
        segmentationResult: SegmentationResult,
        overlayColorResId: Int,
        speciesBoxes: List<BoundingBox>,
        pixelsPerCm: Float,
        isCoin: Boolean
    ): String {
        val width = overlay.width
        val height = overlay.height
        val overlayColor = ContextCompat.getColor(context, overlayColorResId)

        // Draw Mask Pixels
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (segmentationResult.mask[y][x] > 0) {
                    overlay.setPixel(x, y, applyTransparentOverlayColor(overlayColor))
                }
            }
        }

        val canvas = Canvas(overlay)
        val boxPaint = Paint().apply {
            color = if (isCoin) Color.YELLOW else Color.WHITE
            strokeWidth = 4F
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        val textPaint = Paint().apply {
            color = if (isCoin) Color.YELLOW else Color.WHITE
            style = Paint.Style.FILL
            textSize = 28f
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }

        val box = segmentationResult.box
        var displayText = ""
        var detailedInfo = ""
        var labelX = box.x1 * width
        var labelY = box.y1 * height - 10

        if (isCoin) {
            // Draw Standard Bounding Box for Coin
            val left = box.x1 * width
            val top = box.y1 * height
            val right = box.x2 * width
            val bottom = box.y2 * height
            canvas.drawRect(left, top, right, bottom, boxPaint)

            val wPx = right - left
            val hPx = bottom - top
            val diameterPx = max(wPx, hPx)
            val diameterCm = (diameterPx / pixelsPerCm).toDouble()

            displayText = context.getString(R.string.coin_display_text, f(diameterCm))
            detailedInfo = context.getString(R.string.coin_detailed_info, f(diameterCm), f(diameterCm), f(diameterPx.toDouble()/2.7))
        } else {
            // --- FISH LOGIC: Use Aligned Box ---
            var bestName = context.getString(R.string.unknown)
            if (speciesBoxes.isNotEmpty()) {
                val maskRect = RectF(box.x1, box.y1, box.x2, box.y2)
                var maxIoU = 0.0f
                for (sBox in speciesBoxes) {
                    val sRect = RectF(sBox.x1, sBox.y1, sBox.x2, sBox.y2)
                    val iou = calculateIoU(maskRect, sRect)
                    if (iou > maxIoU && iou > 0.1) {
                        maxIoU = iou
                        bestName = sBox.clsName
                    }
                }
            } else {
                bestName = box.clsName
            }

            val bio = SpeciesRepository.getSpeciesInfo(bestName)

            // Calculate Volume & Get Aligned Corners
            val measurements = VolumeCalculator.calculateVolume(
                mask = segmentationResult.mask,
                speciesRatio = bio.ratio,
                pixelsPerCm = pixelsPerCm
            )

            // Draw Aligned Box if corners exist
            if (measurements.corners != null && measurements.corners.size == 4) {
                val path = Path()
                path.moveTo(measurements.corners[0].x, measurements.corners[0].y)
                path.lineTo(measurements.corners[1].x, measurements.corners[1].y)
                path.lineTo(measurements.corners[2].x, measurements.corners[2].y)
                path.lineTo(measurements.corners[3].x, measurements.corners[3].y)
                path.close()
                canvas.drawPath(path, boxPaint)

                // Update Label Position to the highest point of the aligned box
                val topCorner = measurements.corners.minByOrNull { it.y }
                if (topCorner != null) {
                    labelX = topCorner.x
                    labelY = topCorner.y - 10
                }
            } else {
                // Fallback to standard box if calculation fails
                val left = box.x1 * width
                val top = box.y1 * height
                val right = box.x2 * width
                val bottom = box.y2 * height
                canvas.drawRect(left, top, right, bottom, boxPaint)
            }

            val weightG = bio.a * measurements.lengthCm.pow(bio.b)

            displayText = context.getString(R.string.fish_display_text, bestName, f0(weightG))
            detailedInfo = context.getString(
                R.string.fish_detailed_info,
                bestName,
                f(measurements.lengthCm),
                f(measurements.depthCm),
                bio.a, bio.b,
                f0(weightG),
                f0(measurements.volumeCm3)
            )
        }

        if (labelY < 30) labelY = 40f
        canvas.drawText(displayText, labelX, labelY, textPaint)

        return detailedInfo
    }

    private fun calculateIoU(r1: RectF, r2: RectF): Float {
        val intersectLeft = max(r1.left, r2.left)
        val intersectTop = max(r1.top, r2.top)
        val intersectRight = min(r1.right, r2.right)
        val intersectBottom = min(r1.bottom, r2.bottom)
        if (intersectRight < intersectLeft || intersectBottom < intersectTop) return 0f
        val intersectionArea = (intersectRight - intersectLeft) * (intersectBottom - intersectTop)
        val r1Area = (r1.right - r1.left) * (r1.bottom - r1.top)
        val r2Area = (r2.right - r2.left) * (r2.bottom - r2.top)
        return intersectionArea / (r1Area + r2Area - intersectionArea)
    }

    private fun f(value: Double) = String.format("%.1f", value)
    private fun f0(value: Double) = String.format("%.0f", value)

    private fun applyTransparentOverlayColor(color: Int): Int {
        val alpha = 96
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        return Color.argb(alpha, red, green, blue)
    }
}