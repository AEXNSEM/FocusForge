package com.focusforge.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import java.util.Locale

private const val PREFS_NAME = "focus_forge_prefs"

// Gate 1 reading cooldown + Home-Bypass Surcharge tuning. This track is completely
// separate from Progressive Drift Escalation and from the Trapwire Quarantine Hammer —
// it only ever adds minutes to the Gate 1 reading countdown, never triggers a lock.
private const val BASE_READING_MS = 180_000L          // 3-minute mandatory reset
private const val BYPASS_SURCHARGE_FIRST_MS = 5 * 60_000L   // +5 min on the 1st bypass
private const val BYPASS_SURCHARGE_REPEAT_MS = 10 * 60_000L // +10 min on each bypass after that
private const val MAX_COUNTED_BYPASS_ATTEMPTS = 3      // worst case: 3 + 5 + 10 + 10 = 28 min

data class LearningModule(
    val id: String,
    val topic: String,
    val title: String,
    val content: String,
    val question: String,
    val options: List<String>,
    val correctIndex: Int
)

data class InstalledApp(
    val appName: String,
    val packageName: String,
    var isBlocked: Boolean
)

enum class LockoutPhase { READING, QUIZ, SUCCESS }
enum class ReverificationPhase { ANCHOR, QUIZ }

/**
 * Everything MainActivity can be showing, derived atomically from a single Intent.
 * One value rather than several independently-mutable fields, so a recomposition can
 * never observe a torn combination of them — which matters here because the
 * accessibility service can redeliver an intent to this singleTop activity very
 * frequently (every 1.5s while a gate is unresolved).
 *
 * DriftCheckpoint carries a `level` (1 = 45-min Intent Anchor, 2 = 90-min pause +
 * re-verification quiz). DriftCoolOff (level 3, 135 min) is its own case with its own
 * screen — deliberately not merged into QuarantineHammer, even though both are
 * "lock screens with a countdown": one is an overuse cool-off, the other is a
 * content-violation lock, and conflating them would blur the exact distinction the
 * app is built around.
 */
sealed class ScreenState {
    object Dashboard : ScreenState()
    data class GateEntry(val targetPackage: String) : ScreenState()
    data class DriftCheckpoint(val targetPackage: String, val level: Int) : ScreenState()
    data class DriftCoolOff(val targetPackage: String, val coolOffEnd: Long) : ScreenState()
    data class QuarantineHammer(val targetPackage: String, val quarantineEnd: Long) : ScreenState()
}

class MainActivity : ComponentActivity() {

    private var screenState by mutableStateOf<ScreenState>(ScreenState.Dashboard)

    // True only while the Gate 1 reading countdown is mounted AND still running (not
    // the quiz phase, and not once the countdown has already hit zero). Reported up by
    // ReadingPhaseView. onStop() reads this to tell a genuine home-bypass apart from the
    // legitimate backgrounding that happens when we launch the now-authorized target app.
    private var readingCooldownActive by mutableStateOf(false)

    // Bumped on every onResume(). ReadingPhaseView includes this in its remember/
    // DisposableEffect keys so that coming back to the foreground always re-derives the
    // countdown from SharedPreferences instead of trusting whatever the already-running
    // CountDownTimer had in memory — which is what lets a surcharge written in onStop()
    // (while the composition was never actually torn down) take effect.
    private var resyncToken by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screenState = computeScreenState(intent)

