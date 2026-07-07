package com.dot.gallery.feature_node.data.externalcrop

import android.app.ComponentCaller
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.IntentCompat
import com.dot.gallery.feature_node.domain.externalcrop.ExternalCropIntentParser
import com.dot.gallery.feature_node.domain.model.editor.crop.ExternalCropRequest
import javax.inject.Inject

internal class ExternalCropIntentParserImpl @Inject constructor(
    private val uriPermissionChecker: ExternalCropUriPermissionChecker,
) : ExternalCropIntentParser {

    override fun parse(
        intent: Intent,
        caller: ComponentCaller,
    ): ExternalCropRequest? {
        return intent
            .takeIf {
                intent.action == ExternalCropIntentParser.ACTION_CROP
            }
            ?.let {
                getSupportedSourceUri(
                    intent = intent,
                    caller = caller,
                )
            }
            ?.let { sourceUri ->
                createExternalCropRequestForSupportedSource(
                    intent = intent,
                    caller = caller,
                    sourceUri = sourceUri,
                )
            }
    }

    private fun getSupportedSourceUri(
        intent: Intent,
        caller: ComponentCaller,
    ): Uri? {
        return intent
            .data
            ?.takeIf { uri ->
                isSupportedExternalCropSource(
                    uri = uri,
                    caller = caller,
                )
            }
    }

    private fun getRequestedOutputUri(intent: Intent): Uri? {
        return when {
            hasOutputUriExtra(intent = intent) -> getOutputUriExtra(intent = intent)
            else -> null
        }
    }

    private fun hasOutputUriExtra(intent: Intent): Boolean {
        return intent.hasExtra(MediaStore.EXTRA_OUTPUT)
    }

    private fun isValidRequestedOutputUri(
        intent: Intent,
        outputUri: Uri?,
        caller: ComponentCaller,
    ): Boolean {
        return when {
            !hasOutputUriExtra(intent = intent) -> true
            outputUri == null -> false
            else -> isSupportedExternalCropOutput(
                uri = outputUri,
                caller = caller,
            )
        }
    }

    private fun createExternalCropRequestForSupportedSource(
        intent: Intent,
        caller: ComponentCaller,
        sourceUri: Uri,
    ): ExternalCropRequest? {
        val outputUri = getRequestedOutputUri(intent = intent)

        return when {
            isValidRequestedOutputUri(
                intent = intent,
                outputUri = outputUri,
                caller = caller,
            ) -> {
                createExternalCropRequest(
                    intent = intent,
                    sourceUri = sourceUri,
                    outputUri = outputUri,
                )
            }

            else -> null
        }
    }

    private fun createExternalCropRequest(
        intent: Intent,
        sourceUri: Uri,
        outputUri: Uri?,
    ): ExternalCropRequest {
        return ExternalCropRequest(
            sourceUri = sourceUri,
            outputUri = outputUri,
            outputX = intent.getIntExtra(EXTRA_OUTPUT_X, 0),
            outputY = intent.getIntExtra(EXTRA_OUTPUT_Y, 0),
            scale = intent.getBooleanExtra(EXTRA_SCALE, true),
            scaleUpIfNeeded = intent.getBooleanExtra(EXTRA_SCALE_UP_IF_NEEDED, false),
            aspectX = intent.getIntExtra(EXTRA_ASPECT_X, 0),
            aspectY = intent.getIntExtra(EXTRA_ASPECT_Y, 0),
            returnData = intent.getBooleanExtra(EXTRA_RETURN_DATA, false),
            outputFormat = intent.getStringExtra(EXTRA_OUTPUT_FORMAT),
        )
    }

    private fun getOutputUriExtra(intent: Intent): Uri? {
        return IntentCompat.getParcelableExtra(
            intent,
            MediaStore.EXTRA_OUTPUT,
            Uri::class.java,
        )
    }

    private fun isSupportedExternalCropSource(
        uri: Uri,
        caller: ComponentCaller,
    ): Boolean {
        return when (uri.scheme) {
            ContentResolver.SCHEME_CONTENT -> {
                uriPermissionChecker.canReadContentUri(
                    uri = uri,
                    caller = caller,
                )
            }

            ContentResolver.SCHEME_FILE -> {
                uriPermissionChecker.canReadFileUri(
                    uri = uri,
                    caller = caller,
                )
            }

            else -> false
        }
    }

    private fun isSupportedExternalCropOutput(
        uri: Uri,
        caller: ComponentCaller,
    ): Boolean {
        return when (uri.scheme) {
            ContentResolver.SCHEME_CONTENT -> {
                uriPermissionChecker.canWriteContentUri(
                    uri = uri,
                    caller = caller,
                )
            }

            else -> false
        }
    }

    companion object {
        private const val EXTRA_ASPECT_X = "aspectX"
        private const val EXTRA_ASPECT_Y = "aspectY"
        private const val EXTRA_OUTPUT_FORMAT = "outputFormat"
        private const val EXTRA_OUTPUT_X = "outputX"
        private const val EXTRA_OUTPUT_Y = "outputY"
        private const val EXTRA_RETURN_DATA = "return-data"
        private const val EXTRA_SCALE = "scale"
        private const val EXTRA_SCALE_UP_IF_NEEDED = "scaleUpIfNeeded"
    }
}
