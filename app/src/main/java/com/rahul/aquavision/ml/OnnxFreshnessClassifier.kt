package com.rahul.aquavision.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import kotlin.math.exp

class OnnxFreshnessClassifier(context: Context) {
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private val TEMPERATURE = 1.8f

    init {
        try {
            env = OrtEnvironment.getEnvironment()
            val bytes = context.assets.open("freshness_sim.onnx").readBytes()
            session = env?.createSession(bytes, OrtSession.SessionOptions())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun analyzeFreshnessTTA(bitmap: Bitmap): FloatArray? {
        if (session == null) return null

        // 5 TTA variants matching Python's analyze_freshness_tta exactly:
        // 1. original, 2. horizontal flip, 3. 90° rotation,
        // 4. slightly brighter (alpha=1.1, beta=10), 5. slightly darker (alpha=0.9, beta=-10)
        val variants = listOf(
            bitmap,
            flipBitmap(bitmap),
            rotateBitmap(bitmap),
            adjustBrightness(bitmap, 1.1f, 10f),   // slightly brighter
            adjustBrightness(bitmap, 0.9f, -10f)    // slightly darker
        )

        val results = mutableListOf<FloatArray>()
        for (variant in variants) {
            val res = singleFreshness(variant)
            if (res != null) results.add(res)
        }

        if (results.isEmpty()) return null

        val avg = FloatArray(3) { 0f }
        for (res in results) {
            avg[0] = avg[0] + res[0]
            avg[1] = avg[1] + res[1]
            avg[2] = avg[2] + res[2]
        }
        avg[0] = avg[0] / results.size
        avg[1] = avg[1] / results.size
        avg[2] = avg[2] / results.size
        return avg
    }

    private fun singleFreshness(bitmap: Bitmap): FloatArray? {
        try {
            val resized = Bitmap.createScaledBitmap(bitmap, 224, 224, true)
            val floatBuffer = allocateBuffer(resized)
            
            val inputName = session?.inputNames?.iterator()?.next() ?: return null
            val shape = longArrayOf(1, 3, 224, 224)
            val tensor = OnnxTensor.createTensor(env, floatBuffer, shape)
            
            val result = session?.run(mapOf(inputName to tensor))
            val output = result?.get(0)?.value as? Array<FloatArray>
            
            tensor.close()
            result?.close()
            
            val logits = output?.get(0) ?: return null
            
            // Softmax with temperature
            val maxLogit = logits.maxOrNull() ?: 0f
            var sumExp = 0f
            val expLogits = FloatArray(logits.size)
            for (i in logits.indices) {
                expLogits[i] = exp((logits[i] - maxLogit) / TEMPERATURE)
                sumExp += expLogits[i]
            }
            
            for (i in expLogits.indices) {
                expLogits[i] /= sumExp
            }
            
            return expLogits
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun allocateBuffer(bitmap: Bitmap): FloatBuffer {
        val floatBuffer = FloatBuffer.allocate(3 * 224 * 224)
        val pixels = IntArray(224 * 224)
        bitmap.getPixels(pixels, 0, 224, 0, 0, 224, 224)

        // ImageNet normalization
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)

        // Channels first layout
        for (c in 0..2) {
            for (i in 0 until 224 * 224) {
                val pixel = pixels[i]
                val value = when (c) {
                    0 -> ((pixel shr 16) and 0xFF) / 255.0f // R
                    1 -> ((pixel shr 8) and 0xFF) / 255.0f  // G
                    else -> (pixel and 0xFF) / 255.0f       // B
                }
                floatBuffer.put(c * 224 * 224 + i, (value - mean[c]) / std[c])
            }
        }
        floatBuffer.rewind()
        return floatBuffer
    }

    // --- Preprocessing Helpers ---
    fun preprocessCrop(bitmap: Bitmap): Bitmap {
        var mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        
        // Convert to RGB (Bitmap is ARGB)
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2RGB)
        
        mat = normaliseLighting(mat)
        mat = sharpenImage(mat)
        
        val resultBitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGB2RGBA)
        Utils.matToBitmap(mat, resultBitmap)
        return resultBitmap
    }

    private fun normaliseLighting(imgRgb: Mat): Mat {
        val imgBgr = Mat()
        Imgproc.cvtColor(imgRgb, imgBgr, Imgproc.COLOR_RGB2BGR)
        
        val lab = Mat()
        Imgproc.cvtColor(imgBgr, lab, Imgproc.COLOR_BGR2Lab)
        
        val channels = ArrayList<Mat>()
        Core.split(lab, channels)
        
        val clahe = Imgproc.createCLAHE(2.0, Size(4.0, 4.0))
        clahe.apply(channels[0], channels[0])
        
        Core.merge(channels, lab)
        Imgproc.cvtColor(lab, imgBgr, Imgproc.COLOR_Lab2BGR)
        Imgproc.cvtColor(imgBgr, imgRgb, Imgproc.COLOR_BGR2RGB)
        return imgRgb
    }

    private fun sharpenImage(imgRgb: Mat): Mat {
        val blur = Mat()
        Imgproc.GaussianBlur(imgRgb, blur, Size(0.0, 0.0), 2.0)
        
        val result = Mat()
        Core.addWeighted(imgRgb, 1.5, blur, -0.5, 0.0, result)
        return result
    }

    // Simple augmentation fallbacks
    private fun flipBitmap(bitmap: Bitmap): Bitmap {
        val matrix = android.graphics.Matrix()
        matrix.preScale(-1f, 1f)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun rotateBitmap(bitmap: Bitmap): Bitmap {
        val matrix = android.graphics.Matrix()
        matrix.postRotate(90f)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
    
    /**
     * Matches Python's cv2.convertScaleAbs(img, alpha=alpha, beta=beta)
     * Adjusts brightness/contrast: pixel = clamp(alpha * pixel + beta, 0, 255)
     */
    private fun adjustBrightness(bitmap: Bitmap, alpha: Float, beta: Float): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        val result = Mat()
        mat.convertTo(result, -1, alpha.toDouble(), beta.toDouble())
        val outBitmap = Bitmap.createBitmap(result.cols(), result.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(result, outBitmap)
        mat.release()
        result.release()
        return outBitmap
    }

    fun close() {
        session?.close()
        env?.close()
    }
}
