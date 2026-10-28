package com.brekwol.daybreak.update

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import com.brekwol.daybreak.container

/**
 * Receives the result of an update install from PackageInstaller. When Android
 * needs the user to confirm, it opens the system install dialog.
 * Declared only in the GitHub build's manifest.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
            if (confirm != null) {
                try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return
                } catch (ignored: ActivityNotFoundException) {
                    // Fall through and report the failure.
                }
            }
        }
        context.container.updates.onInstallStatus(
            status = status,
            message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
        )
    }

    companion object {
        const val ACTION_STATUS = "com.brekwol.daybreak.action.UPDATE_INSTALL_STATUS"
    }
}
