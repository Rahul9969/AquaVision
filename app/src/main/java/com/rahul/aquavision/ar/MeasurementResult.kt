package com.rahul.aquavision.ar

import android.graphics.RectF

/**
 * Immutable result container for a completed AR fish measurement.
 *
 * Produced by [DepthProcessor] after depth point-cloud analysis,
 * or by hit-test two-point measurement.
 */
data class MeasurementResult(
    /** Fish length along the longest axis, in centimeters. */
    val lengthCm: Float,

    /** Fish width (perpendicular to length), in centimeters. */
    val widthCm: Float,

    /** Fish height / thickness above the table surface, in centimeters. */
    val heightCm: Float,

    /** Estimated volume via voxel integration, in cm³. */
    val volumeCm3: Float,

    /** Estimated weight using fish density (1.05 g/cm³), in grams. */
    val weightGrams: Float,

    /** Number of 3D points that contributed to the measurement. */
    val pointCount: Int,

    /** Number of depth frames accumulated for this measurement. */
    val framesUsed: Int,

    /**
     * Measurement method used:
     * - "RawDepth" — Raw Depth API with confidence filtering
     * - "Depth"    — Full (smoothed) Depth API
     * - "HitTest"  — Two-point anchor-based length-only measurement
     */
    val method: String,

    /** Screen-space bounding box of the fish (for overlay rendering), or null for hit-test. */
    val boundingBoxScreen: RectF? = null
)
