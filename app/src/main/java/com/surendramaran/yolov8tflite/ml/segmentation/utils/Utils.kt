package com.surendramaran.yolov8tflite.ml.segmentation.utils

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.CompositePageTransformer
import androidx.viewpager2.widget.MarginPageTransformer
import androidx.viewpager2.widget.ViewPager2
import java.io.File

object Utils {

    fun List<DoubleArray>.toBitmap(): Bitmap {
        val height = this.size
        val width = if (height > 0) this[0].size else 0
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in this.indices) {
            for (x in this[y].indices) {
                val color = if (this[y][x] > 0) Color.WHITE else Color.BLACK
                bitmap.setPixel(x, y, color)
            }
        }
        return bitmap
    }

    fun Context.getBitmapFromAsset(fileName: String): Bitmap? {
        return try {
            val assetManager = this.assets
            val inputStream = assetManager.open(fileName)
            BitmapFactory.decodeStream(inputStream)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun List<Array<FloatArray>>.clone(): List<Array<FloatArray>> {
        return this.map { array -> array.map { it.clone() }.toTypedArray() }
    }

    fun createImageFile(context: Context): File {
        val uupDir = File(context.filesDir, "surendramaran.com")
        if (!uupDir.exists()) {
            uupDir.mkdir()
        }
        return File.createTempFile("${System.currentTimeMillis()}", ".jpg", uupDir)
    }

    fun getBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val bitmap = ImageDecoder.decodeBitmap(source)
            if (bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e("BitmapError", "Failed to load bitmap from URI", e)
            null
        }
    }

    // NEW: Helper to resize bitmaps to prevent OOM
    fun resizeBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height

        // Only resize if the image is larger than the max dimension
        if (originalWidth <= maxDimension && originalHeight <= maxDimension) {
            return bitmap
        }

        val ratio = originalWidth.toFloat() / originalHeight.toFloat()
        val newWidth: Int
        val newHeight: Int

        if (ratio > 1) {
            newWidth = maxDimension
            newHeight = (maxDimension / ratio).toInt()
        } else {
            newHeight = maxDimension
            newWidth = (maxDimension * ratio).toInt()
        }

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    fun getCameraId(cameraManager: CameraManager): String {
        val cameraIds = cameraManager.cameraIdList
        for (id in cameraIds) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
        }
        return ""
    }

    fun ViewPager2.addCarouselEffect() {
        clipChildren = false
        clipToPadding = false
        offscreenPageLimit = 3
        (getChildAt(0) as RecyclerView).overScrollMode = RecyclerView.OVER_SCROLL_NEVER

        val compositePageTransformer = CompositePageTransformer()
        compositePageTransformer.addTransformer(MarginPageTransformer((8 * Resources.getSystem().displayMetrics.density).toInt()))
        setPageTransformer(compositePageTransformer)
    }

    fun rotateImageIfRequired(context: Context, bitmap: Bitmap, photoUri: Uri): Bitmap {
        val inputStream = context.contentResolver.openInputStream(photoUri)
        val exif = inputStream?.let { ExifInterface(it) }

        val orientation = exif?.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )

        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(bitmap, 90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(bitmap, 180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(bitmap, 270f)
            else -> bitmap // No rotation needed
        }
    }

    fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}