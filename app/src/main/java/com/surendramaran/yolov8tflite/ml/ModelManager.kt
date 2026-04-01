package com.surendramaran.yolov8tflite.ml

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

class ModelManager(private val context: Context) {

    private val modelFileName = "llama.task"

    fun getModelPath(): String {
        return File(context.filesDir, modelFileName).absolutePath
    }

    fun isModelReady(): Boolean {
        val file = File(context.filesDir, modelFileName)
        return file.exists() && file.length() > 0
    }

    fun copyModelFromUri(uri: Uri, onProgress: (Int) -> Unit): Boolean {
        return try {
            val resolver = context.contentResolver

            // 1. Get File Size for progress calculation
            var fileSize = -1L
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                }
            }

            // 2. Copy File with Progress
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(File(context.filesDir, modelFileName)).use { output ->
                    val buffer = ByteArray(8 * 1024) // 8KB buffer
                    var bytes = input.read(buffer)
                    var totalBytes = 0L
                    var lastProgress = 0

                    while (bytes >= 0) {
                        output.write(buffer, 0, bytes)
                        totalBytes += bytes

                        if (fileSize > 0) {
                            val progress = ((totalBytes * 100) / fileSize).toInt()
                            // Update only if percentage changed to avoid UI lag
                            if (progress > lastProgress) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        }
                        bytes = input.read(buffer)
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}