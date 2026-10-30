package com.example.pssender

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------------
// Memory > Learn: lessons on finding and changing values, from easy to expert, sorted by how hard the value is to
// find. Each lesson says what to look for and lists its steps; "Guide me" turns it into a coach at the top of the
// Memory tab that watches what you do and moves on by itself when a step is done. The practice game (see
// PracticeConsole.kt) gives every lesson something to work on without a console.
// ---------------------------------------------------------------------------------------------

/** How hard the values in a level are to find. */
enum class LearnLevel(val label: Int, val look: Int) {
    EASY(R.string.learn_level_easy, R.string.learn_look_easy),
    MEDIUM(R.string.learn_level_medium, R.string.learn_look_medium),
    EXPERT(R.string.learn_level_expert, R.string.learn_look_expert)
}

/** What the session looked like when a step began, so a step can tell that something new happened since. */
class LearnBase(val writes: Int, val undos: Int, val cheats: Int, val neighbors: Int) {
    companion object {
        fun now(m: MemorySession) = LearnBase(m.writes, m.undos, m.cheats.size, m.neighborsOpened)
    }
}

/** One step: its text, and how to tell it is done (null: the user taps Next). */
class LearnStep(val text: Int, val done: ((MemorySession, LearnBase) -> Boolean)? = null)

/** A lesson: the value it is about, what to look for, and its steps (none for a lesson that only explains). */
class Lesson(val id: String, val level: LearnLevel, val title: Int, val look: Int, val steps: List<LearnStep> = emptyList())

object Lessons {
    private val connected: (MemorySession, LearnBase) -> Boolean = { m, _ -> m.snapshot != null && m.gamePid != 0 }
    private val wrote: (MemorySession, LearnBase) -> Boolean = { m, b -> m.writes > b.writes }
    private val undid: (MemorySession, LearnBase) -> Boolean = { m, b -> m.undos > b.undos }
    private val added: (MemorySession, LearnBase) -> Boolean = { m, b -> m.cheats.size > b.cheats }
    private val frozen: (MemorySession, LearnBase) -> Boolean = { m, _ -> m.cheats.any { it.frozen } }
    private fun few(m: MemorySession) = m.searching && m.total in 1..5

    val all = listOf(
        // --- easy: numbers the game shows ---
        Lesson(
            "gold", LearnLevel.EASY, R.string.learn_gold_title, R.string.learn_gold_look, listOf(
                LearnStep(R.string.learn_step_connect, connected),
                LearnStep(R.string.learn_gold_1) { m, _ -> m.searching && m.scans >= 1 && m.searchType == ValueType.INT },
                LearnStep(R.string.learn_gold_2) { m, _ -> m.scans >= 2 },
                LearnStep(R.string.learn_gold_3) { m, _ -> few(m) },
                LearnStep(R.string.learn_gold_4, wrote)
            )
        ),
        Lesson(
            "ammo", LearnLevel.EASY, R.string.learn_ammo_title, R.string.learn_ammo_look, listOf(
                LearnStep(R.string.learn_step_connect, connected),
                LearnStep(R.string.learn_ammo_1) { m, _ -> few(m) && m.searchType == ValueType.INT },
                LearnStep(R.string.learn_ammo_2, added),
                LearnStep(R.string.learn_ammo_3, frozen),
                LearnStep(R.string.learn_ammo_4, undid)
            )
        ),
        Lesson("many", LearnLevel.EASY, R.string.learn_many_title, R.string.learn_many_look),
        // --- medium: values without a number, and values that keep changing ---
        Lesson(
            "health", LearnLevel.MEDIUM, R.string.learn_health_title, R.string.learn_health_look, listOf(
                LearnStep(R.string.learn_step_connect, connected),
                LearnStep(R.string.learn_health_1) { m, _ -> m.searching && m.searchType == ValueType.FLOAT },
                LearnStep(R.string.learn_health_2) { m, _ -> m.searchType == ValueType.FLOAT && m.scans >= 2 },
                LearnStep(R.string.learn_health_3) { m, _ -> m.searchType == ValueType.FLOAT && m.scans >= 4 },
                LearnStep(R.string.learn_health_4) { m, b -> wrote(m, b) || frozen(m, b) }
            )
        ),
        Lesson(
            "timer", LearnLevel.MEDIUM, R.string.learn_timer_title, R.string.learn_timer_look, listOf(
                LearnStep(R.string.learn_step_connect, connected),
                LearnStep(R.string.learn_timer_1) { m, _ -> m.searching && m.searchType == ValueType.FLOAT },
                LearnStep(R.string.learn_timer_2) { m, _ -> m.searchType == ValueType.FLOAT && m.scans >= 4 },
                LearnStep(R.string.learn_timer_3, frozen)
            )
        ),
        Lesson(
            "neighbors", LearnLevel.MEDIUM, R.string.learn_neighbors_title, R.string.learn_neighbors_look, listOf(
                LearnStep(R.string.learn_neighbors_1) { m, _ -> few(m) || m.cheats.isNotEmpty() },
                LearnStep(R.string.learn_neighbors_2) { m, b -> m.neighborsOpened > b.neighbors },
                LearnStep(R.string.learn_neighbors_3, wrote)
            )
        ),
        Lesson("nothing", LearnLevel.MEDIUM, R.string.learn_nothing_title, R.string.learn_nothing_look),
        // --- expert: values that move, code, text ---
        Lesson(
            "pointer", LearnLevel.EXPERT, R.string.learn_pointer_title, R.string.learn_pointer_look, listOf(
                LearnStep(R.string.learn_pointer_1) { m, _ -> m.cheats.isNotEmpty() },
                LearnStep(R.string.learn_pointer_2),
                LearnStep(R.string.learn_pointer_3),
                LearnStep(R.string.learn_pointer_4) { m, _ -> m.cheats.any { it.ptr.isNotEmpty() } }
            )
        ),
        Lesson(
            "code", LearnLevel.EXPERT, R.string.learn_code_title, R.string.learn_code_look, listOf(
                LearnStep(R.string.learn_step_connect, connected),
                LearnStep(R.string.learn_code_1) { m, _ -> m.view == MemoryView.BROWSE && m.hexAt >= 0 },
                LearnStep(R.string.learn_code_2) { m, b -> if (m.practicing) PracticeGame.damageCodeChanged() else wrote(m, b) },
                LearnStep(R.string.learn_code_3, undid)
            )
        ),
        Lesson(
            "text", LearnLevel.EXPERT, R.string.learn_text_title, R.string.learn_text_look, listOf(
                LearnStep(R.string.learn_step_connect, connected),
                LearnStep(R.string.learn_text_1) { m, _ -> m.textTotal >= 1 },
                LearnStep(R.string.learn_text_2) { m, _ -> m.textHits.any { it.written != null } }
            )
        ),
        Lesson("last", LearnLevel.EXPERT, R.string.learn_last_title, R.string.learn_last_look)
    )
}

