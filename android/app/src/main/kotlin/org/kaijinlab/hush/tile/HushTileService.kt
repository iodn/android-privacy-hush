package org.kaijinlab.hush.tile

import android.annotation.TargetApi
import android.os.Build
import android.service.quicksettings.Tile
import org.kaijinlab.hush.widget.HushWidget

@TargetApi(Build.VERSION_CODES.N)
class HushTileService : BaseTileService() {

    override val tag = "HushTileService"
    override val serviceName = "Hush Tile Service"

    override suspend fun executeAction() {
        // Quick Settings opens on the lock screen, where turning protection off would undo it for anyone holding the phone.
        if (preferenceManager.isGlobalPrivacyEnabled && isLocked) {
            unlockAndRun {
                HushWidget.toggleGlobalPrivacy(this, tag)
                updateTileState()
            }
            return
        }
        HushWidget.toggleGlobalPrivacy(this, tag)
    }

    override suspend fun updateTileStateInternal() {
        val tile = qsTile ?: return
        val isEnabled = preferenceManager.isGlobalPrivacyEnabled

        if (isEnabled) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Hush"
            tile.contentDescription = "Hush is enabled. Tap to disable."
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Hush"
            tile.contentDescription = "Hush is disabled. Tap to enable."
        }

        tile.updateTile()
    }
}
