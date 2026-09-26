package com.iamode.app.service.tile

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.iamode.app.core.di.ApplicationScope
import com.iamode.app.domain.usecase.ToggleIAModeUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Quick Settings tile: turn IA Mode on or off from the notification shade. */
@AndroidEntryPoint
class IAModeTileService : TileService() {

    @Inject lateinit var toggle: ToggleIAModeUseCase
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onStartListening() {
        scope.launch {
            val on = toggle.isOn()
            withContext(Dispatchers.Main) { render(on) }
        }
    }

    override fun onClick() {
        scope.launch {
            val on = toggle.toggle()
            withContext(Dispatchers.Main) { render(on) }
        }
    }

    private fun render(on: Boolean) {
        qsTile?.apply {
            state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "IA Mode"
            subtitle = if (on) "On" else "Off"
            updateTile()
        }
    }

    companion object {
        fun requestUpdate(context: Context) =
            TileService.requestListeningState(context, ComponentName(context, IAModeTileService::class.java))
    }
}
