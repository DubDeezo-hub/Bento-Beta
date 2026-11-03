package com.example.pssender

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews

// ---------------------------------------------------------------------------------------------
// Quick access from outside the app: a Quick Settings tile (swipe down from the top) that runs the chain of the selected
// console, and a home-screen widget that shows whether the console answers and has a button for the same chain.
// Both open the app to run the chain, the same way a chain shortcut does (MainActivity, ACTION_RUN_CHAIN).
// ---------------------------------------------------------------------------------------------

/** The selected console (as saved), or null when none is saved. */
fun selectedConsole(ctx: Context): Profile? = try {
    val all = ProfileStore.load(ctx)
    val id = ProfileStore.activeId(ctx, all)
    all.firstOrNull { it.id == id }
} catch (_: Exception) {
    null
}

/** The chain the tile and the widget run: the selected console's automatic chain, else the first chain; null when none. */
fun quickChain(ctx: Context): Chain? {
    val chains = ChainStore.load(ctx)
    val auto = selectedConsole(ctx)?.let { AutoChain.get(ctx, it.id) }.orEmpty()
    return pickQuickChain(chains, auto)
}

/** [chains]'s chain with id [preferred] when there is one, else the first; null for no chains. */
fun pickQuickChain(chains: List<Chain>, preferred: String): Chain? = chains.firstOrNull { it.id == preferred } ?: chains.firstOrNull()

/** Opens the app and runs [chain] (or only opens the app when there is none). */
private fun runChainIntent(ctx: Context, chain: Chain?): Intent {
    val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    if (chain != null) i.setAction(ACTION_RUN_CHAIN).putExtra(EXTRA_CHAIN_ID, chain.id)
    return i
}

/** The Quick Settings tile: one tap runs the chain (see [quickChain]); its label is the chain's name. */
class ChainTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val chain = quickChain(this)
        tile.label = chain?.name ?: AppText.get(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = selectedConsole(this)?.name ?: ""
        tile.state = if (chain != null) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = runChainIntent(this, quickChain(this))
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (_: Exception) {
        }
    }
}

/** The home-screen widget: the console's name, whether it answers (looked at on each refresh), and the chain button. */
class StatusWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        Thread {
            try {
                val console = selectedConsole(ctx)
                val up = console != null && console.host.isNotBlank() && OnlineAlert.answers(console)
                for (id in ids) manager.updateAppWidget(id, views(ctx, console, up))
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun views(ctx: Context, console: Profile?, up: Boolean): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_status)
        val chain = quickChain(ctx)
        v.setTextViewText(R.id.widget_console, console?.name ?: AppText.get(R.string.app_name))
        v.setTextViewText(
            R.id.widget_state,
            AppText.get(if (up) R.string.widget_online else R.string.widget_offline, console?.type?.model ?: "")
        )
        v.setTextColor(R.id.widget_state, if (up) 0xFF7CDB8A.toInt() else 0xFFFF6B78.toInt())
        v.setTextViewText(R.id.widget_run, chain?.name ?: AppText.get(R.string.widget_open))
        v.setOnClickPendingIntent(
            R.id.widget_run,
            PendingIntent.getActivity(ctx, 1, runChainIntent(ctx, chain), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        )
        // Tapping the status looks again
        val refresh = Intent(ctx, StatusWidget::class.java).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, StatusWidget::class.java)))
        v.setOnClickPendingIntent(R.id.widget_state, PendingIntent.getBroadcast(ctx, 2, refresh, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        return v
    }

    companion object {
        /** Brings any widgets up to date (after a chain or console change). */
        fun refresh(ctx: Context) {
            try {
                val m = AppWidgetManager.getInstance(ctx)
                val ids = m.getAppWidgetIds(ComponentName(ctx, StatusWidget::class.java))
                if (ids.isNotEmpty()) {
                    ctx.sendBroadcast(Intent(ctx, StatusWidget::class.java).setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE).putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids))
                }
            } catch (_: Exception) {
            }
        }
    }
}
