/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.core.sandbox

import android.annotation.SuppressLint
import android.content.Context

/**
 * Global access point for the forced-on isolated image decoder.
 */
@SuppressLint("StaticFieldLeak")
object SandboxedDecoderHolder {

    @Volatile
    var decoder: IsolatedImageDecoder? = null
        private set

    fun init(decoder: IsolatedImageDecoder) {
        this.decoder = decoder
    }

    fun isEnabled(@Suppress("UNUSED_PARAMETER") context: Context): Boolean {
        return true
    }
}
