package com.example.pssender

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------------------------------------
// What the Memory tab saves: cheats (with what finds them again after a restart), watched addresses, the undo
// history's steps, and how cheats are kept per game and written to and read from text.
// ---------------------------------------------------------------------------------------------

/** A saved address: a name, where it is, what kind of value, and the value to write or keep there. */
data class Cheat(
    val id: String,
    val name: String,
    val address: Long,
    val type: ValueType,
    val value: String,
    val frozen: Boolean = false,
    /** Cheats with the same group are listed together ("" is no group). */
    val group: String = "",
    /** The bytes around the address, to find it again after a restart (hex), the bytes that count ("1"/"0"), where the value is. */
    val sigHex: String = "",
    val sigMask: String = "",
    val sigAt: Int = 0,
    /** A pointer path from the eboot to the value (see [PointerPath.encode]); "" when there is none. */
    val ptr: String = ""
) {
    val signature: Signature? get() = if (sigHex.isEmpty()) null else Signature.parse(sigHex, sigMask, sigAt)
    val pointer: PointerPath? get() = PointerPath.decode(ptr)

    /** Whether "Find again" can find it after a restart (by its bytes or by its pointer path). */
    val findable: Boolean get() = signature != null || pointer != null
}

/** An address shown at the top of Scan with its live value. */
data class Watch(val address: Long, val type: ValueType, val value: ByteArray? = null)

/** One place a write changed: what was there before and what was written. */
class UndoPart(val address: Long, val before: ByteArray, val after: ByteArray)

/**
 * One change that can be taken back: a value or bytes written, a cheat-file mod switched, or a cheat frozen. [cheatId] is
 * the frozen cheat (undoing it also unfreezes it); [modIndex] and [modWasOn] put a cheat-file mod's switch back.
 */
class UndoStep(
    val pid: Int,
    val label: String,
    val parts: List<UndoPart>,
    val cheatId: String? = null,
    val modIndex: Int = -1,
    val modWasOn: Boolean = false,
    val time: Long = System.currentTimeMillis()
)

/** The cheats of each game, saved by title ID in the app's settings. (Frozen is not saved: a game starts unfrozen.) */
object CheatStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("memory_cheats", Context.MODE_PRIVATE)
    private fun key(titleId: String) = "t_" + titleId.ifBlank { "unknown" }

    fun load(ctx: Context, titleId: String): List<Cheat> = parseCheats(prefs(ctx).getString(key(titleId), null))

    fun save(ctx: Context, titleId: String, list: List<Cheat>) {
        prefs(ctx).edit().putString(key(titleId), cheatsToJson(list)).apply()
    }

    /** Every game's cheats, by title ID ("unknown" for cheats saved without one); games without cheats are left out. */
    fun all(ctx: Context): Map<String, List<Cheat>> =
        prefs(ctx).all.entries.filter { it.key.startsWith("t_") }.mapNotNull { (k, v) ->
            val list = parseCheats(v as? String)
            if (list.isEmpty()) null else k.removePrefix("t_") to list
        }.toMap()
}

/** [incoming] cheats put among the [existing] ones: one with the same id is replaced where it stands, new ones go at the end. */
fun mergeCheats(existing: List<Cheat>, incoming: List<Cheat>): List<Cheat> {
    val byId = incoming.associateBy { it.id }
    val merged = existing.map { byId[it.id]?.copy(frozen = it.frozen) ?: it }.toMutableList()
    for (c in incoming) if (merged.none { it.id == c.id }) merged.add(c.copy(frozen = false))
    return merged
}

fun cheatsToJson(list: List<Cheat>): String {
    val arr = JSONArray()
    for (c in list) {
        arr.put(
            JSONObject().put("id", c.id).put("name", c.name).put("address", java.lang.Long.toHexString(c.address))
                .put("type", c.type.name).put("value", c.value).put("group", c.group)
                .put("sig", c.sigHex).put("sigMask", c.sigMask).put("sigAt", c.sigAt).put("ptr", c.ptr)
        )
    }
    return arr.toString()
}

/** Cheats from saved settings or an exported list (an array of them, or an object with a "cheats" array). */
fun parseCheats(json: String?): List<Cheat> {
    if (json.isNullOrBlank()) return emptyList()
    val root = try {
        MiniJson.parse(json.trim())
    } catch (_: Exception) {
        return emptyList()
    }
    val list = (root as? List<*>) ?: ((root as? Map<*, *>)?.get("cheats") as? List<*>) ?: return emptyList()
    return list.mapNotNull { e ->
        val o = e as? Map<*, *> ?: return@mapNotNull null
        val addr = parseAddress(o["address"]?.toString() ?: "") ?: return@mapNotNull null
        Cheat(
            id = o["id"]?.toString()?.ifBlank { null } ?: UUID.randomUUID().toString(),
            name = o["name"]?.toString().orEmpty(),
            address = addr,
            type = ValueType.of(o["type"]?.toString()),
            value = o["value"]?.toString().orEmpty(),
            group = o["group"]?.toString().orEmpty(),
            sigHex = o["sig"]?.toString().orEmpty(),
            sigMask = o["sigMask"]?.toString().orEmpty(),
            sigAt = (o["sigAt"] as? Number)?.toInt() ?: o["sigAt"]?.toString()?.toIntOrNull() ?: 0,
            ptr = o["ptr"]?.toString().orEmpty()
        )
    }
}

/** An address typed by the user: hex, with or without 0x. Null when it is not one. */
fun parseAddress(text: String): Long? {
    val t = text.trim().removePrefix("0x").removePrefix("0X").replace(" ", "").replace("_", "")
    if (t.isEmpty() || t.length > 16) return null
    return try {
        java.lang.Long.parseUnsignedLong(t, 16)
    } catch (_: NumberFormatException) {
        null
    }
}
