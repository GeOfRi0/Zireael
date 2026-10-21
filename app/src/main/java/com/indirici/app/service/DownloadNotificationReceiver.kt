package com.indirici.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.indirici.app.IndiriciApplication

class DownloadNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext as? IndiriciApplication ?: return
        when (intent?.action) {
            ACTION_PAUSE_ALL -> {
                app.queueCoordinator.pauseAllActive()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE_ALL = "com.indirici.app.action.PAUSE_ALL"
    }
}
