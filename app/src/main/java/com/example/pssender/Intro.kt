package com.example.pssender

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------------
// The opening: the Bento logo, in the same place and size as on Android's own launch screen, grows a little and
// fades while the first screen rises in under it. On Android 12 and newer the system's launch screen (the same
// logo on the same color, see values-v31/themes.xml) hands over to this without a jump; on older phones this is
// the launch screen. It plays once per start of the app, not when the phone is turned.
// ---------------------------------------------------------------------------------------------

/** How long the logo stays still before it moves, and how long the hand-over takes. */
private const val INTRO_HOLD_MS = 120L
private const val INTRO_MS = 750

/** The page colors of Theme.kt, which the launch screen uses too. */
private val INTRO_DARK = Color(0xFF0E1320)
private val INTRO_LIGHT = Color(0xFFF4F6FB)

@Composable
fun IntroHost(play: Boolean, content: @Composable () -> Unit) {
    var done by rememberSaveable { mutableStateOf(!play) }
    val progress = remember { Animatable(if (done) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!done) {
            delay(INTRO_HOLD_MS)
            progress.animateTo(1f, tween(durationMillis = INTRO_MS, easing = FastOutSlowInEasing))
            done = true
        }
    }
    val p = progress.value
    Box(
        modifier = Modifier.fillMaxSize().background(if (isSystemInDarkTheme()) INTRO_DARK else INTRO_LIGHT)
    ) {
        // The first screen comes in during the second part: it fades in, rising a little and growing to full size
        val c = ((p - 0.3f) / 0.7f).coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = c
                    translationY = (1f - c) * 32.dp.toPx()
                    val sc = 0.97f + 0.03f * c
                    scaleX = sc
                    scaleY = sc
                }
        ) { content() }
        if (!done) {
            // The adaptive icon as Android draws it on the launch screen: 240 dp of artwork, 160 dp of it showing
            val fade = ((p - 0.15f) / 0.6f).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(160.dp)
                    .graphicsLayer {
                        alpha = 1f - fade
                        val sc = 1f + 0.35f * p
                        scaleX = sc
                        scaleY = sc
                    }
                    .clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = Modifier.requiredSize(240.dp))
                Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.requiredSize(240.dp))
            }
        }
    }
}
