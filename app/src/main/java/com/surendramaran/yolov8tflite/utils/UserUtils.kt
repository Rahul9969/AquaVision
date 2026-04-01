package com.surendramaran.yolov8tflite.utils

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

object UserUtils {
    private const val PREF_NAME = "user_prefs"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_PFP_PATH = "pfp_path"
    private const val KEY_PFP_URL = "pfp_cloud_url"
    private const val KEY_IS_DIRTY = "is_pfp_dirty"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getUserId(context: Context): String {
        val prefs = getPrefs(context)
        var id = prefs.getString(KEY_USER_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_USER_ID, id).apply()
        }
        return id!!
    }

    fun getUserName(context: Context): String = getPrefs(context).getString(KEY_USER_NAME, "Fisherman") ?: "Fisherman"

    fun getPfpPath(context: Context): String? = getPrefs(context).getString(KEY_PFP_PATH, null)

    fun getPfpUrl(context: Context): String? = getPrefs(context).getString(KEY_PFP_URL, null)

    fun isProfileSet(context: Context): Boolean = getPrefs(context).contains(KEY_USER_NAME)

    fun isPfpDirty(context: Context): Boolean = getPrefs(context).getBoolean(KEY_IS_DIRTY, false)

    fun saveProfile(context: Context, name: String, localPfpPath: String?) {
        val prefs = getPrefs(context)
        val oldPath = prefs.getString(KEY_PFP_PATH, null)

        val editor = prefs.edit()
        editor.putString(KEY_USER_NAME, name)

        if (localPfpPath != null && localPfpPath != oldPath) {
            editor.putString(KEY_PFP_PATH, localPfpPath)
            editor.putBoolean(KEY_IS_DIRTY, true)
        }

        if (!prefs.contains(KEY_USER_ID)) {
            editor.putString(KEY_USER_ID, UUID.randomUUID().toString())
        }

        editor.apply()
    }

    fun updateCloudUrl(context: Context, url: String) {
        getPrefs(context).edit()
            .putString(KEY_PFP_URL, url)
            .putBoolean(KEY_IS_DIRTY, false)
            .apply()
    }
}