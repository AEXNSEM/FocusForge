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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentBlockedApp.value = intent.getStringExtra("TRIGGERED_BY")

        setContent {
            FocusForgeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF121212)
                ) {
                    val blockedApp = currentBlockedApp.value
                    if (blockedApp != null) {
                        TwoPhaseLockoutScreen(
                            blockedApp = blockedApp,
                            onComplete = { grantWindowMinutes ->
                                grantAccessPass(blockedApp, grantWindowMinutes)
                                currentBlockedApp.value = null
                                moveTaskToBack(true)
                            }
                        )
                    } else {
                        DashboardScreen()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        currentBlockedApp.value = intent?.getStringExtra("TRIGGERED_BY")
    }

    private fun grantAccessPass(packageName: String, minutes: Int) {
        val prefs = getSharedPreferences("focus_forge_prefs", Context.MODE_PRIVATE)
        val expiryTime = System.currentTimeMillis() + (minutes * 60 * 1000L)
        prefs.edit().putLong("unlock_expiry_${packageName}", expiryTime).apply()
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
                id = "sys_01",
                topic = "Systems Thinking",
                title = "Feedback Loops & System Delays",
                content = "Complex adaptive systems are governed by positive and negative feedback loops. When a significant time delay exists between an action and its feedback, decision-makers overcompensate, creating severe instability.",
                question = "What primary problem arises from delays in feedback loops?",
                options = listOf(
                    "Instant equilibrium",
                    "Overcorrection and systemic oscillation",
                    "Total system shutdown"
                ),
                correctIndex = 1
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
            // Filter out system apps and FocusForge itself, but keep user-installed browsers/apps
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
                    text = "Select Target Apps to Intercept",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = "Toggled apps will trigger the reading and assessment cycle on launch.",
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
fun TwoPhaseLockoutScreen(blockedApp: String, onComplete: (Int) -> Unit) {
    BackHandler(enabled = true) { }

    val context = LocalContext.current
    val modules = remember { loadLearningModules(context) }
    val activeModule = remember { modules.random() }

    var currentPhase by remember { mutableStateOf(LockoutPhase.READING) }

    when (currentPhase) {
        LockoutPhase.READING -> ReadingPhaseView(
            blockedApp = blockedApp,
            module = activeModule,
            onReadingComplete = { currentPhase = LockoutPhase.QUIZ }
        )
        LockoutPhase.QUIZ -> QuizPhaseView(
            module = activeModule,
            onQuizPassed = { currentPhase = LockoutPhase.SUCCESS }
        )
        LockoutPhase.SUCCESS -> SuccessPhaseView(
            blockedApp = blockedApp,
            onDismiss = { grantMinutes -> onComplete(grantMinutes) }
        )
    }
}

@Composable
fun ReadingPhaseView(blockedApp: String, module: LearningModule, onReadingComplete: () -> Unit) {
    var timeLeftSeconds by remember { mutableStateOf(300L) }
    var isTimerFinished by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val timer = object : CountDownTimer(timeLeftSeconds * 1000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                timeLeftSeconds = millisUntilFinished / 1000
            }

            override fun onFinish() {
                isTimerFinished = true
                timeLeftSeconds = 0
            }
        }.start()

        onDispose {
            timer.cancel()
        }
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
            text = "PHASE 1: COGNITIVE ENGAGEMENT",
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
            text = "Mandatory Reading Duration",
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
                text = if (isTimerFinished) "Start Assessment Quiz" else "Reading Phase Locked ($formattedTime)",
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
            override fun onTick(millisUntilFinished: Long) {
                timeLeftSeconds = millisUntilFinished / 1000
            }

            override fun onFinish() {
                timeLeftSeconds = 0
            }
        }.start()

        onDispose {
            timer.cancel()
        }
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
            text = "PHASE 2: ASSESSMENT QUIZ",
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
                text = "Submit Answer",
                color = if (selectedOption != null) Color.White else Color(0xFF737373),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun SuccessPhaseView(blockedApp: String, onDismiss: (Int) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Threshold Completed",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF10B981)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Cognitive engagement verified. Choose how you wish to proceed.",
            color = Color(0xFFB0B0B0),
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(28.dp))

        Button(
            onClick = { onDismiss(10) },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(text = "Proceed to $blockedApp (10 Min Window)", color = Color.White, fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = { onDismiss(0) },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(text = "Exit to Home (Keep App Locked)", color = Color(0xFF9CA3AF))
        }
    }
}

@Composable
fun FocusForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
