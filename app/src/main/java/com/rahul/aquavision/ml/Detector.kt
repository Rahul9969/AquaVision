package com.rahul.aquavision.ml

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

class Detector(
    private val context: Context,
    private val modelPath: String,
    private val labelPath: String,
    private var detectorListener: DetectorListener,
    private val confidenceThreshold: Float = 0.55F
) {

    fun setListener(listener: DetectorListener) {
        detectorListener = listener
    }

    private var interpreter: Interpreter
    private var labels = mutableListOf<String>()

    private var tensorWidth = 0
    private var tensorHeight = 0
    private var numChannel = 0
    private var numElements = 0

    private val imageProcessor = ImageProcessor.Builder()
        .add(NormalizeOp(INPUT_MEAN, INPUT_STANDARD_DEVIATION))
        .add(CastOp(INPUT_IMAGE_TYPE))
        .build()

    init {
        val compatList = CompatibilityList()

        val options = Interpreter.Options().apply{
            if(compatList.isDelegateSupportedOnThisDevice){
                val delegateOptions = compatList.bestOptionsForThisDevice
                this.addDelegate(GpuDelegate(delegateOptions))
            } else {
                this.setNumThreads(4)
            }
        }

        val model = FileUtil.loadMappedFile(context, modelPath)
        interpreter = Interpreter(model, options)

        val inputShape = interpreter.getInputTensor(0)?.shape()
        val outputShape = interpreter.getOutputTensor(0)?.shape()

        if (inputShape != null) {
            tensorWidth = inputShape[1]
            tensorHeight = inputShape[2]

            // If in case input shape is in format of [1, 3, ..., ...]
            if (inputShape[1] == 3) {
                tensorWidth = inputShape[2]
                tensorHeight = inputShape[3]
            }
        }

        if (outputShape != null) {
            numChannel = outputShape[1]
            numElements = outputShape[2]
        }

        try {
            val inputStream: InputStream = context.assets.open(labelPath)
            val reader = BufferedReader(InputStreamReader(inputStream))

            var line: String? = reader.readLine()
            while (line != null && line != "") {
                labels.add(line)
                line = reader.readLine()
            }

            reader.close()
            inputStream.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun restart(isGpu: Boolean) {
        interpreter.close()

        val options = if (isGpu) {
            val compatList = CompatibilityList()
            Interpreter.Options().apply{
                if(compatList.isDelegateSupportedOnThisDevice){
                    val delegateOptions = compatList.bestOptionsForThisDevice
                    this.addDelegate(GpuDelegate(delegateOptions))
                } else {
                    this.setNumThreads(4)
                }
            }
        } else {
            Interpreter.Options().apply{
                this.setNumThreads(4)
            }
        }

        val model = FileUtil.loadMappedFile(context, modelPath)
        interpreter = Interpreter(model, options)
    }

    fun close() {
        interpreter.close()
    }

    fun detect(frame: Bitmap) {
        if (tensorWidth == 0 || tensorHeight == 0 || numChannel == 0 || numElements == 0) {
            detectorListener.onEmptyDetect()
            return
        }

        try {
            var inferenceTime = SystemClock.uptimeMillis()

            val resizedBitmap = Bitmap.createScaledBitmap(frame, tensorWidth, tensorHeight, false)

            val tensorImage = TensorImage(INPUT_IMAGE_TYPE)
            tensorImage.load(resizedBitmap)
            val processedImage = imageProcessor.process(tensorImage)
            val imageBuffer = processedImage.buffer

            val output = TensorBuffer.createFixedSize(intArrayOf(1, numChannel, numElements), OUTPUT_IMAGE_TYPE)
            interpreter.run(imageBuffer, output.buffer)

            val bestBoxes = bestBox(output.floatArray)
            inferenceTime = SystemClock.uptimeMillis() - inferenceTime

            if (bestBoxes == null) {
                detectorListener.onEmptyDetect()
                return
            }

            detectorListener.onDetect(bestBoxes, inferenceTime)
        } catch (e: Exception) {
            detectorListener.onEmptyDetect()
        }
    }

    /**
     * Per-class feature detection — exact port of Python's detect_features().
     *
     * IMPORTANT: The feature model was trained on EYE CLOSE-UP images.
     * When given a full fish crop, it outputs LARGE anchors (w~0.6, h~0.9).
     * This is EXPECTED — the model has no training signal for small-eye
     * localization within a whole-fish photo.
     *
     * The large eye box is still used to crop the eye region for freshness.
     * The low confidence (typically <0.05) gives the eye channel near-zero
     * weight = 0.4 * eye_score ~= 0.0012, so gills + whole fish dominate.
     * Per-class feature detection for the freshness pipeline.
     *
     * KEY DESIGN DECISION: size caps are ALWAYS enforced, even in fallback passes.
     * The model was trained on eye close-up images so it fires large YOLO anchors
     * on full-fish crops. We deliberately ignore those large anchors because:
     *   - An eye cannot physically be >40% of the fish image in either dimension.
     *   - A huge "eye" box fed to the freshness model produces worse results
     *     than using the whole-fish baseline.
     *
     * Instead of removing the size filter (Python's _pick_no_size), we lower
     * the confidence threshold progressively across 4 passes:
     *   Pass 1: conf > 0.05,  size < maxW/maxH   (standard YOLO threshold)
     *   Pass 2: conf > 0.01,  size < maxW/maxH   (float16 score compression)
     *   Pass 3: conf > 0.001, size < maxW/maxH   (very weak signal still valid)
     *   Pass 4: horizontal flip + repeat passes 1-3 (handles right-facing fish)
     *
     * If nothing found after all passes -> returns null for that feature.
     * The freshness pipeline then uses gills + whole-fish baseline.
     *
     * Eye max:  w=0.40, h=0.40  (~left quarter of a typical fish crop)
     * Gill max: w=0.70, h=0.70  (gill arch spans more of the fish body)
     */
    fun detectFeaturesPerClassSync(
        frame: Bitmap,
        eyeClassIdx: Int = 0,
        gillClassIdx: Int = 1,
        confThreshold: Float = 0.05f
    ): Pair<BoundingBox?, BoundingBox?> {
        if (tensorWidth == 0 || tensorHeight == 0 || numChannel == 0 || numElements == 0) {
            return Pair(null, null)
        }
        return try {
            val resizedBitmap = Bitmap.createScaledBitmap(frame, tensorWidth, tensorHeight, false)
            val tensorImage = TensorImage(INPUT_IMAGE_TYPE)
            tensorImage.load(resizedBitmap)
            val processedImage = imageProcessor.process(tensorImage)
            val output = TensorBuffer.createFixedSize(intArrayOf(1, numChannel, numElements), OUTPUT_IMAGE_TYPE)
            interpreter.run(processedImage.buffer, output.buffer)
            val array = output.floatArray


            // ── Pass 1: Python _pick ─────────────────────────────────────────────
            // score > confThreshold AND size < maxW/maxH (strict — no large boxes)
            fun pick(arr: FloatArray, classIdx: Int, maxW: Float, maxH: Float): BoundingBox? {
                var bestScore = confThreshold
                var bestIdx   = -1
                for (c in 0 until numElements) {
                    val score = arr[c + numElements * (4 + classIdx)]
                    if (score <= bestScore) continue
                    var w = arr[c + numElements * 2]
                    var h = arr[c + numElements * 3]
                    if (w > 1.5f) w /= tensorWidth.toFloat()
                    if (h > 1.5f) h /= tensorHeight.toFloat()
                    if (w < maxW && h < maxH) { bestScore = score; bestIdx = c }
                }
                return anchorToBox(arr, bestIdx, classIdx, bestScore)
            }

            // ── Pass 2: Python _pick_no_size ─────────────────────────────────────
            // NO size filter — exact port of Python.
            // mask = scores > CONF_THRESH; idx = argmax(scores[mask]) else argmax(scores)
            // Large boxes ARE returned here. The freshness pipeline weights them by
            // confidence (weight = 0.4 * cnf) so a low-score eye barely contributes.
            // The UI display layer separately filters out large/low-conf boxes.
            fun pickNoSize(arr: FloatArray, classIdx: Int): BoundingBox? {
                var bestScoreGated = confThreshold     // phase A: threshold-gated
                var bestIdxGated   = -1
                var bestScoreAll   = -Float.MAX_VALUE  // phase B: absolute argmax
                var bestIdxAll     = 0
                for (c in 0 until numElements) {
                    val score = arr[c + numElements * (4 + classIdx)]
                    if (score > bestScoreAll)   { bestScoreAll = score;   bestIdxAll   = c }
                    if (score > bestScoreGated) { bestScoreGated = score; bestIdxGated = c }
                }
                return when {
                    bestIdxGated >= 0     -> anchorToBox(arr, bestIdxGated, classIdx, bestScoreGated)
                    bestScoreAll > 0.001f -> anchorToBox(arr, bestIdxAll,   classIdx, bestScoreAll)
                    else -> null
                }
            }

            // Passes 1 & 2 on original orientation (exact Python detect_features order)
            var bestEye  = pick(array, eyeClassIdx,  0.4f, 0.4f) ?: pickNoSize(array, eyeClassIdx)
            var bestGill = pick(array, gillClassIdx, 0.7f, 0.7f) ?: pickNoSize(array, gillClassIdx)

            // Pass 3: horizontal flip fallback (handles right-facing fish)
            if (bestEye == null || bestGill == null) {
                val flippedArray = runFlippedInference(frame)
                if (flippedArray != null) {
                    if (bestEye  == null) bestEye  = (pick(flippedArray, eyeClassIdx,  0.4f, 0.4f) ?: pickNoSize(flippedArray, eyeClassIdx))?.let  { mirrorBox(it) }
                    if (bestGill == null) bestGill = (pick(flippedArray, gillClassIdx, 0.7f, 0.7f) ?: pickNoSize(flippedArray, gillClassIdx))?.let { mirrorBox(it) }
                }
            }


            Pair(bestEye, bestGill)
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(null, null)
        }
    }

    /**
     * Convert a raw anchor index to a BoundingBox with normalized coordinates.
     */
    private fun anchorToBox(array: FloatArray, anchorIdx: Int, classIdx: Int, score: Float): BoundingBox? {
        if (anchorIdx < 0) return null

        var cx = array[anchorIdx]
        var cy = array[anchorIdx + numElements]
        var w = array[anchorIdx + numElements * 2]
        var h = array[anchorIdx + numElements * 3]

        // Normalize if model outputs pixel coordinates
        if (cx > 1.5f) cx /= tensorWidth.toFloat()
        if (cy > 1.5f) cy /= tensorHeight.toFloat()
        if (w > 1.5f) w /= tensorWidth.toFloat()
        if (h > 1.5f) h /= tensorHeight.toFloat()

        // Clamp to valid range
        cx = cx.coerceIn(0f, 1f)
        cy = cy.coerceIn(0f, 1f)
        w = w.coerceAtLeast(0.01f)   // ensure minimum box size
        h = h.coerceAtLeast(0.01f)

        var x1 = (cx - w / 2f).coerceIn(0f, 1f)
        var y1 = (cy - h / 2f).coerceIn(0f, 1f)
        var x2 = (cx + w / 2f).coerceIn(0f, 1f)
        var y2 = (cy + h / 2f).coerceIn(0f, 1f)

        // Enforce minimum box size on screen (>1% of image)
        if (x2 - x1 < 0.01f) x2 = (x1 + 0.01f).coerceAtMost(1f)
        if (y2 - y1 < 0.01f) y2 = (y1 + 0.01f).coerceAtMost(1f)
        if (x1 >= x2 || y1 >= y2) return null

        val clsName = if (classIdx >= 0 && classIdx < labels.size) labels[classIdx] else "Unknown"
        return BoundingBox(
            x1 = x1, y1 = y1, x2 = x2, y2 = y2,
            cx = cx, cy = cy, w = x2 - x1, h = y2 - y1,
            cnf = score, cls = classIdx, clsName = clsName
        )
    }

    // ── Flip-fallback helpers ─────────────────────────────────────────────────

    /** Run inference on a horizontally-flipped version of the frame. */
    private fun runFlippedInference(frame: Bitmap): FloatArray? {
        return try {
            val flippedMatrix = android.graphics.Matrix().apply { preScale(-1f, 1f) }
            val flipped = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, flippedMatrix, true)
            val resized = Bitmap.createScaledBitmap(flipped, tensorWidth, tensorHeight, false)
            val tensorImage = TensorImage(INPUT_IMAGE_TYPE)
            tensorImage.load(resized)
            val processedImage = imageProcessor.process(tensorImage)
            val output = TensorBuffer.createFixedSize(intArrayOf(1, numChannel, numElements), OUTPUT_IMAGE_TYPE)
            interpreter.run(processedImage.buffer, output.buffer)
            output.floatArray
        } catch (e: Exception) {
            null
        }
    }

    /** Mirror a normalized box horizontally: x1' = 1 - x2, x2' = 1 - x1. */
    private fun mirrorBox(box: BoundingBox): BoundingBox {
        return box.copy(
            x1 = 1f - box.x2,
            x2 = 1f - box.x1,
            cx = 1f - box.cx
        )
    }

    /** Flip-fallback helper: mirrors Python's two-pass strategy on the flipped array. */
    private fun pickBestFromArray(
        array: FloatArray, classIdx: Int,
        strictMaxW: Float, strictMaxH: Float
    ): BoundingBox? {
        val CONF_THRESHOLD = 0.05f
        // Pass A: strict size + conf threshold (Python's _pick on flipped)
        var bestScoreA = CONF_THRESHOLD
        var bestIdxA   = -1
        // Pass B: no size filter, no threshold gate (Python's _pick_no_size on flipped)
        var bestScoreB = -Float.MAX_VALUE
        var bestIdxB   = 0

        for (c in 0 until numElements) {
            val score = array[c + numElements * (4 + classIdx)]
            var w = array[c + numElements * 2]
            var h = array[c + numElements * 3]
            if (w > 1.5f) w /= tensorWidth.toFloat()
            if (h > 1.5f) h /= tensorHeight.toFloat()
            if (score > bestScoreB) { bestScoreB = score; bestIdxB = c }
            if (w < strictMaxW && h < strictMaxH && score > bestScoreA) { bestScoreA = score; bestIdxA = c }
        }

        return when {
            bestIdxA >= 0 -> anchorToBox(array, bestIdxA, classIdx, bestScoreA)
            bestScoreB > 0.001f -> anchorToBox(array, bestIdxB, classIdx, bestScoreB)
            else -> null
        }
    }

    fun detectSync(frame: Bitmap): List<BoundingBox>? {
        if (tensorWidth == 0 || tensorHeight == 0 || numChannel == 0 || numElements == 0) return null
        return try {
            val resizedBitmap = Bitmap.createScaledBitmap(frame, tensorWidth, tensorHeight, false)
            val tensorImage = TensorImage(INPUT_IMAGE_TYPE)
            tensorImage.load(resizedBitmap)
            val processedImage = imageProcessor.process(tensorImage)
            val output = TensorBuffer.createFixedSize(intArrayOf(1, numChannel, numElements), OUTPUT_IMAGE_TYPE)
            interpreter.run(processedImage.buffer, output.buffer)
            bestBox(output.floatArray)
        } catch (e: Exception) {
            null
        }
    }

    private fun bestBox(array: FloatArray) : List<BoundingBox>? {

        val boundingBoxes = mutableListOf<BoundingBox>()
        val arraySize = array.size

        for (c in 0 until numElements) {
            var maxConf = confidenceThreshold
            var maxIdx = -1
            var j = 4
            var arrayIdx = c + numElements * j
            while (j < numChannel){
                if (arrayIdx >= arraySize) break
                if (array[arrayIdx] > maxConf) {
                    maxConf = array[arrayIdx]
                    maxIdx = j - 4
                }
                j++
                arrayIdx += numElements
            }

            if (maxConf > confidenceThreshold) {
                val clsName = if (maxIdx >= 0 && maxIdx < labels.size) labels[maxIdx] else "Unknown"
                var cx = array[c] // 0
                var cy = array[c + numElements] // 1
                var w = array[c + numElements * 2]
                var h = array[c + numElements * 3]

                // Auto-normalize if model outputs pixel coordinates (0-tensorWidth) instead of 0-1
                if (cx > 1.5f || cy > 1.5f || w > 1.5f || h > 1.5f) {
                    cx /= tensorWidth.toFloat()
                    cy /= tensorHeight.toFloat()
                    w /= tensorWidth.toFloat()
                    h /= tensorHeight.toFloat()
                }
                var x1 = cx - (w/2F)
                var y1 = cy - (h/2F)
                var x2 = cx + (w/2F)
                var y2 = cy + (h/2F)
                
                // Clamp to [0, 1] instead of discarding, since eyes/gills can be at the edge
                x1 = Math.max(0F, Math.min(1F, x1))
                y1 = Math.max(0F, Math.min(1F, y1))
                x2 = Math.max(0F, Math.min(1F, x2))
                y2 = Math.max(0F, Math.min(1F, y2))
                
                if (x1 >= x2 || y1 >= y2) continue

                boundingBoxes.add(
                    BoundingBox(
                        x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                        cx = cx, cy = cy, w = w, h = h,
                        cnf = maxConf, cls = maxIdx, clsName = clsName
                    )
                )
            }
        }

        if (boundingBoxes.isEmpty()) return null

        return applyNMS(boundingBoxes)
    }

    /**
     * Soft-NMS: Instead of hard-removing overlapping boxes, decay their confidence
     * using a Gaussian penalty. This preserves valid detections of similar fish
     * near each other while still suppressing true duplicates.
     */
    private fun applyNMS(boxes: List<BoundingBox>): MutableList<BoundingBox> {
        val sorted = boxes.sortedByDescending { it.cnf }.toMutableList()
        val selected = mutableListOf<BoundingBox>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            selected.add(best)

            val iterator = sorted.listIterator()
            while (iterator.hasNext()) {
                val box = iterator.next()
                val iou = calculateIoU(best, box)
                // Gaussian decay: high IoU → big penalty, low IoU → almost no penalty
                // Apply NMS decay ONLY if they belong to the same class
                val decayedConf = if (best.cls == box.cls) {
                    box.cnf * Math.exp(-(iou * iou) / SOFT_NMS_SIGMA.toDouble()).toFloat()
                } else {
                    box.cnf
                }
                
                if (decayedConf < confidenceThreshold) {
                    iterator.remove()
                } else {
                    iterator.set(box.copy(cnf = decayedConf))
                }
            }
        }

        return selected
    }

    private fun calculateIoU(box1: BoundingBox, box2: BoundingBox): Float {
        val x1 = maxOf(box1.x1, box2.x1)
        val y1 = maxOf(box1.y1, box2.y1)
        val x2 = minOf(box1.x2, box2.x2)
        val y2 = minOf(box1.y2, box2.y2)
        val intersectionArea = maxOf(0F, x2 - x1) * maxOf(0F, y2 - y1)
        val box1Area = box1.w * box1.h
        val box2Area = box2.w * box2.h
        return intersectionArea / (box1Area + box2Area - intersectionArea)
    }

    interface DetectorListener {
        fun onEmptyDetect()
        fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long)
    }

    companion object {
        private const val INPUT_MEAN = 0f
        private const val INPUT_STANDARD_DEVIATION = 255f
        private val INPUT_IMAGE_TYPE = DataType.FLOAT32
        private val OUTPUT_IMAGE_TYPE = DataType.FLOAT32
        private const val IOU_THRESHOLD = 0.5F            // Lowered from 0.7 → suppress overlapping duplicates
        private const val SOFT_NMS_SIGMA = 0.5F           // Gaussian decay factor for Soft-NMS
    }
}