package com.example.offlinetranslator.mlkit

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface PendingOperation<T> {
    /** Cancelling this wait must not cancel the provider operation. */
    suspend fun await(): T

    /** Runs exactly once at actual provider completion, including provider cancellation. */
    fun onComplete(listener: () -> Unit)
}

// Completion releases leases independently of Android's main queue or a cancelled owner scope.
private val taskCompletionExecutor = Executor { command -> command.run() }

internal class GoogleTaskOperation<T : Any>(
    private val task: Task<T>,
) : PendingOperation<T> {
    override suspend fun await(): T = suspendCancellableCoroutine { continuation ->
        task.addOnCompleteListener(taskCompletionExecutor) { completed ->
            if (continuation.isActive) {
                when {
                    completed.isCanceled -> continuation.cancel()
                    completed.isSuccessful -> continuation.resume(completed.result)
                    else -> continuation.resumeWithException(
                        checkNotNull(completed.exception) { "Failed Google Task has no exception" },
                    )
                }
            }
        }
        // No cancellation callback: cancellation stops only this continuation's wait.
    }

    override fun onComplete(listener: () -> Unit) {
        task.addOnCompleteListener(taskCompletionExecutor) { listener() }
    }
}

internal class GoogleVoidTaskOperation(
    private val task: Task<Void>,
) : PendingOperation<Unit> {
    override suspend fun await(): Unit = suspendCancellableCoroutine { continuation ->
        task.addOnCompleteListener(taskCompletionExecutor) { completed ->
            if (continuation.isActive) {
                when {
                    completed.isCanceled -> continuation.cancel()
                    completed.isSuccessful -> continuation.resume(Unit)
                    else -> continuation.resumeWithException(
                        checkNotNull(completed.exception) { "Failed Google Task has no exception" },
                    )
                }
            }
        }
    }

    override fun onComplete(listener: () -> Unit) {
        task.addOnCompleteListener(taskCompletionExecutor) { listener() }
    }
}
