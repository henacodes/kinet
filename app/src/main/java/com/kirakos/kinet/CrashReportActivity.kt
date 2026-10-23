package com.kirakos.kinet

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirakos.kinet.ui.theme.KinetTheme

class CrashReportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val crashLog = intent.getStringExtra("crash_log") ?: "No crash log available."

        setContent {
            KinetTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    CrashReportScreen(
                        crashLog = crashLog,
                        onSendTelegram = { CrashReporter.sendCrashToTelegram(this, crashLog) },
                        onOpenEmail = { CrashReporter.openEmail(this, "Kinet App Crash Report", crashLog) },
                        onCopy = { CrashReporter.copyToClipboard(this, crashLog) },
                        onRestart = {
                            val intent = Intent(this, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            }
                            startActivity(intent)
                            finish()
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun CrashReportScreen(
    crashLog: String,
    onSendTelegram: () -> Unit,
    onOpenEmail: () -> Unit,
    onCopy: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = "Something went wrong",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Light,
                    fontSize = 26.sp,
                    letterSpacing = (-0.5).sp
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "An unhandled error occurred. Please send this report to the developer via Telegram to help diagnose the issue.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Developer Contact Info Card (Calm surface tint + hairline border, zero shadow)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Developer contact",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Telegram: @${CrashReporter.DEVELOPER_TELEGRAM}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onSendTelegram() }
                    )
                    Text(
                        text = CrashReporter.DEVELOPER_EMAIL,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { onOpenEmail() }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Scrollable Log Box (Copiable via SelectionContainer, surface tint + border, zero shadow)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(12.dp))
                    .padding(14.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = crashLog,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action Buttons Row (No shadows)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilledTonalButton(
                onClick = onSendTelegram,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                elevation = null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Send crash report via Telegram",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TextButton(
                    onClick = onCopy,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Copy log", color = MaterialTheme.colorScheme.primary)
                }

                TextButton(
                    onClick = onRestart,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Restart app", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
