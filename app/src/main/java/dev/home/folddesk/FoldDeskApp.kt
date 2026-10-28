package dev.home.folddesk

import android.app.Application
import android.content.ComponentCallbacks
import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper

class FoldDeskApp : Application() {
    lateinit var desk: DeskState
        private set

    private var wasInner = false

    override fun onCreate() {
        super.onCreate()
        desk = DeskState(this)
        Widgets.init(this)
        wasInner = isInnerScreen(resources.configuration)
        registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                val inner = isInnerScreen(newConfig)
                if (inner && !wasInner) onUnfolded()
                wasInner = inner
            }

            override fun onLowMemory() = Unit
        })
    }

    /**
     * If the cover screen was showing One UI Home when the phone opened, swap
     * in the desktop. Apps that were open stay where they are.
     */
    private fun onUnfolded() {
        if (!desk.coverUsesOneUi) return
        // Give the activity manager a moment to settle after the display switch.
        Handler(Looper.getMainLooper()).postDelayed({
            if (desk.foregroundPackage() == desk.coverLauncher) {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                )
            }
        }, 150)
    }

    companion object {
        /**
         * The Fold's inner display is sw875dp and nearly square; the cover screen
         * is sw480dp and tall. Go by shape too: a large display size or a changed
         * "Smallest width" can push the inner screen under 600dp.
         */
        fun isInnerScreen(config: Configuration): Boolean {
            if (config.smallestScreenWidthDp >= 600) return true
            val long = maxOf(config.screenWidthDp, config.screenHeightDp)
            val short = minOf(config.screenWidthDp, config.screenHeightDp)
            return short > 0 && long < short * MAX_INNER_ASPECT
        }

        /** Cover screens are about 2.3:1; inner screens about 1.1:1. */
        private const val MAX_INNER_ASPECT = 1.8f
    }
}