        setContent {
            FocusForgeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF121212)
                ) {
                    when (val state = screenState) {
                        is ScreenState.QuarantineHammer -> {
                            QuarantineScreen(
                                blockedApp = state.targetPackage,
                                endTime = state.quarantineEnd,
                                onExit = { moveTaskToBack(true) }
                            )
                        }
                        is ScreenState.DriftCoolOff -> {
                            DriftCoolOffScreen(
                                blockedApp = state.targetPackage,
                                endTime = state.coolOffEnd,
                                onExit = { moveTaskToBack(true) }
                            )
                        }
                        is ScreenState.DriftCheckpoint -> {
                            if (state.level >= 2) {
                                DriftReverificationScreen(
                                    blockedApp = state.targetPackage,
                                    onComplete = { clearDriftCheckpoint(state.targetPackage, level = 2) },
                                    onClose = { endSessionFromCheckpoint(state.targetPackage) }
                                )
                            } else {
                                DriftAnchorScreen(
                                    blockedApp = state.targetPackage,
                                    onExtend = { clearDriftCheckpoint(state.targetPackage, level = 1) },
                                    onClose = { endSessionFromCheckpoint(state.targetPackage) }
                                )
                            }
                        }
                        is ScreenState.GateEntry -> {
                            TwoPhaseLockoutScreen(
                                blockedApp = state.targetPackage,
                                resyncToken = resyncToken,
                                onReadingCooldownActiveChange = { active -> readingCooldownActive = active },
                                onComplete = {
                                    startActiveSession(state.targetPackage)
                                    launchTarget(state.targetPackage)
                                }
                            )
                        }
                        ScreenState.Dashboard -> {
                            DashboardScreen()
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        screenState = computeScreenState(intent)
    }

    /**
     * Home-Bypass Surcharge. onStop() fires for every reason the Activity leaves the
     * foreground — home press, recent-apps switch, screen lock, or us legitimately
     * starting the target app. We only charge a surcharge when readingCooldownActive is
     * still true at that moment, which is false by the time the legitimate path runs
     * (launchTarget() flips screenState to Dashboard, which unmounts ReadingPhaseView
     * and reports cooldown-inactive, before startActivity() is ever called). This is the
     * ONLY penalty onStop() ever applies — it never touches drift or quarantine state.
     */
    override fun onStop() {
        super.onStop()
        val state = screenState
        if (readingCooldownActive && state is ScreenState.GateEntry) {
            recordBypassAttempt(state.targetPackage)
        }
    }

    override fun onResume() {
        super.onResume()
        resyncToken++
    }

    private fun recordBypassAttempt(packageName: String) {
        val prefs = prefs()
        val attemptsSoFar = prefs.getInt("reading_bypass_count_$packageName", 0)
        if (attemptsSoFar >= MAX_COUNTED_BYPASS_ATTEMPTS) return // cap reached, no further surcharge

        val surcharge = if (attemptsSoFar == 0) BYPASS_SURCHARGE_FIRST_MS else BYPASS_SURCHARGE_REPEAT_MS
        val now = System.currentTimeMillis()
        val currentEnd = prefs.getLong("reading_end_time_$packageName", now)
        val currentCeiling = prefs.getLong("reading_ceiling_ms_$packageName", BASE_READING_MS)

        // commit() (synchronous), not apply(): this is an enforcement write made right as
        // the process is about to be backgrounded (and possibly reclaimed), so it needs
        // to hit disk before onStop() returns rather than relying on an async flush.
        prefs.edit()
            .putLong("reading_end_time_$packageName", maxOf(currentEnd, now) + surcharge)
            .putLong("reading_ceiling_ms_$packageName", currentCeiling + surcharge)
            .putInt("reading_bypass_count_$packageName", attemptsSoFar + 1)
            .commit()
    }

    private fun computeScreenState(incoming: Intent?): ScreenState {
        val target = incoming?.getStringExtra(EXTRA_TRIGGERED_BY)
        val mode = incoming?.getStringExtra(EXTRA_MODE)
        return when {
            mode == MODE_QUARANTINE_HAMMER && target != null ->
                ScreenState.QuarantineHammer(target, incoming.getLongExtra(EXTRA_QUARANTINE_END, 0L))
            mode == MODE_DRIFT_COOLOFF && target != null ->
                ScreenState.DriftCoolOff(target, incoming.getLongExtra(EXTRA_COOLOFF_END, 0L))
            mode == MODE_DRIFT_CHECKPOINT && target != null ->
                ScreenState.DriftCheckpoint(target, incoming.getIntExtra(EXTRA_DRIFT_LEVEL, 1))
            mode == MODE_GATE_ENTRY && target != null ->
                ScreenState.GateEntry(target)
            else -> ScreenState.Dashboard
        }
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun startActiveSession(packageName: String) {
        val now = System.currentTimeMillis()
        prefs().edit()
            .putBoolean("session_authorized_$packageName", true)
            .putLong("session_start_$packageName", now)
            .putLong("session_last_active_$packageName", now)
            .putInt("drift_level_$packageName", 0)
            .remove("reading_end_time_$packageName")
            .remove("reading_ceiling_ms_$packageName")
            .remove("reading_bypass_count_$packageName")
            .apply()
    }

    /**
     * Clears one Progressive Drift Escalation checkpoint (level 1 or 2) and sends the
     * user back to the target app. Deliberately does NOT touch session_start — resetting
     * it here was the old bug that let a user dismiss the same checkpoint forever for
     * unlimited continuous access. drift_level is the only thing that advances; the
     * continuous-duration clock the service reads keeps climbing from the original
     * session start, which is exactly what makes checkpoint 2 (90 min) and checkpoint 3
     * (135 min) real, un-dodgeable escalations rather than a repeatable 45-minute reset.
     */
    private fun clearDriftCheckpoint(packageName: String, level: Int) {
        prefs().edit()
            .putInt("drift_level_$packageName", level)
            .putLong("session_last_active_$packageName", System.currentTimeMillis())
            .apply()
        launchTarget(packageName)
    }

    private fun endSessionFromCheckpoint(packageName: String) {
        prefs().edit()
            .putBoolean("session_authorized_$packageName", false)
            .remove("session_start_$packageName")
            .putInt("drift_level_$packageName", 0)
            .apply()
        moveTaskToBack(true)
    }

    private fun launchTarget(packageName: String) {
        screenState = ScreenState.Dashboard
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        } else {
            moveTaskToBack(true)
        }
    }
}

@Composable
fun QuarantineScreen(blockedApp: String, endTime: Long, onExit: () -> Unit) {
    BackHandler(enabled = true) { onExit() }

    var remainingSeconds by remember(endTime) {
        val now = System.currentTimeMillis()
        mutableStateOf(if (endTime > now) (endTime - now) / 1000 else 0L)
    }

    DisposableEffect(endTime) {
        val timer = object : CountDownTimer((remainingSeconds * 1000).coerceAtLeast(1_000L), 1000) {
            override fun onTick(millis: Long) {
                remainingSeconds = millis / 1000
            }
            override fun onFinish() {
                remainingSeconds = 0
            }
        }.start()

        onDispose { timer.cancel() }
    }

    val hours = remainingSeconds / 3600
    val minutes = (remainingSeconds % 3600) / 60
    val seconds = remainingSeconds % 60
    val formattedTime = String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "QUARANTINE LOCK ACTIVE",
            color = Color(0xFFEF4444),
            fontSize = 20.sp,
            fontWeight = FontWeight.Black
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Target $blockedApp has been hard-locked due to a boundary violation (explicit content or private-browsing evasion).",
            color = Color(0xFF9CA3AF),
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(36.dp))

        Text(
            text = formattedTime,
            fontSize = 48.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White
        )
        Text(
            text = "Mandatory Quarantine Remaining",
            fontSize = 13.sp,
            color = Color(0xFF6B7280)
        )
        Spacer(modifier = Modifier.height(36.dp))

        Button(
            onClick = onExit,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Acknowledge & Exit to Home", color = Color.White)
        }
    }
}

