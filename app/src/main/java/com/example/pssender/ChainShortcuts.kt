package com.example.pssender // change to match your project's package name

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon

// ---------------------------------------------------------------------------------------------
// Chains on the app icon: press and hold the icon and the first chains are there, one tap each. A chain can also be put
// on the home screen. Tapping one opens the app and starts the chain (see MainActivity).
// ---------------------------------------------------------------------------------------------

/** What a shortcut of a chain asks the app to do. */
const val ACTION_RUN_CHAIN = "com.example.pssender.RUN_CHAIN"

/** The id of the chain, in the intent of its shortcut. */
const val EXTRA_CHAIN_ID = "chain_id"

/** How many chains show when the app icon is pressed and held (launchers show about four or five shortcuts). */
const val CHAINS_ON_ICON = 4

/**
 * Splits [chains] for a launcher that takes [maxPerActivity] shortcuts: the first ones go on the app icon, the others are
 * only kept up to date in case one of them was put on the home screen.
 */
fun shortcutSplit(chains: List<Chain>, maxPerActivity: Int): Pair<List<Chain>, List<Chain>> {
    val limit = minOf(CHAINS_ON_ICON, maxPerActivity).coerceAtLeast(0)
    return chains.take(limit) to chains.drop(limit)
}

object ChainShortcuts {
    private fun shortcutId(chain: String) = "chain-$chain"

    private fun info(ctx: Context, chain: Chain, rank: Int): ShortcutInfo =
        ShortcutInfo.Builder(ctx, shortcutId(chain.id))
            .setShortLabel(chain.name)
            .setLongLabel(chain.name)
            .setDisabledMessage(AppText.get(R.string.chain_err_unknown))
            .setIcon(Icon.createWithResource(ctx, R.drawable.ic_shortcut_chain))
            .setIntent(Intent(ctx, MainActivity::class.java).setAction(ACTION_RUN_CHAIN).putExtra(EXTRA_CHAIN_ID, chain.id))
            .setRank(rank)
            .build()

    /**
     * Shows the first chains on the app icon, and brings the names of the others in line (a chain that was put on the
     * home screen keeps working after it is renamed). Chains that were deleted can no longer be started from the home
     * screen: [removed] are their ids. Nothing here may stop the app, so a launcher that refuses is ignored.
     */
    fun publish(ctx: Context, chains: List<Chain>, removed: List<String> = emptyList()) {
        try {
            val manager = ctx.getSystemService(ShortcutManager::class.java) ?: return
            val (onIcon, others) = shortcutSplit(chains, manager.maxShortcutCountPerActivity)
            manager.dynamicShortcuts = onIcon.mapIndexed { i, c -> info(ctx, c, i) }
            if (others.isNotEmpty()) manager.updateShortcuts(others.mapIndexed { i, c -> info(ctx, c, onIcon.size + i) })
            if (removed.isNotEmpty()) {
                manager.disableShortcuts(removed.map { shortcutId(it) }, AppText.get(R.string.chain_err_unknown))
            }
        } catch (_: Exception) {
        }
    }

    /** Asks the launcher to put [chain] on the home screen. Returns false when the launcher can't do that. */
    fun pin(ctx: Context, chain: Chain): Boolean {
        return try {
            val manager = ctx.getSystemService(ShortcutManager::class.java) ?: return false
            if (!manager.isRequestPinShortcutSupported) return false
            manager.requestPinShortcut(info(ctx, chain, 0), null)
        } catch (_: Exception) {
            false
        }
    }
}
