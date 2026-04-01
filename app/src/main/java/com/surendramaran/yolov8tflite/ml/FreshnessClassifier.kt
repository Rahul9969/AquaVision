package com.surendramaran.yolov8tflite.ml

import android.content.Context
import android.graphics.Bitmap
import com.surendramaran.yolov8tflite.R
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

class FreshnessClassifier(
    private val context: Context,
    private val modelPath: String,
    private val labelPath: String,
    private val listener: FreshnessListener
) {
    private var interpreter: Interpreter? = null
    private var labels = mutableListOf<String>()
    private var tensorWidth = 224
    private var tensorHeight = 224
    private var inputDataType: DataType = DataType.FLOAT32
    private var outputDataType: DataType = DataType.FLOAT32

    init {
        setupInterpreter()
        loadLabels()
    }

    private fun setupInterpreter() {
        val model = FileUtil.loadMappedFile(context, modelPath)
        val options = Interpreter.Options()
        interpreter = Interpreter(model, options)

        val inputTensor = interpreter?.getInputTensor(0)
        val outputTensor = interpreter?.getOutputTensor(0)

        if (inputTensor != null) {
            tensorWidth = inputTensor.shape()[1]
            tensorHeight = inputTensor.shape()[2]
            inputDataType = inputTensor.dataType()
        }

        if (outputTensor != null) {
            outputDataType = outputTensor.dataType()
        }
    }

    private fun loadLabels() {
        val labelContent = FileUtil.loadLabels(context, labelPath)
        labels.addAll(labelContent)
    }

    fun classify(bitmap: Bitmap) {
        if (interpreter == null) return

        // 1. Create ImageProcessor based on Model Type
        val imageProcessorBuilder = ImageProcessor.Builder()

        // Add Normalization only if model is FLOAT32
        if (inputDataType == DataType.FLOAT32) {
            imageProcessorBuilder.add(NormalizeOp(0f, 1f))
            imageProcessorBuilder.add(CastOp(DataType.FLOAT32))
        } else if (inputDataType == DataType.UINT8) {
            // Quantized models usually expect 0-255, no normalization needed
            imageProcessorBuilder.add(CastOp(DataType.UINT8))
        }

        val imageProcessor = imageProcessorBuilder.build()

        // 2. Preprocess the image
        // Resize first to match model dimensions
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, tensorWidth, tensorHeight, true)

        // Load into TensorImage with correct type
        val tensorImage = TensorImage(inputDataType)
        tensorImage.load(resizedBitmap)
        val processedImage = imageProcessor.process(tensorImage)

        // 3. Prepare output buffer
        // Shape: [1, num_classes]
        val outputBuffer = TensorBuffer.createFixedSize(
            intArrayOf(1, labels.size),
            outputDataType
        )

        // 4. Run inference
        interpreter?.run(processedImage.buffer, outputBuffer.buffer)

        // 5. Process results
        // If output is Quantized (UINT8), we need to convert to Float for probability
        val rawResults = outputBuffer.floatArray

        // Find max score
        val maxScore = rawResults.maxOrNull() ?: 0f
        val maxIndex = rawResults.indexOfFirst { it == maxScore }

        // Calculate confidence percentage
        // For Quantized, 255 = 100%, 0 = 0%. For Float, 1.0 = 100%
        val confidence = if (outputDataType == DataType.UINT8) {
            maxScore / 255.0f
        } else {
            maxScore
        }

        val result = if (maxIndex != -1 && maxIndex < labels.size) labels[maxIndex] else context.getString(R.string.unknown)

        listener.onResult(result, confidence)
    }

    fun close() {
        interpreter?.close()
    }

    interface FreshnessListener {
        fun onResult(className: String, confidence: Float)
    }
}