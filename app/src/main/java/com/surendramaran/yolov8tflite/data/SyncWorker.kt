package com.surendramaran.yolov8tflite.data

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cloudinary.Cloudinary
import com.cloudinary.utils.ObjectUtils
import com.google.firebase.firestore.FirebaseFirestore
import com.surendramaran.yolov8tflite.utils.UserUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val dbHelper = DatabaseHelper(context)
    private val firestore by lazy { FirebaseFirestore.getInstance() }

    private val CLOUD_NAME = "dvvvv5mwt"
    private val API_KEY = "712361699253361"
    private val API_SECRET = "SaKXxjE_ekI28s_C61Ns1-DtymA"

    override suspend fun doWork(): Result {
        return try {
            val cloudinary = Cloudinary(ObjectUtils.asMap(
                "cloud_name", CLOUD_NAME,
                "api_key", API_KEY,
                "api_secret", API_SECRET,
                "secure", true
            ))

            // 1. Upload User Profile Photo if dirty
            if (UserUtils.isPfpDirty(applicationContext)) {
                uploadUserProfile(cloudinary)
            }

            // 2. Fetch User Details to attach
            val userId = UserUtils.getUserId(applicationContext)
            val userName = UserUtils.getUserName(applicationContext)
            val userPfp = UserUtils.getPfpUrl(applicationContext) ?: ""

            // 3. Process Logs
            val unsyncedList = dbHelper.getUnsyncedLogs()
            if (unsyncedList.isEmpty()) return Result.success()

            for ((index, item) in unsyncedList.withIndex()) {
                val progress = "Syncing ${index + 1}/${unsyncedList.size}: ${item.title}"
                setProgress(workDataOf("status" to progress, "is_syncing" to true))

                uploadLogItem(cloudinary, item, userId, userName, userPfp)

                dbHelper.markAsSynced(item.id)
            }

            setProgress(workDataOf("status" to "Sync Complete", "is_syncing" to false))
            Result.success()

        } catch (e: Exception) {
            Log.e("SyncWorker", "Sync failed: ${e.message}", e)
            Result.failure(workDataOf("error_message" to (e.message ?: "Unknown error")))
        }
    }

    private suspend fun uploadUserProfile(cloudinary: Cloudinary) {
        val path = UserUtils.getPfpPath(applicationContext) ?: return
        val file = File(path)
        if (file.exists()) {
            setProgress(workDataOf("status" to "Updating Profile Photo...", "is_syncing" to true))
            try {
                val url = uploadImage(cloudinary, file)
                UserUtils.updateCloudUrl(applicationContext, url)
            } catch (e: Exception) {
                Log.e("SyncWorker", "Failed to upload PFP", e)
            }
        }
    }

    private suspend fun uploadLogItem(cloudinary: Cloudinary, item: HistoryItem, userId: String, userName: String, userPfp: String) {
        val imagePaths = item.imagePath.split("|").filter { it.isNotEmpty() }
        val imageUrls = mutableListOf<String>()

        for ((imgIndex, path) in imagePaths.withIndex()) {
            val file = File(path)
            if (file.exists()) {
                val url = uploadImage(cloudinary, file)
                imageUrls.add(url)
            }
        }

        // Add to Firestore with User Details
        val logData = hashMapOf(
            "timestamp" to item.timestamp,
            "title" to item.title,
            "details" to item.details,
            "type" to item.type,
            "location" to hashMapOf(
                "lat" to item.lat,
                "lng" to item.lng,
                "name" to item.placeName
            ),
            "image_urls" to imageUrls,
            // --- ATTACHED USER INFO ---
            "user" to hashMapOf(
                "id" to userId,
                "name" to userName,
                "pfp_url" to userPfp
            )
        )

        firestore.collection("logs").add(logData).await()
    }

    private suspend fun uploadImage(cloudinary: Cloudinary, file: File): String = withContext(Dispatchers.IO) {
        val params = ObjectUtils.asMap(
            "folder", "fish_app_history",
            "resource_type", "image"
        )
        val result = cloudinary.uploader().upload(file, params)
        return@withContext result["secure_url"] as String
    }
}