/** The lesson being guided, and where in it the user is. */
object LearnCoach {
    var lesson by mutableStateOf<Lesson?>(null)
        private set
    var step by mutableStateOf(0)
        private set
    var finished by mutableStateOf(false)
        private set
    private var base = LearnBase(0, 0, 0, 0)

    fun start(l: Lesson) {
        lesson = l
        finished = false
        go(0)
    }

    fun go(i: Int) {
        val l = lesson ?: return
        if (i >= l.steps.size) {
            finished = true
            return
        }
        step = i.coerceAtLeast(0)
        finished = false
        base = LearnBase.now(MemorySession)
    }

    fun stop() {
        lesson = null
        finished = false
    }

    /** Whether the current step is done. */
    fun isDone(): Boolean {
        val l = lesson ?: return false
        val s = l.steps.getOrNull(step) ?: return false
        return s.done?.invoke(MemorySession, base) ?: false
    }
}

// ---------------------------------------------------------------------------------------------
// Screens
// ---------------------------------------------------------------------------------------------

/** The coach bar at the top of the Memory tab while a lesson is guided. */
@Composable
fun LearnCoachBar() {
    val l = LearnCoach.lesson ?: return
    val step = LearnCoach.step
    // Some steps depend on things outside the screen's state (the practice game), so look again now and then
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(l, step) {
        while (true) {
            delay(700)
            tick++
        }
    }
    // Read while drawing, so a change in the session's state (or the next tick) checks again
    val done = tick >= 0 && LearnCoach.isDone()
    LaunchedEffect(done, step) {
        if (done) {
            delay(900)
            LearnCoach.go(step + 1)
        }
    }
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cs.primary.copy(alpha = 0.12f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(str(R.string.learn_coach_title, str(l.title)), style = MaterialTheme.typography.labelLarge, color = cs.primary)
        if (LearnCoach.finished) {
            Text(str(R.string.learn_coach_done), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { LearnCoach.stop() }) { Text(str(R.string.action_close)) }
                TextButton(onClick = {
                    LearnCoach.stop()
                    MemorySession.view = MemoryView.LEARN
                }) { Text(str(R.string.learn_coach_more)) }
            }
            return@Column
        }
        LinearProgressIndicator(progress = { (step + (if (done) 1 else 0)).toFloat() / l.steps.size }, modifier = Modifier.fillMaxWidth())
        Text(str(R.string.learn_coach_step, step + 1, l.steps.size), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        Text(str(l.steps[step].text), style = MaterialTheme.typography.bodyMedium)
        if (done) Text(str(R.string.learn_coach_nice), style = MaterialTheme.typography.bodySmall, color = statusColors().good, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) TextButton(onClick = { LearnCoach.go(step - 1) }) { Text(str(R.string.learn_coach_back)) }
            OutlinedButton(onClick = { LearnCoach.go(step + 1) }) {
                Text(str(if (l.steps[step].done == null) R.string.learn_coach_next else R.string.learn_coach_skip))
            }
            TextButton(onClick = { LearnCoach.stop() }) { Text(str(R.string.learn_coach_stop)) }
        }
    }
}

