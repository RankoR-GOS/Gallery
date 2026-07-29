package com.dot.gallery.feature_node.presentation.edit.crop

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dot.gallery.R
import com.dot.gallery.feature_node.data.externalcrop.ExternalCropIntentParser
import com.dot.gallery.ui.theme.GalleryTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class CropActivity : ComponentActivity() {

    @Inject
    internal lateinit var externalCropIntentParser: ExternalCropIntentParser

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val request = externalCropIntentParser.parse(
            intent = intent,
            caller = initialCaller,
        )

        if (request == null) {
            finishCanceled()
            return
        }

        setResult(RESULT_CANCELED)
        enableEdgeToEdge()

        setContent {
            GalleryTheme {
                CropScreen(
                    request = request,
                    onFinishCanceled = ::finishCanceled,
                    onFinishWithResult = ::finishWithResult,
                    onShowSaveErrorAndCancel = ::showSaveErrorAndCancel,
                )
            }
        }
    }

    private fun finishWithResult(resultIntent: Intent) {
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun showSaveErrorAndCancel() {
        Toast
            .makeText(this, getString(R.string.error_toast), Toast.LENGTH_SHORT)
            .show()

        finishCanceled()
    }

    private fun finishCanceled() {
        setResult(RESULT_CANCELED)
        finish()
    }
}
