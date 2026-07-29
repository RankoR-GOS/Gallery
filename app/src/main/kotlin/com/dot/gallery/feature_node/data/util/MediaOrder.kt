/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.data.util

import com.dot.gallery.feature_node.data.model.Album
import com.dot.gallery.feature_node.data.model.Media
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Persisted in the `timeline_settings` table as a polymorphic payload keyed by the serial name of
 * the concrete case. As with [OrderType], those names are pinned to the package this type used to
 * live in so that rows written by earlier versions still decode.
 */
@Serializable
sealed class MediaOrder(open val orderType: OrderType) {
    @Serializable
    @SerialName("com.dot.gallery.feature_node.domain.util.MediaOrder.Label")
    data class Label(
        @SerialName("orderType_label")
        override val orderType: OrderType
    ) : MediaOrder(orderType)

    @Serializable
    @SerialName("com.dot.gallery.feature_node.domain.util.MediaOrder.Date")
    data class Date(
        @SerialName("orderType_date")
        override val orderType: OrderType
    ) : MediaOrder(orderType)

    @Serializable
    @SerialName("com.dot.gallery.feature_node.domain.util.MediaOrder.DateModified")
    data class DateModified(
        @SerialName("orderType_date_modified")
        override val orderType: OrderType
    ) : MediaOrder(orderType)

    @Serializable
    @SerialName("com.dot.gallery.feature_node.domain.util.MediaOrder.Expiry")
    data class Expiry(
        @SerialName("orderType_expiry")
        override val orderType: OrderType = OrderType.Descending
    ): MediaOrder(orderType)

    fun <T: Media> sortMedia(media: List<T>): List<T> {
        return when (orderType) {
            OrderType.Ascending -> {
                when (this) {
                    is Date -> media.sortedBy { it.definedTimestamp }
                    is DateModified -> media.sortedBy { it.timestamp }
                    is Label -> media.sortedBy { it.label.lowercase() }
                    is Expiry -> media.sortedBy { it.expiryTimestamp ?: it.definedTimestamp }
                }
            }

            OrderType.Descending -> {
                when (this) {
                    is Date -> media.sortedByDescending { it.definedTimestamp }
                    is DateModified -> media.sortedByDescending { it.timestamp }
                    is Label -> media.sortedByDescending { it.label.lowercase() }
                    is Expiry -> media.sortedByDescending { it.expiryTimestamp ?: it.definedTimestamp }
                }
            }
        }
    }

    fun sortAlbums(albums: List<Album>): List<Album> {
        return when (orderType) {
            OrderType.Ascending -> {
                when (this) {
                    is Date -> albums.sortedBy { it.timestamp }
                    is DateModified -> albums.sortedBy { it.timestamp }
                    is Label -> albums.sortedBy { it.label.lowercase() }
                    else -> albums
                }
            }

            OrderType.Descending -> {
                when (this) {
                    is Date -> albums.sortedByDescending { it.timestamp }
                    is DateModified -> albums.sortedByDescending { it.timestamp }
                    is Label -> albums.sortedByDescending { it.label.lowercase() }
                    else -> albums
                }
            }
        }
    }

    companion object {
        val Default = Date(OrderType.Descending)
    }
}