package com.rahul.aquavision.ar

import android.graphics.RectF
import android.media.Image
import android.util.Log
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Depth-based 3D measurement engine for fish on a flat surface.
 *
 * Implements the recommended pattern from the official ARCore raw_depth_java sample:
 * - Acquires raw depth + confidence images with proper resource management
 * - Filters by confidence threshold before back-projection
 * - Reprojects 2D depth pixels into 3D world coordinates using scaled camera intrinsics
 * - Supports multi-frame point cloud accumulation for improved coverage
 * - Computes volume via voxel grid integration
 *
 * References:
 *   https://github.com/google-ar/arcore-android-sdk/tree/main/samples/raw_depth_java
 *   https://developers.google.com/ar/develop/depth
 */
class DepthProcessor {

    companion object {
        private const val TAG = "DepthProcessor"

        /** Fish tissue density in g/cm³ (standard freshwater fish average). */
        private const val FISH_DENSITY_G_CM3 = 1.05f

        /** Minimum confidence value (0-255) to include a depth pixel. */
        private const val CONFIDENCE_THRESHOLD = 128

        /** Minimum height above table surface (meters) to count as "fish". */
        private const val MIN_HEIGHT_M = 0.008f  // 0.8 cm

        /** Maximum height above table surface (meters) to count as "fish". */
        private const val MAX_HEIGHT_M = 0.30f   // 30 cm

        /** Maximum number of depth points to process per frame (performance cap). */
        private const val MAX_POINTS_PER_FRAME = 40000

        /** Voxel edge length in meters for volume integration. */
        private const val VOXEL_SIZE_M = 0.01f  // 1 cm

        /** Maximum total accumulated points across all frames. */
        private const val MAX_ACCUMULATED_POINTS = 120000
    }

    /** Accumulated 3D world-space points from multiple frames: [x, y, z, height_above_table]. */
    private val accumulatedPoints = mutableListOf<FloatArray>()

    /** The last processed raw depth timestamp to avoid reprocessing identical data. */
    private var lastDepthTimestamp: Long = -1

    /** Number of distinct depth frames that have been accumulated. */
    private var framesAccumulated: Int = 0

    /**
     * The method string indicating which depth mode is active.
     * Set during [configureSession].
     */
    var depthMethod: String = "HitTest"
        private set

    /**
     * Whether the device supports any form of depth.
     */
    var isDepthSupported: Boolean = false
        private set

    /**
     * Whether Raw Depth (with confidence) is available.
     */
    var isRawDepthSupported: Boolean = false
        private set

    // ── Session Configuration ────────────────────────────────────────────

    /**
     * Configure the AR session for optimal depth acquisition.
     * Must be called after session creation, before resume.
     *
     * @return The configured [Config] object.
     */
    fun configureSession(session: Session): Config {
        val config = session.config.apply {
            focusMode = Config.FocusMode.AUTO
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        }

        // Prefer Raw Depth for measurement accuracy
        isRawDepthSupported = session.isDepthModeSupported(Config.DepthMode.RAW_DEPTH_ONLY)
        val automaticSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)

        when {
            isRawDepthSupported -> {
                config.depthMode = Config.DepthMode.RAW_DEPTH_ONLY
                depthMethod = "RawDepth"
                isDepthSupported = true
                Log.d(TAG, "Depth mode: RAW_DEPTH_ONLY (best accuracy)")
            }
            automaticSupported -> {
                config.depthMode = Config.DepthMode.AUTOMATIC
                depthMethod = "Depth"
                isDepthSupported = true
                Log.d(TAG, "Depth mode: AUTOMATIC (smoothed)")
            }
            else -> {
                config.depthMode = Config.DepthMode.DISABLED
                depthMethod = "HitTest"
                isDepthSupported = false
                Log.d(TAG, "Depth mode: DISABLED (hit-test fallback)")
            }
        }

