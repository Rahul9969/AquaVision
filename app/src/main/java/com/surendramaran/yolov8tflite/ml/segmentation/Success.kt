package com.surendramaran.yolov8tflite.ml.segmentation
data class Success(
    val preProcessTime: Long,
    val interfaceTime: Long,
    val postProcessTime: Long,
    val results: List<SegmentationResult>
)