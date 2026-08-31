package com.focusforge.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val triggeredApp = intent.getStringExtra("TRIGGERED_BY")

        setContent {
            FocusForgeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF121212)
                ) {
                    if (triggeredApp != null) {
                        LockoutScreen(blockedApp = triggeredApp)
                    } else {
                        DashboardScreen()
                    }
                }
            }
        }
    }
}

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
                    text = "System Status",
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    fontSize = 18.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Ensure Accessibility permissions are enabled for automatic package interception.",
                    color = Color(0xFFB0B0B0),
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(text = "Enable Accessibility Service", color = Color.White)
                }
            }
        }
    }
}

@Composable
fun LockoutScreen(blockedApp: String) {
    // 5-minute reading timer (300 seconds)
    var timeLeftSeconds by remember { mutableStateOf(300L) }
    var isTimerFinished by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val timer = object : CountDownTimer(300000, 1000) {
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
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = "DOPAMINE INTERCEPT ACTIVE",
            color = Color(0xFFEF4444),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "App Blocked: $blockedApp",
            color = Color(0xFF9CA3AF),
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(24.dp))

        // Timer Display
        Text(
            text = formattedTime,
            fontSize = 54.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White
        )
        Text(
            text = "Mandatory Cognitive Engagement",
            fontSize = 13.sp,
            color = Color(0xFF6B7280)
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Mandatory Reading Material
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Module: Market Structure & Liquidity Pools",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Liquidity represents the concentration of pending orders (stop losses and buy/sell stop orders) resting above key swing highs and below swing lows. Institutional participants require substantial counterpart volume to fill large orders without slippage.\n\n" +
                            "When retail traders place standard stop-loss orders directly below obvious double-bottom formations, they create a dense pocket of sell stops (sell liquidity). Institutions engineer price runs into these pools to accumulate long positions at a discount before reversing the directional trend.",
                    color = Color(0xFFD1D5DB),
                    fontSize = 14.sp,
                    lineHeight = 22.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        Button(
            onClick = { /* Quiz Phase Transition */ },
            enabled = isTimerFinished,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF10B981),
                disabledContainerColor = Color(0xFF374151)
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = if (isTimerFinished) "Proceed to Assessment Quiz" else "Reading Phase Locked ($formattedTime)",
                color = if (isTimerFinished) Color.White else Color(0xFF9CA3AF),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun FocusForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
