/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.core.sandbox

import android.content.ServiceConnection
import android.os.Messenger

/**
 * Holds a per-file isolated service connection.
 * Each instance represents a unique isolated process bound via
 * [android.content.Context.bindIsolatedService].
 */
data class PerFileConnection(
    val serviceConnection: ServiceConnection,
    val messenger: Messenger,
    val instanceName: String,
)
