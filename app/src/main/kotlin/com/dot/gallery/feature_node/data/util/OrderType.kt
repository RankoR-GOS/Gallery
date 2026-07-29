/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.data.util

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Persisted in the `timeline_settings` table and in the sort preferences, as a polymorphic payload
 * keyed by the serial name of the concrete case. The names are pinned to the package this type used
 * to live in so that data written by earlier versions still decodes — they are a storage format, not
 * a reflection of where the class sits today, and must not be updated to follow it.
 */
@Serializable
@Parcelize
sealed class OrderType : Parcelable {
    @Serializable
    @SerialName("com.dot.gallery.feature_node.domain.util.OrderType.Ascending")
    @Parcelize
    data object Ascending : OrderType()

    @Serializable
    @SerialName("com.dot.gallery.feature_node.domain.util.OrderType.Descending")
    @Parcelize
    data object Descending : OrderType()
}
