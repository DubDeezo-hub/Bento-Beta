package com.example.pssender // change to match your project's package name

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------------------------
// The few icons the app needs, drawn as simple outlines so no icon library is required.
// Each one is 24 x 24 with 2-unit round strokes. The color comes from the tint it is shown with.
// ---------------------------------------------------------------------------------------------

private fun outlineIcon(name: String, draw: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = draw
    ).build()

/** The open tray that the download and upload arrows sit above. */
private fun PathBuilder.tray() {
    moveTo(4f, 15f)
    lineTo(4f, 18.5f)
    arcTo(1.5f, 1.5f, 0f, false, false, 5.5f, 20f)
    lineTo(18.5f, 20f)
    arcTo(1.5f, 1.5f, 0f, false, false, 20f, 18.5f)
    lineTo(20f, 15f)
}

/** Arrow into a tray. */
val InstallIcon: ImageVector by lazy {
    outlineIcon("Install") {
        tray()
        moveTo(12f, 4f)
        lineTo(12f, 14.5f)
        moveTo(7.5f, 10f)
        lineTo(12f, 14.5f)
        lineTo(16.5f, 10f)
    }
}

/** Arrow out of a tray. */
val UploadIcon: ImageVector by lazy {
    outlineIcon("Upload") {
        tray()
        moveTo(12f, 14.5f)
        lineTo(12f, 4f)
        moveTo(7.5f, 8.5f)
        lineTo(12f, 4f)
        lineTo(16.5f, 8.5f)
    }
}

/** Lightning bolt. */
val PayloadIcon: ImageVector by lazy {
    outlineIcon("Payload") {
        moveTo(13.5f, 2.5f)
        lineTo(5f, 13.5f)
        lineTo(11f, 13.5f)
        lineTo(10f, 21.5f)
        lineTo(19f, 10.5f)
        lineTo(13f, 10.5f)
        close()
    }
}

/** Folder. */
val FilesIcon: ImageVector by lazy {
    outlineIcon("Files") {
        moveTo(3.5f, 7f)
        arcTo(1.5f, 1.5f, 0f, false, true, 5f, 5.5f)
        lineTo(9.5f, 5.5f)
        lineTo(12f, 8f)
        lineTo(19f, 8f)
        arcTo(1.5f, 1.5f, 0f, false, true, 20.5f, 9.5f)
        lineTo(20.5f, 18f)
        arcTo(1.5f, 1.5f, 0f, false, true, 19f, 19.5f)
        lineTo(5f, 19.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 3.5f, 18f)
        close()
    }
}

/** Globe. */
val WebIcon: ImageVector by lazy {
    outlineIcon("Web") {
        moveTo(12f, 3f)
        arcToRelative(9f, 9f, 0f, true, false, 0f, 18f)
        arcToRelative(9f, 9f, 0f, true, false, 0f, -18f)
        close()
        moveTo(3f, 12f)
        lineTo(21f, 12f)
        moveTo(12f, 3f)
        curveTo(14.8f, 5.6f, 16.2f, 8.6f, 16.2f, 12f)
        curveTo(16.2f, 15.4f, 14.8f, 18.4f, 12f, 21f)
        curveTo(9.2f, 18.4f, 7.8f, 15.4f, 7.8f, 12f)
        curveTo(7.8f, 8.6f, 9.2f, 5.6f, 12f, 3f)
        close()
    }
}

/** Check mark, for a finished transfer. */
val CheckIcon: ImageVector by lazy {
    outlineIcon("Check") {
        moveTo(5f, 12.5f)
        lineTo(10f, 17.5f)
        lineTo(19f, 7f)
    }
}

/** Two bars, for holding a transfer. */
val PauseIcon: ImageVector by lazy {
    outlineIcon("Pause") {
        moveTo(8.5f, 5.5f)
        lineTo(8.5f, 18.5f)
        moveTo(15.5f, 5.5f)
        lineTo(15.5f, 18.5f)
    }
}

/** A triangle pointing right, for letting a held transfer carry on. */
val PlayIcon: ImageVector by lazy {
    outlineIcon("Play") {
        moveTo(8f, 5.5f)
        lineTo(8f, 18.5f)
        lineTo(18.5f, 12f)
        close()
    }
}

/** Circle with an exclamation mark, for a transfer that didn't finish. */
val AlertIcon: ImageVector by lazy {
    outlineIcon("Alert") {
        moveTo(12f, 3f)
        arcToRelative(9f, 9f, 0f, true, false, 0f, 18f)
        arcToRelative(9f, 9f, 0f, true, false, 0f, -18f)
        close()
        moveTo(12f, 7.5f)
        lineTo(12f, 13f)
        moveTo(12f, 16.5f)
        lineTo(12f, 16.51f)
    }
}

/** An arrow pointing up, for moving an entry of a list up. */
val ArrowUpIcon: ImageVector by lazy {
    outlineIcon("ArrowUp") {
        moveTo(12f, 19f)
        lineTo(12f, 5f)
        moveTo(6f, 11f)
        lineTo(12f, 5f)
        lineTo(18f, 11f)
    }
}

/** An arrow pointing down, for moving an entry of a list down. */
val ArrowDownIcon: ImageVector by lazy {
    outlineIcon("ArrowDown") {
        moveTo(12f, 5f)
        lineTo(12f, 19f)
        moveTo(6f, 13f)
        lineTo(12f, 19f)
        lineTo(18f, 13f)
    }
}

/** A cross, for taking an entry out of a list. */
val CloseIcon: ImageVector by lazy {
    outlineIcon("Close") {
        moveTo(6f, 6f)
        lineTo(18f, 18f)
        moveTo(18f, 6f)
        lineTo(6f, 18f)
    }
}
