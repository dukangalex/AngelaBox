package io.nekohasekai.sfa.bg

import android.app.KeyguardManager
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.constant.Status

@RequiresApi(24)
class TileService :
    TileService(),
    ServiceConnection.Callback {
    private val connection = ServiceConnection(this, this)

    override fun onServiceStatusChanged(status: Status) {
        applyTile(status)
    }

    override fun onStartListening() {
        super.onStartListening()
        applyTile(connection.status)
        connection.connect()
    }

    override fun onStopListening() {
        connection.disconnect()
        super.onStopListening()
    }

    override fun onClick() {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguardManager.isKeyguardLocked) {
            unlockAndRun {
                toggleService()
            }
        } else {
            toggleService()
        }
    }

    private fun applyTile(status: Status) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.app_name)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_brand)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (status == Status.Started) {
                getString(R.string.status_started)
            } else {
                ""
            }
        }
        tile.state = when (status) {
            Status.Started -> Tile.STATE_ACTIVE
            Status.Stopped -> Tile.STATE_INACTIVE
            else -> Tile.STATE_UNAVAILABLE
        }
        tile.updateTile()
    }

    private fun toggleService() {
        when (connection.status) {
            Status.Stopped -> BoxService.start()
            Status.Started -> BoxService.stop()
            else -> {}
        }
    }
}
