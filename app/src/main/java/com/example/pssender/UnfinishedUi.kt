package com.example.pssender // change to match your project's package name

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------------------------
// The card that offers the transfers again that the last run of the app did not finish.
// ---------------------------------------------------------------------------------------------

/**
 * Shows [items], the transfers that were not finished when the app closed last time, with their names. Continue puts them
 * back in the queue, Discard forgets them. Nothing is shown while there are none.
 */
@Composable
fun UnfinishedCard(items: List<TransferRequest>, onContinue: () -> Unit, onDiscard: () -> Unit) {
    if (items.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val (names, more) = unfinishedNames(items)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(shape)
            .background(cs.surfaceContainerHigh)
            .border(1.dp, cs.outlineVariant, shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(str(R.string.unfinished_title, items.size), style = MaterialTheme.typography.titleSmall)
        Text(
            joinList(names) + if (more) "…" else "",
            color = cs.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(str(R.string.unfinished_hint), color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onContinue) { Text(str(R.string.unfinished_continue)) }
            TextButton(onClick = onDiscard) { Text(str(R.string.unfinished_discard)) }
        }
    }
}