/**
 * The 135-minute Progressive Drift Escalation cool-off. Visually and semantically kept
 * distinct from QuarantineScreen (amber, "cool-off" language) even though the mechanics
 * are similar — this is a consequence of continuous overuse, not a content violation,
 * and the two must never be presented as the same thing.
 */
@Composable
fun DriftCoolOffScreen(blockedApp: String, endTime: Long, onExit: () -> Unit) {
    BackHandler(enabled = true) { onExit() }

    var remainingSeconds by remember(endTime) {
        val now = System.currentTimeMillis()
        mutableStateOf(if (endTime > now) (endTime - now) / 1000 else 0L)
    }

    DisposableEffect(endTime) {
        val timer = object : CountDownTimer((remainingSeconds * 1000).coerceAtLeast(1_000L), 1000) {
            override fun onTick(millis: Long) {
                remainingSeconds = millis / 1000
            }
            override fun onFinish() {
                remainingSeconds = 0
            }
        }.start()

        onDispose { timer.cancel() }
    }

    val minutes = remainingSeconds / 60
    val seconds = remainingSeconds % 60
    val formattedTime = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "DRIFT COOL-OFF ACTIVE",
            color = Color(0xFFF59E0B),
            fontSize = 20.sp,
            fontWeight = FontWeight.Black
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Continuous usage in $blockedApp passed 135 minutes without a break. A mandatory 15-minute cool-off is required before your next session.",
            color = Color(0xFF9CA3AF),
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(36.dp))

        Text(
            text = formattedTime,
            fontSize = 48.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White
        )
        Text(
            text = "Cool-Off Remaining",
            fontSize = 13.sp,
            color = Color(0xFF6B7280)
        )
        Spacer(modifier = Modifier.height(36.dp))

        Button(
            onClick = onExit,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Acknowledge & Exit to Home", color = Color.White)
        }
    }
}

