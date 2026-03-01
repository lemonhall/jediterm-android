package com.lemonhall.jediterm.sample.imebridge

import android.content.Context
import android.content.Intent
import android.util.Log

class ImeBroadcastDispatcher(private val context: Context) {
    companion object {
        private const val TAG = "ImeBroadcastDispatcher"
        private const val ACTION = "com.lsl.lemonhall.fcitx5.action.APPLY_PROJECT_META"
        private const val EXTRA_KEY = "meta_json"
        private const val RELEASE_PACKAGE = "com.lsl.lemonhall.fcitx5"
        private const val DEBUG_PACKAGE = "com.lsl.lemonhall.fcitx5.debug"
    }

    fun dispatch(metaJson: String): Boolean {
        val targetPackage = selectTargetPackage() ?: run {
            Log.w(TAG, "No IME package installed (checked: $RELEASE_PACKAGE, $DEBUG_PACKAGE)")
            return false
        }

        return try {
            val intent = Intent(ACTION)
                .setPackage(targetPackage)
                .putExtra(EXTRA_KEY, metaJson)
            context.sendBroadcast(intent)
            Log.i(TAG, "✓ Broadcast sent successfully")
            Log.i(TAG, "  Target: $targetPackage")
            Log.i(TAG, "  Action: $ACTION")
            Log.i(TAG, "  Payload: $metaJson")
            true
        } catch (e: Exception) {
            Log.e(TAG, "✗ Failed to send broadcast", e)
            false
        }
    }

    private fun selectTargetPackage(): String? {
        val pm = context.packageManager
        return when {
            isPackageInstalled(pm, RELEASE_PACKAGE) -> RELEASE_PACKAGE
            isPackageInstalled(pm, DEBUG_PACKAGE) -> DEBUG_PACKAGE
            else -> null
        }
    }

    private fun isPackageInstalled(pm: android.content.pm.PackageManager, packageName: String): Boolean {
        return try {
            pm.getPackageInfo(packageName, 0)
            Log.d(TAG, "✓ Package found: $packageName")
            true
        } catch (e: Exception) {
            Log.d(TAG, "✗ Package not found: $packageName (${e.message})")
            false
        }
    }
}
