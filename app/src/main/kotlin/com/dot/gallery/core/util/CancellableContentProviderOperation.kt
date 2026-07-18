package com.dot.gallery.core.util

import android.os.CancellationSignal
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

internal suspend fun <Result> runCancellableContentProviderOperation(
    disposeResult: (Result) -> Unit,
    operation: (CancellationSignal) -> Result,
): Result {
    return suspendCancellableCoroutine { continuation ->
        val cancellationSignal = CancellationSignal()
        continuation.invokeOnCancellation {
            cancellationSignal.cancel()
        }

        val result = try {
            operation(cancellationSignal)
        } catch (exception: Throwable) {
            if (continuation.isActive) {
                continuation.resumeWithException(exception)
            }
            return@suspendCancellableCoroutine
        }

        continuation.resume(result) { _, cancelledResult, _ ->
            disposeResult(cancelledResult)
        }
    }
}