/**
 * Shared timed-pause UI used by both Drift Checkpoint 1 (30s, standalone) and the first
 * half of Drift Checkpoint 2 (60s, followed by a quiz) — same interaction pattern, only
 * duration/copy/continue-label differ.
 */
@Composable
fun IntentAnchorPause(
    title: String,
    message: String,
    durationSeconds: Long,
    continueLabel: String,
    onContinue: () -> Unit,
    onClose: () -> Unit
) {
    BackHandler(enabled = true) { onClose() }

    var secondsLeft by remember { mutableStateOf(durationSeconds) }
    var timerDone by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val timer = object : CountDownTimer(durationSeconds * 1000, 1000) {
            override fun onTick(millis: Long) {
                secondsLeft = millis / 1000
            }
            override fun onFinish() {
                timerDone = true
                secondsLeft = 0
            }
        }.start()

        onDispose { timer.cancel() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            color = Color(0xFFF59E0B),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = message,
            color = Color(0xFFD1D5DB),
            fontSize = 14.sp,
            lineHeight = 22.sp
        )
        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "00:${String.format(Locale.getDefault(), "%02d", secondsLeft)}",
            fontSize = 44.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White
        )
        Text(
            text = "Breathing Anchor",
            fontSize = 12.sp,
            color = Color(0xFF6B7280)
        )
        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onContinue,
            enabled = timerDone,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF3B82F6),
                disabledContainerColor = Color(0xFF262626)
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = if (timerDone) continueLabel else "Reflect (${secondsLeft}s)",
                color = if (timerDone) Color.White else Color(0xFF737373)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Task Complete - Close App", color = Color(0xFF9CA3AF))
        }
    }
}

/** Drift Checkpoint 1 (45 min continuous): 30-second Intent Anchor, no quiz. */
@Composable
fun DriftAnchorScreen(blockedApp: String, onExtend: () -> Unit, onClose: () -> Unit) {
    IntentAnchorPause(
        title = "45-MINUTE DRIFT CHECKPOINT",
        message = "You have maintained continuous active usage in $blockedApp for 45 minutes.\n\nTake a mandatory 30-second breath to evaluate: are you executing an intentional objective, or drifting into automated consumption?",
        durationSeconds = 30L,
        continueLabel = "Continue Task",
        onContinue = onExtend,
        onClose = onClose
    )
}

/**
 * Drift Checkpoint 2 (90 min continuous): a harder gate than checkpoint 1 — a 60-second
 * pause followed by a genuine re-verification quiz (reusing the same module bank as
 * Gate 1) before access continues.
 */
