package com.ai.assistance.operit.services

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TermuxCommandResultReceiver : BroadcastReceiver() {

    data class Result(
        val executionId: Int,
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val errCode: Int,
        val errmsg: String
    )

    companion object {
        private const val TAG = "TermuxResultReceiver"
        private const val EXTRA_EXECUTION_ID = "execution_id"
        private const val EXTRA_RESULT_BUNDLE = "result"
        private const val EXTRA_STDOUT = "stdout"
        private const val EXTRA_STDERR = "stderr"
        private const val EXTRA_EXIT_CODE = "exitCode"
        private const val EXTRA_ERR = "err"
        private const val EXTRA_ERRMSG = "errmsg"
        private const val TERMUX_SUCCESS_ERR_CODE = -1

        private val nextExecutionId = AtomicInteger(1)
        private val callbacks = ConcurrentHashMap<Int, (Result) -> Unit>()

        fun createPendingIntent(
            context: Context,
            callback: (Result) -> Unit
        ): Pair<Int, PendingIntent> {
            val executionId = nextExecutionId.getAndIncrement()
            callbacks[executionId] = callback

            val intent =
                Intent(context, TermuxCommandResultReceiver::class.java)
                    .putExtra(EXTRA_EXECUTION_ID, executionId)

            val flags =
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE
                    } else {
                        0
                    })

            return executionId to
                PendingIntent.getBroadcast(context, executionId, intent, flags)
        }

        fun removeCallback(executionId: Int) {
            callbacks.remove(executionId)
        }

        fun isTermuxTransportError(result: Result): Boolean {
            return result.errCode != TERMUX_SUCCESS_ERR_CODE && result.errmsg.isNotBlank()
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        val executionId = intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
        if (executionId == -1) {
            AppLogger.e(TAG, "Received Termux command result without execution id")
            return
        }

        val resultBundle = intent.getBundleExtra(EXTRA_RESULT_BUNDLE)
        if (resultBundle == null) {
            AppLogger.e(TAG, "Received Termux command result without result bundle")
            callbacks.remove(executionId)?.invoke(
                Result(
                    executionId = executionId,
                    stdout = "",
                    stderr = "",
                    exitCode = -1,
                    errCode = 1,
                    errmsg = "Termux did not return a result bundle"
                )
            )
            return
        }

        val result =
            Result(
                executionId = executionId,
                stdout = resultBundle.getString(EXTRA_STDOUT, ""),
                stderr = resultBundle.getString(EXTRA_STDERR, ""),
                exitCode =
                    if (resultBundle.containsKey(EXTRA_EXIT_CODE)) {
                        resultBundle.getInt(EXTRA_EXIT_CODE)
                    } else {
                        -1
                    },
                errCode =
                    if (resultBundle.containsKey(EXTRA_ERR)) {
                        resultBundle.getInt(EXTRA_ERR)
                    } else {
                        TERMUX_SUCCESS_ERR_CODE
                    },
                errmsg = resultBundle.getString(EXTRA_ERRMSG, "")
            )

        callbacks.remove(executionId)?.invoke(result)
            ?: AppLogger.w(TAG, "No callback registered for Termux execution id $executionId")
    }
}