        session.configure(config)
        return config
    }

    // ── Point Cloud Acquisition ──────────────────────────────────────────

    /**
     * Acquire depth data from the current frame, filter by confidence,
     * and reproject into 3D world coordinates.
     *
     * This follows the official ARCore sample pattern:
     * 1. Acquire raw depth image + confidence image
     * 2. Scale camera intrinsics to depth-image resolution
     * 3. For each pixel with sufficient confidence, back-project to camera space
     * 4. Transform camera-space points to world space using the camera pose
     *
     * @param frame The current AR frame (must be TRACKING).
     * @param tablePlaneY The world-space Y coordinate of the detected table surface.
     * @return Number of new points added, or -1 if depth data was unavailable/stale.
     */
    fun acquireAndProcessDepth(frame: Frame, tablePlaneY: Float): Int {
        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) return -1

        // Acquire depth image (Raw or Full depending on config)
        var depthImage: Image? = null
        var confidenceImage: Image? = null

        try {
            depthImage = if (isRawDepthSupported) {
                frame.acquireRawDepthImage16Bits()
            } else {
                frame.acquireDepthImage16Bits()
            }

            // Check timestamp — skip if we already processed this exact depth frame
            val timestamp = depthImage.timestamp
            if (timestamp == lastDepthTimestamp) {
                depthImage.close()
                return -1
            }
            lastDepthTimestamp = timestamp

            // Acquire confidence image (only available with Raw Depth)
            if (isRawDepthSupported) {
                try {
                    confidenceImage = frame.acquireRawDepthConfidenceImage()
                } catch (e: NotYetAvailableException) {
                    Log.w(TAG, "Confidence image not yet available")
                }
            }

            val newPoints = processDepthImage(
                depthImage, confidenceImage, camera, tablePlaneY
            )

            framesAccumulated++
            Log.d(TAG, "Frame $framesAccumulated: added $newPoints points (total: ${accumulatedPoints.size})")
            return newPoints

        } catch (e: NotYetAvailableException) {
            Log.d(TAG, "Depth data not yet available")
            return -1
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring depth data", e)
            return -1
        } finally {
            // CRITICAL: Always close Image objects to prevent native memory leaks.
            // This follows the official ARCore sample's try-with-resources pattern.
            depthImage?.close()
            confidenceImage?.close()
        }
    }

    /**
     * Core depth processing: iterate pixels, filter by confidence,
     * back-project to 3D, filter by height above table.
     */
    private fun processDepthImage(
        depthImage: Image,
        confidenceImage: Image?,
        camera: Camera,
        tablePlaneY: Float
    ): Int {
        val depthW = depthImage.width
        val depthH = depthImage.height

        // ── Scale intrinsics from full camera resolution to depth-image resolution ──
        val intrinsics = camera.imageIntrinsics
        val imgDims = intrinsics.imageDimensions
        val scaleX = depthW.toFloat() / imgDims[0].toFloat()
        val scaleY = depthH.toFloat() / imgDims[1].toFloat()
        val fx = intrinsics.focalLength[0] * scaleX
        val fy = intrinsics.focalLength[1] * scaleY
        val cx = intrinsics.principalPoint[0] * scaleX
        val cy = intrinsics.principalPoint[1] * scaleY

        // ── Camera pose for world-space transformation ──
        val cameraPose = camera.pose
        val worldMatrix = FloatArray(16)
        cameraPose.toMatrix(worldMatrix, 0)

        // ── Depth buffer access ──
        val depthPlane = depthImage.planes[0]
        val depthBuffer = depthPlane.buffer.asShortBuffer()
        val depthRowStride = depthPlane.rowStride / 2  // rowStride is in bytes, shorts are 2 bytes

        // ── Confidence buffer access (if available) ──
        val confBuffer: java.nio.ByteBuffer? = confidenceImage?.planes?.get(0)?.buffer
        val confRowStride = confidenceImage?.planes?.get(0)?.rowStride ?: 0

        var newPointCount = 0

        for (v in 0 until depthH) {
            for (u in 0 until depthW) {
                if (accumulatedPoints.size >= MAX_ACCUMULATED_POINTS) break
                if (newPointCount >= MAX_POINTS_PER_FRAME) break

                // ── Confidence filter ──
                if (confBuffer != null) {
                    val confIdx = v * confRowStride + u
                    if (confIdx < confBuffer.limit()) {
                        val confidence = confBuffer.get(confIdx).toInt() and 0xFF
                        if (confidence < CONFIDENCE_THRESHOLD) continue
                    }
                }

                // ── Read depth value ──
                val depthIdx = v * depthRowStride + u
                if (depthIdx >= depthBuffer.limit()) continue
                val depthMm = depthBuffer.get(depthIdx).toInt() and 0xFFFF
                if (depthMm == 0 || depthMm > 3000) continue  // Skip invalid or too far (>3m)

                val depthM = depthMm / 1000f

                // ── Back-project to camera coordinates ──
                val camX = (u - cx) * depthM / fx
                val camY = (cy - v) * depthM / fy  // Y is inverted in camera space
                val camZ = -depthM                   // Depth is along negative Z

                // ── Transform to world coordinates ──
                val pointCamera = floatArrayOf(camX, camY, camZ, 1f)
                val pointWorld = FloatArray(4)
                android.opengl.Matrix.multiplyMV(pointWorld, 0, worldMatrix, 0, pointCamera, 0)

                // ── Filter by height above table ──
                val heightAboveTable = pointWorld[1] - tablePlaneY
                if (heightAboveTable in MIN_HEIGHT_M..MAX_HEIGHT_M) {
                    accumulatedPoints.add(
                        floatArrayOf(pointWorld[0], pointWorld[1], pointWorld[2], heightAboveTable)
                    )
                    newPointCount++
                }
            }
            if (newPointCount >= MAX_POINTS_PER_FRAME) break
        }

        return newPointCount
    }

    // ── Table Plane Detection ────────────────────────────────────────────

    fun findTablePlaneY(session: Session, frame: Frame, tapX: Float, tapY: Float): Float? {
        // Strategy 1: Hit-test at the tap point
        val hitResults = frame.hitTest(tapX, tapY)
        for (hit in hitResults) {
            val trackable = hit.trackable
            if (trackable is Plane &&
                trackable.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                trackable.trackingState == TrackingState.TRACKING
            ) {
                return hit.hitPose.ty()
            }
        }

        // Strategy 2: Use the largest tracked horizontal plane
        val bestPlane = session.getAllTrackables(Plane::class.java)
            .filter {
                it.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                it.trackingState == TrackingState.TRACKING
            }
            .maxByOrNull { it.extentX * it.extentZ }

        return bestPlane?.centerPose?.ty()
    }

    // ── Measurement Computation ──────────────────────────────────────────

    /**
     * Compute final measurements from the accumulated point cloud.
     *
     * Uses voxel grid integration for volume computation:
     * 1. Quantize all points into a 3D voxel grid (1cm³ cells)
     * 2. Count occupied voxels
     * 3. Multiply by voxel volume to get total volume
     *
     * @param viewWidth  GLSurfaceView width for screen-space bounding box projection.
     * @param viewHeight GLSurfaceView height.
     * @param camera     Current camera for projection matrix.
     * @return [MeasurementResult] or null if insufficient points.
     */
    fun computeMeasurements(
        viewWidth: Float,
        viewHeight: Float,
        camera: Camera?
    ): MeasurementResult? {
        if (accumulatedPoints.size < 100) {
            Log.w(TAG, "Insufficient points for measurement: ${accumulatedPoints.size}")
            return null
        }

        // ── Compute axis-aligned bounding box in world space ──
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var maxH = 0f

        // Screen-space bounds for overlay
        var minScreenX = Float.MAX_VALUE; var maxScreenX = -Float.MAX_VALUE
        var minScreenY = Float.MAX_VALUE; var maxScreenY = -Float.MAX_VALUE

        // Projection matrix for screen-space mapping
        val viewProjMatrix: FloatArray? = if (camera != null && viewWidth > 0 && viewHeight > 0) {
            val projMatrix = FloatArray(16)
            camera.getProjectionMatrix(projMatrix, 0, 0.1f, 100.0f)
            val viewMatrix = FloatArray(16)
            camera.getViewMatrix(viewMatrix, 0)
            val vp = FloatArray(16)
            android.opengl.Matrix.multiplyMM(vp, 0, projMatrix, 0, viewMatrix, 0)
            vp
        } else null

        // ── Voxel grid for volume computation ──
        // We use a HashSet of quantized (ix, iy, iz) to count occupied voxels.
        val occupiedVoxels = HashSet<Long>(accumulatedPoints.size)

        for (pt in accumulatedPoints) {
            val wx = pt[0]; val wy = pt[1]; val wz = pt[2]
            val heightAboveTable = pt[3]

            // AABB
            if (wx < minX) minX = wx
            if (wx > maxX) maxX = wx
            if (wz < minZ) minZ = wz
            if (wz > maxZ) maxZ = wz
            if (heightAboveTable > maxH) maxH = heightAboveTable

            // Voxel quantization: pack (ix, iy, iz) into a single Long
            val ix = (wx / VOXEL_SIZE_M).roundToInt()
            val iy = (wy / VOXEL_SIZE_M).roundToInt()
            val iz = (wz / VOXEL_SIZE_M).roundToInt()
            val voxelKey = (ix.toLong() shl 40) or ((iy.toLong() and 0xFFFFF) shl 20) or (iz.toLong() and 0xFFFFF)
            occupiedVoxels.add(voxelKey)

            // Project to screen space for bounding box
            if (viewProjMatrix != null) {
                val screenPt = projectToScreen(
                    floatArrayOf(wx, wy, wz), viewProjMatrix, viewWidth, viewHeight
                )
                if (screenPt != null) {
                    if (screenPt[0] < minScreenX) minScreenX = screenPt[0]
                    if (screenPt[0] > maxScreenX) maxScreenX = screenPt[0]
                    if (screenPt[1] < minScreenY) minScreenY = screenPt[1]
                    if (screenPt[1] > maxScreenY) maxScreenY = screenPt[1]
                }
            }
        }

        // ── Dimensions ──
        val dx = maxX - minX
        val dz = maxZ - minZ
        val lengthM = max(dx, dz)
        val widthM = min(dx, dz)

        val lengthCm = lengthM * 100f
        val widthCm = widthM * 100f
        val heightCm = maxH * 100f

        // ── Volume via voxel count ──
        val voxelVolumeCm3 = VOXEL_SIZE_M * VOXEL_SIZE_M * VOXEL_SIZE_M * 1_000_000f  // m³ → cm³
        val volumeCm3 = occupiedVoxels.size * voxelVolumeCm3

        // ── Estimated weight ──
        val weightGrams = volumeCm3 * FISH_DENSITY_G_CM3

        // ── Screen bounding box ──
        val screenBox = if (minScreenX < maxScreenX && minScreenY < maxScreenY) {
            RectF(minScreenX, minScreenY, maxScreenX, maxScreenY)
        } else null

        Log.d(TAG, "Measurement: L=${lengthCm}cm W=${widthCm}cm H=${heightCm}cm " +
                "V=${volumeCm3}cm³ W=${weightGrams}g points=${accumulatedPoints.size} " +
                "voxels=${occupiedVoxels.size} frames=$framesAccumulated")

        return MeasurementResult(
            lengthCm = lengthCm,
            widthCm = widthCm,
            heightCm = heightCm,
            volumeCm3 = volumeCm3,
            weightGrams = weightGrams,
            pointCount = accumulatedPoints.size,
            framesUsed = framesAccumulated,
            method = depthMethod,
            boundingBoxScreen = screenBox
        )
    }

    // ── Utilities ────────────────────────────────────────────────────────

    /**
     * Project a 3D world-space point to 2D screen coordinates.
     * Returns null if the point is behind the camera.
     */
    private fun projectToScreen(
        point3d: FloatArray,
        viewProj: FloatArray,
        width: Float,
        height: Float
    ): FloatArray? {
        val vec4 = floatArrayOf(point3d[0], point3d[1], point3d[2], 1.0f)
        val result = FloatArray(4)
        android.opengl.Matrix.multiplyMV(result, 0, viewProj, 0, vec4, 0)

        if (result[3] <= 0) return null  // Behind camera

        val w = result[3]
        val ndcX = result[0] / w
        val ndcY = result[1] / w

        val screenX = ((ndcX + 1.0f) / 2.0f) * width
        val screenY = ((1.0f - ndcY) / 2.0f) * height
        return floatArrayOf(screenX, screenY)
    }

    /** Reset all accumulated data for a new measurement. */
    fun reset() {
        accumulatedPoints.clear()
        lastDepthTimestamp = -1
        framesAccumulated = 0
    }

    /** Current number of accumulated depth frames. */
    val frameCount: Int get() = framesAccumulated

    /** Current number of accumulated 3D points. */
    val pointCount: Int get() = accumulatedPoints.size

    /** Whether we have enough data for a valid measurement. */
    val hasEnoughData: Boolean get() = accumulatedPoints.size >= 100
}
