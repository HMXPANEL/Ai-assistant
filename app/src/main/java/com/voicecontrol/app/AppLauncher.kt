package com.voicecontrol.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.util.*

class AppLauncher(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager

    data class AppInfo(
        val packageName: String,
        val label: String,
        val icon: android.graphics.drawable.Drawable? = null
    )

    fun getAllLaunchableApps(): List<AppInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = packageManager.queryIntentActivities(intent, PackageManager.GET_META_DATA)
        val apps = mutableListOf<AppInfo>()
        for (resolveInfo in resolveInfos) {
            val packageName = resolveInfo.activityInfo.packageName
            val label = resolveInfo.loadLabel(packageManager).toString()
            val icon = resolveInfo.loadIcon(packageManager)
            apps.add(AppInfo(packageName, label, icon))
        }
        apps.sortBy { it.label.lowercase() }
        return apps
    }

    fun findAppByName(query: String): AppInfo? {
        val apps = getAllLaunchableApps()
        val lowerQuery = query.lowercase(Locale.getDefault())
        return apps.firstOrNull { it.label.lowercase(Locale.getDefault()).contains(lowerQuery) }
    }

    fun launchApp(packageName: String): Boolean {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        return if (launchIntent != null) {
            context.startActivity(launchIntent)
            true
        } else {
            false
        }
    }

    fun launchAppByName(appName: String): Boolean {
        val app = findAppByName(appName)
        return if (app != null) {
            launchApp(app.packageName)
        } else {
            false
        }
    }

    fun openAppSettings(packageName: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun isAppInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}