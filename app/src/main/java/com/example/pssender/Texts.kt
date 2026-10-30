package com.example.pssender

import android.app.Application
import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import java.io.File

// ---------------------------------------------------------------------------------------------
// Texts: every sentence the app shows lives in res/values/strings.xml (English) and in one
// res/values-xx/strings.xml per other language. Android picks the file that matches the phone's language.
// ---------------------------------------------------------------------------------------------

/** First-strong-isolate and pop-directional-isolate: they keep a piece of text together inside a sentence that reads the other way. */
private const val ISOLATE_START = '⁨'
private const val ISOLATE_END = '⁩'

/**
 * The values to put into a text. In a right-to-left sentence (Arabic, Hebrew, ...) a text value such as a file path
 * or an address is wrapped so it keeps its own order instead of being scrambled with the words around it.
 * Numbers are left alone.
 */
fun isolateArgs(args: Array<out Any>, rtl: Boolean): Array<Any> =
    Array(args.size) { i ->
        val a = args[i]
        if (rtl && a is String && a.isNotEmpty()) "$ISOLATE_START$a$ISOLATE_END" else a
    }

/**
 * The texts for code that has no screen to ask: the network code, the transfer service and the like.
 * The screens use [str] instead; both read the same translation files.
 */
object AppText {
    @Volatile
    private var reader: ((Int, Array<out Any>) -> String)? = null

    /**
     * Reads the texts from the app's resources. [App] calls this when the app process starts; [texts] is the context
     * in the chosen language when the app picks its own (see [AppLanguage]).
     */
    fun init(ctx: Context, texts: Context = ctx.applicationContext) {
        val app = texts
        reader = { id, args ->
            if (args.isEmpty()) {
                app.getString(id)
            } else {
                val rtl = app.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
                app.getString(id, *isolateArgs(args, rtl))
            }
        }
    }

    /** Reads the texts from somewhere else (the checks use the translation files directly). */
    fun use(read: (Int, Array<out Any>) -> String) {
        reader = read
    }

    /** The text with this id (an R.string value), with [args] filled in for its %1$s, %2$d, ... places. */
    fun get(id: Int, vararg args: Any): String = reader?.invoke(id, args) ?: ""
}

/**
 * The app itself: it makes the texts available before anything else runs, and picks up the transfers that the last run
 * left unfinished. They wait on the screen for the user to continue or discard them; from here on the list is kept up to
 * date in a file that is not part of the phone's backups (it holds addresses and links).
 */
class App : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        AppText.init(this)
        AppLook.load(this)
        val store = UnfinishedFile(File(noBackupFilesDir, "unfinished-transfers.json"))
        TransferState.adoptUnfinished(store.load())
        TransferState.journal = FileJournal(store)
    }
}

/** A text for use inside a screen, with [args] filled in. */
@Composable
fun str(id: Int, vararg args: Any): String {
    if (args.isEmpty()) return stringResource(id)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return stringResource(id, *isolateArgs(args, rtl))
}

/** "A, B, C" with the separator of the current language. */
fun joinList(items: List<String>): String = items.joinToString(AppText.get(R.string.list_separator))
