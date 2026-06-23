package com.voicecontrol.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

object AppLauncher {

    data class AppInfo(val name: String, val packageName: String)

    fun getInstalledApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfoList: List<ResolveInfo> = pm.queryIntentActivities(intent, 0)
        return resolveInfoList
            .map { ri ->
                AppInfo(
                    name = ri.loadLabel(pm).toString(),
                    packageName = ri.activityInfo.packageName
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    fun findAndLaunchApp(context: Context, query: String): String {
        val apps = getInstalledApps(context)
        val queryLower = query.lowercase().trim()

        val match = apps.firstOrNull { app ->
            app.name.lowercase().contains(queryLower) ||
            queryLower.contains(app.name.lowercase())
        }

        return if (match != null) {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(match.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                "Opening ${match.name}..."
            } else {
                "Found ${match.name} but couldn't launch it."
            }
        } else {
            "App not found on your device. Try 'show apps' to see all installed apps."
        }
    }
}
