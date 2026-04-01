package com.surendramaran.yolov8tflite.data

object Constants {
    const val MODEL_PATH = "model.tflite"
    const val LABELS_PATH = "labels.txt"
    const val SEG_MODEL_PATH = "seg_model.tflite" // New

    const val COIN_MODEL_PATH = "coin_model_float16.tflite"

    // Fishing Zone Backend API
    // For emulator: use 10.0.2.2 (maps to host machine's localhost)
    // For physical device on same WiFi: use your machine's local IP (e.g., 192.168.1.x)
    // For production: use your deployed URL (e.g., https://your-app.railway.app)
    @JvmField
    var FISHING_ZONE_API_BASE_URL = "https://fishing-zone-backend.onrender.com"
}