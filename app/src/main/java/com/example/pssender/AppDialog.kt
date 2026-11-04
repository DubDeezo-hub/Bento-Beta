package com.example.pssender

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

// ---------------------------------------------------------------------------------------------
// Every pop-up in the app goes through here (files call AlertDialog without importing Material's, so this one is used):
// nearly the full width of the phone instead of Android's narrow default, so texts wrap less and lists show more, and a
// ✕ in the corner that closes it like tapping outside does.
// ---------------------------------------------------------------------------------------------

/** How much of the screen's width a pop-up takes, and the most it takes on a tablet. */
private const val DIALOG_WIDTH = 0.94f
private val DIALOG_MAX_WIDTH = 560.dp

@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        title = {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f)) { title?.invoke() }
                CloseCorner(onDismissRequest)
            }
        },
        text = text,
        modifier = Modifier.fillMaxWidth(DIALOG_WIDTH).widthIn(max = DIALOG_MAX_WIDTH),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        shape = RoundedCornerShape(28.dp)
    )
}

/** The ✕ in a pop-up's corner. */
@Composable
private fun CloseCorner(onClose: () -> Unit) {
    val label = str(R.string.dialog_close)
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClose)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Text("✕", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
