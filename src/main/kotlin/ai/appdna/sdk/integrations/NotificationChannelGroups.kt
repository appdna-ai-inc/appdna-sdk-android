package ai.appdna.sdk.integrations

import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Creates a notification channel group (`data.channel_group`) if it does not exist yet.
 *
 * Channel groups are API 26, but `NotificationManager.getNotificationChannelGroup(String)` is API 28.
 * It used to be called under an API 26 guard only, so on Android 8.0 / 8.1 the first push that named a
 * `channel_group` threw `NoSuchMethodError` inside the messaging service and the app crashed. Below
 * API 28 the existing groups are read from `notificationChannelGroups` (API 26) instead.
 */
internal object NotificationChannelGroups {

    @RequiresApi(Build.VERSION_CODES.O)
    fun ensure(manager: NotificationManager, groupId: String) {
        val exists = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.getNotificationChannelGroup(groupId) != null
        } else {
            manager.notificationChannelGroups.any { it.id == groupId }
        }
        if (!exists) manager.createNotificationChannelGroup(NotificationChannelGroup(groupId, groupId))
    }
}
