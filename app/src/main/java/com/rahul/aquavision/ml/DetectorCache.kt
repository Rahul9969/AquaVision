package com.rahul.aquavision.ml

import android.content.Context

/**
 * Singleton cache for TFLite detection models.
 * Prevents expensive re-loading when the CameraFragment is re-created
 * due to navigation (bottom nav tab switches).
 */
object DetectorCache {

    @Volatile
    private var detector: Detector? = null

    @Volatile
    private var detectorNano: Detector? = null

    @Volatile
    private var detectorEyes: Detector? = null

    @Volatile
    private var isInitializing = false

    /**
     * Returns cached detectors or initializes them on the provided executor.
     * Thread-safe — only one initialization can run at a time.
     *
     * @param appContext  Application context (leak-safe)
     * @param modelPath   Path for the high-accuracy (small) model
     * @param labelsPath  Path for species labels
     * @param mainListener Detector listener for fish detection callbacks
     * @param eyesListener Detector listener for eye detection callbacks
     * @param onReady     Called on the calling thread when models are ready
     */
    fun getOrInit(
        appContext: Context,
        modelPath: String,
        labelsPath: String,
        mainListener: Detector.DetectorListener,
        eyesListener: Detector.DetectorListener,
        onReady: (main: Detector, nano: Detector, eyes: Detector) -> Unit
    ) {
        val d = detector
        val dn = detectorNano
        val de = detectorEyes

        // Already loaded — just rebind listeners and return
        if (d != null && dn != null && de != null) {
            d.setListener(mainListener)
            dn.setListener(mainListener)
            de.setListener(eyesListener)
            onReady(d, dn, de)
            return
        }

        // Prevent double init
        synchronized(this) {
            if (isInitializing) return
            isInitializing = true
        }

        // Load models (this runs on whatever thread the caller is on — should be background)
        try {
            val newDetector = Detector(appContext, modelPath, labelsPath, mainListener)
            val newNano = Detector(appContext, "model_nano.tflite", labelsPath, mainListener)
            val newEyes = Detector(appContext, "eyes_model.tflite", "eyes_labels.txt", eyesListener)

            detector = newDetector
            detectorNano = newNano
            detectorEyes = newEyes

            onReady(newDetector, newNano, newEyes)
        } finally {
            synchronized(this) { isInitializing = false }
        }
    }

    /**
     * Rebinds listeners without re-loading models.
     * Call this in onViewCreated to reconnect the fragment's UI callbacks.
     */
    fun rebindListeners(
        mainListener: Detector.DetectorListener,
        eyesListener: Detector.DetectorListener
    ) {
        detector?.setListener(mainListener)
        detectorNano?.setListener(mainListener)
        detectorEyes?.setListener(eyesListener)
    }

    /**
     * Release all models. Call only when the app is truly shutting down.
     */
    fun release() {
        detector?.close(); detector = null
        detectorNano?.close(); detectorNano = null
        detectorEyes?.close(); detectorEyes = null
    }
}
