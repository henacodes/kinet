package com.kirakos.kinet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

object CrashReporter {

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
            appendLine("🚨 Kinet App Crash Report")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("Issue: $issueTitle")
            appendLine("Error: ${throwable.javaClass.simpleName}: ${throwable.localizedMessage}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android OS: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("RAM: Free ${freeMemoryMb}MB / Total ${totalMemoryMb}MB / Max ${maxMemoryMb}MB")
            appendLine("App Version: 1.0 (release)")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("Stack Trace:")
            appendLine(stackTrace.take(3500))
        }
    }

    fun sendCrashToTelegram(context: Context, crashText: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Kinet App Crash Report")
            putExtra(Intent.EXTRA_TEXT, crashText)
            setPackage("org.telegram.messenger")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(sendIntent)
        } catch (e: Exception) {
            // If main Telegram app isn't found, open system chooser (Telegram X, web, etc.)
            val chooser = Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Kinet App Crash Report")
                    putExtra(Intent.EXTRA_TEXT, crashText)
                },
                "Send crash report via..."
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }
    }

    fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Kinet Crash Log", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Crash report copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}
