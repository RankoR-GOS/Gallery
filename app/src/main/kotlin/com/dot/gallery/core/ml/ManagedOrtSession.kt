package com.dot.gallery.core.ml

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.util.Log

class ManagedOrtSession internal constructor(
    internal val environment: OrtEnvironment,
    internal val session: OrtSession,
    private val options: OrtSession.SessionOptions,
) : AutoCloseable {

    override fun close() {
        try {
            session.close()
        } catch (exception: OrtException) {
            Log.e(TAG, "Unable to close ONNX session", exception)
        } finally {
            options.close()
        }
    }

    private companion object {
        private const val TAG = "ManagedOrtSession"
    }
}
