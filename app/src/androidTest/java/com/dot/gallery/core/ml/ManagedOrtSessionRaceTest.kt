package com.dot.gallery.core.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.nio.IntBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ManagedOrtSessionRaceTest {
    private val environment = OrtEnvironment.getEnvironment()

    @Test
    fun closeWaitsForRunningTextInferenceResultConsumer() {
        repeat(ITERATIONS) {
            verifyCloseWaitsForRun()
        }
    }

    private fun verifyCloseWaitsForRun() {
        val runEntered = CountDownLatch(1)
        val releaseRun = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val session = openTextSession()

        val runner = Thread(
            {
                runTextInference(
                    session = session,
                    runEntered = runEntered,
                    releaseRun = releaseRun,
                    failure = failure,
                )
            },
            "managed-ort-session-run",
        )
        val closer = Thread(
            {
                session.close()
                closeReturned.countDown()
            },
            "managed-ort-session-close",
        )

        runner.start()
        assertTrue(
            "ONNX run did not enter result consumer",
            runEntered.await(10, TimeUnit.SECONDS)
        )

        closer.start()
        assertFalse(
            "close returned while a run result was still in use",
            closeReturned.await(250, TimeUnit.MILLISECONDS)
        )

        releaseRun.countDown()
        runner.join(10_000)
        closer.join(10_000)

        assertFalse("ONNX run thread did not finish", runner.isAlive)
        assertFalse("ONNX close thread did not finish", closer.isAlive)
        assertTrue(
            "close did not return after run completed",
            closeReturned.await(0, TimeUnit.MILLISECONDS)
        )
        assertNull("ONNX run failed", failure.get())
    }

    private fun runTextInference(
        session: ManagedOrtSession,
        runEntered: CountDownLatch,
        releaseRun: CountDownLatch,
        failure: AtomicReference<Throwable>,
    ) {
        try {
            OnnxTensor
                .createTensor(environment, textInput(), TEXT_INPUT_SHAPE)
                .use { inputIdsTensor ->
                    OnnxTensor
                        .createTensor(environment, attentionMask(), TEXT_INPUT_SHAPE)
                        .use { attentionMaskTensor ->
                            val inputs = mapOf(
                                "input_ids" to inputIdsTensor,
                                "attention_mask" to attentionMaskTensor,
                            )
                            session.run(inputs = inputs) { result ->
                                runEntered.countDown()
                                if (!releaseRun.await(10, TimeUnit.SECONDS)) {
                                    fail("Timed out waiting to release ONNX result")
                                }
                                assertEquals("Unexpected ONNX output count", 1, result.size())
                            }
                        }
                }
        } catch (throwable: Throwable) {
            failure.set(throwable)
        }
    }

    private fun openTextSession(): ManagedOrtSession {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val options = OrtSession.SessionOptions()
        var closeOptions = true
        try {
            context.assets.openFd(TEXT_MODEL_NAME).use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                    options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    val model = channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        descriptor.startOffset,
                        descriptor.length,
                    )
                    val session = environment.createSession(model, options)
                    closeOptions = false
                    return ManagedOrtSession(
                        environment = environment,
                        session = session,
                        options = options,
                    )
                }
            }
        } finally {
            if (closeOptions) {
                options.close()
            }
        }
    }

    private fun textInput(): IntBuffer {
        val buffer = IntBuffer.allocate(TEXT_TOKEN_COUNT)
        buffer.put(TOKEN_BOS)
        buffer.put(TOKEN_EOS)
        while (buffer.position() < TEXT_TOKEN_COUNT) {
            buffer.put(0)
        }
        buffer.flip()
        return buffer
    }

    private fun attentionMask(): IntBuffer {
        val buffer = IntBuffer.allocate(TEXT_TOKEN_COUNT)
        buffer.put(1)
        buffer.put(1)
        while (buffer.position() < TEXT_TOKEN_COUNT) {
            buffer.put(0)
        }
        buffer.flip()
        return buffer
    }

    private companion object {
        private const val ITERATIONS = 10
        private const val TEXT_MODEL_NAME = "textual_quant.onnx"
        private const val TEXT_TOKEN_COUNT = 77
        private const val TOKEN_BOS = 49406
        private const val TOKEN_EOS = 49407
        private val TEXT_INPUT_SHAPE = longArrayOf(1, TEXT_TOKEN_COUNT.toLong())
    }
}
