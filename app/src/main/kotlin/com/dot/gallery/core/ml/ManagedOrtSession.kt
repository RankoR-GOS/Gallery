package com.dot.gallery.core.ml

import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import android.util.Log
import java.util.concurrent.locks.ReentrantReadWriteLock

class ManagedOrtSession internal constructor(
    internal val environment: OrtEnvironment,
    private val session: OrtSession,
    private val options: OrtSession.SessionOptions,
) : AutoCloseable {
    private val lock = ReentrantReadWriteLock(true)
    private var closed = false

    fun <T> run(
        inputs: Map<String, OnnxTensorLike>,
        block: (OrtSession.Result) -> T,
    ): T {
        val readLock = lock.readLock()
        readLock.lock()
        try {
            if (closed) {
                throw OrtException("ONNX session is closed")
            }
            session.run(inputs).use { result ->
                return block(result)
            }
        } finally {
            readLock.unlock()
        }
    }

    override fun close() {
        val writeLock = lock.writeLock()
        writeLock.lock()
        try {
            if (closed) {
                return
            }

            closed = true

            try {
                session.close()
            } catch (exception: OrtException) {
                Log.e(TAG, "Unable to close ONNX session", exception)
            }
            try {
                options.close()
            } catch (exception: OrtException) {
                Log.e(TAG, "Unable to close ONNX session options", exception)
            }
        } finally {
            writeLock.unlock()
        }
    }

    private companion object {
        private const val TAG = "ManagedOrtSession"
    }
}
