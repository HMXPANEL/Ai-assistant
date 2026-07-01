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
}
