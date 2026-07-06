package com.ai.assistance.operit.core.tools.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.ai.assistance.operit.services.TermuxCommandResultReceiver
import com.ai.assistance.operit.util.AppLogger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

object TermuxRunCommandClient {
    const val DEFAULT_TERMUX_PACKAGE = "com.termux"

    private const val TAG = "TermuxRunCommandClient"
    private const val DEFAULT_TIMEOUT_MS = 120_000L

    data class CommandResult(
        val packageName: String,
        val command: String,
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val errCode: Int,
        val errmsg: String,
        val timedOut: Boolean
    )

    fun runCommandPermission(packageName: String = DEFAULT_TERMUX_PACKAGE): String {
        return "$packageName.permission.RUN_COMMAND"
    }

    fun isPackageInstalled(
        context: Context,
        packageName: String = DEFAULT_TERMUX_PACKAGE
    ): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun hasRunCommandPermission(
        context: Context,
        packageName: String = DEFAULT_TERMUX_PACKAGE
    ): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            runCommandPermission(packageName)
        ) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun execute(
        context: Context,
        command: String,
        packageName: String = DEFAULT_TERMUX_PACKAGE,
        workingDirectory: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): CommandResult {
        require(command.isNotBlank()) { "Command must not be blank" }

        val appContext = context.applicationContext
        if (!isPackageInstalled(appContext, packageName)) {
            throw IllegalStateException("Termux package $packageName is not installed")
        }
        if (!hasRunCommandPermission(appContext, packageName)) {
            throw SecurityException(
                "Missing ${runCommandPermission(packageName)}. Grant the Termux RUN_COMMAND permission to Operit first."
            )
        }

        val result =
            withTimeoutOrNull(timeoutMs.coerceAtLeast(1_000L)) {
                suspendCancellableCoroutine<TermuxCommandResultReceiver.Result> { continuation ->
                    val (executionId, pendingIntent) =
                        TermuxCommandResultReceiver.createPendingIntent(appContext) { result ->
                            if (continuation.isActive) {
                                continuation.resume(result)
                            }
                        }

                    continuation.invokeOnCancellation {
                        TermuxCommandResultReceiver.removeCallback(executionId)
                    }

                    try {
                        val intent =
                            buildRunCommandIntent(
                                packageName = packageName,
                                command = command,
                                workingDirectory = workingDirectory,
                                pendingIntent = pendingIntent
                            )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            appContext.startForegroundService(intent)
                        } else {
                            appContext.startService(intent)
                        }
                    } catch (error: Exception) {
                        TermuxCommandResultReceiver.removeCallback(executionId)
                        AppLogger.e(TAG, "Failed to start Termux RunCommandService", error)
                        if (continuation.isActive) {
                            continuation.resumeWithException(error)
                        }
                    }
                }
            }

        if (result == null) {
            return CommandResult(
                packageName = packageName,
                command = command,
                stdout = "",
                stderr = "",
                exitCode = -1,
                errCode = 1,
                errmsg = "Timed out waiting for Termux command result after ${timeoutMs}ms",
                timedOut = true
            )
        }

        return CommandResult(
            packageName = packageName,
            command = command,
            stdout = result.stdout,
            stderr = result.stderr,
            exitCode = result.exitCode,
            errCode = result.errCode,
            errmsg = result.errmsg,
            timedOut = false
        )
    }

    private fun buildRunCommandIntent(
        packageName: String,
        command: String,
        workingDirectory: String?,
        pendingIntent: android.app.PendingIntent
    ): Intent {
        val home = "/data/data/$packageName/files/home"
        return Intent("$packageName.RUN_COMMAND").apply {
            component = ComponentName(packageName, "$packageName.app.RunCommandService")
            putExtra("$packageName.RUN_COMMAND_PATH", "/data/data/$packageName/files/usr/bin/bash")
            putExtra("$packageName.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
            putExtra("$packageName.RUN_COMMAND_WORKDIR", workingDirectory?.takeIf { it.isNotBlank() } ?: home)
            putExtra("$packageName.RUN_COMMAND_BACKGROUND", true)
            putExtra("$packageName.RUN_COMMAND_PENDING_INTENT", pendingIntent)
            putExtra("$packageName.RUN_COMMAND_COMMAND_LABEL", "Operit Termux command")
        }
    }
}
