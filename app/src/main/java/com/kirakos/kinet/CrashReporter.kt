package com.kirakos.kinet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

object CrashReporter {

    const val DEVELOPER_NAME = "Kirakos"
    const val DEVELOPER_TELEGRAM = "genuinely_curious" // @genuinely_curious
    const val DEVELOPER_EMAIL = "henacodes@gmail.com"
    const val DEVELOPER_PORTFOLIO = "https://kirakos.netlify.app/"

    private const val CRASH_FILE_NAME = "last_crash_log.txt"

    fun init(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashReport = generateCrashReport("App Crash in ${thread.name}", throwable)
                val crashFile = File(context.filesDir, CRASH_FILE_NAME)
                crashFile.writeText(crashReport)

                // Launch CrashReportActivity in a new task
                val intent = Intent(context, CrashReportActivity::class.java).apply {
                    putExtra("crash_log", crashReport)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                context.startActivity(intent)
            } catch (e: Throwable) {
                e.printStackTrace()
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    fun generateCrashReport(issueTitle: String, throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString()

        val runtime = Runtime.getRuntime()
        val maxMemoryMb = runtime.maxMemory() / (1024 * 1024)
        val totalMemoryMb = runtime.totalMemory() / (1024 * 1024)
        val freeMemoryMb = runtime.freeMemory() / (1024 * 1024)

        return buildString {
            appendLine("🚨 Kinet Crash Report")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("Developer: $DEVELOPER_NAME (Telegram: @$DEVELOPER_TELEGRAM | $DEVELOPER_EMAIL)")
            appendLine("Issue: $issueTitle")
            appendLine("Error: ${throwable.javaClass.simpleName}: ${throwable.localizedMessage}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android OS: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("RAM: Free ${freeMemoryMb}MB / Total ${totalMemoryMb}MB / Max ${maxMemoryMb}MB")
            appendLine("App Version: 0.2.0")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("Stack Trace:")
            appendLine(stackTrace.take(3500))
        }
    }

    /**
     * Direct deep-link to Telegram inbox with prefilled template message.
     * tg://resolve?domain=<username>&text=<encoded_text>
     * Opens chat with @Kirakos with draft message ready to send.
     */
    fun sendCrashToTelegram(context: Context, crashText: String, telegramUsername: String = DEVELOPER_TELEGRAM) {
        val cleanUsername = telegramUsername.removePrefix("@")
        val trimmed = if (crashText.length > 2800) crashText.take(2800) + "\n...[truncated]" else crashText
        val encodedText = try {
            java.net.URLEncoder.encode(trimmed, "UTF-8").replace("+", "%20")
        } catch (e: Exception) {
            Uri.encode(trimmed)
        }

        val tgUri = Uri.parse("tg://resolve?domain=$cleanUsername&text=$encodedText")
        val tgIntent = Intent(Intent.ACTION_VIEW, tgUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(tgIntent)
        } catch (e: Exception) {
            // Fallback to https://t.me/username?text=...
            val webUri = Uri.parse("https://t.me/$cleanUsername?text=$encodedText")
            val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(webIntent)
            } catch (ex: Exception) {
                // System share sheet fallback
                val chooser = Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Kinet Crash Report")
                        putExtra(Intent.EXTRA_TEXT, crashText)
                    },
                    "Send crash report via..."
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            }
        }
    }

    fun openEmail(context: Context, subject: String = "Kinet Bug Report", body: String = "") {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$DEVELOPER_EMAIL")
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            copyToClipboard(context, DEVELOPER_EMAIL, "Developer Email")
            Toast.makeText(context, "Email copied: $DEVELOPER_EMAIL", Toast.LENGTH_SHORT).show()
        }
    }

    fun openPortfolio(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DEVELOPER_PORTFOLIO)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            copyToClipboard(context, DEVELOPER_PORTFOLIO, "Portfolio URL")
        }
    }

    fun copyToClipboard(context: Context, text: String, label: String = "Kinet Crash Log") {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}
