package com.rahul.aquavision

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import androidx.work.*
import android.animation.ValueAnimator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.rahul.aquavision.data.SyncWorker
import com.rahul.aquavision.databinding.ActivityMainBinding
import com.rahul.aquavision.utils.NetworkHelper
import com.rahul.aquavision.utils.UserUtils
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var networkHelper: NetworkHelper
    private lateinit var statusBanner: TextView

    // UI State
    private var isNetworkConnected = false
    private var isSyncing = false
    private var syncResultMessage: String? = null
    private var syncResultSuccess: Boolean? = null

    // MAGIC NAV STATE
    private var currentIndex = 0
    private val navItems by lazy {
        listOf(
            binding.navItem1 to R.id.cameraFragment,
            binding.navItem2 to R.id.volumeFragment,
            binding.navItem3 to R.id.historyFragment,
            binding.navItem4 to R.id.oceanDataFragment,
            binding.navItem5 to R.id.moreFragment
        )
    }
    private val navIcons by lazy {
        listOf(binding.navIcon1, binding.navIcon2, binding.navIcon3, binding.navIcon4, binding.navIcon5)
    }
    private val navTexts by lazy {
        listOf(binding.navText1, binding.navText2, binding.navText3, binding.navText4, binding.navText5)
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        val prefs = newBase.getSharedPreferences("AquaVisionPrefs", android.content.Context.MODE_PRIVATE)
        val lang = prefs.getString("app_language", "en") ?: "en"
        val locale = java.util.Locale(lang)
        java.util.Locale.setDefault(locale)
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        val context = newBase.createConfigurationContext(config)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        statusBanner = findViewById(R.id.networkStatusBanner)
        networkHelper = NetworkHelper(this)

        // --- NAVIGATION SETUP ---
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        val navGraph = navController.navInflater.inflate(R.navigation.nav_graph)

        // 1. CONDITIONAL START DESTINATION
        if (!UserUtils.isProfileSet(this)) {
            navGraph.setStartDestination(R.id.languageFragment)
        } else {
            navGraph.setStartDestination(R.id.cameraFragment)
        }
        navController.graph = navGraph

        // 2. Hide Bottom Nav on Onboarding Screens
        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (destination.id == R.id.languageFragment || destination.id == R.id.profileFragment) {
                binding.magicBottomNav.visibility = View.GONE
            } else {
                binding.magicBottomNav.visibility = View.VISIBLE
                
                // Keep UI in sync if navController changes destination from back stack
                val index = getIndexForDestination(destination.id)
                if (index != -1 && index != currentIndex) {
                    animateMagicNav(index)
                }
            }
        }

        setupMagicNav(navController)


        // --- NETWORK & SYNC LOGIC ---
        networkHelper.observe(this) { isConnected ->
            isNetworkConnected = isConnected
            if (isConnected) {
                scheduleDataSync()
            }
            updateStatusBanner()
        }

        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData("HistoryUploadWork")
            .observe(this) { workInfos ->
                if (workInfos.isNullOrEmpty()) return@observe
                val workInfo = workInfos.find { it.state == WorkInfo.State.RUNNING } ?: workInfos.last()

                when (workInfo.state) {
                    WorkInfo.State.RUNNING -> {
                        isSyncing = true
                        syncResultMessage = workInfo.progress.getString("status") ?: "Syncing data..."
                        syncResultSuccess = null
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        isSyncing = false
                        syncResultMessage = "Data Synced Successfully"
                        syncResultSuccess = true
                        clearResultAfterDelay()
                    }
                    WorkInfo.State.FAILED -> {
                        isSyncing = false
                        val error = workInfo.outputData.getString("error_message") ?: "Sync Failed"
                        syncResultMessage = "Sync Failed: $error"
                        syncResultSuccess = false
                        clearResultAfterDelay(4000)
                    }
                    else -> { isSyncing = false }
                }
                updateStatusBanner()
            }
    }

    private fun clearResultAfterDelay(delay: Long = 3000) {
        binding.root.postDelayed({
            if (!isSyncing) {
                syncResultMessage = null
                syncResultSuccess = null
                updateStatusBanner()
            }
        }, delay)
    }

    private fun updateStatusBanner() {
        if (!isNetworkConnected) {
            statusBanner.text = "Offline Mode"
            statusBanner.setBackgroundColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            statusBanner.visibility = View.VISIBLE
        } else if (isSyncing) {
            statusBanner.text = syncResultMessage ?: "Syncing..."
            statusBanner.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
            statusBanner.visibility = View.VISIBLE
        } else if (syncResultMessage != null) {
            statusBanner.text = syncResultMessage
            val color = if (syncResultSuccess == true) android.R.color.holo_green_dark else android.R.color.holo_red_dark
            statusBanner.setBackgroundColor(ContextCompat.getColor(this, color))
            statusBanner.visibility = View.VISIBLE
        } else {
            statusBanner.text = "Online"
            statusBanner.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            statusBanner.visibility = View.VISIBLE
        }
    }

    private fun scheduleDataSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequest.Builder(SyncWorker::class.java)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            "HistoryUploadWork",
            ExistingWorkPolicy.APPEND,
            syncRequest
        )
    }

    // --- MAGIC NAVIGATION LOGIC ---
    private fun setupMagicNav(navController: androidx.navigation.NavController) {
        // Initial setup
        binding.root.post {
            animateMagicNav(currentIndex, animate = false)
        }

        navItems.forEachIndexed { index, pair ->
            pair.first.setOnClickListener {
                if (index != currentIndex && index != getIndexForDestination(navController.currentDestination?.id)) {
                    animateMagicNav(index, animate = true)

                    val destinationId = pair.second
                    val shouldRestore = destinationId != R.id.moreFragment

                    val builder = NavOptions.Builder()
                        .setLaunchSingleTop(true)
                        .setRestoreState(shouldRestore)
                        .setEnterAnim(R.anim.fade_in)
                        .setExitAnim(R.anim.fade_out)
                        .setPopEnterAnim(R.anim.fade_in)
                        .setPopExitAnim(R.anim.fade_out)
                        .setPopUpTo(navController.graph.startDestinationId, false, true)

                    navController.navigate(destinationId, null, builder.build())
                }
            }
        }
    }

    private fun getIndexForDestination(destId: Int?): Int {
        return navItems.indexOfFirst { it.second == destId }
    }

    private fun animateMagicNav(index: Int, animate: Boolean = true) {
        currentIndex = index

        val duration = if (animate) 300L else 0L

        // Screen width & item width
        val containerWidth = binding.navItemsContainer.width.toFloat()
        if (containerWidth == 0f) {
            // Layout not ready, apply alpha without translation
            for (i in 0 until 5) {
                val alphaVal = if (i == index) 1f else 0.4f
                navIcons[i].alpha = alphaVal
                navTexts[i].alpha = alphaVal
            }
            return
        }

        val itemWidth = containerWidth / 5f
        
        // Target X: Center of the nav item, minus half of the Tubelight container's width
        val targetIndicatorX = (itemWidth * index) + (itemWidth / 2f) - (binding.floatingTubelight.width / 2f)
        
        if (animate) {
            binding.floatingTubelight.animate()
                .translationX(targetIndicatorX)
                .setDuration(duration)
                .setInterpolator(OvershootInterpolator(1.2f))
                .start()
        } else {
            binding.floatingTubelight.translationX = targetIndicatorX
        }

        // Animate Icons and Texts Alpha
        for (i in 0 until 5) {
            val isSelected = (i == index)
            val icon = navIcons[i]
            val text = navTexts[i]

            val targetIconAlpha = if (isSelected) 1f else 0.4f

            if (animate) {
                icon.animate()
                    .alpha(targetIconAlpha)
                    .setDuration(duration)
                    .start()
                    
                text.animate()
                    .alpha(targetIconAlpha)
                    .setDuration(duration)
                    .start()
            } else {
                icon.alpha = targetIconAlpha
                text.alpha = targetIconAlpha
            }
        }
    }
}