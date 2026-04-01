package com.surendramaran.yolov8tflite.ml.segmentation

import android.graphics.Bitmap

data class AnalysisResult(
    val original: Bitmap,
    val overlay: Bitmap?,
    val description: String
)