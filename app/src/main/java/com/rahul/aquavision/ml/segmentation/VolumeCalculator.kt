package com.rahul.aquavision.ml.segmentation

import android.graphics.PointF
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

data class Measurement(
    val volumeCm3: Double,
    val lengthCm: Double,
    val depthCm: Double,
    val corners: List<PointF>?
)

object VolumeCalculator {

    /**
     * Computes volume (cm³) and dimensions from a binary segmentation mask using
     * the Disc Integration (Solid of Revolution) method.
     *
     * Mathematical model:
     *   The fish body is treated as a solid whose cross-section at each position x
     *   is an ellipse. The mask gives the vertical profile h(x) (height of fish in pixels).
     *   The horizontal depth (into camera) is assumed proportional: depth(x) = h(x) × ratio.
     *
     *   Ellipse cross-section area at x:
     *     A(x) = π × (h/2) × (h×ratio/2) = π × ratio × h² / 4
     *
     *   Volume (px³) = Σ_x [ π × ratio / 4 × h(x)² ]  (each slice is 1 px thick)
     *   Volume (cm³) = Volume_px³ / pixelsPerCm³
     *
     * @param mask         Binary segmentation mask (pixel > 0 = fish body)
     * @param speciesRatio Body form factor: depth/height ratio of cross-section (see SpeciesData)
     * @param pixelsPerCm  Scale derived from coin reference or ArUco marker
     */
    fun calculateVolume(mask: Array<IntArray>, speciesRatio: Double, pixelsPerCm: Float): Measurement {
        if (pixelsPerCm <= 0) return Measurement(0.0, 0.0, 0.0, null)

        val rows = mask.size
        val cols = mask[0].size

        // Build binary OpenCV Mat from mask
        val mat = Mat(rows, cols, CvType.CV_8UC1)
        val byteArray = ByteArray(rows * cols)
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                byteArray[i * cols + j] = if (mask[i][j] > 0) 255.toByte() else 0
            }
        }
        mat.put(0, 0, byteArray)

        // Find largest contour (the fish body)
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(mat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        if (contours.isEmpty()) {
            mat.release(); hierarchy.release()
            return Measurement(0.0, 0.0, 0.0, null)
        }

        val maxContour = contours.maxByOrNull { Imgproc.contourArea(it) }
            ?: run { mat.release(); hierarchy.release(); return Measurement(0.0, 0.0, 0.0, null) }

        // Fit minimum-area rectangle to get orientation and true length/depth
        val point2f = MatOfPoint2f(*maxContour.toArray())
        val rotatedRect = Imgproc.minAreaRect(point2f)

        val rectPoints = arrayOfNulls<Point>(4)
        rotatedRect.points(rectPoints)
        val cornerPoints = rectPoints.filterNotNull().map { PointF(it.x.toFloat(), it.y.toFloat()) }

        val lengthPx = max(rotatedRect.size.width, rotatedRect.size.height)
        val depthPx  = min(rotatedRect.size.width, rotatedRect.size.height)

        // Rotate fish so major axis aligns horizontally for clean column sums
        var angle = rotatedRect.angle
        if (rotatedRect.size.width < rotatedRect.size.height) angle += 90.0

        val rotationMatrix = Imgproc.getRotationMatrix2D(rotatedRect.center, angle, 1.0)
        val rotatedMat = Mat()
        Imgproc.warpAffine(mat, rotatedMat, rotationMatrix, mat.size(), Imgproc.INTER_NEAREST)

        // Column sums: each value = sum of pixel values in that column = 255 × (# lit pixels)
        // h(x) = column_sum / 255 = thickness of fish cross-section at position x
        val colSums = Mat()
        Core.reduce(rotatedMat, colSums, 0, Core.REDUCE_SUM, CvType.CV_32S)

        val widthData = IntArray(cols)
        colSums.get(0, 0, widthData)

        // Disc integration: V = Σ [π × ratio / 4 × h²] for each 1-pixel-thick slice
        val geometricFactor = (PI * speciesRatio) / 4.0
        var totalVolumePx3 = 0.0
        for (pixelSum in widthData) {
            val h = pixelSum / 255.0   // height of fish (pixels) at this x-slice
            if (h > 0) {
                totalVolumePx3 += h * h * geometricFactor
            }
        }

        // Convert from pixel³ to cm³
        val pxPerCm3 = pixelsPerCm.toDouble().let { it * it * it }
        val volumeCm3 = totalVolumePx3 / pxPerCm3

        val realLengthCm = lengthPx / pixelsPerCm
        val realDepthCm  = depthPx  / pixelsPerCm

        mat.release()
        rotatedMat.release()
        colSums.release()
        hierarchy.release()
        point2f.release()

        return Measurement(volumeCm3, realLengthCm, realDepthCm, cornerPoints)
    }
}