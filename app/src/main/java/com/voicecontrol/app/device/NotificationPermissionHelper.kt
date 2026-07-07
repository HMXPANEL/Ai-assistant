package com.voicecontrol.app.device

import android.content.Context
import androidx.core.app.NotificationManagerCompat

object NotificationPermissionHelper {
    fun isNotificationAccessGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
}
