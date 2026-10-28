package com.lnu.volumelockr.plus.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.navigation.NavigationBarView
import com.lnu.volumelockr.plus.R
import com.lnu.volumelockr.plus.databinding.ActivityMainBinding
import com.lnu.volumelockr.plus.util.NavigationAnimationUtils
import com.lnu.volumelockr.plus.util.SecurityUtils

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val uiMode = getSystemService(android.content.Context.UI_MODE_SERVICE) as android.app.UiModeManager
        if (uiMode.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) {
            startActivity(android.content.Intent(this, TvMainActivity::class.java))
            finish()
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupNavigation()
        setupWindowInsets()
    }

    override fun onStart() {
        super.onStart()

        binding.unlockButton?.setOnClickListener {
            if (!SecurityUtils.isAppUnlocked && (SecurityUtils.isPasswordProtected(this) || SecurityUtils.isBiometricEnabled(this))) {
                SecurityUtils.authenticate(
                    this,
                    onSuccess = {
                        SecurityUtils.isAppUnlocked = true
                        binding.lockOverlay?.visibility = android.view.View.GONE
                    },
                    onCancel = {
                        // User cancelled or dismissed the dialog. The overlay remains visible.
                        // They can tap the unlock button to try again.
                    }
                )
            }
        }

        if (!SecurityUtils.isAppUnlocked && (SecurityUtils.isPasswordProtected(this) || SecurityUtils.isBiometricEnabled(this))) {
            binding.lockOverlay?.visibility = android.view.View.VISIBLE
            // Automatically trigger the authentication dialog when the app starts and is locked
            binding.unlockButton?.performClick()
        } else {
            binding.lockOverlay?.visibility = android.view.View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
    }

    private var isDndDialogShowing = false
    private var activeDndDialog: androidx.appcompat.app.AlertDialog? = null

    override fun onPause() {
        super.onPause()
        binding.toolbar.dismissPopupMenus()
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onDestroy() {
        if (::binding.isInitialized) binding.toolbar.dismissPopupMenus()
        activeDndDialog?.dismiss()
        activeDndDialog = null
        themeAnimator?.cancel()
        themeAnimator = null
        bannerRadiusAnimator?.cancel()
        bannerRadiusAnimator = null
        super.onDestroy()
    }

    private var hasPromptedRuntimePerms = false

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        checkPermissions()
    }

    private var isBannerCollapsed = false
    private var bannerRadiusAnimator: android.animation.ValueAnimator? = null

    private fun updateBannerCollapsedState(animate: Boolean = true) {
        val banner = binding.permissionBanner ?: return
        val root = binding.root as? android.view.ViewGroup ?: return
        val density = resources.displayMetrics.density

        val targetRadius = if (isBannerCollapsed) {
            BANNER_RADIUS_COLLAPSED_DP * density
        } else {
            BANNER_RADIUS_EXPANDED_DP * density
        }

        if (animate) {
            val transition = android.transition.TransitionSet().apply {
                ordering = android.transition.TransitionSet.ORDERING_TOGETHER
                duration = BANNER_ANIM_DURATION_MS
                interpolator = createFastOutSlowInInterpolator()
                addTransition(android.transition.ChangeBounds())
                addTransition(android.transition.Fade())
            }
            android.transition.TransitionManager.beginDelayedTransition(root, transition)

            bannerRadiusAnimator?.cancel()
            val startRadius = banner.radius
            bannerRadiusAnimator = android.animation.ValueAnimator.ofFloat(startRadius, targetRadius).apply {
                duration = BANNER_ANIM_DURATION_MS
                interpolator = createFastOutSlowInInterpolator()
                addUpdateListener { animator ->
                    banner.radius = animator.animatedValue as Float
                }
                start()
            }
        } else {
            bannerRadiusAnimator?.cancel()
            banner.radius = targetRadius
        }

        if (isBannerCollapsed) {
            binding.permissionBannerTextContainer?.visibility = android.view.View.GONE
            binding.permissionBannerButton?.visibility = android.view.View.GONE
            binding.permissionBannerToggle?.visibility = android.view.View.GONE

            val padH = (BANNER_PAD_COLLAPSED_H_DP * density).toInt()
            val padV = (BANNER_PAD_COLLAPSED_V_DP * density).toInt()
            binding.permissionBannerContent?.setPadding(padH, padV, padH, padV)

            binding.permissionBannerIcon?.layoutParams = binding.permissionBannerIcon?.layoutParams?.apply {
                width = (BANNER_ICON_COLLAPSED_DP * density).toInt()
                height = (BANNER_ICON_COLLAPSED_DP * density).toInt()
            }
        } else {
            binding.permissionBannerTextContainer?.visibility = android.view.View.VISIBLE
            binding.permissionBannerButton?.visibility = android.view.View.VISIBLE
            binding.permissionBannerToggle?.visibility = android.view.View.VISIBLE
            binding.permissionBannerToggle?.setImageResource(R.drawable.ic_expand_less)

            val padH = (BANNER_PAD_EXPANDED_H_DP * density).toInt()
            val padV = (BANNER_PAD_EXPANDED_V_DP * density).toInt()
            binding.permissionBannerContent?.setPadding(padH, padV, padH, padV)

            binding.permissionBannerIcon?.layoutParams = binding.permissionBannerIcon?.layoutParams?.apply {
                width = (BANNER_ICON_EXPANDED_DP * density).toInt()
                height = (BANNER_ICON_EXPANDED_DP * density).toInt()
            }
        }
    }

    private fun checkPermissions() {
        if (::navController.isInitialized && navController.currentDestination?.id != R.id.volumeSliderFragment) {
            binding.permissionBanner?.visibility = android.view.View.GONE
            return
        }

        val uiModeManager = getSystemService(android.content.Context.UI_MODE_SERVICE) as android.app.UiModeManager
        val isTv = uiModeManager.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

        // Prompt system dialog once for runtime permissions on Android 13+
        if (!hasPromptedRuntimePerms) {
            hasPromptedRuntimePerms = true
            checkPostNotificationsPermission()
        }

        val missingNames = mutableListOf<String>()

        // 1. DND Policy Permission
        val notificationManager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val isDndMissing = !isTv && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && !notificationManager.isNotificationPolicyAccessGranted
        if (isDndMissing) {
            missingNames.add(getString(R.string.perm_dnd))
        }

        // 2. Notification Permission
        var isNotificationMissing = false
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                isNotificationMissing = true
                missingNames.add(getString(R.string.perm_post_notifications))
            }
        }

        // 3. Nearby Wi-Fi Permission
        var isNearbyMissing = false
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.NEARBY_WIFI_DEVICES
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                isNearbyMissing = true
                missingNames.add(getString(R.string.perm_nearby_wifi))
            }
        }

        // 4. Local Network Permission (Android 17+)
        var isLocalNetworkMissing = false
        if (android.os.Build.VERSION.SDK_INT >= 37) { // API 37 is Android 17
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    "android.permission.ACCESS_LOCAL_NETWORK"
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                isLocalNetworkMissing = true
                missingNames.add(getString(R.string.perm_local_network))
            }
        }

        // Update Banner UI
        binding.permissionBanner?.let { banner ->
            if (missingNames.isNotEmpty()) {
                banner.visibility = android.view.View.VISIBLE
                val separator = getString(R.string.permission_separator)
                val namesStr = missingNames.joinToString(separator)
                binding.permissionBannerMessage?.text = getString(R.string.missing_permission_prefix, namesStr)

                updateBannerCollapsedState(animate = false)

                banner.setOnClickListener {
                    if (isBannerCollapsed) {
                        isBannerCollapsed = false
                        updateBannerCollapsedState()
                    }
                }

                binding.permissionBannerToggle?.setOnClickListener {
                    isBannerCollapsed = !isBannerCollapsed
                    updateBannerCollapsedState()
                }

                binding.permissionBannerTitle?.setOnClickListener {
                    isBannerCollapsed = !isBannerCollapsed
                    updateBannerCollapsedState()
                }

                binding.permissionBannerButton?.setOnClickListener {
                    if (isDndMissing) {
                        try {
                            val intent = android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                            startActivity(intent)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    if (isNotificationMissing || isNearbyMissing || isLocalNetworkMissing) {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            val permsToReq = mutableListOf<String>()
                            if (isNotificationMissing) permsToReq.add(android.Manifest.permission.POST_NOTIFICATIONS)
                            if (isNearbyMissing) permsToReq.add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
                            if (isLocalNetworkMissing && android.os.Build.VERSION.SDK_INT >= 37) {
                                permsToReq.add("android.permission.ACCESS_LOCAL_NETWORK")
                            }

                            val shouldShowRationale = permsToReq.any {
                                androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(this, it)
                            }

                            if (!shouldShowRationale) {
                                openAppSettings()
                            } else {
                                androidx.core.app.ActivityCompat.requestPermissions(
                                    this,
                                    permsToReq.toTypedArray(),
                                    101
                                )
                            }
                        }
                    }
                }
            } else {
                banner.visibility = android.view.View.GONE
            }
        }
    }

    private fun openAppSettings() {
        try {
            val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun checkPostNotificationsPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val perms = mutableListOf<String>()
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.NEARBY_WIFI_DEVICES
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                perms.add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            if (android.os.Build.VERSION.SDK_INT >= 37) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        this,
                        "android.permission.ACCESS_LOCAL_NETWORK"
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    perms.add("android.permission.ACCESS_LOCAL_NETWORK")
                }
            }
            if (perms.isNotEmpty()) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this,
                    perms.toTypedArray(),
                    101
                )
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.options, menu)
        
        val uiModeManager = getSystemService(android.content.Context.UI_MODE_SERVICE) as android.app.UiModeManager
        if (uiModeManager.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) {
            menu.findItem(R.id.about)?.isVisible = false
        }
        
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.about -> {
                startActivity(android.content.Intent(this, AboutActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupNavigation() {
        setSupportActionBar(binding.toolbar)
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.fragment_container_view) as NavHostFragment
        navController = navHostFragment.navController
        val appBarConfiguration = AppBarConfiguration(
            setOf(R.id.volumeSliderFragment, R.id.settingsFragment)
        )
        binding.toolbar.setupWithNavController(navController, appBarConfiguration)

        val navView: NavigationBarView? = binding.bottomNavigation ?: binding.navigationRail
        navView?.setupWithNavController(navController)

        navView?.setOnItemReselectedListener { item ->
            val view = navView.findViewById<View>(item.itemId)
            NavigationAnimationUtils.animateNavigationItem(item.itemId, view)
        }
        
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val view = navView?.findViewById<View>(destination.id)
            NavigationAnimationUtils.animateNavigationItem(destination.id, view)
            
            if (destination.id == R.id.tvRemoteFragment) {
                applyThemeColors(isTvRemote = true)
            } else {
                applyThemeColors(isTvRemote = false)
            }

            if (destination.id == R.id.volumeSliderFragment) {
                checkPermissions()
            } else {
                binding.permissionBanner?.visibility = View.GONE
            }
        }
        
        val uiModeManager = getSystemService(android.content.Context.UI_MODE_SERVICE) as android.app.UiModeManager
        if (uiModeManager.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) {
            (navView as? View)?.visibility = View.GONE
        }
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { v, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars()
                    or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, 0)
            WindowInsetsCompat.CONSUMED
        }

        binding.bottomNavigation?.let { view ->
            ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
                val bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
                )
                v.setPadding(bars.left, 0, bars.right, bars.bottom)
                WindowInsetsCompat.CONSUMED
            }
        }

        binding.navigationRail?.let { view ->
            ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
                val bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                        or WindowInsetsCompat.Type.displayCutout()
                )
                v.setPadding(bars.left, bars.top, 0, bars.bottom)
                WindowInsetsCompat.CONSUMED
            }
        }
    }

    private var themeAnimator: android.animation.ValueAnimator? = null

    @Suppress("DEPRECATION")
    private fun applyThemeColors(isTvRemote: Boolean) {
        val themeContext = if (isTvRemote) {
            android.view.ContextThemeWrapper(this, R.style.ThemeOverlay_App_RoyalPurple)
        } else {
            this
        }
        
        val targetSurface = com.google.android.material.color.MaterialColors.getColor(themeContext, com.google.android.material.R.attr.colorSurface, android.graphics.Color.BLACK)
        val targetOnSurface = com.google.android.material.color.MaterialColors.getColor(themeContext, com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.WHITE)
        val colorSecondaryContainer = com.google.android.material.color.MaterialColors.getColor(themeContext, com.google.android.material.R.attr.colorSecondaryContainer, android.graphics.Color.GRAY)
        val colorOnSecondaryContainer = com.google.android.material.color.MaterialColors.getColor(themeContext, com.google.android.material.R.attr.colorOnSecondaryContainer, android.graphics.Color.BLACK)
        val colorOnSurfaceVariant = com.google.android.material.color.MaterialColors.getColor(themeContext, com.google.android.material.R.attr.colorOnSurfaceVariant, android.graphics.Color.GRAY)
        
        val typedValue = android.util.TypedValue()
        val hasSurfaceContainer = themeContext.theme.resolveAttribute(com.google.android.material.R.attr.colorSurfaceContainer, typedValue, true)
        val targetBottomNavBg = if (hasSurfaceContainer) typedValue.data else targetSurface

        binding.toolbar.setTitleTextColor(targetOnSurface)
        binding.toolbar.navigationIcon?.setTint(targetOnSurface)

        val navView = binding.bottomNavigation ?: binding.navigationRail
        if (navView != null) {
            val iconTintList = android.content.res.ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(-android.R.attr.state_checked)
                ),
                intArrayOf(colorOnSecondaryContainer, colorOnSurfaceVariant)
            )
            val textColorList = android.content.res.ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(-android.R.attr.state_checked)
                ),
                intArrayOf(targetOnSurface, colorOnSurfaceVariant)
            )
            
            if (navView is com.google.android.material.bottomnavigation.BottomNavigationView) {
                navView.itemActiveIndicatorColor = android.content.res.ColorStateList.valueOf(colorSecondaryContainer)
                navView.itemIconTintList = iconTintList
                navView.itemTextColor = textColorList
            } else if (navView is com.google.android.material.navigationrail.NavigationRailView) {
                navView.itemActiveIndicatorColor = android.content.res.ColorStateList.valueOf(colorSecondaryContainer)
                navView.itemIconTintList = iconTintList
                navView.itemTextColor = textColorList
            }
        }

        val initialSurface = (binding.root.background as? android.graphics.drawable.ColorDrawable)?.color ?: window.statusBarColor
        val initialBottomNavBg = window.navigationBarColor

        themeAnimator?.cancel()
        themeAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            val argbEvaluator = android.animation.ArgbEvaluator()
            addUpdateListener { animator ->
                val fraction = animator.animatedFraction
                val currentSurface = argbEvaluator.evaluate(fraction, initialSurface, targetSurface) as Int
                val currentBottomNavBg = argbEvaluator.evaluate(fraction, initialBottomNavBg, targetBottomNavBg) as Int

                binding.root.setBackgroundColor(currentSurface)
                binding.fragmentContainerView.setBackgroundColor(currentSurface)
                window.decorView.setBackgroundColor(currentSurface)
                binding.appBarLayout.setBackgroundColor(currentSurface)
                binding.toolbar.setBackgroundColor(currentSurface)
                window.statusBarColor = currentSurface
                window.navigationBarColor = currentBottomNavBg

                if (navView is com.google.android.material.bottomnavigation.BottomNavigationView) {
                    navView.setBackgroundColor(currentBottomNavBg)
                } else if (navView is com.google.android.material.navigationrail.NavigationRailView) {
                    navView.setBackgroundColor(currentBottomNavBg)
                }
            }
            start()
        }

        val isLightSurface = androidx.core.graphics.ColorUtils.calculateLuminance(targetSurface) > 0.5
        val isLightBottomNavBg = androidx.core.graphics.ColorUtils.calculateLuminance(targetBottomNavBg) > 0.5
        val windowInsetsController = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        windowInsetsController.isAppearanceLightStatusBars = isLightSurface
        windowInsetsController.isAppearanceLightNavigationBars = isLightBottomNavBg
    }

    companion object {
        private const val BANNER_ANIM_DURATION_MS = 250L
        private const val BANNER_ICON_COLLAPSED_DP = 16
        private const val BANNER_ICON_EXPANDED_DP = 24
        private const val BANNER_RADIUS_COLLAPSED_DP = 8f
        private const val BANNER_RADIUS_EXPANDED_DP = 16f
        private const val BANNER_PAD_COLLAPSED_H_DP = 6
        private const val BANNER_PAD_COLLAPSED_V_DP = 4
        private const val BANNER_PAD_EXPANDED_H_DP = 12
        private const val BANNER_PAD_EXPANDED_V_DP = 8

        private const val INTERPOLATOR_X1 = 0.4f
        private const val INTERPOLATOR_Y1 = 0.0f
        private const val INTERPOLATOR_X2 = 0.2f
        private const val INTERPOLATOR_Y2 = 1.0f

        fun createFastOutSlowInInterpolator() = android.view.animation.PathInterpolator(
            INTERPOLATOR_X1,
            INTERPOLATOR_Y1,
            INTERPOLATOR_X2,
            INTERPOLATOR_Y2
        )
    }
}
