/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.core.ml

import android.content.Context
import com.dot.gallery.feature_node.presentation.util.printInfo
import com.dot.gallery.feature_node.presentation.util.printWarning
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

enum class ModelStatus {
    CHECKING,
    READY,
    ERROR,
}

@Singleton
class ModelManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val _status = MutableStateFlow(ModelStatus.CHECKING)
    val status: StateFlow<ModelStatus> = _status.asStateFlow()

    private val mutex = Mutex()

    val isReady: Boolean
        get() {
            return _status.value == ModelStatus.READY
        }

    suspend fun refreshStatus() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                refreshStatusFromBundledAssets()
            }
        }
    }

    fun checkModelsPresent(): Boolean {
        return REQUIRED_FILES.all { fileName ->
            runCatching {
                getBundledAssetSize(name = fileName) > 0L
            }.getOrElse { exception ->
                printWarning("ModelManager: asset $fileName is not openable: ${exception.message}")
                false
            }
        }
    }

    fun <T> withMappedBundledModel(name: String, block: (MappedByteBuffer) -> T): T {
        if (!isReady) throw ModelsNotAvailableException()
        requireKnownModel(name = name)

        val mappedBuffer = try {
            context.assets.openFd(name).use { descriptor ->
                val length = descriptor.length
                if (length <= 0L) {
                    throw ModelsNotAvailableException(message = "Model asset is empty: $name")
                }

                FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                    channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        descriptor.startOffset,
                        length,
                    )
                }
            }
        } catch (exception: IOException) {
            throw ModelsNotAvailableException(
                message = "Model asset is unavailable: $name",
                cause = exception,
            )
        }

        return block(mappedBuffer)
    }

    fun openBundledModelInputStream(name: String): InputStream {
        if (!isReady) throw ModelsNotAvailableException()
        requireKnownModel(name = name)

        try {
            return context.assets.open(name)
        } catch (exception: IOException) {
            throw ModelsNotAvailableException(
                message = "Model asset is unavailable: $name",
                cause = exception,
            )
        }
    }

    private fun refreshStatusFromBundledAssets() {
        if (checkModelsPresent()) {
            _status.value = ModelStatus.READY
            printInfo("ModelManager: Bundled models are available")
        } else {
            _status.value = ModelStatus.ERROR
            printWarning("ModelManager: Bundled models are unavailable")
        }
    }

    private fun getBundledAssetSize(name: String): Long {
        requireKnownModel(name = name)
        return context.assets.openFd(name).use { descriptor ->
            descriptor.length
        }
    }

    private fun requireKnownModel(name: String) {
        require(name in REQUIRED_FILES) { "Unknown model file: $name" }
    }

    companion object {
        val REQUIRED_FILES = listOf(
            "visual_quant.onnx",
            "textual_quant.onnx",
            "vocab.json",
            "merges.txt",
        )
    }
}

class ModelsNotAvailableException(
    message: String = "ML models are unavailable in this build.",
    cause: Throwable? = null,
) : RuntimeException(message, cause)
