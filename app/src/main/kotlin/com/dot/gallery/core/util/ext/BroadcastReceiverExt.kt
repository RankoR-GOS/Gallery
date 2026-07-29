package com.dot.gallery.core.util.ext

import android.content.BroadcastReceiver
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs [block] off the main thread while keeping the broadcast alive via
 * [BroadcastReceiver.goAsync], finishing it once [block] completes.
 *
 * A receiver has no lifecycle to scope work to, so the coroutine runs in a detached scope.
 * Failures are logged rather than propagated: an uncaught throwable would otherwise reach the
 * default handler and take down the process over background bookkeeping.
 *
 * [block] must stay well inside the broadcast timeout — it is not cancellable, since the work
 * it wraps is blocking I/O with no suspension points for a timeout to act on.
 *
 * [tag] is passed in rather than derived from the class name, which R8 rewrites in release builds.
 */
internal fun BroadcastReceiver.goAsync(
    tag: String,
    dispatcher: CoroutineDispatcher,
    block: suspend CoroutineScope.() -> Unit,
) {
    val pendingResult = goAsync()
    CoroutineScope(SupervisorJob() + dispatcher).launch {
        try {
            block()
        } catch (exception: Exception) {
            Log.w(tag, "Background broadcast work failed", exception)
        } finally {
            pendingResult.finish()
        }
    }
}
