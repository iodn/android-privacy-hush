package org.kaijinlab.hush.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.kaijinlab.hush.util.DeferredActionWork

class PrivacyTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            intent.data?.lastPathSegment?.let { DeferredActionWork.dispatchAlarm(context, it) }
        } catch (error: Exception) {
            android.util.Log.e("hush-Timer", "Could not dispatch the privacy timer", error)
        }
    }
}
