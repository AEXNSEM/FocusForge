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
import java.util.Locale

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

enum class LockoutPhase {
    READING,
    QUIZ,
    SUCCESS
}

data class QuizQuestion(
    val question: String,
    val options: List<String>,
    val correctIndex: Int
)

@Composable
fun DashboardScreen() {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "FocusForge",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "High-Friction Dopamine Interceptor",
            fontSize = 14.sp,
            color = Color(0xFF888888)
        )
        Spacer(modifier = Modifier.height(36.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Shield Operational",
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF10B981),
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Interception running. Launching blocked targets forces cognitive review before access is evaluated.",
                    color = Color(0xFFB0B0B0),
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(text = "Manage Accessibility Settings", color = Color.White)
                }
            }
        }
    }
}

@Composable
fun TwoPhaseLockoutScreen(blockedApp: String, onComplete: (Int) -> Unit) {
    BackHandler(enabled = true) { /* Block hardware back */ }

    var currentPhase by remember { mutableStateOf(LockoutPhase.READING) }

    when (currentPhase) {
        LockoutPhase.READING -> ReadingPhaseView(
            blockedApp = blockedApp,
            onReadingComplete = { currentPhase = LockoutPhase.QUIZ }
        )
        LockoutPhase.QUIZ -> QuizPhaseView(
            onQuizPassed = { currentPhase = LockoutPhase.SUCCESS }
        )
        LockoutPhase.SUCCESS -> SuccessPhaseView(
            blockedApp = blockedApp,
            onDismiss = { grantMinutes -> onComplete(grantMinutes) }
        )
    }
}

@Composable
fun ReadingPhaseView(blockedApp: String, onReadingComplete: () -> Unit) {
    // 5 minutes (300s)
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
            text = "Blocked Target: $blockedApp",
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
            text = "Mandatory Reading Timer",
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
                    text = "Market Microstructure: Liquidity Sweeps",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "A liquidity sweep occurs when price aggressively drives beyond an obvious support or resistance level to trigger clustered stop orders before immediately reversing.\n\n" +
                            "Large institutions cannot enter substantial positions in regular trading volume without suffering severe slippage. By pushing price past common retail stop-loss zones, they force massive stop-loss sell orders to trigger.\n\n" +
                            "The institution absorbs this concentrated selling volume at a discount, filling their long orders completely before letting the market reverse higher.",
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
fun QuizPhaseView(onQuizPassed: () -> Unit) {
    var timeLeftSeconds by remember { mutableStateOf(600L) }
    var selectedOption by remember { mutableStateOf<Int?>(null) }
    var errorMessage by remember { mutableStateOf("") }

    val sampleQuestion = remember {
        QuizQuestion(
            question = "Why do institutional buyers push price below key support levels prior to an upward trend reversal?",
            options = listOf(
                "To trigger sell stop-loss orders and absorb the necessary sell volume at a discount.",
                "To allow retail traders to open profitable short positions.",
                "Because support levels automatically guarantee a permanent price collapse.",
                "To reduce overall trading exchange fees."
            ),
            correctIndex = 0
        )
    }

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
                    text = sampleQuestion.question,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                )
                Spacer(modifier = Modifier.height(18.dp))

                sampleQuestion.options.forEachIndexed { index, optionText ->
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
                if (selectedOption == sampleQuestion.correctIndex) {
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

        // Option A: Intentional 10-Minute Work Window
        Button(
            onClick = { onDismiss(10) },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(text = "Proceed to $blockedApp (10 Min Window)", color = Color.White, fontWeight = FontWeight.SemiBold)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Option B: Pure Deterrence (No access, close to home)
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
