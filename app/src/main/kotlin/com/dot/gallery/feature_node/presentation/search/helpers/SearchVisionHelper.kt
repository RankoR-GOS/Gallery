package com.dot.gallery.feature_node.presentation.search.helpers

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.graphics.Bitmap
import com.dot.gallery.core.ml.ManagedOrtSession
import com.dot.gallery.core.ml.ModelInferenceException
import com.dot.gallery.core.ml.ModelManager
import com.dot.gallery.core.ml.ModelsNotAvailableException
import com.dot.gallery.feature_node.presentation.search.tokenizer.ClipTokenizer
import com.dot.gallery.feature_node.presentation.search.util.centerCrop
import com.dot.gallery.feature_node.presentation.search.util.normalizeL2
import com.dot.gallery.feature_node.presentation.search.util.preProcess
import com.dot.gallery.feature_node.presentation.util.printDebug
import java.nio.IntBuffer
import java.util.EnumSet

class SearchVisionHelper(private val modelManager: ModelManager) {
    private val tokenizer by lazy {
        ClipTokenizer(
            vocabInputStreamProvider = {
                modelManager.openBundledModelInputStream(name = "vocab.json")
            },
            mergesInputStreamProvider = {
                modelManager.openBundledModelInputStream(name = "merges.txt")
            },
        )
    }
    private val ortEnv = OrtEnvironment.getEnvironment()

    fun setupVisionSession(): ManagedOrtSession {
        return createOrtSessionWithFallback(modelName = "visual_quant.onnx")
    }

    fun setupTextSession(): ManagedOrtSession {
        return createOrtSessionWithFallback(modelName = "textual_quant.onnx")
    }

    private fun createOrtSessionWithFallback(modelName: String): ManagedOrtSession {
        if (!modelManager.isReady) throw ModelsNotAvailableException()

        val isQuantized = modelName.contains("quant")

        val options = OrtSession.SessionOptions()
        var closeOptions = true
        try {
            options.apply {
                // Enable all graph optimizations (constant folding, op fusion, etc.)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                // Use multiple CPU threads for intra-op parallelism (e.g. matmul)
                setIntraOpNumThreads(
                    Runtime.getRuntime().availableProcessors().coerceIn(
                        minimumValue = 2,
                        maximumValue = 4,
                    ),
                )
            }

            // Only try NNAPI for non-quantized models.
            // Quantized (INT8) models cause severe NNAPI overhead:
            //  - NNAPI compilation can take minutes for complex models like CLIP
            //  - Many quantized ops aren't NNAPI-supported, causing graph partitioning
            //    with expensive CPU/NNAPI memory copies at each boundary
            //  - USE_FP16 flag with INT8 model adds unnecessary type conversions
            if (!isQuantized) {
                try {
                    printDebug("Available providers: ${OrtEnvironment.getAvailableProviders()}")
                    printDebug("Using NNAPI for inference")
                    options.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
                } catch (exception: Exception) {
                    printDebug("NNAPI not available, falling back to CPU: ${exception.message}")
                }
            } else {
                printDebug("Using optimized CPU inference for quantized model: $modelName")
            }

            val session = modelManager.withMappedBundledModel(name = modelName) { modelBuffer ->
                ortEnv.createSession(modelBuffer, options)
            }
            closeOptions = false
            return ManagedOrtSession(
                environment = ortEnv,
                session = session,
                options = options,
            )
        } catch (exception: OrtException) {
            throw ModelInferenceException(
                message = "Unable to create ONNX session for $modelName",
                cause = exception,
            )
        } finally {
            if (closeOptions) {
                options.close()
            }
        }
    }

    fun getTextEmbedding(session: ManagedOrtSession, text: String): FloatArray {
        val tokenBOS = 49406
        val tokenEOS = 49407

        val queryFilter = Regex("[^A-Za-z0-9 ]")
        // Tokenize
        val textClean = queryFilter.replace(text, "").lowercase()
        var tokens: MutableList<Int> = ArrayList()
        tokens.add(tokenBOS)
        tokens.addAll(tokenizer.encode(textClean))
        tokens.add(tokenEOS)

        var mask: MutableList<Int> = ArrayList()
        for (i in 0 until tokens.size) {
            mask.add(1)
        }
        while (tokens.size < 77) {
            tokens.add(0)
            mask.add(0)
        }
        tokens = tokens.subList(0, 77)
        mask = mask.subList(0, 77)

        // Convert to tensor
        val inputShape = longArrayOf(1, 77)
        val inputIds = IntBuffer.allocate(1 * 77)
        inputIds.rewind()
        for (i in 0 until 77) {
            inputIds.put(tokens[i])
        }
        inputIds.rewind()
        val attentionMask = IntBuffer.allocate(1 * 77)
        attentionMask.rewind()
        for (i in 0 until 77) {
            attentionMask.put(mask[i])
        }
        attentionMask.rewind()
        try {
            return OnnxTensor.createTensor(session.environment, inputIds, inputShape).use { inputIdsTensor ->
                OnnxTensor.createTensor(session.environment, attentionMask, inputShape).use { attentionMaskTensor ->
                    val inputMap = mapOf(
                        "input_ids" to inputIdsTensor,
                        "attention_mask" to attentionMaskTensor,
                    )
                    session.session.run(inputMap).use { result ->
                        normalizeL2(extractSingleFloatOutput(result = result))
                    }
                }
            }
        } catch (exception: OrtException) {
            throw ModelInferenceException(
                message = "Text model inference failed",
                cause = exception,
            )
        }
    }

    fun getImageEmbedding(session: ManagedOrtSession, bitmap: Bitmap): FloatArray {
        val rawBitmap = centerCrop(bitmap, 224)
        val inputShape = longArrayOf(1, 3, 224, 224)
        val inputName = "pixel_values"
        val imgData = preProcess(rawBitmap)

        try {
            return OnnxTensor.createTensor(session.environment, imgData, inputShape).use { inputTensor ->
                session.session.run(mapOf(inputName to inputTensor)).use { result ->
                    normalizeL2(extractSingleFloatOutput(result = result))
                }
            }
        } catch (exception: OrtException) {
            throw ModelInferenceException(
                message = "Image model inference failed",
                cause = exception,
            )
        }
    }

    private fun extractSingleFloatOutput(result: OrtSession.Result): FloatArray {
        if (result.size() != 1) {
            throw ModelInferenceException(
                message = "Expected exactly one ONNX output, got ${result.size()}",
            )
        }

        val output = try {
            result.get(0).value
        } catch (exception: OrtException) {
            throw ModelInferenceException(
                message = "Unable to read ONNX output",
                cause = exception,
            )
        } as? Array<*>
            ?: throw ModelInferenceException(
                message = "Expected ONNX output to be an array",
            )
        if (output.size != 1) {
            throw ModelInferenceException(
                message = "Expected exactly one ONNX output batch, got ${output.size}",
            )
        }

        return output[0] as? FloatArray
            ?: throw ModelInferenceException(
                message = "Expected ONNX output batch to be a float array",
            )
    }

    companion object {

        const val THRESHOLD = 0.2f
    }

}