/** The Learn view: the practice game, the level, and its lessons. */
internal fun LazyListScope.learnItems() {
    item {
        Text(str(R.string.learn_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    item { PracticeCard() }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChipRow {
                LearnLevel.entries.forEach { lv -> Chip(str(lv.label), LearnScreen.level == lv) { LearnScreen.level = lv } }
            }
            Panel {
                TipTitle(str(R.string.learn_look_title), Tip.MEM_LEARN, style = MaterialTheme.typography.labelLarge)
                Text(str(LearnScreen.level.look), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    items(Lessons.all.filter { it.level == LearnScreen.level }, key = { it.id }) { l -> LessonCard(l) }
}

/** Which level the Learn view shows (kept while the app runs). */
object LearnScreen {
    var level by mutableStateOf(LearnLevel.EASY)
}

@Composable
private fun LessonCard(l: Lesson) {
    var open by remember { mutableStateOf(false) }
    Panel {
        Text(str(l.title), style = MaterialTheme.typography.titleSmall)
        Text(str(l.look), style = MaterialTheme.typography.bodySmall)
        if (l.steps.isNotEmpty()) {
            if (open) {
                l.steps.forEachIndexed { i, s ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${i + 1}.", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        Text(str(s.text), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    LearnCoach.start(l)
                    MemorySession.view = MemoryView.SCAN
                }) { Text(str(R.string.learn_guide_me)) }
                TextButton(onClick = { open = !open }) { Text(str(if (open) R.string.learn_hide_steps else R.string.learn_show_steps)) }
            }
        }
    }
}

/** The practice game: what it shows, its buttons, and the switch between it and the console. */
@Composable
private fun PracticeCard() {
    val m = MemorySession
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(m.practicing) {
        while (m.practicing) {
            PracticeGame.tick()
            tick++
            delay(250)
        }
    }
    Panel {
        TipTitle(str(R.string.learn_practice_title), Tip.MEM_PRACTICE, style = MaterialTheme.typography.titleSmall)
        if (!m.practicing) {
            Text(str(R.string.learn_practice_off), style = MaterialTheme.typography.bodySmall)
            Button(enabled = !m.busy, onClick = { m.startPractice() }) { Text(str(R.string.learn_practice_start)) }
            return@Panel
        }
        // Read every time it is drawn ([tick] makes that happen four times a second)
        if (tick < 0) return@Panel
        val hp = PracticeGame.health()
        val max = PracticeGame.maxHealth().coerceAtLeast(1f)
        val t = PracticeGame.timer().coerceAtLeast(0f).toInt()
        Text(str(R.string.learn_practice_on), style = MaterialTheme.typography.bodySmall, color = statusColors().good)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row {
                Text(PracticeGame.name(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("%d:%02d".format(t / 60, t % 60), style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(str(R.string.learn_practice_gold, PracticeGame.gold()), style = MaterialTheme.typography.bodyMedium)
                Text(str(R.string.learn_practice_ammo, PracticeGame.ammo(), PracticeGame.CLIP), style = MaterialTheme.typography.bodyMedium)
            }
            // Health is only a bar, as in many games: there is no number to search for
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth((hp / max).coerceIn(0f, 1f))
                        .height(10.dp)
                        .background(statusColors().bad)
                )
            }
            if (PracticeGame.damageCodeChanged()) Text(str(R.string.learn_practice_code_changed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        ChipRow {
            Chip(str(R.string.learn_practice_buy), false) { PracticeGame.spendGold(50); tick++ }
            Chip(str(R.string.learn_practice_find), false) { PracticeGame.earnGold(35); tick++ }
            Chip(str(R.string.learn_practice_shoot), false) { PracticeGame.shoot(); tick++ }
            Chip(str(R.string.learn_practice_reload), false) { PracticeGame.reload(); tick++ }
            Chip(str(R.string.learn_practice_hit), false) { PracticeGame.takeHit(); tick++ }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                PracticeGame.restart()
                tick++
            }) { Text(str(R.string.learn_practice_restart)) }
            TextButton(enabled = !m.busy, onClick = { m.stopPractice() }) { Text(str(R.string.learn_practice_stop)) }
        }
        Text(str(R.string.learn_practice_restart_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
