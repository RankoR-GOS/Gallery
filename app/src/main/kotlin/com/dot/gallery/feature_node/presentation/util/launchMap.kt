/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.util

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.dot.gallery.R

fun Context.launchMap(lat: Double, lang: Double) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        data = "geo:0,0?q=$lat,$lang(${getString(R.string.media_location)})".toUri()
    }
    tryStartActivity(
        intent = intent,
        errorMessage = getString(R.string.error_toast),
    )
}
