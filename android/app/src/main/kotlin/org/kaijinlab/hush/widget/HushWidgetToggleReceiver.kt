package org.kaijinlab.hush.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.kaijinlab.hush.util.PreferenceManager
import org.kaijinlab.hush.util.isScreenCurrentlyLocked

/**
 * Handles the widget's tap-to-toggle action only.
 *
 * Kept separate from HushWidget (the AppWidgetProvider) and marked
 * android:exported="false" in the manifest, so only this app's own widget
 * tap (a PendingIntent it built itself) can reach it. HushWidget
 * itself stays exported, because Android needs to reach it to redraw the
 * widget, but redrawing carries no risk the way toggling protection does.
 */
class HushWidgetToggleReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "HushWidgetToggle"
        const val ACTION_TOGGLE_PRIVACY = "org.kaijinlab.hush.TOGGLE_PRIVACY"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TOGGLE_PRIVACY) {
            return
        }

        Log.d(TAG, "Widget toggle privacy action received")

        try {
            // A widget host on the lock screen must not let anyone holding the phone turn protection off.
            if (PreferenceManager.getInstance(context).isGlobalPrivacyEnabled && isScreenCurrentlyLocked(context, TAG)) {
                Log.w(TAG, "Phone is locked - not turning protection off from the widget")
                return
            }
            HushWidget.toggleGlobalPrivacy(context, TAG)
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling privacy from widget", e)
        }
    }
}