@Composable
fun DriftReverificationScreen(blockedApp: String, onComplete: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val modules = remember { loadLearningModules(context) }
    val activeModule = remember(blockedApp) { modules.random() }

    var phase by remember { mutableStateOf(ReverificationPhase.ANCHOR) }

    when (phase) {
        ReverificationPhase.ANCHOR -> IntentAnchorPause(
            title = "90-MINUTE RE-VERIFICATION",
            message = "You've been continuously active in $blockedApp for 90 minutes.\n\nA short pause plus a quick comprehension check is required before continuing.",
            durationSeconds = 60L,
            continueLabel = "Start Re-Verification Quiz",
            onContinue = { phase = ReverificationPhase.QUIZ },
            onClose = onClose
        )
        ReverificationPhase.QUIZ -> QuizPhaseView(
            module = activeModule,
            onQuizPassed = onComplete
        )
    }
}

fun loadLearningModules(context: Context): List<LearningModule> {
    val modules = mutableListOf<LearningModule>()
    try {
        val jsonString = context.assets.open("modules.json").bufferedReader().use { it.readText() }
        val array = JSONArray(jsonString)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val optionsJson = obj.getJSONArray("options")
            val optionsList = mutableListOf<String>()
            for (j in 0 until optionsJson.length()) {
                optionsList.add(optionsJson.getString(j))
            }
            modules.add(
                LearningModule(
                    id = obj.getString("id"),
                    topic = obj.getString("topic"),
                    title = obj.getString("title"),
                    content = obj.getString("content"),
                    question = obj.getString("question"),
                    options = optionsList,
                    correctIndex = obj.getInt("correctIndex")
                )
            )
        }
    } catch (e: Exception) {
        modules.add(
            LearningModule(
                id = "dop_01",
                topic = "Neuroscience",
                title = "Dopamine & Anticipation",
                content = "Dopamine drives pursuit and craving. Enforced friction breaks automatic habits by re-engaging conscious prefrontal evaluation.",
                question = "What primary function does friction serve?",
                options = listOf("Re-engaging conscious evaluation", "Increasing scroll speed", "Preventing hardware usage"),
                correctIndex = 0
            )
        )
    }
    return modules
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        val savedBlocked = prefs.getStringSet("blocked_packages_set", setOf("com.android.chrome")) ?: emptySet()

        val seenPackages = mutableSetOf<String>()
        val appList = mutableListOf<InstalledApp>()

        for (info in resolveInfos) {
            val pkg = info.activityInfo.packageName
            if (pkg != context.packageName && !seenPackages.contains(pkg)) {
                seenPackages.add(pkg)
                val label = info.loadLabel(pm).toString()
                appList.add(
                    InstalledApp(
                        appName = label,
                        packageName = pkg,
                        isBlocked = savedBlocked.contains(pkg)
                    )
                )
            }
        }

        // Always guarantee Chrome is in the list even if not picked up by launcher query
        if (!seenPackages.contains("com.android.chrome")) {
            appList.add(
                InstalledApp(
                    appName = "Google Chrome",
                    packageName = "com.android.chrome",
                    isBlocked = savedBlocked.contains("com.android.chrome")
                )
            )
        }

        installedApps = appList.sortedBy { it.appName.lowercase(Locale.getDefault()) }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FocusForge", color = Color.White, fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1E1E1E)),
                actions = {
                    TextButton(onClick = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    }) {
                        Text("Shield Settings", color = Color(0xFF3B82F6))
                    }
                }
            )
        },
        containerColor = Color(0xFF121212)
    ) { innerPadding ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color(0xFF3B82F6))
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Adaptive Supervision Targets",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = "Gate 1 entry, 10-min idle reset, 45/90/135-min progressive drift escalation, incognito detection, & 1-hr quarantine on content breach.",
                    fontSize = 13.sp,
                    color = Color(0xFF9CA3AF)
                )
                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(installedApps, key = { it.packageName }) { app ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.appName,
                                        fontWeight = FontWeight.Medium,
                                        color = Color.White,
                                        fontSize = 15.sp
                                    )
                                    Text(
                                        text = app.packageName,
                                        color = Color(0xFF6B7280),
                                        fontSize = 11.sp
                                    )
                                }
                                Switch(
                                    checked = app.isBlocked,
                                    onCheckedChange = { isChecked ->
                                        installedApps = installedApps.map {
                                            if (it.packageName == app.packageName) it.copy(isBlocked = isChecked) else it
                                        }
                                        val updatedSet = installedApps.filter { it.isBlocked }.map { it.packageName }.toSet()
                                        prefs.edit().putStringSet("blocked_packages_set", updatedSet).apply()
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFFEF4444)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TwoPhaseLockoutScreen(
    blockedApp: String,
    resyncToken: Int,
    onReadingCooldownActiveChange: (Boolean) -> Unit,
    onComplete: () -> Unit
) {
    BackHandler(enabled = true) { }

    val context = LocalContext.current
    val modules = remember { loadLearningModules(context) }
    val activeModule = remember(blockedApp) { modules.random() }

    var currentPhase by remember { mutableStateOf(LockoutPhase.READING) }

    when (currentPhase) {
        LockoutPhase.READING -> ReadingPhaseView(
            blockedApp = blockedApp,
            resyncToken = resyncToken,
            module = activeModule,
            onCooldownActiveChange = onReadingCooldownActiveChange,
            onReadingComplete = { currentPhase = LockoutPhase.QUIZ }
        )
        LockoutPhase.QUIZ -> QuizPhaseView(
            module = activeModule,
            onQuizPassed = onComplete
        )
        LockoutPhase.SUCCESS -> {}
    }
}

@Composable
fun ReadingPhaseView(
    blockedApp: String,
    resyncToken: Int,
    module: LearningModule,
    onCooldownActiveChange: (Boolean) -> Unit,
    onReadingComplete: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    // The ceiling isn't a fixed 180s anymore: it grows by the surcharge amount every
    // time recordBypassAttempt() fires, so a resumed countdown that's legitimately
    // longer than the base 3 minutes (because of a prior bypass) isn't mistaken for
    // stale/tampered data and reset back down to base.
    //
    // Keyed on resyncToken (in addition to blockedApp) so that every time the Activity
    // comes back to the foreground, this block re-runs and re-reads whatever value is
    // currently in SharedPreferences — including a surcharge that was written while the
    // composition stayed mounted in the background, which the running CountDownTimer
    // below has no way of knowing about on its own.
    var timeLeftSeconds by remember(blockedApp, resyncToken) {
        val now = System.currentTimeMillis()
        val ceilingMs = prefs.getLong("reading_ceiling_ms_$blockedApp", BASE_READING_MS)
        val storedEndTime = prefs.getLong("reading_end_time_$blockedApp", 0L)
        val calculatedRemainingMs = storedEndTime - now

        val safeRemainingSeconds = if (calculatedRemainingMs in 1..ceilingMs) {
            calculatedRemainingMs / 1000
        } else {
            // No valid in-flight countdown: first entry into this gate, or the stored
            // value is stale/outside the current ceiling. Start clean, including
            // resetting the bypass penalty state for this fresh attempt.
            val freshEnd = now + BASE_READING_MS
            prefs.edit()
                .putLong("reading_end_time_$blockedApp", freshEnd)
                .putLong("reading_ceiling_ms_$blockedApp", BASE_READING_MS)
                .putInt("reading_bypass_count_$blockedApp", 0)
                .apply()
            BASE_READING_MS / 1000
        }
        mutableStateOf(safeRemainingSeconds)
    }

    val bypassCount = remember(blockedApp, resyncToken) { prefs.getInt("reading_bypass_count_$blockedApp", 0) }

    var isTimerFinished by remember(blockedApp, resyncToken) { mutableStateOf(timeLeftSeconds <= 0) }

    // Reports cooldown-active up to MainActivity for the whole time this composable is
    // mounted with an unfinished timer, and flips off the instant the countdown hits
    // zero — a bypass attempt after that point doesn't re-penalize an already-cleared gate.
    DisposableEffect(blockedApp, resyncToken) {
        onCooldownActiveChange(!isTimerFinished)
        onDispose { onCooldownActiveChange(false) }
    }
    LaunchedEffect(isTimerFinished) {
        if (isTimerFinished) onCooldownActiveChange(false)
    }

    // Also keyed on resyncToken: on every return-to-foreground we tear down whatever
    // timer was running (with a possibly-now-stale duration) and start a fresh one from
    // the just-resynced timeLeftSeconds.
    DisposableEffect(blockedApp, resyncToken) {
        val timer = object : CountDownTimer((timeLeftSeconds * 1000).coerceAtLeast(1_000L), 1000) {
            override fun onTick(millis: Long) {
                timeLeftSeconds = millis / 1000
            }
            override fun onFinish() {
                isTimerFinished = true
                timeLeftSeconds = 0
            }
        }.start()

        onDispose { timer.cancel() }
    }

    val minutes = timeLeftSeconds / 60
    val seconds = timeLeftSeconds % 60
    val formattedTime = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "GATE 1: COGNITIVE RESET",
            color = Color(0xFFEF4444),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Target: $blockedApp",
            color = Color(0xFF9CA3AF),
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = formattedTime,
            fontSize = 52.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White
        )
        Text(
            text = "Mandatory 3-Minute Reset",
            fontSize = 13.sp,
            color = Color(0xFF6B7280)
        )
        if (bypassCount > 0) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Cooldown extended — $bypassCount home-bypass attempt${if (bypassCount == 1) "" else "s"} detected",
                fontSize = 12.sp,
                color = Color(0xFFEF4444),
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "${module.topic}: ${module.title}",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = module.content,
                    color = Color(0xFFD1D5DB),
                    fontSize = 14.sp,
                    lineHeight = 22.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onReadingComplete,
            enabled = isTimerFinished,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF3B82F6),
                disabledContainerColor = Color(0xFF262626)
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = if (isTimerFinished) "Start Assessment Quiz" else "Reading Active ($formattedTime)",
                color = if (isTimerFinished) Color.White else Color(0xFF737373),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun QuizPhaseView(module: LearningModule, onQuizPassed: () -> Unit) {
    var timeLeftSeconds by remember { mutableStateOf(600L) }
    var selectedOption by remember { mutableStateOf<Int?>(null) }
    var errorMessage by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        val timer = object : CountDownTimer(timeLeftSeconds * 1000, 1000) {
            override fun onTick(millis: Long) {
                timeLeftSeconds = millis / 1000
            }
            override fun onFinish() {
                timeLeftSeconds = 0
            }
        }.start()

        onDispose { timer.cancel() }
    }

    val minutes = timeLeftSeconds / 60
    val seconds = timeLeftSeconds % 60
    val formattedTime = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "ASSESSMENT COMPREHENSION",
            color = Color(0xFFF59E0B),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Time Remaining: $formattedTime",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(20.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = module.question,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                )
                Spacer(modifier = Modifier.height(18.dp))

                module.options.forEachIndexed { index, optionText ->
                    val isSelected = selectedOption == index
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .background(
                                color = if (isSelected) Color(0xFF2563EB) else Color(0xFF262626),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                selectedOption = index
                                errorMessage = ""
                            }
                            .padding(14.dp)
                    ) {
                        Text(
                            text = optionText,
                            color = Color.White,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }

        if (errorMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = errorMessage,
                color = Color(0xFFEF4444),
                fontSize = 13.sp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (selectedOption == module.correctIndex) {
                    onQuizPassed()
                } else {
                    errorMessage = "Incorrect answer. Review the concept and try again."
                }
            },
            enabled = selectedOption != null,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF10B981),
                disabledContainerColor = Color(0xFF262626)
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = "Authorize Session",
                color = if (selectedOption != null) Color.White else Color(0xFF737373),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun FocusForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
