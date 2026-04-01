package com.surendramaran.yolov8tflite.ml.segmentation

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
    val corners: List<PointF>? // Added: Corners for drawing
)

object VolumeCalculator {

    fun calculateVolume(mask: Array<IntArray>, speciesRatio: Double, pixelsPerCm: Float): Measurement {
        if (pixelsPerCm <= 0) return Measurement(0.0, 0.0, 0.0, null)

        val rows = mask.size
        val cols = mask[0].size
        val mat = Mat(rows, cols, CvType.CV_8UC1)
        val byteArray = ByteArray(rows * cols)
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                byteArray[i * cols + j] = if (mask[i][j] > 0) 255.toByte() else 0
            }
        }
        mat.put(0, 0, byteArray)

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(mat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        if (contours.isEmpty()) {
            mat.release(); hierarchy.release()
            return Measurement(0.0, 0.0, 0.0, null)
        }

        val maxContour = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return Measurement(0.0, 0.0, 0.0, null)

        // --- 1. Get Rotated Rectangle ---
        val point2f = MatOfPoint2f(*maxContour.toArray())
        val rotatedRect = Imgproc.minAreaRect(point2f)

        // --- 2. Extract Corners for Drawing ---
        val rectPoints = arrayOfNulls<Point>(4)
        rotatedRect.points(rectPoints)
        val cornerPoints = rectPoints.filterNotNull().map { PointF(it.x.toFloat(), it.y.toFloat()) }

        // --- 3. Align Logic ---
        val lengthPx = max(rotatedRect.size.width, rotatedRect.size.height)
        val depthPx = min(rotatedRect.size.width, rotatedRect.size.height)

        var angle = rotatedRect.angle
        if (rotatedRect.size.width < rotatedRect.size.height) {
            angle += 90.0
        }

        val rotationMatrix = Imgproc.getRotationMatrix2D(rotatedRect.center, angle, 1.0)
        val rotatedMat = Mat()
        Imgproc.warpAffine(mat, rotatedMat, rotationMatrix, mat.size(), Imgproc.INTER_NEAREST)

        // --- 4. Volume Integration ---
        val colSums = Mat()
        Core.reduce(rotatedMat, colSums, 0, Core.REDUCE_SUM, CvType.CV_32S)

        var totalVolumePx = 0.0
        val widthData = IntArray(cols)
        colSums.get(0, 0, widthData)

        val geometricFactor = (PI * speciesRatio) / 4.0

        for (pixelSum in widthData) {
            val h = pixelSum / 255.0
            if (h > 0) {
                totalVolumePx += (h * h) * geometricFactor
            }
        }

        val scaleCubed = pixelsPerCm.toDouble() * pixelsPerCm.toDouble() * pixelsPerCm.toDouble()
        val volumeCm3 = totalVolumePx / scaleCubed
        val realLengthCm = lengthPx / pixelsPerCm
        val realDepthCm = depthPx / pixelsPerCm

        mat.release()
        rotatedMat.release()
        colSums.release()
        hierarchy.release()
        point2f.release()

        return Measurement(volumeCm3, realLengthCm, realDepthCm, cornerPoints)
    }
}