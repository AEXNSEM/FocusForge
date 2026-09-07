package com.focusforge.app

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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

class MainActivity : ComponentActivity() {

    private val currentBlockedApp = mutableStateOf<String?>(null)
    private val currentMode = mutableStateOf<String?>("DASHBOARD")
    private val quarantineEnd = mutableStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        parseIntentData(intent)

        setContent {
            FocusForgeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF121212)
                ) {
                    val app = currentBlockedApp.value
                    val mode = currentMode.value

                    when {
                        mode == "QUARANTINE_HAMMER" && app != null -> {
                            QuarantineScreen(
                                blockedApp = app,
                                endTime = quarantineEnd.value,
                                onExit = { moveTaskToBack(true) }
                            )
                        }
                        mode == "DRIFT_CHECKPOINT" && app != null -> {
                            DriftCheckpointScreen(
                                blockedApp = app,
                                onExtend = {
                                    val prefs = getSharedPreferences("focus_forge_prefs", Context.MODE_PRIVATE)
                                    val now = System.currentTimeMillis()
                                    prefs.edit()
                                        .putLong("session_start_${app}", now)
                                        .putBoolean("checkpoint_dismissed_${app}", true)
                                        .apply()
                                    launchTarget(app)
                                },
                                onClose = {
                                    val prefs = getSharedPreferences("focus_forge_prefs", Context.MODE_PRIVATE)
                                    prefs.edit()
                                        .putBoolean("session_authorized_${app}", false)
                                        .remove("session_start_${app}")
                                        .remove("checkpoint_dismissed_${app}")
                                        .apply()
                                    moveTaskToBack(true)
                                }
                            )
                        }
                        mode == "GATE_ENTRY" && app != null -> {
                            TwoPhaseLockoutScreen(
                                blockedApp = app,
                                onComplete = {
                                    startActiveSession(app)
                                    launchTarget(app)
                                }
                            )
                        }
                        else -> {
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
        parseIntentData(intent)
    }

    private fun parseIntentData(incoming: Intent?) {
        currentBlockedApp.value = incoming?.getStringExtra("TRIGGERED_BY")
        currentMode.value = incoming?.getStringExtra("MODE") ?: "DASHBOARD"
        quarantineEnd.value = incoming?.getLongExtra("QUARANTINE_END", 0L) ?: 0L
    }

    private fun startActiveSession(packageName: String) {
        val prefs = getSharedPreferences("focus_forge_prefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        prefs.edit()
            .putBoolean("session_authorized_${packageName}", true)
            .putLong("session_start_${packageName}", now)
            .putLong("session_last_active_${packageName}", now)
            .putBoolean("checkpoint_dismissed_${packageName}", false)
            .remove("reading_end_time_${packageName}")
            .apply()
    }

    private fun launchTarget(packageName: String) {
        currentBlockedApp.value = null
        currentMode.value = "DASHBOARD"
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
        val timer = object : CountDownTimer(remainingSeconds * 1000, 1000) {
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
            text = "Target $blockedApp has been hard-locked due to a boundary violation.",
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

@Composable
fun DriftCheckpointScreen(blockedApp: String, onExtend: () -> Unit, onClose: () -> Unit) {
    BackHandler(enabled = true) { onClose() }

    var secondsLeft by remember { mutableStateOf(30L) }
    var timerDone by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val timer = object : CountDownTimer(30_000, 1000) {
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
            text = "45-MINUTE DRIFT CHECKPOINT",
            color = Color(0xFFF59E0B),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "You have maintained continuous active usage in $blockedApp for 45 minutes.\n\nTake a mandatory 30-second breath to evaluate: are you executing an intentional objective, or drifting into automated consumption?",
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
            onClick = onExtend,
            enabled = timerDone,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF3B82F6),
                disabledContainerColor = Color(0xFF262626)
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = if (timerDone) "Continue Task (45-Min Extension)" else "Reflect (${secondsLeft}s)",
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
    val prefs = remember { context.getSharedPreferences("focus_forge_prefs", Context.MODE_PRIVATE) }

    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val savedBlocked = prefs.getStringSet("blocked_packages_set", setOf("com.android.chrome")) ?: emptySet()

        val appList = packages.filter { app ->
            app.packageName != context.packageName &&
            ((app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
             app.packageName == "com.android.chrome" ||
             app.packageName.contains("youtube") ||
             app.packageName.contains("browser"))
        }.map { app ->
            InstalledApp(
                appName = pm.getApplicationLabel(app).toString(),
                packageName = app.packageName,
                isBlocked = savedBlocked.contains(app.packageName)
            )
        }.sortedBy { it.appName.lowercase(Locale.getDefault()) }

        installedApps = appList
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
                    text = "Enforces 3-min entry gate, 10-min idle reset, 45-min drift checks, & 1-hr quarantine on breach.",
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
fun TwoPhaseLockoutScreen(blockedApp: String, onComplete: () -> Unit) {
    BackHandler(enabled = true) { }

    val context = LocalContext.current
    val modules = remember { loadLearningModules(context) }
    val activeModule = remember(blockedApp) { modules.random() }

    var currentPhase by remember { mutableStateOf(LockoutPhase.READING) }

    when (currentPhase) {
        LockoutPhase.READING -> ReadingPhaseView(
            blockedApp = blockedApp,
            module = activeModule,
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
fun ReadingPhaseView(blockedApp: String, module: LearningModule, onReadingComplete: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("focus_forge_prefs", Context.MODE_PRIVATE) }
    val totalSeconds = 180L

    var timeLeftSeconds by remember(blockedApp) {
        val now = System.currentTimeMillis()
        val storedEndTime = prefs.getLong("reading_end_time_${blockedApp}", 0L)
        val remaining = if (storedEndTime > now) {
            (storedEndTime - now) / 1000
        } else {
            val newEnd = now + (totalSeconds * 1000L)
            prefs.edit().putLong("reading_end_time_${blockedApp}", newEnd).apply()
            totalSeconds
        }
        mutableStateOf(remaining)
    }

    var isTimerFinished by remember(blockedApp) { mutableStateOf(timeLeftSeconds <= 0) }

    DisposableEffect(blockedApp) {
        val timer = object : CountDownTimer(timeLeftSeconds * 1000, 1000) {
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
